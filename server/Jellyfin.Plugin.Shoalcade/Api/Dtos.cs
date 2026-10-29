using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.Json.Serialization;
using Jellyfin.Plugin.Shoalcade.Consoles;
using Jellyfin.Plugin.Shoalcade.Saves;
using Jellyfin.Plugin.Shoalcade.States;

namespace Jellyfin.Plugin.Shoalcade.Api;

/// <summary>An error the client can show to the person.</summary>
/// <param name="Error">Machine-readable code.</param>
/// <param name="Message">Plain-English message.</param>
public record ApiError(
    [property: JsonPropertyName("error")] string Error,
    [property: JsonPropertyName("message")] string Message);

/// <summary>A conflict: the server's current save differs from what the client last saw.</summary>
/// <param name="Error">Always "conflict".</param>
/// <param name="Message">Message.</param>
/// <param name="Current">The server's current save.</param>
public sealed record ConflictError(
    [property: JsonPropertyName("error")] string Error,
    [property: JsonPropertyName("message")] string Message,
    [property: JsonPropertyName("current")] SaveVersionDto? Current);

/// <summary>Server info for the version check (D41).</summary>
/// <param name="Server">Server version label, e.g. "s0.2" (no dev number: this is what the app compares).</param>
/// <param name="ServerVersion">Jellyfin-style version, e.g. "0.2.0.1".</param>
/// <param name="Display">What pages and the app show, e.g. "s0.2 dev 1" (dev build) or "s1.0" (release, D88).</param>
/// <param name="ApiLevel">API level; bumped on breaking changes.</param>
/// <param name="MinAppVersion">Oldest app version that works with this server, e.g. "a0.5".</param>
/// <param name="EmulatorJs">EmulatorJS version bundled, or null if missing.</param>
/// <param name="BrowserPlayer">Whether the browser player is on.</param>
/// <param name="Consoles">Known consoles.</param>
/// <param name="User">The signed-in user.</param>
/// <param name="SaveRules">How many save versions are kept (shown on the saves page).</param>
/// <param name="Features">What the server can do beyond its API level (e.g. "cheats").</param>
public sealed record InfoDto(
    [property: JsonPropertyName("server")] string Server,
    [property: JsonPropertyName("serverVersion")] string ServerVersion,
    [property: JsonPropertyName("display")] string Display,
    [property: JsonPropertyName("apiLevel")] int ApiLevel,
    [property: JsonPropertyName("minAppVersion")] string MinAppVersion,
    [property: JsonPropertyName("emulatorJs")] string? EmulatorJs,
    [property: JsonPropertyName("browserPlayer")] bool BrowserPlayer,
    [property: JsonPropertyName("consoles")] IReadOnlyList<ConsoleDto> Consoles,
    [property: JsonPropertyName("user")] UserDto User,
    [property: JsonPropertyName("saveRules")] SaveRulesDto SaveRules,
    [property: JsonPropertyName("features")] IReadOnlyList<string>? Features = null);

/// <summary>The save keep rule in force, so pages show the real numbers rather than assumed ones.</summary>
/// <param name="KeepRecent">Most recent versions always kept.</param>
/// <param name="KeepDailyDays">Days for which one version per day is kept.</param>
/// <param name="MaxPinned">Most pinned versions per game (pinned are never tidied away).</param>
/// <param name="MaxLabelLength">Longest label kept.</param>
public sealed record SaveRulesDto(
    [property: JsonPropertyName("keepRecent")] int KeepRecent,
    [property: JsonPropertyName("keepDailyDays")] int KeepDailyDays,
    [property: JsonPropertyName("maxPinned")] int MaxPinned,
    [property: JsonPropertyName("maxLabelLength")] int MaxLabelLength)
{
    /// <summary>Creates the DTO.</summary>
    /// <param name="o">Save store options.</param>
    /// <returns>The DTO.</returns>
    public static SaveRulesDto From(SaveStoreOptions o)
    {
        ArgumentNullException.ThrowIfNull(o);
        return new(o.KeepRecent, o.KeepDailyDays, o.MaxPinned, SaveStore.MaxLabelLength);
    }
}

/// <summary>The signed-in user.</summary>
/// <param name="Id">User id.</param>
/// <param name="Name">User name.</param>
/// <param name="CanDownload">Whether the user may download (needed for play).</param>
/// <param name="IsAdmin">Whether the user is a Jellyfin administrator (the library page then offers "Edit game", D122;
/// the server checks again on every edit).</param>
public sealed record UserDto(
    [property: JsonPropertyName("id")] string Id,
    [property: JsonPropertyName("name")] string Name,
    [property: JsonPropertyName("canDownload")] bool CanDownload,
    [property: JsonPropertyName("isAdmin")] bool IsAdmin);

