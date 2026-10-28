using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Enumeration;
using System.Linq;
using System.Text.Json;
using System.Text.Json.Serialization;
using Jellyfin.Plugin.Shoalcade.Common;
using Jellyfin.Plugin.Shoalcade.Saves;

namespace Jellyfin.Plugin.Shoalcade.Storage;

/// <summary>A game as the library knows it, for the storage report.</summary>
/// <param name="Name">Game name.</param>
/// <param name="Console">Console key.</param>
public sealed record StorageGameRef(string Name, string? Console);

/// <summary>Storage used by one user for one game. Sizes and counts only, never contents.</summary>
/// <param name="ItemId">Jellyfin item id (N format).</param>
/// <param name="Name">Game name (from the library, else from the save history; null if unknown).</param>
/// <param name="Console">Console key, if known.</param>
/// <param name="InLibrary">Whether this user can still see the game in the library.</param>
/// <param name="SaveBytes">Bytes in the game's save folder (all versions and the history file).</param>
/// <param name="VersionCount">Save versions kept.</param>
/// <param name="SnapshotBytes">Bytes in the game's snapshot folder (snapshots, pictures, info files, backups).</param>
/// <param name="SnapshotCount">Snapshot slots filled.</param>
/// <param name="PinnedCount">Pinned save versions.</param>
/// <param name="PinnedBytes">Bytes of pinned save versions.</param>
/// <param name="MovedCount">Save versions moved in from another game.</param>
/// <param name="MovedBytes">Bytes of moved-in save versions.</param>
/// <param name="TotalBytes">Saves + snapshots.</param>
/// <param name="Unreadable">A folder of this game couldn't be read (permissions, disk error), so its figures are too low (L12).</param>
public sealed record GameStorageDto(
    [property: JsonPropertyName("itemId")] string ItemId,
    [property: JsonPropertyName("name")] string? Name,
    [property: JsonPropertyName("console")] string? Console,
    [property: JsonPropertyName("inLibrary")] bool InLibrary,
    [property: JsonPropertyName("saveBytes")] long SaveBytes,
    [property: JsonPropertyName("versionCount")] int VersionCount,
    [property: JsonPropertyName("snapshotBytes")] long SnapshotBytes,
    [property: JsonPropertyName("snapshotCount")] int SnapshotCount,
    [property: JsonPropertyName("pinnedCount")] int PinnedCount,
    [property: JsonPropertyName("pinnedBytes")] long PinnedBytes,
    [property: JsonPropertyName("movedCount")] int MovedCount,
    [property: JsonPropertyName("movedBytes")] long MovedBytes,
    [property: JsonPropertyName("totalBytes")] long TotalBytes,
    [property: JsonPropertyName("unreadable")] bool Unreadable = false);

/// <summary>Bytes of one settings group (a console key or "global").</summary>
/// <param name="Scope">Settings group.</param>
/// <param name="Bytes">Bytes (including any kept copy of a damaged file).</param>
public sealed record SettingsStorageDto(
    [property: JsonPropertyName("scope")] string Scope,
    [property: JsonPropertyName("bytes")] long Bytes);

/// <summary>Storage used by one user. The saves and snapshot figures are the same ones the quotas are checked against.</summary>
/// <param name="UserId">User id (N format).</param>
/// <param name="Name">Jellyfin user name, or null if the account no longer exists.</param>
/// <param name="SaveBytes">Save storage used.</param>
/// <param name="SnapshotBytes">Snapshot storage used (snapshots + pictures).</param>
/// <param name="SettingsBytes">Settings storage used.</param>
/// <param name="TotalBytes">All three.</param>
/// <param name="PinnedCount">Pinned save versions.</param>
/// <param name="PinnedBytes">Bytes of pinned save versions.</param>
/// <param name="MovedCount">Moved-in save versions.</param>
/// <param name="MovedBytes">Bytes of moved-in save versions.</param>
/// <param name="Games">Per-game breakdown, biggest first.</param>
/// <param name="Settings">Settings groups.</param>
/// <param name="Unreadable">Some of this user's folders couldn't be read, so the figures are too low (L12).</param>
public sealed record UserStorageDto(
    [property: JsonPropertyName("userId")] string UserId,
    [property: JsonPropertyName("name")] string? Name,
    [property: JsonPropertyName("saveBytes")] long SaveBytes,
    [property: JsonPropertyName("snapshotBytes")] long SnapshotBytes,
    [property: JsonPropertyName("settingsBytes")] long SettingsBytes,
    [property: JsonPropertyName("totalBytes")] long TotalBytes,
    [property: JsonPropertyName("pinnedCount")] int PinnedCount,
    [property: JsonPropertyName("pinnedBytes")] long PinnedBytes,
    [property: JsonPropertyName("movedCount")] int MovedCount,
    [property: JsonPropertyName("movedBytes")] long MovedBytes,
    [property: JsonPropertyName("games")] IReadOnlyList<GameStorageDto> Games,
    [property: JsonPropertyName("settings")] IReadOnlyList<SettingsStorageDto> Settings,
    [property: JsonPropertyName("unreadable")] bool Unreadable = false);

