using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Data.Enums;
using Jellyfin.Plugin.Shoalcade.Library;
using Jellyfin.Plugin.Shoalcade.Scanner;
using MediaBrowser.Common.Plugins;
using MediaBrowser.Controller;
using MediaBrowser.Controller.Configuration;
using MediaBrowser.Controller.Entities;
using MediaBrowser.Controller.Library;
using MediaBrowser.Model.Configuration;
using MediaBrowser.Model.Tasks;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Shoalcade.Migration;

/// <summary>
/// The JSON side of the provider rename: every string list anywhere in a settings object gets the old provider names
/// replaced (D124). Works on whatever lists Jellyfin's settings have, so a new list in a later Jellyfin is covered too.
/// </summary>
public static class ProviderNameRename
{
    private static readonly JsonSerializerOptions Json = new();

    /// <summary>Renames old provider names inside a settings object.</summary>
    /// <typeparam name="T">Settings type.</typeparam>
    /// <param name="settings">Settings (not changed).</param>
    /// <param name="renamed">A changed copy, or null if nothing needed changing.</param>
    /// <returns>True if something was renamed.</returns>
    public static bool TryRename<T>(T settings, out T? renamed)
        where T : class
    {
        renamed = null;
        var node = JsonSerializer.SerializeToNode(settings, Json);
        if (node is null || !Walk(node))
        {
            return false;
        }

        renamed = node.Deserialize<T>(Json);
        return renamed is not null;
    }

    private static bool Walk(JsonNode node)
    {
        var changed = false;
        switch (node)
        {
            case JsonObject o:
                foreach (var key in o.Select(p => p.Key).ToList())
                {
                    if (o[key] is JsonArray a && a.Count > 0 && a.All(x => x is JsonValue v && v.TryGetValue<string>(out _)))
                    {
                        if (RenameMigration.RenameProviders(a.Select(x => x!.GetValue<string>())) is { } names)
                        {
                            o[key] = new JsonArray(names.Select(n => (JsonNode?)JsonValue.Create(n)).ToArray());
                            changed = true;
                        }
                    }
                    else if (o[key] is { } child)
                    {
                        changed |= Walk(child);
                    }
                }

                break;
            case JsonArray arr:
                foreach (var child in arr.Where(c => c is not null))
                {
                    changed |= Walk(child!);
                }

                break;
        }

        return changed;
    }
}

/// <summary>
/// Scheduled task (Dashboard → Scheduled Tasks → Shoalcade), run at every start-up: carries the JellyGames markers that
/// live on Jellyfin's side over to the new names (D124). Each step only changes what still has an old name, so it does
/// nothing after the first run.
/// <list type="bullet">
/// <item>Library and server settings: "JellyGames box art (libretro)" / "JellyGames ROM info" become the Shoalcade
/// names, ticks and order kept (an untick stays an untick).</item>
/// <item>Game items: the <c>JellyGames</c> tag becomes <c>Shoalcade</c> (IDs, names, locks, pictures untouched).</item>
/// <item>An old JellyGames copy of this plugin (same id, old name): removed through Jellyfin's plugin manager (program
/// files only), see <see cref="OldPluginRemover"/>.</item>
/// <item>Admin covers (D122): a game still pointing at the old <c>jellygames/covers</c> file is pointed at the copied one.</item>
/// </list>
/// </summary>
public class CarryOverFromJellyGamesTask : IScheduledTask
{
    private readonly ILibraryManager _library;
    private readonly IServerConfigurationManager _config;
    private readonly GameEditor _editor;
    private readonly IPluginManager _plugins;
    private readonly IServerApplicationHost _host;
    private readonly ShoalcadeServices _services;
    private readonly ILogger<CarryOverFromJellyGamesTask> _logger;

    /// <summary>Initializes a new instance of the <see cref="CarryOverFromJellyGamesTask"/> class.</summary>
    /// <param name="library">Library manager.</param>
    /// <param name="config">Server settings.</param>
    /// <param name="editor">Game editor.</param>
    /// <param name="plugins">Jellyfin's plugin manager (to remove an old JellyGames copy of this plugin).</param>
    /// <param name="host">Jellyfin (to ask for a restart).</param>
    /// <param name="services">Our stores (for the data folder in use).</param>
    /// <param name="logger">Logger.</param>
    public CarryOverFromJellyGamesTask(ILibraryManager library, IServerConfigurationManager config, GameEditor editor, IPluginManager plugins, IServerApplicationHost host, ShoalcadeServices services, ILogger<CarryOverFromJellyGamesTask> logger)
    {
        _services = services;
        _plugins = plugins;
        _host = host;
        _library = library;
        _config = config;
        _editor = editor;
        _logger = logger;
    }

    /// <inheritdoc />
    public string Name => "Carry over settings from JellyGames";

