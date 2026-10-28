using System;
using System.Collections.Concurrent;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using MediaBrowser.Controller.Entities;
using MediaBrowser.Controller.Library;
using MediaBrowser.Controller.Providers;
using MediaBrowser.Model.Entities;
using MediaBrowser.Model.IO;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Shoalcade.Library;

/// <summary>
/// Admin game editor (D122): rename a game, give it a cover, or put it back to automatic. Changes go on Jellyfin's own
/// item, so the app, the browser and Jellyfin's pages all show them, and they are protected from scans:
/// <list type="bullet">
/// <item><b>Name:</b> Jellyfin's own Name lock (LockedFields), which every metadata refresh respects, including
/// "Replace all metadata". The lock shows in Jellyfin's metadata editor too.</item>
/// <item><b>Cover:</b> a file in the plugin's data folder (<see cref="GameEdits"/>), outside Jellyfin's metadata folder,
/// so "Replace existing images" doesn't delete it; our box art source never offers a picture for an edited game; and
/// <see cref="GameEditWatch"/> puts the cover back if anything else replaces or removes it.</item>
/// </list>
/// Nothing is ever written next to the ROMs. Only ROM items reach here (the API checks).
/// </summary>
public sealed class GameEditor
{
    /// <summary>Longest name accepted.</summary>
    public const int MaxNameLength = 200;

    private readonly ILibraryManager _library;
    private readonly IFileSystem _fileSystem;
    private readonly GameEdits _edits;
    private readonly ILogger<GameEditor> _logger;
    private readonly CoverWatchGuard _guard = new();
    private readonly ConcurrentDictionary<Guid, byte> _recheck = new();

    /// <summary>Initializes a new instance of the <see cref="GameEditor"/> class.</summary>
    /// <param name="library">Library manager.</param>
    /// <param name="fileSystem">File system (Jellyfin's file info for image paths).</param>
    /// <param name="edits">Edit records and covers.</param>
    /// <param name="logger">Logger.</param>
    public GameEditor(ILibraryManager library, IFileSystem fileSystem, GameEdits edits, ILogger<GameEditor> logger)
    {
        _library = library;
        _fileSystem = fileSystem;
        _edits = edits;
        _logger = logger;
    }

    /// <summary>Gets the edit records.</summary>
    public GameEdits Edits => _edits;

    /// <summary>
    /// Checks a new name: trimmed, 1 to 200 characters, no control characters and no invisible format characters
    /// (right-to-left override, zero-width space and the like, G5 dev 2 I2), which could make a name show as something else.
    /// </summary>
    /// <param name="name">Name as typed.</param>
    /// <param name="clean">The trimmed name.</param>
    /// <returns>Null if fine, else the reason.</returns>
    public static string? CheckName(string? name, out string clean)
    {
        clean = (name ?? string.Empty).Trim();
        if (clean.Length == 0)
        {
            return "Type a name.";
        }

        if (clean.Length > MaxNameLength)
        {
            return $"That name is too long ({MaxNameLength} characters at most).";
        }

        return clean.Any(c => char.IsControl(c) || CharUnicodeInfo.GetUnicodeCategory(c) == UnicodeCategory.Format)
            ? "That name has characters that can't be used."
            : null;
    }

    /// <summary>True if the item's Primary picture is the admin's cover file.</summary>
    /// <param name="item">Item.</param>
    /// <param name="cover">Cover entry.</param>
    /// <returns>Whether it is in place.</returns>
    public bool CoverInPlace(BaseItem item, GameEditCover cover)
    {
        ArgumentNullException.ThrowIfNull(item);
        var info = item.GetImageInfo(ImageType.Primary, 0);
        return info is not null && string.Equals(Path.GetFullPath(info.Path), _edits.LivePath(cover), StringComparison.OrdinalIgnoreCase) && File.Exists(info.Path);
    }

