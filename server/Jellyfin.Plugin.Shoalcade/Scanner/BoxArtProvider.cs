using System;
using System.Collections.Generic;
using System.Diagnostics.CodeAnalysis;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Plugin.Shoalcade.Common;
using Jellyfin.Plugin.Shoalcade.Consoles;
using MediaBrowser.Controller.Entities;
using MediaBrowser.Controller.Providers;
using MediaBrowser.Model.Entities;
using MediaBrowser.Model.Providers;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Shoalcade.Scanner;

/// <summary>
/// Box art for our games from libretro-thumbnails (D48), offered to Jellyfin as a remote image: Jellyfin downloads
/// the picture once and stores it with the item (nothing is hotlinked). Order of sources: a <c>&lt;rom&gt;.png/.jpg</c>
/// next to the ROM wins (Jellyfin's own local reader; Jellyfin only asks remote providers when the item has no
/// picture), then this. Names tried, in order: the file name, the libretro-database name from the ROM's MD5, the
/// name cut at the first "(".
/// <para>Only for our game items: Books tagged <c>Shoalcade</c>, or <c>JellyEmu</c> for a game JellyEMU found before the
/// swap (D112); does nothing while JellyEMU is installed. Every
/// request goes through <see cref="LibretroHttp"/> (one at a time, timeouts, clear User-Agent). Pictures that don't
/// exist are remembered for 30 days, and after a network failure no box art is asked for for 10 minutes.</para>
/// </summary>
public sealed class BoxArtProvider : IRemoteImageProvider, IHasOrder
{
    /// <summary>The provider name shown in Jellyfin's library settings (Image fetchers).</summary>
    public const string ProviderName = "Shoalcade box art (libretro)";

    /// <summary>Largest picture accepted.</summary>
    internal const long MaxImageBytes = 10L * 1024 * 1024;

    private static readonly TimeSpan Timeout = TimeSpan.FromSeconds(30);
    private static readonly TimeSpan OfflinePause = TimeSpan.FromMinutes(10);

    private readonly ScannerGuard _guard;
    private readonly GameDatabase _db;
    private readonly LibretroHttp _http;
    private readonly ThumbnailMisses _misses;
    private readonly TimeProvider _clock;
    private readonly ILogger _logger;
    private readonly Library.GameEdits? _edits;
    private DateTimeOffset _offlineUntil = DateTimeOffset.MinValue;

    /// <summary>Initializes a new instance of the <see cref="BoxArtProvider"/> class.</summary>
    /// <param name="guard">Stands down while JellyEMU is installed.</param>
    /// <param name="db">Game database.</param>
    /// <param name="http">The polite libretro client.</param>
    /// <param name="misses">Remembered missing pictures.</param>
    /// <param name="edits">Admin game edits: a game with an admin's cover is never offered box art (D122).</param>
    /// <param name="logger">Logger.</param>
    public BoxArtProvider(ScannerGuard guard, GameDatabase db, LibretroHttp http, ThumbnailMisses misses, Library.GameEdits edits, ILogger<BoxArtProvider> logger)
        : this(guard, db, http, misses, TimeProvider.System, logger, edits)
    {
    }

    /// <summary>Initializes a new instance of the <see cref="BoxArtProvider"/> class with a given clock (tests).</summary>
    /// <param name="guard">Stands down while JellyEMU is installed.</param>
    /// <param name="db">Game database.</param>
    /// <param name="http">The polite libretro client.</param>
    /// <param name="misses">Remembered missing pictures.</param>
    /// <param name="clock">Clock.</param>
    /// <param name="logger">Logger.</param>
    /// <param name="edits">Admin game edits (null = none, tests).</param>
    internal BoxArtProvider(ScannerGuard guard, GameDatabase db, LibretroHttp http, ThumbnailMisses misses, TimeProvider clock, ILogger logger, Library.GameEdits? edits = null)
    {
        _edits = edits;
        _guard = guard;
        _db = db;
        _http = http;
        _misses = misses;
        _clock = clock;
        _logger = logger;
    }

    /// <inheritdoc />
    public string Name => ProviderName;

    /// <summary>Gets Jellyfin's default order (after the other image sources). In a games library the box art switch puts
/// ours first in the library's own order (D110), which is what Jellyfin uses there.</summary>
    public int Order => 100;

    /// <summary>Our games only. Also a blank Book (no id yet): that is how Jellyfin's dashboard asks which image sources to
    /// list in a library's settings, so ours shows there as a box the admin can see and untick (without it, saving the
    /// library's settings would silently drop ours). <see cref="GetImages"/> still returns nothing for anything but our games.</summary>
    /// <param name="item">Item.</param>
    /// <returns>Whether this source applies.</returns>
    public bool Supports(BaseItem item) =>
        !_guard.StandDown
        && ((item is Book && item.Id.Equals(Guid.Empty)) || (ConsoleOf(item) is { } c && LibretroNames.SystemName(c) is not null));

