using System;
using Jellyfin.Plugin.Shoalcade.Library;
using Jellyfin.Plugin.Shoalcade.Web;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;

namespace Jellyfin.Plugin.Shoalcade.Api;

/// <summary>
/// Tiny lookups for <c>jellyfin-links.js</c>, the script that puts our Shoalcade link and Play/Saves buttons inside
/// Jellyfin's own web client (D67). Needs a Jellyfin login like every API route. Always answers 200 so Jellyfin
/// web's console stays clean on items that aren't games.
/// </summary>
[Route("shoalcade/links")]
public class WebLinksController : ShoalcadeControllerBase
{
    private readonly WebAssets _web;

    /// <summary>Initializes a new instance of the <see cref="WebLinksController"/> class.</summary>
    /// <param name="library">ROM library.</param>
    /// <param name="services">Stores.</param>
    /// <param name="web">Web assets.</param>
    public WebLinksController(RomLibrary library, ShoalcadeServices services, WebAssets web)
        : base(library, services)
    {
        _web = web;
    }

    /// <summary>Whether to show the Shoalcade link for this user.</summary>
    /// <returns>The answer.</returns>
    [HttpGet]
    [ProducesResponseType(StatusCodes.Status200OK)]
    public ActionResult<WebLinksDto> GetMenu()
    {
        if (RequireUser(out var user) is ActionResult fail)
        {
            return fail;
        }

        return new WebLinksDto(JellyfinWebLinksMiddleware.Enabled && Library.HasAnyRom(user));
    }

    /// <summary>Whether to show Play/Saves on this item's page.</summary>
    /// <param name="id">Item id.</param>
    /// <returns>The answer.</returns>
    [HttpGet("{id:guid}")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    public ActionResult<WebLinksItemDto> GetItem([FromRoute] Guid id)
    {
        if (RequireUser(out var user) is ActionResult fail)
        {
            return fail;
        }

        var rom = JellyfinWebLinksMiddleware.Enabled ? Library.GetRom(id, user) : null;
        return rom is null
            ? new WebLinksItemDto(false, false)
            : new WebLinksItemDto(true, GamesController.IsBrowserPlayable(rom, _web.EmulatorAvailable));
    }
}
