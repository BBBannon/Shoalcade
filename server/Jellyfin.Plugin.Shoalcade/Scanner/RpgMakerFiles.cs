using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Diagnostics.CodeAnalysis;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Plugin.Shoalcade.Common;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Shoalcade.Scanner;

/// <summary>One listed file of an RPG Maker game.</summary>
/// <param name="Path">Path inside the game folder, with <c>/</c>.</param>
/// <param name="Size">Bytes.</param>
/// <param name="Sha256">SHA-256, lower-case hex.</param>
public sealed record RpgMakerListedFile(string Path, long Size, string Sha256);

/// <summary>What the library shows for an RPG Maker game (no hashing needed).</summary>
/// <param name="Engine">"XP", "VX", "VX Ace" or null.</param>
/// <param name="Title">Game.ini title (else the cleaned folder name).</param>
/// <param name="Total">Bytes of all listed files.</param>
/// <param name="Count">Listed files.</param>
/// <param name="MayNotWork">The game ships Windows DLLs we don't recognise.</param>
/// <param name="OldRuby">Made for Ruby 1.8 / 1.9 (<see cref="RpgMakerGame.OldRuby"/>): clients turn on the syntax transform.</param>
public sealed record RpgMakerSummary(string? Engine, string Title, long Total, int Count, bool MayNotWork, bool OldRuby = false);

/// <summary>An RPG Maker game's file list with hashes (<c>GET /shoalcade/games/{id}/files</c>).</summary>
/// <param name="Summary">Engine, title, size, count, badge.</param>
/// <param name="Files">Files, sorted by path (ordinal).</param>
public sealed record RpgMakerManifest(RpgMakerSummary Summary, IReadOnlyList<RpgMakerListedFile> Files);

/// <summary>
/// File lists of RPG Maker game folders (D147) with each file's SHA-256, so the phone and browser only fetch files that
/// changed. Hashes are remembered by (path, size, modified time) in the plugin's data folder (<c>shoalcade/rpgmaker/</c>,
/// one small file per game), never next to the game; game files are only ever opened for reading. The first list of a
/// big game reads every file once (several at a time); later lists only look at sizes and times.
/// </summary>
public sealed class RpgMakerFiles
{
    /// <summary>How long the library's size/count/badge for a game is reused before the folder is looked at again.</summary>
    internal static readonly TimeSpan SummaryFor = TimeSpan.FromMinutes(2);

    private readonly string _root;
    private readonly ILogger _logger;
    private readonly TimeProvider _clock;
    private readonly int _parallel;
    private readonly ConcurrentDictionary<string, GameCache> _games = new(StringComparer.Ordinal);
    private readonly ConcurrentDictionary<string, (RpgMakerSummary Summary, DateTimeOffset At)> _summaries = new(StringComparer.Ordinal);
    private int _hashed;

    /// <summary>Initializes a new instance of the <see cref="RpgMakerFiles"/> class.</summary>
    /// <param name="root">Folder for the hash caches.</param>
    /// <param name="clock">Clock.</param>
    /// <param name="logger">Logger.</param>
    /// <param name="parallel">Files hashed at once (default: one per processor, 2 to 8).</param>
    public RpgMakerFiles(string root, TimeProvider clock, ILogger logger, int parallel = 0)
    {
        _root = root;
        _clock = clock;
        _logger = logger;
        _parallel = parallel > 0 ? parallel : Math.Clamp(Environment.ProcessorCount, 2, 8);
    }

    /// <summary>Gets how many files have actually been read and hashed (tests).</summary>
    internal int HashesComputed => _hashed;

    /// <summary>The library's view of a game: engine, title, total size, file count and the "may not work" badge.
    /// Only looks at names, sizes and times (reused for <see cref="SummaryFor"/>). Null if the folder can't be read.</summary>
    /// <param name="folder">Game folder.</param>
    /// <returns>The summary, or null.</returns>
    public RpgMakerSummary? GetSummary(string folder)
    {
        ArgumentException.ThrowIfNullOrEmpty(folder);
        var key = GameKey(folder);
        if (_summaries.TryGetValue(key, out var s) && _clock.GetUtcNow() - s.At < SummaryFor)
        {
            return s.Summary;
        }

        try
        {
            return Summarise(key, RpgMakerGame.ListFiles(key));
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or InvalidDataException)
        {
            _logger.LogInformation("Could not look at RPG Maker game {Folder}: {Message}", key, ex.Message);
            return null;
        }
    }

