using System;
using System.IO;
using System.Text.Json.Serialization;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Plugin.Shoalcade.Library;
using Jellyfin.Plugin.Shoalcade.Link;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Http.Features;
using Microsoft.AspNetCore.Mvc;

namespace Jellyfin.Plugin.Shoalcade.Api;

/// <summary>Body for opening a room or joining one.</summary>
public sealed class LinkRoomRequest
{
    /// <summary>Gets or sets the console, e.g. "GBA" (only GBA for now).</summary>
    [JsonPropertyName("console")]
    public string? Console { get; set; }

    /// <summary>Gets or sets the room code the host phone shows (join only).</summary>
    [JsonPropertyName("code")]
    public string? Code { get; set; }
}

/// <summary>A seat in a link room. <paramref name="Seat"/> goes in the <c>X-Shoalcade-Seat</c> header of every later call.</summary>
/// <param name="Code">Room code (the host shows it; the joiner types it).</param>
/// <param name="Seat">Seat token (secret to this phone).</param>
/// <param name="Gen">The joiner's generation (0 for the host until someone joins).</param>
/// <param name="HeartbeatSeconds">Longest quiet time on the down stream.</param>
/// <param name="ExpiresInSeconds">How long the room waits for the second phone.</param>
public sealed record LinkSeatDto(
    [property: JsonPropertyName("code")] string Code,
    [property: JsonPropertyName("seat")] string Seat,
    [property: JsonPropertyName("gen")] int Gen,
    [property: JsonPropertyName("heartbeatSeconds")] int HeartbeatSeconds,
    [property: JsonPropertyName("expiresInSeconds")] int ExpiresInSeconds);

/// <summary>
/// Internet link play through this Jellyfin (feature "link-relay"). Two phones logged in to this server swap their link
/// bytes: the host opens a room and shows its code, the joiner types it, and the phones' own PIN handshake (D133) then
/// runs end to end over the pipe. <b>Every route needs a Jellyfin login</b>, and every seat route also needs the seat
/// token, in a header (never the URL), from the same user. Data goes down one long streaming GET (records, see
/// <see cref="DownStream"/>; <c>X-Accel-Buffering: no</c>, a heartbeat every 15 s) and up in small numbered POSTs.
/// Link bytes are never read, kept on disk or logged.
/// </summary>
[Route("shoalcade/link")]
public class LinkController : ShoalcadeControllerBase
{
    /// <summary>The seat token header.</summary>
    public const string SeatHeader = "X-Shoalcade-Seat";

    /// <summary>Down stream: the byte offset the phone has got up to (to carry on after a dropped connection).</summary>
    public const string FromHeader = "X-Shoalcade-From";

    /// <summary>Upload sequence number (1, 2, 3…; a repeat is a safe retry).</summary>
    public const string SeqHeader = "X-Shoalcade-Seq";

    /// <summary>The generation the upload is for (the joiner it is meant for).</summary>
    public const string GenHeader = "X-Shoalcade-Gen";

    private readonly LinkRooms _rooms;

    /// <summary>Initializes a new instance of the <see cref="LinkController"/> class.</summary>
    /// <param name="library">ROM library.</param>
    /// <param name="services">Stores.</param>
    /// <param name="rooms">Link rooms.</param>
    public LinkController(RomLibrary library, ShoalcadeServices services, LinkRooms rooms)
        : base(library, services)
    {
        _rooms = rooms;
    }

