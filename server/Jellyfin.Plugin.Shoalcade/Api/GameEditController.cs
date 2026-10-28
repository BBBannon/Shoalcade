using System;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Plugin.Shoalcade.Library;
using Jellyfin.Plugin.Shoalcade.Scanner;
using MediaBrowser.Common.Api;
using MediaBrowser.Controller.Providers;
using MediaBrowser.Model.Entities;
using MediaBrowser.Model.IO;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;

namespace Jellyfin.Plugin.Shoalcade.Api;

/// <summary>
/// Admin game editor (D122): rename a game, upload or find its cover, or put it back to automatic. <b>Jellyfin
/// administrators only</b> (Jellyfin's own "RequiresElevation" policy, on top of the normal login every route needs):
/// anyone else gets 403. Only ROM items the admin can see are ever touched (anything else is 404). Changes are kept in
/// the plugin's data folder and on Jellyfin's item, never next to the ROMs, and are locked against scans.
/// </summary>
[Authorize(Policy = Policies.RequiresElevation)]
[Route("shoalcade/admin/games/{id:guid}")]
public class GameEditController : ShoalcadeControllerBase
{
    private readonly GameEditor _editor;
    private readonly CoverFinder _finder;
    private readonly IFileSystem _fileSystem;

    /// <summary>Initializes a new instance of the <see cref="GameEditController"/> class.</summary>
    /// <param name="library">ROM library.</param>
    /// <param name="services">Stores.</param>
    /// <param name="editor">Game editor.</param>
    /// <param name="finder">libretro cover search.</param>
    /// <param name="fileSystem">File system.</param>
    public GameEditController(RomLibrary library, ShoalcadeServices services, GameEditor editor, CoverFinder finder, IFileSystem fileSystem)
        : base(library, services)
    {
        _editor = editor;
        _finder = finder;
        _fileSystem = fileSystem;
    }

    /// <summary>The game as the editor sees it.</summary>
    /// <param name="id">Item id.</param>
    /// <returns>The game.</returns>
    [HttpGet]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public ActionResult<GameEditDto> Get([FromRoute] Guid id)
    {
        if (Find(id, out _, out var rom) is ActionResult fail)
        {
            return fail;
        }

        return State(rom);
    }

