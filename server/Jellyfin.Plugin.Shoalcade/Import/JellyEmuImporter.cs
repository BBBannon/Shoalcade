using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.IO;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Plugin.Shoalcade.Consoles;
using Jellyfin.Plugin.Shoalcade.Saves;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Shoalcade.Import;

/// <summary>
/// Copies saves from JellyEMU into Shoalcade (D40). <b>Read-only:</b> JellyEMU's files are never moved, changed or deleted,
/// so reinstalling JellyEMU is always a working rollback.
/// JellyEMU keeps in-game saves at <c>{DataPath}/jellyemu-saves/{userId}/slot{N}/{itemId}.sav</c>; the app used slot 1,
/// and slot 9 for PSP save-folder zips (D27). While JellyEMU is still installed, a JellyEMU save that changes is imported
/// again as a new version, so the browser and app never split.
/// </summary>
public sealed class JellyEmuImporter
{
    /// <summary>Slot the app used for in-game saves.</summary>
    public const int MainSlot = 1;

    /// <summary>Slot the app used for PSP save-folder zips.</summary>
    public const int PspSlot = 9;

    private readonly string _root;
    private readonly SaveStore _store;
    private readonly ILogger _logger;
    private readonly Func<bool> _enabled;
    private readonly ConcurrentDictionary<string, (DateTime Modified, long Size)> _seen = new(StringComparer.Ordinal);

    /// <summary>Initializes a new instance of the <see cref="JellyEmuImporter"/> class.</summary>
    /// <param name="jellyEmuSavesRoot">JellyEMU's saves folder.</param>
    /// <param name="store">Our save store.</param>
    /// <param name="enabled">Whether importing is switched on.</param>
    /// <param name="logger">Logger.</param>
    public JellyEmuImporter(string jellyEmuSavesRoot, SaveStore store, Func<bool> enabled, ILogger logger)
    {
        _root = Path.GetFullPath(jellyEmuSavesRoot);
        _store = store;
        _enabled = enabled;
        _logger = logger;
    }

    /// <summary>Gets JellyEMU's saves folder.</summary>
    public string Root => _root;

    /// <summary>The JellyEMU slot holding the app's save for a console.</summary>
    /// <param name="console">Console.</param>
    /// <returns>Slot number.</returns>
    public static int SlotFor(GameConsole console) => console.Key == "psp" ? PspSlot : MainSlot;

    /// <summary>Imports this game's JellyEMU save if it is new or changed. Cheap when nothing changed.</summary>
    /// <param name="user">User id.</param>
    /// <param name="item">Item id.</param>
    /// <param name="console">Console.</param>
    /// <param name="gameName">Game name.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>A task.</returns>
    public async Task SyncAsync(Guid user, Guid item, GameConsole console, string? gameName, CancellationToken cancellationToken)
    {
        if (!_enabled() || !Directory.Exists(_root))
        {
            return;
        }

        foreach (var path in CandidatePaths(user, item, SlotFor(console)))
        {
            await ImportFileAsync(user, item, console, gameName, path, cancellationToken).ConfigureAwait(false);
        }
    }

    /// <summary>Finds every JellyEMU save file that could be imported: (user, item, path).</summary>
    /// <returns>Candidates.</returns>
    public IEnumerable<(Guid User, Guid Item, int Slot, string Path)> Discover()
    {
        if (!Directory.Exists(_root))
        {
            yield break;
        }

        foreach (var userDir in Directory.EnumerateDirectories(_root))
        {
            if (!Guid.TryParse(System.IO.Path.GetFileName(userDir), out var user))
            {
                continue;
            }

            foreach (var slot in new[] { MainSlot, PspSlot })
            {
                var slotDir = System.IO.Path.Combine(userDir, "slot" + slot.ToString(System.Globalization.CultureInfo.InvariantCulture));
                if (!Directory.Exists(slotDir))
                {
                    continue;
                }

                foreach (var file in Directory.EnumerateFiles(slotDir, "*.sav"))
                {
                    if (Guid.TryParse(System.IO.Path.GetFileNameWithoutExtension(file), out var item))
                    {
                        yield return (user, item, slot, file);
                    }
                }
            }
        }
    }

    /// <summary>Imports one JellyEMU file.</summary>
    /// <param name="user">User id.</param>
    /// <param name="item">Item id.</param>
    /// <param name="console">Console.</param>
    /// <param name="gameName">Game name.</param>
    /// <param name="path">JellyEMU file.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>The result, or null if skipped.</returns>
    public async Task<SaveWriteResult?> ImportFileAsync(Guid user, Guid item, GameConsole console, string? gameName, string path, CancellationToken cancellationToken)
    {
        var info = new FileInfo(path);
        if (!info.Exists)
        {
            return null;
        }

        var key = info.FullName;
        if (_seen.TryGetValue(key, out var last) && last.Modified == info.LastWriteTimeUtc && last.Size == info.Length)
        {
            return null;
        }

        if (info.Length > console.MaxSaveBytes)
        {
            _logger.LogInformation("Skipped JellyEMU save {File}: too big for {Console}", info.Name, console.Key);
            _seen[key] = (info.LastWriteTimeUtc, info.Length);
            return null;
        }

        byte[] bytes;
        try
        {
            // FileShare.ReadWrite: JellyEMU may be writing; we only ever read.
            var fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
            await using (fs.ConfigureAwait(false))
            {
                bytes = new byte[fs.Length];
                await fs.ReadExactlyAsync(bytes, cancellationToken).ConfigureAwait(false);
            }
        }
        catch (IOException ex)
        {
            _logger.LogWarning(ex, "Could not read JellyEMU save {File}; will try again later", info.Name);
            return null;
        }

        var rel = System.IO.Path.GetRelativePath(_root, info.FullName).Replace('\\', '/');
        var result = await _store.ImportAsync(user, item, console, gameName, bytes, rel, new DateTimeOffset(info.LastWriteTimeUtc, TimeSpan.Zero), cancellationToken).ConfigureAwait(false);
        // Remember the file so it isn't imported again. Not after a full quota: that is temporary, so the next sync tries it again.
        if (result.ErrorCode != "quota")
        {
            _seen[key] = (info.LastWriteTimeUtc, info.Length);
        }

        if (result.Status == SaveWriteStatus.Created)
        {
            _logger.LogInformation("Imported JellyEMU save for item {Item} as version {Version}", item.ToString("N"), result.Version?.Version);
        }

        return result;
    }

    private IEnumerable<string> CandidatePaths(Guid user, Guid item, int slot)
    {
        var slotName = "slot" + slot.ToString(System.Globalization.CultureInfo.InvariantCulture);
        var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (var u in new[] { user.ToString("N"), user.ToString("D") })
        {
            foreach (var i in new[] { item.ToString("N"), item.ToString("D") })
            {
                var p = System.IO.Path.Combine(_root, u, slotName, i + ".sav");
                if (seen.Add(p) && File.Exists(p))
                {
                    yield return p;
                }
            }
        }
    }
}
