using System;
using System.Collections.Generic;
using System.Diagnostics.CodeAnalysis;
using System.IO;
using System.Linq;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Data;
using Jellyfin.Database.Implementations.Enums;
using Jellyfin.Plugin.Shoalcade.Common;
using MediaBrowser.Controller.Library;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Shoalcade.Library;

/// <summary>One user's home page lists: libraries left out of "My Media" and out of the other home rows (Recently Added, Continue, Next Up).</summary>
/// <param name="MyMedia">Jellyfin's MyMediaExcludes.</param>
/// <param name="Latest">Jellyfin's LatestItemsExcludes.</param>
public sealed record HomeExcludes(IReadOnlyList<Guid> MyMedia, IReadOnlyList<Guid> Latest);

/// <summary>Reads and writes users' home page lists (Jellyfin's per-user display settings).</summary>
public interface IHomeExcludesStore
{
    /// <summary>Gets every user id.</summary>
    /// <returns>User ids.</returns>
    IReadOnlyList<Guid> UserIds();

    /// <summary>Gets a user's lists, or null if the user is gone.</summary>
    /// <param name="user">User id.</param>
    /// <returns>The lists.</returns>
    HomeExcludes? Read(Guid user);

    /// <summary>True if the user's library access (their profile) lets them open this library.</summary>
    /// <param name="user">User id.</param>
    /// <param name="library">Library id.</param>
    /// <returns>Whether they can.</returns>
    bool CanOpen(Guid user, Guid library);

    /// <summary>Saves a user's lists.</summary>
    /// <param name="user">User id.</param>
    /// <param name="excludes">The lists.</param>
    /// <returns>A task.</returns>
    Task SetAsync(Guid user, HomeExcludes excludes);
}

/// <summary>One (user, library) pair the plugin has dealt with.</summary>
public sealed class HomeHiddenEntry
{
    /// <summary>Gets or sets the user id.</summary>
    [JsonPropertyName("user")]
    public Guid User { get; set; }

    /// <summary>Gets or sets the library id.</summary>
    [JsonPropertyName("library")]
    public Guid Library { get; set; }

    /// <summary>Gets or sets a value indicating whether the plugin (not the user) added it to MyMediaExcludes.</summary>
    [JsonPropertyName("myMedia")]
    public bool MyMedia { get; set; }

    /// <summary>Gets or sets a value indicating whether the plugin (not the user) added it to LatestItemsExcludes.</summary>
    [JsonPropertyName("latest")]
    public bool Latest { get; set; }
}

/// <summary>
/// Keeps games libraries off Jellyfin's home page (D109). For each user and games library it adds the library to that user's
/// "My Media" and "other home sections" exclusions <b>once</b>, and records what it added in <c>home-page.json</c>.
/// <para>After that the user is in charge, through the one box Jellyfin shows them for a library (Settings → Home screen →
/// "Display in other home screen sections", which is the Latest list): ticked = games back on their home page, and the
/// plugin never re-hides them. Jellyfin web has no "My Media" box for normal libraries, and saving that settings page drops
/// the library from the My Media list; so while the library is still in the user's Latest list, the plugin puts My Media
/// back to match (only for entries it added).</para>
/// Switching the admin setting off removes only what the plugin added, then forgets the record (switching on again re-applies).
/// Games stay in search, in the Shoalcade pages and in the app: these lists only change what the home page and menus show.
/// </summary>
public sealed class HomePageHider : IDisposable
{
    private static readonly JsonSerializerOptions JsonOptions = new() { WriteIndented = true };