/// <summary>Server totals across every user.</summary>
/// <param name="Users">Users listed.</param>
/// <param name="Games">User-and-game pairs with anything stored.</param>
/// <param name="GamesNotInLibrary">Of those, games the user can no longer see in the library.</param>
/// <param name="SaveBytes">Save storage used.</param>
/// <param name="SnapshotBytes">Snapshot storage used.</param>
/// <param name="SettingsBytes">Settings storage used.</param>
/// <param name="TotalBytes">All three.</param>
/// <param name="PinnedCount">Pinned save versions.</param>
/// <param name="PinnedBytes">Bytes of pinned save versions.</param>
/// <param name="MovedCount">Moved-in save versions.</param>
/// <param name="MovedBytes">Bytes of moved-in save versions.</param>
/// <param name="Unreadable">Users with a folder that couldn't be read (their figures are too low, L12).</param>
public sealed record StorageTotalsDto(
    [property: JsonPropertyName("users")] int Users,
    [property: JsonPropertyName("games")] int Games,
    [property: JsonPropertyName("gamesNotInLibrary")] int GamesNotInLibrary,
    [property: JsonPropertyName("saveBytes")] long SaveBytes,
    [property: JsonPropertyName("snapshotBytes")] long SnapshotBytes,
    [property: JsonPropertyName("settingsBytes")] long SettingsBytes,
    [property: JsonPropertyName("totalBytes")] long TotalBytes,
    [property: JsonPropertyName("pinnedCount")] int PinnedCount,
    [property: JsonPropertyName("pinnedBytes")] long PinnedBytes,
    [property: JsonPropertyName("movedCount")] int MovedCount,
    [property: JsonPropertyName("movedBytes")] long MovedBytes,
    [property: JsonPropertyName("unreadable")] int Unreadable = 0);

/// <summary>The admin storage report (view only).</summary>
/// <param name="SaveQuotaBytes">Save storage allowed per user (0 = no limit).</param>
/// <param name="SnapshotQuotaBytes">Snapshot storage allowed per user (0 = no limit).</param>
/// <param name="Totals">Server totals.</param>
/// <param name="Users">Every user, biggest first.</param>
public sealed record StorageReportDto(
    [property: JsonPropertyName("saveQuotaBytes")] long SaveQuotaBytes,
    [property: JsonPropertyName("snapshotQuotaBytes")] long SnapshotQuotaBytes,
    [property: JsonPropertyName("totals")] StorageTotalsDto Totals,
    [property: JsonPropertyName("users")] IReadOnlyList<UserStorageDto> Users);

/// <summary>
/// Works out how much storage each user uses (item 9 of s0.2). Only walks the folders and reads file sizes, plus each
/// game's small <c>history.json</c> (for pinned and moved-in counts): never a save, snapshot or settings file's contents.
/// Takes no locks, so saving is never held up; a file added or removed mid-scan is simply counted or not.
/// </summary>
public sealed class StorageReport
{
    private static readonly EnumerationOptions FileWalk = DiskUsage.AllFiles; // the same walk as the quota checks

    private static readonly EnumerationOptions TopOnly = new()
    {
        IgnoreInaccessible = true,
        AttributesToSkip = 0,
        MatchType = MatchType.Simple,
    };

    private readonly string _saves;
    private readonly string _states;
    private readonly string _prefs;

    /// <summary>Initializes a new instance of the <see cref="StorageReport"/> class.</summary>
    /// <param name="savesRoot">Saves root folder.</param>
    /// <param name="statesRoot">Snapshots root folder.</param>
    /// <param name="prefsRoot">Settings root folder.</param>
    public StorageReport(string savesRoot, string statesRoot, string prefsRoot)
    {
        _saves = Path.GetFullPath(savesRoot);
        _states = Path.GetFullPath(statesRoot);
        _prefs = Path.GetFullPath(prefsRoot);
    }

