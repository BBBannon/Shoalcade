using System;
using System.Collections.Generic;
using System.Diagnostics.CodeAnalysis;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Plugin.Shoalcade.Common;
using Jellyfin.Plugin.Shoalcade.Consoles;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Shoalcade.Saves;

/// <summary>Limits for the save store, read from the plugin settings on every call.</summary>
public sealed class SaveStoreOptions
{
    /// <summary>Gets the number of most recent versions always kept.</summary>
    public int KeepRecent { get; init; } = 20;

    /// <summary>Gets the number of days for which one version per day is kept.</summary>
    public int KeepDailyDays { get; init; } = 30;

    /// <summary>Gets the most pinned versions per game.</summary>
    public int MaxPinned { get; init; } = 20;

    /// <summary>Gets the most bytes one user's saves (all versions) may use.</summary>
    public long UserQuotaBytes { get; init; } = 2L * 1024 * 1024 * 1024;
}

/// <summary>
/// Stores saves as plain files (D47): <c>{root}/{userId}/{itemId}/</c> holds <c>v000001.sav</c>, <c>v000002.sav</c>...
/// plus <c>history.json</c>. One save list per game (D91): a DS game's old separate browser list (D52, a <c>browser/</c>
/// sub-folder) is merged into it the first time the game's saves are touched (<see cref="MergeBrowserAsync"/>).
/// Version files are written once and never changed; <c>history.json</c> is replaced atomically.
/// One lock per game folder. If <c>history.json</c> is lost, it is rebuilt from the version files.
/// </summary>
public sealed partial class SaveStore : IDisposable
{
    /// <summary>Name of the history file in each game folder.</summary>
    public const string HistoryFileName = "history.json";

    /// <summary>Longest save label kept (longer ones are cut).</summary>
    public const int MaxLabelLength = 60;

    /// <summary>Label put on the save kept from before the first save made with cheats on (D143).</summary>
    public const string BeforeCheatsLabel = "Before cheats";

    /// <summary>Source of a version brought in by a move (never removed by the keep rule, M2).</summary>
    public const string MovedSource = "moved";

    private const int MaxDeviceLength = 64;

    private static readonly JsonSerializerOptions JsonOptions = new() { WriteIndented = true };

    private readonly string _root;
    private readonly Func<SaveStoreOptions> _options;
    private readonly TimeProvider _clock;
    private readonly ILogger _logger;
    private readonly KeyedLocks _locks = new();
    [SuppressMessage("Usage", "CA2213:Disposable fields should be disposed", Justification = "Not disposed on purpose: Jellyfin's in-process restart disposes this service while a task may still wait on or hold the lock, and a SemaphoreSlim only needs disposing once its AvailableWaitHandle is used (never here)")]
    private readonly SemaphoreSlim _moveLock = new(1, 1); // one move at a time, so two moves can't deadlock

    /// <summary>Initializes a new instance of the <see cref="SaveStore"/> class.</summary>
    /// <param name="root">The saves root folder.</param>
    /// <param name="options">Supplies the current limits.</param>
    /// <param name="clock">Clock (tests use a fake one).</param>
    /// <param name="logger">Logger.</param>
    public SaveStore(string root, Func<SaveStoreOptions> options, TimeProvider clock, ILogger logger)
    {
        _root = Path.GetFullPath(root);
        _options = options;
        _clock = clock;
        _logger = logger;
    }

    /// <summary>Gets the saves root folder.</summary>
    public string Root => _root;

    /// <inheritdoc />
    /// <remarks>The lock is not disposed (see its field): a Jellyfin restart can dispose this service while a
    /// start-up task or scan still uses it.</remarks>
    public void Dispose()
    {
    }

    /// <summary>Folder of the old separate DS browser save list (D52) inside a game folder; merged away by D91.</summary>
    public const string BrowserFolderName = "browser";

    /// <summary><see cref="SaveVersion.Origin"/> of a version merged in from the old DS browser save list (D91).</summary>
    public const string BrowserOrigin = "browser";

    /// <summary>Picks which versions the retention rule removes: everything except current, pinned,
    /// the most recent <paramref name="keepRecent"/>, and the newest of each day for <paramref name="keepDays"/> days.</summary>
    /// <param name="history">History.</param>
    /// <param name="now">Now.</param>
    /// <param name="keepRecent">Recent versions to keep.</param>
    /// <param name="keepDays">Days of daily versions to keep.</param>
    /// <returns>Versions to remove.</returns>
    public static List<SaveVersion> SelectPrunable(SaveHistory history, DateTimeOffset now, int keepRecent, int keepDays)
    {
        var keep = new HashSet<int>();
        if (history.Current is int c)
        {
            keep.Add(c);
        }

        foreach (var v in history.Versions)
        {
            // "recovered" = found after history.json was lost, so its pin may be lost too: never tidy it away automatically.
            // "moved" = came from another game (M2), origin "browser" = merged in from the old DS browser list (D91):
            // their numbers are new but their dates are old, so the keep rule can't judge them fairly. Kept until the
            // person deletes them by hand.
            if (v.Pinned || v.Source == "recovered" || IsBroughtIn(v))
            {
                keep.Add(v.Version);
            }
        }

        // Moved and merged versions don't take the game's own "recent" or "one per day" places either, so a move or
        // the D91 merge never pushes out the game's own history.
        var own = history.Versions.Where(v => !IsBroughtIn(v)).ToList();
        foreach (var v in own.OrderByDescending(v => v.Version).Take(Math.Max(1, keepRecent)))
        {
            keep.Add(v.Version);
        }

        var since = now.UtcDateTime.Date.AddDays(-Math.Max(0, keepDays - 1));
        foreach (var day in own.Where(v => v.Created.UtcDateTime >= since).GroupBy(v => v.Created.UtcDateTime.Date))
        {
            keep.Add(day.Max(v => v.Version));
        }

        return history.Versions.Where(v => !keep.Contains(v.Version)).ToList();
    }

    /// <summary>Came from elsewhere (moved from another game, M2, or merged from the old DS browser list, D91).</summary>
    private static bool IsBroughtIn(SaveVersion v) => v.Source == MovedSource || v.Origin == BrowserOrigin;