    private readonly string _recordPath;
    private readonly IHomeExcludesStore _store;
    private readonly Func<IReadOnlyCollection<Guid>> _gamesLibraries;
    private readonly Func<bool> _enabled;
    private readonly ILogger _logger;
    [SuppressMessage("Usage", "CA2213:Disposable fields should be disposed", Justification = "Not disposed on purpose: Jellyfin's in-process restart disposes this service while a task may still wait on or hold the lock, and a SemaphoreSlim only needs disposing once its AvailableWaitHandle is used (never here)")]
    private readonly SemaphoreSlim _lock = new(1, 1);
    [SuppressMessage("Usage", "CA2213:Disposable fields should be disposed", Justification = "Not disposed on purpose: Jellyfin's in-process restart disposes this service while a task may still wait on or hold the lock, and a SemaphoreSlim only needs disposing once its AvailableWaitHandle is used (never here)")]
    private readonly SemaphoreSlim _runGate = new(1, 1);
    private readonly AsyncLocal<bool> _holdingLock = new(); // true inside ApplyAsync/UndoAsync and whatever they call
    private int _syncQueued;

    /// <summary>Initializes a new instance of the <see cref="HomePageHider"/> class.</summary>
    /// <param name="recordPath">Where the record of what was added lives.</param>
    /// <param name="store">Users' home page lists.</param>
    /// <param name="gamesLibraries">Ids of the libraries holding games right now.</param>
    /// <param name="enabled">The admin setting.</param>
    /// <param name="logger">Logger.</param>
    public HomePageHider(string recordPath, IHomeExcludesStore store, Func<IReadOnlyCollection<Guid>> gamesLibraries, Func<bool> enabled, ILogger logger)
    {
        _recordPath = recordPath;
        _store = store;
        _gamesLibraries = gamesLibraries;
        _enabled = enabled;
        _logger = logger;
    }

    /// <summary>
    /// Brings everything in line with the admin setting: on = hide every games library (plus <paramref name="extraLibraries"/>,
    /// e.g. one just created and not scanned yet) for users not yet dealt with; off = undo what the plugin added.
    /// </summary>
    /// <param name="extraLibraries">More library ids to treat as games libraries.</param>
    /// <returns>A task.</returns>
    public async Task SyncAsync(IEnumerable<Guid>? extraLibraries = null)
    {
        if (_holdingLock.Value)
        {
            // Our own user change raised "user updated" while we hold the lock (L15): nothing new to do, and waiting
            // for the lock here would wait for ever.
            return;
        }

        if (_enabled())
        {
            var libs = _gamesLibraries().Concat(extraLibraries ?? []).Where(i => i != Guid.Empty).Distinct().ToList();
            await ApplyAsync(libs).ConfigureAwait(false);
        }
        else
        {
            await UndoAsync().ConfigureAwait(false);
        }
    }

    /// <summary>Like <see cref="SyncAsync"/>, but logs instead of throwing (for events and background triggers).</summary>
    /// <param name="extraLibraries">More library ids to treat as games libraries.</param>
    /// <returns>A task.</returns>
    public async Task SyncQuietlyAsync(IEnumerable<Guid>? extraLibraries = null)
    {
        try
        {
            await SyncAsync(extraLibraries).ConfigureAwait(false);
        }
#pragma warning disable CA1031 // a home page tidy-up must never break start-up, scans or user creation
        catch (Exception ex)
#pragma warning restore CA1031
        {
            _logger.LogWarning(ex, "Could not update which libraries show on the Jellyfin home page");
        }
    }

    /// <summary>
    /// Runs <see cref="SyncQuietlyAsync"/> in the background, coalesced (G5 s0.2 L5): at most one run going and one waiting.
    /// A trigger while one is already waiting is dropped, as that run will see the same changes. For request-driven triggers.
    /// </summary>
    /// <returns>The background run (already finished if this trigger was dropped).</returns>
    public Task SyncSoonAsync()
    {
        if (Interlocked.Exchange(ref _syncQueued, 1) == 1)
        {
            return Task.CompletedTask;
        }

        return Task.Run(async () =>
        {
            await _runGate.WaitAsync().ConfigureAwait(false);
            try
            {
                Volatile.Write(ref _syncQueued, 0); // later triggers queue the next run
                await SyncQuietlyAsync().ConfigureAwait(false);
            }
            finally
            {
                _runGate.Release();
            }
        });
    }

