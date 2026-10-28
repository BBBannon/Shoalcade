using System;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Plugin.Shoalcade.Migration;
using MediaBrowser.Common.Configuration;
using MediaBrowser.Common.Net;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.Logging;
using Microsoft.Net.Http.Headers;

namespace Jellyfin.Plugin.Shoalcade.Web;

/// <summary>
/// Puts Shoalcade inside Jellyfin's own web client (D67), with no other plugin needed. Jellyfin has no plugin API
/// for this, so we use a standard ASP.NET Core start-up filter (the same mechanism the File Transformation plugin
/// uses) to change two of Jellyfin web's files <b>as they are sent</b> (nothing on disk changes):
/// <list type="bullet">
/// <item><c>config.json</c>: one entry in Jellyfin web's own <c>menuLinks</c> list, so Jellyfin itself draws the
/// Shoalcade link (top bar on wide screens, side menu on phones).</item>
/// <item><c>index.html</c>: one <c>&lt;script src&gt;</c> tag for <c>jellyfin-links.js</c>, which adds Play and Saves
/// buttons on game pages, hides the Shoalcade link from users with no games, and adds the link itself if the
/// <c>config.json</c> Jellyfin web loaded doesn't have it (an old cached copy, a proxy).</item>
/// </list>
/// If anything looks different or goes wrong, Jellyfin's original file is sent unchanged.
/// </summary>
public sealed class JellyfinWebLinksStartupFilter : IStartupFilter
{
    /// <inheritdoc />
    public Action<IApplicationBuilder> Configure(Action<IApplicationBuilder> next)
    {
        return app =>
        {
            app.UseMiddleware<JellyfinWebLinksMiddleware>();
            next(app);
        };
    }
}

/// <summary>Which Jellyfin web file a request is for.</summary>
public enum JellyfinWebFile
{
    /// <summary>Not one we change.</summary>
    None,

    /// <summary>The start page, <c>{base}/web/</c> or <c>{base}/web/index.html</c>.</summary>
    Index,

    /// <summary>Jellyfin web's settings, <c>{base}/web/config.json</c>.</summary>
    Config,
}

/// <summary>Changes Jellyfin web's <c>index.html</c> and <c>config.json</c> as they are sent. See <see cref="JellyfinWebLinksStartupFilter"/>.</summary>
public sealed class JellyfinWebLinksMiddleware
{
    /// <summary>Marks our script tag so it is never added twice.</summary>
    public const string Marker = "data-shoalcade-links";

    /// <summary>The tag added. Relative to <c>/web/index.html</c>, so any Jellyfin base URL works; no token, no inline code.</summary>
    public const string ScriptTag = "<script defer src=\"../shoalcade/web/jellyfin-links.js\" " + Marker + "></script>";

    /// <summary>The Shoalcade menu link's address. Relative to <c>/web/</c> (keeps any base URL); <c>jellyfin-links.js</c> finds the link by it.</summary>
    public const string MenuUrl = "../shoalcade/";

    /// <summary>The Shoalcade menu link's name (s0.2 item 8: not "Games", which clashed with a library called Games).</summary>
    public const string MenuName = "Shoalcade";

    /// <summary>The menu link's name before s0.2. An entry of ours still carrying it is renamed to <see cref="MenuName"/>.</summary>
    public const string OldMenuName = "Games";

    /// <summary>The menu link's address before the rename (D124). An entry for it is moved to <see cref="MenuUrl"/>.</summary>
    public const string OldMenuUrl = "../" + LegacyNames.Route + "/";

    /// <summary>The Shoalcade menu link's icon (a Material icon name, as Jellyfin web expects).</summary>
    public const string MenuIcon = "sports_esports";

    private const long MaxFileBytes = 1024 * 1024;

    private readonly RequestDelegate _next;
    private readonly Func<string?> _baseUrl;
    private readonly ILogger _logger;
    private int _warned; // 1 once a failed link injection has been logged as a warning

    /// <summary>Initializes a new instance of the <see cref="JellyfinWebLinksMiddleware"/> class.</summary>
    /// <param name="next">Next middleware.</param>
    /// <param name="config">Server configuration (for the base URL).</param>
    /// <param name="logger">Logger.</param>
    public JellyfinWebLinksMiddleware(RequestDelegate next, IConfigurationManager config, ILogger<JellyfinWebLinksMiddleware> logger)
        : this(next, () => config.GetNetworkConfiguration().BaseUrl, logger)
    {
    }

