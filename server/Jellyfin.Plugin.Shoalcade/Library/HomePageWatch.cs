using System;
using System.Text.RegularExpressions;
using System.Threading.Tasks;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;

namespace Jellyfin.Plugin.Shoalcade.Library;

/// <summary>
/// Watches for a user saving their own display settings (Settings → Home screen), which Jellyfin raises no event for.
/// Jellyfin web drops games libraries from "My Media" on every such save, so right after it the home page hider runs
/// again and puts it back if the user still has the games library unticked (D109). Only looks at the request path and
/// method; never reads or changes the request or the reply.
/// </summary>
public sealed class HomePageWatchStartupFilter : IStartupFilter
{
    /// <inheritdoc />
    public Action<IApplicationBuilder> Configure(Action<IApplicationBuilder> next)
    {
        return app =>
        {
            app.UseMiddleware<HomePageWatchMiddleware>();
            next(app);
        };
    }
}

/// <summary>See <see cref="HomePageWatchStartupFilter"/>.</summary>
public sealed partial class HomePageWatchMiddleware
{
    private readonly RequestDelegate _next;
    private readonly HomePageHider _hider;

    /// <summary>Initializes a new instance of the <see cref="HomePageWatchMiddleware"/> class.</summary>
    /// <param name="next">Next step.</param>
    /// <param name="hider">Home page hider.</param>
    public HomePageWatchMiddleware(RequestDelegate next, HomePageHider hider)
    {
        _next = next;
        _hider = hider;
    }

    /// <summary>True for Jellyfin's "save user display settings" calls (<c>POST /Users/Configuration</c> and
    /// <c>POST /Users/{id}/Configuration</c>, under a one-part base URL such as <c>/jellyfin</c>). Anchored at the start.</summary>
    /// <param name="method">HTTP method.</param>
    /// <param name="path">Request path.</param>
    /// <returns>Whether it is one.</returns>
    public static bool IsUserConfigurationSave(string method, string? path) =>
        HttpMethods.IsPost(method) && path is not null && UserConfigPath().IsMatch(path);

    /// <summary>Runs the request, then the hider if it was a successful display settings save.</summary>
    /// <param name="context">Request.</param>
    /// <returns>A task.</returns>
    public async Task InvokeAsync(HttpContext context)
    {
        ArgumentNullException.ThrowIfNull(context);
        await _next(context).ConfigureAwait(false);
        if (IsUserConfigurationSave(context.Request.Method, context.Request.Path.Value)
            && context.Response.StatusCode is >= 200 and < 300)
        {
            _ = _hider.SyncSoonAsync(); // in the background (coalesced): the user's reply is already sent
        }
    }

    [GeneratedRegex(@"^(?:/[^/]+)?/Users/(?:[0-9a-f]{8}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{12}/)?Configuration/?$", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant)]
    private static partial Regex UserConfigPath();
}
