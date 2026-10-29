using System;
using System.Collections.Generic;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Plugin.Shoalcade.Library;
using Jellyfin.Plugin.Shoalcade.States;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;

namespace Jellyfin.Plugin.Shoalcade.Api;

/// <summary>
/// Browser snapshots (D37): slots 1–3 plus "auto" (taken when the player closes), per user per game.
/// Not shared with the app: different emulator builds can't load each other's snapshots.
/// Snapshots of a game that has left the library (F8) can still be listed, downloaded and deleted by their owner, never
/// stored; a game that exists but is hidden from the user stays "not found".
/// </summary>
[Route("shoalcade/state")]
public class StatesController : ShoalcadeControllerBase
{
    /// <summary>Initializes a new instance of the <see cref="StatesController"/> class.</summary>
    /// <param name="library">ROM library.</param>
    /// <param name="services">Stores.</param>
    public StatesController(RomLibrary library, ShoalcadeServices services)
        : base(library, services)
    {
    }

    /// <summary>Lists filled snapshot slots and whether snapshots are on for this console.</summary>
    /// <param name="id">Item id.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>The list.</returns>
    [HttpGet("{id:guid}")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    public async Task<ActionResult<StateListDto>> List([FromRoute] Guid id, CancellationToken cancellationToken)
    {
        if (Resolve(id, null, false, out var user, out var rom) is ActionResult fail)
        {
            return fail;
        }

        // A removed game can't be played, so new snapshots are "off" for it.
        return new StateListDto(Services.States.List(user.Id, id), rom is not null && await SnapshotsEnabledAsync(user, rom, cancellationToken).ConfigureAwait(false));
    }

    /// <summary>Downloads a snapshot.</summary>
    /// <param name="id">Item id.</param>
    /// <param name="slot">1, 2, 3 or auto.</param>
    /// <returns>The snapshot.</returns>
    [HttpGet("{id:guid}/{slot}")]
    [Produces("application/octet-stream", "application/json")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public ActionResult Get([FromRoute] Guid id, [FromRoute] string slot) => Send(id, slot, false);

    /// <summary>Downloads a snapshot's screenshot.</summary>
    /// <param name="id">Item id.</param>
    /// <param name="slot">Slot.</param>
    /// <returns>PNG.</returns>
    [HttpGet("{id:guid}/{slot}/image")]
    [Produces("image/png", "application/json")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    public ActionResult GetImage([FromRoute] Guid id, [FromRoute] string slot) => Send(id, slot, true);

    /// <summary>Stores a snapshot (raw bytes). The previous one in the slot is kept as a backup file.</summary>
    /// <param name="id">Item id.</param>
    /// <param name="slot">Slot.</param>
    /// <param name="core">Emulator core that made it.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>Info.</returns>
    [HttpPut("{id:guid}/{slot}")]
    [Consumes("application/octet-stream")]
    [ProducesResponseType(StatusCodes.Status201Created)]
    public async Task<ActionResult> Put([FromRoute] Guid id, [FromRoute] string slot, [FromQuery] string? core, CancellationToken cancellationToken)
    {
        if (Resolve(id, slot, true, out var user, out var rom) is ActionResult fail)
        {
            return fail;
        }

        if (!await SnapshotsEnabledAsync(user, rom!, cancellationToken).ConfigureAwait(false))
        {
            return Fail(StatusCodes.Status403Forbidden, "snapshots_off", "Snapshots are switched off for " + rom!.Console.DisplayName + " in your settings.");
        }

        var cfg = ShoalcadeServices.Config;
        var (uploadSlot, busy) = await BeginUploadAsync(user.Id, Math.Clamp(cfg.MaxSnapshotMegabytes, 1, 1024) * 1024L * 1024L, cancellationToken).ConfigureAwait(false);
        if (busy is not null)
        {
            return busy;
        }

        using (uploadSlot)
        {
            // M1: streamed to disk as it arrives, never held in memory.
            var tmp = Services.States.NewUploadPath();
            try
            {
                var (length, sha, bodyFail) = await ReadBodyToFileAsync(tmp, Math.Clamp(cfg.MaxSnapshotMegabytes, 1, 1024) * 1024L * 1024L, cancellationToken).ConfigureAwait(false);
                if (bodyFail is not null)
                {
                    return bodyFail;
                }

                if (length < 16)
                {
                    return Fail(StatusCodes.Status422UnprocessableEntity, "size", "That snapshot is empty.");
                }

                var info = await Services.States.PutFileAsync(user.Id, rom!.Id, slot, tmp, sha, core, Math.Max(0, cfg.SnapshotQuotaMegabytes) * 1024L * 1024L, cancellationToken, rom.Console.Key, rom.Name).ConfigureAwait(false);
                return info is null
                    ? Fail(StatusCodes.Status507InsufficientStorage, "quota", "Your snapshot storage is full. Delete some snapshots first.")
                    : StatusCode(StatusCodes.Status201Created, info);
            }
            finally
            {
                TryDeleteFile(tmp);
            }
        }
    }

    /// <summary>Stores the screenshot for a snapshot (PNG, up to 2 MB).</summary>
    /// <param name="id">Item id.</param>
    /// <param name="slot">Slot.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>Info.</returns>
    [HttpPut("{id:guid}/{slot}/image")]
    [Consumes("image/png")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    public async Task<ActionResult> PutImage([FromRoute] Guid id, [FromRoute] string slot, CancellationToken cancellationToken)
    {
        if (Resolve(id, slot, true, out var user, out var rom) is ActionResult fail)
        {
            return fail;
        }

        // s0.x L4: the same rules as the snapshot itself (snapshots switched off, quota).
        if (!await SnapshotsEnabledAsync(user, rom!, cancellationToken).ConfigureAwait(false))
        {
            return Fail(StatusCodes.Status403Forbidden, "snapshots_off", "Snapshots are switched off for " + rom!.Console.DisplayName + " in your settings.");
        }

        var (uploadSlot, busy) = await BeginUploadAsync(user.Id, 2 * 1024 * 1024, cancellationToken).ConfigureAwait(false);
        if (busy is not null)
        {
            return busy;
        }

        using var slotHandle = uploadSlot;
        var (data, bodyFail) = await ReadBodyAsync(2 * 1024 * 1024, cancellationToken).ConfigureAwait(false);
        if (bodyFail is not null)
        {
            return bodyFail;
        }

        var r = await Services.States.PutImageAsync(user.Id, rom!.Id, slot, data!, Math.Max(0, ShoalcadeServices.Config.SnapshotQuotaMegabytes) * 1024L * 1024L, cancellationToken).ConfigureAwait(false);
        if (r.OverQuota)
        {
            return Fail(StatusCodes.Status507InsufficientStorage, "quota", "Your snapshot storage is full. Delete some snapshots first.");
        }

        return r.Info is null ? Fail(StatusCodes.Status422UnprocessableEntity, "bad_image", "Needs a PNG, and a snapshot in that slot.") : Ok(r.Info);
    }

    /// <summary>Stores which cheats were on with a snapshot ({ cheats: [{ name, code }] }, D143). Sent after the snapshot
    /// itself, like its picture. An empty list clears it.</summary>
    /// <param name="id">Item id.</param>
    /// <param name="slot">Slot.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>Info.</returns>
    [HttpPut("{id:guid}/{slot}/cheats")]
    [Consumes("application/json")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    public async Task<ActionResult> PutCheats([FromRoute] Guid id, [FromRoute] string slot, CancellationToken cancellationToken)
    {
        if (Resolve(id, slot, true, out var user, out var rom) is ActionResult fail)
        {
            return fail;
        }

        var max = Cheats.CheatStore.MaxBodyBytes * 4;
        var (uploadSlot, busy) = await BeginUploadAsync(user.Id, max, cancellationToken).ConfigureAwait(false);
        if (busy is not null)
        {
            return busy;
        }

        using var slotHandle = uploadSlot;
        var (data, bodyFail) = await ReadBodyAsync(max, cancellationToken).ConfigureAwait(false);
        if (bodyFail is not null)
        {
            return bodyFail;
        }

        List<Cheats.SnapshotCheat>? list = null;
        try
        {
            list = System.Text.Json.JsonSerializer.Deserialize<SnapshotCheatsDto>(data!)?.Cheats;
        }
        catch (System.Text.Json.JsonException)
        {
            // handled below
        }

        if (list is null || list.Count > Cheats.CheatStore.MaxCheats)
        {
            return Fail(StatusCodes.Status422UnprocessableEntity, "bad_cheats", "That isn't a list of cheats.");
        }

        var clean = new List<Cheats.SnapshotCheat>();
        foreach (var c in list)
        {
            var name = Cheats.CheatStore.CleanName(c?.Name);
            var code = Cheats.CheatStore.CleanCode(c?.Code);
            if (name is null || code is null)
            {
                return Fail(StatusCodes.Status422UnprocessableEntity, "bad_cheats", "One of the cheats has a name or code that isn't allowed.");
            }

            clean.Add(new Cheats.SnapshotCheat(name, code));
        }

        var info = await Services.States.PutCheatsAsync(user.Id, rom!.Id, slot, clean, cancellationToken).ConfigureAwait(false);
        return info is null ? Fail(StatusCodes.Status404NotFound, "no_snapshot", "There's no snapshot in that slot.") : Ok(info);
    }

    /// <summary>Deletes a snapshot.</summary>
    /// <param name="id">Item id.</param>
    /// <param name="slot">Slot.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>204 or 404.</returns>
    [HttpDelete("{id:guid}/{slot}")]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    public async Task<ActionResult> Delete([FromRoute] Guid id, [FromRoute] string slot, CancellationToken cancellationToken)
    {
        if (Resolve(id, slot, false, out var user, out _) is ActionResult fail)
        {
            return fail;
        }

        return await Services.States.DeleteAsync(user.Id, id, slot, cancellationToken).ConfigureAwait(false)
            ? NoContent()
            : Fail(StatusCodes.Status404NotFound, "missing", "No snapshot in that slot.");
    }

    private ActionResult Send(Guid id, string slot, bool image)
    {
        if (Resolve(id, slot, false, out var user, out _) is ActionResult fail)
        {
            return fail;
        }

        var opened = Services.States.Open(user.Id, id, slot, image);
        if (opened is not { } o)
        {
            return Fail(StatusCodes.Status404NotFound, "missing", "No snapshot in that slot.");
        }

        Response.Headers.CacheControl = "no-store";
        if (!image && o.Info.Core is not null)
        {
            Response.Headers["X-Shoalcade-Core"] = o.Info.Core;
        }

        return File(o.Data, image ? "image/png" : "application/octet-stream");
    }

    /// <summary>
    /// Works out who and which game. A ROM the user can see always counts; with <paramref name="needGame"/> false, so does a
    /// game no longer in the library that this user has snapshots of (F8; <paramref name="rom"/> is then null). The store
    /// is keyed by the signed-in user, so only their own snapshots are ever reached.
    /// </summary>
    private ActionResult? Resolve(Guid id, string? slot, bool needGame, out Jellyfin.Database.Implementations.Entities.User user, out RomItem? rom)
    {
        rom = null;
        if (RequireUser(out user) is ActionResult fail)
        {
            return fail;
        }

        if (slot is not null && !StateStore.IsValidSlot(slot))
        {
            return Fail(StatusCodes.Status400BadRequest, "bad_slot", "Slot must be 1, 2, 3 or auto.");
        }

        if (Library.GetRom(id, user) is RomItem r)
        {
            rom = r;
            return r.Console.UsesEmulatorJs ? null : GameNotFound(); // no snapshots for RPG Maker (D147)
        }

        // Removed from the library (not just hidden from this user), and this user has snapshots of it.
        return needGame || Library.Exists(id) || Services.States.List(user.Id, id).Count == 0 ? GameNotFound() : null;
    }

    private async Task<bool> SnapshotsEnabledAsync(Jellyfin.Database.Implementations.Entities.User user, RomItem rom, CancellationToken cancellationToken)
    {
        var bytes = await Services.Prefs.GetAsync(user.Id, rom.Console.Key, cancellationToken).ConfigureAwait(false);
        try
        {
            return JsonNode.Parse(bytes) is not JsonObject o || o["snapshots"] is not JsonValue v || !v.TryGetValue<bool>(out var on) || on;
        }
        catch (System.Text.Json.JsonException)
        {
            return true;
        }
    }
}
