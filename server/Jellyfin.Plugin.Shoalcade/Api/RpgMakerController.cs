using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text.Json.Serialization;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Data;
using Jellyfin.Database.Implementations.Enums;
using Jellyfin.Plugin.Shoalcade.Library;
using Jellyfin.Plugin.Shoalcade.Scanner;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Net.Http.Headers;

namespace Jellyfin.Plugin.Shoalcade.Api;

/// <summary>One file in an RPG Maker game's list.</summary>
/// <param name="Path">Path inside the game folder, with <c>/</c>.</param>
/// <param name="Size">Bytes.</param>
/// <param name="Sha256">SHA-256, lower-case hex.</param>
public sealed record RpgMakerFileDto(
    [property: JsonPropertyName("path")] string Path,
    [property: JsonPropertyName("size")] long Size,
    [property: JsonPropertyName("sha256")] string Sha256);

/// <summary>An RPG Maker game's file list (D147).</summary>
/// <param name="Engine">"XP", "VX", "VX Ace" or null.</param>
/// <param name="Title">Game.ini title (else the folder name).</param>
/// <param name="Total">Bytes of all listed files.</param>
/// <param name="Count">Files listed.</param>
/// <param name="MayNotWork">The game ships Windows DLLs we don't recognise.</param>
/// <param name="Files">Files, sorted by path (ordinal).</param>
/// <param name="OldRuby">Made for Ruby 1.8 / 1.9: clients turn on mkxp-z's syntax transform.</param>
public sealed record RpgMakerFilesDto(
    [property: JsonPropertyName("engine")] string? Engine,
    [property: JsonPropertyName("title")] string Title,
    [property: JsonPropertyName("total")] long Total,
    [property: JsonPropertyName("count")] int Count,
    [property: JsonPropertyName("mayNotWork")] bool MayNotWork,
    [property: JsonPropertyName("files")] IReadOnlyList<RpgMakerFileDto> Files,
    [property: JsonPropertyName("oldRuby")] bool OldRuby = false);

/// <summary>
/// RPG Maker games (D147) are folders, so they are downloaded file by file: the list (with SHA-256s, so only changed
/// files are fetched again) and one file at a time. Same checks as every Shoalcade route (login, a real user, a ROM item
/// the user can see inside its library) plus Jellyfin's own download permission. Only paths in the list are ever
/// served: Windows-only files, dotfiles, links/junctions and anything outside the game folder never are.
/// </summary>
[Route("shoalcade")]
public class RpgMakerController : ShoalcadeControllerBase
{
    /// <summary>Initializes a new instance of the <see cref="RpgMakerController"/> class.</summary>
    /// <param name="library">ROM library.</param>
    /// <param name="services">Stores.</param>
    public RpgMakerController(RomLibrary library, ShoalcadeServices services)
        : base(library, services)
    {
    }