    /// <summary>Reads a game's save history (empty if none).</summary>
    /// <param name="user">User id.</param>
    /// <param name="item">Item id.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>The history.</returns>
    public async Task<SaveHistory> GetHistoryAsync(Guid user, Guid item, CancellationToken cancellationToken)
    {
        var dir = GameDir(user, item);
        using (await _locks.AcquireAsync(dir, cancellationToken).ConfigureAwait(false))
        {
            return await LoadAsync(dir, item, cancellationToken).ConfigureAwait(false);
        }
    }

    /// <summary>Opens a version's bytes for download.</summary>
    /// <param name="user">User id.</param>
    /// <param name="item">Item id.</param>
    /// <param name="version">Version number, or null for the current save.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>The version and an open read stream, or null if there is none.</returns>
    public async Task<(SaveVersion Version, Stream Data)?> OpenAsync(Guid user, Guid item, int? version, CancellationToken cancellationToken)
    {
        var dir = GameDir(user, item);
        using (await _locks.AcquireAsync(dir, cancellationToken).ConfigureAwait(false))
        {
            var h = await LoadAsync(dir, item, cancellationToken).ConfigureAwait(false);
            var v = version is int n ? h.Versions.Find(x => x.Version == n) : h.CurrentVersion();
            if (v is null)
            {
                return null;
            }

            // Opened inside the lock, so a prune can't delete it first. Readers keep the handle after the lock.
            try
            {
                Stream s = new FileStream(Path.Combine(dir, v.File), FileMode.Open, FileAccess.Read, FileShare.Read | FileShare.Delete, 81920, FileOptions.Asynchronous);
                return (v, s);
            }
            catch (FileNotFoundException)
            {
                _logger.LogWarning("Save version file {File} is listed but missing from {Dir}", v.File, dir);
                return null;
            }
        }
    }

    /// <summary>Stores an uploaded save.</summary>
    /// <param name="user">User id.</param>
    /// <param name="item">Item id.</param>
    /// <param name="console">Console of the game.</param>
    /// <param name="gameName">Game name (kept for the save list).</param>
    /// <param name="data">Save bytes.</param>
    /// <param name="mode">Normal, Force or HistoryOnly.</param>
    /// <param name="baseVersion">The version the client last saw (0 = none). Required for Normal.</param>
    /// <param name="source">Source label (browser, app, upload).</param>
    /// <param name="device">Client/device name, if known.</param>
    /// <param name="allowBlank">Accept a blank save.</param>
    /// <param name="afterSnapshot">The game saved this after a snapshot was loaded (D90): marked on the new version.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <param name="expect">With <see cref="PutMode.Force"/>: the save the person was shown; a different current save = conflict (L2).</param>
    /// <param name="cheats">The game saved this while cheats were on (D143): marked on the new version. The first time it
    /// replaces a save made without cheats, that save is pinned and labelled "Before cheats", so it is never tidied away.</param>
    /// <returns>The result.</returns>
    public Task<SaveWriteResult> PutAsync(
        Guid user,
        Guid item,
        GameConsole console,
        string? gameName,
        ReadOnlyMemory<byte> data,
        PutMode mode,
        int? baseVersion,
        string source,
        string? device,
        bool allowBlank,
        bool afterSnapshot,
        CancellationToken cancellationToken,
        SaveExpectation? expect = null,
        bool cheats = false)
    {
        var warnings = new List<string>();
        var error = SaveRules.Check(console, data.Span, allowBlank, warnings, out var code);
        if (error is not null)
        {
            return Task.FromResult(new SaveWriteResult(SaveWriteStatus.Rejected, null, null, error, code, warnings));
        }

        if (mode == PutMode.Normal && baseVersion is null)
        {
            return Task.FromResult(new SaveWriteResult(SaveWriteStatus.Rejected, null, null, "The upload must say which save version it replaces.", "version", warnings));
        }

        var sha = Sha256Hex(data.Span);
        return MutateAsync(user, item, console, gameName, async (dir, h) =>
        {
            var current = h.CurrentVersion();
            if (current is not null && current.Sha256 == sha)
            {
                return new SaveWriteResult(SaveWriteStatus.Unchanged, current, current, null, null, warnings);
            }

            if (mode == PutMode.Normal && (baseVersion ?? 0) != (h.Current ?? 0))
            {
                return new SaveWriteResult(SaveWriteStatus.Conflict, null, current, "The server has a newer save than the one this device last saw.", "conflict", warnings);
            }

            // L2: "Keep the phone's" replaces exactly the save the person was shown, never one that arrived since.
            if (expect is not null && !expect.Matches(current))
            {
                return new SaveWriteResult(SaveWriteStatus.Conflict, null, current, "The save on Jellyfin changed since you chose. Look again and choose.", "conflict", warnings);
            }

            if (mode == PutMode.HistoryOnly && h.Versions.Find(x => x.Sha256 == sha) is SaveVersion same)
            {
                return new SaveWriteResult(SaveWriteStatus.Unchanged, same, current, null, null, warnings);
            }

            var quota = CheckQuota(user, data.Length);
            if (quota is not null)
            {
                return new SaveWriteResult(SaveWriteStatus.Rejected, null, current, quota, "quota", warnings);
            }

            // D143: the first time a clean save is replaced by one made with cheats on, keep the clean save for good.
            if (cheats && current is not null && current.Cheats != true && !h.Versions.Exists(x => x.Label == BeforeCheatsLabel))
            {
                current.Label ??= BeforeCheatsLabel;
                if (current.Pinned || h.Versions.Count(x => x.Pinned) < _options().MaxPinned)
                {
                    current.Pinned = true;
                }
                else
                {
                    warnings.Add("Your save from before cheats couldn't be pinned (this game has the most pinned saves allowed), so it may be tidied away later. Unpin one to keep it.");
                }
            }

            var v = await AddVersionAsync(dir, h, console.SaveExtension, data, sha, source, device, null, cancellationToken).ConfigureAwait(false);
            v.AfterSnapshot = afterSnapshot ? true : null;
            v.Cheats = cheats ? true : null;
            if (mode != PutMode.HistoryOnly)
            {
                h.Current = v.Version;
            }

            return new SaveWriteResult(SaveWriteStatus.Created, v, h.CurrentVersion(), null, null, warnings);
        }, cancellationToken);
    }

