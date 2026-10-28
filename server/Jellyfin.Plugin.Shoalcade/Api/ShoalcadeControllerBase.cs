using System;
using System.IO;
using System.Linq;
using System.Net.Mime;
using System.Security.Cryptography;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Database.Implementations.Entities;
using Jellyfin.Plugin.Shoalcade.Common;
using Jellyfin.Plugin.Shoalcade.Library;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;

namespace Jellyfin.Plugin.Shoalcade.Api;

/// <summary>
/// Base for every Shoalcade API controller. <b>Every route needs a Jellyfin login</b> ([Authorize] = Jellyfin's default
/// policy: valid token, account enabled, remote-access and schedule rules). On top of that each request must belong to
/// a real user (API keys have none and are refused), and users only ever reach their own saves and settings.
/// </summary>
[ApiController]
[Authorize]
[ApiSecurityHeaders]
[Produces(MediaTypeNames.Application.Json)]
public abstract class ShoalcadeControllerBase : ControllerBase
{
    /// <summary>Jellyfin's user id claim (Jellyfin.Api InternalClaimTypes.UserId).</summary>
    public const string UserIdClaim = "Jellyfin-UserId";

    /// <summary>Most refused-upload bodies read (and thrown away) at once, server-wide (G5 s0.2 L3). Beyond that the
    /// 429 goes out straight away with <c>Connection: close</c>.</summary>
    internal const int MaxDrains = 4;

    /// <summary>Most bytes read from one refused upload's body (G5 s0.2 L3); a bigger body gets the 429 and a close.</summary>
    internal const long MaxDrainBytes = 1024 * 1024;

    /// <summary>Longest time spent reading (and throwing away) the body of an upload refused as busy (G5 s0.2 L3).</summary>
    internal static readonly TimeSpan RefusedBodyTimeout = TimeSpan.FromSeconds(15);

    /// <summary>Drains going now (a global cap: keyed on one id).</summary>
    private static readonly UploadLimiter Drains = new(MaxDrains);

    /// <summary>Initializes a new instance of the <see cref="ShoalcadeControllerBase"/> class.</summary>
    /// <param name="library">ROM library.</param>
    /// <param name="services">Stores.</param>
    protected ShoalcadeControllerBase(RomLibrary library, ShoalcadeServices services)
    {
        Library = library;
        Services = services;
    }

    /// <summary>Gets the ROM library.</summary>
    protected RomLibrary Library { get; }

    /// <summary>Gets the stores.</summary>
    protected ShoalcadeServices Services { get; }

    /// <summary>Gets "client / device" from the login, for the save history.</summary>
    protected string? DeviceLabel
    {
        get
        {
            var client = User.FindFirst("Jellyfin-Client")?.Value;
            var device = User.FindFirst("Jellyfin-Device")?.Value;
            var s = string.Join(" / ", new[] { client, device }.Where(x => !string.IsNullOrWhiteSpace(x)));
            return s.Length == 0 ? null : s;
        }
    }

    /// <summary>The signed-in user, or an error result. API keys (no user) get 403.</summary>
    /// <param name="user">The user.</param>
    /// <returns>Null if OK, else the error to return.</returns>
    protected ActionResult? RequireUser(out User user)
    {
        user = null!;
        var raw = User.FindFirst(UserIdClaim)?.Value;
        if (!Guid.TryParse(raw, out var id) || id == Guid.Empty)
        {
            return Fail(StatusCodes.Status403Forbidden, "no_user", "Sign in with a Jellyfin user account (API keys can't be used here).");
        }

        var u = Library.GetUser(id);
        if (u is null)
        {
            return Fail(StatusCodes.Status403Forbidden, "no_user", "Your Jellyfin account wasn't found.");
        }

        user = u;
        return null;
    }

    /// <summary>The ROM if the user can see it, else a 404 (same answer for "doesn't exist", "hidden" and "not a ROM").</summary>
    /// <param name="itemId">Item id.</param>
    /// <param name="user">User.</param>
    /// <param name="rom">The ROM.</param>
    /// <returns>Null if OK, else the 404.</returns>
    protected ActionResult? RequireRom(Guid itemId, User user, out RomItem rom)
    {
        rom = null!;
        var r = Library.GetRom(itemId, user);
        if (r is null)
        {
            return GameNotFound();
        }

        rom = r;
        return null;
    }

    /// <summary>The standard "game not found" 404.</summary>
    /// <returns>The result.</returns>
    protected ObjectResult GameNotFound() => Fail(StatusCodes.Status404NotFound, "not_found", "Game not found.");

    /// <summary>A JSON error.</summary>
    /// <param name="status">HTTP status.</param>
    /// <param name="code">Code.</param>
    /// <param name="message">Message.</param>
    /// <returns>The result.</returns>
    protected ObjectResult Fail(int status, string code, string message) => StatusCode(status, new ApiError(code, message));

