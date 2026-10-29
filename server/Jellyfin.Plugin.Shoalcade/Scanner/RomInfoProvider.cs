using System;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Xml;
using System.Xml.Linq;
using MediaBrowser.Controller.Entities;
using MediaBrowser.Controller.Library;
using MediaBrowser.Controller.Providers;
using MediaBrowser.Model.Entities;
using MediaBrowser.Model.Providers;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Shoalcade.Scanner;

/// <summary>
/// Local game info for scanned ROMs: the ROM's MD5 (provider id <c>MD5</c>, cached, see <see cref="Md5Cache"/>) and, if
/// there is one, a <c>&lt;rom&gt;.nfo</c> next to it (title, plot, year, genres, developer/publisher, rating).
/// Then libretro-database by MD5 (<see cref="GameDatabase"/>, D48) fills what the .nfo didn't give: the clean
/// official title (region and other bracketed extras dropped; the region stays a tag), year, developer, publisher
/// and genre. With neither, the game keeps its cleaned file name.
/// A <c>&lt;rom&gt;.png/.jpg</c> next to the ROM is picked up by Jellyfin's own local image reader (same file name).
/// Only reads; never writes anything next to the ROMs. Does nothing while JellyEMU is installed (its own reader runs).
/// </summary>
public sealed class RomInfoProvider : ILocalMetadataProvider<Book>
{
    /// <summary>Largest .nfo we read (a real one is a few KB).</summary>
    internal const long MaxNfoBytes = 1024 * 1024;

    private readonly ScannerGuard _guard;
    private readonly Md5Cache _md5;
    private readonly GameDatabase _db;
    private readonly ILogger<RomInfoProvider> _logger;

    /// <summary>Initializes a new instance of the <see cref="RomInfoProvider"/> class.</summary>
    /// <param name="guard">Stands down while JellyEMU is installed.</param>
    /// <param name="md5">MD5 cache.</param>
    /// <param name="db">Game database (names, years, developers by MD5).</param>
    /// <param name="logger">Logger.</param>
    public RomInfoProvider(ScannerGuard guard, Md5Cache md5, GameDatabase db, ILogger<RomInfoProvider> logger)
    {
        _guard = guard;
        _md5 = md5;
        _db = db;
        _logger = logger;
    }

    /// <summary>The name Jellyfin shows for this reader in a library's settings.</summary>
    public const string ProviderName = "Shoalcade ROM info";

    /// <inheritdoc />
    public string Name => ProviderName;

    /// <inheritdoc />
    public async Task<MetadataResult<Book>> GetMetadata(ItemInfo info, IDirectoryService directoryService, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(info);
        var result = new MetadataResult<Book>();
        if (_guard.StandDown || string.IsNullOrEmpty(info.Path))
        {
            return result;
        }

        var rom = RomScanner.ForItemPath(info.Path);
        if (rom is null)
        {
            return result; // not one of our games (a real book, a film...)
        }

        // Our tags come back with every metadata refresh: Jellyfin's "Replace all metadata" copies the provider's tags
        // over the item's, and without them the game would drop out of Shoalcade (found in the swap rehearsal).
        // They also turn a game JellyEMU found (tag JellyEmu) into one of ours.
        var item = new Book { Tags = rom.Tags.ToArray() };
        var nfo = NfoPath(info.Path);
        if (File.Exists(nfo))
        {
            ReadNfo(nfo, item);
        }

        // RPG Maker (D147): the name is Game.ini's title (unless an .nfo names it); there is no game database to look a
        // folder up in and no MD5 to show, so no box art either (people upload their own cover).
        if (rom.Console.FolderGame)
        {
            if (string.IsNullOrWhiteSpace(item.Name))
            {
                item.Name = rom.Name;
            }

            result.Item = item;
            result.HasMetadata = true;
            return result;
        }

        var md5 = await _md5.GetAsync(rom.HashPath, cancellationToken).ConfigureAwait(false);
        if (md5 is not null)
        {
            item.SetProviderId(Md5ExternalId.ProviderKey, md5);
            if (await _db.LookupAsync(rom.Console, md5, cancellationToken).ConfigureAwait(false) is { } game)
            {
                ApplyDatabase(game, item);
            }
        }

        result.Item = item;
        result.HasMetadata = true;
        return result;
    }

    /// <summary>Fills in what the .nfo (if any) left empty from the game database. The .nfo always wins.</summary>
    /// <param name="game">Database entry.</param>
    /// <param name="item">Item being filled.</param>
    internal static void ApplyDatabase(DatGame game, Book item)
    {
        if (string.IsNullOrWhiteSpace(item.Name) && LibretroNames.DisplayTitle(game.Name) is string title)
        {
            item.Name = title;
        }

        if (item.ProductionYear is null && game.Year is int year)
        {
            item.ProductionYear = year;
        }

        if (item.Studios.Length == 0)
        {
            foreach (var studio in new[] { game.Developer, game.Publisher }.Where(s => !string.IsNullOrWhiteSpace(s)))
            {
                item.AddStudio(studio!);
            }
        }

        if (item.Genres.Length == 0 && !string.IsNullOrWhiteSpace(game.Genre))
        {
            item.AddGenre(game.Genre);
        }
    }

