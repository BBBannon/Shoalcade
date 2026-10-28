using System;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Data.Events.Users;
using MediaBrowser.Controller.Events;
using MediaBrowser.Controller.Library;
using MediaBrowser.Model.Tasks;

namespace Jellyfin.Plugin.Shoalcade.Library;

/// <summary>
/// Scheduled task (Dashboard → Scheduled Tasks → Shoalcade): keeps games libraries off Jellyfin's home page (D109).
/// Runs at start-up and every 6 hours; also after each library scan, when a user is created or their library access changes,
/// when someone saves their own home screen settings, and when the setting changes.
/// </summary>
public class KeepGamesOffHomePageTask : IScheduledTask
{
    private readonly HomePageHider _hider;

    /// <summary>Initializes a new instance of the <see cref="KeepGamesOffHomePageTask"/> class.</summary>
    /// <param name="hider">Home page hider.</param>
    public KeepGamesOffHomePageTask(HomePageHider hider) => _hider = hider;

    /// <inheritdoc />
    public string Name => "Keep games off the home page";

    /// <inheritdoc />
    public string Key => "ShoalcadeKeepGamesOffHomePage";

    /// <inheritdoc />
    public string Description => "Keeps games libraries off everyone's Jellyfin home page (set in the Shoalcade plugin settings). Games stay in search and the Shoalcade pages.";

    /// <inheritdoc />
    public string Category => "Shoalcade";

    /// <inheritdoc />
    public async Task ExecuteAsync(IProgress<double> progress, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(progress);
        await _hider.SyncAsync().ConfigureAwait(false);
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

/// <summary>After every library scan: a library that has just gained games is kept off the home page straight away.</summary>
public class KeepGamesOffHomePageAfterScan : ILibraryPostScanTask
{
    private readonly HomePageHider _hider;

    /// <summary>Initializes a new instance of the <see cref="KeepGamesOffHomePageAfterScan"/> class.</summary>
    /// <param name="hider">Home page hider.</param>
    public KeepGamesOffHomePageAfterScan(HomePageHider hider) => _hider = hider;

    /// <inheritdoc />
    public async Task Run(IProgress<double> progress, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(progress);
        await _hider.SyncQuietlyAsync().ConfigureAwait(false);
        progress.Report(100);
    }
}

/// <summary>A new user starts with games off their home page; so does a user just given access to a games library
/// (Jellyfin raises "user updated" for library access and name changes, not for display settings).</summary>
public class KeepGamesOffHomePageForUserChanges : IEventConsumer<UserCreatedEventArgs>, IEventConsumer<UserUpdatedEventArgs>
{
    private readonly HomePageHider _hider;

    /// <summary>Initializes a new instance of the <see cref="KeepGamesOffHomePageForUserChanges"/> class.</summary>
    /// <param name="hider">Home page hider.</param>
    public KeepGamesOffHomePageForUserChanges(HomePageHider hider) => _hider = hider;

    /// <inheritdoc />
    public Task OnEvent(UserCreatedEventArgs eventArgs) => _hider.SyncQuietlyAsync();

    /// <inheritdoc />
    public Task OnEvent(UserUpdatedEventArgs eventArgs) => _hider.SyncQuietlyAsync();
}