    /// <summary>Makes an old version current again. The bytes are copied into a new version, so nothing is lost.</summary>
    /// <param name="user">User id.</param>
    /// <param name="item">Item id.</param>
    /// <param name="console">Console.</param>
    /// <param name="gameName">Game name.</param>
    /// <param name="version">Version to restore.</param>
    /// <param name="baseVersion">Version the client last saw; null = don't check.</param>
    /// <param name="device">Client/device.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>The result.</returns>
    public Task<SaveWriteResult> RestoreAsync(Guid user, Guid item, GameConsole console, string? gameName, int version, int? baseVersion, string? device, CancellationToken cancellationToken)
    {
        return MutateAsync(user, item, console, gameName, async (dir, h) =>
        {
            var current = h.CurrentVersion();
            var old = h.Versions.Find(x => x.Version == version);
            if (old is null)
            {
                return new SaveWriteResult(SaveWriteStatus.NotFound, null, current, "That save version doesn't exist (it may have been tidied away).", "missing", []);
            }

            if (baseVersion is int b && b != (h.Current ?? 0))
            {
                return new SaveWriteResult(SaveWriteStatus.Conflict, null, current, "The save changed since you opened this page. Refresh and try again.", "conflict", []);
            }

            if (current is not null && current.Sha256 == old.Sha256)
            {
                return new SaveWriteResult(SaveWriteStatus.Unchanged, current, current, null, null, []);
            }

            var bytes = await TryReadAsync(Path.Combine(dir, old.File), cancellationToken).ConfigureAwait(false);
            if (bytes is null || Sha256Hex(bytes) != old.Sha256)
            {
                return new SaveWriteResult(SaveWriteStatus.Rejected, null, current, "That version's file is damaged on the server, so it wasn't restored.", "damaged", []);
            }

            var quota = CheckQuota(user, bytes.Length);
            if (quota is not null)
            {
                return new SaveWriteResult(SaveWriteStatus.Rejected, null, current, quota, "quota", []);
            }

            var v = await AddVersionAsync(dir, h, console.SaveExtension, bytes, old.Sha256, "restore", device, old.Version, cancellationToken).ConfigureAwait(false);
            h.Current = v.Version;
            return new SaveWriteResult(SaveWriteStatus.Created, v, v, null, null, []);
        }, cancellationToken);
    }

    /// <summary>Pins or unpins a version and sets its label.</summary>
    /// <param name="user">User id.</param>
    /// <param name="item">Item id.</param>
    /// <param name="version">Version.</param>
    /// <param name="pinned">Pin state.</param>
    /// <param name="label">Label (null = leave as is, "" = clear).</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>The result.</returns>
    public Task<SaveWriteResult> UpdateVersionAsync(Guid user, Guid item, int version, bool? pinned, string? label, CancellationToken cancellationToken)
    {
        return MutateAsync(user, item, null, null, (dir, h) =>
        {
            var v = h.Versions.Find(x => x.Version == version);
            if (v is null)
            {
                return Task.FromResult(new SaveWriteResult(SaveWriteStatus.NotFound, null, h.CurrentVersion(), "That save version doesn't exist.", "missing", []));
            }

            if (pinned == true && !v.Pinned && h.Versions.Count(x => x.Pinned) >= _options().MaxPinned)
            {
                return Task.FromResult(new SaveWriteResult(SaveWriteStatus.Rejected, null, h.CurrentVersion(), "Too many pinned saves for this game. Unpin one first.", "pinned", []));
            }

            if (pinned is bool p)
            {
                v.Pinned = p;
            }

            if (label is not null)
            {
                var clean = Clean(label, MaxLabelLength);
                v.Label = clean.Length == 0 ? null : clean;
            }

            return Task.FromResult(new SaveWriteResult(SaveWriteStatus.Created, v, h.CurrentVersion(), null, null, []));
        }, false, cancellationToken); // An unpin never deletes anything by itself; the keep rule runs at the next save.
    }

    /// <summary>Deletes one old version. The current save and pinned versions can't be deleted.</summary>
    /// <param name="user">User id.</param>
    /// <param name="item">Item id.</param>
    /// <param name="version">Version.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>The result.</returns>
    public Task<SaveWriteResult> DeleteVersionAsync(Guid user, Guid item, int version, CancellationToken cancellationToken)
    {
        return MutateAsync(user, item, null, null, (dir, h) =>
        {
            var v = h.Versions.Find(x => x.Version == version);
            if (v is null)
            {
                return Task.FromResult(new SaveWriteResult(SaveWriteStatus.NotFound, null, h.CurrentVersion(), "That save version doesn't exist.", "missing", []));
            }

            if (h.Current == version)
            {
                return Task.FromResult(new SaveWriteResult(SaveWriteStatus.Rejected, null, h.CurrentVersion(), "That's the current save, so it can't be deleted. Restore another version first, or use \"Start fresh\".", "current", []));
            }

            if (v.Pinned)
            {
                return Task.FromResult(new SaveWriteResult(SaveWriteStatus.Rejected, null, h.CurrentVersion(), "That save is pinned. Unpin it first.", "pinned", []));
            }

            h.Versions.Remove(v);
            h.PendingDeletes.Add(v);
            return Task.FromResult(new SaveWriteResult(SaveWriteStatus.Created, v, h.CurrentVersion(), null, null, []));
        }, cancellationToken);
    }

    /// <summary>"Start fresh": there is no current save afterwards, but every version stays in history.</summary>
    /// <param name="user">User id.</param>
    /// <param name="item">Item id.</param>
    /// <param name="baseVersion">Version the client last saw; null = don't check.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>The result.</returns>
    public Task<SaveWriteResult> ClearCurrentAsync(Guid user, Guid item, int? baseVersion, CancellationToken cancellationToken)
    {
        return MutateAsync(user, item, null, null, (dir, h) =>
        {
            var current = h.CurrentVersion();
            if (baseVersion is int b && b != (h.Current ?? 0))
            {
                return Task.FromResult(new SaveWriteResult(SaveWriteStatus.Conflict, null, current, "The save changed since you opened this page. Refresh and try again.", "conflict", []));
            }

            if (current is null)
            {
                return Task.FromResult(new SaveWriteResult(SaveWriteStatus.Unchanged, null, null, null, null, []));
            }

            h.Current = null;
            return Task.FromResult(new SaveWriteResult(SaveWriteStatus.Created, current, null, null, null, []));
        }, false, cancellationToken); // "Nothing is deleted": the save that was in use must not be cleared out by the keep rule.
    }

