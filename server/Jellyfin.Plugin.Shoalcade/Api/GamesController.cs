using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Data;
using Jellyfin.Database.Implementations.Entities;
using Jellyfin.Database.Implementations.Enums;
using Jellyfin.Plugin.Shoalcade.Common;
using Jellyfin.Plugin.Shoalcade.Consoles;
using Jellyfin.Plugin.Shoalcade.Import;
using Jellyfin.Plugin.Shoalcade.Library;
using Jellyfin.Plugin.Shoalcade.PlayTime;
using Jellyfin.Plugin.Shoalcade.Saves;
using Jellyfin.Plugin.Shoalcade.Web;
using MediaBrowser.Controller.Library;
using MediaBrowser.Model.Entities;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;

namespace Jellyfin.Plugin.Shoalcade.Api;

/// <summary>Server info and the game list.</summary>
[Route("shoalcade")]
public class GamesController : ShoalcadeControllerBase
{
    /// <summary>API level. Bump on any breaking change to these routes (app and plugin change in the same commit).</summary>
    public const int ApiLevel = 1;

    /// <summary>Oldest app that works with this server (D41).</summary>
    public const string MinAppVersion = "a0.5";

    private readonly IUserDataManager _userData;
    private readonly WebAssets _web;

    /// <summary>Initializes a new instance of the <see cref="GamesController"/> class.</summary>
    /// <param name="library">ROM library.</param>
    /// <param name="services">Stores.</param>
    /// <param name="userData">Jellyfin play data.</param>
    /// <param name="web">Web assets.</param>
    public GamesController(RomLibrary library, ShoalcadeServices services, IUserDataManager userData, WebAssets web)
        : base(library, services)
    {
        _userData = userData;
        _web = web;
    }

    /// <summary>True if a game can be played in the browser (console supported and the item is one file).</summary>
    /// <param name="rom">ROM.</param>
    /// <param name="emulatorAvailable">EmulatorJS installed.</param>
    /// <returns>Whether it is browser-playable.</returns>
    internal static bool IsBrowserPlayable(RomItem rom, bool emulatorAvailable)
    {
        if (!emulatorAvailable || rom.Console.BrowserCore is null || !ShoalcadeServices.Config.EnableBrowserPlayer)
        {
            return false;
        }

        // A disc set plays its first disc in the browser (D-8; EmulatorJS gets one file).
        var discs = DiscFiles(rom);
        var ext = Path.GetExtension(discs.Count > 0 ? discs[0] : rom.Item.Path ?? string.Empty).ToLowerInvariant();
        return rom.Console.Extensions.Contains(ext) || ext is ".zip" or ".7z";
    }

    /// <summary>The discs of a disc-set game (D-8), in play order; empty for a one-file game.</summary>
    /// <param name="rom">ROM.</param>
    /// <returns>Full paths.</returns>
    internal static IReadOnlyList<string> DiscFiles(RomItem rom)
    {
        var path = rom.Item.Path;
        return string.IsNullOrEmpty(path) || !rom.Console.TakesDiscSets
            || rom.Console.Extensions.Contains(Path.GetExtension(path).ToLowerInvariant())
            ? [] : Scanner.RomScanner.DiscSet(path, rom.Console);
    }

    /// <summary>The file names the discs are served under: their own, made unique within the set.</summary>
    /// <param name="discs">Disc paths.</param>
    /// <returns>Names, same order.</returns>
    internal static IReadOnlyList<string> DiscNames(IReadOnlyList<string> discs)
    {
        var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        return discs.Select((p, i) =>
        {
            var name = Path.GetFileName(p);
            return seen.Add(name) ? name : (i + 1).ToString(System.Globalization.CultureInfo.InvariantCulture) + " - " + name;
        }).ToList();
    }

