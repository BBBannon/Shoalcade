using System;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using MediaBrowser.Model.Tasks;

namespace Jellyfin.Plugin.Shoalcade.Saves;

/// <summary>
/// Scheduled task (Dashboard → Scheduled Tasks → Shoalcade): removes the hidden copies "Delete all saves" keeps, once
/// they are older than <see cref="DeletedSaves.KeepFor"/> (D168). Runs at start-up and every 6 hours.
/// </summary>
public class ClearDeletedSavesTask : IScheduledTask
{
    private readonly ShoalcadeServices _services;

    /// <summary>Initializes a new instance of the <see cref="ClearDeletedSavesTask"/> class.</summary>
    /// <param name="services">Shoalcade services.</param>
    public ClearDeletedSavesTask(ShoalcadeServices services) => _services = services;

    /// <inheritdoc />
    public string Name => "Clear out deleted saves";

    /// <inheritdoc />
    public string Key => "ShoalcadeClearDeletedSaves";

    /// <inheritdoc />
    public string Description => "\"Delete all saves\" keeps a hidden copy for 7 days so a mistake can be undone. This removes copies older than that.";

    /// <inheritdoc />
    public string Category => "Shoalcade";

    /// <inheritdoc />
    public Task ExecuteAsync(IProgress<double> progress, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(progress);
        _services.Deleted.Purge();
        progress.Report(100);
        return Task.CompletedTask;
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
