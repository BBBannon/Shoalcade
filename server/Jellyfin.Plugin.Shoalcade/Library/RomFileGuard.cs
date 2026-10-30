using System;
using System.Threading.Tasks;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.Filters;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Shoalcade.Library;

/// <summary>
/// Defence in depth for G5 s0.2 M1: Jellyfin's own file routes (<c>GET /Items/{id}/Download</c> and
/// <c>/Items/{id}/File</c>) refuse (404) a ROM item whose file is not inside one of its library's folders, even if an
/// item's path somehow points elsewhere (e.g. <c>jellyfin.db</c>). Other items are never touched. Runs as an MVC action
/// filter, so after Jellyfin's own login and permission checks and whatever the URL looked like.
/// </summary>
public sealed class RomFileGuard : IAsyncActionFilter
{
    private readonly RomLibrary _library;
    private readonly ILogger<RomFileGuard> _logger;

    /// <summary>Initializes a new instance of the <see cref="RomFileGuard"/> class.</summary>
    /// <param name="library">ROM library.</param>
    /// <param name="logger">Logger.</param>
    public RomFileGuard(RomLibrary library, ILogger<RomFileGuard> logger)
    {
        _library = library;
        _logger = logger;
    }

    /// <summary>True for the route templates of Jellyfin's file-serving item routes.</summary>
    /// <param name="template">Attribute route template.</param>
    /// <returns>Whether we guard it.</returns>
    public static bool IsGuardedRoute(string? template) =>
        string.Equals(template, "Items/{itemId}/Download", StringComparison.OrdinalIgnoreCase)
        || string.Equals(template, "Items/{itemId}/File", StringComparison.OrdinalIgnoreCase);

    /// <inheritdoc />
    public async Task OnActionExecutionAsync(ActionExecutingContext context, ActionExecutionDelegate next)
    {
        ArgumentNullException.ThrowIfNull(context);
        ArgumentNullException.ThrowIfNull(next);
        if (IsGuardedRoute(context.ActionDescriptor.AttributeRouteInfo?.Template)
            && context.ActionArguments.TryGetValue("itemId", out var raw) && raw is Guid itemId
            && _library.IsRomOutsideItsLibrary(itemId))
        {
            _logger.LogWarning("Shoalcade refused to serve game {ItemId}: its file is not inside its library's folders", itemId);
            context.Result = new StatusCodeResult(StatusCodes.Status404NotFound);
            return;
        }

        await next().ConfigureAwait(false);
    }
}
