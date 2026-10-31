using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Plugin.Shoalcade.Cheats;
using Jellyfin.Plugin.Shoalcade.Common;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;

namespace Jellyfin.Plugin.Shoalcade.States;

/// <summary>Information about one browser snapshot slot.</summary>
public sealed class StateInfo
{
    /// <summary>Gets or sets the slot: 1, 2, 3 or auto.</summary>
    [JsonPropertyName("slot")]
    public string Slot { get; set; } = string.Empty;

    /// <summary>Gets or sets the size in bytes.</summary>
    [JsonPropertyName("size")]
    public long Size { get; set; }

    /// <summary>Gets or sets when it was taken (UTC).</summary>
    [JsonPropertyName("created")]
    public DateTimeOffset Created { get; set; }

    /// <summary>Gets or sets the emulator core that made it (snapshots only load in the same core).</summary>
    [JsonPropertyName("core")]
    public string? Core { get; set; }

    /// <summary>Gets or sets a value indicating whether a screenshot is stored with it.</summary>
    [JsonPropertyName("hasImage")]
    public bool HasImage { get; set; }

    /// <summary>Gets or sets the snapshot's SHA-256 (lower-case hex; null for older snapshots).</summary>
    [JsonPropertyName("sha256")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Sha256 { get; set; }

    /// <summary>Gets or sets a value indicating whether the slot's info file is damaged. The snapshot file is still
    /// kept, but it can't be checked or loaded; size and time then come from the file itself (L8).</summary>
    [JsonPropertyName("damaged")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingDefault)]
    public bool Damaged { get; set; }

