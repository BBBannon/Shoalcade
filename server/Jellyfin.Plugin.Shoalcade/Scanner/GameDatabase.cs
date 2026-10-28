using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Plugin.Shoalcade.Common;
using Jellyfin.Plugin.Shoalcade.Consoles;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Shoalcade.Scanner;

/// <summary>What libretro-database knows about one ROM (found by its MD5).</summary>
/// <param name="Name">Official No-Intro/Redump name, e.g. "Pokemon - Red Version (USA, Europe) (SGB Enhanced)".</param>
/// <param name="Crc">The ROM's CRC32 (upper-case hex), if listed.</param>
/// <param name="Year">Release year, if listed.</param>
/// <param name="Developer">Developer, if listed.</param>
/// <param name="Publisher">Publisher, if listed.</param>
/// <param name="Genre">Genre, if listed.</param>
public sealed record DatGame(string Name, string? Crc, int? Year, string? Developer, string? Publisher, string? Genre);

/// <summary>
/// Game names, years, developers and publishers from libretro-database (D48), looked up on the server by the ROM's MD5,
/// with no online lookup per game. The .dat files (CC-BY-SA-4.0) are not bundled: they are downloaded the first time a
/// console's game is looked up, into the plugin's data folder (<c>shoalcade/gamedb/</c>), only for consoles that have
/// games, and checked for changes about once a week. With no network the cached copy is used; if a console's list was
/// never downloaded, games simply keep their cleaned file name.
/// </summary>
public sealed class GameDatabase
{
    /// <summary>How long a downloaded list is used before checking for a newer one.</summary>
    internal static readonly TimeSpan RefreshAfter = TimeSpan.FromDays(7);

    /// <summary>How long to wait after a failed download before trying again.</summary>
    internal static readonly TimeSpan RetryAfter = TimeSpan.FromHours(6);

    /// <summary>Largest .dat file accepted (the biggest real one is about 2.5 MB).</summary>
    internal const long MaxDatBytes = 32L * 1024 * 1024;

    private static readonly TimeSpan Timeout = TimeSpan.FromSeconds(60);

    private static readonly string[] ExtraFolders = ["developer", "publisher", "releaseyear", "genre"];

    private static readonly string[] Unfinished = ["beta", "demo", "proto", "prototype", "sample", "kiosk"];

    private readonly string _root;
    private readonly LibretroHttp _http;
    private readonly TimeProvider _clock;
    private readonly ILogger _logger;
    private readonly ConcurrentDictionary<string, ConsoleData> _consoles = new(StringComparer.Ordinal);

    /// <summary>Initializes a new instance of the <see cref="GameDatabase"/> class.</summary>
    /// <param name="root">Folder for the downloaded lists (<c>shoalcade/gamedb</c>).</param>
    /// <param name="http">The polite libretro client.</param>
    /// <param name="clock">Clock.</param>
    /// <param name="logger">Logger.</param>
    public GameDatabase(string root, LibretroHttp http, TimeProvider clock, ILogger logger)
    {
        _root = root;
        _http = http;
        _clock = clock;
        _logger = logger;
    }

    /// <summary>The folder each console's main list comes from: No-Intro for cartridges, Redump for PSP and PS1 discs.</summary>
    /// <param name="console">Console.</param>
    /// <returns>Folder name.</returns>
    public static string MainFolder(GameConsole console) => console?.Key is "psp" or "psx" ? "redump" : "no-intro";

    /// <summary>
    /// Looks a ROM up by MD5. The first lookup for a console downloads its lists if they are missing (one request at
    /// a time); a week-old list is used as it is while a newer one is fetched in the background.
    /// </summary>
    /// <param name="console">Console.</param>
    /// <param name="md5">ROM MD5 (any case).</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>The game, or null if it isn't listed (or no list is available).</returns>
    public async Task<DatGame?> LookupAsync(GameConsole console, string md5, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(console);
        var system = LibretroNames.SystemName(console);
        if (system is null || string.IsNullOrEmpty(md5))
        {
            return null;
        }

        var data = _consoles.GetOrAdd(console.Key, _ => new ConsoleData(console, system));
        var index = await IndexAsync(data, cancellationToken).ConfigureAwait(false);
        return index is not null && index.TryGetValue(md5.ToUpperInvariant(), out var game) ? game : null;
    }