    /// <summary>Opens a room (closing any room this user already hosts).</summary>
    /// <param name="body">{"console":"GBA"}.</param>
    /// <returns>The host's seat and the room code.</returns>
    [HttpPost("rooms")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    public ActionResult<LinkSeatDto> Create([FromBody] LinkRoomRequest? body)
    {
        if (RequireUser(out var user) is ActionResult fail)
        {
            return fail;
        }

        if (ConsoleKey(body?.Console) is not string console)
        {
            return NotGba();
        }

        var (seat, error) = _rooms.Create(user.Id, console);
        if (seat is null)
        {
            return Refusal(error);
        }

        NoStore();
        return Dto(seat);
    }

    /// <summary>Takes the second seat in a room.</summary>
    /// <param name="body">{"code":"K7QM2P","console":"GBA"}.</param>
    /// <returns>The joiner's seat.</returns>
    [HttpPost("join")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    public ActionResult<LinkSeatDto> Join([FromBody] LinkRoomRequest? body)
    {
        if (RequireUser(out var user) is ActionResult fail)
        {
            return fail;
        }

        if (ConsoleKey(body?.Console) is not string console)
        {
            return NotGba();
        }

        var (seat, error) = _rooms.Join(user.Id, body?.Code, console);
        if (seat is null)
        {
            return Refusal(error);
        }

        NoStore();
        return Dto(seat);
    }

    /// <summary>The seat's down stream: records as they arrive, flushed one by one, until the room closes.</summary>
    /// <param name="cancellationToken">Ends when the phone hangs up.</param>
    /// <returns>A long <c>application/octet-stream</c> answer.</returns>
    [HttpGet("down")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    public async Task<ActionResult> Down(CancellationToken cancellationToken)
    {
        if (RequireUser(out var user) is ActionResult fail)
        {
            return fail;
        }

        if (_rooms.Find(user.Id, Request.Headers[SeatHeader]) is not LinkSeat seat)
        {
            return NoSeat();
        }

        long from = 0;
        var fromHeader = Request.Headers[FromHeader].ToString();
        if (fromHeader.Length > 0 && (!long.TryParse(fromHeader, System.Globalization.NumberStyles.None, System.Globalization.CultureInfo.InvariantCulture, out from) || from < 0))
        {
            return Fail(StatusCodes.Status400BadRequest, "bad_offset", "Bad stream position.");
        }

        var (reader, error) = _rooms.OpenDown(seat, from);
        if (error is not null)
        {
            return error == "gone"
                ? Fail(StatusCodes.Status409Conflict, "gone", "The link data from before the connection dropped is gone.")
                : Refusal(error);
        }

        Response.StatusCode = StatusCodes.Status200OK;
        Response.ContentType = "application/octet-stream";
        Response.Headers.CacheControl = "no-store";
        Response.Headers["X-Accel-Buffering"] = "no"; // nginx (and Nginx Proxy Manager): pass each write straight on
        Response.Headers["X-Content-Type-Options"] = "nosniff";
        Response.Headers["Content-Security-Policy"] = WebController.NonHtmlContentSecurityPolicy;
        HttpContext.Features.Get<IHttpResponseBodyFeature>()?.DisableBuffering();
        try
        {
            await Response.StartAsync(cancellationToken).ConfigureAwait(false);
            await seat.Down.PumpAsync(
                reader,
                from,
                async (chunk, ct) =>
                {
                    await Response.Body.WriteAsync(chunk, ct).ConfigureAwait(false);
                    await Response.Body.FlushAsync(ct).ConfigureAwait(false);
                },
                _rooms.Options.Heartbeat,
                _rooms.Sweep,
                cancellationToken).ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            // The phone hung up (it reconnects with X-Shoalcade-From if it still wants the link).
        }
        catch (IOException)
        {
            // Same: the connection went.
        }

        return new EmptyResult();
    }

    /// <summary>
    /// Sends bytes to the other phone (up to 64 KB). Headers: seat, <c>X-Shoalcade-Seq</c> (one more than the last; a
    /// repeat is ignored, so a retry is safe) and <c>X-Shoalcade-Gen</c>. An empty body only says "still here".
    /// </summary>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>204, or an error.</returns>
    [HttpPost("up")]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    public async Task<ActionResult> Up(CancellationToken cancellationToken)
    {
        if (RequireUser(out var user) is ActionResult fail)
        {
            return fail;
        }

        if (_rooms.Find(user.Id, Request.Headers[SeatHeader]) is not LinkSeat seat)
        {
            return NoSeat();
        }

        var (data, bodyError) = await ReadBodyAsync(_rooms.Options.MaxPostBytes, cancellationToken).ConfigureAwait(false);
        if (bodyError is not null)
        {
            return bodyError;
        }

        if (data!.Length == 0)
        {
            return NoContent(); // keep-alive: Find has marked the seat as seen
        }

        if (!long.TryParse(Request.Headers[SeqHeader].ToString(), System.Globalization.NumberStyles.None, System.Globalization.CultureInfo.InvariantCulture, out var seq)
            || !int.TryParse(Request.Headers[GenHeader].ToString(), System.Globalization.NumberStyles.None, System.Globalization.CultureInfo.InvariantCulture, out var gen))
        {
            return Fail(StatusCodes.Status400BadRequest, "bad_seq", "Missing sequence or generation.");
        }

        return _rooms.Up(seat, seq, gen, data) switch
        {
            UpOutcome.Sent or UpOutcome.Duplicate => NoContent(),
            UpOutcome.PeerGone => Fail(StatusCodes.Status409Conflict, "peer_gone", "The other phone left."),
            UpOutcome.BadSequence => Fail(StatusCodes.Status409Conflict, "bad_seq", "Link data arrived out of order."),
            UpOutcome.TooFast => Fail(StatusCodes.Status429TooManyRequests, "too_fast", "Too much link data at once. Try again in a moment."),
            _ => RoomClosed(),
        };
    }

    /// <summary>The host sends the joiner of a generation away (wrong PIN, not a Shoalcade phone); the seat is free again.</summary>
    /// <returns>204.</returns>
    [HttpPost("kick")]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    public ActionResult Kick()
    {
        if (RequireUser(out var user) is ActionResult fail)
        {
            return fail;
        }

        if (_rooms.Find(user.Id, Request.Headers[SeatHeader]) is not LinkSeat seat)
        {
            return NoSeat();
        }

        if (!int.TryParse(Request.Headers[GenHeader].ToString(), System.Globalization.NumberStyles.None, System.Globalization.CultureInfo.InvariantCulture, out var gen))
        {
            return Fail(StatusCodes.Status400BadRequest, "bad_seq", "Missing generation.");
        }

        _rooms.Kick(seat, gen);
        return NoContent();
    }

    /// <summary>Leaves: a joiner frees its seat, the host closes the room.</summary>
    /// <returns>204.</returns>
    [HttpDelete("seat")]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    public ActionResult Leave()
    {
        if (RequireUser(out var user) is ActionResult fail)
        {
            return fail;
        }

        if (_rooms.Find(user.Id, Request.Headers[SeatHeader]) is LinkSeat seat)
        {
            _rooms.Leave(seat);
        }

        return NoContent(); // already gone is fine too
    }

    /// <summary>Only GBA link play goes over the internet for now (DS wireless needs the low latency of local Wi-Fi).</summary>
    private static string? ConsoleKey(string? console) =>
        string.Equals(console, "gba", StringComparison.OrdinalIgnoreCase) ? "gba" : null;

    private LinkSeatDto Dto(LinkSeat seat) => new(
        seat.Room.Code,
        seat.Token,
        seat.Gen,
        (int)_rooms.Options.Heartbeat.TotalSeconds,
        (int)_rooms.Options.UnjoinedLifetime.TotalSeconds);

    private void NoStore() => Response.Headers.CacheControl = "no-store";

    private ObjectResult NotGba() =>
        Fail(StatusCodes.Status422UnprocessableEntity, "not_supported", "Internet link play is for Game Boy Advance games only for now.");

    private ObjectResult NoSeat() =>
        Fail(StatusCodes.Status404NotFound, "no_seat", "That link has ended.");

    private ObjectResult RoomClosed() =>
        Fail(StatusCodes.Status410Gone, "room_closed", "That link has ended.");

    private ObjectResult Refusal(string? error) => error switch
    {
        "too_many" => Fail(StatusCodes.Status429TooManyRequests, "too_many", "Too many tries. Wait a minute and try again."),
        "busy" => Fail(StatusCodes.Status503ServiceUnavailable, "busy", "Jellyfin has too many link rooms open. Try again later."),
        "no_room" => Fail(StatusCodes.Status404NotFound, "no_room", "That room code wasn't found. Check it on the host phone."),
        "other_console" => Fail(StatusCodes.Status409Conflict, "other_console", "That room is for a different console."),
        "full" => Fail(StatusCodes.Status409Conflict, "full", "That room already has two phones."),
        _ => RoomClosed(),
    };
}
