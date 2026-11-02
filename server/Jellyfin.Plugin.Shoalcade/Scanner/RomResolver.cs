using System;
using System.Collections.Concurrent;
using System.IO;
using System.Linq;
using Jellyfin.Data.Enums;
using MediaBrowser.Controller.Entities;
using MediaBrowser.Controller.Library;
using MediaBrowser.Controller.Resolvers;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;

namespace Jellyfin.Plugin.Shoalcade.Scanner;

/// <summary>
/// The Shoalcade library scanner: turns ROM files in Books (or mixed) libraries into plain <see cref="Book"/> items,
/// as JellyEMU did (D42), tagged <c>Shoalcade</c> + console + region (+ MultiDisc, + Azahar for 3DS). Path rules:
/// <see cref="RomScanner"/>. Stands down while JellyEMU is installed (<see cref="ScannerGuard"/>).
/// It only creates library entries: nothing is ever written next to, moved or deleted from the ROM folders.
/// Jellyfin finds this class itself (every <see cref="IItemResolver"/> in a plugin is used).
/// Never silent (D227, lessons 29/10/2026): a file that looks like a game but isn't one (another console's ROM, a wrong file
/// type in a console folder) is logged with the reason, once per file at Information, then at Debug on later scans.
/// </summary>
public sealed class RomResolver : IItemResolver
{
    /// <summary>Files logged at Information at most (then Debug), so a huge folder of another console can't flood the log.</summary>
    internal const int MaxReported = 5000;

    private readonly ScannerGuard _guard;
    private readonly ILogger _logger;
    private readonly ConcurrentDictionary<string, byte> _reported = new(RomScanner.PathComparison == StringComparison.Ordinal ? StringComparer.Ordinal : StringComparer.OrdinalIgnoreCase);

    /// <summary>Initializes a new instance of the <see cref="RomResolver"/> class.</summary>
    /// <param name="guard">Stands down while JellyEMU is installed.</param>
    /// <param name="logger">Logs the files that are skipped.</param>
    public RomResolver(ScannerGuard guard, ILogger<RomResolver> logger)
        : this(guard, (ILogger)logger)
    {
    }

    /// <summary>Initializes a new instance of the <see cref="RomResolver"/> class (tests).</summary>
    /// <param name="guard">Stands down while JellyEMU is installed.</param>
    /// <param name="logger">Logger; none = nothing logged.</param>
    internal RomResolver(ScannerGuard guard, ILogger? logger = null)
    {
        _guard = guard;
        _logger = logger ?? NullLogger.Instance;
    }

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
        if (rom is null)
        {
            ReportSkipped(args.Path, args.IsDirectory);
        }

        return rom is null ? null : ToBook(rom);
    }

    /// <summary>Logs why a file that looks like a game is not one: the first time at Information, later at Debug.</summary>
    private void ReportSkipped(string path, bool isDirectory)
    {
        // Review #16: only ever a log line, so nothing in it may break the scan.
        try
        {
            var first = !_reported.ContainsKey(path) && _reported.Count < MaxReported;
            if (!first && !_logger.IsEnabled(LogLevel.Debug))
            {
                return;
            }

            var reason = isDirectory ? RomScanner.FolderSkipReason(path) : RomScanner.SkipReason(path, knownNotAGame: true);
            if (reason is null)
            {
                return;
            }

            first = first && _reported.TryAdd(path, 0);
            // Folder + file name only: enough to find it in the library, and never a path outside it.
            var trimmed = path.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
            var shown = Path.GetFileName(Path.GetDirectoryName(trimmed)) + "/" + Path.GetFileName(trimmed) + (isDirectory ? "/" : string.Empty);
            _logger.Log(first ? LogLevel.Information : LogLevel.Debug, "Scanner skipped {File}: {Reason}", shown, reason);
        }
#pragma warning disable CA1031 // a skip-log line must never stop a library scan
        catch (Exception ex)
#pragma warning restore CA1031
        {
            _logger.LogDebug(ex, "Scanner skip log failed for one file");
        }
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
