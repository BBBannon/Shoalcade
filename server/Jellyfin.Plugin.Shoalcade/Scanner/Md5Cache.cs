using System;
using System.Collections.Generic;
using System.Diagnostics.CodeAnalysis;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Security.Cryptography;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Plugin.Shoalcade.Common;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Shoalcade.Scanner;

/// <summary>
/// MD5 of each ROM (the item's <c>MD5</c> provider id, used to look games up by hash for box art, D48), remembered by
/// path + size + modified time so a big PSP or 3DS file is read once, not on every scan or metadata refresh.
/// The cache file lives in the plugin's data folder (<c>shoalcade/scanner/md5.json</c>), never next to the ROMs;
/// ROM files are only ever opened for reading.
/// </summary>
public sealed class Md5Cache : IDisposable
{
    private static readonly TimeSpan SaveEvery = TimeSpan.FromSeconds(10);

    private readonly string _file;
    private readonly TimeProvider _clock;
    private readonly ILogger _logger;
    private readonly Lock _lock = new();
    [SuppressMessage("Usage", "CA2213:Disposable fields should be disposed", Justification = "Not disposed on purpose: Jellyfin's in-process restart disposes this service while a task may still wait on or hold the lock, and a SemaphoreSlim only needs disposing once its AvailableWaitHandle is used (never here)")]
    private readonly SemaphoreSlim _saveLock = new(1, 1);
    private Dictionary<string, Entry>? _entries;
    private bool _dirty;
    private DateTimeOffset _lastSave;

    /// <summary>Initializes a new instance of the <see cref="Md5Cache"/> class.</summary>
    /// <param name="file">Cache file path.</param>
    /// <param name="clock">Clock.</param>
    /// <param name="logger">Logger.</param>
    public Md5Cache(string file, TimeProvider clock, ILogger logger)
    {
        _file = file;
        _clock = clock;
        _logger = logger;
        _lastSave = clock.GetUtcNow();
    }

    /// <summary>Gets how many files have actually been read and hashed (tests).</summary>
    internal int HashesComputed { get; private set; }

    /// <summary>
    /// The MD5 (lower-case hex) of a ROM. For a .zip, the MD5 of the ROM inside (the largest file), which is what the
    /// game databases list. Null if the file is missing or unreadable.
    /// </summary>
    /// <param name="path">ROM path.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>The hash, or null.</returns>
    public async Task<string?> GetAsync(string path, CancellationToken cancellationToken)
    {
        var info = new FileInfo(path);
        if (!info.Exists)
        {
            return null;
        }

        var size = info.Length;
        var ticks = info.LastWriteTimeUtc.Ticks;
        lock (_lock)
        {
            if (Entries().TryGetValue(path, out var e) && e.Size == size && e.Modified == ticks)
            {
                return e.Md5;
            }
        }

        string md5;
        try
        {
            md5 = await HashAsync(path, cancellationToken).ConfigureAwait(false);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or InvalidDataException)
        {
            _logger.LogInformation("Could not read {Path} to work out its MD5: {Message}", path, ex.Message);
            return null;
        }

        lock (_lock)
        {
            Entries()[path] = new Entry { Size = size, Modified = ticks, Md5 = md5 };
            _dirty = true;
            HashesComputed++;
        }

        if (_clock.GetUtcNow() - _lastSave >= SaveEvery)
        {
            await FlushAsync(cancellationToken).ConfigureAwait(false);
        }

        return md5;
    }

    /// <summary>Writes the cache file if anything changed (after each library scan, and every few seconds during one).</summary>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>A task.</returns>
    public async Task FlushAsync(CancellationToken cancellationToken)
    {
        await _saveLock.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            byte[] json;
            lock (_lock)
            {
                if (!_dirty || _entries is null)
                {
                    return;
                }

                // Forget files that are gone, so the cache doesn't grow for ever.
                foreach (var gone in _entries.Keys.Where(k => !File.Exists(k)).ToList())
                {
                    _entries.Remove(gone);
                }

                json = JsonSerializer.SerializeToUtf8Bytes(_entries);
                _dirty = false;
                _lastSave = _clock.GetUtcNow();
            }

            await AtomicFile.WriteAsync(_file, json, overwrite: true, cancellationToken).ConfigureAwait(false);
        }
        finally
        {
            _saveLock.Release();
        }
    }

    /// <inheritdoc />
    /// <remarks>The lock is not disposed (see its field): a Jellyfin restart can dispose this service while a
    /// start-up task or scan still uses it.</remarks>
    public void Dispose()
    {
    }

    // MD5 here is a game-database lookup key (No-Intro/Redump list MD5s), not security: nothing trusts it.
#pragma warning disable CA5351
    private static async Task<string> HashAsync(string path, CancellationToken cancellationToken)
    {
        var fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete, 1024 * 1024, FileOptions.Asynchronous | FileOptions.SequentialScan);
        await using (fs.ConfigureAwait(false))
        {
            if (string.Equals(Path.GetExtension(path), ".zip", StringComparison.OrdinalIgnoreCase))
            {
                using var zip = new ZipArchive(fs, ZipArchiveMode.Read, leaveOpen: true);
                var rom = zip.Entries.Where(e => e.Length > 0).OrderByDescending(e => e.Length).FirstOrDefault();
                if (rom is not null)
                {
                    var inner = rom.Open();
                    await using (inner.ConfigureAwait(false))
                    {
                        return Convert.ToHexStringLower(await MD5.HashDataAsync(inner, cancellationToken).ConfigureAwait(false));
                    }
                }

                fs.Position = 0;
            }

            return Convert.ToHexStringLower(await MD5.HashDataAsync(fs, cancellationToken).ConfigureAwait(false));
        }
    }
#pragma warning restore CA5351

    // Caller holds _lock.
    private Dictionary<string, Entry> Entries()
    {
        if (_entries is not null)
        {
            return _entries;
        }

        _entries = new Dictionary<string, Entry>(StringComparer.Ordinal);
        try
        {
            if (File.Exists(_file))
            {
                var loaded = JsonSerializer.Deserialize<Dictionary<string, Entry>>(File.ReadAllBytes(_file));
                if (loaded is not null)
                {
                    _entries = new Dictionary<string, Entry>(loaded, StringComparer.Ordinal);
                }
            }
        }
        catch (Exception ex) when (ex is IOException or JsonException or UnauthorizedAccessException)
        {
            // A damaged cache only costs re-hashing; start a fresh one.
            _logger.LogInformation("Starting a new MD5 cache ({Message})", ex.Message);
        }

        return _entries;
    }

    /// <summary>One remembered hash.</summary>
    internal sealed class Entry
    {
        /// <summary>Gets or sets the file size when hashed.</summary>
        public long Size { get; set; }

        /// <summary>Gets or sets the file's last-write time (UTC ticks) when hashed.</summary>
        public long Modified { get; set; }

        /// <summary>Gets or sets the MD5, lower-case hex.</summary>
        public string Md5 { get; set; } = string.Empty;
    }
}
