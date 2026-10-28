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
using Jellyfin.Plugin.Shoalcade.Saves;
using Jellyfin.Plugin.Shoalcade.Web;
using MediaBrowser.Controller.Library;
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

        var ext = Path.GetExtension(rom.Item.Path ?? string.Empty).ToLowerInvariant();
        return rom.Console.Extensions.Contains(ext) || ext is ".zip" or ".7z";
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
            SaveRulesDto.From(ShoalcadeServices.SaveOptions()));
    }

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
        var list = new List<GameDto>(roms.Count);
        foreach (var r in roms.OrderBy(r => r.SortName, StringComparer.OrdinalIgnoreCase))
        {
            list.Add(ToDto(r, user, saves.GetValueOrDefault(r.Id)?.Current));
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
        return ToDto(rom, user, main.CurrentVersion());
    }

    private GameDto ToDto(RomItem r, User user, SaveVersion? save)
    {
        var played = _userData.GetUserData(user, r.Item)?.LastPlayedDate;
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
            SaveVersionDto.From(save, save?.Version));
    }
}
