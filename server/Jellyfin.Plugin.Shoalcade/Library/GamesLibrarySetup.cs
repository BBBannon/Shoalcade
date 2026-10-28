using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.Json.Serialization;
using System.Threading.Tasks;
using Jellyfin.Data.Enums;
using MediaBrowser.Controller.Entities;
using MediaBrowser.Controller.Library;
using MediaBrowser.Model.Configuration;
using MediaBrowser.Model.Entities;

namespace Jellyfin.Plugin.Shoalcade.Library;

/// <summary>One Jellyfin library, as seen by the games-library check.</summary>
/// <param name="Id">Library id.</param>
/// <param name="Name">Name.</param>
/// <param name="Type">Content type (books, mixed, movies, ...).</param>
/// <param name="Paths">Folders.</param>
/// <param name="RomCount">Games found in it.</param>
/// <param name="Suitable">Whether games can live in it (Books or Mixed).</param>
public sealed record LibraryStatus(
    [property: JsonPropertyName("id")] string Id,
    [property: JsonPropertyName("name")] string Name,
    [property: JsonPropertyName("type")] string Type,
    [property: JsonPropertyName("paths")] IReadOnlyList<string> Paths,
    [property: JsonPropertyName("romCount")] int RomCount,
    [property: JsonPropertyName("suitable")] bool Suitable);

/// <summary>
/// Makes the games library simple: lists libraries with how many games each holds, and creates a correctly set up
/// games library in one step. A games library is a normal Jellyfin <b>Books</b> library (Jellyfin has no game type;
/// games are Book items, exactly as JellyEMU makes them, D42). Created with settings that never write files next to ROMs.
/// </summary>
public sealed class GamesLibrarySetup
{
    private readonly ILibraryManager _library;
    private readonly Scanner.ScannerGuard _guard;

    /// <summary>Initializes a new instance of the <see cref="GamesLibrarySetup"/> class.</summary>
    /// <param name="library">Library manager.</param>
    /// <param name="guard">Says whether JellyEMU or our scanner finds the games.</param>
    public GamesLibrarySetup(ILibraryManager library, Scanner.ScannerGuard guard)
    {
        _library = library;
        _guard = guard;
    }

    /// <summary>Gets which plugin finds ROMs in libraries right now: "JellyEMU" while it is installed, else our own scanner.</summary>
    public string RomScanner => _guard.Scanner;

    /// <summary>True if games can live in a library of this type (JellyEMU skips music, films, TV, photos...).</summary>
    /// <param name="type">Content type.</param>
    /// <returns>Whether it is suitable.</returns>
    public static bool IsSuitable(CollectionTypeOptions? type) => type is null or CollectionTypeOptions.books or CollectionTypeOptions.mixed;

    /// <summary>The settings a new games library gets.</summary>
    /// <param name="path">ROM folder.</param>
    /// <returns>Library options.</returns>
    public static LibraryOptions NewLibraryOptions(string path) => new()
    {
        Enabled = true,
        PathInfos = [new MediaPathInfo(path)],
        EnableRealtimeMonitor = true,            // new ROMs show up without a manual scan
        SaveLocalMetadata = false,               // never write .nfo or pictures into the ROM folders
        EnablePhotos = false,
        EnableChapterImageExtraction = false,
        ExtractChapterImagesDuringLibraryScan = false,
        EnableTrickplayImageExtraction = false,
        ExtractTrickplayImagesDuringLibraryScan = false,
        EnableEmbeddedTitles = false,
    };

    /// <summary>Lists every library with its game count.</summary>
    /// <returns>Libraries.</returns>
    public IReadOnlyList<LibraryStatus> List()
    {
        var list = new List<LibraryStatus>();
        foreach (var f in _library.GetVirtualFolders())
        {
            var count = 0;
            if (Guid.TryParse(f.ItemId, out var id) && IsSuitable(f.CollectionType))
            {
                count = _library.GetItemList(new InternalItemsQuery
                {
                    AncestorIds = [id],
                    Recursive = true,
                    IncludeItemTypes = [BaseItemKind.Book],
                    Tags = RomLibrary.RomTags.ToArray(),
                }).Count(i => RomLibrary.ToRom(i) is not null);
            }

            list.Add(new LibraryStatus(
                f.ItemId,
                f.Name,
                f.CollectionType?.ToString() ?? "mixed",
                f.Locations ?? [],
                count,
                IsSuitable(f.CollectionType)));
        }

        return list;
    }

    /// <summary>Ids of the libraries that hold games now (suitable type, at least one ROM), e.g. to keep them off the home page.</summary>
    /// <returns>Library ids.</returns>
    public IReadOnlyCollection<Guid> GamesLibraryIds() =>
        List().Where(l => l.Suitable && l.RomCount > 0 && Guid.TryParse(l.Id, out _)).Select(l => Guid.Parse(l.Id)).ToList();

    /// <summary>Gets a library's id by its name (null if none).</summary>
    /// <param name="name">Library name.</param>
    /// <returns>The id.</returns>
    public Guid? FindId(string name) =>
        _library.GetVirtualFolders().Where(f => string.Equals(f.Name, name.Trim(), StringComparison.OrdinalIgnoreCase))
            .Select(f => Guid.TryParse(f.ItemId, out var id) ? id : (Guid?)null).FirstOrDefault(i => i is not null);

    /// <summary>Checks a request to create a games library. Null if fine, else the reason.</summary>
    /// <param name="name">Library name.</param>
    /// <param name="path">ROM folder on the server.</param>
    /// <returns>Null or an error.</returns>
    public string? Validate(string? name, string? path)
    {
        if (string.IsNullOrWhiteSpace(name) || name.Trim().Length > 60)
        {
            return "Give the library a name (up to 60 characters).";
        }

        if (string.IsNullOrWhiteSpace(path) || !Path.IsPathFullyQualified(path.Trim()))
        {
            return "Enter the full path of the ROM folder as the server sees it, e.g. /media/games or D:\\Games.";
        }

        var full = Path.GetFullPath(path.Trim());
        if (!Directory.Exists(full))
        {
            return "The server can't find that folder. Check the path (and, in Docker, that the folder is mounted).";
        }

        if (_library.GetVirtualFolders().Any(f => string.Equals(f.Name, name.Trim(), StringComparison.OrdinalIgnoreCase)))
        {
            return "There's already a library called \"" + name.Trim() + "\".";
        }

        foreach (var f in _library.GetVirtualFolders())
        {
            foreach (var loc in f.Locations ?? [])
            {
                var l = Path.GetFullPath(loc).TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar) + Path.DirectorySeparatorChar;
                var p = full.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar) + Path.DirectorySeparatorChar;
                if (p.StartsWith(l, StringComparison.OrdinalIgnoreCase) || l.StartsWith(p, StringComparison.OrdinalIgnoreCase))
                {
                    return "That folder is already in the \"" + f.Name + "\" library, so the games would show up twice. Use that library instead.";
                }
            }
        }

        return null;
    }

    /// <summary>Creates the games library (Books type, safe settings) and starts a scan.</summary>
    /// <param name="name">Library name.</param>
    /// <param name="path">ROM folder.</param>
    /// <returns>A task.</returns>
    public Task CreateAsync(string name, string path) =>
        _library.AddVirtualFolder(name.Trim(), CollectionTypeOptions.books, NewLibraryOptions(Path.GetFullPath(path.Trim())), true);

    /// <summary>Starts a scan of all libraries (picks up new ROMs).</summary>
    public void Scan() => _library.QueueLibraryScan();
}