    /// <summary>Renames a game and locks the name (Jellyfin's Name lock) so scans keep it.</summary>
    /// <param name="item">The ROM item.</param>
    /// <param name="name">Checked name.</param>
    /// <param name="by">Admin's user id.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>A task.</returns>
    public async Task RenameAsync(BaseItem item, string name, Guid by, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(item);
        using (await _edits.Locks.AcquireAsync(Key(item), cancellationToken).ConfigureAwait(false))
        {
            var edit = _edits.Get(item.Id) ?? new GameEdit();
            if (!item.LockedFields.Contains(MetadataField.Name))
            {
                item.LockedFields = [.. item.LockedFields, MetadataField.Name];
                edit.NameLockAdded = true;
            }

            edit.Name = name;
            Stamp(edit, by);

            // Record first: if the item update fails, Reset still knows to remove the lock it may have added.
            await _edits.SaveAsync(item.Id, edit, cancellationToken).ConfigureAwait(false);
            item.Name = name;
            await item.UpdateToRepositoryAsync(ItemUpdateType.MetadataEdit, cancellationToken).ConfigureAwait(false);
            _logger.LogInformation("Game {Id} renamed by an admin (name locked against scans)", item.Id);
        }
    }

    /// <summary>Gives a game a cover (already checked by <see cref="CoverImage.Check"/>).</summary>
    /// <param name="item">The ROM item.</param>
    /// <param name="data">Picture bytes.</param>
    /// <param name="info">What it is.</param>
    /// <param name="source">"upload" or "libretro".</param>
    /// <param name="from">libretro name, if any.</param>
    /// <param name="by">Admin's user id.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>A task.</returns>
    public async Task SetCoverAsync(BaseItem item, byte[] data, CoverInfo info, string source, string? from, Guid by, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(item);
        using (await _edits.Locks.AcquireAsync(Key(item), cancellationToken).ConfigureAwait(false))
        {
            var edit = _edits.Get(item.Id) ?? new GameEdit();
            var cover = await _edits.WriteCoverAsync(item.Id, data, info, source, from, cancellationToken).ConfigureAwait(false);
            edit.Cover = cover;
            Stamp(edit, by);
            await _edits.SaveAsync(item.Id, edit, cancellationToken).ConfigureAwait(false);
            await PointAtCoverAsync(item, _edits.LivePath(cover), cancellationToken).ConfigureAwait(false);
            _edits.RemoveOldCovers(item.Id, cover.File);
            _logger.LogInformation("Game {Id} given a cover by an admin ({Source})", item.Id, source);
        }
    }

    /// <summary>
    /// Puts a game back to automatic: removes the Name lock Shoalcade added (never one set in Jellyfin's own editor) and
    /// the admin's cover, forgets the edit, then refreshes the game so its automatic name and box art come back.
    /// </summary>
    /// <param name="item">The ROM item.</param>
    /// <param name="refresh">Refresh options for the game (null = no refresh, tests).</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>A task.</returns>
    public async Task ResetAsync(BaseItem item, MetadataRefreshOptions? refresh, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(item);
        using (await _edits.Locks.AcquireAsync(Key(item), cancellationToken).ConfigureAwait(false))
        {
            var edit = _edits.Get(item.Id);
            if (edit is null)
            {
                return;
            }

            if (edit.NameLockAdded)
            {
                item.LockedFields = item.LockedFields.Where(f => f != MetadataField.Name).ToArray();
            }

            // Only the picture that is ours: anything in the covers folder.
            var ours = item.GetImages(ImageType.Primary)
                .Where(i => i.Path is not null && Path.GetFullPath(i.Path).StartsWith(_edits.CoversFolder + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase))
                .ToList();
            if (ours.Count > 0)
            {
                item.RemoveImages(ours);
            }

            await item.UpdateToRepositoryAsync(ItemUpdateType.MetadataEdit | ItemUpdateType.ImageUpdate, cancellationToken).ConfigureAwait(false);
            _edits.Delete(item.Id);
            _logger.LogInformation("Game {Id} put back to automatic by an admin", item.Id);
        }

        if (refresh is not null)
        {
            await item.RefreshMetadata(refresh, cancellationToken).ConfigureAwait(false);
        }
    }