/// <summary>A console.</summary>
/// <param name="Key">Key.</param>
/// <param name="Name">Name.</param>
/// <param name="BrowserCore">Browser core or null.</param>
/// <param name="BrowserSaves">none/shared/separate.</param>
/// <param name="SaveFormat">Save format family: saves move only between consoles with the same value (gb and gbc share "gb").</param>
/// <param name="Maker">Maker the library's console bar groups it under ("Nintendo", "Sony"), or null (its own button).</param>
public sealed record ConsoleDto(
    [property: JsonPropertyName("key")] string Key,
    [property: JsonPropertyName("name")] string Name,
    [property: JsonPropertyName("browserCore")] string? BrowserCore,
    [property: JsonPropertyName("browserSaves")] string BrowserSaves,
    [property: JsonPropertyName("saveFormat")] string SaveFormat,
    [property: JsonPropertyName("maker")] string? Maker)
{
    /// <summary>Creates the DTO.</summary>
    /// <param name="c">Console.</param>
    /// <returns>The DTO.</returns>
    public static ConsoleDto From(GameConsole c) => new(c.Key, c.DisplayName, c.BrowserCore, c.BrowserSaves.ToString().ToLowerInvariant(), c.SaveFormat, c.Maker);
}

/// <summary>A save version.</summary>
/// <param name="Version">Number.</param>
/// <param name="Size">Bytes.</param>
/// <param name="Sha256">SHA-256.</param>
/// <param name="Created">When.</param>
/// <param name="Source">Where from.</param>
/// <param name="Device">Device, if known.</param>
/// <param name="Pinned">Pinned.</param>
/// <param name="Label">Label.</param>
/// <param name="RestoredFrom">Restored from.</param>
/// <param name="IsCurrent">Whether it is the current save.</param>
/// <param name="Origin">"browser" = merged in from the old separate DS browser list (D91); else null.</param>
/// <param name="AfterSnapshot">The game saved it after a snapshot was loaded (D90).</param>
/// <param name="Cheats">The game saved it while cheats were on (D143).</param>
public sealed record SaveVersionDto(
    [property: JsonPropertyName("version")] int Version,
    [property: JsonPropertyName("size")] long Size,
    [property: JsonPropertyName("sha256")] string Sha256,
    [property: JsonPropertyName("created")] DateTimeOffset Created,
    [property: JsonPropertyName("source")] string Source,
    [property: JsonPropertyName("device")] string? Device,
    [property: JsonPropertyName("pinned")] bool Pinned,
    [property: JsonPropertyName("label")] string? Label,
    [property: JsonPropertyName("restoredFrom")] int? RestoredFrom,
    [property: JsonPropertyName("isCurrent")] bool IsCurrent,
    [property: JsonPropertyName("origin")] string? Origin,
    [property: JsonPropertyName("afterSnapshot")] bool AfterSnapshot,
    [property: JsonPropertyName("cheats")] bool Cheats = false)
{
    /// <summary>Creates the DTO.</summary>
    /// <param name="v">Version.</param>
    /// <param name="current">Current version number.</param>
    /// <returns>The DTO, or null.</returns>
    public static SaveVersionDto? From(SaveVersion? v, int? current) =>
        v is null ? null : new(v.Version, v.Size, v.Sha256, v.Created, v.Source, v.Device, v.Pinned, v.Label, v.RestoredFrom, current == v.Version, v.Origin, v.AfterSnapshot == true, v.Cheats == true);
}

/// <summary>A game's save history.</summary>
/// <param name="ItemId">Item.</param>
/// <param name="Channel">Always "main" (kept for app 0.5).</param>
/// <param name="Current">Current version number (0 = none).</param>
/// <param name="CurrentVersion">Current version.</param>
/// <param name="Versions">All versions, newest first by time (merged and moved versions keep their own times).</param>
public sealed record SaveHistoryDto(
    [property: JsonPropertyName("itemId")] string ItemId,
    [property: JsonPropertyName("channel")] string Channel,
    [property: JsonPropertyName("current")] int Current,
    [property: JsonPropertyName("currentVersion")] SaveVersionDto? CurrentVersion,
    [property: JsonPropertyName("versions")] IReadOnlyList<SaveVersionDto> Versions)
{
    /// <summary>The one save list's channel name (D91), still sent for app 0.5.</summary>
    public const string MainChannel = "main";

    /// <summary>Creates the DTO.</summary>
    /// <param name="h">History.</param>
    /// <param name="item">Item id.</param>
    /// <returns>The DTO.</returns>
    public static SaveHistoryDto From(SaveHistory h, Guid item) => new(
        item.ToString("N"),
        MainChannel,
        h.Current ?? 0,
        SaveVersionDto.From(h.CurrentVersion(), h.Current),
        h.Versions.OrderByDescending(v => v.Created).ThenByDescending(v => v.Version).Select(v => SaveVersionDto.From(v, h.Current)!).ToList());
}