    /// <summary>
    /// A disc-set game's discs (D-8): names and sizes, plus a .m3u naming them. Needs a login, the game in the user's
    /// libraries and Jellyfin's download permission, as Jellyfin's own download route does.
    /// </summary>
    /// <param name="id">Item id.</param>
    /// <returns>The discs.</returns>
    [HttpGet("games/{id:guid}/discs")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    [ProducesResponseType(StatusCodes.Status409Conflict)]
    [ProducesResponseType(StatusCodes.Status503ServiceUnavailable)]
    public ActionResult<DiscSetDto> GetDiscs([FromRoute] Guid id)
    {
        if (DiscSetFor(id, out var discs) is ActionResult fail)
        {
            return fail;
        }

        var names = DiscNames(discs);
        List<DiscDto> list;
        try
        {
            list = discs.Select((p, i) => new DiscDto(i, names[i], new FileInfo(p).Length)).ToList();
        }
        catch (Exception ex) when (ex is FileNotFoundException or DirectoryNotFoundException)
        {
            return DiscGone(); // a disc went while this was answered (S12): not a 500
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return CantReadGame();
        }

        Response.Headers.CacheControl = "no-store";
        return new DiscSetDto(list, string.Concat(names.Select(n => n + "\n")));
    }

    private ObjectResult DiscGone() =>
        Fail(StatusCodes.Status409Conflict, "disc_gone", "This game's discs have just changed on the server. Try again.");

    /// <summary>One disc of a disc-set game (D-8), as a download. Range requests work, but the apps start a dropped
    /// disc download again from the beginning (they don't resume).</summary>
    /// <param name="id">Item id.</param>
    /// <param name="index">Disc index from /discs (0 = the first disc).</param>
    /// <returns>The disc file.</returns>
    [HttpGet("games/{id:guid}/discs/{index:int}")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    [ProducesResponseType(StatusCodes.Status409Conflict)]
    [ProducesResponseType(StatusCodes.Status503ServiceUnavailable)]
    public ActionResult GetDisc([FromRoute] Guid id, [FromRoute] int index)
    {
        if (DiscSetFor(id, out var discs) is ActionResult fail)
        {
            return fail;
        }

        if (index < 0 || index >= discs.Count)
        {
            return Fail(StatusCodes.Status404NotFound, "no_disc", "This game has no such disc.");
        }

        if (!System.IO.File.Exists(discs[index]))
        {
            // Gone since the set was read, or (folder unreadable too) asleep: a clean answer, not a 500 (S12).
            return Directory.Exists(Path.GetDirectoryName(discs[index])) ? DiscGone() : CantReadGame();
        }

        Response.Headers.CacheControl = "no-store";
        return PhysicalFile(discs[index], "application/octet-stream", DiscNames(discs)[index], enableRangeProcessing: true);
    }

    private ActionResult? DiscSetFor(Guid id, out IReadOnlyList<string> discs)
    {
        discs = [];
        if (RequireUser(out var user) is ActionResult fail)
        {
            return fail;
        }

        if (RequireRom(id, user, out var rom) is ActionResult notFound)
        {
            return notFound;
        }

        if (!user.HasPermission(PermissionKind.EnableContentDownloading))
        {
            return Fail(StatusCodes.Status403Forbidden, "no_download", "Your Jellyfin account isn't allowed to download. Ask the server admin.");
        }

        discs = DiscFiles(rom);
        return discs.Count == 0 ? Fail(StatusCodes.Status404NotFound, "not_a_set", "This game is one file, not a disc set.") : null;
    }

    /// <summary>Server version and what it supports. The app checks this (D41).</summary>
    /// <returns>Info.</returns>
    [HttpGet("info")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    public ActionResult<InfoDto> GetInfo()
    {
        if (RequireUser(out var user) is ActionResult fail)
        {
            return fail;
        }

        var v = ServerVersion.Current;
        return new InfoDto(
            ServerVersion.Name(v),
            v.ToString(),
            ServerVersion.Display(v),
            ApiLevel,
            MinAppVersion,
            _web.EmulatorAvailable ? WebAssets.EmulatorJsVersion : null,
            ShoalcadeServices.Config.EnableBrowserPlayer && _web.EmulatorAvailable,
            GameConsole.All.Select(ConsoleDto.From).ToList(),
            new UserDto(user.Id.ToString("N"), user.Username, user.HasPermission(PermissionKind.EnableContentDownloading), user.HasPermission(PermissionKind.IsAdministrator)),
            SaveRulesDto.From(ShoalcadeServices.SaveOptions()),
            Features);
    }

    /// <summary>Gets what this server can do beyond its API level, so newer apps can offer it and older ones ignore it.
    /// "cheats": the cheats routes (D143). "favourites": the favourite flag on games and the route to set it.
    /// "playtime": <c>playSeconds</c> on games and the route to add play sessions.
    /// "link-relay": internet link play rooms under <c>shoalcade/link</c> (<see cref="LinkController"/>).</summary>
    internal static IReadOnlyList<string> Features { get; } = ["cheats", "favourites", "playtime", "link-relay"];

    /// <summary>Every game the user can see, with their current save and last played time.</summary>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>Games.</returns>
    [HttpGet("games")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    public async Task<ActionResult<IReadOnlyList<GameDto>>> GetGames(CancellationToken cancellationToken)
    {
        if (RequireUser(out var user) is ActionResult fail)
        {
            return fail;
        }

        var roms = Library.ListRoms(user);
        var saves = (await Services.Saves.ListAsync(user.Id, cancellationToken).ConfigureAwait(false))
            .ToDictionary(s => s.ItemId);
        var played = await Services.PlayTime.ListAsync(user.Id, cancellationToken).ConfigureAwait(false);
        var list = new List<GameDto>(roms.Count);
        foreach (var r in roms.OrderBy(r => r.SortName, StringComparer.OrdinalIgnoreCase))
        {
            list.Add(ToDto(r, user, saves.GetValueOrDefault(r.Id)?.Current, played.GetValueOrDefault(r.Id)));
        }

        return list;
    }

    /// <summary>One game.</summary>
    /// <param name="id">Item id.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>The game.</returns>
    [HttpGet("games/{id:guid}")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<ActionResult<GameDto>> GetGame([FromRoute] Guid id, CancellationToken cancellationToken)
    {
        if (RequireUser(out var user) is ActionResult fail)
        {
            return fail;
        }

        if (RequireRom(id, user, out var rom) is ActionResult notFound)
        {
            return notFound;
        }

        await Services.JellyEmu.SyncAsync(user.Id, rom.Id, rom.Console, rom.Name, cancellationToken).ConfigureAwait(false);
        var main = await Services.Saves.GetHistoryAsync(user.Id, rom.Id, cancellationToken).ConfigureAwait(false);
        var seconds = await Services.PlayTime.GetAsync(user.Id, rom.Id, cancellationToken).ConfigureAwait(false);
        return ToDto(rom, user, main.CurrentVersion(), seconds);
    }

    /// <summary>
    /// Stars or unstars a game for the signed-in user. Kept in Jellyfin's own favourites, so the phone app, the browser
    /// library and Jellyfin itself all show the same stars.
    /// </summary>
    /// <param name="id">Item id.</param>
    /// <param name="on">True to star, false to unstar.</param>
    /// <returns>204 when done.</returns>
    [HttpPost("games/{id:guid}/favourite")]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public ActionResult SetFavourite([FromRoute] Guid id, [FromQuery] bool on)
    {
        if (RequireUser(out var user) is ActionResult fail)
        {
            return fail;
        }

        if (RequireRom(id, user, out var rom) is ActionResult notFound)
        {
            return notFound;
        }

        var data = _userData.GetUserData(user, rom.Item);
        if (data is not null && data.IsFavorite != on)
        {
            data.IsFavorite = on;
            _userData.SaveUserData(user, rom.Item, data, UserDataSaveReason.UpdateUserRating, CancellationToken.None);
        }

        return NoContent();
    }

    /// <summary>
    /// Adds finished play sessions to the signed-in user's play time on a game: <c>{ "sessions": [ { "id", "seconds",
    /// "endedAt" } ] }</c>, 1 to 100 at once (a phone that was offline sends several). Each session id counts once only,
    /// so a resend after a lost answer never adds twice. Every device adds to the same total.
    /// </summary>
    /// <param name="id">Item id.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>200 with the new total, 404, 422 bad sessions, 429 busy.</returns>
    [HttpPost("games/{id:guid}/playtime")]
    [Consumes("application/json")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    [ProducesResponseType(StatusCodes.Status422UnprocessableEntity)]
    public async Task<ActionResult<PlayTimeResultDto>> AddPlayTime([FromRoute] Guid id, CancellationToken cancellationToken)
    {
        if (RequireUser(out var user) is ActionResult fail)
        {
            return fail;
        }

        if (RequireRom(id, user, out var rom) is ActionResult notFound)
        {
            return notFound;
        }

        var (uploadSlot, busy) = await BeginUploadAsync(user.Id, PlayTimeStore.MaxBodyBytes, cancellationToken).ConfigureAwait(false);
        if (busy is not null)
        {
            return busy;
        }

        using var slotHandle = uploadSlot;
        var (data, bodyFail) = await ReadBodyAsync(PlayTimeStore.MaxBodyBytes, cancellationToken).ConfigureAwait(false);
        if (bodyFail is not null)
        {
            return bodyFail;
        }

        PlayTimeAddDto? body = null;
        try
        {
            body = System.Text.Json.JsonSerializer.Deserialize<PlayTimeAddDto>(data!);
        }
        catch (System.Text.Json.JsonException)
        {
            // handled below
        }

        if (body is null)
        {
            return Fail(StatusCodes.Status422UnprocessableEntity, "bad_json", "The request wasn't valid.");
        }

        if (PlayTimeStore.Validate(body.Sessions) is string reason)
        {
            return Fail(StatusCodes.Status422UnprocessableEntity, "bad_sessions", reason);
        }

        var (total, added) = await Services.PlayTime.AddAsync(user.Id, rom.Id, body.Sessions!.Select(s => s!), cancellationToken).ConfigureAwait(false);
        Response.Headers.CacheControl = "no-store";
        return new PlayTimeResultDto(total, added);
    }

    private GameDto ToDto(RomItem r, User user, SaveVersion? save, long playSeconds)
    {
        var data = _userData.GetUserData(user, r.Item);
        var played = data?.LastPlayedDate;
        return new GameDto(
            r.Id.ToString("N"),
            r.Name,
            r.SortName,
            r.Console.Key,
            r.Region,
            r.Series,
            r.Year,
            r.ImageTag,
            played,
            IsBrowserPlayable(r, _web.EmulatorAvailable),
            SaveVersionDto.From(save, save?.Version),
            data?.IsFavorite == true,
            playSeconds,
            DiscFiles(r).Count);
    }
}
