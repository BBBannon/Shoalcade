using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Plugin.Shoalcade.Cheats;
using Jellyfin.Plugin.Shoalcade.Consoles;
using Jellyfin.Plugin.Shoalcade.Library;
using Jellyfin.Plugin.Shoalcade.PlayTime;
using Jellyfin.Plugin.Shoalcade.Scanner;
using Jellyfin.Plugin.Shoalcade.States;
using MediaBrowser.Controller.Library;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Shoalcade.Saves;

/// <summary>
/// D233: discs that used to be separate games and are now one disc set (D-8) bring their saves with them, by themselves.
/// Only into a disc set with no save yet, and only from old disc games that have left the library. Every old save is
/// moved (never deleted): the newest becomes the set's current save, the others go into its history. Snapshots,
/// cheats and play time follow, as with a Move by hand.
/// </summary>
public static class DiscSetCarry
{
    /// <summary>A disc set and the ids its discs had as separate games.</summary>
    /// <param name="Id">The disc set's item id.</param>
    /// <param name="Name">The disc set's name.</param>
    /// <param name="Console">The disc set's console.</param>
    /// <param name="OldIds">Item ids of its discs as separate games.</param>
    public sealed record Target(Guid Id, string Name, GameConsole Console, IReadOnlyList<Guid> OldIds);

    /// <summary>Carries one user's old disc saves into one disc set.</summary>
    /// <param name="saves">Save store.</param>
    /// <param name="states">Snapshot store (null: snapshots stay).</param>
    /// <param name="cheats">Cheat store (null: cheats stay).</param>
    /// <param name="playTime">Play time store (null: play time stays).</param>
    /// <param name="user">User id.</param>
    /// <param name="set">The disc set.</param>
    /// <param name="inLibrary">True if an item id is a game in the library now.</param>
    /// <param name="logger">Logger.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>How many old saves were moved.</returns>
    public static async Task<int> CarryAsync(
        SaveStore saves,
        StateStore? states,
        CheatStore? cheats,
        PlayTimeStore? playTime,
        Guid user,
        Target set,
        Func<Guid, bool> inLibrary,
        ILogger logger,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(saves);
        ArgumentNullException.ThrowIfNull(set);
        ArgumentNullException.ThrowIfNull(inLibrary);
        ArgumentNullException.ThrowIfNull(logger);
        if ((await saves.GetHistoryAsync(user, set.Id, cancellationToken).ConfigureAwait(false)).Versions.Count > 0)
        {
            return 0; // the set has its own save: anything left over is moved by hand (Saves page)
        }

        var found = new List<(Guid Id, GameConsole Console, DateTimeOffset Newest)>();
        foreach (var old in set.OldIds.Distinct())
        {
            if (old == set.Id || old == Guid.Empty || inLibrary(old) || !Directory.Exists(saves.GameDir(user, old)))
            {
                continue;
            }

            var h = await saves.GetHistoryAsync(user, old, cancellationToken).ConfigureAwait(false);
            if (h.Versions.Count == 0)
            {
                continue;
            }

            var console = GameConsole.FromKey(h.Console) ?? set.Console;
            var current = h.Versions.FirstOrDefault(v => v.Version == h.Current);
            found.Add((old, console, current?.Created ?? h.Versions.Max(v => v.Created)));
        }

        var moved = 0;
        foreach (var (old, console, _) in found.OrderByDescending(f => f.Newest))
        {
            var r = await saves.MoveAsync(user, old, console, set.Id, set.Console, set.Name, cancellationToken).ConfigureAwait(false);
            if (r.Status != SaveWriteStatus.Created)
            {
                logger.LogWarning("Disc set {Game}: an old disc's save was not carried over ({Reason}); it can still be moved on the Saves page", set.Name, r.Error);
                continue;
            }

            moved++;
            if (states is not null)
            {
                await states.MoveAsync(user, old, set.Id, cancellationToken, set.Console.Key, set.Name).ConfigureAwait(false);
            }

            if (cheats is not null)
            {
                await cheats.MoveAsync(user, old, set.Id, cancellationToken).ConfigureAwait(false);
            }

            if (playTime is not null)
            {
                await playTime.MoveAsync(user, old, set.Id, cancellationToken).ConfigureAwait(false);
            }
        }

        if (moved > 0)
        {
            logger.LogInformation("Disc set {Game}: carried over {Count} save(s) from its discs' old separate games", set.Name, moved);
        }

        return moved;
    }
}

/// <summary>After every library scan: disc sets pick up the saves of their discs' old separate games (D233).</summary>
public class CarrySavesToDiscSetsAfterScan : ILibraryPostScanTask
{
    private readonly ShoalcadeServices _services;
    private readonly RomLibrary _library;
    private readonly ILogger<CarrySavesToDiscSetsAfterScan> _logger;

    /// <summary>Initializes a new instance of the <see cref="CarrySavesToDiscSetsAfterScan"/> class.</summary>
    /// <param name="services">Shoalcade stores.</param>
    /// <param name="library">ROM library.</param>
    /// <param name="logger">Logger.</param>
    public CarrySavesToDiscSetsAfterScan(ShoalcadeServices services, RomLibrary library, ILogger<CarrySavesToDiscSetsAfterScan> logger)
    {
        _services = services;
        _library = library;
        _logger = logger;
    }

    /// <inheritdoc />
    public async Task Run(IProgress<double> progress, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(progress);
        try
        {
            var root = _services.Saves.Root;
            var users = Directory.Exists(root)
                ? Directory.EnumerateDirectories(root).Select(d => Guid.TryParseExact(Path.GetFileName(d), "N", out var g) ? g : Guid.Empty).Where(g => g != Guid.Empty).ToList()
                : [];
            foreach (var id in users)
            {
                if (_library.GetUser(id) is not { } user)
                {
                    continue;
                }

                foreach (var rom in _library.ListRoms(user).Where(r => r.Console.TakesDiscSets && r.Item.Tags.Contains(ScannedRom.MultiDiscTag, StringComparer.OrdinalIgnoreCase)))
                {
                    var discs = RomScanner.DiscSet(rom.Item.Path, rom.Console);
                    var set = new DiscSetCarry.Target(rom.Id, rom.Name, rom.Console, [.. discs.Select(_library.OldItemId)]);
                    await DiscSetCarry.CarryAsync(_services.Saves, _services.States, _services.Cheats, _services.PlayTime, id, set, _library.Exists, _logger, cancellationToken).ConfigureAwait(false);
                }
            }
        }
#pragma warning disable CA1031 // a carry-over problem must never fail the library scan; saves stay where they are
        catch (Exception ex) when (ex is not OperationCanceledException)
#pragma warning restore CA1031
        {
            _logger.LogWarning(ex, "Disc set save carry-over stopped; old saves stay where they are and can be moved on the Saves page");
        }

        progress.Report(100);
    }
}