/// <summary>Result of a save write.</summary>
/// <param name="Status">created/unchanged.</param>
/// <param name="Version">Version stored or matched.</param>
/// <param name="Current">Current after the call.</param>
/// <param name="Warnings">Warnings.</param>
public sealed record SaveWriteDto(
    [property: JsonPropertyName("status")] string Status,
    [property: JsonPropertyName("version")] SaveVersionDto? Version,
    [property: JsonPropertyName("current")] SaveVersionDto? Current,
    [property: JsonPropertyName("warnings")] IReadOnlyList<string> Warnings);

/// <summary>One game in the library list.</summary>
/// <param name="Id">Item id.</param>
/// <param name="Name">Name.</param>
/// <param name="SortName">Sort name.</param>
/// <param name="Console">Console key.</param>
/// <param name="Region">Region.</param>
/// <param name="Series">Series.</param>
/// <param name="Year">Year.</param>
/// <param name="ImageTag">Cover cache tag, or null if no cover.</param>
/// <param name="LastPlayed">Last played (Jellyfin play data).</param>
/// <param name="BrowserPlayable">Whether it plays in the browser.</param>
/// <param name="Save">Current save, if any.</param>
/// <param name="Engine">RPG Maker only (D147): "XP", "VX" or "VX Ace" (null when it can't be told); left out for others.</param>
/// <param name="MayNotWork">RPG Maker only: the game ships Windows DLLs we don't recognise; left out for others.</param>
/// <param name="Size">RPG Maker only: bytes of all its listed files (the download size); left out for others.</param>
/// <param name="FileCount">RPG Maker only: files listed; left out for others.</param>
public sealed record GameDto(
    [property: JsonPropertyName("id")] string Id,
    [property: JsonPropertyName("name")] string Name,
    [property: JsonPropertyName("sortName")] string SortName,
    [property: JsonPropertyName("console")] string Console,
    [property: JsonPropertyName("region")] string? Region,
    [property: JsonPropertyName("series")] string? Series,
    [property: JsonPropertyName("year")] int? Year,
    [property: JsonPropertyName("imageTag")] string? ImageTag,
    [property: JsonPropertyName("lastPlayed")] DateTime? LastPlayed,
    [property: JsonPropertyName("browserPlayable")] bool BrowserPlayable,
    [property: JsonPropertyName("save")] SaveVersionDto? Save,
    [property: JsonPropertyName("engine"), JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] string? Engine = null,
    [property: JsonPropertyName("mayNotWork"), JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] bool? MayNotWork = null,
    [property: JsonPropertyName("size"), JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] long? Size = null,
    [property: JsonPropertyName("fileCount"), JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] int? FileCount = null);

/// <summary>One entry in the save manager list.</summary>
/// <param name="ItemId">Item id.</param>
/// <param name="Channel">Always "main" (kept for app 0.5).</param>
/// <param name="GameName">Name (live, or stored if the game was removed).</param>
/// <param name="Console">Console key.</param>
/// <param name="Status">ok / removed (game no longer in the library).</param>
/// <param name="Current">Current version.</param>
/// <param name="VersionCount">Versions kept.</param>
/// <param name="TotalBytes">Bytes used.</param>
/// <param name="ImageTag">Cover tag.</param>
/// <param name="SnapshotCount">Browser snapshot slots filled (F8: a removed game with only snapshots is listed too, with
/// no versions).</param>
public sealed record SaveListEntryDto(
    [property: JsonPropertyName("itemId")] string ItemId,
    [property: JsonPropertyName("channel")] string Channel,
    [property: JsonPropertyName("gameName")] string? GameName,
    [property: JsonPropertyName("console")] string? Console,
    [property: JsonPropertyName("status")] string Status,
    [property: JsonPropertyName("current")] SaveVersionDto? Current,
    [property: JsonPropertyName("versionCount")] int VersionCount,
    [property: JsonPropertyName("totalBytes")] long TotalBytes,
    [property: JsonPropertyName("imageTag")] string? ImageTag,
    [property: JsonPropertyName("snapshotCount")] int SnapshotCount = 0);