    /// <summary>Hides these libraries from the home page of every user not yet dealt with for them, and keeps My Media in step
    /// with the user's own Latest box for pairs already dealt with.</summary>
    /// <param name="libraries">Library ids.</param>
    /// <returns>How many users' settings changed.</returns>
    public async Task<int> ApplyAsync(IReadOnlyCollection<Guid> libraries)
    {
        ArgumentNullException.ThrowIfNull(libraries);
        if (libraries.Count == 0)
        {
            return 0;
        }

        await _lock.WaitAsync().ConfigureAwait(false);
        _holdingLock.Value = true;
        try
        {
            var record = Load();
            var mine = record.ToLookup(e => e.User);
            var changes = new List<(Guid User, HomeExcludes Updated)>();
            var added = false;
            foreach (var user in _store.UserIds())
            {
                var dealt = mine[user].Where(e => libraries.Contains(e.Library)).ToList();

                // Only libraries this user can open: Jellyfin web silently drops any other from their lists the next time
                // they save Settings → Home screen. One they're given later is hidden then (policy change, or next run).
                var todo = libraries.Where(l => !dealt.Any(e => e.Library == l) && _store.CanOpen(user, l)).ToList();
                var current = _store.Read(user);
                if (current is null)
                {
                    continue;
                }

                var myMedia = current.MyMedia.ToList();
                var latest = current.Latest.ToList();
                foreach (var lib in todo)
                {
                    var entry = new HomeHiddenEntry { User = user, Library = lib, MyMedia = !myMedia.Contains(lib), Latest = !latest.Contains(lib) };
                    if (entry.MyMedia)
                    {
                        myMedia.Add(lib);
                    }

                    if (entry.Latest)
                    {
                        latest.Add(lib);
                    }

                    added = true;
                    record.Add(entry); // recorded even when nothing was added: "dealt with", so it is never re-added
                }

                // Already dealt with: follow the user's visible box (the Latest list). Still unticked but My Media lost it
                // (Jellyfin web drops it on every save of Settings → Home screen) = put it back. Ticked = leave alone.
                foreach (var e in dealt.Where(e => e.MyMedia && latest.Contains(e.Library) && !myMedia.Contains(e.Library)))
                {
                    myMedia.Add(e.Library);
                }

                if (myMedia.Count != current.MyMedia.Count || latest.Count != current.Latest.Count)
                {
                    changes.Add((user, new HomeExcludes(myMedia, latest)));
                }
            }

            if (added)
            {
                // Record first: if the server stops half way, the record may claim an entry the user never got (undo then
                // finds nothing to remove), but it can never lose track of one the plugin added.
                await SaveAsync(record).ConfigureAwait(false);
            }

            foreach (var (user, updated) in changes)
            {
                await _store.SetAsync(user, updated).ConfigureAwait(false);
            }

            if (changes.Count > 0)
            {
                _logger.LogInformation("Kept games libraries off the Jellyfin home page for {Count} user(s)", changes.Count);
            }

            return changes.Count;
        }
        finally
        {
            _holdingLock.Value = false;
            _lock.Release();
        }
    }

    /// <summary>Removes only what the plugin added (anything the user set themselves stays), then forgets the record.</summary>
    /// <returns>How many users' settings changed.</returns>
    public async Task<int> UndoAsync()
    {
        await _lock.WaitAsync().ConfigureAwait(false);
        _holdingLock.Value = true;
        try
        {
            var record = Load();
            if (record.Count == 0)
            {
                return 0;
            }

            var changed = 0;
            foreach (var group in record.GroupBy(e => e.User))
            {
                var current = _store.Read(group.Key);
                if (current is null)
                {
                    continue;
                }

                var myMedia = current.MyMedia.ToList();
                var latest = current.Latest.ToList();
                foreach (var e in group)
                {
                    if (e.MyMedia)
                    {
                        myMedia.Remove(e.Library);
                    }

                    if (e.Latest)
                    {
                        latest.Remove(e.Library);
                    }
                }

                if (myMedia.Count != current.MyMedia.Count || latest.Count != current.Latest.Count)
                {
                    await _store.SetAsync(group.Key, new HomeExcludes(myMedia, latest)).ConfigureAwait(false);
                    changed++;
                }
            }

            // Forget only after every user is put back, so a failure part way can simply be retried.
            await SaveAsync([]).ConfigureAwait(false);
            if (changed > 0)
            {
                _logger.LogInformation("Put games libraries back on the Jellyfin home page for {Count} user(s)", changed);
            }

            return changed;
        }
        finally
        {
            _holdingLock.Value = false;
            _lock.Release();
        }
    }

