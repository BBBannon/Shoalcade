using System;
using System.IO;
using System.Text.Json.Serialization;
using System.Threading.Tasks;
using Jellyfin.Plugin.Shoalcade.Common;
using Jellyfin.Plugin.Shoalcade.Library;
using Jellyfin.Plugin.Shoalcade.Web;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;

namespace Jellyfin.Plugin.Shoalcade.Api;

/// <summary>What the Settings page (and the apps' update banner) shows about the phone or Windows app.</summary>
/// <param name="Enabled">The admin offers the download (always true here: off answers 404).</param>
/// <param name="Available">Phone: this plugin build includes the app. Windows: always (it is on the release page).</param>
/// <param name="Version">App version, or null (unknown).</param>
/// <param name="Size">APK size in bytes, or null (always null for Windows).</param>
/// <param name="Sha256">APK SHA-256, or null (always null for Windows).</param>
/// <param name="Url">Windows (S2, D181): this release's page on GitHub, where the installer is. Null for the phone app,
/// which downloads from this server.</param>
public sealed record AppInfoDto(
    [property: JsonPropertyName("enabled")] bool Enabled,
    [property: JsonPropertyName("available")] bool Available,
    [property: JsonPropertyName("version")] string? Version,
    [property: JsonPropertyName("size")] long? Size,
    [property: JsonPropertyName("sha256")] string? Sha256,
    [property: JsonPropertyName("url")] string? Url = null);

/// <summary>A short-lived download link for the QR code.</summary>
/// <param name="Url">Path from the Jellyfin base URL, e.g. <c>/shoalcade/app/Shoalcade.apk?ticket=…</c>.</param>
/// <param name="ExpiresInSeconds">How long it works.</param>
/// <param name="Uses">How many downloads it allows.</param>
public sealed record AppLinkDto(
    [property: JsonPropertyName("url")] string Url,
    [property: JsonPropertyName("expiresInSeconds")] int ExpiresInSeconds,
    [property: JsonPropertyName("uses")] int Uses);

/// <summary>
/// The phone app download (D134). Everything needs a Jellyfin login: <c>info</c> and <c>link</c> through the normal
/// [Authorize]; the APK itself takes either the login header or a ticket from <c>link</c> (a phone scanning the QR code
/// has no sign-in). Off in the plugin settings: all three answer 404. A deliberate exception to "only ROM items are
/// served": this serves one fixed file built into the plugin, never anything a request names.
/// </summary>
[Route("shoalcade/app")]
public class AppController : ShoalcadeControllerBase
{
    private readonly PhoneApp _app;
    private readonly AppTickets _tickets;
    private readonly IAuthorizationService _authz;
    private readonly IAuthorizationPolicyProvider _policies;

    /// <summary>Initializes a new instance of the <see cref="AppController"/> class.</summary>
    /// <param name="library">ROM library.</param>
    /// <param name="services">Stores.</param>
    /// <param name="app">The bundled app.</param>
    /// <param name="tickets">Download tickets.</param>
    /// <param name="authz">Authorization (the APK route checks the login itself, as it also takes tickets).</param>
    /// <param name="policies">Policies (Jellyfin's default login policy, the same one [Authorize] uses).</param>
    public AppController(RomLibrary library, ShoalcadeServices services, PhoneApp app, AppTickets tickets, IAuthorizationService authz, IAuthorizationPolicyProvider policies)
        : base(library, services)
    {
        _app = app;
        _tickets = tickets;
        _authz = authz;
        _policies = policies;
    }

    /// <summary>Where the Windows app's installer is: this release's page (D181; the plugin never carries it).</summary>
    internal const string ReleasePage = "https://github.com/BBBannon/Shoalcade/releases/tag/v";