/// <summary>The save manager list with storage use.</summary>
/// <param name="Saves">Entries.</param>
/// <param name="UsedBytes">Save storage used.</param>
/// <param name="QuotaBytes">Save storage allowed (0 = no limit).</param>
/// <param name="SnapshotBytes">Snapshot storage used.</param>
/// <param name="SnapshotQuotaBytes">Snapshot storage allowed.</param>
public sealed record SaveListDto(
    [property: JsonPropertyName("saves")] IReadOnlyList<SaveListEntryDto> Saves,
    [property: JsonPropertyName("usedBytes")] long UsedBytes,
    [property: JsonPropertyName("quotaBytes")] long QuotaBytes,
    [property: JsonPropertyName("snapshotBytes")] long SnapshotBytes,
    [property: JsonPropertyName("snapshotQuotaBytes")] long SnapshotQuotaBytes);

/// <summary>Pin/label change.</summary>
public sealed class VersionUpdateDto
{
    /// <summary>Gets or sets the pin state (null = unchanged).</summary>
    [JsonPropertyName("pinned")]
    public bool? Pinned { get; set; }

    /// <summary>Gets or sets the label (null = unchanged, "" = clear).</summary>
    [JsonPropertyName("label")]
    public string? Label { get; set; }
}

/// <summary>Snapshot list.</summary>
/// <param name="Snapshots">Filled slots.</param>
/// <param name="Enabled">Whether snapshots are on for this console (D38).</param>
public sealed record StateListDto(
    [property: JsonPropertyName("snapshots")] IReadOnlyList<StateInfo> Snapshots,
    [property: JsonPropertyName("enabled")] bool Enabled);

/// <summary>What the Jellyfin web side menu needs (D67).</summary>
/// <param name="HasGames">Whether the user can see at least one game (so the Shoalcade link is shown).</param>
public sealed record WebLinksDto(
    [property: JsonPropertyName("hasGames")] bool HasGames);

/// <summary>What a Jellyfin web item page needs (D67). Same answer for "not a game", "hidden" and "doesn't exist".</summary>
/// <param name="IsGame">Whether the item is a game the user can see (show Saves).</param>
/// <param name="Playable">Whether it plays in the browser (show Play).</param>
public sealed record WebLinksItemDto(
    [property: JsonPropertyName("isGame")] bool IsGame,
    [property: JsonPropertyName("playable")] bool Playable);

/// <summary>A game as the admin game editor sees it (D122).</summary>
/// <param name="Id">Item id.</param>
/// <param name="Name">Name shown now.</param>
/// <param name="Console">Console key.</param>
/// <param name="NameEdited">Whether an admin gave the name (it is locked against scans).</param>
/// <param name="CoverSource">"upload" or "libretro" if an admin gave the cover, else null (automatic).</param>
/// <param name="CoverFrom">The libretro name a picked cover came from, if any.</param>
/// <param name="ImageTag">Cover image tag (changes with the picture), or null.</param>
/// <param name="CanFindCovers">Whether "Find a cover" works now (not while JellyEMU is installed, not for consoles libretro has no covers for).</param>
/// <param name="NameLockedInJellyfin">The name is locked in Jellyfin's own metadata editor (not by Shoalcade), so Reset keeps it (G6 L2b).</param>
public sealed record GameEditDto(
    [property: JsonPropertyName("id")] string Id,
    [property: JsonPropertyName("name")] string Name,
    [property: JsonPropertyName("console")] string Console,
    [property: JsonPropertyName("nameEdited")] bool NameEdited,
    [property: JsonPropertyName("coverSource")] string? CoverSource,
    [property: JsonPropertyName("coverFrom")] string? CoverFrom,
    [property: JsonPropertyName("imageTag")] string? ImageTag,
    [property: JsonPropertyName("canFindCovers")] bool CanFindCovers,
    [property: JsonPropertyName("nameLockedInJellyfin")] bool NameLockedInJellyfin = false);

/// <summary>Body for renaming a game or picking a libretro cover.</summary>
public sealed class GameEditNameDto
{
    /// <summary>Gets or sets the name (a new game name, or the libretro name of a cover).</summary>
    [JsonPropertyName("name")]
    public string? Name { get; set; }
}

/// <summary>Cover search results.</summary>
/// <param name="Names">libretro names, best first.</param>
public sealed record CoverSearchDto(
    [property: JsonPropertyName("names")] IReadOnlyList<string> Names);