    /// <summary>The game's file list: { engine, title, total, count, mayNotWork, files: [{ path, size, sha256 }], oldRuby }.</summary>
    /// <param name="id">Item id.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>The list; 404 if not an RPG Maker game the user can see; 403 without download permission.</returns>
    [HttpGet("games/{id:guid}/files")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<ActionResult<RpgMakerFilesDto>> GetFiles([FromRoute] Guid id, CancellationToken cancellationToken)
    {
        if (Resolve(id, out var folder) is ActionResult fail)
        {
            return fail;
        }

        RpgMakerManifest m;
        try
        {
            // The hashing carries on if the client gives up waiting (first list of a big game), so the next try is quick.
            m = await Services.RpgMaker.GetManifestAsync(folder, CancellationToken.None).WaitAsync(cancellationToken).ConfigureAwait(false);
        }
        catch (InvalidDataException)
        {
            return Fail(StatusCodes.Status422UnprocessableEntity, "too_many_files", "This game folder has too many files to be an RPG Maker game.");
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return Fail(StatusCodes.Status503ServiceUnavailable, "unreadable", "A file of this game couldn't be read on the server. Try again in a moment.");
        }

        Response.Headers.CacheControl = "no-cache, private";
        var s = m.Summary;
        return new RpgMakerFilesDto(s.Engine, s.Title, s.Total, s.Count, s.MayNotWork, m.Files.Select(f => new RpgMakerFileDto(f.Path, f.Size, f.Sha256)).ToList(), s.OldRuby);
    }

    /// <summary>One file of the game, e.g. <c>?path=Data%2FMap001.rxdata</c>: the bytes, with Content-Length and Range
    /// (206 / 416) support. Only a path in the game's list is served.</summary>
    /// <param name="id">Item id.</param>
    /// <param name="path">Path inside the game folder, with <c>/</c>, exactly as listed.</param>
    /// <param name="sha256">The SHA-256 the client expects (from the list): when it is this file's known hash, the answer
    /// may be cached for good (the URL then names exactly these bytes); otherwise it is not cached as-is.</param>
    /// <returns>The file; 404 if it isn't in the list.</returns>
    [HttpGet("games/{id:guid}/file")]
    [HttpHead("games/{id:guid}/file")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status206PartialContent)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    [ProducesResponseType(StatusCodes.Status416RangeNotSatisfiable)]
    public ActionResult GetFile([FromRoute] Guid id, [FromQuery] string? path, [FromQuery] string? sha256 = null)
    {
        if (Resolve(id, out var folder) is ActionResult fail)
        {
            return fail;
        }

        var file = RpgMakerGame.Resolve(folder, path);
        if (file is null)
        {
            return Fail(StatusCodes.Status404NotFound, "no_file", "That file isn't part of this game.");
        }

        FileStream stream;
        try
        {
            stream = RpgMakerFiles.OpenRead(file.FullName);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return Fail(StatusCodes.Status503ServiceUnavailable, "unreadable", "That file couldn't be read on the server. Try again in a moment.");
        }

        var known = string.IsNullOrEmpty(sha256) ? null : Services.RpgMaker.KnownSha256(folder, path!, file);
        Response.Headers.CacheControl = known is not null && string.Equals(known, sha256, StringComparison.OrdinalIgnoreCase)
            ? ImmutableCache
            : "no-cache, private";
        var tag = new EntityTagHeaderValue("\"" + file.Length.ToString("x", CultureInfo.InvariantCulture) + "-" + file.LastWriteTimeUtc.Ticks.ToString("x", CultureInfo.InvariantCulture) + "\"");
        return File(stream, "application/octet-stream", new DateTimeOffset(file.LastWriteTimeUtc), tag, enableRangeProcessing: true);
    }

    /// <summary>Cache rule for a file asked for with its own SHA-256 (those bytes never change under that URL).</summary>
    internal const string ImmutableCache = "private, max-age=31536000, immutable";

    /// <summary>The game folder if the user may download this RPG Maker game, else the error to return.</summary>
    private ActionResult? Resolve(Guid id, out string folder)
    {
        folder = string.Empty;
        if (RequireUser(out var user) is ActionResult fail)
        {
            return fail;
        }

        if (RequireRom(id, user, out var rom) is ActionResult notFound)
        {
            return notFound;
        }

        // The real game folder (holding Game.ini): file paths are relative to it, even when the item is the folder above.
        if (!rom.Console.FolderGame || RpgMakerGame.GameRoot(rom.Item.Path, inConsoleFolder: true) is not string root)
        {
            return GameNotFound();
        }

        // Jellyfin's own rule for downloads (its /Items/{id}/Download refuses the same people).
        if (!user.HasPermission(PermissionKind.EnableContentDownloading))
        {
            return Fail(StatusCodes.Status403Forbidden, "no_download", "Your Jellyfin account isn't allowed to download.");
        }

        folder = root;
        return null;
    }
}
