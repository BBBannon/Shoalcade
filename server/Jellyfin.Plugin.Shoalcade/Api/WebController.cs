using System;
using System.Collections.Frozen;
using System.Collections.Generic;
using Jellyfin.Plugin.Shoalcade.Web;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Net.Http.Headers;

namespace Jellyfin.Plugin.Shoalcade.Api;

/// <summary>
/// Serves the browser pages and EmulatorJS. These are public program files (HTML/JS/cores) with <b>no user data</b>,
/// so they need no login, exactly like Jellyfin's own web client. Every bit of data the pages show comes from the
/// logged-in API. Only files on the fixed start-up list can be served, with strict security headers.
/// </summary>
[ApiController]
[AllowAnonymous]
[Route("shoalcade")]
[ApiExplorerSettings(IgnoreApi = true)]
public class WebController : ControllerBase
{
    /// <summary>Content Security Policy for our pages: only this server's own files; no inline scripts, no other sites.
    /// <c>blob:</c> + <c>wasm-unsafe-eval</c> are what EmulatorJS needs to start its cores.</summary>
    public const string ContentSecurityPolicy =
        "default-src 'self'; script-src 'self' blob: 'wasm-unsafe-eval'; style-src 'self' 'unsafe-inline'; " +
        "img-src 'self' data: blob:; media-src 'self' blob:; connect-src 'self' blob: data:; worker-src 'self' blob:; " +
        "font-src 'self' data:; object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'self'";

    /// <summary>
    /// Content Security Policy for EmulatorJS's two unzip workers. They are old Emscripten builds that call
    /// <c>eval</c> while starting, which the page policy (rightly) forbids. Workers loaded from a URL get their own
    /// policy, so <c>unsafe-eval</c> is allowed <b>only inside these two workers</b>, which can't fetch anything,
    /// load other scripts or touch the page. The page itself never allows eval.
    /// </summary>
    public const string EvalWorkerContentSecurityPolicy = "default-src 'none'; script-src 'unsafe-eval'";

    /// <summary>
    /// Content Security Policy for every other response: scripts, styles, cores, pictures and the API (s0.x L6). These
    /// are never meant to be opened as a page, so if one ever is (a crafted link, a wrong type), it can't run anything,
    /// load anything or be framed.
    /// </summary>
    public const string NonHtmlContentSecurityPolicy = "default-src 'none'; frame-ancestors 'none'; sandbox";

    /// <summary>
    /// Content Security Policy for the player frame (security review M3, docs/plan-player-sandbox.md): the page policy
    /// plus <c>sandbox allow-scripts</c>, so the frame runs with an opaque origin even if it is opened on its own. It can
    /// run EmulatorJS but can never read the Jellyfin login (storage, cookies), call the API, open windows or move the
    /// page: it asks the player page (the shell) for this one game's routes instead.
    /// </summary>
    public const string FrameContentSecurityPolicy = "sandbox allow-scripts; " + ContentSecurityPolicy;

    /// <summary>The only files served with <see cref="EvalWorkerContentSecurityPolicy"/>.</summary>
    public static readonly FrozenSet<string> EvalWorkers = new[] { "compression/extract7z.js", "compression/extractzip.js" }
        .ToFrozenSet(StringComparer.Ordinal);

    private readonly WebAssets _assets;

    /// <summary>Initializes a new instance of the <see cref="WebController"/> class.</summary>
    /// <param name="assets">Assets.</param>
    public WebController(WebAssets assets) => _assets = assets;

    /// <summary>Redirects to the library page.</summary>
    /// <returns>Redirect.</returns>
    [HttpGet("")]
    public ActionResult Root()
    {
        // "/shoalcade" -> "/shoalcade/" so relative links work (keeps any Jellyfin base URL).
        if (!Request.Path.Value!.EndsWith('/'))
        {
            return Redirect(Request.PathBase + Request.Path + "/");
        }

        return Page("index.html", false);
    }

    /// <summary>The player page.</summary>
    /// <returns>HTML.</returns>
    [HttpGet("play")]
    public ActionResult Play() => Page("play.html", true);

    /// <summary>The player frame (M3): the game and its controls, sandboxed inside the player page.</summary>
    /// <returns>HTML.</returns>
    [HttpGet("play-frame")]
    public ActionResult PlayFrame() => Page("play-frame.html", true, true);

    /// <summary>The save manager page.</summary>
    /// <returns>HTML.</returns>
    [HttpGet("saves-manager")]
    public ActionResult SavesPage() => Page("saves.html", false);

    /// <summary>The Settings page: saves, the phone app download and browser player settings per console (D134).</summary>
    /// <returns>HTML.</returns>
    [HttpGet("settings")]
    public ActionResult SettingsPage() => Page("settings.html", false);

    /// <summary>The patcher page: applies a ROM patch in the browser; nothing is uploaded.</summary>
    /// <returns>HTML.</returns>
    [HttpGet("patcher")]
    public ActionResult PatcherPage() => Page("patcher.html", false);

