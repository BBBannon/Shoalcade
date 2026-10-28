using System.Linq;
using Jellyfin.Data.Enums;
using MediaBrowser.Controller.Entities;
using MediaBrowser.Controller.Library;
using MediaBrowser.Controller.Resolvers;

namespace Jellyfin.Plugin.Shoalcade.Scanner;

/// <summary>
/// The Shoalcade library scanner: turns ROM files in Books (or mixed) libraries into plain <see cref="Book"/> items,
/// as JellyEMU did (D42), tagged <c>Shoalcade</c> + console + region (+ MultiDisc, + Azahar for 3DS). Path rules:
/// <see cref="RomScanner"/>. Stands down while JellyEMU is installed (<see cref="ScannerGuard"/>).
/// It only creates library entries: nothing is ever written next to, moved or deleted from the ROM folders.
/// Jellyfin finds this class itself (every <see cref="IItemResolver"/> in a plugin is used).
/// </summary>
public sealed class RomResolver : IItemResolver
{
    private readonly ScannerGuard _guard;

    /// <summary>Initializes a new instance of the <see cref="RomResolver"/> class.</summary>
    /// <param name="guard">Stands down while JellyEMU is installed.</param>
    public RomResolver(ScannerGuard guard) => _guard = guard;

    /// <summary>Gets the priority: first, like JellyEMU's, so a .iso or .zip ROM is never taken for a film or a book.</summary>
    public ResolverPriority Priority => ResolverPriority.First;

    /// <summary>True if games can be in a library of this type (Books, or mixed content). Never films, music, TV, photos.</summary>
    /// <param name="type">Library type (null = mixed).</param>
    /// <returns>Whether it is a games-capable library.</returns>
    public static bool IsGamesLibraryType(CollectionType? type) => type is null or CollectionType.books;

    /// <inheritdoc />
    public BaseItem? ResolvePath(ItemResolveArgs args)
    {
        if (args?.FileInfo is null || !IsGamesLibraryType(args.CollectionType) || _guard.StandDown)
        {
            return null;
        }

        var rom = RomScanner.Resolve(args.Path, args.IsDirectory);
        return rom is null ? null : ToBook(rom);
    }

    /// <summary>The library item for a scanned game.</summary>
    /// <param name="rom">Scanned game.</param>
    /// <returns>A plain Jellyfin Book.</returns>
    internal static Book ToBook(ScannedRom rom) => new()
    {
        Name = rom.Name,
        Path = rom.Path,
        IsInMixedFolder = rom.IsInMixedFolder,
        SeriesName = rom.SeriesName,
        Tags = rom.Tags.ToArray(),
    };
}