    /// <summary>Builds the report.</summary>
    /// <param name="users">Every Jellyfin user (id, name). Folders of users no longer on the server are listed too, with no name.</param>
    /// <param name="findGame">The game if this user can see it in the library, else null.</param>
    /// <param name="saveQuotaBytes">Save quota per user (0 = no limit).</param>
    /// <param name="snapshotQuotaBytes">Snapshot quota per user (0 = no limit).</param>
    /// <returns>The report.</returns>
    public StorageReportDto Build(
        IEnumerable<(Guid Id, string Name)> users,
        Func<Guid, Guid, StorageGameRef?> findGame,
        long saveQuotaBytes,
        long snapshotQuotaBytes)
    {
        ArgumentNullException.ThrowIfNull(users);
        ArgumentNullException.ThrowIfNull(findGame);
        var names = new Dictionary<Guid, string?>();
        foreach (var (id, name) in users)
        {
            names[id] = name;
        }

        foreach (var root in new[] { _saves, _states, _prefs })
        {
            foreach (var d in Dirs(root))
            {
                if (Guid.TryParseExact(Path.GetFileName(d), "N", out var id))
                {
                    names.TryAdd(id, null);
                }
            }
        }

        var list = names.Select(kv => ForUser(kv.Key, kv.Value, findGame))
            .OrderByDescending(u => u.TotalBytes)
            .ThenBy(u => u.Name ?? "￿", StringComparer.OrdinalIgnoreCase)
            .ToList();
        var games = list.SelectMany(u => u.Games).ToList();
        var totals = new StorageTotalsDto(
            list.Count,
            games.Count,
            games.Count(g => !g.InLibrary),
            list.Sum(u => u.SaveBytes),
            list.Sum(u => u.SnapshotBytes),
            list.Sum(u => u.SettingsBytes),
            list.Sum(u => u.TotalBytes),
            list.Sum(u => u.PinnedCount),
            list.Sum(u => u.PinnedBytes),
            list.Sum(u => u.MovedCount),
            list.Sum(u => u.MovedBytes),
            list.Count(u => u.Unreadable));
        return new StorageReportDto(Math.Max(0, saveQuotaBytes), Math.Max(0, snapshotQuotaBytes), totals, list);
    }

    private static UserStorageDto Empty(string id, string? name) => new(id, name, 0, 0, 0, 0, 0, 0, 0, 0, [], []);

    private UserStorageDto ForUser(Guid user, string? name, Func<Guid, Guid, StorageGameRef?> findGame)
    {
        var key = user.ToString("N");
        var games = new Dictionary<Guid, Builder>();
        Builder Game(Guid item) => games.TryGetValue(item, out var b) ? b : games[item] = new Builder();

        // L12: a folder that can't be read is flagged ("Couldn't read"), never shown as an honest 0 bytes.
        var unreadable = false;
        List<(string Name, long Length)> Walk(string dir, ref bool bad)
        {
            var (files, ok) = Files(dir, FileWalk);
            bad |= !ok;
            return files;
        }

        // Saves: everything under the user's folder counts (same as the quota check); game folders are broken down.
        var userSaves = Path.Combine(_saves, key);
        long saveBytes = Walk(userSaves, ref unreadable).Sum(f => f.Length);
        foreach (var dir in Dirs(userSaves, ref unreadable))
        {
            if (!Guid.TryParseExact(Path.GetFileName(dir), "N", out var item))
            {
                continue;
            }

            var bad = false;
            var files = Walk(dir, ref bad);
            if (files.Count == 0 && !bad)
            {
                continue;
            }

            var g = Game(item);
            g.Unreadable |= bad;
            unreadable |= bad;
            g.SaveBytes = files.Sum(f => f.Length);
            var histories = new[] { ReadHistory(Path.Combine(dir, SaveStore.HistoryFileName)), ReadHistory(Path.Combine(dir, SaveStore.BrowserFolderName, SaveStore.HistoryFileName)) };
            var versions = histories.Where(h => h is not null).SelectMany(h => h!.Versions).ToList();
            g.VersionCount = histories.Any(h => h is not null) ? versions.Count : files.Count(f => f.Name.StartsWith('v') && (f.Name.EndsWith(".sav", StringComparison.Ordinal) || f.Name.EndsWith(".zip", StringComparison.Ordinal)));
            g.PinnedCount = versions.Count(v => v.Pinned);
            g.PinnedBytes = versions.Where(v => v.Pinned).Sum(v => v.Size);
            g.MovedCount = versions.Count(v => v.Source == SaveStore.MovedSource);
            g.MovedBytes = versions.Where(v => v.Source == SaveStore.MovedSource).Sum(v => v.Size);
            var named = histories.FirstOrDefault(h => h?.GameName is not null);
            g.Name = named?.GameName;
            g.Console = histories.FirstOrDefault(h => h?.Console is not null)?.Console;
        }

        // Snapshots (+ pictures, info files and the previous snapshot kept as .bak).
        var userStates = Path.Combine(_states, key);
        long snapshotBytes = Walk(userStates, ref unreadable).Sum(f => f.Length);
        foreach (var dir in Dirs(userStates, ref unreadable))
        {
            if (!Guid.TryParseExact(Path.GetFileName(dir), "N", out var item))
            {
                continue;
            }

            var bad = false;
            var files = Walk(dir, ref bad);
            if (files.Count == 0 && !bad)
            {
                continue;
            }

            var g = Game(item);
            g.Unreadable |= bad;
            unreadable |= bad;
            g.SnapshotBytes = files.Sum(f => f.Length);
            g.SnapshotCount = files.Count(f => f.Name.EndsWith(".state", StringComparison.Ordinal));
        }

        // Settings: one file per console (or "global"); a kept damaged copy counts towards its group.
        var settings = Walk(Path.Combine(_prefs, key), ref unreadable)
            .GroupBy(f => f.Name.Split('.')[0], StringComparer.Ordinal)
            .Select(x => new SettingsStorageDto(x.Key, x.Sum(f => f.Length)))
            .OrderBy(s => s.Scope, StringComparer.Ordinal)
            .ToList();
        long settingsBytes = settings.Sum(s => s.Bytes);

        if (saveBytes + snapshotBytes + settingsBytes == 0 && games.Count == 0 && !unreadable)
        {
            return Empty(key, name);
        }

        var gameList = games.Select(kv =>
            {
                var b = kv.Value;
                var lib = findGame(user, kv.Key);
                return new GameStorageDto(
                    kv.Key.ToString("N"),
                    lib?.Name ?? b.Name,
                    lib?.Console ?? b.Console,
                    lib is not null,
                    b.SaveBytes,
                    b.VersionCount,
                    b.SnapshotBytes,
                    b.SnapshotCount,
                    b.PinnedCount,
                    b.PinnedBytes,
                    b.MovedCount,
                    b.MovedBytes,
                    b.SaveBytes + b.SnapshotBytes,
                    b.Unreadable);
            })
            .OrderByDescending(g => g.TotalBytes)
            .ThenBy(g => g.Name ?? "￿", StringComparer.OrdinalIgnoreCase)
            .ToList();

        return new UserStorageDto(
            key,
            name,
            saveBytes,
            snapshotBytes,
            settingsBytes,
            saveBytes + snapshotBytes + settingsBytes,
            gameList.Sum(g => g.PinnedCount),
            gameList.Sum(g => g.PinnedBytes),
            gameList.Sum(g => g.MovedCount),
            gameList.Sum(g => g.MovedBytes),
            gameList,
            settings,
            unreadable);
    }

