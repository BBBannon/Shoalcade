using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using Jellyfin.Data.Enums;
using Jellyfin.Database.Implementations.Entities;
using Jellyfin.Plugin.Shoalcade.Consoles;
using MediaBrowser.Controller.Drawing;
using MediaBrowser.Controller.Entities;
using MediaBrowser.Controller.Library;
using MediaBrowser.Model.Entities;

namespace Jellyfin.Plugin.Shoalcade.Library;

/// <summary>A library item that is a ROM.</summary>
/// <param name="Id">Item id.</param>
/// <param name="Name">Name.</param>
/// <param name="Console">Console.</param>
/// <param name="SortName">Sort name.</param>
/// <param name="ImageTag">Changes when the cover picture changes (cache buster), or null if none.</param>
/// <param name="Region">Region tag, if any.</param>
/// <param name="Series">Series/folder name, if any.</param>
/// <param name="Year">Year, if known.</param>
/// <param name="Item">The Jellyfin item.</param>
public sealed record RomItem(Guid Id, string Name, GameConsole Console, string SortName, string? ImageTag, string? Region, string? Series, int? Year, BaseItem Item);

/// <summary>
/// The only way the plugin looks at the library. Every lookup needs a real user, and only items that user can see
/// AND that are ROMs come back. Anything else (films, books, hidden libraries) is treated as not existing (404).
/// </summary>
public sealed class RomLibrary
{
    /// <summary>Tags that mark an item as a ROM: JellyEMU's scanner, and ours (D42). Both count during the swap-over.
    /// Our old tag from before the rename (D124) counts too, until the start-up carry-over task has renamed it.</summary>
    public static readonly IReadOnlyList<string> RomTags = ["JellyEmu", Scanner.ScannedRom.ScannerTag, Migration.LegacyNames.Tag];

    private static readonly string[] Regions = ["USA", "Europe", "Japan", "World", "Australia", "Korea", "Asia", "China", "Brazil", "Canada", "France", "Germany", "Italy", "Spain", "UK"];

    private readonly ILibraryManager _library;
    private readonly IUserManager _users;
    private readonly IImageProcessor _images;

    /// <summary>Initializes a new instance of the <see cref="RomLibrary"/> class.</summary>
    /// <param name="library">Library manager.</param>
    /// <param name="users">User manager.</param>
    /// <param name="images">Image processor (for real image tags).</param>
    public RomLibrary(ILibraryManager library, IUserManager users, IImageProcessor images)
    {
        _library = library;
        _users = users;
        _images = images;
    }

    /// <summary>Gets a user by id (null if none or empty id).</summary>
    /// <param name="id">User id.</param>
    /// <returns>The user.</returns>
    public User? GetUser(Guid id) => id == Guid.Empty ? null : _users.GetUserById(id);

    /// <summary>
    /// Gets a ROM the user is allowed to see. Null for: no such item, not visible to the user (library access,
    /// parental rating, tags), or not a ROM.
    /// </summary>
    /// <param name="itemId">Item id.</param>
    /// <param name="user">The signed-in user. Never null: Jellyfin treats a null user as "can see everything".</param>
    /// <returns>The ROM, or null.</returns>
    public RomItem? GetRom(Guid itemId, User user) => GetRom(itemId, user, out _);

    /// <summary>
    /// <see cref="GetRom(Guid, User)"/>, also saying when a null is only "can't read the game's files right now" (S4):
    /// a game the user can see whose disc set or disc header can't be read now and never was. Callers answer that with
    /// a 503 so apps try again, never a 404 (which apps take as "this game won't take a save").
    /// </summary>
    /// <param name="itemId">Item id.</param>
    /// <param name="user">The signed-in user.</param>
    /// <param name="cantReadNow">True when the null is only "can't read now".</param>
    /// <returns>The ROM, or null.</returns>
    public RomItem? GetRom(Guid itemId, User user, out bool cantReadNow)
    {
        ArgumentNullException.ThrowIfNull(user);
        cantReadNow = false;
        if (itemId == Guid.Empty)
        {
            return null;
        }

        var item = _library.GetItemById<BaseItem>(itemId, user);
        if (item is null || !IsInItsLibrary(item))
        {
            return null;
        }

        return ToRom(item, _images, out cantReadNow);
    }

