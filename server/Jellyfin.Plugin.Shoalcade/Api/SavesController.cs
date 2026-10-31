using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Database.Implementations.Entities;
using Jellyfin.Plugin.Shoalcade.Consoles;
using Jellyfin.Plugin.Shoalcade.Library;
using Jellyfin.Plugin.Shoalcade.Saves;
using Jellyfin.Plugin.Shoalcade.States;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Net.Http.Headers;

namespace Jellyfin.Plugin.Shoalcade.Api;

/// <summary>
/// One save per game per user, shared by the app and browser (D35; DS too since D91), with full history.
/// <list type="bullet">
/// <item><c>channel</c>: only <c>main</c> exists now. <c>browser</c> (the old separate DS list, D52) is still accepted
/// and means the same one list, so app 0.5 keeps working; anything else is 400 <c>bad_channel</c>.</item>
/// <item>Uploads say which version they replace (<c>base</c>). If the server has moved on, the answer is 409 and the
/// person chooses; the losing copy can be kept in history (<c>mode=history</c>).</item>
/// <item>Nothing is ever overwritten: every change is a new version; restore copies an old version forward.</item>
/// <item>Saves of a game that has left the library ("removed") can still be listed, downloaded, pinned, deleted and moved
/// to another game, so a renamed ROM never strands a save. A removed game with only browser snapshots is listed too, and
/// its snapshots can be moved on their own (F8).</item>
/// </list>
/// </summary>
[Route("shoalcade")]
public class SavesController : ShoalcadeControllerBase
{
    /// <summary>Initializes a new instance of the <see cref="SavesController"/> class.</summary>
    /// <param name="library">ROM library.</param>
    /// <param name="services">Stores.</param>
    public SavesController(RomLibrary library, ShoalcadeServices services)
        : base(library, services)
    {
    }

    private const string DeleteAllChanged = "Your saves for this game changed since you opened the page, so nothing was deleted. Check the list and try again.";

    private static readonly System.Collections.Concurrent.ConcurrentDictionary<Guid, byte> Exporting = new();
    private static readonly SemaphoreSlim ExportSlots = new(2, 2);

    private enum Access
    {
        /// <summary>A ROM the user can see.</summary>
        Game,

        /// <summary>A game no longer in the library, with a save of the user's.</summary>
        Removed,
    }

