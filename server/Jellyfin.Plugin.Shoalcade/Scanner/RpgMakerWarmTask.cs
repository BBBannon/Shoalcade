using System;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Data.Enums;
using MediaBrowser.Controller.Entities;
using MediaBrowser.Controller.Library;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Shoalcade.Scanner;

/// <summary>
/// After each library scan, works out the file list (with hashes) of every RPG Maker game (D147), so the first
/// download of a big game doesn't wait while hundreds of MB are read. Unchanged games only cost a look at file sizes and
/// times. Only reads the game folders.
/// </summary>
public sealed class RpgMakerWarmTask : ILibraryPostScanTask
{
    private readonly ILibraryManager _library;
    private readonly ShoalcadeServices _services;
    private readonly ScannerGuard _guard;
    private readonly ILogger<RpgMakerWarmTask> _logger;

    /// <summary>Initializes a new instance of the <see cref="RpgMakerWarmTask"/> class.</summary>
    /// <param name="library">Library manager.</param>
    /// <param name="services">Stores.</param>
    /// <param name="guard">Stands down while JellyEMU is installed.</param>
    /// <param name="logger">Logger.</param>
    public RpgMakerWarmTask(ILibraryManager library, ShoalcadeServices services, ScannerGuard guard, ILogger<RpgMakerWarmTask> logger)
    {
        _library = library;
        _services = services;
        _guard = guard;
        _logger = logger;
    }

    /// <inheritdoc />
    public async Task Run(IProgress<double> progress, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(progress);
        var folders = _guard.StandDown
            ? []
            : _library.GetItemList(new InternalItemsQuery { IncludeItemTypes = [BaseItemKind.Book], Recursive = true, Tags = [RpgMakerGame.Tag] })
                .Select(i => RpgMakerGame.GameRoot(i.Path, inConsoleFolder: true))
                .OfType<string>()
                .Distinct(StringComparer.Ordinal)
                .ToList();
        for (var i = 0; i < folders.Count; i++)
        {
            try
            {
                await _services.RpgMaker.GetManifestAsync(folders[i], cancellationToken).ConfigureAwait(false);
            }
            catch (Exception ex) when (ex is System.IO.IOException or UnauthorizedAccessException or System.IO.InvalidDataException)
            {
                _logger.LogInformation("Could not list RPG Maker game {Folder} yet: {Message}", folders[i], ex.Message);
            }

            progress.Report(100.0 * (i + 1) / folders.Count);
        }

        progress.Report(100);
    }
}