    /// <summary>Initializes a new instance of the <see cref="JellyfinWebLinksMiddleware"/> class (tests).</summary>
    /// <param name="next">Next middleware.</param>
    /// <param name="baseUrl">Gets Jellyfin's base URL.</param>
    /// <param name="logger">Logger.</param>
    internal JellyfinWebLinksMiddleware(RequestDelegate next, Func<string?> baseUrl, ILogger logger)
    {
        _next = next;
        _baseUrl = baseUrl;
        _logger = logger;
    }

    /// <summary>Gets a value indicating whether the links are switched on (they need the browser pages too).</summary>
    internal static bool Enabled => ShoalcadeServices.Config.EnableBrowserPlayer && ShoalcadeServices.Config.ShowInJellyfin;

    /// <summary>Handles a request.</summary>
    /// <param name="context">HTTP context.</param>
    /// <returns>A task.</returns>
    public Task InvokeAsync(HttpContext context)
    {
        ArgumentNullException.ThrowIfNull(context);
        var path = context.Request.Path.Value;
        if (!HttpMethods.IsGet(context.Request.Method) || path is null || !path.Contains("/web/", StringComparison.OrdinalIgnoreCase))
        {
            return _next(context);
        }

        var file = Which(path, _baseUrl());
        return file == JellyfinWebFile.None || !Enabled ? _next(context) : ChangeAsync(context, file);
    }

    /// <summary>Which Jellyfin web file a path is for.</summary>
    /// <param name="path">Request path.</param>
    /// <param name="baseUrl">Jellyfin's base URL ("" or e.g. "/jellyfin").</param>
    /// <returns>The file, or <see cref="JellyfinWebFile.None"/>.</returns>
    internal static JellyfinWebFile Which(string? path, string? baseUrl)
    {
        if (string.IsNullOrEmpty(path))
        {
            return JellyfinWebFile.None;
        }

        var b = (baseUrl ?? string.Empty).Trim().TrimEnd('/');
        if (b.Length > 0 && !b.StartsWith('/'))
        {
            b = "/" + b;
        }

        if (string.Equals(path, b + "/web/", StringComparison.OrdinalIgnoreCase)
            || string.Equals(path, b + "/web/index.html", StringComparison.OrdinalIgnoreCase))
        {
            return JellyfinWebFile.Index;
        }

        return string.Equals(path, b + "/web/config.json", StringComparison.OrdinalIgnoreCase) ? JellyfinWebFile.Config : JellyfinWebFile.None;
    }

    /// <summary>Adds our tag to the page, just before <c>&lt;/body&gt;</c> (or <c>&lt;/head&gt;</c>).</summary>
    /// <param name="html">Jellyfin web's index.html.</param>
    /// <returns>The new page, or null to leave it as it is (already there, or it doesn't look like the page we know).</returns>
    internal static string? InjectScript(string? html)
    {
        if (string.IsNullOrEmpty(html) || html.Contains(Marker, StringComparison.Ordinal))
        {
            return null;
        }

        var at = html.LastIndexOf("</body>", StringComparison.OrdinalIgnoreCase);
        if (at < 0)
        {
            at = html.LastIndexOf("</head>", StringComparison.OrdinalIgnoreCase);
        }

        return at < 0 ? null : string.Concat(html.AsSpan(0, at), ScriptTag, html.AsSpan(at));
    }

