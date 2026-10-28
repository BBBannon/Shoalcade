using System;
using System.Collections.Generic;
using System.Text.Json.Serialization;

namespace Jellyfin.Plugin.Shoalcade.Saves;

/// <summary>How an upload treats the current save.</summary>
public enum PutMode
{
    /// <summary>Only succeeds if the client saw the current version (else 409 conflict).</summary>
    Normal,

    /// <summary>The person chose this copy: it becomes current, the old current stays in history.</summary>
    Force,

    /// <summary>Kept in history only (e.g. the losing side of a conflict). Current is unchanged.</summary>
    HistoryOnly,
}

/// <summary>Outcome of a write.</summary>
public enum SaveWriteStatus
{
    /// <summary>A new version was stored.</summary>
    Created,

    /// <summary>Same bytes as the current save: nothing new stored.</summary>
    Unchanged,

    /// <summary>The server has a different current save than the client last saw.</summary>
    Conflict,

    /// <summary>Refused (size, blank data, quota, bad version, ...).</summary>
    Rejected,

    /// <summary>No such version.</summary>
    NotFound,
}

/// <summary>One stored version of a save. Version files are written once and never changed.</summary>
public sealed class SaveVersion
{
    /// <summary>Gets or sets the version number (1, 2, 3...). Never reused.</summary>
    [JsonPropertyName("v")]
    public int Version { get; set; }

    /// <summary>Gets or sets the file name inside the game folder.</summary>
    [JsonPropertyName("file")]
    public string File { get; set; } = string.Empty;

    /// <summary>Gets or sets the size in bytes.</summary>
    [JsonPropertyName("size")]
    public long Size { get; set; }

    /// <summary>Gets or sets the SHA-256 of the bytes (lower-case hex).</summary>
    [JsonPropertyName("sha256")]
    public string Sha256 { get; set; } = string.Empty;

    /// <summary>Gets or sets when it was stored (UTC).</summary>
    [JsonPropertyName("created")]
    public DateTimeOffset Created { get; set; }

    /// <summary>Gets or sets where it came from: browser, app, upload, restore, jellyemu, moved, recovered.</summary>
    [JsonPropertyName("source")]
    public string Source { get; set; } = string.Empty;

    /// <summary>Gets or sets a value indicating whether the person pinned it (never auto-deleted).</summary>
    [JsonPropertyName("pinned")]
    public bool Pinned { get; set; }

    /// <summary>Gets or sets the person's label, if any.</summary>
    [JsonPropertyName("label")]
    public string? Label { get; set; }

    /// <summary>Gets or sets the version this was restored from, if it is a restore.</summary>
    [JsonPropertyName("restoredFrom")]
    public int? RestoredFrom { get; set; }

    /// <summary>Gets or sets the device or client that sent it, if known (e.g. "Shoalcade Android").</summary>
    [JsonPropertyName("device")]
    public string? Device { get; set; }