    /// <summary>Our scripts, styles and icons.</summary>
    /// <param name="name">File name.</param>
    /// <returns>The file.</returns>
    [HttpGet("web/{name}")]
    public ActionResult Asset([FromRoute] string name)
    {
        if (!ShoalcadeServices.Config.EnableBrowserPlayer)
        {
            return NotFound();
        }

        var a = _assets.FindPage(name);
        if (a is null || name.EndsWith(".html", StringComparison.Ordinal))
        {
            return NotFound();
        }

        // Loaded by the sandboxed player frame too (an opaque origin counts as another site): public program files only.
        return Send(a, "no-cache", false, false, forFrame: true);
    }

    /// <summary>EmulatorJS files (pinned v4.2.3).</summary>
    /// <param name="path">Path inside the emulator folder.</param>
    /// <returns>The file.</returns>
    [HttpGet("ejs/{**path}")]
    public ActionResult Emulator([FromRoute] string path)
    {
        if (!ShoalcadeServices.Config.EnableBrowserPlayer)
        {
            return NotFound();
        }

        var a = _assets.FindEmulatorFile(path);
        // The frame's EmulatorJS also fetches its cores (CORS), so these may be read from any origin: they are the same
        // public files for everyone. The two eval workers are only ever started by the player page itself: kept same-origin.
        var worker = EvalWorkers.Contains(path);
        return a is null ? NotFound() : Send(a, "public, max-age=86400", false, true, worker, forFrame: !worker, anyOrigin: !worker);
    }

    private ActionResult Page(string name, bool isolated, bool sandboxed = false)
    {
        if (!ShoalcadeServices.Config.EnableBrowserPlayer)
        {
            return NotFound();
        }

        var a = _assets.FindPage(name);
        return a is null ? NotFound() : Send(a, "no-cache", isolated, false, sandboxed: sandboxed);
    }

    /// <summary>The security headers for a served file (kept separate so the policy can be tested).</summary>
    /// <param name="contentType">MIME type.</param>
    /// <param name="isolated">Cross-origin isolate the page (the player).</param>
    /// <param name="evalWorker">One of the <see cref="EvalWorkers"/>.</param>
    /// <param name="forFrame">A program file (web/*, ejs/*) the sandboxed player frame loads: <c>Cross-Origin-Resource-Policy:
    /// cross-origin</c>, as the frame's opaque origin is another site to the browser (never for pages).</param>
    /// <param name="anyOrigin">EmulatorJS files the frame fetches (cores): <c>Access-Control-Allow-Origin: *</c> (never for pages).</param>
    /// <param name="sandboxed">The player frame page: <see cref="FrameContentSecurityPolicy"/>.</param>
    /// <returns>Header name to value.</returns>
    internal static Dictionary<string, string> SecurityHeaders(string contentType, bool isolated, bool evalWorker, bool forFrame = false, bool anyOrigin = false, bool sandboxed = false)
    {
        var isHtml = contentType.StartsWith("text/html", StringComparison.Ordinal);
        var h = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase)
        {
            ["X-Content-Type-Options"] = "nosniff",
            ["Cross-Origin-Resource-Policy"] = forFrame && !isHtml && !evalWorker ? "cross-origin" : "same-origin",
            ["Referrer-Policy"] = "no-referrer",
        };
        if (anyOrigin && !isHtml && !evalWorker)
        {
            h["Access-Control-Allow-Origin"] = "*";
        }

        if (isHtml)
        {
            h["Content-Security-Policy"] = sandboxed ? FrameContentSecurityPolicy : ContentSecurityPolicy;
            h["X-Frame-Options"] = "SAMEORIGIN";
            h["Permissions-Policy"] = "camera=(), microphone=(), geolocation=(), payment=(), usb=()";

            // The player page is cross-origin isolated so threaded cores (SharedArrayBuffer) can run.
            if (isolated)
            {
                h["Cross-Origin-Opener-Policy"] = "same-origin";
                h["Cross-Origin-Embedder-Policy"] = "require-corp";
            }
        }
        else if (evalWorker)
        {
            h["Content-Security-Policy"] = EvalWorkerContentSecurityPolicy;

            // A worker started by the isolated player page must be isolated too, or the browser refuses it.
            h["Cross-Origin-Embedder-Policy"] = "require-corp";
        }
        else
        {
            h["Content-Security-Policy"] = NonHtmlContentSecurityPolicy;
        }

        return h;
    }

    private FileStreamResult Send(WebAsset a, string cacheControl, bool isolated, bool rangeable, bool evalWorker = false, bool forFrame = false, bool anyOrigin = false, bool sandboxed = false)
    {
        foreach (var (name, value) in SecurityHeaders(a.ContentType, isolated, evalWorker, forFrame, anyOrigin, sandboxed))
        {
            Response.Headers[name] = value;
        }

        Response.Headers.CacheControl = cacheControl;
        return File(WebAssets.Open(a), a.ContentType, null, new EntityTagHeaderValue(a.ETag), rangeable);
    }
}