    /// <summary>
    /// Adds the Shoalcade link to Jellyfin web's <c>menuLinks</c>, after any the admin set up. Upgrade from before
    /// s0.2: an entry for our address still named "Games" is renamed, and a second entry for our address is dropped,
    /// so the menu never shows both names. Entries for any other address are never touched.
    /// </summary>
    /// <param name="json">Jellyfin web's config.json.</param>
    /// <returns>The new file, or null to leave it as it is (already right, or not the shape we know).</returns>
    internal static string? AddMenuLink(string? json)
    {
        if (string.IsNullOrWhiteSpace(json))
        {
            return null;
        }

        JsonObject? root;
        try
        {
            root = JsonNode.Parse(json) as JsonObject;
        }
        catch (JsonException)
        {
            return null;
        }

        if (root is null)
        {
            return null;
        }

        JsonArray links;
        if (root["menuLinks"] is null)
        {
            links = [];
            root["menuLinks"] = links;
        }
        else if (root["menuLinks"] is JsonArray existing)
        {
            links = existing;
        }
        else
        {
            return null;
        }

        // Our old address from before the rename (D124) counts as ours: it is moved to the new address.
        var ours = links.OfType<JsonObject>().Where(l => l["url"] is JsonValue v && v.TryGetValue<string>(out var u)
            && (string.Equals(u, MenuUrl, StringComparison.Ordinal) || string.Equals(u, OldMenuUrl, StringComparison.Ordinal))).ToList();
        if (ours.Count == 0)
        {
            links.Add(new JsonObject { ["name"] = MenuName, ["icon"] = MenuIcon, ["url"] = MenuUrl });
        }
        else
        {
            var changed = false;
            foreach (var extra in ours.Skip(1))
            {
                links.Remove(extra);
                changed = true;
            }

            if (ours[0]["name"] is JsonValue n && n.TryGetValue<string>(out var name)
                && (string.Equals(name, OldMenuName, StringComparison.Ordinal) || string.Equals(name, LegacyNames.MenuName, StringComparison.Ordinal)))
            {
                ours[0]["name"] = MenuName;
                changed = true;
            }

            if (ours[0]["url"] is JsonValue uv && uv.TryGetValue<string>(out var url) && !string.Equals(url, MenuUrl, StringComparison.Ordinal))
            {
                ours[0]["url"] = MenuUrl;
                changed = true;
            }

            if (!changed)
            {
                return null;
            }
        }

        return root.ToJsonString(new JsonSerializerOptions { WriteIndented = true });
    }

    private async Task ChangeAsync(HttpContext context, JellyfinWebFile file)
    {
        // Ask for the plain, whole file every time: no compression to undo, no "not modified" with an empty body,
        // and a cached copy never outlives the plugin. Both files are a few KB.
        var req = context.Request.Headers;
        req.Remove(HeaderNames.AcceptEncoding);
        req.Remove(HeaderNames.IfNoneMatch);
        req.Remove(HeaderNames.IfModifiedSince);
        req.Remove(HeaderNames.Range);
        req.Remove(HeaderNames.IfRange);

        var original = context.Response.Body;
        using var buffer = new MemoryStream();
        context.Response.Body = buffer;
        try
        {
            await _next(context).ConfigureAwait(false);
        }
        finally
        {
            context.Response.Body = original;
        }

        var bytes = buffer.ToArray();
        var res = context.Response;
        try
        {
            var type = res.ContentType ?? string.Empty;
            var typeOk = file == JellyfinWebFile.Index
                ? type.StartsWith("text/html", StringComparison.OrdinalIgnoreCase)
                : type.StartsWith("application/json", StringComparison.OrdinalIgnoreCase);
            if (res.StatusCode == StatusCodes.Status200OK
                && typeOk
                && bytes.Length <= MaxFileBytes
                && !res.Headers.ContainsKey(HeaderNames.ContentEncoding))
            {
                var text = Encoding.UTF8.GetString(bytes);
                var changed = file == JellyfinWebFile.Index ? InjectScript(text) : AddMenuLink(text);
                if (changed is not null)
                {
                    bytes = Encoding.UTF8.GetBytes(changed);
                    res.Headers.Remove(HeaderNames.ETag);
                    res.Headers.Remove(HeaderNames.LastModified);
                }
            }
        }
#pragma warning disable CA1031 // Never break Jellyfin's web client: on any surprise send its file unchanged.
        catch (Exception ex)
#pragma warning restore CA1031
        {
            if (Interlocked.Exchange(ref _warned, 1) == 0)
            {
                _logger.LogWarning(ex, "Couldn't add the Shoalcade links to Jellyfin web; sent its file unchanged (only logged once)");
            }
            else
            {
                _logger.LogDebug(ex, "Couldn't add the Shoalcade links to Jellyfin web; sent its file unchanged");
            }
            bytes = buffer.ToArray();
        }

        if (!res.HasStarted)
        {
            res.ContentLength = bytes.Length;

            // Jellyfin sends config.json with no caching rule, so browsers may keep an old copy for days.
            // Always check for a fresh one, so switching the links on or off shows up on the next reload.
            if (file == JellyfinWebFile.Config)
            {
                res.Headers.CacheControl = "no-cache";
            }
        }

        await original.WriteAsync(bytes, context.RequestAborted).ConfigureAwait(false);
    }
}