    /// <summary>
    /// Imports a save from outside (JellyEMU, D40). Never imports the same file contents twice.
    /// Newer than our current save → becomes current (old current stays in history). Older → history only.
    /// </summary>
    /// <param name="user">User id.</param>
    /// <param name="item">Item id.</param>
    /// <param name="console">Console.</param>
    /// <param name="gameName">Game name.</param>
    /// <param name="data">Bytes.</param>
    /// <param name="sourceKey">Where it came from (relative path), for the import marker.</param>
    /// <param name="modified">Source file's last-modified time.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>The result (Unchanged if already imported or identical).</returns>
    public Task<SaveWriteResult> ImportAsync(Guid user, Guid item, GameConsole console, string? gameName, ReadOnlyMemory<byte> data, string sourceKey, DateTimeOffset modified, CancellationToken cancellationToken)
    {
        var sha = Sha256Hex(data.Span);
        return MutateAsync(user, item, console, gameName, async (dir, h) =>
        {
            var current = h.CurrentVersion();
            if (h.Imports.Any(m => m.Source == sourceKey && m.Sha256 == sha))
            {
                return new SaveWriteResult(SaveWriteStatus.Unchanged, null, current, null, null, []);
            }

            // Marker = "dealt with, don't import again". Not set for a full quota: that is temporary, so retry later.
            var marker = new ImportMarker { Source = sourceKey, Sha256 = sha, Modified = modified };
            var warnings = new List<string>();
            var error = SaveRules.Check(console, data.Span, false, warnings, out var code);
            if (error is not null)
            {
                h.Imports.Add(marker);
                _logger.LogInformation("Skipped JellyEMU save {Source}: {Reason}", sourceKey, code);
                return new SaveWriteResult(SaveWriteStatus.Rejected, null, current, error, code, warnings);
            }

            if (h.Versions.Find(x => x.Sha256 == sha) is SaveVersion same)
            {
                h.Imports.Add(marker);
                return new SaveWriteResult(SaveWriteStatus.Unchanged, same, current, null, null, warnings);
            }

            var quota = CheckQuota(user, data.Length);
            if (quota is not null)
            {
                return new SaveWriteResult(SaveWriteStatus.Rejected, null, current, quota, "quota", warnings);
            }

            h.Imports.Add(marker);
            var v = await AddVersionAsync(dir, h, console.SaveExtension, data, sha, "jellyemu", "JellyEMU", null, cancellationToken).ConfigureAwait(false);
            v.Created = modified < v.Created ? modified : v.Created;
            if (current is null || modified > current.Created)
            {
                h.Current = v.Version;
            }

            return new SaveWriteResult(SaveWriteStatus.Created, v, h.CurrentVersion(), null, null, warnings);
        }, cancellationToken);
    }

    /// <summary>
    /// The rules for moving a game's data to another game (same game, or a different save format, is refused). Used by
    /// <see cref="MoveAsync"/> and by a snapshots-only move of a game that left the library (F8).
    /// </summary>
    /// <param name="fromItem">Old item id.</param>
    /// <param name="fromConsole">Console of the old game.</param>
    /// <param name="toItem">New item id.</param>
    /// <param name="console">Console of the new game.</param>
    /// <returns>The refusal, or null if the move is allowed.</returns>
    public static SaveWriteResult? MoveRefusal(Guid fromItem, GameConsole fromConsole, Guid toItem, GameConsole console)
    {
        ArgumentNullException.ThrowIfNull(fromConsole);
        ArgumentNullException.ThrowIfNull(console);
        if (fromItem == toItem)
        {
            return new SaveWriteResult(SaveWriteStatus.Rejected, null, null, "That's the same game.", "same", []);
        }

        return fromConsole.SharesSaveFormatWith(console)
            ? null
            : new SaveWriteResult(SaveWriteStatus.Rejected, null, null, "A " + fromConsole.DisplayName + " save can't be moved to a " + console.DisplayName + " game: the save formats are different.", "console", []);
    }