    /// <summary>
    /// Puts the admin's cover back if something replaced or removed it (a scan with a local picture, "Replace existing
    /// images", another image source). Does nothing for games with no edited cover, or when it is already in place.
    /// </summary>
    /// <param name="id">Item id.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>True if the cover was put back.</returns>
    public async Task<bool> EnsureCoverAsync(Guid id, CancellationToken cancellationToken)
    {
        if (_edits.Get(id)?.Cover is null)
        {
            return false;
        }

        using (await _edits.Locks.AcquireAsync(id.ToString("N"), cancellationToken).ConfigureAwait(false))
        {
            var cover = _edits.Get(id)?.Cover;
            if (cover is null || _library.GetItemById(id) is not BaseItem item || RomLibrary.ToRom(item) is null || CoverInPlace(item, cover))
            {
                return false;
            }

            // Loop guard (G5 dev 2 L1): a few put-backs per game per window, then wait. A later change still gets its
            // cover back: one re-check runs when the window ends.
            var wait = _guard.Wait(id);
            if (wait > TimeSpan.Zero)
            {
                if (_guard.FirstTrip(id))
                {
                    _logger.LogWarning(
                        "The admin's cover on game {Id} was put back {Count} times in {Seconds} s and is still not in place; waiting before trying again. If this repeats, Jellyfin may be reporting the picture's path differently (Docker/symlinked data folder)",
                        id,
                        CoverWatchGuard.MaxPutBacks,
                        (int)CoverWatchGuard.Window.TotalSeconds);
                }

                RecheckLater(id, wait);
                return false;
            }

            var live = await _edits.EnsureLiveAsync(id, cover, cancellationToken).ConfigureAwait(false);
            if (live is null)
            {
                return false;
            }

            _guard.PutBack(id);
            await PointAtCoverAsync(item, live, cancellationToken).ConfigureAwait(false);
            _logger.LogInformation("Put the admin's cover back on game {Id} (it had been replaced or removed)", id);
            return true;
        }
    }

    private void RecheckLater(Guid id, TimeSpan wait)
    {
        if (!_recheck.TryAdd(id, 0))
        {
            return; // one re-check per game is enough
        }

        _ = Task.Run(async () =>
        {
            try
            {
                await Task.Delay(wait + TimeSpan.FromMilliseconds(100)).ConfigureAwait(false);
                _recheck.TryRemove(id, out _);
                await EnsureCoverAsync(id, CancellationToken.None).ConfigureAwait(false);
            }
            catch (Exception ex)
            {
                _recheck.TryRemove(id, out _);
                _logger.LogWarning(ex, "Could not put the admin's cover back on game {Id}", id);
            }
        });
    }

    private static string Key(BaseItem item) => item.Id.ToString("N");

    private static void Stamp(GameEdit edit, Guid by)
    {
        edit.Edited = DateTimeOffset.UtcNow;
        edit.By = by.ToString("N", CultureInfo.InvariantCulture);
    }

    private async Task PointAtCoverAsync(BaseItem item, string live, CancellationToken cancellationToken)
    {
        item.SetImagePath(ImageType.Primary, 0, _fileSystem.GetFileInfo(live));
        await item.UpdateToRepositoryAsync(ItemUpdateType.ImageUpdate, cancellationToken).ConfigureAwait(false);
    }
}

/// <summary>
/// Watches Jellyfin's item updates (every scan and refresh ends in one) and puts an admin's cover back on a game if it
/// was replaced or removed (D122). Only games with an edited cover are looked at; the work runs in the background.
/// </summary>
public sealed class GameEditWatch : IHostedService
{
    private readonly ILibraryManager _library;
    private readonly GameEditor _editor;
    private readonly ILogger<GameEditWatch> _logger;

    /// <summary>Initializes a new instance of the <see cref="GameEditWatch"/> class.</summary>
    /// <param name="library">Library manager.</param>
    /// <param name="editor">Game editor.</param>
    /// <param name="logger">Logger.</param>
    public GameEditWatch(ILibraryManager library, GameEditor editor, ILogger<GameEditWatch> logger)
    {
        _library = library;
        _editor = editor;
        _logger = logger;
    }

    /// <inheritdoc />
    public Task StartAsync(CancellationToken cancellationToken)
    {
        _library.ItemUpdated += OnItemChanged;
        _library.ItemAdded += OnItemChanged;
        return Task.CompletedTask;
    }

    /// <inheritdoc />
    public Task StopAsync(CancellationToken cancellationToken)
    {
        _library.ItemUpdated -= OnItemChanged;
        _library.ItemAdded -= OnItemChanged;
        return Task.CompletedTask;
    }

    private void OnItemChanged(object? sender, ItemChangeEventArgs e)
    {
        if (e?.Item is not Book book || !_editor.Edits.HasCover(book.Id))
        {
            return;
        }

        var id = book.Id;
        _ = Task.Run(async () =>
        {
            try
            {
                await _editor.EnsureCoverAsync(id, CancellationToken.None).ConfigureAwait(false);
            }
            catch (Exception ex)
            {
                _logger.LogWarning(ex, "Could not put the admin's cover back on game {Id}", id);
            }
        });
    }
}