    /// <summary>
    /// The game's file list with SHA-256s. One list is worked out at a time per game (a second request waits for the
    /// first, then reuses its hashes); different games don't wait for each other.
    /// </summary>
    /// <param name="folder">Game folder.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>The list.</returns>
    /// <exception cref="IOException">A file could not be read (try again).</exception>
    public async Task<RpgMakerManifest> GetManifestAsync(string folder, CancellationToken cancellationToken)
    {
        ArgumentException.ThrowIfNullOrEmpty(folder);
        var key = GameKey(folder);
        var game = CacheFor(key);
        await game.Lock.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            var files = RpgMakerGame.ListFiles(key);
            var known = game.Load(_logger);
            var listed = new RpgMakerListedFile[files.Count];
            var todo = new List<int>();
            for (var i = 0; i < files.Count; i++)
            {
                var f = files[i];
                if (known.TryGetValue(f.Path, out var e) && e.Size == f.Size && e.Modified == f.Modified && e.Sha256.Length == 64)
                {
                    listed[i] = new RpgMakerListedFile(f.Path, f.Size, e.Sha256);
                }
                else
                {
                    todo.Add(i);
                }
            }

            if (todo.Count > 0)
            {
                // Biggest first, so one huge file doesn't start last and keep everyone waiting.
                todo.Sort((a, b) => files[b].Size.CompareTo(files[a].Size));
                var options = new ParallelOptions { MaxDegreeOfParallelism = _parallel, CancellationToken = cancellationToken };
                await Parallel.ForEachAsync(todo, options, async (i, ct) =>
                {
                    var (file, sha) = await HashAsync(files[i], ct).ConfigureAwait(false);
                    files[i] = file;
                    listed[i] = new RpgMakerListedFile(file.Path, file.Size, sha);
                    Interlocked.Increment(ref _hashed);
                }).ConfigureAwait(false);
            }

            // Saved only if a file was hashed or one has gone; otherwise the cache already is exactly this list.
            if (todo.Count > 0 || files.Count != known.Count)
            {
                var fresh = new Dictionary<string, CacheEntry>(files.Count, StringComparer.Ordinal);
                for (var i = 0; i < files.Count; i++)
                {
                    fresh[files[i].Path] = new CacheEntry { Size = files[i].Size, Modified = files[i].Modified, Sha256 = listed[i].Sha256 };
                }

                await game.SaveAsync(key, fresh, _logger, cancellationToken).ConfigureAwait(false);
            }

            return new RpgMakerManifest(Summarise(key, files), listed);
        }
        finally
        {
            game.Lock.Release();
        }
    }

    /// <summary>
    /// The SHA-256 already worked out for one file of a game (by a file list), if its size and time are still the same;
    /// null if it isn't known yet. Never reads the file or looks in the game folder (a quick look-up for each file request).
    /// </summary>
    /// <param name="gameRoot">The real game folder (holding Game.ini, <see cref="RpgMakerGame.GameRoot"/>), as the file
    /// route already worked it out.</param>
    /// <param name="path">Path inside the game folder, as listed.</param>
    /// <param name="file">The file now.</param>
    /// <returns>Lower-case hex SHA-256, or null.</returns>
    public string? KnownSha256(string gameRoot, string path, FileInfo file)
    {
        ArgumentNullException.ThrowIfNull(file);
        var game = CacheFor(Path.GetFullPath(gameRoot));
        var entries = game.Known;
        if (entries is null && game.Lock.Wait(0))
        {
            try
            {
                entries = game.Load(_logger);
            }
            finally
            {
                game.Lock.Release();
            }
        }

        return entries is not null && entries.TryGetValue(path, out var e) && e.Size == file.Length && e.Modified == file.LastWriteTimeUtc.Ticks
            ? e.Sha256
            : null;
    }

    /// <summary>The real game folder (holding Game.ini, <see cref="RpgMakerGame.GameRoot"/>) of a game item's folder, full
    /// path: file paths, hashes and the cache are all for that folder.</summary>
    /// <param name="folder">Game item folder (or the real game folder itself).</param>
    /// <returns>Full path.</returns>
    internal static string GameKey(string folder)
    {
        var full = Path.GetFullPath(folder);
        return RpgMakerGame.GameRoot(full) ?? full;
    }

    /// <summary>The cache file name for a game folder (a hash of its full path, so any folder name is safe).</summary>
    /// <param name="fullPath">Full folder path.</param>
    /// <returns>File name.</returns>
    internal static string CacheName(string fullPath) =>
        Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(fullPath)), 0, 16) + ".json";

    /// <summary>Opens a game file to read (never locks it against the admin, never writes).</summary>
    /// <param name="fullPath">The file.</param>
    /// <returns>The stream.</returns>
    internal static FileStream OpenRead(string fullPath) =>
        new(fullPath, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete, 128 * 1024, FileOptions.Asynchronous | FileOptions.SequentialScan);

    private GameCache CacheFor(string key) => _games.GetOrAdd(key, k => new GameCache(Path.Combine(_root, CacheName(k))));

    // Works out the library's view of a game and remembers it for GetSummary (SummaryFor).
    private RpgMakerSummary Summarise(string folder, List<RpgMakerFile> files)
    {
        var ini = RpgMakerGame.FindIni(folder) is string p ? RpgMakerGame.ReadIni(p) : new RpgMakerIni(null, null);
        var engine = ini.Engine ?? RpgMakerGame.ScriptEngines
            .FirstOrDefault(e => files.Any(f => string.Equals(f.Path, "Data/Scripts" + e.Extension, StringComparison.OrdinalIgnoreCase))).Engine;
        var summary = new RpgMakerSummary(engine, RpgMakerGame.DisplayName(folder, ini), files.Sum(f => f.Size), files.Count, RpgMakerGame.MayNotWork(folder), RpgMakerGame.OldRuby(folder));
        _summaries[folder] = (summary, _clock.GetUtcNow());
        return summary;
    }

    /// <summary>Reads and hashes one file. If it changed while being read, it is read again (up to three times).</summary>
    private static async Task<(RpgMakerFile File, string Sha256)> HashAsync(RpgMakerFile file, CancellationToken cancellationToken)
    {
        for (var attempt = 0; ; attempt++)
        {
            string sha;
            var fs = OpenRead(file.FullPath);
            await using (fs.ConfigureAwait(false))
            {
                sha = Convert.ToHexStringLower(await SHA256.HashDataAsync(fs, cancellationToken).ConfigureAwait(false));
            }

            var now = new FileInfo(file.FullPath);
            if (now.Exists && now.Length == file.Size && now.LastWriteTimeUtc.Ticks == file.Modified)
            {
                return (file, sha);
            }

            if (attempt >= 2 || !now.Exists)
            {
                throw new IOException("The file " + file.Path + " changed while it was being read.");
            }

            file = file with { Size = now.Length, Modified = now.LastWriteTimeUtc.Ticks };
        }
    }

    /// <summary>One game's remembered hashes.</summary>
    private sealed class GameCache
    {
        private Dictionary<string, CacheEntry>? _entries;

        public GameCache(string file) => File = file;

        public string File { get; }

        /// <summary>Gets the loaded hashes (never changed once set: a new list replaces it), or null if not loaded.</summary>
        public Dictionary<string, CacheEntry>? Known => Volatile.Read(ref _entries);

        [SuppressMessage("Usage", "CA2213:Disposable fields should be disposed", Justification = "Lives as long as the plugin; its wait handle is never used")]
        public SemaphoreSlim Lock { get; } = new(1, 1);

        // Caller holds Lock.
        public Dictionary<string, CacheEntry> Load(ILogger logger)
        {
            if (_entries is not null)
            {
                return _entries;
            }

            _entries = new Dictionary<string, CacheEntry>(StringComparer.Ordinal);
            try
            {
                if (System.IO.File.Exists(File) && JsonSerializer.Deserialize<CacheFile>(System.IO.File.ReadAllBytes(File)) is { Files: { } files })
                {
                    _entries = new Dictionary<string, CacheEntry>(files, StringComparer.Ordinal);
                }
            }
            catch (Exception ex) when (ex is IOException or JsonException or UnauthorizedAccessException)
            {
                logger.LogInformation("Starting a new RPG Maker hash cache ({Message})", ex.Message);
            }

            return _entries;
        }

        // Caller holds Lock.
        public async Task SaveAsync(string folder, Dictionary<string, CacheEntry> entries, ILogger logger, CancellationToken cancellationToken)
        {
            _entries = entries;
            try
            {
                Directory.CreateDirectory(Path.GetDirectoryName(File)!);
                var json = JsonSerializer.SerializeToUtf8Bytes(new CacheFile { Folder = folder, Files = entries });
                await AtomicFile.WriteAsync(File, json, overwrite: true, cancellationToken).ConfigureAwait(false);
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
            {
                // Only costs hashing again next time.
                logger.LogWarning("Could not save the RPG Maker hash cache {File}: {Message}", File, ex.Message);
            }
        }
    }

    /// <summary>The cache file.</summary>
    internal sealed class CacheFile
    {
        /// <summary>Gets or sets the game folder (for people reading the file).</summary>
        public string Folder { get; set; } = string.Empty;

        /// <summary>Gets or sets the hashes by path.</summary>
        [SuppressMessage("Usage", "CA2227:Collection properties should be read only", Justification = "JSON")]
        public Dictionary<string, CacheEntry> Files { get; set; } = new(StringComparer.Ordinal);
    }

    /// <summary>One remembered hash.</summary>
    internal sealed class CacheEntry
    {
        /// <summary>Gets or sets the size when hashed.</summary>
        public long Size { get; set; }

        /// <summary>Gets or sets the last-write time (UTC ticks) when hashed.</summary>
        public long Modified { get; set; }

        /// <summary>Gets or sets the SHA-256, lower-case hex.</summary>
        public string Sha256 { get; set; } = string.Empty;
    }
}