    /// <summary>Gets what the plugin has recorded (for tests and support).</summary>
    /// <returns>The entries.</returns>
    public IReadOnlyList<HomeHiddenEntry> Record() => Load();

    /// <inheritdoc />
    /// <remarks>The lock is not disposed (see its field): a Jellyfin restart can dispose this service while a
    /// start-up task or scan still uses it.</remarks>
    public void Dispose()
    {
    }

    private List<HomeHiddenEntry> Load()
    {
        if (!File.Exists(_recordPath))
        {
            return [];
        }

        // A damaged record throws rather than reading as empty: empty would mean "nothing dealt with" and re-add everything.
        return JsonSerializer.Deserialize<List<HomeHiddenEntry>>(File.ReadAllBytes(_recordPath), JsonOptions) ?? [];
    }

    private Task SaveAsync(List<HomeHiddenEntry> record) =>
        AtomicFile.WriteAsync(_recordPath, JsonSerializer.SerializeToUtf8Bytes(record, JsonOptions), true, CancellationToken.None);
}

/// <summary>Jellyfin's real per-user home page lists (the same ones Settings → Home screen changes).</summary>
public sealed class JellyfinHomeExcludesStore : IHomeExcludesStore
{
    private readonly IUserManager _users;

    /// <summary>Initializes a new instance of the <see cref="JellyfinHomeExcludesStore"/> class.</summary>
    /// <param name="users">User manager.</param>
    public JellyfinHomeExcludesStore(IUserManager users) => _users = users;

    /// <inheritdoc />
    public bool CanOpen(Guid user, Guid library)
    {
        // Jellyfin's own library access rule (Folder.IsVisible), read from the profile so a library made a moment ago counts.
        var u = user == Guid.Empty ? null : _users.GetUserById(user);
        if (u is null)
        {
            return false;
        }

        var blocked = u.GetPreferenceValues<Guid>(PreferenceKind.BlockedMediaFolders);
        return blocked.Length > 0
            ? !blocked.Contains(library)
            : u.HasPermission(PermissionKind.EnableAllFolders) || u.GetPreferenceValues<Guid>(PreferenceKind.EnabledFolders).Contains(library);
    }

    /// <inheritdoc />
    public IReadOnlyList<Guid> UserIds() => _users.GetUsers().Select(u => u.Id).ToList();

    /// <inheritdoc />
    public HomeExcludes? Read(Guid user)
    {
        var u = user == Guid.Empty ? null : _users.GetUserById(user);
        return u is null ? null : new HomeExcludes(u.GetPreferenceValues<Guid>(PreferenceKind.MyMediaExcludes), u.GetPreferenceValues<Guid>(PreferenceKind.LatestItemExcludes));
    }

    /// <inheritdoc />
    public async Task SetAsync(Guid user, HomeExcludes excludes)
    {
        ArgumentNullException.ThrowIfNull(excludes);

        // Fresh copy, change only these two lists, save: every other setting is written back exactly as it was.
        var u = _users.GetUserById(user);
        if (u is null)
        {
            return;
        }

        u.SetPreference(PreferenceKind.MyMediaExcludes, excludes.MyMedia.ToArray());
        u.SetPreference(PreferenceKind.LatestItemExcludes, excludes.Latest.ToArray());
        await _users.UpdateUserAsync(u).ConfigureAwait(false);
    }
}
