using Jellyfin.Plugin.Shoalcade.Migration;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;

namespace Jellyfin.Plugin.Shoalcade.Api;

/// <summary>
/// The browser pages' old address from before the rename (D124): <c>/jellygames/</c>, <c>/jellygames/play</c> and
/// <c>/jellygames/saves-manager</c> send the browser to the same page under <c>/shoalcade/</c> (query kept), so old
/// bookmarks and menu links still work. Pages only: no API route answers at the old address, and no data is served here.
/// </summary>
[ApiController]
[AllowAnonymous]
[ApiSecurityHeaders]
[Route(LegacyNames.Route)]
[ApiExplorerSettings(IgnoreApi = true)]
public class LegacyPagesController : ControllerBase
{
    /// <summary>Library page.</summary>
    /// <returns>Redirect.</returns>
    [HttpGet("", Order = 1)] // Order 1: an old JellyGames plugin still running until a restart keeps its own pages
    public ActionResult Library() => To(string.Empty);

    /// <summary>Player page.</summary>
    /// <returns>Redirect.</returns>
    [HttpGet("play", Order = 1)]
    public ActionResult Play() => To("play");

    /// <summary>Saves manager page.</summary>
    /// <returns>Redirect.</returns>
    [HttpGet("saves-manager", Order = 1)]
    public ActionResult Saves() => To("saves-manager");

    private RedirectResult To(string page) =>
        Redirect(Request.PathBase + "/shoalcade/" + page + Request.QueryString);
}