    /// <summary>
    /// Starts an upload for the user, or gives the 429 to return if they already have too many going (M1).
    /// A refused upload's body is read and thrown away first (never kept: one small buffer). Answering before the body
    /// has arrived let Kestrel reset the connection once the rest took over ~5 s to come in (a slow phone link, a busy
    /// server), so the app saw "connection reset" instead of "busy" (the flaky "12 parallel uploads" test, 24/10/2026).
    /// Only a small body is drained (up to <see cref="MaxDrainBytes"/> or <paramref name="maxBytes"/>, within
    /// <see cref="RefusedBodyTimeout"/>, at most <see cref="MaxDrains"/> at once server-wide, G5 s0.2 L3); anything
    /// bigger, slower or over that count isn't read: the 429 then closes the connection.
    /// </summary>
    /// <param name="user">User id.</param>
    /// <param name="maxBytes">Largest body this route accepts (the most that is ever read for a refusal).</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>The slot to dispose when the request is done, and null or the error to return.</returns>
    protected async Task<(IDisposable Slot, ActionResult? Busy)> BeginUploadAsync(Guid user, long maxBytes, CancellationToken cancellationToken)
    {
        if (UploadLimiter.Shared.TryEnter(user) is IDisposable slot)
        {
            return (slot, null);
        }

        await DiscardBodyAsync(maxBytes, cancellationToken).ConfigureAwait(false);
        return (NoSlot.Instance, Fail(StatusCodes.Status429TooManyRequests, "busy", "Too many uploads at once. Wait a moment and try again."));
    }

    /// <summary>Reads the raw request body, refusing anything over <paramref name="maxBytes"/> (413) before reading it all.
    /// Only for small bodies (settings, pictures): saves use <see cref="ReadBodyViaFileAsync"/> and snapshots <see cref="ReadBodyToFileAsync"/>.</summary>
    /// <param name="maxBytes">Limit.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>The bytes, or the error result.</returns>
    protected async Task<(byte[]? Data, ActionResult? Error)> ReadBodyAsync(long maxBytes, CancellationToken cancellationToken)
    {
        if (Request.ContentLength is long len && len > maxBytes)
        {
            return (null, TooBig());
        }

        LimitBody(maxBytes);

        // Memory grows only as bytes really arrive: never allocated up front from the client's Content-Length (G5 s0.2 L4).
        using var ms = new MemoryStream();
        var buffer = new byte[81920];
        try
        {
            while (true)
            {
                var n = await Request.Body.ReadAsync(buffer, cancellationToken).ConfigureAwait(false);
                if (n == 0)
                {
                    break;
                }

                if (ms.Length + n > maxBytes)
                {
                    return (null, TooBig());
                }

                ms.Write(buffer, 0, n);
            }
        }
        catch (BadHttpRequestException ex)
        {
            return (null, BodyError(ex));
        }

        if (Request.ContentLength is long known && ms.Length != known)
        {
            return (null, Fail(StatusCodes.Status400BadRequest, "short_body", "The upload was cut short."));
        }

        return (ms.ToArray(), null);
    }

    /// <summary>
    /// Reads the raw request body by streaming it to a temp file first (G5 s0.2 L4: a slow or big upload never sits in
    /// memory while it arrives), then loads the finished file, checked against the SHA-256 taken while streaming. Same
    /// size rule as <see cref="ReadBodyAsync"/>. For saves (up to 64 MB); the temp file is always removed.
    /// </summary>
    /// <param name="maxBytes">Limit.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>The bytes, or the error result.</returns>
    protected async Task<(byte[]? Data, ActionResult? Error)> ReadBodyViaFileAsync(long maxBytes, CancellationToken cancellationToken)
    {
        var tmp = Services.States.NewUploadPath();
        try
        {
            var (length, sha, fail) = await ReadBodyToFileAsync(tmp, maxBytes, cancellationToken).ConfigureAwait(false);
            if (fail is not null)
            {
                return (null, fail);
            }

            var data = await System.IO.File.ReadAllBytesAsync(tmp, cancellationToken).ConfigureAwait(false);
            if (data.LongLength != length || !string.Equals(Convert.ToHexStringLower(SHA256.HashData(data)), sha, StringComparison.Ordinal))
            {
                return (null, Fail(StatusCodes.Status500InternalServerError, "upload_damaged", "The upload was damaged on the server. Please try again."));
            }

            return (data, null);
        }
        finally
        {
            TryDeleteFile(tmp);
        }
    }