    /// <summary>
    /// Name and size of every file under a folder. A folder that doesn't exist is simply empty; one that can't be read
    /// (permissions, disk error), or has a subfolder that can't be, gives <c>Ok = false</c> (L12) with what could be read.
    /// </summary>
    /// <param name="dir">Folder.</param>
    /// <param name="options">The forgiving walk (the same one as the quota checks).</param>
    /// <returns>The files, and whether everything could be read.</returns>
    internal static (List<(string Name, long Length)> Files, bool Ok) Files(string dir, EnumerationOptions options)
    {
        if (!Directory.Exists(dir))
        {
            return ([], true);
        }

        // The quota walk skips folders it can't open; to notice them, walk once without skipping.
        var strict = new EnumerationOptions
        {
            RecurseSubdirectories = options.RecurseSubdirectories,
            AttributesToSkip = options.AttributesToSkip,
            MatchType = options.MatchType,
            IgnoreInaccessible = false,
        };
        try
        {
            return (List(dir, strict), true);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            // Fall through: count what can be read, and flag the rest.
        }

        try
        {
            return (List(dir, options), false);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return ([], false);
        }

        static List<(string Name, long Length)> List(string dir, EnumerationOptions o) =>
            new FileSystemEnumerable<(string Name, long Length)>(dir, (ref FileSystemEntry e) => (e.FileName.ToString(), e.Length), o)
            {
                ShouldIncludePredicate = (ref FileSystemEntry e) => !e.IsDirectory,
            }.ToList();
    }

    private static List<string> Dirs(string dir)
    {
        var bad = false;
        return Dirs(dir, ref bad);
    }

    private static List<string> Dirs(string dir, ref bool bad)
    {
        if (!Directory.Exists(dir))
        {
            return [];
        }

        try
        {
            return Directory.EnumerateDirectories(dir, "*", TopOnly).ToList();
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            bad = true;
            return [];
        }
    }

    /// <summary>Reads a history file without locking it (it is only ever replaced whole), or null if missing or damaged.</summary>
    private static SaveHistory? ReadHistory(string path)
    {
        try
        {
            using var s = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
            return JsonSerializer.Deserialize<SaveHistory>(s);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or JsonException)
        {
            return null;
        }
    }

    private sealed class Builder
    {
        public string? Name { get; set; }

        public string? Console { get; set; }

        public long SaveBytes { get; set; }

        public int VersionCount { get; set; }

        public long SnapshotBytes { get; set; }

        public int SnapshotCount { get; set; }

        public int PinnedCount { get; set; }

        public long PinnedBytes { get; set; }

        public int MovedCount { get; set; }

        public long MovedBytes { get; set; }

        public bool Unreadable { get; set; }
    }
}
