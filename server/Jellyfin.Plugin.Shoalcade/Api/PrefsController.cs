using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Plugin.Shoalcade.Library;
using Jellyfin.Plugin.Shoalcade.Prefs;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;

namespace Jellyfin.Plugin.Shoalcade.Api;

/// <summary>
/// The signed-in user's settings, per console (gb, gbc, gba, nds, 3ds, psp) plus "global" (D33).
/// The server is the only store, so controls follow the person to any browser.
/// </summary>
[Route("shoalcade/prefs")]
public class PrefsController : ShoalcadeControllerBase
{
    /// <summary>Initializes a new instance of the <see cref="PrefsController"/> class.</summary>
    /// <param name="library">ROM library.</param>
    /// <param name="services">Stores.</param>
    public PrefsController(RomLibrary library, ShoalcadeServices services)
        : base(library, services)
    {
    }

    /// <summary>All settings: { "gba": {...}, "global": {...}, ... }.</summary>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>Settings.</returns>
    [HttpGet]
    [ProducesResponseType(StatusCodes.Status200OK)]
    public async Task<ActionResult> GetAll(CancellationToken cancellationToken)
    {
        if (RequireUser(out var user) is ActionResult fail)
        {
            return fail;
        }

        var all = new JsonObject();
        foreach (var scope in PrefsStore.Scopes)
        {
            var bytes = await Services.Prefs.GetAsync(user.Id, scope, cancellationToken).ConfigureAwait(false);
            PrefsStore.Validate(bytes, out var obj); // the store hands back only valid settings (a damaged file is kept aside)
            all[scope] = obj ?? new JsonObject();
        }

        Response.Headers.CacheControl = "no-store";
        return Content(all.ToJsonString(), "application/json");
    }

    /// <summary>One scope's settings ({} if none yet).</summary>
    /// <param name="scope">Console key or "global".</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>Settings.</returns>
    [HttpGet("{scope}")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    public async Task<ActionResult> Get([FromRoute] string scope, CancellationToken cancellationToken)
    {
        if (RequireUser(out var user) is ActionResult fail)
        {
            return fail;
        }

        if (!PrefsStore.IsValidScope(scope))
        {
            return Fail(StatusCodes.Status404NotFound, "bad_scope", "Unknown settings group.");
        }

        Response.Headers.CacheControl = "no-store";
        return File(await Services.Prefs.GetAsync(user.Id, scope, cancellationToken).ConfigureAwait(false), "application/json");
    }

    /// <summary>Replaces one scope's settings (a JSON object, up to 64 KB).</summary>
    /// <param name="scope">Console key or "global".</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>204.</returns>
    [HttpPut("{scope}")]
    [Consumes("application/json")]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    public async Task<ActionResult> Put([FromRoute] string scope, CancellationToken cancellationToken)
    {
        if (RequireUser(out var user) is ActionResult fail)
        {
            return fail;
        }

        if (!PrefsStore.IsValidScope(scope))
        {
            return Fail(StatusCodes.Status404NotFound, "bad_scope", "Unknown settings group.");
        }

        var (uploadSlot, busy) = await BeginUploadAsync(user.Id, PrefsStore.MaxBytes, cancellationToken).ConfigureAwait(false);
        if (busy is not null)
        {
            return busy;
        }

        using var slotHandle = uploadSlot;
        var (data, bodyFail) = await ReadBodyAsync(PrefsStore.MaxBytes, cancellationToken).ConfigureAwait(false);
        if (bodyFail is not null)
        {
            return bodyFail;
        }

        var err = PrefsStore.Validate(data, out var obj);
        if (err is not null)
        {
            return Fail(StatusCodes.Status422UnprocessableEntity, "bad_settings", err);
        }

        await Services.Prefs.PutAsync(user.Id, scope, obj!, cancellationToken).ConfigureAwait(false);
        return NoContent();
    }
}
