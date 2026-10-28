using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Plugin.Shoalcade.Library;
using MediaBrowser.Model.Tasks;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Shoalcade.Import;

/// <summary>
/// Scheduled task (Dashboard → Scheduled Tasks → Shoalcade): copies every JellyEMU save into Shoalcade, read-only (D40).
/// Runs at start-up and every 6 hours while JellyEMU is still installed; games are also checked each time they're opened.
/// </summary>
public class ImportJellyEmuSavesTask : IScheduledTask
{
    private readonly ShoalcadeServices _services;
    private readonly RomLibrary _library;
    private readonly ILogger<ImportJellyEmuSavesTask> _logger;

    /// <summary>Initializes a new instance of the <see cref="ImportJellyEmuSavesTask"/> class.</summary>
    /// <param name="services">Stores.</param>
    /// <param name="library">ROM library.</param>
    /// <param name="logger">Logger.</param>
    public ImportJellyEmuSavesTask(ShoalcadeServices services, RomLibrary library, ILogger<ImportJellyEmuSavesTask> logger)
    {
        _services = services;
        _library = library;
        _logger = logger;
    }

    /// <inheritdoc />
    public string Name => "Import JellyEMU saves";

    /// <inheritdoc />
    public string Key => "ShoalcadeImportJellyEmuSaves";

    /// <inheritdoc />
    public string Description => "Copies in-game saves from JellyEMU into Shoalcade. JellyEMU's files are only read, never changed.";

    /// <inheritdoc />
    public string Category => "Shoalcade";

    /// <inheritdoc />
    public async Task ExecuteAsync(IProgress<double> progress, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(progress);
        if (!ShoalcadeServices.Config.ImportJellyEmuSaves)
        {
            progress.Report(100);
            return;
        }

        var all = _services.JellyEmu.Discover().ToList();
        int done = 0, imported = 0, skipped = 0;
        foreach (var (user, item, slot, path) in all)
        {
            cancellationToken.ThrowIfCancellationRequested();
            done++;
            var rom = _library.GetRomForServerJob(item);
            if (_library.GetUser(user) is null || rom is null || JellyEmuImporter.SlotFor(rom.Console) != slot)
            {
                skipped++;
                continue;
            }

            try
            {
                var r = await _services.JellyEmu.ImportFileAsync(user, item, rom.Console, rom.Name, path, cancellationToken).ConfigureAwait(false);
                if (r?.Status == Saves.SaveWriteStatus.Created)
                {
                    imported++;
                }
            }
            catch (System.IO.IOException ex)
            {
                _logger.LogWarning(ex, "Could not import a JellyEMU save for item {Item}", item.ToString("N"));
            }

            progress.Report(100.0 * done / Math.Max(1, all.Count));
        }

        _logger.LogInformation("JellyEMU save import: {Imported} imported, {Skipped} skipped (no matching game/user), {Total} checked", imported, skipped, all.Count);
        progress.Report(100);
    }

    /// <inheritdoc />
    public IEnumerable<TaskTriggerInfo> GetDefaultTriggers()
    {
        return
        [
            new TaskTriggerInfo { Type = TaskTriggerInfoType.StartupTrigger },
            new TaskTriggerInfo { Type = TaskTriggerInfoType.IntervalTrigger, IntervalTicks = TimeSpan.FromHours(6).Ticks },
        ];
    }
}