    /// <summary>Every save the user has, including games that left the library, plus storage used.</summary>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>The list.</returns>
    [HttpGet("saves")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    public async Task<ActionResult<SaveListDto>> ListSaves(CancellationToken cancellationToken)
    {
        if (RequireUser(out var user) is ActionResult fail)
        {
            return fail;
        }

        var list = new List<SaveListEntryDto>();
        foreach (var s in await Services.Saves.ListAsync(user.Id, cancellationToken).ConfigureAwait(false))
        {
            var rom = Library.GetRom(s.ItemId, user);
            string status;
            if (rom is not null)
            {
                status = "ok";
            }
            else if (!Library.Exists(s.ItemId))
            {
                status = "removed";
            }
            else
            {
                continue; // Exists but now hidden from this user: don't reveal it.
            }

            list.Add(new SaveListEntryDto(
                s.ItemId.ToString("N"),
                SaveHistoryDto.MainChannel,
                rom?.Name ?? s.GameName,
                rom?.Console.Key ?? s.Console,
                status,
                SaveVersionDto.From(s.Current, s.Current?.Version),
                s.VersionCount,
                s.TotalBytes,
                rom?.ImageTag,
                Services.States.List(user.Id, s.ItemId).Count));
        }

        // F8: removed games with snapshots but no save. In-library games' snapshots live in the player; hidden ones stay hidden.
        var listed = list.Select(e => e.ItemId).ToHashSet(StringComparer.Ordinal);
        foreach (var item in Services.States.Items(user.Id))
        {
            if (listed.Contains(item.ToString("N")) || Library.Exists(item))
            {
                continue;
            }

            var (name, console, count) = await RemovedGameAsync(user.Id, item, cancellationToken).ConfigureAwait(false);
            list.Add(new SaveListEntryDto(item.ToString("N"), SaveHistoryDto.MainChannel, name, console, "removed", null, 0, 0, null, count));
        }

        var c = ShoalcadeServices.Config;
        return new SaveListDto(
            list.OrderBy(e => e.GameName, StringComparer.OrdinalIgnoreCase).ToList(),
            Services.Saves.UsedBytes(user.Id),
            Math.Max(0, c.SaveQuotaMegabytes) * 1024L * 1024L,
            Services.States.UsedBytes(user.Id),
            Math.Max(0, c.SnapshotQuotaMegabytes) * 1024L * 1024L);
    }

    /// <summary>
    /// Every save in use, as one zip (a backup: <c>console/Game name.ext</c>, each file's time = when it was saved).
    /// Games no longer in the library are included; games hidden from this user are not. Only the save in use per game,
    /// not the older versions.
    /// </summary>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>The zip.</returns>
    [HttpGet("saves/export")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    public async Task<ActionResult> Export(CancellationToken cancellationToken)
    {
        if (RequireUser(out var user) is ActionResult fail)
        {
            return fail;
        }

        // One export per user at a time, and a few at once on the server: each builds a whole zip before sending.
        if (!Exporting.TryAdd(user.Id, 0))
        {
            return Fail(StatusCodes.Status429TooManyRequests, "busy", "Your saves are already being downloaded. Wait for that to finish.");
        }

        if (!await ExportSlots.WaitAsync(0, cancellationToken).ConfigureAwait(false))
        {
            Exporting.TryRemove(user.Id, out _);
            return Fail(StatusCodes.Status429TooManyRequests, "busy", "The server is busy making other downloads. Try again in a minute.");
        }

        try
        {
            return await ExportAsync(user, cancellationToken).ConfigureAwait(false);
        }
        finally
        {
            ExportSlots.Release();
            Exporting.TryRemove(user.Id, out _);
        }
    }

    private async Task<ActionResult> ExportAsync(User user, CancellationToken cancellationToken)
    {
        // In the plugin's own data folder (Jellyfin's account only), not the shared temp folder; only this account can
        // read it on Linux. Deleted when the download is closed.
        var tmpDir = Path.Combine(Services.DataRoot, "export-tmp");
        Directory.CreateDirectory(tmpDir);
        var tmp = Path.Combine(tmpDir, Guid.NewGuid().ToString("N") + ".zip");
        var opts = new FileStreamOptions
        {
            Mode = FileMode.CreateNew,
            Access = FileAccess.ReadWrite,
            Share = FileShare.None,
            BufferSize = 81920,
            Options = FileOptions.DeleteOnClose | FileOptions.Asynchronous,
        };
        if (!OperatingSystem.IsWindows())
        {
            opts.UnixCreateMode = UnixFileMode.UserRead | UnixFileMode.UserWrite;
        }

        var fs = new FileStream(tmp, opts);
        try
        {
            using (var zip = new ZipArchive(fs, ZipArchiveMode.Create, true))
            {
                var names = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
                foreach (var s in await Services.Saves.ListAsync(user.Id, cancellationToken).ConfigureAwait(false))
                {
                    var rom = Library.GetRom(s.ItemId, user);
                    if (s.Current is null || (rom is null && Library.Exists(s.ItemId)))
                    {
                        continue; // no save in use, or hidden from this user (never revealed)
                    }

                    var opened = await Services.Saves.OpenAsync(user.Id, s.ItemId, null, cancellationToken).ConfigureAwait(false);
                    if (opened is not { } o)
                    {
                        continue;
                    }

                    using (o.Data)
                    {
                        var folder = SafeFileName(rom?.Console.Key ?? s.Console ?? "unknown");
                        var stem = folder + "/" + SafeFileName(rom?.Name ?? s.GameName ?? s.ItemId.ToString("N"));
                        var ext = Path.GetExtension(o.Version.File);
                        var name = stem + ext;
                        for (var n = 2; !names.Add(name); n++)
                        {
                            name = stem + " (" + n.ToString(CultureInfo.InvariantCulture) + ")" + ext;
                        }

                        var entry = zip.CreateEntry(name, CompressionLevel.Fastest);
                        var t = o.Version.Created;
                        entry.LastWriteTime = t.Year is >= 1980 and <= 2107 ? t : DateTimeOffset.UtcNow; // zip times only go 1980-2107
                        var es = await entry.OpenAsync(cancellationToken).ConfigureAwait(false);
                        await using (es.ConfigureAwait(false))
                        {
                            await o.Data.CopyToAsync(es, cancellationToken).ConfigureAwait(false);
                        }
                    }
                }
            }

            fs.Position = 0;
        }
        catch
        {
            await fs.DisposeAsync().ConfigureAwait(false);
            throw;
        }

        Response.Headers.CacheControl = "no-store";
        return File(fs, "application/zip", "Shoalcade saves " + DateTime.UtcNow.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture) + ".zip");
    }

    /// <summary>Downloads the current save (raw bytes). 404 with error "no_save" if there is none.</summary>
    /// <param name="id">Item id.</param>
    /// <param name="channel">Old clients only: main (default) or browser, both the one list (D91).</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>The save.</returns>
    [HttpGet("save/{id:guid}")]
    [HttpHead("save/{id:guid}")]
    [Produces("application/octet-stream", "application/json")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public Task<ActionResult> GetCurrent([FromRoute] Guid id, [FromQuery] string? channel, CancellationToken cancellationToken)
        => Download(id, channel, null, false, cancellationToken);

    /// <summary>Downloads one version of a save (as a file).</summary>
    /// <param name="id">Item id.</param>
    /// <param name="version">Version number.</param>
    /// <param name="channel">Channel.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>The file.</returns>
    [HttpGet("save/{id:guid}/versions/{version:int}")]
    [Produces("application/octet-stream", "application/json")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public Task<ActionResult> GetVersion([FromRoute] Guid id, [FromRoute] int version, [FromQuery] string? channel, CancellationToken cancellationToken)
        => Download(id, channel, version, true, cancellationToken);

    /// <summary>The save's history: current version and every kept version, newest first.</summary>
    /// <param name="id">Item id.</param>
    /// <param name="channel">Channel.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>History.</returns>
    [HttpGet("save/{id:guid}/history")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<ActionResult<SaveHistoryDto>> GetHistory([FromRoute] Guid id, [FromQuery] string? channel, CancellationToken cancellationToken)
    {
        var (ctx, fail) = await ResolveAsync(id, channel, false, cancellationToken).ConfigureAwait(false);
        if (fail is not null)
        {
            return fail;
        }

        var h = await Services.Saves.GetHistoryAsync(ctx.User.Id, id, cancellationToken).ConfigureAwait(false);
        return SaveHistoryDto.From(h, id);
    }

    /// <summary>
    /// Uploads a save (raw bytes in the body).
    /// <c>base</c> = the version this device last saw (0 = none) is required unless <c>mode</c> is force/history.
    /// </summary>
    /// <param name="id">Item id.</param>
    /// <param name="channel">Channel.</param>
    /// <param name="baseVersion">Version the client last saw.</param>
    /// <param name="mode">normal (default), force (the person chose this copy) or history (keep as an old version only).</param>
    /// <param name="source">browser, app or upload (by hand).</param>
    /// <param name="allowBlank">Accept a blank save (all one byte).</param>
    /// <param name="afterSnapshot">The game saved this after a snapshot was loaded (D90); shown on the version.</param>
    /// <param name="cheats">The game saved this while cheats were on (D143); shown on the version.</param>
    /// <param name="expect">With <c>mode=force</c>: the version the person was shown as Jellyfin's save (0 = none). If the
    /// save in use is no longer that one, nothing changes and the answer is 409, so they can be asked again (L2).</param>
    /// <param name="expectSha">With <c>expect</c>: that version's SHA-256, checked too.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>201 created, 200 unchanged, 409 conflict, 413 too big, 422 refused.</returns>
    [HttpPut("save/{id:guid}")]
    [Consumes("application/octet-stream")]
    [ProducesResponseType(StatusCodes.Status201Created)]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status409Conflict)]
    [ProducesResponseType(StatusCodes.Status413PayloadTooLarge)]
    [ProducesResponseType(StatusCodes.Status422UnprocessableEntity)]
    public async Task<ActionResult> PutSave(
        [FromRoute] Guid id,
        [FromQuery] string? channel,
        [FromQuery(Name = "base")] int? baseVersion,
        [FromQuery] string? mode,
        [FromQuery] string? source,
        [FromQuery] bool allowBlank,
        [FromQuery] bool afterSnapshot,
        [FromQuery] bool cheats,
        [FromQuery] int? expect,
        [FromQuery] string? expectSha,
        CancellationToken cancellationToken)
    {
        var (ctx, fail) = await ResolveAsync(id, channel, true, cancellationToken).ConfigureAwait(false);
        if (fail is not null)
        {
            return fail;
        }

        PutMode? putMode = mode switch
        {
            null or "" or "normal" => PutMode.Normal,
            "force" => PutMode.Force,
            "history" => PutMode.HistoryOnly,
            _ => null,
        };
        if (putMode is null)
        {
            return Fail(StatusCodes.Status400BadRequest, "bad_mode", "mode must be normal, force or history.");
        }

        if ((expect is not null || expectSha is not null) && (putMode != PutMode.Force || expect is null or < 0 || (expectSha is not null && !SaveStore.IsSha256Hex(expectSha))))
        {
            return Fail(StatusCodes.Status400BadRequest, "bad_expect", "expect needs mode=force and a version (0 = none); expectSha must be a SHA-256.");
        }

        string src;
        switch (source)
        {
            case null or "":
                src = "upload";
                break;
            case "browser" or "app" or "upload":
                src = source;
                break;
            default:
                return Fail(StatusCodes.Status400BadRequest, "bad_source", "source must be browser, app or upload.");
        }

        var (uploadSlot, busy) = await BeginUploadAsync(ctx.User.Id, ctx.Console!.MaxSaveBytes, cancellationToken).ConfigureAwait(false);
        if (busy is not null)
        {
            return busy;
        }

        using var slotHandle = uploadSlot;
        var (data, bodyFail) = await ReadBodyViaFileAsync(ctx.Console!.MaxSaveBytes, cancellationToken).ConfigureAwait(false);
        if (bodyFail is not null)
        {
            return bodyFail;
        }

        var shown = expect is null ? null : new SaveExpectation(expect.Value, expectSha);
        var r = await Services.Saves.PutAsync(ctx.User.Id, id, ctx.Console!, ctx.Rom?.Name, data!, putMode.Value, baseVersion, src, DeviceLabel, allowBlank, afterSnapshot, cancellationToken, shown, cheats).ConfigureAwait(false);
        return ToResult(r);
    }

    /// <summary>Makes an old version current again (the current save goes into history first; nothing is lost).</summary>
    /// <param name="id">Item id.</param>
    /// <param name="version">Version to restore.</param>
    /// <param name="channel">Channel.</param>
    /// <param name="baseVersion">Version the page last saw (optional safety check).</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>Result.</returns>
    [HttpPost("save/{id:guid}/versions/{version:int}/restore")]
    [ProducesResponseType(StatusCodes.Status201Created)]
    [ProducesResponseType(StatusCodes.Status409Conflict)]
    public async Task<ActionResult> Restore([FromRoute] Guid id, [FromRoute] int version, [FromQuery] string? channel, [FromQuery(Name = "base")] int? baseVersion, CancellationToken cancellationToken)
    {
        var (ctx, fail) = await ResolveAsync(id, channel, true, cancellationToken).ConfigureAwait(false);
        if (fail is not null)
        {
            return fail;
        }

        var r = await Services.Saves.RestoreAsync(ctx.User.Id, id, ctx.Console!, ctx.Rom?.Name, version, baseVersion, DeviceLabel, cancellationToken).ConfigureAwait(false);
        return ToResult(r);
    }

    /// <summary>Pins/unpins a version or changes its label.</summary>
    /// <param name="id">Item id.</param>
    /// <param name="version">Version.</param>
    /// <param name="body">Changes.</param>
    /// <param name="channel">Channel.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>Result.</returns>
    [HttpPatch("save/{id:guid}/versions/{version:int}")]
    [Consumes("application/json")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    public async Task<ActionResult> UpdateVersion([FromRoute] Guid id, [FromRoute] int version, [FromBody] VersionUpdateDto body, [FromQuery] string? channel, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(body);
        var (ctx, fail) = await ResolveAsync(id, channel, false, cancellationToken).ConfigureAwait(false);
        if (fail is not null)
        {
            return fail;
        }

        var r = await Services.Saves.UpdateVersionAsync(ctx.User.Id, id, version, body.Pinned, body.Label, cancellationToken).ConfigureAwait(false);
        return ToResult(r, StatusCodes.Status200OK);
    }

    /// <summary>Deletes an old version (not the current one, not a pinned one).</summary>
    /// <param name="id">Item id.</param>
    /// <param name="version">Version.</param>
    /// <param name="channel">Channel.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>Result.</returns>
    [HttpDelete("save/{id:guid}/versions/{version:int}")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    public async Task<ActionResult> DeleteVersion([FromRoute] Guid id, [FromRoute] int version, [FromQuery] string? channel, CancellationToken cancellationToken)
    {
        var (ctx, fail) = await ResolveAsync(id, channel, false, cancellationToken).ConfigureAwait(false);
        if (fail is not null)
        {
            return fail;
        }

        var r = await Services.Saves.DeleteVersionAsync(ctx.User.Id, id, version, cancellationToken).ConfigureAwait(false);
        return ToResult(r, StatusCodes.Status200OK);
    }

    /// <summary>"Start fresh": no current save afterwards (the game starts new), but all versions stay in history.</summary>
    /// <param name="id">Item id.</param>
    /// <param name="channel">Channel.</param>
    /// <param name="baseVersion">Version the page last saw.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>Result.</returns>
    [HttpPost("save/{id:guid}/clear")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    public async Task<ActionResult> Clear([FromRoute] Guid id, [FromQuery] string? channel, [FromQuery(Name = "base")] int? baseVersion, CancellationToken cancellationToken)
    {
        var (ctx, fail) = await ResolveAsync(id, channel, true, cancellationToken).ConfigureAwait(false);
        if (fail is not null)
        {
            return fail;
        }

        var r = await Services.Saves.ClearCurrentAsync(ctx.User.Id, id, baseVersion, cancellationToken).ConfigureAwait(false);
        return ToResult(r, StatusCodes.Status200OK);
    }

    /// <summary>
    /// Deletes every save version (pinned and in use too) and every snapshot of a game that is no longer in the library.
    /// Refused for a game still in the library (422 <c>in_library</c>: delete old versions one at a time there). The page
    /// sends the counts it showed; if either changed since, nothing is deleted (409 <c>conflict</c>).
    /// </summary>
    /// <param name="id">Item id.</param>
    /// <param name="versions">Save versions the person was shown.</param>
    /// <param name="snapshots">Snapshots the person was shown.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>How many were deleted.</returns>
    [HttpPost("save/{id:guid}/delete-all")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    public async Task<ActionResult<SaveDeleteAllDto>> DeleteAll([FromRoute] Guid id, [FromQuery] int? versions, [FromQuery] int? snapshots, CancellationToken cancellationToken)
    {
        var (ctx, fail) = await ResolveAsync(id, null, false, cancellationToken).ConfigureAwait(false);
        if (fail is not null)
        {
            return fail;
        }

        if (ctx.Access == Access.Game)
        {
            return Fail(StatusCodes.Status422UnprocessableEntity, "in_library", "This game is in your library. Delete old versions one at a time instead.");
        }

        if (versions is not int v || snapshots is not int n || v < 0 || n < 0)
        {
            return Fail(StatusCodes.Status400BadRequest, "bad_expect", "Say how many saves and snapshots you were shown.");
        }

        if (Services.States.List(ctx.User.Id, id).Count != n)
        {
            return Fail(StatusCodes.Status409Conflict, "conflict", DeleteAllChanged);
        }

        // A hidden copy first, kept 7 days (D168): if it can't be made, nothing is deleted.
        string kept;
        try
        {
            var name = (await Services.Saves.GetHistoryAsync(ctx.User.Id, id, cancellationToken).ConfigureAwait(false)).GameName;
            kept = Services.Deleted.Keep(ctx.User.Id, id, name, Services.Saves.GameDir(ctx.User.Id, id), Services.States.Dir(ctx.User.Id, id));
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return Fail(StatusCodes.Status500InternalServerError, "keep_failed", "The server couldn't keep a safety copy of these saves, so nothing was deleted. Try again later.");
        }

        var r = await Services.Saves.DeleteAllAsync(ctx.User.Id, id, v, cancellationToken).ConfigureAwait(false);
        if (r.Status == SaveWriteStatus.Conflict)
        {
            Services.Deleted.Discard(kept);
            return Fail(StatusCodes.Status409Conflict, "conflict", DeleteAllChanged);
        }

        var gone = await Services.States.DeleteAllAsync(ctx.User.Id, id, cancellationToken).ConfigureAwait(false);
        return Ok(new SaveDeleteAllDto(r.Deleted, gone));
    }

    /// <summary>
    /// Moves all of a save's versions to another game (e.g. the ROM was renamed and got a new id).
    /// Only to a game with the same save format: same console, or Game Boy and Game Boy Color (422 <c>console</c> otherwise).
    /// If the target has no save, the moved current save becomes its current save; otherwise it goes into history.
    /// </summary>
    /// <param name="id">Item the save is under now.</param>
    /// <param name="to">Target item.</param>
    /// <param name="channel">Old clients only (see the class notes).</param>
    /// <param name="toChannel">Old clients only (see the class notes).</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>Result for the target.</returns>
    [HttpPost("save/{id:guid}/move")]
    [ProducesResponseType(StatusCodes.Status201Created)]
    public async Task<ActionResult> Move([FromRoute] Guid id, [FromQuery] Guid to, [FromQuery] string? channel, [FromQuery] string? toChannel, CancellationToken cancellationToken)
    {
        var (src, fail) = await ResolveAsync(id, channel, false, cancellationToken).ConfigureAwait(false);
        if (fail is not null)
        {
            return fail;
        }

        var (dst, fail2) = await ResolveAsync(to, toChannel, true, cancellationToken).ConfigureAwait(false);
        if (fail2 is not null)
        {
            return fail2;
        }

        if (src.Console is null)
        {
            return Fail(StatusCodes.Status422UnprocessableEntity, "console_unknown", "Can't tell which console this save is for, so it can't be moved. Download it instead.");
        }

        if (src.Access == Access.Removed && (await Services.Saves.GetHistoryAsync(src.User.Id, id, cancellationToken).ConfigureAwait(false)).Versions.Count == 0)
        {
            return await MoveSnapshotsOnlyAsync(src, id, to, dst, cancellationToken).ConfigureAwait(false);
        }

        var r = await Services.Saves.MoveAsync(src.User.Id, id, src.Console, to, dst.Console!, dst.Rom?.Name, cancellationToken).ConfigureAwait(false);
        if (r.Status == SaveWriteStatus.Created)
        {
            // B9: the game's browser snapshots follow it (copied, checked, then removed; a used slot is never overwritten).
            var s = await Services.States.MoveAsync(src.User.Id, id, to, cancellationToken, dst.Console!.Key, dst.Rom?.Name).ConfigureAwait(false);
            r = r with { Warnings = [.. r.Warnings, .. SnapshotMoveNotes(s), .. CheatMoveNotes(await Services.Cheats.MoveAsync(src.User.Id, id, to, cancellationToken).ConfigureAwait(false))] };
        }

        return ToResult(r);
    }

    /// <summary>Plain-English notes about a snapshot move, for the move's warnings (empty if there were no snapshots).</summary>
    /// <param name="s">The move result.</param>
    /// <returns>Notes.</returns>
    internal static IEnumerable<string> SnapshotMoveNotes(StateMoveResult s)
    {
        static string Names(IReadOnlyList<string> slots) =>
            string.Join(", ", slots.Select(x => x == "auto" ? "Automatic" : "Slot " + x));

        if (s.Moved.Count > 0)
        {
            yield return (s.Moved.Count == 1 ? "1 snapshot" : s.Moved.Count.ToString(CultureInfo.InvariantCulture) + " snapshots") + " moved too (" + Names(s.Moved) + ").";
        }

        if (s.Kept.Count > 0)
        {
            yield return "Snapshot " + Names(s.Kept) + " stayed with the old game, because this game already has a snapshot there.";
        }

        if (s.Damaged.Count > 0)
        {
            yield return "Snapshot " + Names(s.Damaged) + " stayed with the old game, because it couldn't be checked.";
        }
    }

    /// <summary>Plain-English notes about cheats moved with a save (D143).</summary>
    /// <param name="r">How many moved, and how many stayed (the target game's list was full).</param>
    /// <returns>Notes.</returns>
    internal static IEnumerable<string> CheatMoveNotes((int Moved, int Kept) r)
    {
        if (r.Moved > 0)
        {
            yield return (r.Moved == 1 ? "1 cheat" : r.Moved.ToString(CultureInfo.InvariantCulture) + " cheats") + " moved too.";
        }

        if (r.Kept > 0)
        {
            yield return (r.Kept == 1 ? "1 cheat" : r.Kept.ToString(CultureInfo.InvariantCulture) + " cheats") + " stayed with the old game, because this game's cheat list is full.";
        }
    }

    /// <summary>
    /// F8: a removed game with snapshots but no save. Same rules as a save move (<see cref="SaveStore.MoveRefusal"/>); the
    /// target's save is not touched. 422 <c>kept</c> if no snapshot could move (every slot used at the target, or damaged).
    /// </summary>
    private async Task<ActionResult> MoveSnapshotsOnlyAsync(SaveContext src, Guid id, Guid to, SaveContext dst, CancellationToken cancellationToken)
    {
        if (SaveStore.MoveRefusal(id, src.Console!, to, dst.Console!) is { } refused)
        {
            return ToResult(refused);
        }

        var s = await Services.States.MoveAsync(src.User.Id, id, to, cancellationToken, dst.Console!.Key, dst.Rom?.Name).ConfigureAwait(false);
        var notes = SnapshotMoveNotes(s).Concat(CheatMoveNotes(await Services.Cheats.MoveAsync(src.User.Id, id, to, cancellationToken).ConfigureAwait(false))).ToList();
        var current = (await Services.Saves.GetHistoryAsync(src.User.Id, to, cancellationToken).ConfigureAwait(false)).CurrentVersion();
        return ToResult(s.Moved.Count > 0
            ? new SaveWriteResult(SaveWriteStatus.Created, null, current, null, null, notes)
            : new SaveWriteResult(SaveWriteStatus.Rejected, null, current, string.Join(" ", notes), "kept", []));
    }

    /// <summary>Stored name and console of a removed game: from its save history, else from its snapshots (F8).</summary>
    private async Task<(string? Name, string? Console, int Snapshots)> RemovedGameAsync(Guid user, Guid id, CancellationToken cancellationToken)
    {
        var h = await Services.Saves.GetHistoryAsync(user, id, cancellationToken).ConfigureAwait(false);
        var states = Services.States.List(user, id);
        return (
            h.GameName ?? states.Select(x => x.GameName).FirstOrDefault(x => x is not null),
            h.Console ?? states.Select(x => x.Console).FirstOrDefault(x => x is not null),
            states.Count);
    }

    /// <summary>"main", or "browser" from app 0.5 (the old DS browser list, merged into the one list by D91).</summary>
    private static bool IsKnownChannel(string? channel) => channel is null or "" or SaveHistoryDto.MainChannel or "browser";

    private static string SafeFileName(string? name)
    {
        var sb = new StringBuilder();
        foreach (var ch in name ?? "save")
        {
            sb.Append(char.IsLetterOrDigit(ch) || ch is ' ' or '-' or '_' or '(' or ')' or '.' ? ch : '_');
        }

        var s = sb.ToString().Trim().Trim('.');
        return s.Length == 0 ? "save" : (s.Length > 80 ? s[..80] : s);
    }

    private async Task<ActionResult> Download(Guid id, string? channel, int? version, bool attachment, CancellationToken cancellationToken)
    {
        var (ctx, fail) = await ResolveAsync(id, channel, false, cancellationToken).ConfigureAwait(false);
        if (fail is not null)
        {
            return fail;
        }

        var opened = await Services.Saves.OpenAsync(ctx.User.Id, id, version, cancellationToken).ConfigureAwait(false);
        if (opened is not { } o)
        {
            return version is null
                ? Fail(StatusCodes.Status404NotFound, "no_save", "There's no save for this game yet.")
                : Fail(StatusCodes.Status404NotFound, "missing", "That save version doesn't exist.");
        }

        var h = await Services.Saves.GetHistoryAsync(ctx.User.Id, id, cancellationToken).ConfigureAwait(false);
        Response.Headers["X-Shoalcade-Save-Version"] = o.Version.Version.ToString(CultureInfo.InvariantCulture);
        Response.Headers["X-Shoalcade-Save-Current"] = (h.Current ?? 0).ToString(CultureInfo.InvariantCulture);
        Response.Headers["X-Shoalcade-Save-Sha256"] = o.Version.Sha256;
        Response.Headers.CacheControl = "no-store";
        var etag = new EntityTagHeaderValue("\"v" + o.Version.Version.ToString(CultureInfo.InvariantCulture) + "-" + o.Version.Sha256[..16] + "\"");
        string? fileName = null;
        if (attachment)
        {
            var name = ctx.Rom?.Name ?? h.GameName;
            fileName = SafeFileName(name) + " v" + o.Version.Version.ToString(CultureInfo.InvariantCulture) + Path.GetExtension(o.Version.File);
        }

        return File(o.Data, "application/octet-stream", fileName, new DateTimeOffset(o.Version.Created.UtcDateTime, TimeSpan.Zero), etag, false);
    }

    private ObjectResult ToResult(SaveWriteResult r, int createdStatus = StatusCodes.Status201Created)
    {
        var current = SaveVersionDto.From(r.Current, r.Current?.Version);
        return r.Status switch
        {
            SaveWriteStatus.Created => StatusCode(createdStatus, new SaveWriteDto("created", SaveVersionDto.From(r.Version, r.Current?.Version), current, r.Warnings)),
            SaveWriteStatus.Unchanged => Ok(new SaveWriteDto("unchanged", SaveVersionDto.From(r.Version, r.Current?.Version), current, r.Warnings)),
            SaveWriteStatus.Conflict => Conflict(new ConflictError("conflict", r.Error ?? "Conflict.", current)),
            SaveWriteStatus.NotFound => Fail(StatusCodes.Status404NotFound, r.ErrorCode ?? "missing", r.Error ?? "Not found."),
            _ => Fail(r.ErrorCode == "quota" ? StatusCodes.Status507InsufficientStorage : StatusCodes.Status422UnprocessableEntity, r.ErrorCode ?? "refused", r.Error ?? "Refused."),
        };
    }

    /// <summary>
    /// Works out who and what a request is about. A game must be a ROM the user can see; a game that has left the library
    /// is allowed only for reading/tidying the user's own save (<paramref name="needGame"/> = false), and only if they have one.
    /// </summary>
    private async Task<(SaveContext Ctx, ActionResult? Fail)> ResolveAsync(Guid id, string? channel, bool needGame, CancellationToken cancellationToken)
    {
        if (RequireUser(out var user) is ActionResult fail)
        {
            return (default!, fail);
        }

        if (!IsKnownChannel(channel))
        {
            return (default!, Fail(StatusCodes.Status400BadRequest, "bad_channel", "channel must be main or browser."));
        }

        var rom = Library.GetRom(id, user);
        if (rom is not null)
        {
            await Services.JellyEmu.SyncAsync(user.Id, rom.Id, rom.Console, rom.Name, cancellationToken).ConfigureAwait(false);
            return (new SaveContext(user, rom, rom.Console, Access.Game), null);
        }

        if (needGame || Library.Exists(id))
        {
            return (default!, GameNotFound());
        }

        // Game removed from the library: only the user's own existing save (or, F8, snapshots) can be reached.
        var h = await Services.Saves.GetHistoryAsync(user.Id, id, cancellationToken).ConfigureAwait(false);
        if (h.Versions.Count == 0 && Services.States.List(user.Id, id).Count == 0)
        {
            return (default!, GameNotFound());
        }

        // Unknown console (e.g. history.json was rebuilt from the save files): never guess. Moves are refused (L3).
        var console = h.Versions.Count > 0 ? h.Console : (await RemovedGameAsync(user.Id, id, cancellationToken).ConfigureAwait(false)).Console;
        return (new SaveContext(user, null, GameConsole.FromKey(console), Access.Removed), null);
    }

    private sealed record SaveContext(User User, RomItem? Rom, GameConsole? Console, Access Access);
}
