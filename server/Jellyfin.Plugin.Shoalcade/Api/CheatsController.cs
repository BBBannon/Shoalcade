using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Plugin.Shoalcade.Cheats;
using Jellyfin.Plugin.Shoalcade.Library;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;

namespace Jellyfin.Plugin.Shoalcade.Api;

/// <summary>Body of "add a cheat".</summary>
/// <param name="Name">Name.</param>
/// <param name="Code">Code.</param>
public sealed record CheatAddDto(
    [property: JsonPropertyName("name")] string? Name,
    [property: JsonPropertyName("code")] string? Code);

/// <summary>Body of "set the cheats that are on".</summary>
/// <param name="Ids">Cheat ids, in order.</param>
public sealed record CheatActiveDto([property: JsonPropertyName("ids")] List<string>? Ids);

/// <summary>Body of "store the cheats that were on with a snapshot".</summary>
/// <param name="Cheats">Name and code of each.</param>
public sealed record SnapshotCheatsDto([property: JsonPropertyName("cheats")] List<SnapshotCheat>? Cheats);

/// <summary>
/// The signed-in person's cheat codes for one game (D143): the game's cheat library and which are on for the in-game
/// save. Only for ROMs the person can see. Codes are only what the person types or imports: there's no cheat database.
/// </summary>
[Route("shoalcade/cheats")]
public class CheatsController : ShoalcadeControllerBase
{
    private static readonly JsonSerializerOptions Json = new() { PropertyNameCaseInsensitive = false };

    /// <summary>Initializes a new instance of the <see cref="CheatsController"/> class.</summary>
    /// <param name="library">ROM library.</param>
    /// <param name="services">Stores.</param>
    public CheatsController(RomLibrary library, ShoalcadeServices services)
        : base(library, services)
    {
    }