    /// <summary>
    /// Game names in a console's list that match what an admin typed (the "Find a cover" search, D122): every typed word
    /// must start a word of the name (accents and case ignored). Best first: fewest extra words, no beta/demo/prototype,
    /// then shortest. Uses the saved list (downloading it first if this console's was never fetched).
    /// </summary>
    /// <param name="console">Console.</param>
    /// <param name="query">What was typed.</param>
    /// <param name="max">Most names returned.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>Full database names (e.g. "Pokemon - HeartGold Version (USA)").</returns>
    public async Task<IReadOnlyList<string>> SearchAsync(GameConsole console, string query, int max, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(console);
        var system = LibretroNames.SystemName(console);
        if (system is null || string.IsNullOrWhiteSpace(query))
        {
            return [];
        }

        var data = _consoles.GetOrAdd(console.Key, _ => new ConsoleData(console, system));
        if (await IndexAsync(data, cancellationToken).ConfigureAwait(false) is null || data.Index is not { } loaded)
        {
            return [];
        }

        return Search(loaded.Names, query, max);
    }

    /// <summary>The search itself (see <see cref="SearchAsync"/>).</summary>
    /// <param name="names">Every name.</param>
    /// <param name="query">What was typed.</param>
    /// <param name="max">Most names returned.</param>
    /// <returns>Matching names, best first.</returns>
    internal static IReadOnlyList<string> Search(IEnumerable<string> names, string query, int max)
    {
        var want = Words(query);
        if (want.Length == 0 || max <= 0)
        {
            return [];
        }

        return names
            .Select(n => (Name: n, Title: Words(LibretroNames.CutAtBracket(n) ?? n), All: Words(n)))
            .Where(x => want.All(w => x.All.Any(a => a.StartsWith(w, StringComparison.Ordinal))))
            .OrderBy(x => x.Title.Count(t => !want.Any(w => t.StartsWith(w, StringComparison.Ordinal))))
            .ThenBy(x => Unfinished.Any(u => x.All.Contains(u, StringComparer.Ordinal)) ? 1 : 0)
            .ThenBy(x => x.Name.Length)
            .ThenBy(x => x.Name, StringComparer.Ordinal)
            .Select(x => x.Name)
            .Distinct(StringComparer.Ordinal)
            .Take(max)
            .ToList();
    }

    /// <summary>Waits for any background refresh to finish (tests).</summary>
    /// <returns>A task.</returns>
    internal async Task WaitForRefreshesAsync()
    {
        foreach (var d in _consoles.Values)
        {
            if (d.Refresh is Task t)
            {
                await t.ConfigureAwait(false);
            }
        }
    }

    /// <summary>Builds the MD5 index from the text of a console's lists (tests use this directly).</summary>
    /// <param name="main">The No-Intro/Redump list.</param>
    /// <param name="extras">Extra lists by folder (developer, publisher, releaseyear, genre), keyed by CRC.</param>
    /// <returns>MD5 (upper case) to game.</returns>
    internal static Dictionary<string, DatGame> BuildIndex(string main, IReadOnlyDictionary<string, string> extras)
    {
        // CRC -> field value, from each extra list ("rom ( crc XXXXXXXX )" + one field named like the folder).
        var byCrc = new Dictionary<string, Dictionary<string, string>>(StringComparer.OrdinalIgnoreCase);
        foreach (var (folder, text) in extras)
        {
            var values = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
            foreach (var g in DatFile.Parse(text))
            {
                if (g.Fields.TryGetValue(folder, out var value) && !string.IsNullOrWhiteSpace(value))
                {
                    foreach (var rom in g.Roms)
                    {
                        if (rom.TryGetValue("crc", out var crc))
                        {
                            values.TryAdd(crc, value.Trim());
                        }
                    }
                }
            }

            byCrc[folder] = values;
        }

        string? Extra(string folder, string? crc) =>
            crc is not null && byCrc.TryGetValue(folder, out var v) && v.TryGetValue(crc, out var value) ? value : null;

        var index = new Dictionary<string, DatGame>(StringComparer.Ordinal);
        foreach (var g in DatFile.Parse(main))
        {
            if (!g.Fields.TryGetValue("name", out var name) || string.IsNullOrWhiteSpace(name))
            {
                continue;
            }

            foreach (var rom in g.Roms)
            {
                if (!rom.TryGetValue("md5", out var md5) || md5.Length != 32)
                {
                    continue;
                }

                var crc = rom.TryGetValue("crc", out var c) ? c.ToUpperInvariant() : null;
                int? year = int.TryParse(Extra("releaseyear", crc) ?? g.Fields.GetValueOrDefault("releaseyear"), NumberStyles.None, CultureInfo.InvariantCulture, out var y) && y is > 1970 and < 2200 ? y : null;
                index.TryAdd(md5.ToUpperInvariant(), new DatGame(name.Trim(), crc, year, Extra("developer", crc), Extra("publisher", crc), Extra("genre", crc)));
            }
        }

        return index;
    }