    /// <summary>Gets or sets the console key of the game it was taken in (F8: so a snapshot of a game that left the
    /// library can still be listed and moved; null for older snapshots).</summary>
    [JsonPropertyName("console")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Console { get; set; }

    /// <summary>Gets or sets the game's name when it was taken (F8; null for older snapshots).</summary>
    [JsonPropertyName("gameName")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? GameName { get; set; }

    /// <summary>Gets or sets the cheats that were on when it was taken (D143), name and code each, so loading it brings
    /// back exactly those. Null = none (or an older snapshot).</summary>
    [JsonPropertyName("cheats")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public List<SnapshotCheat>? Cheats { get; set; }
}

/// <summary>Result of storing a snapshot's screenshot.</summary>
/// <param name="Info">Updated info, or null if refused.</param>
/// <param name="OverQuota">True if refused because the user's snapshot storage is full.</param>
public sealed record StateImageResult(StateInfo? Info, bool OverQuota);

/// <summary>Result of moving a game's snapshots to another game.</summary>
/// <param name="Moved">Slots moved.</param>
/// <param name="Kept">Slots kept with the old game because the target already has a snapshot there.</param>
/// <param name="Damaged">Slots kept with the old game because they couldn't be checked or copied.</param>
public sealed record StateMoveResult(IReadOnlyList<string> Moved, IReadOnlyList<string> Kept, IReadOnlyList<string> Damaged);

/// <summary>
/// Browser snapshots (save states, D37): 3 slots + 1 automatic, per user per game, at
/// <c>{root}/{userId}/{itemId}/{slot}.state</c> with a <c>.json</c> info file and an optional <c>.png</c>.
/// The previous snapshot in a slot is kept as <c>.state.bak</c>.
/// </summary>
public sealed class StateStore
{
    /// <summary>Allowed slots.</summary>
    public static readonly IReadOnlyList<string> Slots = ["1", "2", "3", "auto"];

    private const int MaxImageBytes = 2 * 1024 * 1024;
    private const string UploadsFolder = ".uploads";
    private static readonly JsonSerializerOptions JsonOptions = new() { WriteIndented = true };

    private readonly string _root;
    private readonly TimeProvider _clock;
    private readonly KeyedLocks _locks = new();
    private readonly ILogger _logger;

    /// <summary>Initializes a new instance of the <see cref="StateStore"/> class.</summary>
    /// <param name="root">Snapshots root folder.</param>
    /// <param name="clock">Clock.</param>
    /// <param name="logger">Logger (none = don't log).</param>
    public StateStore(string root, TimeProvider clock, ILogger? logger = null)
    {
        _root = Path.GetFullPath(root);
        _clock = clock;
        _logger = logger ?? NullLogger.Instance;
    }

    /// <summary>True if the slot name is allowed.</summary>
    /// <param name="slot">Slot.</param>
    /// <returns>Whether it is valid.</returns>
    public static bool IsValidSlot(string? slot) => slot is not null && Slots.Contains(slot, StringComparer.Ordinal);

    /// <summary>True if the bytes start with the PNG signature.</summary>
    /// <param name="data">Bytes.</param>
    /// <returns>Whether it is a PNG.</returns>
    public static bool IsPng(ReadOnlySpan<byte> data) =>
        data.Length > 8 && data[..8].SequenceEqual(new byte[] { 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A });

    /// <summary>Lists the filled slots.</summary>
    /// <param name="user">User id.</param>
    /// <param name="item">Item id.</param>
    /// <returns>Slot infos.</returns>
    public IReadOnlyList<StateInfo> List(Guid user, Guid item)
    {
        var dir = Dir(user, item);
        var list = new List<StateInfo>();
        foreach (var slot in Slots)
        {
            var info = ReadInfo(dir, slot);
            if (info is not null)
            {
                list.Add(info);
            }
        }

        return list;
    }

    /// <summary>Opens a snapshot.</summary>
    /// <param name="user">User id.</param>
    /// <param name="item">Item id.</param>
    /// <param name="slot">Slot.</param>
    /// <param name="image">True for the screenshot instead of the snapshot.</param>
    /// <returns>Stream and info, or null.</returns>
    public (StateInfo Info, Stream Data)? Open(Guid user, Guid item, string slot, bool image)
    {
        var dir = Dir(user, item);
        var info = ReadInfo(dir, slot);
        if (info is null || info.Damaged || (image && !info.HasImage))
        {
            return null;
        }

        var path = Path.Combine(dir, slot + (image ? ".png" : ".state"));
        try
        {
            return (info, new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read | FileShare.Delete, 81920, FileOptions.Asynchronous));
        }
        catch (FileNotFoundException)
        {
            return null;
        }
    }

    /// <summary>
    /// A new, unused path to stream an upload to (M1). It is in the snapshots root's <c>.uploads</c> folder, on the same
    /// disk as the slots, so <see cref="PutFileAsync"/> can rename it into place. Leftovers older than a day are removed.
    /// </summary>
    /// <returns>The path (the file does not exist yet).</returns>
    public string NewUploadPath()
    {
        var dir = Path.Combine(_root, UploadsFolder);
        Directory.CreateDirectory(dir);
        var cutoff = _clock.GetUtcNow().UtcDateTime.AddDays(-1);
        foreach (var f in Directory.EnumerateFiles(dir, AtomicFile.TempPrefix + "*"))
        {
            if (File.GetLastWriteTimeUtc(f) < cutoff)
            {
                TryDelete(f);
            }
        }

        return Path.Combine(dir, AtomicFile.TempPrefix + Guid.NewGuid().ToString("N"));
    }

    /// <summary>Stores a snapshot already streamed to <paramref name="uploaded"/> (from <see cref="NewUploadPath"/>).
    /// The file is moved into the slot (the previous one is kept as .bak). If refused, the caller still owns the file.</summary>
    /// <param name="user">User id.</param>
    /// <param name="item">Item id.</param>
    /// <param name="slot">Slot.</param>
    /// <param name="uploaded">The streamed file (already flushed to disk).</param>
    /// <param name="sha256">Its SHA-256.</param>
    /// <param name="core">Core that made it.</param>
    /// <param name="quotaBytes">User snapshot quota (0 = none).</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <param name="console">Console key of the game (F8, kept in the info).</param>
    /// <param name="gameName">The game's name (F8, kept in the info).</param>
    /// <returns>The new info, or null if over quota.</returns>
    public async Task<StateInfo?> PutFileAsync(Guid user, Guid item, string slot, string uploaded, string? sha256, string? core, long quotaBytes, CancellationToken cancellationToken, string? console = null, string? gameName = null)
    {
        var size = new FileInfo(uploaded).Length;
        var dir = Dir(user, item);
        using (await _locks.AcquireAsync(dir, cancellationToken).ConfigureAwait(false))
        using (await _locks.AcquireAsync(QuotaKey(user), cancellationToken).ConfigureAwait(false))
        {
            // s0.x L4: the per-user quota lock (always taken after the game lock) makes check + write one step, so
            // parallel uploads to different games can't all pass the check and go over the quota together.
            // The info file counts too, so the quota really is a ceiling.
            var info = new StateInfo { Slot = slot, Size = size, Created = _clock.GetUtcNow(), Core = CleanCore(core), HasImage = false, Sha256 = sha256, Console = console, GameName = gameName };
            if (quotaBytes > 0 && UsedBytes(user) + size + InfoBytes(info).Length > quotaBytes)
            {
                return null;
            }

            Directory.CreateDirectory(dir);
            var path = Path.Combine(dir, slot + ".state");
            if (File.Exists(path))
            {
                File.Copy(path, path + ".bak", true);
            }

            TryDelete(Path.Combine(dir, slot + ".png"));
            await AtomicFile.MoveInAsync(uploaded, path, cancellationToken).ConfigureAwait(false);
            await WriteInfoAsync(dir, info, cancellationToken).ConfigureAwait(false);
            return info;
        }
    }

    /// <summary>Stores the screenshot for a filled slot.</summary>
    /// <param name="user">User id.</param>
    /// <param name="item">Item id.</param>
    /// <param name="slot">Slot.</param>
    /// <param name="png">PNG bytes.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>Updated info, or null if the slot is empty or the image is not a small PNG.</returns>
    public async Task<StateInfo?> PutImageAsync(Guid user, Guid item, string slot, ReadOnlyMemory<byte> png, CancellationToken cancellationToken) =>
        (await PutImageAsync(user, item, slot, png, 0, cancellationToken).ConfigureAwait(false)).Info;

    /// <summary>Stores the screenshot for a filled slot, within the user's snapshot quota (s0.x L4).</summary>
    /// <param name="user">User id.</param>
    /// <param name="item">Item id.</param>
    /// <param name="slot">Slot.</param>
    /// <param name="png">PNG bytes.</param>
    /// <param name="quotaBytes">User snapshot quota (0 = none).</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>Updated info, or why not (empty slot / not a small PNG, or over quota).</returns>
    public async Task<StateImageResult> PutImageAsync(Guid user, Guid item, string slot, ReadOnlyMemory<byte> png, long quotaBytes, CancellationToken cancellationToken)
    {
        if (png.Length > MaxImageBytes || !IsPng(png.Span))
        {
            return new StateImageResult(null, false);
        }

        var dir = Dir(user, item);
        using (await _locks.AcquireAsync(dir, cancellationToken).ConfigureAwait(false))
        using (await _locks.AcquireAsync(QuotaKey(user), cancellationToken).ConfigureAwait(false))
        {
            var info = ReadInfo(dir, slot);
            if (info is null || info.Damaged)
            {
                return new StateImageResult(null, false);
            }

            var path = Path.Combine(dir, slot + ".png");
            var old = File.Exists(path) ? new FileInfo(path).Length : 0;
            if (quotaBytes > 0 && png.Length > old && UsedBytes(user) - old + png.Length > quotaBytes)
            {
                return new StateImageResult(null, true);
            }

            await AtomicFile.WriteAsync(path, png, true, cancellationToken).ConfigureAwait(false);
            info.HasImage = true;
            await WriteInfoAsync(dir, info, cancellationToken).ConfigureAwait(false);
            return new StateImageResult(info, false);
        }
    }

    /// <summary>Stores which cheats were on with a snapshot (D143). An empty list clears it.</summary>
    /// <param name="user">User id.</param>
    /// <param name="item">Item id.</param>
    /// <param name="slot">Slot.</param>
    /// <param name="cheats">Tidy cheats (name and code each).</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>Updated info, or null if the slot is empty or damaged.</returns>
    public async Task<StateInfo?> PutCheatsAsync(Guid user, Guid item, string slot, IReadOnlyList<SnapshotCheat> cheats, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(cheats);
        var dir = Dir(user, item);
        using (await _locks.AcquireAsync(dir, cancellationToken).ConfigureAwait(false))
        {
            var info = ReadInfo(dir, slot);
            if (info is null || info.Damaged)
            {
                return null;
            }

            info.Cheats = cheats.Count == 0 ? null : [.. cheats];
            await WriteInfoAsync(dir, info, cancellationToken).ConfigureAwait(false);
            return info;
        }
    }

    /// <summary>
    /// Moves a game's snapshots to another game (B9: the ROM got a new id and its save was moved with "Move to another
    /// game"). Each slot is copied, the copy checked (size, and SHA-256 when known), and only then the source removed. A
    /// slot the target already uses is never overwritten: that snapshot stays with the old game. Same user only.
    /// </summary>
    /// <param name="user">User id.</param>
    /// <param name="fromItem">Old item id.</param>
    /// <param name="toItem">New item id.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <param name="toConsole">The new game's console key, recorded on each moved slot (null = keep the old one).</param>
    /// <param name="toGameName">The new game's name, recorded on each moved slot (null = keep the old one).</param>
    /// <returns>Slots moved, and slots kept with the old game.</returns>
    public async Task<StateMoveResult> MoveAsync(Guid user, Guid fromItem, Guid toItem, CancellationToken cancellationToken, string? toConsole = null, string? toGameName = null)
    {
        var moved = new List<string>();
        var kept = new List<string>();
        var damaged = new List<string>();
        var fromDir = Dir(user, fromItem);
        var toDir = Dir(user, toItem);
        if (fromItem == toItem || !Directory.Exists(fromDir))
        {
            return new StateMoveResult(moved, kept, damaged);
        }

        // Both game locks, always in the same order, so two opposite moves can't wait on each other for ever.
        var (first, second) = string.CompareOrdinal(fromDir, toDir) < 0 ? (fromDir, toDir) : (toDir, fromDir);
        using (await _locks.AcquireAsync(first, cancellationToken).ConfigureAwait(false))
        using (await _locks.AcquireAsync(second, cancellationToken).ConfigureAwait(false))
        {
            foreach (var slot in Slots)
            {
                var src = Path.Combine(fromDir, slot + ".state");
                if (!File.Exists(src))
                {
                    continue;
                }

                var dst = Path.Combine(toDir, slot + ".state");
                if (File.Exists(dst) || File.Exists(Path.Combine(toDir, slot + ".json")))
                {
                    kept.Add(slot); // never overwrite a slot
                    continue;
                }

                var info = ReadInfo(fromDir, slot);
                if (info is null || info.Damaged)
                {
                    damaged.Add(slot); // can't be checked, so it stays where it is
                    continue;
                }

                Directory.CreateDirectory(toDir);
                var parts = new List<(string From, string To)> { (src, dst) };
                if (info.HasImage && File.Exists(Path.Combine(fromDir, slot + ".png")))
                {
                    parts.Add((Path.Combine(fromDir, slot + ".png"), Path.Combine(toDir, slot + ".png")));
                }

                // The backup of the slot's previous snapshot goes too, unless the target keeps one of its own there.
                if (File.Exists(src + ".bak") && !File.Exists(dst + ".bak"))
                {
                    parts.Add((src + ".bak", dst + ".bak"));
                }

                if (!await CopyCheckedAsync(parts, info.Sha256, cancellationToken).ConfigureAwait(false))
                {
                    damaged.Add(slot);
                    continue;
                }

                // Info last: the slot only shows at the target once every file is in place and checked.
                info.Console = toConsole ?? info.Console;
                info.GameName = toGameName ?? info.GameName;
                await WriteInfoAsync(toDir, info, cancellationToken).ConfigureAwait(false);
                TryDelete(Path.Combine(fromDir, slot + ".json"));
                foreach (var (from, _) in parts)
                {
                    TryDelete(from);
                }

                moved.Add(slot);
            }
        }

        if (moved.Count > 0 || kept.Count > 0 || damaged.Count > 0)
        {
            _logger.LogInformation(
                "Snapshots of game {From} moved to {To}: moved {Moved}; kept with the old game {Kept} (target slot in use) and {Damaged} (couldn't be checked)",
                fromItem,
                toItem,
                string.Join(",", moved),
                string.Join(",", kept),
                string.Join(",", damaged));
        }

        return new StateMoveResult(moved, kept, damaged);
    }

    /// <summary>Deletes a snapshot (kept as .bak until the slot is next used).</summary>
    /// <param name="user">User id.</param>
    /// <param name="item">Item id.</param>
    /// <param name="slot">Slot.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>True if something was deleted.</returns>
    public async Task<bool> DeleteAsync(Guid user, Guid item, string slot, CancellationToken cancellationToken)
    {
        var dir = Dir(user, item);
        using (await _locks.AcquireAsync(dir, cancellationToken).ConfigureAwait(false))
        {
            var path = Path.Combine(dir, slot + ".state");
            if (!File.Exists(path))
            {
                return false;
            }

            File.Move(path, path + ".bak", true);
            TryDelete(Path.Combine(dir, slot + ".json"));
            TryDelete(Path.Combine(dir, slot + ".png"));
            return true;
        }
    }

    /// <summary>Deletes every snapshot of a game for good (no .bak kept: nothing would ever show it again).</summary>
    /// <param name="user">User id.</param>
    /// <param name="item">Item id.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>How many snapshots there were.</returns>
    public async Task<int> DeleteAllAsync(Guid user, Guid item, CancellationToken cancellationToken)
    {
        var dir = Dir(user, item);
        using (await _locks.AcquireAsync(dir, cancellationToken).ConfigureAwait(false))
        {
            var n = List(user, item).Count;
            if (!Directory.Exists(dir))
            {
                return n;
            }

            foreach (var f in Directory.EnumerateFiles(dir))
            {
                TryDelete(f);
            }

            try
            {
                if (!Directory.EnumerateFileSystemEntries(dir).Any())
                {
                    Directory.Delete(dir);
                }
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
            {
                // An empty folder left behind is harmless.
            }

            return n;
        }
    }

    /// <summary>Every game this user has at least one snapshot slot for (F8: including games no longer in the library).</summary>
    /// <param name="user">User id.</param>
    /// <returns>Item ids.</returns>
    public IReadOnlyList<Guid> Items(Guid user)
    {
        var userDir = Path.Combine(_root, user.ToString("N"));
        var items = new List<Guid>();
        if (!Directory.Exists(userDir))
        {
            return items;
        }

        foreach (var dir in Directory.EnumerateDirectories(userDir))
        {
            if (Guid.TryParseExact(Path.GetFileName(dir), "N", out var item) && List(user, item).Count > 0)
            {
                items.Add(item);
            }
        }

        return items;
    }

    /// <summary>Bytes used by a user's snapshots.</summary>
    /// <param name="user">User id.</param>
    /// <returns>Bytes.</returns>
    public long UsedBytes(Guid user) => DiskUsage.Bytes(Path.Combine(_root, user.ToString("N")));

    // Copies each file to a temp name next to its target, checks its size (and the snapshot's SHA-256 if known), then
    // renames each into place without overwriting. On any failure every copy is removed and the source is untouched.
    private static async Task<bool> CopyCheckedAsync(List<(string From, string To)> parts, string? sha256, CancellationToken cancellationToken)
    {
        var made = new List<string>();
        var temps = new List<string>();
        try
        {
            for (var i = 0; i < parts.Count; i++)
            {
                var (from, to) = parts[i];
                var tmp = Path.Combine(Path.GetDirectoryName(to)!, AtomicFile.TempPrefix + Guid.NewGuid().ToString("N"));
                made.Add(tmp);
                temps.Add(tmp);
                File.Copy(from, tmp, false);
                if (new FileInfo(tmp).Length != new FileInfo(from).Length)
                {
                    return Undo();
                }

                if (i == 0 && sha256 is not null)
                {
                    var bytes = await File.ReadAllBytesAsync(tmp, cancellationToken).ConfigureAwait(false);
                    if (!string.Equals(Convert.ToHexStringLower(SHA256.HashData(bytes)), sha256, StringComparison.OrdinalIgnoreCase))
                    {
                        return Undo();
                    }
                }
            }

            for (var i = 0; i < parts.Count; i++)
            {
                File.Move(temps[i], parts[i].To, false); // never overwrites
                made.Add(parts[i].To);
            }

            return true;
        }
        catch (IOException)
        {
            return Undo();
        }
        catch (UnauthorizedAccessException)
        {
            return Undo();
        }

        bool Undo()
        {
            foreach (var f in made)
            {
                TryDelete(f);
            }

            return false;
        }
    }

    private static string QuotaKey(Guid user) => "quota:" + user.ToString("N");

    private static string? CleanCore(string? core)
    {
        if (string.IsNullOrEmpty(core) || core.Length > 32 || !core.All(c => char.IsAsciiLetterOrDigit(c) || c is '_' or '-'))
        {
            return null;
        }

        return core;
    }

    private StateInfo? ReadInfo(string dir, string slot)
    {
        var path = Path.Combine(dir, slot + ".json");
        if (!File.Exists(path) || !File.Exists(Path.Combine(dir, slot + ".state")))
        {
            return null;
        }

        try
        {
            var info = JsonSerializer.Deserialize<StateInfo>(File.ReadAllBytes(path), JsonOptions);
            if (info is not null)
            {
                info.Slot = slot;
                return info;
            }

            _logger.LogWarning("Snapshot info {Path} is empty; the slot shows as damaged until it is saved over or deleted", path);
        }
        catch (JsonException ex)
        {
            // The snapshot file is still there (and its .bak); only its info is unreadable (L8).
            _logger.LogWarning(ex, "Snapshot info {Path} is damaged; the slot shows as damaged until it is saved over or deleted", path);
        }

        var state = new FileInfo(Path.Combine(dir, slot + ".state"));
        return new StateInfo { Slot = slot, Size = state.Length, Created = new DateTimeOffset(state.LastWriteTimeUtc, TimeSpan.Zero), Damaged = true };
    }

    private static byte[] InfoBytes(StateInfo info) => Encoding.UTF8.GetBytes(JsonSerializer.Serialize(info, JsonOptions));

    private static Task WriteInfoAsync(string dir, StateInfo info, CancellationToken cancellationToken) =>
        AtomicFile.WriteAsync(Path.Combine(dir, info.Slot + ".json"), InfoBytes(info), true, cancellationToken);

    private static void TryDelete(string path)
    {
        try
        {
            File.Delete(path);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            // Not important (e.g. a file held open by antivirus).
        }
    }

    internal string Dir(Guid user, Guid item) => Path.Combine(_root, user.ToString("N"), item.ToString("N"));
}
