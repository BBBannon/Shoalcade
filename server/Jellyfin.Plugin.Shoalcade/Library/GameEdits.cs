using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Plugin.Shoalcade.Common;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Shoalcade.Library;

/// <summary>An admin's cover for a game (D122).</summary>
public sealed class GameEditCover
{
    /// <summary>Gets or sets the file Jellyfin shows (<c>covers/{id}-{hash}.png</c>), name only.</summary>
    [JsonPropertyName("file")]
    public string File { get; set; } = string.Empty;

    /// <summary>Gets or sets the SHA-256 of the picture (lower-case hex).</summary>
    [JsonPropertyName("sha256")]
    public string Sha256 { get; set; } = string.Empty;

    /// <summary>Gets or sets the media type.</summary>
    [JsonPropertyName("type")]
    public string Type { get; set; } = string.Empty;

    /// <summary>Gets or sets where it came from: "upload" or "libretro".</summary>
    [JsonPropertyName("source")]
    public string Source { get; set; } = string.Empty;

    /// <summary>Gets or sets the libretro name it was found under (libretro covers only).</summary>
    [JsonPropertyName("from")]
    public string? From { get; set; }
}

/// <summary>What an admin changed on one game (D122). Kept so scans and "Replace all metadata" can't undo it, and so
/// "Reset to automatic" knows exactly what to undo.</summary>
public sealed class GameEdit
{
    /// <summary>Gets or sets the name the admin gave, or null if the name wasn't changed.</summary>
    [JsonPropertyName("name")]
    public string? Name { get; set; }

    /// <summary>Gets or sets a value indicating whether Shoalcade added Jellyfin's Name lock (so Reset removes only
    /// a lock it added, never one an admin set in Jellyfin's own editor).</summary>
    [JsonPropertyName("nameLockAdded")]
    public bool NameLockAdded { get; set; }

    /// <summary>Gets or sets the admin's cover, or null.</summary>
    [JsonPropertyName("cover")]
    public GameEditCover? Cover { get; set; }

    /// <summary>Gets or sets when it was last changed.</summary>
    [JsonPropertyName("edited")]
    public DateTimeOffset Edited { get; set; }

    /// <summary>Gets or sets who last changed it (Jellyfin user id).</summary>
    [JsonPropertyName("by")]
    public string? By { get; set; }
}

/// <summary>
/// The admin game editor's files (D122), all in the plugin's data folder, never next to the ROMs:
/// <list type="bullet">
/// <item><c>edits/{id}.json</c>: what was changed (<see cref="GameEdit"/>).</item>
/// <item><c>edits/{id}.cover</c>: the cover as given (the master copy, never handed to Jellyfin).</item>
/// <item><c>covers/{id}-{hash}.{ext}</c>: the copy Jellyfin's item points at. Outside Jellyfin's metadata folder, so
/// "Replace all metadata" with "Replace existing images" doesn't delete it; if anything does, it is made again from the
/// master copy.</item>
/// </list>
/// Callers hold the item's lock (<see cref="Locks"/>) around changes.
/// </summary>
public sealed class GameEdits
{
    private readonly string _edits;
    private readonly string _covers;
    private readonly ILogger _logger;

    /// <summary>Initializes a new instance of the <see cref="GameEdits"/> class.</summary>
    /// <param name="root">The plugin's data folder (<c>shoalcade</c>).</param>
    /// <param name="logger">Logger.</param>
    public GameEdits(string root, ILogger logger)
    {
        _edits = Path.Combine(Path.GetFullPath(root), "edits");
        _covers = Path.Combine(Path.GetFullPath(root), "covers");
        _logger = logger;
    }

    /// <summary>Gets the per-game locks (one change to a game at a time).</summary>
    internal KeyedLocks Locks { get; } = new();

    /// <summary>Gets the folder holding the files Jellyfin shows.</summary>
    public string CoversFolder => _covers;

    /// <summary>What was changed on a game, or null if nothing (a damaged record counts as nothing and is logged).</summary>
    /// <param name="id">Item id.</param>
    /// <returns>The record.</returns>
    public GameEdit? Get(Guid id)
    {
        var path = RecordPath(id);
        try
        {
            return File.Exists(path) ? JsonSerializer.Deserialize<GameEdit>(File.ReadAllBytes(path)) : null;
        }
        catch (FileNotFoundException)
        {
            return null; // reset (Delete) between the check and the read: nothing changed on the game, not damage
        }
        catch (Exception ex) when (ex is IOException or JsonException or UnauthorizedAccessException)
        {
            _logger.LogWarning("Could not read the game edit record {Path}: {Message}", path, ex.Message);
            return null;
        }
    }

    /// <summary>Every game with an edit record.</summary>
    /// <returns>Item ids.</returns>
    public IReadOnlyList<Guid> EditedIds() =>
        !Directory.Exists(_edits)
            ? []
            : Directory.EnumerateFiles(_edits, "*.json")
                .Select(f => Guid.TryParseExact(Path.GetFileNameWithoutExtension(f), "N", out var g) ? g : Guid.Empty)
                .Where(g => g != Guid.Empty)
                .ToList();

    /// <summary>True if an admin gave this game its cover (box art then leaves it alone).</summary>
    /// <param name="id">Item id.</param>
    /// <returns>Whether it has one.</returns>
    public bool HasCover(Guid id) => Get(id)?.Cover is not null;