    private static bool LooksLikeDat(byte[] body)
    {
        var head = Encoding.UTF8.GetString(body, 0, Math.Min(body.Length, 512)).TrimStart('﻿', ' ', '\t', '\r', '\n');
        return head.StartsWith("clrmamepro", StringComparison.OrdinalIgnoreCase) || head.StartsWith("game", StringComparison.OrdinalIgnoreCase);
    }

    private string FilePath(ConsoleData d, string folder) => Path.Combine(_root, d.Console.Key, folder + ".dat");

    private async Task<Dictionary<string, DatGame>?> IndexAsync(ConsoleData d, CancellationToken cancellationToken)
    {
        var mainFile = FilePath(d, MainFolder(d.Console));
        if (!File.Exists(mainFile))
        {
            if (_clock.GetUtcNow() < d.NextTry)
            {
                return null; // failed recently: no list for now (games keep their file names)
            }

            await d.DownloadLock.WaitAsync(cancellationToken).ConfigureAwait(false);
            try
            {
                if (!File.Exists(mainFile) && _clock.GetUtcNow() >= d.NextTry)
                {
                    await DownloadAsync(d, cancellationToken).ConfigureAwait(false);
                }
            }
            finally
            {
                d.DownloadLock.Release();
            }

            if (!File.Exists(mainFile))
            {
                return null;
            }
        }
        else if (File.GetLastWriteTimeUtc(mainFile) + RefreshAfter < _clock.GetUtcNow().UtcDateTime
                 && _clock.GetUtcNow() >= d.NextTry && d.Refresh is null or { IsCompleted: true })
        {
            // Use the cached list now; fetch a newer one in the background (never blocks a scan on the network).
            d.Refresh = Task.Run(() => RefreshInBackgroundAsync(d), CancellationToken.None);
        }

        return await LoadAsync(d, mainFile, cancellationToken).ConfigureAwait(false);
    }

    private async Task RefreshInBackgroundAsync(ConsoleData d)
    {
        await d.DownloadLock.WaitAsync().ConfigureAwait(false);
        try
        {
            await DownloadAsync(d, CancellationToken.None).ConfigureAwait(false);
        }
        finally
        {
            d.DownloadLock.Release();
        }
    }

