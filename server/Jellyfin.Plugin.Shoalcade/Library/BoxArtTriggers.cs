using System;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using MediaBrowser.Controller.Library;
using MediaBrowser.Model.Tasks;

namespace Jellyfin.Plugin.Shoalcade.Library;

/// <summary>
/// Scheduled task (Dashboard → Scheduled Tasks → Shoalcade): switches our box art source on for games libraries, once each.
/// Runs at start-up; also after each library scan and when a games library is made in the Shoalcade settings.
/// </summary>
public class SwitchOnBoxArtTask : IScheduledTask
{
    private readonly BoxArtSwitch _switch;

    /// <summary>Initializes a new instance of the <see cref="SwitchOnBoxArtTask"/> class.</summary>
    /// <param name="boxArt">Box art switch.</param>
    public SwitchOnBoxArtTask(BoxArtSwitch boxArt) => _switch = boxArt;

    /// <inheritdoc />
    public string Name => "Switch on box art for games libraries";

    /// <inheritdoc />
    public string Key => "ShoalcadeSwitchOnBoxArt";

    /// <inheritdoc />
    public string Description => "Ticks \"Shoalcade box art (libretro)\" in each games library's image sources, once. Untick it in the library's settings to stop it; Shoalcade won't tick it again.";

    /// <inheritdoc />
    public string Category => "Shoalcade";

    /// <inheritdoc />
    public async Task ExecuteAsync(IProgress<double> progress, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(progress);
        try
        {
            await _switch.SyncAsync().ConfigureAwait(false);
        }
        catch (ObjectDisposedException) when (cancellationToken.IsCancellationRequested)
        {
            return; // Jellyfin is shutting down or restarting mid-run: the task runs again at the next start.
        }

        progress.Report(100);
    }

    /// <inheritdoc />
    public IEnumerable<TaskTriggerInfo> GetDefaultTriggers() => [new TaskTriggerInfo { Type = TaskTriggerInfoType.StartupTrigger }];
}

/// <summary>After every library scan: a library that has just gained games gets box art straight away.</summary>
public class SwitchOnBoxArtAfterScan : ILibraryPostScanTask
{
    private readonly BoxArtSwitch _switch;

    /// <summary>Initializes a new instance of the <see cref="SwitchOnBoxArtAfterScan"/> class.</summary>
    /// <param name="boxArt">Box art switch.</param>
    public SwitchOnBoxArtAfterScan(BoxArtSwitch boxArt) => _switch = boxArt;

    /// <inheritdoc />
    public async Task Run(IProgress<double> progress, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(progress);
        await _switch.SyncQuietlyAsync().ConfigureAwait(false);
        progress.Report(100);
    }
}
