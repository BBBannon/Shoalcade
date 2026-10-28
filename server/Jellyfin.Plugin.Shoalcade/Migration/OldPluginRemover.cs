using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using MediaBrowser.Common;
using MediaBrowser.Common.Plugins;
using MediaBrowser.Model.Plugins;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Shoalcade.Migration;

/// <summary>A plugin as Jellyfin lists it (what <see cref="OldPluginRemover.Pick"/> needs).</summary>
/// <param name="Id">Plugin id.</param>
/// <param name="Name">Name.</param>
/// <param name="Version">Version.</param>
/// <param name="Path">Its install folder.</param>
/// <param name="Status">Status.</param>
public sealed record PluginEntry(Guid Id, string Name, Version Version, string Path, PluginStatus Status);

/// <summary>
/// Jellyfin 12.3 groups plugin versions by <b>name</b>, so after the rename (D124) an old "JellyGames" folder next to the
/// new "Shoalcade" one is loaded as a second, separate plugin at the next full start, even though both have the same id.
/// Two copies would both scan, both serve pages and keep separate saves. So the new plugin asks Jellyfin's own plugin
/// manager to remove the old version, exactly as the Uninstall button does: only its <b>program folder</b>
/// (<c>plugins/JellyGames_x.y.z.w</c>) goes; its settings file and the data folder are never touched (they were copied).
/// If the old one was running in this start, Jellyfin is told a restart is needed.
/// </summary>
public static class OldPluginRemover
{
    /// <summary>Which plugins are old copies of this one: same id, a different (older) name, lower version, in a
    /// plugin folder named after the old name. Never this plugin itself.</summary>
    /// <param name="all">Every plugin Jellyfin knows.</param>
    /// <param name="id">This plugin's id.</param>
    /// <param name="name">This plugin's name.</param>
    /// <param name="version">This plugin's version.</param>
    /// <returns>The ones to remove.</returns>
    public static IReadOnlyList<PluginEntry> Pick(IEnumerable<PluginEntry> all, Guid id, string name, Version version)
    {
        ArgumentNullException.ThrowIfNull(all);
        return all.Where(p => p.Id == id
                && !string.Equals(p.Name, name, StringComparison.OrdinalIgnoreCase)
                && string.Equals(p.Name, LegacyNames.MenuName, StringComparison.OrdinalIgnoreCase)
                && p.Version < version
                && p.Status != PluginStatus.Deleted
                && p.Status != PluginStatus.Disabled
                && Path.GetFileName(Path.TrimEndingDirectorySeparator(p.Path ?? string.Empty)).StartsWith(LegacyNames.MenuName + "_", StringComparison.OrdinalIgnoreCase))
            .ToList();
    }

    /// <summary>Removes old JellyGames copies of this plugin through Jellyfin's plugin manager. Never throws.</summary>
    /// <param name="plugins">Jellyfin's plugin manager.</param>
    /// <param name="host">Jellyfin (to ask for a restart).</param>
    /// <param name="logger">Logger.</param>
    /// <returns>How many were removed.</returns>
    public static int RemoveOldCopies(IPluginManager plugins, IApplicationHost host, ILogger logger)
    {
        ArgumentNullException.ThrowIfNull(plugins);
        ArgumentNullException.ThrowIfNull(host);
        ArgumentNullException.ThrowIfNull(logger);
        var me = Plugin.Instance;
        if (me is null)
        {
            return 0;
        }

        var removed = 0;
        try
        {
            var list = plugins.Plugins.ToList();
            var entries = list.Select(p => new PluginEntry(p.Id, p.Name, p.Version, p.Path, p.Manifest.Status)).ToList();
            foreach (var old in Pick(entries, me.Id, me.Name, me.Version))
            {
                var lp = list.First(p => p.Id == old.Id && p.Version == old.Version && string.Equals(p.Path, old.Path, StringComparison.Ordinal));
                var wasRunning = old.Status == PluginStatus.Active;
                plugins.RemovePlugin(lp);
                removed++;
                if (Directory.Exists(old.Path))
                {
                    // The folder couldn't go (Windows: this process still has its DLL open). A "Deleted" plugin that is
                    // alone under its name is loaded again by Jellyfin at the next start, so mark it Disabled instead:
                    // never loaded, still listed, removable with Dashboard -> Plugins -> Uninstall.
                    plugins.DisablePlugin(lp);
                    logger.LogWarning(
                        "Shoalcade: switched off the old JellyGames {Version} plugin ({Path}); it can be uninstalled in Dashboard -> Plugins. Its settings and data were copied and are kept.{Restart}",
                        old.Version,
                        old.Path,
                        wasRunning ? " It is still running until Jellyfin restarts: please restart Jellyfin once more." : string.Empty);
                }
                else
                {
                    logger.LogWarning(
                        "Shoalcade: removed the old JellyGames {Version} plugin program files ({Path}); its settings and data were copied and are kept.{Restart}",
                        old.Version,
                        old.Path,
                        wasRunning ? " It is still running until Jellyfin restarts: please restart Jellyfin once more." : string.Empty);
                }

                if (wasRunning)
                {
                    host.NotifyPendingRestart();
                }
            }
        }
#pragma warning disable CA1031 // never break start-up; the old copy stays and is tried again next start
        catch (Exception ex)
#pragma warning restore CA1031
        {
            logger.LogError(ex, "Shoalcade: could not remove the old JellyGames plugin; remove it in Dashboard -> Plugins");
        }

        return removed;
    }
}
