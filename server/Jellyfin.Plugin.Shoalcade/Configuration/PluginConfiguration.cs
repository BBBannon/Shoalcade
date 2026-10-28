using MediaBrowser.Model.Plugins;

namespace Jellyfin.Plugin.Shoalcade.Configuration;

/// <summary>Admin settings (Dashboard → Plugins → Shoalcade).</summary>
public class PluginConfiguration : BasePluginConfiguration
{
    /// <summary>Gets or sets a value indicating whether the browser player and library pages are switched on.</summary>
    public bool EnableBrowserPlayer { get; set; } = true;

    /// <summary>Gets or sets a value indicating whether Jellyfin's own web client shows a Games menu link and Play/Saves buttons (D67).</summary>
    public bool ShowInJellyfin { get; set; } = true;

    /// <summary>Gets or sets a value indicating whether signed-in people can download the phone app from this server
    /// (Shoalcade Settings, Phone app, D134). Off: the download links and the app info answer 404.</summary>
    public bool OfferPhoneApp { get; set; } = true;

    /// <summary>Gets or sets a value indicating whether games libraries are kept off everyone's Jellyfin home page (D109).
    /// Games stay in search, the Shoalcade pages and the app.</summary>
    public bool KeepGamesOffHomePage { get; set; } = true;

    /// <summary>Gets or sets a value indicating whether JellyEMU saves are imported (read-only, D40).</summary>
    public bool ImportJellyEmuSaves { get; set; } = true;

    /// <summary>Gets or sets how many recent versions of each save are always kept.</summary>
    public int KeepRecentVersions { get; set; } = 20;

    /// <summary>Gets or sets for how many days one version per day is kept.</summary>
    public int KeepDailyDays { get; set; } = 30;

    /// <summary>Gets or sets the most save storage one user may use, in MB (all versions). 0 = no limit.</summary>
    public int SaveQuotaMegabytes { get; set; } = 2048;

    /// <summary>Gets or sets the most snapshot storage one user may use, in MB. 0 = no limit.</summary>
    public int SnapshotQuotaMegabytes { get; set; } = 4096;

    /// <summary>Gets or sets the largest single snapshot accepted, in MB (1-1024). Default 32 (security M1): browser
    /// snapshots, DS included, are well under that.</summary>
    public int MaxSnapshotMegabytes { get; set; } = 32;
}