    /// <summary>Writes the record (whole file swapped in at once).</summary>
    /// <param name="id">Item id.</param>
    /// <param name="edit">Record.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>A task.</returns>
    public Task SaveAsync(Guid id, GameEdit edit, CancellationToken cancellationToken) =>
        AtomicFile.WriteAsync(RecordPath(id), JsonSerializer.SerializeToUtf8Bytes(edit), true, cancellationToken);

    /// <summary>Stores a checked cover: the master copy, then the file Jellyfin will show.</summary>
    /// <param name="id">Item id.</param>
    /// <param name="data">Picture bytes (already checked by <see cref="CoverImage.Check"/>).</param>
    /// <param name="info">What it is.</param>
    /// <param name="source">"upload" or "libretro".</param>
    /// <param name="from">libretro name, if any.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>The cover entry for the record.</returns>
    public async Task<GameEditCover> WriteCoverAsync(Guid id, byte[] data, CoverInfo info, string source, string? from, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(data);
        ArgumentNullException.ThrowIfNull(info);
        var sha = Convert.ToHexStringLower(SHA256.HashData(data));
        var cover = new GameEditCover
        {
            File = N(id) + "-" + sha[..16] + info.Extension,
            Sha256 = sha,
            Type = info.MediaType,
            Source = source,
            From = from,
        };
        await AtomicFile.WriteAsync(MasterPath(id), data, true, cancellationToken).ConfigureAwait(false);
        await AtomicFile.WriteAsync(LivePath(cover), data, true, cancellationToken).ConfigureAwait(false);
        return cover;
    }

    /// <summary>The full path of the file Jellyfin shows for a cover.</summary>
    /// <param name="cover">Cover entry.</param>
    /// <returns>Path inside <see cref="CoversFolder"/>.</returns>
    public string LivePath(GameEditCover cover)
    {
        ArgumentNullException.ThrowIfNull(cover);
        var name = Path.GetFileName(cover.File);
        if (string.IsNullOrEmpty(name) || name != cover.File)
        {
            throw new InvalidDataException("Bad cover file name in a game edit record.");
        }

        return Path.Combine(_covers, name);
    }

    /// <summary>
    /// Makes sure the file Jellyfin shows exists and is the right picture, copying it again from the master copy if it
    /// was removed. Returns its path, or null if the master copy is missing or damaged (logged).
    /// </summary>
    /// <param name="id">Item id.</param>
    /// <param name="cover">Cover entry.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>The path, or null.</returns>
    public async Task<string?> EnsureLiveAsync(Guid id, GameEditCover cover, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(cover);
        var live = LivePath(cover);
        if (File.Exists(live) && Matches(await File.ReadAllBytesAsync(live, cancellationToken).ConfigureAwait(false), cover.Sha256))
        {
            return live;
        }

        var master = MasterPath(id);
        var data = File.Exists(master) ? await File.ReadAllBytesAsync(master, cancellationToken).ConfigureAwait(false) : null;
        if (data is null || !Matches(data, cover.Sha256))
        {
            _logger.LogWarning("The cover kept for game {Id} is missing or damaged; it can be set again in the Shoalcade library", id);
            return null;
        }

        await AtomicFile.WriteAsync(live, data, true, cancellationToken).ConfigureAwait(false);
        return live;
    }

    /// <summary>Removes old cover files for a game, keeping <paramref name="keep"/> (null = remove all).</summary>
    /// <param name="id">Item id.</param>
    /// <param name="keep">File name to keep.</param>
    public void RemoveOldCovers(Guid id, string? keep)
    {
        if (!Directory.Exists(_covers))
        {
            return;
        }

        foreach (var f in Directory.EnumerateFiles(_covers, N(id) + "-*").Where(f => !string.Equals(Path.GetFileName(f), keep, StringComparison.Ordinal)))
        {
            TryDelete(f);
        }
    }

    /// <summary>Removes everything kept for a game (record, master copy, shown copies). Record first: a crash part-way
    /// leaves only stray picture files, never a record pointing at a missing picture.</summary>
    /// <param name="id">Item id.</param>
    public void Delete(Guid id)
    {
        TryDelete(RecordPath(id));
        TryDelete(MasterPath(id));
        RemoveOldCovers(id, null);
    }

    /// <summary>Removes the cover only (master copy and shown copies are removed after the record is saved).</summary>
    /// <param name="id">Item id.</param>
    public void DeleteCoverFiles(Guid id)
    {
        TryDelete(MasterPath(id));
        RemoveOldCovers(id, null);
    }

    private static bool Matches(byte[] data, string sha) =>
        string.Equals(Convert.ToHexStringLower(SHA256.HashData(data)), sha, StringComparison.Ordinal);

    private static string N(Guid id) => id.ToString("N");

    private string RecordPath(Guid id) => Path.Combine(_edits, N(id) + ".json");

    private string MasterPath(Guid id) => Path.Combine(_edits, N(id) + ".cover");

    private void TryDelete(string path)
    {
        try
        {
            File.Delete(path);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            _logger.LogInformation("Could not remove {Path} ({Message}); it is left for later", path, ex.Message);
        }
    }
}