    /// <summary>
    /// Streams the request body straight into <paramref name="path"/> (a new file, flushed to disk), hashing as it goes,
    /// and stops at <paramref name="maxBytes"/> (M1: big uploads never sit in memory). The file is deleted on any failure.
    /// </summary>
    /// <param name="path">New file to write (must not exist).</param>
    /// <param name="maxBytes">Limit.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>Length and SHA-256 (lower-case hex), or the error result.</returns>
    protected async Task<(long Length, string? Sha256, ActionResult? Error)> ReadBodyToFileAsync(string path, long maxBytes, CancellationToken cancellationToken)
    {
        if (Request.ContentLength is long len && len > maxBytes)
        {
            return (0, null, TooBig());
        }

        LimitBody(maxBytes);
        var ok = false;
        try
        {
            using var hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
            long total = 0;
            var fs = new FileStream(path, FileMode.CreateNew, FileAccess.Write, FileShare.None, 81920, FileOptions.Asynchronous);
            await using (fs.ConfigureAwait(false))
            {
                var buffer = new byte[81920];
                while (true)
                {
                    int n;
                    try
                    {
                        n = await Request.Body.ReadAsync(buffer, cancellationToken).ConfigureAwait(false);
                    }
                    catch (BadHttpRequestException ex)
                    {
                        return (0, null, BodyError(ex));
                    }

                    if (n == 0)
                    {
                        break;
                    }

                    total += n;
                    if (total > maxBytes)
                    {
                        return (0, null, TooBig());
                    }

                    hash.AppendData(buffer, 0, n);
                    await fs.WriteAsync(buffer.AsMemory(0, n), cancellationToken).ConfigureAwait(false);
                }

                await fs.FlushAsync(cancellationToken).ConfigureAwait(false);
                fs.Flush(true);
            }

            ok = true;
            return (total, Convert.ToHexStringLower(hash.GetHashAndReset()), null);
        }
        finally
        {
            if (!ok)
            {
                TryDeleteFile(path);
            }
        }
    }

    /// <summary>Deletes a temp file if it is still there (a failure is left for the next tidy-up).</summary>
    /// <param name="path">File.</param>
    protected static void TryDeleteFile(string path)
    {
        try
        {
            System.IO.File.Delete(path);
        }
        catch (IOException)
        {
            // Left for the next tidy-up.
        }
        catch (UnauthorizedAccessException)
        {
            // Left for the next tidy-up.
        }
    }

    private ObjectResult TooBig() => Fail(StatusCodes.Status413PayloadTooLarge, "too_big", "That file is too big.");

    /// <summary>Kestrel's body error: 413 only when it really was the size limit; bad framing etc. is a 400 (N9).</summary>
    private ObjectResult BodyError(BadHttpRequestException ex) =>
        ex.StatusCode == StatusCodes.Status413PayloadTooLarge ? TooBig() : Fail(StatusCodes.Status400BadRequest, "bad_body", "The upload couldn't be read. Please try again.");

    private async Task DiscardBodyAsync(long maxBytes, CancellationToken cancellationToken)
    {
        var limit = Math.Min(maxBytes, MaxDrainBytes);
        using var drain = Request.ContentLength is long len && len > limit ? null : Drains.TryEnter(Guid.Empty);
        if (drain is null)
        {
            Response.Headers.Connection = "close"; // too big, or too many being drained already: answer straight away
            return;
        }

        LimitBody(maxBytes);
        var buffer = new byte[16 * 1024];
        long total = 0;
        using var cts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        cts.CancelAfter(RefusedBodyTimeout);
        try
        {
            int n;
            while ((n = await Request.Body.ReadAsync(buffer, cts.Token).ConfigureAwait(false)) > 0)
            {
                total += n;
                if (total > limit)
                {
                    Response.Headers.Connection = "close";
                    return;
                }
            }
        }
        catch (Exception ex) when (ex is BadHttpRequestException or IOException || (ex is OperationCanceledException && !cancellationToken.IsCancellationRequested))
        {
            Response.Headers.Connection = "close";
        }
    }

    private void LimitBody(long maxBytes)
    {
        var feature = HttpContext.Features.Get<Microsoft.AspNetCore.Http.Features.IHttpMaxRequestBodySizeFeature>();
        if (feature is { IsReadOnly: false })
        {
            // Kestrel counts chunked framing (size lines, CRLFs) towards this limit, so a chunked upload of exactly
            // maxBytes was refused (found by e2e, 24/10/2026). Leave 1/16 headroom for the framing; the exact cap on the
            // data itself is still enforced by our own byte count in the readers.
            feature.MaxRequestBodySize = Request.ContentLength is null ? maxBytes + (maxBytes / 16) + 1024 : maxBytes + 1;
        }
    }

    private sealed class NoSlot : IDisposable
    {
        public static readonly NoSlot Instance = new();

        public void Dispose()
        {
        }
    }
}