    private async Task<Dictionary<string, DatGame>?> LoadAsync(ConsoleData d, string mainFile, CancellationToken cancellationToken)
    {
        var stamp = Interlocked.Read(ref d.Version);
        var loaded = d.Index;
        if (loaded is not null && loaded.Stamp == stamp)
        {
            return loaded.Games;
        }

        // Files are swapped in whole (AtomicFile), so reading while a download runs is safe.
        await d.LoadLock.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            stamp = Interlocked.Read(ref d.Version);
            if (d.Index is { } again && again.Stamp == stamp)
            {
                return again.Games;
            }

            var main = await File.ReadAllTextAsync(mainFile, cancellationToken).ConfigureAwait(false);
            var extras = new Dictionary<string, string>(StringComparer.Ordinal);
            foreach (var folder in ExtraFolders)
            {
                var f = FilePath(d, folder);
                if (File.Exists(f))
                {
                    extras[folder] = await File.ReadAllTextAsync(f, cancellationToken).ConfigureAwait(false);
                }
            }

            var games = BuildIndex(main, extras);
            d.Index = new LoadedIndex(stamp, games);
            _logger.LogInformation("Game database for {Console}: {Count} games", d.Console.DisplayName, games.Count);
            return games;
        }
        catch (IOException ex)
        {
            _logger.LogInformation("Could not read the {Console} game database ({Message}); games keep their file names", d.Console.DisplayName, ex.Message);
            return d.Index?.Games;
        }
        finally
        {
            d.LoadLock.Release();
        }
    }

    // Caller holds d.DownloadLock. Main list first: if that fails nothing else is fetched. Extra lists are optional (some
    // consoles have no year or genre list: a 404 is fine).
    private async Task DownloadAsync(ConsoleData d, CancellationToken cancellationToken)
    {
        var changed = false;
        try
        {
            changed |= await DownloadOneAsync(d, MainFolder(d.Console), required: true, cancellationToken).ConfigureAwait(false);
            foreach (var folder in ExtraFolders)
            {
                changed |= await DownloadOneAsync(d, folder, required: false, cancellationToken).ConfigureAwait(false);
            }
        }
        catch (Exception ex) when (ex is HttpRequestException or IOException or UnauthorizedAccessException)
        {
            d.NextTry = _clock.GetUtcNow() + RetryAfter;
            _logger.LogInformation(
                "Could not download the {Console} game database ({Message}). {What}; trying again in {Hours} hours.",
                d.Console.DisplayName,
                ex.Message,
                File.Exists(FilePath(d, MainFolder(d.Console))) ? "Using the saved copy" : "Games keep their file names for now",
                RetryAfter.TotalHours);
        }

        if (changed)
        {
            Interlocked.Increment(ref d.Version); // reload the index on the next lookup
        }
    }

    // Returns true if the file on disk changed.
    private async Task<bool> DownloadOneAsync(ConsoleData d, string folder, bool required, CancellationToken cancellationToken)
    {
        var file = FilePath(d, folder);
        var etagFile = file + ".etag";
        var etag = File.Exists(file) && File.Exists(etagFile) ? (await File.ReadAllTextAsync(etagFile, cancellationToken).ConfigureAwait(false)).Trim() : null;
        var url = LibretroNames.DatUrl(_http.DatabaseBase, folder, d.System);
        var r = await _http.GetAsync(url, etag, MaxDatBytes, Timeout, cancellationToken).ConfigureAwait(false);
        Directory.CreateDirectory(Path.GetDirectoryName(file)!);
        switch (r.Status)
        {
            case HttpStatusCode.NotModified:
                File.SetLastWriteTimeUtc(file, _clock.GetUtcNow().UtcDateTime); // checked: still current
                return false;
            case HttpStatusCode.OK when r.Body is not null && LooksLikeDat(r.Body):
                await AtomicFile.WriteAsync(file, r.Body, overwrite: true, cancellationToken).ConfigureAwait(false);
                File.SetLastWriteTimeUtc(file, _clock.GetUtcNow().UtcDateTime);
                if (r.ETag is not null)
                {
                    await AtomicFile.WriteAsync(etagFile, Encoding.UTF8.GetBytes(r.ETag), overwrite: true, cancellationToken).ConfigureAwait(false);
                }

                return true;
            case HttpStatusCode.NotFound when !required:
                return false; // this console has no such list
            default:
                if (required || File.Exists(file))
                {
                    throw new HttpRequestException($"{url} answered {(int)r.Status}{(r.Status == HttpStatusCode.OK ? " with something that isn't a game list" : string.Empty)}");
                }

                return false;
        }
    }

    // Lower-case words with accents and punctuation removed ("Pokémon - Heart" -> ["pokemon", "heart"]).
    private static string[] Words(string text)
    {
        var sb = new StringBuilder(text.Length);
        foreach (var c in text.Normalize(NormalizationForm.FormD))
        {
            if (CharUnicodeInfo.GetUnicodeCategory(c) == UnicodeCategory.NonSpacingMark)
            {
                continue;
            }

            sb.Append(char.IsLetterOrDigit(c) ? char.ToLowerInvariant(c) : ' ');
        }

        return sb.ToString().Split(' ', StringSplitOptions.RemoveEmptyEntries);
    }

    private sealed record LoadedIndex(long Stamp, Dictionary<string, DatGame> Games)
    {
        private string[]? _names;

        // Each game name once (several ROM versions can share a name).
        public string[] Names => _names ??= Games.Values.Select(g => g.Name).Distinct(StringComparer.Ordinal).ToArray();
    }

    private sealed class ConsoleData
    {
        public ConsoleData(GameConsole console, string system)
        {
            Console = console;
            System = system;
        }

#pragma warning disable SA1401, CA1051 // small private state holder; Version is used with Interlocked
        public long Version;
#pragma warning restore SA1401, CA1051

        public GameConsole Console { get; }

        public string System { get; }

        public SemaphoreSlim DownloadLock { get; } = new(1, 1);

        public SemaphoreSlim LoadLock { get; } = new(1, 1);

        public DateTimeOffset NextTry { get; set; } = DateTimeOffset.MinValue;

        public LoadedIndex? Index { get; set; }

        public Task? Refresh { get; set; }
    }
}