    /// <summary>Renames the game; the name is locked so scans and "Replace all metadata" keep it.</summary>
    /// <param name="id">Item id.</param>
    /// <param name="body">{ "name": "..." }.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>The game, or 422 with the reason.</returns>
    [HttpPut("name")]
    [Consumes("application/json")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status422UnprocessableEntity)]
    public async Task<ActionResult<GameEditDto>> Rename([FromRoute] Guid id, [FromBody] GameEditNameDto body, CancellationToken cancellationToken)
    {
        if (Find(id, out var user, out var rom) is ActionResult fail)
        {
            return fail;
        }

        if (GameEditor.CheckName(body?.Name, out var name) is string err)
        {
            return Fail(StatusCodes.Status422UnprocessableEntity, "bad_name", err);
        }

        await _editor.RenameAsync(rom.Item, name, user.Id, cancellationToken).ConfigureAwait(false);
        return Fresh(id, user);
    }

    /// <summary>Uploads a cover: the body is the picture (PNG, JPEG or WebP, at most 10 MB, checked by its own bytes).</summary>
    /// <param name="id">Item id.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>The game, 413 if too big, 422 if not a usable picture.</returns>
    [HttpPut("cover")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status413PayloadTooLarge)]
    [ProducesResponseType(StatusCodes.Status422UnprocessableEntity)]
    public async Task<ActionResult<GameEditDto>> UploadCover([FromRoute] Guid id, CancellationToken cancellationToken)
    {
        if (Find(id, out var user, out var rom) is ActionResult fail)
        {
            return fail;
        }

        var (slot, busy) = await BeginUploadAsync(user.Id, CoverImage.MaxBytes, cancellationToken).ConfigureAwait(false);
        if (busy is not null)
        {
            return busy;
        }

        using var slotHandle = slot;
        var (data, bodyFail) = await ReadBodyAsync(CoverImage.MaxBytes, cancellationToken).ConfigureAwait(false);
        if (bodyFail is not null)
        {
            return bodyFail;
        }

        if (CoverImage.Check(data, out var info) is string err)
        {
            return Fail(StatusCodes.Status422UnprocessableEntity, "bad_picture", err);
        }

        await _editor.SetCoverAsync(rom.Item, data!, info!, "upload", null, user.Id, cancellationToken).ConfigureAwait(false);
        return Fresh(id, user);
    }

    /// <summary>libretro names like the typed one, for this game's console (D122 "Find a cover").</summary>
    /// <param name="id">Item id.</param>
    /// <param name="q">What was typed.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>Names, best first (409 while JellyEMU is installed).</returns>
    [HttpGet("cover-search")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status409Conflict)]
    public async Task<ActionResult<CoverSearchDto>> SearchCovers([FromRoute] Guid id, [FromQuery] string? q, CancellationToken cancellationToken)
    {
        if (Find(id, out _, out var rom) is ActionResult fail)
        {
            return fail;
        }

        if (!_finder.Available)
        {
            return Fail(StatusCodes.Status409Conflict, "jellyemu", "Covers can't be looked up while JellyEMU is installed.");
        }

        return new CoverSearchDto(await _finder.SearchAsync(rom.Console, q ?? string.Empty, cancellationToken).ConfigureAwait(false));
    }

    /// <summary>The libretro cover under a name, to show the admin before they pick it. Nothing is stored.</summary>
    /// <param name="id">Item id.</param>
    /// <param name="name">libretro name.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>The picture, 404 if there is none by that name, 502 if the cover server couldn't be reached.</returns>
    [HttpGet("cover-preview")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<ActionResult> PreviewCover([FromRoute] Guid id, [FromQuery] string? name, CancellationToken cancellationToken)
    {
        if (Find(id, out _, out var rom) is ActionResult fail)
        {
            return fail;
        }

        var found = await _finder.FetchAsync(rom.Console, name ?? string.Empty, cancellationToken).ConfigureAwait(false);
        if (found.Data is null)
        {
            return Fail(found.Missing ? StatusCodes.Status404NotFound : StatusCodes.Status502BadGateway, found.Missing ? "no_cover" : "cover_server", found.Error ?? "No cover.");
        }

        Response.Headers.CacheControl = "private, max-age=3600";
        Response.Headers["X-Content-Type-Options"] = "nosniff";
        return File(found.Data, found.Info!.MediaType);
    }

    /// <summary>Uses the libretro cover under a name as this game's cover.</summary>
    /// <param name="id">Item id.</param>
    /// <param name="body">{ "name": "libretro name" }.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>The game, 404 if there is no cover by that name.</returns>
    [HttpPost("cover-pick")]
    [Consumes("application/json")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<ActionResult<GameEditDto>> PickCover([FromRoute] Guid id, [FromBody] GameEditNameDto body, CancellationToken cancellationToken)
    {
        if (Find(id, out var user, out var rom) is ActionResult fail)
        {
            return fail;
        }

        var name = (body?.Name ?? string.Empty).Trim();
        var found = await _finder.FetchAsync(rom.Console, name, cancellationToken).ConfigureAwait(false);
        if (found.Data is null)
        {
            return Fail(found.Missing ? StatusCodes.Status404NotFound : StatusCodes.Status502BadGateway, found.Missing ? "no_cover" : "cover_server", found.Error ?? "No cover.");
        }

        await _editor.SetCoverAsync(rom.Item, found.Data, found.Info!, "libretro", name, user.Id, cancellationToken).ConfigureAwait(false);
        return Fresh(id, user);
    }

    /// <summary>Puts the game back to automatic (name and cover from the game database and box art again).</summary>
    /// <param name="id">Item id.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>The game.</returns>
    [HttpPost("reset")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    public async Task<ActionResult<GameEditDto>> Reset([FromRoute] Guid id, CancellationToken cancellationToken)
    {
        if (Find(id, out var user, out var rom) is ActionResult fail)
        {
            return fail;
        }

        // Replace all metadata (the name is unlocked now, so the automatic one comes back); images are only looked for
        // if the game has none, which it doesn't once the admin's cover is removed.
        var refresh = new MetadataRefreshOptions(new DirectoryService(_fileSystem))
        {
            MetadataRefreshMode = MetadataRefreshMode.FullRefresh,
            ImageRefreshMode = MetadataRefreshMode.FullRefresh,
            ReplaceAllMetadata = true,
            ReplaceAllImages = false,
        };
        await _editor.ResetAsync(rom.Item, refresh, cancellationToken).ConfigureAwait(false);
        return Fresh(id, user);
    }

    private ActionResult? Find(Guid id, out Jellyfin.Database.Implementations.Entities.User user, out RomItem rom)
    {
        rom = null!;
        if (RequireUser(out user) is ActionResult fail)
        {
            return fail;
        }

        return RequireRom(id, user, out rom);
    }

    private ActionResult<GameEditDto> Fresh(Guid id, Jellyfin.Database.Implementations.Entities.User user) =>
        Library.GetRom(id, user) is RomItem rom ? State(rom) : GameNotFound();

    private GameEditDto State(RomItem rom)
    {
        var edit = _editor.Edits.Get(rom.Id);
        return new GameEditDto(
            rom.Id.ToString("N"),
            rom.Name,
            rom.Console.Key,
            edit?.Name is not null,
            edit?.Cover?.Source,
            edit?.Cover?.From,
            rom.ImageTag,
            _finder.Available && LibretroNames.SystemName(rom.Console) is not null,
            rom.Item.LockedFields.Contains(MetadataField.Name) && edit?.NameLockAdded != true);
    }
}
