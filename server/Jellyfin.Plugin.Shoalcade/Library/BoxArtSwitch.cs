using System;
using System.Collections.Generic;
using System.Diagnostics.CodeAnalysis;
using System.IO;
using System.Linq;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Data.Enums;
using Jellyfin.Plugin.Shoalcade.Common;
using Jellyfin.Plugin.Shoalcade.Scanner;
using MediaBrowser.Controller.Configuration;
using MediaBrowser.Controller.Entities;
using MediaBrowser.Controller.Library;
using MediaBrowser.Controller.Providers;
using MediaBrowser.Model.Configuration;
using MediaBrowser.Model.Entities;
using MediaBrowser.Model.IO;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Shoalcade.Library;

/// <summary>Reads and writes a library's settings (the same ones Dashboard → Libraries → Manage library changes).</summary>
public interface ILibraryImageSettings
{
    /// <summary>Gets a copy of a library's settings, or null if the library is gone.</summary>
    /// <param name="library">Library id.</param>
    /// <returns>The settings (changing the copy changes nothing until <see cref="Write"/>).</returns>
    LibraryOptions? Read(Guid library);

    /// <summary>Saves a library's settings.</summary>
    /// <param name="library">Library id.</param>
    /// <param name="options">The settings.</param>
    void Write(Guid library, LibraryOptions options);

    /// <summary>True if the server-wide Book settings (used when a library has no Book settings of its own) allow our box art.</summary>
    /// <returns>Whether they do.</returns>
    bool ServerWideOn();

    /// <summary>Asks Jellyfin to look again for pictures for the games in this library that have none (games found before
    /// box art was switched on would otherwise wait for a manual "Refresh metadata").</summary>
    /// <param name="library">Library id.</param>
    /// <returns>How many games were queued.</returns>
    int FillMissingArt(Guid library);
}

/// <summary>One games library the plugin has dealt with.</summary>
public sealed class BoxArtEntry
{
    /// <summary>Gets or sets the library id.</summary>
    [JsonPropertyName("library")]
    public Guid Library { get; set; }

    /// <summary>Gets or sets a value indicating whether the plugin (not the admin) ticked our box art source.</summary>
    [JsonPropertyName("added")]
    public bool Added { get; set; }
}

/// <summary>
/// Switches our box art source ("Shoalcade box art (libretro)") on for every games library, <b>once</b> per library, and
/// records it in <c>box-art.json</c>. Needed because a library made by hand in Jellyfin's dashboard lists its Book image
/// sources, and Jellyfin leaves a new source unticked there, so no box art would ever arrive. Ours is ticked and put first
/// in the order; every other source the admin chose stays exactly as it was (a local <c>&lt;rom&gt;.png</c> is read
/// separately by Jellyfin and still wins). After that the admin is in charge: unticking it later is respected.
/// Does nothing while JellyEMU is loaded (it finds the games and their art then).
/// </summary>
public sealed class BoxArtSwitch : IDisposable
{
    private const string BookType = nameof(Book);
    private static readonly JsonSerializerOptions JsonOptions = new() { WriteIndented = true };

    private readonly string _recordPath;
    private readonly ILibraryImageSettings _settings;
    private readonly Func<IReadOnlyCollection<Guid>> _gamesLibraries;
    private readonly Func<bool> _standDown;
    private readonly ILogger _logger;
    [SuppressMessage("Usage", "CA2213:Disposable fields should be disposed", Justification = "Not disposed on purpose: Jellyfin's in-process restart disposes this service while a task may still wait on or hold the lock, and a SemaphoreSlim only needs disposing once its AvailableWaitHandle is used (never here)")]
    private readonly SemaphoreSlim _lock = new(1, 1);

    /// <summary>Initializes a new instance of the <see cref="BoxArtSwitch"/> class.</summary>
    /// <param name="recordPath">Where the record of libraries dealt with lives.</param>
    /// <param name="settings">Library settings.</param>
    /// <param name="gamesLibraries">Ids of the libraries holding games right now.</param>
    /// <param name="standDown">True while JellyEMU is loaded.</param>
    /// <param name="logger">Logger.</param>
    public BoxArtSwitch(string recordPath, ILibraryImageSettings settings, Func<IReadOnlyCollection<Guid>> gamesLibraries, Func<bool> standDown, ILogger logger)
    {
        _recordPath = recordPath;
        _settings = settings;
        _gamesLibraries = gamesLibraries;
        _standDown = standDown;
        _logger = logger;
    }