    /// <inheritdoc />
    public string Key => "ShoalcadeCarryOverFromJellyGames";

    /// <inheritdoc />
    public string Description => "Shoalcade used to be called JellyGames. Renames the old tags and image source names in your libraries so nothing is lost. Does nothing once done.";

    /// <inheritdoc />
    public string Category => "Shoalcade";

    /// <inheritdoc />
    public async Task ExecuteAsync(IProgress<double> progress, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(progress);

        ReportCarryOverProblems();

        // First: an old JellyGames copy of this plugin would otherwise be loaded again at the next full start.
        OldPluginRemover.RemoveOldCopies(_plugins, _host, _logger);
        var libs = RenameLibrarySettings();
        progress.Report(20);
        var server = RenameServerSettings();
        progress.Report(30);
        var tags = await RetagAsync(cancellationToken).ConfigureAwait(false);
        progress.Report(80);
        var covers = 0;
        foreach (var id in _editor.Edits.EditedIds())
        {
            if (await _editor.EnsureCoverAsync(id, cancellationToken).ConfigureAwait(false))
            {
                covers++;
            }
        }

        if (libs + server + tags + covers > 0)
        {
            _logger.LogInformation(
                "Carried over from JellyGames: {Libs} library setting(s), {Server} server setting(s), {Tags} game tag(s), {Covers} admin cover(s)",
                libs,
                server,
                tags,
                covers);
        }

        progress.Report(100);
    }

    /// <inheritdoc />
    public IEnumerable<TaskTriggerInfo> GetDefaultTriggers() => [new TaskTriggerInfo { Type = TaskTriggerInfoType.StartupTrigger }];

    /// <summary>Tells the admin what the copy couldn't carry over (G6 dev 2 M1, M2). Report only: nothing is moved or deleted.</summary>
    private void ReportCarryOverProblems()
    {
        if (Plugin.ConfigCarryOver == ConfigMigrationOutcome.Failed)
        {
            _logger.LogError(
                "Shoalcade: could not copy the JellyGames settings ({Error}), so Shoalcade is on default settings. No old save versions are deleted until Jellyfin restarts. Please set your settings again in Dashboard -> Plugins -> Shoalcade (the old file {File} is kept).",
                Plugin.ConfigCarryOverError,
                LegacyNames.ConfigFile);
        }

        var late = RenameMigration.WrittenToOldFolderSinceCopy(_services.DataRoot);
        if (late.Count > 0)
        {
            _logger.LogWarning(
                "Shoalcade: {Count} save/snapshot/settings file(s) were written to the old JellyGames data folder after it was copied (an old JellyGames copy was still running), so Shoalcade doesn't show them. They are kept, nothing was deleted: {Files}",
                late.Count,
                string.Join(", ", late.Take(20)));
        }
    }

    private int RenameLibrarySettings()
    {
        var n = 0;
        foreach (var f in _library.GetVirtualFolders())
        {
            if (!Guid.TryParse(f.ItemId, out var id) || _library.GetItemById(id) is not CollectionFolder folder)
            {
                continue;
            }

            if (ProviderNameRename.TryRename(folder.GetLibraryOptions(), out var renamed) && renamed is not null)
            {
                folder.UpdateLibraryOptions(renamed);
                n++;
                _logger.LogInformation("Library {Library}: JellyGames image/info source names changed to Shoalcade (ticks and order kept)", f.Name);
            }
        }

        return n;
    }

    private int RenameServerSettings()
    {
        var cfg = _config.Configuration;
        var n = 0;
        var list = cfg.MetadataOptions ?? [];
        for (var i = 0; i < list.Length; i++)
        {
            if (ProviderNameRename.TryRename<MetadataOptions>(list[i], out var renamed) && renamed is not null)
            {
                list[i] = renamed;
                n++;
            }
        }

        if (n > 0)
        {
            _config.SaveConfiguration();
        }

        return n;
    }

    private async Task<int> RetagAsync(CancellationToken cancellationToken)
    {
        var items = _library.GetItemList(new InternalItemsQuery
        {
            IncludeItemTypes = [BaseItemKind.Book],
            Recursive = true,
            Tags = [LegacyNames.Tag],
        });
        var n = 0;
        foreach (var item in items)
        {
            if (!item.Tags.Contains(LegacyNames.Tag, StringComparer.Ordinal))
            {
                continue;
            }

            item.Tags = item.Tags.Select(t => string.Equals(t, LegacyNames.Tag, StringComparison.Ordinal) ? ScannedRom.ScannerTag : t)
                .Distinct(StringComparer.Ordinal).ToArray();
            await item.UpdateToRepositoryAsync(ItemUpdateType.MetadataEdit, cancellationToken).ConfigureAwait(false);
            n++;
        }

        return n;
    }
}