    /// <summary>Gets a ROM with no user check. Only for server-side jobs (JellyEMU import), never for a request.</summary>
    /// <param name="itemId">Item id.</param>
    /// <returns>The ROM, or null.</returns>
    public RomItem? GetRomForServerJob(Guid itemId) => itemId == Guid.Empty ? null : (_library.GetItemById(itemId) is BaseItem i ? ToRom(i, _images) : null);

    /// <summary>True if any library item has this id (used to tell "game removed" apart from "hidden from you").</summary>
    /// <param name="itemId">Item id.</param>
    /// <returns>Whether it exists.</returns>
    public bool Exists(Guid itemId) => itemId != Guid.Empty && _library.GetItemById(itemId) is not null;

    /// <summary>The id a file had (or would have) as a game of its own: Jellyfin's id for a Book at that path (D233).</summary>
    /// <param name="path">Full path of the file.</param>
    /// <returns>The item id.</returns>
    public Guid OldItemId(string path) => _library.GetNewItemId(path, typeof(Book));

    /// <summary>Lists every ROM the user can see.</summary>
    /// <param name="user">User.</param>
    /// <returns>ROMs.</returns>
    public IReadOnlyList<RomItem> ListRoms(User user)
    {
        var inLibrary = LibraryCheck();
        return _library.GetItemList(RomQuery(user)).Select(i => ToRom(i, _images)).OfType<RomItem>().Where(r => inLibrary(r.Item)).ToList();
    }

    /// <summary>True if the user can see at least one ROM: the same rules as <see cref="ListRoms"/>, stopping at the
    /// first one and with no cover tags worked out (asked on every Jellyfin web page load).</summary>
    /// <param name="user">User.</param>
    /// <returns>Whether there is one.</returns>
    public bool HasAnyRom(User user)
    {
        var inLibrary = LibraryCheck();
        return _library.GetItemList(RomQuery(user)).Any(i => ToRom(i) is not null && inLibrary(i));
    }

    private static InternalItemsQuery RomQuery(User user)
    {
        ArgumentNullException.ThrowIfNull(user);
        return new InternalItemsQuery(user)
        {
            IncludeItemTypes = [BaseItemKind.Book],
            Recursive = true,
            Tags = RomTags.ToArray(),
        };
    }

    /// <summary>
    /// <see cref="IsInItsLibrary"/> for a whole list: items in the same folder share their library's folders, so those are
    /// looked up once per folder rather than once per game.
    /// </summary>
    private Func<BaseItem, bool> LibraryCheck()
    {
        var byParent = new Dictionary<Guid, string[]>();
        return item =>
        {
            if (!byParent.TryGetValue(item.ParentId, out var folders))
            {
                folders = LibraryFolders(item);
                if (item.ParentId != Guid.Empty)
                {
                    byParent[item.ParentId] = folders;
                }
            }

            return IsInsideAny(item.Path, folders);
        };
    }

    /// <summary>
    /// True if the item is a ROM whose file is NOT inside one of its library's folders (G5 s0.2 M1, defence in depth):
    /// such an item is never a game and Jellyfin's download route refuses it (<see cref="RomFileGuard"/>).
    /// </summary>
    /// <param name="itemId">Item id.</param>
    /// <returns>Whether to refuse it.</returns>
    public bool IsRomOutsideItsLibrary(Guid itemId) =>
        itemId != Guid.Empty && _library.GetItemById(itemId) is BaseItem item && ToRom(item) is not null && !IsInItsLibrary(item);

    /// <summary>True if the path is strictly inside one of the folders (after resolving <c>..</c>).</summary>
    /// <param name="path">Item path.</param>
    /// <param name="folders">Library folders.</param>
    /// <returns>Whether it is inside one.</returns>
    internal static bool IsInsideAny(string? path, IEnumerable<string> folders) =>
        !string.IsNullOrEmpty(path) && folders.Any(f => Scanner.RomScanner.IsInside(path, f));

    // The item's own library folders (a ROM item is always inside one when it was found by a scan).
    private bool IsInItsLibrary(BaseItem item) => IsInsideAny(item.Path, LibraryFolders(item));