    /// <summary>True if our box art source is ticked for Book items in these settings.</summary>
    /// <param name="options">Library settings.</param>
    /// <param name="serverWideOn">What applies when the library has no Book settings of its own.</param>
    /// <returns>Whether box art can arrive.</returns>
    public static bool IsOn(LibraryOptions options, bool serverWideOn)
    {
        ArgumentNullException.ThrowIfNull(options);
        var t = options.GetTypeOptions(BookType);
        return t is null ? serverWideOn : t.ImageFetchers.Contains(BoxArtProvider.ProviderName, StringComparer.OrdinalIgnoreCase);
    }

    /// <summary>
    /// Ticks our box art source for Book items and puts it first in the order. Only ever adds ours: nothing else is
    /// removed, the other sources keep their order, and the admin's other choices (such as which local info readers are
    /// off) are left exactly as they are (D110).
    /// No Book settings of its own = the library follows the server-wide settings, which list ours already: left alone.
    /// </summary>
    /// <param name="options">Library settings (changed in place).</param>
    /// <returns>Whether box art was ticked (false if it already was, or the library has no Book list).</returns>
    public static bool SwitchOn(LibraryOptions options)
    {
        ArgumentNullException.ThrowIfNull(options);
        var t = options.GetTypeOptions(BookType);
        if (t is null || t.ImageFetchers.Contains(BoxArtProvider.ProviderName, StringComparer.OrdinalIgnoreCase))
        {
            return false;
        }

        t.ImageFetchers = [BoxArtProvider.ProviderName, .. t.ImageFetchers];
        t.ImageFetcherOrder = [BoxArtProvider.ProviderName, .. (t.ImageFetcherOrder ?? []).Where(n => !string.Equals(n, BoxArtProvider.ProviderName, StringComparison.OrdinalIgnoreCase))];
        return true;
    }

    /// <summary>Deals with every games library (plus <paramref name="extraLibraries"/>, e.g. one just created) not yet dealt with.</summary>
    /// <param name="extraLibraries">More library ids to treat as games libraries.</param>
    /// <returns>How many libraries had box art switched on.</returns>
    public Task<int> SyncAsync(IEnumerable<Guid>? extraLibraries = null)
    {
        if (_standDown())
        {
            return Task.FromResult(0);
        }

        var libs = _gamesLibraries().Concat(extraLibraries ?? []).Where(i => i != Guid.Empty).Distinct().ToList();
        return ApplyAsync(libs);
    }

    /// <summary>Like <see cref="SyncAsync"/>, but logs instead of throwing (for start-up, scans and library creation).</summary>
    /// <param name="extraLibraries">More library ids to treat as games libraries.</param>
    /// <returns>A task.</returns>
    public async Task SyncQuietlyAsync(IEnumerable<Guid>? extraLibraries = null)
    {
        try
        {
            await SyncAsync(extraLibraries).ConfigureAwait(false);
        }
#pragma warning disable CA1031 // switching on box art must never break start-up, scans or library creation
        catch (Exception ex)
#pragma warning restore CA1031
        {
            _logger.LogWarning(ex, "Could not switch on Shoalcade box art for the games libraries");
        }
    }

    /// <summary>Switches box art on for these libraries, once each.</summary>
    /// <param name="libraries">Library ids.</param>
    /// <returns>How many libraries had box art switched on.</returns>
    public async Task<int> ApplyAsync(IReadOnlyCollection<Guid> libraries)
    {
        ArgumentNullException.ThrowIfNull(libraries);
        if (libraries.Count == 0 || _standDown())
        {
            return 0;
        }

        await _lock.WaitAsync().ConfigureAwait(false);
        try
        {
            var record = Load();
            var before = record.Count;
            var switched = 0;
            try
            {
                foreach (var lib in libraries.Where(l => !record.Any(e => e.Library == l)))
                {
                    var options = _settings.Read(lib);
                    if (options is null)
                    {
                        continue;
                    }

                    // Library first, record after: if the server stops in between, the next run finds ours ticked and just
                    // records it; it can never record a library as dealt with that didn't get box art.
                    var added = SwitchOn(options);
                    if (added)
                    {
                        _settings.Write(lib, options);
                    }

                    record.Add(new BoxArtEntry { Library = lib, Added = added });
                    if (added)
                    {
                        switched++;
                        var queued = _settings.FillMissingArt(lib);
                        _logger.LogInformation("Switched on Shoalcade box art for games library {Library}; looking for pictures for {Count} game(s)", lib, queued);
                    }
                }
            }
            finally
            {
                if (record.Count != before)
                {
                    await SaveAsync(record).ConfigureAwait(false);
                }
            }

            return switched;
        }
        finally
        {
            _lock.Release();
        }
    }