    /// <summary>Gets or sets where the version was kept before it joined this list: <see cref="SaveStore.BrowserOrigin"/>
    /// = it was in the DS game's separate browser save list (D52), merged into the one list by D91. Null = always here.</summary>
    [JsonPropertyName("origin")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Origin { get; set; }

    /// <summary>Gets or sets a value indicating whether the game saved this after a snapshot was loaded (D90): it may be
    /// older game progress than the version before it. True or null (null = a normal save).</summary>
    [JsonPropertyName("afterSnapshot")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public bool? AfterSnapshot { get; set; }
}

/// <summary>A JellyEMU save file we already imported (D40), so it is never imported twice.</summary>
public sealed class ImportMarker
{
    /// <summary>Gets or sets the JellyEMU file, relative to its saves folder.</summary>
    [JsonPropertyName("source")]
    public string Source { get; set; } = string.Empty;

    /// <summary>Gets or sets the SHA-256 of what was imported.</summary>
    [JsonPropertyName("sha256")]
    public string Sha256 { get; set; } = string.Empty;

    /// <summary>Gets or sets the JellyEMU file's last-modified time when imported.</summary>
    [JsonPropertyName("modified")]
    public DateTimeOffset Modified { get; set; }
}

/// <summary>The <c>history.json</c> of one game's save (one per user and game).</summary>
public sealed class SaveHistory
{
    /// <summary>Gets or sets the file format version.</summary>
    [JsonPropertyName("schema")]
    public int Schema { get; set; } = 1;

    /// <summary>Gets or sets the Jellyfin item id (N format).</summary>
    [JsonPropertyName("itemId")]
    public string ItemId { get; set; } = string.Empty;

    /// <summary>Gets or sets the channel. Always "main" since D91 (one save list per game); the old DS
    /// <c>browser</c> list is merged in (see <see cref="BrowserMerged"/>).</summary>
    [JsonPropertyName("channel")]
    public string Channel { get; set; } = "main";

    /// <summary>Gets or sets when the old separate DS browser save list (D52) was merged into this one (D91),
    /// or null if it never had one. Set in the same write that lists the merged versions, so the merge runs once.</summary>
    [JsonPropertyName("browserMerged")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public DateTimeOffset? BrowserMerged { get; set; }

    /// <summary>Gets or sets the game's name when last saved (shown if the game leaves the library).</summary>
    [JsonPropertyName("gameName")]
    public string? GameName { get; set; }

    /// <summary>Gets or sets the console key.</summary>
    [JsonPropertyName("console")]
    public string? Console { get; set; }

    /// <summary>Gets or sets the current version number, or null if there is no current save.</summary>
    [JsonPropertyName("current")]
    public int? Current { get; set; }

    /// <summary>Gets or sets the next version number to use.</summary>
    [JsonPropertyName("nextVersion")]
    public int NextVersion { get; set; } = 1;

    /// <summary>Gets the versions, oldest first.</summary>
    [JsonPropertyName("versions")]
    public List<SaveVersion> Versions { get; init; } = [];

    /// <summary>Gets the JellyEMU files already imported.</summary>
    [JsonPropertyName("imports")]
    public List<ImportMarker> Imports { get; init; } = [];

    /// <summary>Gets version files to delete once the new history.json is safely written (never before).</summary>
    [JsonIgnore]
    internal List<SaveVersion> PendingDeletes { get; } = [];

    /// <summary>Gets the current version entry, if any.</summary>
    /// <returns>The current version or null.</returns>
    public SaveVersion? CurrentVersion() => Current is int c ? Versions.Find(v => v.Version == c) : null;
}

/// <summary>Result of a save write.</summary>
/// <param name="Status">What happened.</param>
/// <param name="Version">The version stored (or the matching existing one).</param>
/// <param name="Current">The server's current version after the call.</param>
/// <param name="Error">Why it was refused, in plain words.</param>
/// <param name="ErrorCode">Machine-readable reason: size, blank, quota, version, pinned, current, label.</param>
/// <param name="Warnings">Things worth telling the person (e.g. unusual size).</param>
public sealed record SaveWriteResult(
    SaveWriteStatus Status,
    SaveVersion? Version,
    SaveVersion? Current,
    string? Error,
    string? ErrorCode,
    IReadOnlyList<string> Warnings);

/// <summary>
/// The save a person was shown when they chose "Keep the phone's" (L2): a forced upload only goes ahead if Jellyfin's
/// save in use is still exactly this one.
/// </summary>
/// <param name="Version">Version shown (0 = Jellyfin had no save in use).</param>
/// <param name="Sha256">Its SHA-256, if known.</param>
public sealed record SaveExpectation(int Version, string? Sha256)
{
    /// <summary>True if <paramref name="current"/> is the save that was shown.</summary>
    /// <param name="current">The save in use now (null = none).</param>
    /// <returns>Whether it matches.</returns>
    public bool Matches(SaveVersion? current) =>
        (current?.Version ?? 0) == Version
        && (Sha256 is null || current is null || string.Equals(current.Sha256, Sha256, System.StringComparison.OrdinalIgnoreCase));
}

/// <summary>Summary of one game's save for the save manager list.</summary>
/// <param name="ItemId">Jellyfin item id.</param>
/// <param name="GameName">Stored name.</param>
/// <param name="Console">Console key.</param>
/// <param name="Current">Current version, if any.</param>
/// <param name="VersionCount">Number of versions kept.</param>
/// <param name="TotalBytes">Bytes used by all versions.</param>
public sealed record SaveSummary(
    Guid ItemId,
    string? GameName,
    string? Console,
    SaveVersion? Current,
    int VersionCount,
    long TotalBytes);