    /// <summary>A game's cheats: { cheats: [{ id, name, code, added }], active: [ids] }.</summary>
    /// <param name="id">Item id.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>The cheats.</returns>
    [HttpGet("{id:guid}")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    public async Task<ActionResult> Get([FromRoute] Guid id, CancellationToken cancellationToken)
    {
        if (Resolve(id, out var user) is ActionResult fail)
        {
            return fail;
        }

        Response.Headers.CacheControl = "no-store";
        return Ok(await Services.Cheats.GetAsync(user.Id, id, cancellationToken).ConfigureAwait(false));
    }

    /// <summary>Adds a cheat ({ name, code }). The same code twice isn't added again: 200 with the one already there.</summary>
    /// <param name="id">Item id.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>201 with the new cheat, 200 with the existing one, 409 full, 422 bad name or code.</returns>
    [HttpPost("{id:guid}")]
    [Consumes("application/json")]
    [ProducesResponseType(StatusCodes.Status201Created)]
    [ProducesResponseType(StatusCodes.Status200OK)]
    public async Task<ActionResult> Add([FromRoute] Guid id, CancellationToken cancellationToken)
    {
        if (Resolve(id, out var user) is ActionResult fail)
        {
            return fail;
        }

        var (body, bad) = await ReadJsonAsync<CheatAddDto>(user.Id, cancellationToken).ConfigureAwait(false);
        if (bad is not null)
        {
            return bad;
        }

        var name = CheatStore.CleanName(body!.Name);
        var code = CheatStore.CleanCode(body.Code);
        if (name is null)
        {
            return Fail(StatusCodes.Status422UnprocessableEntity, "bad_name", "Give the cheat a name (up to " + CheatStore.MaxName + " characters).");
        }

        if (code is null)
        {
            return Fail(StatusCodes.Status422UnprocessableEntity, "bad_code", "That doesn't look like a cheat code. Codes use letters, numbers, spaces and + - : _ . , only.");
        }

        var r = await Services.Cheats.AddAsync(user.Id, id, name, code, cancellationToken).ConfigureAwait(false);
        return r.Outcome switch
        {
            CheatOutcome.Created => StatusCode(StatusCodes.Status201Created, r.Entry),
            CheatOutcome.Existing => Ok(r.Entry),
            _ => Fail(StatusCodes.Status409Conflict, "full", "This game has " + CheatStore.MaxCheats + " cheats already. Delete some first."),
        };
    }

    /// <summary>Renames a cheat and/or changes its code ({ name?, code? }).</summary>
    /// <param name="id">Item id.</param>
    /// <param name="cheat">Cheat id.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>200 with the cheat, 404, or 422.</returns>
    [HttpPatch("{id:guid}/{cheat}")]
    [Consumes("application/json")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    public async Task<ActionResult> Update([FromRoute] Guid id, [FromRoute] string cheat, CancellationToken cancellationToken)
    {
        if (Resolve(id, out var user) is ActionResult fail)
        {
            return fail;
        }

        if (!CheatStore.IsId(cheat))
        {
            return CheatNotFound();
        }

        var (body, bad) = await ReadJsonAsync<CheatAddDto>(user.Id, cancellationToken).ConfigureAwait(false);
        if (bad is not null)
        {
            return bad;
        }

        string? name = null, code = null;
        if (body!.Name is not null && (name = CheatStore.CleanName(body.Name)) is null)
        {
            return Fail(StatusCodes.Status422UnprocessableEntity, "bad_name", "Give the cheat a name (up to " + CheatStore.MaxName + " characters).");
        }

        if (body.Code is not null && (code = CheatStore.CleanCode(body.Code)) is null)
        {
            return Fail(StatusCodes.Status422UnprocessableEntity, "bad_code", "That doesn't look like a cheat code. Codes use letters, numbers, spaces and + - : _ . , only.");
        }

        var r = await Services.Cheats.UpdateAsync(user.Id, id, cheat, name, code, cancellationToken).ConfigureAwait(false);
        return r.Outcome == CheatOutcome.NotFound ? CheatNotFound() : Ok(r.Entry);
    }

    /// <summary>Deletes a cheat from the game's list (and turns it off).</summary>
    /// <param name="id">Item id.</param>
    /// <param name="cheat">Cheat id.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>204 or 404.</returns>
    [HttpDelete("{id:guid}/{cheat}")]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    public async Task<ActionResult> Delete([FromRoute] Guid id, [FromRoute] string cheat, CancellationToken cancellationToken)
    {
        if (Resolve(id, out var user) is ActionResult fail)
        {
            return fail;
        }

        if (!CheatStore.IsId(cheat))
        {
            return CheatNotFound();
        }

        var r = await Services.Cheats.DeleteAsync(user.Id, id, cheat, cancellationToken).ConfigureAwait(false);
        return r.Outcome == CheatOutcome.NotFound ? CheatNotFound() : NoContent();
    }

    /// <summary>Sets which cheats are on for the in-game save ({ ids: [...] }, in the order they're applied).</summary>
    /// <param name="id">Item id.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>200 with the game's cheats.</returns>
    [HttpPut("{id:guid}/active")]
    [Consumes("application/json")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    public async Task<ActionResult> SetActive([FromRoute] Guid id, CancellationToken cancellationToken)
    {
        if (Resolve(id, out var user) is ActionResult fail)
        {
            return fail;
        }

        var (body, bad) = await ReadJsonAsync<CheatActiveDto>(user.Id, cancellationToken).ConfigureAwait(false);
        if (bad is not null)
        {
            return bad;
        }

        var ids = body!.Ids ?? [];
        if (ids.Count > CheatStore.MaxCheats || !ids.TrueForAll(CheatStore.IsId))
        {
            return Fail(StatusCodes.Status422UnprocessableEntity, "bad_ids", "That isn't a list of this game's cheats.");
        }

        var r = await Services.Cheats.SetActiveAsync(user.Id, id, ids, cancellationToken).ConfigureAwait(false);
        return Ok(r.Doc);
    }

    private ActionResult? Resolve(Guid id, out Jellyfin.Database.Implementations.Entities.User user)
    {
        if (RequireUser(out user) is ActionResult fail)
        {
            return fail;
        }

        return RequireRom(id, user, out _);
    }

    private ObjectResult CheatNotFound() => Fail(StatusCodes.Status404NotFound, "no_cheat", "That cheat isn't in this game's list any more.");

    private async Task<(T? Body, ActionResult? Error)> ReadJsonAsync<T>(Guid user, CancellationToken cancellationToken)
        where T : class
    {
        var (uploadSlot, busy) = await BeginUploadAsync(user, CheatStore.MaxBodyBytes, cancellationToken).ConfigureAwait(false);
        if (busy is not null)
        {
            return (null, busy);
        }

        using var slotHandle = uploadSlot;
        var (data, bodyFail) = await ReadBodyAsync(CheatStore.MaxBodyBytes, cancellationToken).ConfigureAwait(false);
        if (bodyFail is not null)
        {
            return (null, bodyFail);
        }

        try
        {
            var body = JsonSerializer.Deserialize<T>(data!, Json);
            if (body is not null)
            {
                return (body, null);
            }
        }
        catch (JsonException)
        {
            // handled below
        }

        return (null, Fail(StatusCodes.Status422UnprocessableEntity, "bad_json", "The request wasn't valid."));
    }
}