    /// <summary>Whether box art can arrive for this library now (null while JellyEMU is loaded, or the library is gone).</summary>
    /// <param name="library">Library id.</param>
    /// <returns>On, off or null.</returns>
    public bool? IsOn(Guid library)
    {
        if (_standDown())
        {
            return null;
        }

        var options = _settings.Read(library);
        return options is null ? null : IsOn(options, _settings.ServerWideOn());
    }

    /// <summary>Gets what the plugin has recorded (for tests and support).</summary>
    /// <returns>The entries.</returns>
    public IReadOnlyList<BoxArtEntry> Record() => Load();

    /// <inheritdoc />
    /// <remarks>The lock is not disposed (see its field): a Jellyfin restart can dispose this service while a
    /// start-up task or scan still uses it.</remarks>
    public void Dispose()
    {
    }

    private List<BoxArtEntry> Load()
    {
        if (!File.Exists(_recordPath))
        {
            return [];
        }

        // A damaged record throws rather than reading as empty: empty would re-tick box art the admin had unticked.
        return JsonSerializer.Deserialize<List<BoxArtEntry>>(File.ReadAllBytes(_recordPath), JsonOptions) ?? [];
    }

    private Task SaveAsync(List<BoxArtEntry> record) =>
        AtomicFile.WriteAsync(_recordPath, JsonSerializer.SerializeToUtf8Bytes(record, JsonOptions), true, CancellationToken.None);
}

/// <summary>Jellyfin's real library settings.</summary>
public sealed class JellyfinLibraryImageSettings : ILibraryImageSettings
{
    private readonly ILibraryManager _library;
    private readonly IServerConfigurationManager _config;
    private readonly IProviderManager _providers;
    private readonly IFileSystem _fileSystem;

    /// <summary>Initializes a new instance of the <see cref="JellyfinLibraryImageSettings"/> class.</summary>
    /// <param name="library">Library manager.</param>
    /// <param name="config">Server settings.</param>
    /// <param name="providers">Provider manager (queues picture look-ups).</param>
    /// <param name="fileSystem">File system.</param>
    public JellyfinLibraryImageSettings(ILibraryManager library, IServerConfigurationManager config, IProviderManager providers, IFileSystem fileSystem)
    {
        _library = library;
        _config = config;
        _providers = providers;
        _fileSystem = fileSystem;
    }

    /// <inheritdoc />
    public LibraryOptions? Read(Guid library)
    {
        if (_library.GetItemById(library) is not CollectionFolder folder)
        {
            return null;
        }

        // A copy: Jellyfin hands out its cached settings object, which must only change through UpdateLibraryOptions.
        var live = folder.GetLibraryOptions();
        return JsonSerializer.Deserialize<LibraryOptions>(JsonSerializer.SerializeToUtf8Bytes(live));
    }

    /// <inheritdoc />
    public void Write(Guid library, LibraryOptions options)
    {
        if (_library.GetItemById(library) is CollectionFolder folder)
        {
            folder.UpdateLibraryOptions(options);
        }
    }

    /// <inheritdoc />
    public bool ServerWideOn()
    {
        var o = _config.GetMetadataOptionsForType(nameof(Book));
        return o is null || !o.DisabledImageFetchers.Contains(BoxArtProvider.ProviderName, StringComparer.OrdinalIgnoreCase);
    }

    /// <inheritdoc />
    public int FillMissingArt(Guid library)
    {
        var games = _library.GetItemList(new InternalItemsQuery
        {
            AncestorIds = [library],
            Recursive = true,
            IncludeItemTypes = [BaseItemKind.Book],
            Tags = RomLibrary.RomTags.ToArray(),
        }).Where(i => RomLibrary.ToRom(i) is not null && !i.HasImage(ImageType.Primary)).ToList();

        foreach (var item in games)
        {
            // Pictures only (missing ones; nothing replaced), at low priority behind normal scans.
            _providers.QueueRefresh(
                item.Id,
                new MetadataRefreshOptions(new DirectoryService(_fileSystem))
                {
                    MetadataRefreshMode = MetadataRefreshMode.None,
                    ImageRefreshMode = MetadataRefreshMode.FullRefresh,
                    ReplaceAllImages = false,
                    ReplaceAllMetadata = false,
                },
                RefreshPriority.Low);
        }

        return games.Count;
    }
}