    /// <summary>
    /// Moves every version of one game's save into another game (for a game whose id changed, e.g. a renamed ROM).
    /// Only between games with the same save format (<see cref="GameConsole.SharesSaveFormatWith"/>): same console,
    /// or Game Boy and Game Boy Color.
    /// The source's current save becomes the target's current only if the target has none; otherwise it goes into history.
    /// The source folder is removed only after every version is copied and checked.
    /// </summary>
    /// <param name="user">User id.</param>
    /// <param name="fromItem">Old item id.</param>
    /// <param name="fromConsole">Console of the old game.</param>
    /// <param name="toItem">New item id.</param>
    /// <param name="console">Console of the new game.</param>
    /// <param name="gameName">Name of the new game.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>The result for the target.</returns>
    public async Task<SaveWriteResult> MoveAsync(Guid user, Guid fromItem, GameConsole fromConsole, Guid toItem, GameConsole console, string? gameName, CancellationToken cancellationToken)
    {
        if (MoveRefusal(fromItem, fromConsole, toItem, console) is { } refused)
        {
            return refused;
        }

        var fromDir = GameDir(user, fromItem);
        await _moveLock.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            using var fromLock = await _locks.AcquireAsync(fromDir, cancellationToken).ConfigureAwait(false);
            var src = await LoadAsync(fromDir, fromItem, cancellationToken).ConfigureAwait(false);
            if (src.Versions.Count == 0)
            {
                return new SaveWriteResult(SaveWriteStatus.NotFound, null, null, "There's no save to move.", "missing", []);
            }

            var copies = new List<(SaveVersion V, byte[] Data)>();
            foreach (var v in src.Versions)
            {
                var bytes = await TryReadAsync(Path.Combine(fromDir, v.File), cancellationToken).ConfigureAwait(false);
                if (bytes is null || Sha256Hex(bytes) != v.Sha256)
                {
                    return new SaveWriteResult(SaveWriteStatus.Rejected, null, null, "Version " + v.Version.ToString(CultureInfo.InvariantCulture) + " is damaged, so nothing was moved.", "damaged", []);
                }

                copies.Add((v, bytes));
            }

            var result = await MutateAsync(user, toItem, console, gameName, async (dir, h) =>
            {
                var quota = CheckQuota(user, copies.Sum(c => (long)c.Data.Length));
                if (quota is not null)
                {
                    return new SaveWriteResult(SaveWriteStatus.Rejected, null, h.CurrentVersion(), quota, "quota", []);
                }

                SaveVersion? newCurrent = null;
                foreach (var (v, bytes) in copies)
                {
                    var existing = h.Versions.Find(x => x.Sha256 == v.Sha256);
                    var nv = existing ?? await AddVersionAsync(dir, h, console.SaveExtension, bytes, v.Sha256, MovedSource, v.Device, null, cancellationToken).ConfigureAwait(false);
                    if (existing is null)
                    {
                        nv.Created = v.Created;
                        nv.Pinned = v.Pinned;
                        nv.Label = v.Label;
                        nv.Origin = v.Origin;
                        nv.AfterSnapshot = v.AfterSnapshot;
                        nv.Cheats = v.Cheats;
                    }
                    else
                    {
                        // Same bytes already here: keep the source's pin and label on the copy that stays (L5).
                        existing.Pinned |= v.Pinned;
                        existing.Label ??= v.Label;
                    }

                    if (src.Current == v.Version)
                    {
                        newCurrent = nv;
                    }
                }

                if (h.Current is null && newCurrent is not null)
                {
                    h.Current = newCurrent.Version;
                }

                return new SaveWriteResult(SaveWriteStatus.Created, newCurrent, h.CurrentVersion(), null, null, []);
            }, false, cancellationToken).ConfigureAwait(false); // M2: a move never runs the keep rule.

            if (result.Status == SaveWriteStatus.Created)
            {
                if (src.Imports.Count > 0)
                {
                    await EmptyKeepingImportsAsync(fromDir, src).ConfigureAwait(false);
                }
                else
                {
                    DeleteGameFolder(fromDir);
                }
            }

            return result;
        }
        finally
        {
            _moveLock.Release();
        }
    }

    /// <summary>
    /// Deletes every save version of a game no longer in the library (pinned and in-use ones too). The only path that
    /// does that: the caller checks the game is gone and the person confirmed. JellyEMU import markers are kept (as after
    /// a move), so a returning game never re-imports a save already brought in. An old DS browser folder goes too.
    /// </summary>
    /// <param name="user">User id.</param>
    /// <param name="item">Item id.</param>
    /// <param name="expectedVersions">How many versions the person was shown: a different count means it changed.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>Conflict if the count changed, Unchanged if there was nothing, else Created with the number deleted.</returns>
    public async Task<(SaveWriteStatus Status, int Deleted)> DeleteAllAsync(Guid user, Guid item, int expectedVersions, CancellationToken cancellationToken)
    {
        var dir = GameDir(user, item);
        await _moveLock.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            using var gameLock = await _locks.AcquireAsync(dir, cancellationToken).ConfigureAwait(false);
            var h = await LoadAsync(dir, item, cancellationToken).ConfigureAwait(false);
            if (h.Versions.Count != expectedVersions)
            {
                return (SaveWriteStatus.Conflict, 0);
            }

            if (h.Versions.Count == 0)
            {
                return (SaveWriteStatus.Unchanged, 0);
            }

            var n = h.Versions.Count;

            // The empty list is written first (history-first, as after a move): if a file can't be deleted (held open by
            // antivirus, say), it stays a stray that the next load adopts as "recovered", never a list pointing at nothing.
            await EmptyKeepingImportsAsync(dir, h).ConfigureAwait(false);
            if (h.Imports.Count == 0)
            {
                TryDeleteFile(Path.Combine(dir, HistoryFileName)); // nothing to remember: the folder can go
            }

            var browserDir = Path.Combine(dir, BrowserFolderName);
            try
            {
                if (Directory.Exists(browserDir))
                {
                    Directory.Delete(browserDir, true);
                }

                if (h.Imports.Count == 0 && Directory.Exists(dir) && !Directory.EnumerateFileSystemEntries(dir).Any())
                {
                    Directory.Delete(dir);
                }
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
            {
                _logger.LogWarning(ex, "Deleted a removed game's saves but could not tidy its folder {Dir}", dir);
            }

            return (SaveWriteStatus.Created, n);
        }
        finally
        {
            _moveLock.Release();
        }
    }

    /// <summary>Lists every game the user has a save folder for.</summary>
    /// <param name="user">User id.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>Summaries.</returns>
    public async Task<IReadOnlyList<SaveSummary>> ListAsync(Guid user, CancellationToken cancellationToken)
    {
        var userDir = Path.Combine(_root, user.ToString("N"));
        var list = new List<SaveSummary>();
        if (!Directory.Exists(userDir))
        {
            return list;
        }

        foreach (var gameDir in Directory.EnumerateDirectories(userDir))
        {
            if (!Guid.TryParseExact(Path.GetFileName(gameDir), "N", out var item))
            {
                continue;
            }

            // A DS game may have only the old browser list so far (D52): reading it merges that in (D91).
            if (!HasAnything(gameDir) && !Directory.Exists(Path.Combine(gameDir, BrowserFolderName)))
            {
                continue;
            }

            var h = await GetHistoryAsync(user, item, cancellationToken).ConfigureAwait(false);
            if (h.Versions.Count == 0)
            {
                continue;
            }

            list.Add(new SaveSummary(item, h.GameName, h.Console, h.CurrentVersion(), h.Versions.Count, h.Versions.Sum(v => v.Size)));
        }

        return list;
    }

    /// <summary>Bytes used by all of a user's save files.</summary>
    /// <param name="user">User id.</param>
    /// <returns>Bytes.</returns>
    public long UsedBytes(Guid user) => DiskUsage.Bytes(Path.Combine(_root, user.ToString("N")));

    internal static string Sha256Hex(ReadOnlySpan<byte> data) => Convert.ToHexStringLower(SHA256.HashData(data));

    /// <summary>True if the text is a SHA-256 in hex (64 characters).</summary>
    /// <param name="text">Text.</param>
    /// <returns>Whether it is one.</returns>
    public static bool IsSha256Hex(string? text) => text is { Length: 64 } && text.All(char.IsAsciiHexDigit);

    /// <summary>Reads a version file, or null if it has gone missing (shown to the person as "damaged", never a crash).</summary>
    private static async Task<byte[]?> TryReadAsync(string path, CancellationToken cancellationToken)
    {
        try
        {
            return await File.ReadAllBytesAsync(path, cancellationToken).ConfigureAwait(false);
        }
        catch (FileNotFoundException)
        {
            return null;
        }
        catch (DirectoryNotFoundException)
        {
            return null;
        }
    }

    // Guids only ever become folder names in "N" format, so no path tricks are possible.
    internal string GameDir(Guid user, Guid item) => Path.Combine(_root, user.ToString("N"), item.ToString("N"));

    private static string Clean(string s, int max)
    {
        var sb = new StringBuilder();
        foreach (var ch in s.Trim())
        {
            if (!char.IsControl(ch))
            {
                sb.Append(ch);
            }

            if (sb.Length >= max)
            {
                break;
            }
        }

        return sb.ToString().Trim();
    }

    private static bool HasAnything(string dir) =>
        File.Exists(Path.Combine(dir, HistoryFileName)) || Directory.EnumerateFiles(dir, "v*.*").Any();

    [GeneratedRegex(@"^v(\d{6,9})\.(sav|zip)$", RegexOptions.CultureInvariant)]
    private static partial Regex VersionFileRegex();

    private string? CheckQuota(Guid user, long adding)
    {
        var quota = _options().UserQuotaBytes;
        if (quota > 0 && UsedBytes(user) + adding > quota)
        {
            return "Your save storage is full. Delete some old save versions, or ask the server admin for more space.";
        }

        return null;
    }

    private Task<SaveWriteResult> MutateAsync(
        Guid user,
        Guid item,
        GameConsole? console,
        string? gameName,
        Func<string, SaveHistory, Task<SaveWriteResult>> action,
        CancellationToken cancellationToken)
        => MutateAsync(user, item, console, gameName, action, true, cancellationToken);

    // prune = false: never run the keep rule in this change (moves and pin/label edits must not lose a version, M2/L5).
    private async Task<SaveWriteResult> MutateAsync(
        Guid user,
        Guid item,
        GameConsole? console,
        string? gameName,
        Func<string, SaveHistory, Task<SaveWriteResult>> action,
        bool prune,
        CancellationToken cancellationToken)
    {
        var dir = GameDir(user, item);
        using (await _locks.AcquireAsync(dir, cancellationToken).ConfigureAwait(false))
        {
            var h = await LoadAsync(dir, item, cancellationToken).ConfigureAwait(false);
            var before = JsonSerializer.Serialize(h, JsonOptions);
            var result = await action(dir, h).ConfigureAwait(false);
            if (result.Status == SaveWriteStatus.Created)
            {
                if (console is not null)
                {
                    h.Console = console.Key;
                }

                if (!string.IsNullOrWhiteSpace(gameName))
                {
                    h.GameName = Clean(gameName, 200);
                }

                if (prune)
                {
                    var opts = _options();
                    foreach (var old in SelectPrunable(h, _clock.GetUtcNow(), opts.KeepRecent, opts.KeepDailyDays))
                    {
                        h.Versions.Remove(old);
                        h.PendingDeletes.Add(old);
                    }
                }
            }

            var after = JsonSerializer.Serialize(h, JsonOptions);
            if (!string.Equals(before, after, StringComparison.Ordinal))
            {
                await AtomicFile.WriteAsync(Path.Combine(dir, HistoryFileName), Encoding.UTF8.GetBytes(after), true, cancellationToken).ConfigureAwait(false);
            }

            // Files go only after history.json no longer lists them: if that write failed, every listed file still exists.
            foreach (var old in h.PendingDeletes)
            {
                DeleteVersionFile(dir, old);
            }

            return result;
        }
    }

    private async Task<SaveVersion> AddVersionAsync(string dir, SaveHistory h, string extension, ReadOnlyMemory<byte> data, string sha, string source, string? device, int? restoredFrom, CancellationToken cancellationToken)
    {
        var n = h.NextVersion;
        string file;
        while (true)
        {
            file = "v" + n.ToString("D6", CultureInfo.InvariantCulture) + "." + extension;
            if (!File.Exists(Path.Combine(dir, file)))
            {
                break;
            }

            n++; // A version file from an interrupted write that never reached history.json: never overwrite it.
        }

        await AtomicFile.WriteAsync(Path.Combine(dir, file), data, false, cancellationToken).ConfigureAwait(false);
        var v = new SaveVersion
        {
            Version = n,
            File = file,
            Size = data.Length,
            Sha256 = sha,
            Created = _clock.GetUtcNow(),
            Source = source,
            Device = device is null ? null : Clean(device, MaxDeviceLength),
            RestoredFrom = restoredFrom,
        };
        h.Versions.Add(v);
        h.NextVersion = n + 1;
        return v;
    }

    private void DeleteVersionFile(string dir, SaveVersion v)
    {
        try
        {
            File.Delete(Path.Combine(dir, v.File));
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            _logger.LogWarning(ex, "Could not delete old save version {File}", v.File);
        }
    }

    /// <summary>
    /// After a move: the source keeps an empty history.json with its JellyEMU import markers (and version counter), so
    /// JellyEMU saves already brought in are never imported again onto the game the save was moved off.
    /// history.json is written first, then the version files go (a failed write leaves every listed file in place).
    /// </summary>
    private async Task EmptyKeepingImportsAsync(string dir, SaveHistory src)
    {
        var gone = src.Versions.ToList();
        src.Versions.Clear();
        src.Current = null;

        // The move has already happened, so this tidy-up is not cancelled half way.
        await AtomicFile.WriteAsync(Path.Combine(dir, HistoryFileName), Encoding.UTF8.GetBytes(JsonSerializer.Serialize(src, JsonOptions)), true, CancellationToken.None).ConfigureAwait(false);
        foreach (var v in gone)
        {
            DeleteVersionFile(dir, v);
        }
    }

    private void TryDeleteFile(string path)
    {
        try
        {
            File.Delete(path);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            _logger.LogWarning(ex, "Could not delete {Path}", path);
        }
    }

    private void DeleteGameFolder(string dir)
    {
        try
        {
            foreach (var f in Directory.EnumerateFiles(dir))
            {
                File.Delete(f);
            }

            // Leave the folder if anything else is still in it (e.g. a kept-back old browser folder, D91).
            if (!Directory.EnumerateFileSystemEntries(dir).Any())
            {
                Directory.Delete(dir);
            }
        }
        catch (IOException ex)
        {
            _logger.LogWarning(ex, "Moved a save but could not tidy the old folder {Dir}", dir);
        }
    }

    /// <summary>Loads a game's one save list, merging in the old DS browser list first if there is one (D91).
    /// Every caller holds the game's lock.</summary>
    private async Task<SaveHistory> LoadAsync(string dir, Guid item, CancellationToken cancellationToken)
    {
        var h = await LoadFolderAsync(dir, item, "main", cancellationToken).ConfigureAwait(false);
        var browserDir = Path.Combine(dir, BrowserFolderName);
        if (!Directory.Exists(browserDir))
        {
            return h;
        }

        if (h.BrowserMerged is not null)
        {
            TidyBrowserFolder(browserDir, h); // merged before, but the tidy-up didn't finish
            return h;
        }

        try
        {
            await MergeBrowserAsync(dir, browserDir, item, h, cancellationToken).ConfigureAwait(false);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            // Nothing was deleted: the browser list is untouched and any copies already made are found again (and
            // claimed one for one) next time. Carry on with the list as it is on disk.
            _logger.LogWarning(ex, "Could not merge the old DS browser saves in {Dir}; will try again next time", browserDir);
            return await LoadFolderAsync(dir, item, "main", cancellationToken).ConfigureAwait(false);
        }

        return h;
    }

    /// <summary>
    /// D91: puts every version of the old separate DS browser save list (D52) into the game's one list. Crash-safe
    /// and runs once:
    /// <list type="number">
    /// <item>Each browser version is copied in as a new version (new number; its own time, source, device, pin and
    /// label kept; <see cref="SaveVersion.Origin"/> = browser), oldest first. Bytes are checked against their SHA-256
    /// first; a damaged one is left where it is. Nothing is skipped as "same bytes": every version stays a version.</item>
    /// <item>The save in use becomes whichever is newer, the browser's or the app's (the app's if they are the same
    /// time). The other one stays in the list.</item>
    /// <item>history.json is written once with every copy and <see cref="SaveHistory.BrowserMerged"/>. Only then is the
    /// browser folder tidied away, and only files whose bytes are now in the list (never delete before the record
    /// listing them is safely written).</item>
    /// </list>
    /// A crash after some copies but before history.json leaves them as stray files: they are adopted as "recovered"
    /// on the next load and then claimed by the browser version with the same bytes, so nothing is doubled.
    /// </summary>
    private async Task MergeBrowserAsync(string dir, string browserDir, Guid item, SaveHistory h, CancellationToken cancellationToken)
    {
        var b = await LoadFolderAsync(browserDir, item, "browser", cancellationToken).ConfigureAwait(false);
        var newNumber = new Dictionary<int, SaveVersion>();
        var claimed = new HashSet<int>();
        var damaged = 0;
        foreach (var v in b.Versions.OrderBy(x => x.Created).ThenBy(x => x.Version))
        {
            var bytes = await TryReadAsync(Path.Combine(browserDir, v.File), cancellationToken).ConfigureAwait(false);
            if (bytes is null || Sha256Hex(bytes) != v.Sha256)
            {
                damaged++;
                _logger.LogWarning("Old DS browser save {File} in {Dir} is missing or damaged; left where it is", v.File, browserDir);
                continue;
            }

            // A copy from an earlier, interrupted merge (adopted as "recovered"): claim it instead of copying again.
            var nv = h.Versions.Find(x => x.Source == "recovered" && x.Origin is null && x.Sha256 == v.Sha256 && !claimed.Contains(x.Version));
            if (nv is null)
            {
                var ext = Path.GetExtension(v.File).TrimStart('.');
                nv = await AddVersionAsync(dir, h, ext.Length == 0 ? "sav" : ext, bytes, v.Sha256, v.Source, v.Device, null, cancellationToken).ConfigureAwait(false);
            }

            claimed.Add(nv.Version);
            nv.Source = v.Source;
            nv.Device = v.Device;
            nv.Created = v.Created;
            nv.Pinned = v.Pinned;
            nv.Label = v.Label;
            nv.Origin = BrowserOrigin;
            newNumber[v.Version] = nv;
        }

        // "Restored from" pointed at a browser version number: point it at that version's new number.
        foreach (var v in b.Versions)
        {
            if (newNumber.TryGetValue(v.Version, out var nv))
            {
                nv.RestoredFrom = v.RestoredFrom is int r && newNumber.TryGetValue(r, out var from) ? from.Version : null;
            }
        }

        if (b.CurrentVersion() is SaveVersion bc && newNumber.TryGetValue(bc.Version, out var browserCurrent))
        {
            // The app's save in use, or (after "Start fresh") its newest version: the browser's wins only if newer.
            // (A "current" that is a claimed copy came from rebuilding a list lost mid-merge: not the app's.)
            var app = h.CurrentVersion() is { } mc && !claimed.Contains(mc.Version)
                ? mc
                : h.Versions.Where(x => x.Origin is null).MaxBy(x => x.Created);
            if (app is null || bc.Created > app.Created)
            {
                h.Current = browserCurrent.Version;
            }
        }

        h.Versions.Sort((x, y) => x.Version.CompareTo(y.Version));
        h.GameName ??= b.GameName;
        h.Console ??= b.Console;
        h.BrowserMerged = _clock.GetUtcNow();
        await AtomicFile.WriteAsync(Path.Combine(dir, HistoryFileName), Encoding.UTF8.GetBytes(JsonSerializer.Serialize(h, JsonOptions)), true, CancellationToken.None).ConfigureAwait(false);
        _logger.LogInformation(
            "Merged {Count} old DS browser save versions into one save list in {Dir} (save in use: version {Current}; {Damaged} damaged left behind)",
            newNumber.Count,
            dir,
            h.Current,
            damaged);
        TidyBrowserFolder(browserDir, h);
    }

    /// <summary>
    /// After the merge is written: removes the old browser folder's version files whose bytes are in the one list, then
    /// its history.json and the folder. Anything else (a damaged file) is kept: the folder is renamed
    /// <c>browser.kept</c> so it is never merged or tidied again, and a warning says so.
    /// </summary>
    private void TidyBrowserFolder(string browserDir, SaveHistory h)
    {
        try
        {
            var have = h.Versions.Select(v => v.Sha256).ToHashSet(StringComparer.Ordinal);
            foreach (var f in Directory.EnumerateFiles(browserDir, "v*.*").ToList())
            {
                if (VersionFileRegex().IsMatch(Path.GetFileName(f)) && have.Contains(Sha256Hex(File.ReadAllBytes(f))))
                {
                    File.Delete(f);
                }
            }

            AtomicFile.CleanTemps(browserDir);
            if (!Directory.EnumerateFiles(browserDir, "v*.*").Any())
            {
                File.Delete(Path.Combine(browserDir, HistoryFileName));
            }

            if (!Directory.EnumerateFileSystemEntries(browserDir).Any())
            {
                Directory.Delete(browserDir);
                return;
            }

            var kept = browserDir + ".kept";
            if (Directory.Exists(kept))
            {
                kept += "-" + _clock.GetUtcNow().ToString("yyyyMMddHHmmss", CultureInfo.InvariantCulture);
            }

            Directory.Move(browserDir, kept);
            _logger.LogWarning("Some old DS browser save files could not be merged; kept them in {Dir}", kept);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            _logger.LogWarning(ex, "Merged the old DS browser saves but could not tidy {Dir}; will try again next time", browserDir);
        }
    }

    private async Task<SaveHistory> LoadFolderAsync(string dir, Guid item, string channelName, CancellationToken cancellationToken)
    {
        AtomicFile.CleanTemps(dir);
        var path = Path.Combine(dir, HistoryFileName);
        if (File.Exists(path))
        {
            try
            {
                var bytes = await File.ReadAllBytesAsync(path, cancellationToken).ConfigureAwait(false);
                var h = JsonSerializer.Deserialize<SaveHistory>(bytes, JsonOptions);
                if (h is not null)
                {
                    if (AdoptStrays(dir, h))
                    {
                        await WriteRepairedAsync(dir, h).ConfigureAwait(false);
                    }

                    return h;
                }
            }
            catch (JsonException ex)
            {
                var keep = path + ".damaged-" + _clock.GetUtcNow().ToString("yyyyMMddHHmmss", CultureInfo.InvariantCulture);
                File.Move(path, keep, true);
                _logger.LogWarning(ex, "Save history {Path} was damaged; kept a copy as {Copy} and rebuilt it from the save files", path, keep);
            }
        }

        var rebuilt = await RebuildAsync(dir, item, channelName, cancellationToken).ConfigureAwait(false);
        if (rebuilt.Versions.Count > 0)
        {
            await WriteRepairedAsync(dir, rebuilt).ConfigureAwait(false);
        }

        return rebuilt;
    }

    /// <summary>
    /// Writes a history that was repaired while loading (rebuilt, or with stray versions adopted), so the repair and its
    /// warning happen once, not on every read. Every caller of <see cref="LoadAsync"/> holds the game's lock. Nothing is
    /// deleted here, so if the write fails the store is exactly as it was and the repair is tried again next time.
    /// </summary>
    private async Task WriteRepairedAsync(string dir, SaveHistory h)
    {
        try
        {
            await AtomicFile.WriteAsync(Path.Combine(dir, HistoryFileName), Encoding.UTF8.GetBytes(JsonSerializer.Serialize(h, JsonOptions)), true, CancellationToken.None).ConfigureAwait(false);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            _logger.LogWarning(ex, "Could not write the repaired save history in {Dir}; will try again next time", dir);
        }
    }

    /// <summary>A version file with no history entry (crash between writing it and history.json) is kept
    /// and its number is never reused. It is not made current: the person can see it as "recovered".</summary>
    /// <returns>True if any were adopted.</returns>
    private bool AdoptStrays(string dir, SaveHistory h)
    {
        if (!Directory.Exists(dir))
        {
            return false;
        }

        var adopted = false;

        foreach (var f in Directory.EnumerateFiles(dir, "v*.*"))
        {
            var m = VersionFileRegex().Match(Path.GetFileName(f));
            if (!m.Success)
            {
                continue;
            }

            var n = int.Parse(m.Groups[1].Value, CultureInfo.InvariantCulture);
            if (h.Versions.Any(v => v.Version == n))
            {
                continue;
            }

            var bytes = File.ReadAllBytes(f);
            h.Versions.Add(new SaveVersion
            {
                Version = n,
                File = Path.GetFileName(f),
                Size = bytes.Length,
                Sha256 = Sha256Hex(bytes),
                Created = new DateTimeOffset(File.GetLastWriteTimeUtc(f), TimeSpan.Zero),
                Source = "recovered",
            });
            h.NextVersion = Math.Max(h.NextVersion, n + 1);
            _logger.LogWarning("Found save version {File} missing from its history; kept it as \"recovered\"", f);
            adopted = true;
        }

        h.Versions.Sort((a, b) => a.Version.CompareTo(b.Version));
        return adopted;
    }

    private Task<SaveHistory> RebuildAsync(string dir, Guid item, string channelName, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        var h = new SaveHistory { ItemId = item.ToString("N"), Channel = channelName };
        AdoptStrays(dir, h);
        if (h.Versions.Count > 0)
        {
            h.Current = h.Versions[^1].Version;
            _logger.LogWarning("Rebuilt save history for {Dir} from {Count} save files", dir, h.Versions.Count);
        }

        return Task.FromResult(h);
    }
}