    /// <inheritdoc />
    public IEnumerable<ImageType> GetSupportedImages(BaseItem item) => [ImageType.Primary];

    /// <inheritdoc />
    public async Task<IEnumerable<RemoteImageInfo>> GetImages(BaseItem item, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(item);
        if (_guard.StandDown || _clock.GetUtcNow() < _offlineUntil || ConsoleOf(item) is not { } console
            || LibretroNames.SystemName(console) is not string system || _edits?.HasCover(item.Id) == true)
        {
            return []; // an admin's cover (D122) is never replaced by box art
        }

        var md5 = item.GetProviderId(Md5ExternalId.ProviderKey);
        var game = string.IsNullOrEmpty(md5) ? null : await _db.LookupAsync(console, md5, cancellationToken).ConfigureAwait(false);
        return LibretroNames.BoxArtCandidates(item.Path, game?.Name)
            .Select(n => LibretroNames.BoxArtUrl(_http.ThumbnailsBase, system, n).AbsoluteUri)
            .Where(u => !_misses.IsMissing(u))
            .Select(u => new RemoteImageInfo { ProviderName = ProviderName, Url = u, Type = ImageType.Primary })
            .ToList();
    }

    /// <inheritdoc />
    public async Task<HttpResponseMessage> GetImageResponse(string url, CancellationToken cancellationToken)
    {
        // Only ever fetch from the box art server: this is never a general "download any address" helper.
        if (!Uri.TryCreate(url, UriKind.Absolute, out var uri) || !_http.ThumbnailsBase.IsBaseOf(uri)
            || _misses.IsMissing(uri.AbsoluteUri) || _clock.GetUtcNow() < _offlineUntil)
        {
            return new HttpResponseMessage(HttpStatusCode.NotFound);
        }

        LibretroResponse r;
        try
        {
            r = await _http.GetAsync(uri, null, MaxImageBytes, Timeout, cancellationToken).ConfigureAwait(false);
        }
        catch (HttpRequestException ex)
        {
            _offlineUntil = _clock.GetUtcNow() + OfflinePause;
            _logger.LogInformation("Box art server could not be reached ({Message}); trying again in {Minutes} minutes", ex.Message, OfflinePause.TotalMinutes);
            throw; // Jellyfin stops trying this game's other names
        }

        var isImage = r.ContentType?.StartsWith("image/", StringComparison.OrdinalIgnoreCase) == true;
        if (r.Status == HttpStatusCode.OK && r.Body is { Length: > 0 } body && isImage && PictureType(body) is string type)
        {
            var content = new ByteArrayContent(body);
            content.Headers.ContentType = new MediaTypeHeaderValue(type);
            return new HttpResponseMessage(HttpStatusCode.OK) { Content = content, RequestMessage = new HttpRequestMessage(HttpMethod.Get, uri) };
        }

        if (r.Status is HttpStatusCode.NotFound or HttpStatusCode.Gone or HttpStatusCode.OK)
        {
            await _misses.AddAsync(uri.AbsoluteUri, cancellationToken).ConfigureAwait(false); // no picture by that name
            return new HttpResponseMessage(HttpStatusCode.NotFound);
        }

        return new HttpResponseMessage(r.Status); // other answers (server busy...): Jellyfin skips this game for now
    }

    /// <summary>
    /// The picture type from the file's first bytes: PNG, JPEG or WebP only, whatever the server said (G5 s0.2 L2: an SVG
    /// or HTML file saved as a game's picture could run script on the Jellyfin site).
    /// </summary>
    /// <param name="body">Downloaded bytes.</param>
    /// <returns>The media type, or null if it is not one of those.</returns>
    internal static string? PictureType(ReadOnlySpan<byte> body)
    {
        if (body.StartsWith((ReadOnlySpan<byte>)[0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]))
        {
            return "image/png";
        }

        if (body.StartsWith((ReadOnlySpan<byte>)[0xFF, 0xD8, 0xFF]))
        {
            return "image/jpeg";
        }

        return body.Length >= 12 && body[..4].SequenceEqual("RIFF"u8) && body.Slice(8, 4).SequenceEqual("WEBP"u8) ? "image/webp" : null;
    }