    /// <summary>Where a game's .nfo is: next to the ROM with the same name, or inside a disc-set folder as "folder name.nfo".</summary>
    /// <param name="path">Item path.</param>
    /// <returns>.nfo path.</returns>
    internal static string NfoPath(string path) =>
        Directory.Exists(path)
            ? Path.Combine(path, Path.GetFileName(path.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar)) + ".nfo")
            : Path.ChangeExtension(path, ".nfo");

    /// <summary>Reads a Kodi-style game .nfo into <paramref name="item"/>. A broken file is logged and skipped.</summary>
    /// <param name="nfo">.nfo path.</param>
    /// <param name="item">Item to fill.</param>
    internal void ReadNfo(string nfo, Book item)
    {
        try
        {
            if (new FileInfo(nfo).Length > MaxNfoBytes)
            {
                _logger.LogInformation("Skipped {Nfo}: too big to be a game .nfo", nfo);
                return;
            }

            var settings = new XmlReaderSettings { DtdProcessing = DtdProcessing.Prohibit, XmlResolver = null, IgnoreComments = true };
            using var reader = XmlReader.Create(nfo, settings);
            var root = XDocument.Load(reader).Root;
            if (root is null)
            {
                return;
            }

            string? Text(params string[] names) =>
                names.Select(n => root.Element(n)?.Value.Trim()).FirstOrDefault(v => !string.IsNullOrEmpty(v));

            if (Text("title") is string title)
            {
                item.Name = title;
            }

            item.Overview = Text("plot", "overview", "description") ?? item.Overview;
            if (DateTime.TryParse(Text("premiered", "releasedate"), CultureInfo.InvariantCulture, DateTimeStyles.AdjustToUniversal | DateTimeStyles.AssumeUniversal, out var date))
            {
                item.PremiereDate = date;
                item.ProductionYear = date.Year;
            }
            else if (int.TryParse(Text("year"), NumberStyles.None, CultureInfo.InvariantCulture, out var year) && year is > 1950 and < 2200)
            {
                item.ProductionYear = year;
            }

            if (float.TryParse(Text("rating"), NumberStyles.Float, CultureInfo.InvariantCulture, out var rating))
            {
                item.CommunityRating = rating;
            }

            foreach (var genre in root.Elements("genre").Select(g => g.Value.Trim()).Where(g => g.Length > 0))
            {
                item.AddGenre(genre);
            }

            foreach (var studio in root.Elements("developer").Concat(root.Elements("publisher")).Select(s => s.Value.Trim()).Where(s => s.Length > 0))
            {
                item.AddStudio(studio);
            }
        }
        catch (Exception ex) when (ex is XmlException or IOException or UnauthorizedAccessException)
        {
            _logger.LogWarning("Could not read {Nfo} (game info is taken from the file name instead): {Message}", nfo, ex.Message);
        }
    }
}

/// <summary>Shows a game's MD5 under External IDs in Jellyfin (ROM items only; while JellyEMU is installed it shows its own).</summary>
public sealed class Md5ExternalId : IExternalId
{
    /// <summary>The provider id key (the same key JellyEMU used, so existing ids carry over).</summary>
    public const string ProviderKey = "MD5";

    private readonly ScannerGuard _guard;

    /// <summary>Initializes a new instance of the <see cref="Md5ExternalId"/> class.</summary>
    /// <param name="guard">Stands down while JellyEMU is installed.</param>
    public Md5ExternalId(ScannerGuard guard) => _guard = guard;

    /// <inheritdoc />
    public string ProviderName => "MD5";

    /// <inheritdoc />
    public string Key => ProviderKey;

    /// <inheritdoc />
    public ExternalIdMediaType? Type => null;

    /// <inheritdoc />
    public bool Supports(IHasProviderIds item) =>
        !_guard.StandDown && item is Book b && Library.RomLibrary.ToRom(b) is { Console.FolderGame: false }; // no MD5 for RPG Maker folders
}

/// <summary>Saves the MD5 cache after every library scan.</summary>
public sealed class Md5CacheFlushTask : ILibraryPostScanTask
{
    private readonly Md5Cache _md5;

    /// <summary>Initializes a new instance of the <see cref="Md5CacheFlushTask"/> class.</summary>
    /// <param name="md5">MD5 cache.</param>
    public Md5CacheFlushTask(Md5Cache md5) => _md5 = md5;

    /// <inheritdoc />
    public async Task Run(IProgress<double> progress, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(progress);
        await _md5.FlushAsync(cancellationToken).ConfigureAwait(false);
        progress.Report(100);
    }
}