    private string[] LibraryFolders(BaseItem item) =>
        _library.GetCollectionFolders(item).OfType<CollectionFolder>().SelectMany(f => f.PhysicalLocations ?? []).ToArray();

    /// <summary>Turns an item into a ROM, or null if it isn't one.</summary>
    /// <param name="item">Item.</param>
    /// <param name="images">Jellyfin's image processor, for the real image tag (null = a cache-busting stand-in).</param>
    /// <returns>The ROM or null.</returns>
    internal static RomItem? ToRom(BaseItem item, IImageProcessor? images = null) => ToRom(item, images, out _);

    /// <summary>Turns an item into a ROM, or null if it isn't one, or isn't one that can be told now.</summary>
    /// <param name="item">Item.</param>
    /// <param name="images">Jellyfin's image processor (null = a cache-busting stand-in).</param>
    /// <param name="cantReadNow">True when the answer is null only because the game's files can't be read now and were
    /// never read (a sleeping NAS, review S4): the game is not gone, so a request for it should be tried again.</param>
    /// <returns>The ROM or null.</returns>
    internal static RomItem? ToRom(BaseItem item, IImageProcessor? images, out bool cantReadNow)
    {
        cantReadNow = false;
        if (item is not Book || item.IsFolder || string.IsNullOrEmpty(item.Path))
        {
            return null;
        }

        var tags = item.Tags ?? [];
        if (!tags.Any(t => RomTags.Contains(t, StringComparer.Ordinal)))
        {
            return null;
        }

        var console = GameConsole.Detect(tags, item.Path);
        if (!GameConsole.All.Any(c => c.Tags.Any(t => tags.Contains(t, StringComparer.Ordinal))))
        {
            // An item no tag names a console for (JellyEMU's "Unknown"): a disc image's own header settles the .iso
            // clash (PSP, PS2, GameCube and Wii all use .iso: D218) instead of the first console with that extension.
            // Read once per file version (review #4). A disc that can't be read now and was never read is left out of
            // this answer rather than guessed: a guess could give it another console's save rules.
            if (!Scanner.ConsoleDetector.TryDiscTag(item.Path, out var disc))
            {
                cantReadNow = true;
                return null;
            }

            if (disc is not null)
            {
                console = GameConsole.Detect([disc], item.Path);
            }
        }

        if (console is null)
        {
            return null;
        }

        // D-8: an item for a folder or a .m3u is a game only while it is a whole disc set (2+ discs in the console's own
        // formats); JellyEMU also makes folder items for a disc folder we turn down (one disc, or a .cue/.bin). A set folder
        // that can't be read now uses its last set read; with none, the game is "can't read now", not "gone" (S4). A path
        // with a set of 2+ is a set folder or .m3u, which every disc console takes, so the path check is for the rest only
        // (it asks whether the folder exists, which a sleeping drive answers "no").
        if (console.TakesDiscSets && !console.Extensions.Contains(System.IO.Path.GetExtension(item.Path).ToLowerInvariant()))
        {
            if (!Scanner.RomScanner.TryDiscSet(item.Path, console, out var set))
            {
                cantReadNow = true;
                return null;
            }

            if (set.Count < 2)
            {
                return null;
            }
        }
        else if (!console.AcceptsItemPath(item.Path))
        {
            return null;
        }

        string? imageTag = null;
        if (item.HasImage(ImageType.Primary, 0))
        {
            // Jellyfin's own image tag (B2). The fallback (no image processor, e.g. unit tests) only busts the cache.
            var info = item.GetImageInfo(ImageType.Primary, 0);
            imageTag = info is null ? null : images?.GetImageCacheTag(item, info) ?? info.DateModified.Ticks.ToString(CultureInfo.InvariantCulture);
        }

        var region = tags.FirstOrDefault(t => Regions.Contains(t, StringComparer.Ordinal));
        var series = item is Book b && !string.IsNullOrWhiteSpace(b.SeriesName) ? b.SeriesName : null;
        return new RomItem(item.Id, item.Name ?? "Untitled", console, item.SortName ?? item.Name ?? string.Empty, imageTag, region, series, item.ProductionYear, item);
    }
}