    /// <summary>
    /// The bundled phone app's version, size and SHA-256; with <c>platform=windows</c> (S2), the Windows app's version
    /// (the same number as the phone app, plan-windows §6) and this release's page.
    /// </summary>
    /// <param name="platform"><c>android</c> (default) or <c>windows</c>.</param>
    /// <returns>Info, or 404 when the admin has turned that app off, or 400 for another platform.</returns>
    [HttpGet("info")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public ActionResult<AppInfoDto> GetInfo([FromQuery] string? platform)
    {
        var windows = string.Equals(platform, "windows", StringComparison.OrdinalIgnoreCase);
        if (!windows && !string.IsNullOrEmpty(platform) && !string.Equals(platform, "android", StringComparison.OrdinalIgnoreCase))
        {
            return Fail(StatusCodes.Status400BadRequest, "bad_platform", "platform must be android or windows.");
        }

        if (windows)
        {
            if (!ShoalcadeServices.Config.OfferWindowsApp)
            {
                return Off();
            }

            if (RequireUser(out _) is ActionResult wfail)
            {
                return wfail;
            }

            Response.Headers.CacheControl = "no-store";
            return DescribeWindows(_app, ServerVersion.Current);
        }

        if (!_app.Enabled)
        {
            return Off();
        }

        if (RequireUser(out _) is ActionResult fail)
        {
            return fail;
        }

        Response.Headers.CacheControl = "no-store";
        return Describe(_app);
    }

    /// <summary>A download link for the QR code, valid 10 minutes for a few downloads.</summary>
    /// <returns>The link.</returns>
    [HttpPost("link")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public ActionResult<AppLinkDto> CreateLink()
    {
        if (!_app.Enabled)
        {
            return Off();
        }

        if (_app.Package is null)
        {
            return NoApp();
        }

        if (RequireUser(out var user) is ActionResult fail)
        {
            return fail;
        }

        Response.Headers.CacheControl = "no-store";
        return new AppLinkDto("/shoalcade/app/" + PhoneApp.FileName + "?ticket=" + _tickets.Issue(user.Id), (int)AppTickets.Lifetime.TotalSeconds, AppTickets.MaxUses);
    }

    /// <summary>The phone app (APK). Needs the Jellyfin login header, or a ticket from <see cref="CreateLink"/>.</summary>
    /// <param name="ticket">Ticket from the QR code, if any.</param>
    /// <returns>The APK.</returns>
    [HttpGet("Shoalcade.apk")] // = PhoneApp.FileName; a literal so the security probe finds it
    [AllowAnonymous] // the login is checked below: a header login OR a ticket
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status401Unauthorized)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<ActionResult> Download([FromQuery] string? ticket)
    {
        var byTicket = ticket is not null;
        if (byTicket)
        {
            if (!_tickets.IsValid(ticket))
            {
                return TicketFail();
            }
        }
        else if (!await SignedInAsync().ConfigureAwait(false))
        {
            return Fail(StatusCodes.Status401Unauthorized, "signed_out", "Sign in to Jellyfin first.");
        }

        if (!_app.Enabled)
        {
            return Off();
        }

        if (_app.Package is not { } p)
        {
            return NoApp();
        }

        if (byTicket && !_tickets.TryUse(ticket))
        {
            return TicketFail();
        }

        FileStream file;
        try
        {
            file = new FileStream(_app.ApkPath, FileMode.Open, FileAccess.Read, FileShare.Read, 81920, FileOptions.Asynchronous | FileOptions.SequentialScan);
        }
        catch (IOException)
        {
            return NoApp();
        }

        Response.Headers.CacheControl = "no-store";
        Response.Headers.ContentDisposition = "attachment; filename=\"" + PhoneApp.FileName + "\"";
        Response.Headers["X-Shoalcade-App-Version"] = p.Version;
        return File(file, PhoneApp.ContentType, enableRangeProcessing: true);
    }

    /// <summary>The info answer for a bundled app (or none).</summary>
    /// <param name="app">The app.</param>
    /// <returns>The DTO.</returns>
    internal static AppInfoDto Describe(PhoneApp app) =>
        app.Package is { } p ? new AppInfoDto(true, true, p.Version, p.Size, p.Sha256) : new AppInfoDto(true, false, null, null, null);

    /// <summary>The Windows info answer: the phone app's version (same number) and this release's GitHub page.</summary>
    /// <param name="app">The bundled phone app (null version if this build has none).</param>
    /// <param name="plugin">This plugin's version (release tag = its first three parts).</param>
    /// <returns>The DTO.</returns>
    internal static AppInfoDto DescribeWindows(PhoneApp app, Version plugin) =>
        new(true, true, app.Package?.Version, null, null, ReleasePage + plugin.ToString(3));

    private async Task<bool> SignedInAsync()
    {
        var policy = await _policies.GetDefaultPolicyAsync().ConfigureAwait(false);
        return User.Identity?.IsAuthenticated == true && (await _authz.AuthorizeAsync(User, null, policy).ConfigureAwait(false)).Succeeded;
    }

    private ObjectResult Off() => Fail(StatusCodes.Status404NotFound, "app_off", "Your Jellyfin admin has turned this off.");

    private ObjectResult NoApp() => Fail(StatusCodes.Status404NotFound, "no_app", "The phone app isn't included in this build of the plugin.");

    private ObjectResult TicketFail() => Fail(StatusCodes.Status401Unauthorized, "link_expired", "This download link has run out. Make a new QR code on the Settings page.");
}
