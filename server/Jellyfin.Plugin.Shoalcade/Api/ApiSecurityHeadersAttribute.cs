using System;
using Microsoft.AspNetCore.Mvc.Filters;

namespace Jellyfin.Plugin.Shoalcade.Api;

/// <summary>
/// Locks down every Shoalcade API answer (JSON, save and snapshot downloads, cover previews, redirects) the same way as
/// the non-HTML files (s0.x L6): <c>nosniff</c> plus <see cref="WebController.NonHtmlContentSecurityPolicy"/>, so a
/// response opened as a page can never run script, load anything or be framed. Headers a route set itself are kept.
/// </summary>
[AttributeUsage(AttributeTargets.Class, Inherited = true, AllowMultiple = false)]
public sealed class ApiSecurityHeadersAttribute : ResultFilterAttribute
{
    /// <inheritdoc />
    public override void OnResultExecuting(ResultExecutingContext context)
    {
        ArgumentNullException.ThrowIfNull(context);
        var headers = context.HttpContext.Response.Headers;
        if (!headers.ContainsKey("X-Content-Type-Options"))
        {
            headers["X-Content-Type-Options"] = "nosniff";
        }

        if (!headers.ContainsKey("Content-Security-Policy"))
        {
            headers["Content-Security-Policy"] = WebController.NonHtmlContentSecurityPolicy;
        }

        base.OnResultExecuting(context);
    }
}
