using System;
using System.IO;
using Jellyfin.Plugin.Shoalcade.Cheats;
using Jellyfin.Plugin.Shoalcade.Configuration;
using Jellyfin.Plugin.Shoalcade.Import;
using Jellyfin.Plugin.Shoalcade.Migration;
using Jellyfin.Plugin.Shoalcade.Prefs;
using Jellyfin.Plugin.Shoalcade.Saves;
using Jellyfin.Plugin.Shoalcade.States;
using Jellyfin.Plugin.Shoalcade.Storage;
using MediaBrowser.Common.Configuration;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Shoalcade;

/// <summary>
/// The plugin's stores, created once. All data lives under <c>{DataPath}/shoalcade/</c> (include it in server backups).
/// </summary>
public sealed class ShoalcadeServices
{
    /// <summary>Initializes a new instance of the <see cref="ShoalcadeServices"/> class.</summary>
    /// <param name="paths">Jellyfin paths.</param>
    /// <param name="loggerFactory">Logger factory.</param>
    public ShoalcadeServices(IApplicationPaths paths, ILoggerFactory loggerFactory)
    {
        ArgumentNullException.ThrowIfNull(paths);
        ArgumentNullException.ThrowIfNull(loggerFactory);
        // Renamed from JellyGames (D124): the old data folder is copied (never moved) on the first start, before any store opens.
        DataMigration = RenameMigration.MigrateDataFolder(paths.DataPath, "shoalcade", loggerFactory.CreateLogger<ShoalcadeServices>(), DateTimeOffset.UtcNow);
        DataRoot = DataMigration.Folder;
        Saves = new SaveStore(Path.Combine(DataRoot, "saves"), SaveOptions, TimeProvider.System, loggerFactory.CreateLogger<SaveStore>());
        States = new StateStore(Path.Combine(DataRoot, "states"), TimeProvider.System, loggerFactory.CreateLogger<StateStore>());
        Prefs = new PrefsStore(Path.Combine(DataRoot, "prefs"), TimeProvider.System, loggerFactory.CreateLogger<PrefsStore>());
        Cheats = new CheatStore(Path.Combine(DataRoot, "cheats"), TimeProvider.System, loggerFactory.CreateLogger<CheatStore>());
        Storage = new StorageReport(Path.Combine(DataRoot, "saves"), Path.Combine(DataRoot, "states"), Path.Combine(DataRoot, "prefs"), Path.Combine(DataRoot, "cheats"));
        JellyEmu = new JellyEmuImporter(Path.Combine(paths.DataPath, "jellyemu-saves"), Saves, () => Config.ImportJellyEmuSaves, loggerFactory.CreateLogger<JellyEmuImporter>());
    }

    /// <summary>Gets the current admin settings.</summary>
    public static PluginConfiguration Config => Plugin.Instance?.Configuration ?? new PluginConfiguration();

    /// <summary>Gets the plugin's data folder (<c>shoalcade</c>; the old <c>jellygames</c> one only if copying it failed this start).</summary>
    public string DataRoot { get; }

    /// <summary>Gets what the JellyGames data copy did at this start (D124).</summary>
    public DataMigrationResult DataMigration { get; }

    /// <summary>Gets the save store.</summary>
    public SaveStore Saves { get; }

    /// <summary>Gets the snapshot store.</summary>
    public StateStore States { get; }

    /// <summary>Gets the settings store.</summary>
    public PrefsStore Prefs { get; }

    /// <summary>Gets the cheats store (D143).</summary>
    public CheatStore Cheats { get; }

    /// <summary>Gets the admin storage report (view only).</summary>
    public StorageReport Storage { get; }

    /// <summary>Gets the JellyEMU save importer.</summary>
    public JellyEmuImporter JellyEmu { get; }

    /// <summary>The save limits in force now, from the admin settings (clamped to sane ranges).</summary>
    /// <returns>Options.</returns>
    internal static SaveStoreOptions SaveOptions()
    {
        var c = Config;
        // The JellyGames settings couldn't be carried over (D124), so these are defaults, not the admin's keep rule,
        // which may be longer: delete no old save versions until the settings are right (G6 dev 2 M1).
        var keepAll = Plugin.ConfigCarryOver == ConfigMigrationOutcome.Failed;
        return new SaveStoreOptions
        {
            KeepRecent = keepAll ? 1000 : Math.Clamp(c.KeepRecentVersions, 1, 1000),
            KeepDailyDays = keepAll ? 3650 : Math.Clamp(c.KeepDailyDays, 0, 3650),
            UserQuotaBytes = Math.Max(0, c.SaveQuotaMegabytes) * 1024L * 1024L,
        };
    }
}