    /// <summary>
    /// The console of one of our game items (Book tagged <c>Shoalcade</c>, or <c>JellyEmu</c>: a game JellyEMU found
    /// keeps its item and tags after the swap, and we serve it), or null for anything else. While JellyEMU is loaded
    /// nothing here runs at all (<see cref="ScannerGuard"/>).
    /// </summary>
    private static GameConsole? ConsoleOf(BaseItem item) =>
        item is Book && !item.IsFolder && !string.IsNullOrEmpty(item.Path)
        && item.Tags is { } tags && tags.Any(t => Library.RomLibrary.RomTags.Contains(t, StringComparer.Ordinal))
            ? GameConsole.Detect(tags, item.Path)
            : null;
}

/// <summary>
/// Box art addresses that answered "not found", remembered for 30 days in <c>shoalcade/gamedb/thumbnail-misses.json</c>
/// so a game with no picture isn't asked for on every scan.
/// </summary>
public sealed class ThumbnailMisses : IDisposable
{
    /// <summary>How long a "not found" is remembered.</summary>
    internal static readonly TimeSpan Keep = TimeSpan.FromDays(30);

    /// <summary>Most addresses remembered; the oldest go first (G5 dev 2 I3: "Find a cover" adds one per typed name).</summary>
    internal const int MaxEntries = 20000;

    private readonly string _file;
    private readonly TimeProvider _clock;
    private readonly ILogger _logger;
    private readonly Lock _lock = new();
    [SuppressMessage("Usage", "CA2213:Disposable fields should be disposed", Justification = "Not disposed on purpose: Jellyfin's in-process restart disposes this service while a task may still wait on or hold the lock, and a SemaphoreSlim only needs disposing once its AvailableWaitHandle is used (never here)")]
    private readonly SemaphoreSlim _saveLock = new(1, 1);
    private readonly int _max;
    private Dictionary<string, DateTimeOffset>? _entries;

    /// <summary>Initializes a new instance of the <see cref="ThumbnailMisses"/> class.</summary>
    /// <param name="file">Where the list is kept.</param>
    /// <param name="clock">Clock.</param>
    /// <param name="logger">Logger.</param>
    public ThumbnailMisses(string file, TimeProvider clock, ILogger logger)
        : this(file, clock, logger, MaxEntries)
    {
    }

    /// <summary>Initializes a new instance of the <see cref="ThumbnailMisses"/> class with a different cap (tests).</summary>
    /// <param name="file">Where the list is kept.</param>
    /// <param name="clock">Clock.</param>
    /// <param name="logger">Logger.</param>
    /// <param name="maxEntries">Most addresses remembered.</param>
    internal ThumbnailMisses(string file, TimeProvider clock, ILogger logger, int maxEntries)
    {
        _file = file;
        _clock = clock;
        _logger = logger;
        _max = maxEntries;
    }

    /// <summary>True if this address answered "not found" in the last 30 days.</summary>
    /// <param name="url">Address.</param>
    /// <returns>Whether to skip it.</returns>
    public bool IsMissing(string url)
    {
        lock (_lock)
        {
            return Entries().TryGetValue(url, out var when) && when + Keep > _clock.GetUtcNow();
        }
    }

    /// <summary>Remembers a "not found" and saves the list.</summary>
    /// <param name="url">Address.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>A task.</returns>
    public async Task AddAsync(string url, CancellationToken cancellationToken)
    {
        byte[] json;
        lock (_lock)
        {
            var now = _clock.GetUtcNow();
            var entries = Entries();
            foreach (var old in entries.Where(e => e.Value + Keep <= now).Select(e => e.Key).ToList())
            {
                entries.Remove(old);
            }

            entries[url] = now;
            if (entries.Count > _max)
            {
                foreach (var oldest in entries.OrderBy(e => e.Value).Take(entries.Count - _max).Select(e => e.Key).ToList())
                {
                    entries.Remove(oldest);
                }
            }

            json = JsonSerializer.SerializeToUtf8Bytes(entries);
        }

        await _saveLock.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            await AtomicFile.WriteAsync(_file, json, overwrite: true, cancellationToken).ConfigureAwait(false);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            _logger.LogInformation("Could not save the list of missing box art ({Message})", ex.Message);
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

    // Caller holds _lock.
    private Dictionary<string, DateTimeOffset> Entries()
    {
        if (_entries is not null)
        {
            return _entries;
        }

        _entries = new Dictionary<string, DateTimeOffset>(StringComparer.Ordinal);
        try
        {
            if (File.Exists(_file) && JsonSerializer.Deserialize<Dictionary<string, DateTimeOffset>>(File.ReadAllBytes(_file)) is { } loaded)
            {
                _entries = new Dictionary<string, DateTimeOffset>(loaded, StringComparer.Ordinal);
            }
        }
        catch (Exception ex) when (ex is IOException or JsonException or UnauthorizedAccessException)
        {
            _logger.LogInformation("Starting a new list of missing box art ({Message})", ex.Message);
        }

        return _entries;
    }
}
