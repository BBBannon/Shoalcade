using System;
using System.Collections.Generic;
using System.Globalization;
using Jellyfin.Plugin.Shoalcade.Configuration;
using Jellyfin.Plugin.Shoalcade.Migration;
using MediaBrowser.Common.Configuration;
using MediaBrowser.Common.Plugins;
using MediaBrowser.Model.Plugins;
using MediaBrowser.Model.Serialization;

namespace Jellyfin.Plugin.Shoalcade;

/// <summary>The Shoalcade plugin: secure saves, snapshots and settings for the Shoalcade app and browser player.</summary>
public class Plugin : BasePlugin<PluginConfiguration>, IHasWebPages
{
    /// <summary>Plugin id. Never change it: Jellyfin keys the settings by it.</summary>
    public const string PluginGuid = "5a8b0f1e-7c3d-4e2a-9b6f-1d4c8e9a2f70";

    /// <summary>Initializes a new instance of the <see cref="Plugin"/> class.</summary>
    /// <param name="applicationPaths">Application paths.</param>
    /// <param name="xmlSerializer">XML serializer.</param>
    public Plugin(IApplicationPaths applicationPaths, IXmlSerializer xmlSerializer)
        : base(applicationPaths, xmlSerializer)
    {
        // Renamed from JellyGames (D124): Jellyfin names the settings file after the assembly, so the old
        // Jellyfin.Plugin.JellyGames.xml is copied to the new name before the settings are first read. Never moved.
        ConfigCarryOver = RenameMigration.MigrateConfigFile(ConfigurationFilePath, out var error);
        ConfigCarryOverError = error;
        Instance = this;
    }

    /// <summary>Gets what happened to the JellyGames settings file at this start (logged by the carry-over task).</summary>
    public static ConfigMigrationOutcome ConfigCarryOver { get; private set; }

    /// <summary>Gets why copying the JellyGames settings file failed, if it did.</summary>
    public static string? ConfigCarryOverError { get; private set; }

    /// <summary>Gets the running instance.</summary>
    public static Plugin? Instance { get; private set; }

    /// <inheritdoc />
    public override string Name => "Shoalcade";

    /// <inheritdoc />
    public override Guid Id => Guid.Parse(PluginGuid);

    /// <inheritdoc />
    public override string Description => "Shoalcade for Jellyfin: play your own games on your Android phone (offline) and in the browser, with every save kept safe on your Jellyfin server.";

    /// <inheritdoc />
    public IEnumerable<PluginPageInfo> GetPages()
    {
        return
        [
            new PluginPageInfo
            {
                Name = Name,
                EmbeddedResourcePath = string.Format(CultureInfo.InvariantCulture, "{0}.Configuration.configPage.html", GetType().Namespace),
            },
        ];
    }
}
