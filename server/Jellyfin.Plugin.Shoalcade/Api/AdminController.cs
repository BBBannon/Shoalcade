using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.Json.Serialization;
using System.Threading.Tasks;
using Jellyfin.Plugin.Shoalcade.Library;
using Jellyfin.Plugin.Shoalcade.Migration;
using Jellyfin.Plugin.Shoalcade.Storage;
using MediaBrowser.Common.Api;
using MediaBrowser.Controller.Library;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;

namespace Jellyfin.Plugin.Shoalcade.Api;

/// <summary>Games library status.</summary>
/// <param name="Scanner">Which plugin finds ROMs right now: "JellyEMU" while it is installed, else "Shoalcade".</param>
/// <param name="Libraries">Every library.</param>
/// <param name="BoxArt">Whether our box art source is ticked, per games library (null while JellyEMU finds the games).</param>
/// <param name="Migration">What carrying JellyGames data over did this start, in plain English, or null if there was nothing to tell (G6 dev 2 L8).</param>
/// <param name="MigrationProblem">True if the carry-over failed (the admin should act).</param>
public sealed record GamesLibraryOverviewDto(
    [property: JsonPropertyName("scanner")] string Scanner,
    [property: JsonPropertyName("libraries")] IReadOnlyList<LibraryStatus> Libraries,
    [property: JsonPropertyName("boxArt")] IReadOnlyList<BoxArtStatusDto>? BoxArt,
    [property: JsonPropertyName("migration")] string? Migration = null,
    [property: JsonPropertyName("migrationProblem")] bool MigrationProblem = false);

/// <summary>Box art for one games library.</summary>
/// <param name="Name">Library name.</param>
/// <param name="On">Whether our box art source is ticked for it.</param>
public sealed record BoxArtStatusDto(
    [property: JsonPropertyName("name")] string Name,
    [property: JsonPropertyName("on")] bool On);

/// <summary>Request to create a games library.</summary>
public sealed class CreateGamesLibraryDto
{
    /// <summary>Gets or sets the library name.</summary>
    [JsonPropertyName("name")]
    public string? Name { get; set; }

    /// <summary>Gets or sets the ROM folder on the server.</summary>
    [JsonPropertyName("path")]
    public string? Path { get; set; }
}

/// <summary>Admin-only: set up the games library and see storage use from the Shoalcade settings page.</summary>
[ApiController]
[Authorize(Policy = Policies.RequiresElevation)]
[ApiSecurityHeaders]
[Route("shoalcade/admin")]
[Produces("application/json")]
public class AdminController : ControllerBase
{
    private readonly GamesLibrarySetup _setup;
    private readonly ShoalcadeServices _services;
    private readonly RomLibrary _library;
    private readonly IUserManager _users;
    private readonly HomePageHider _home;
    private readonly BoxArtSwitch _boxArt;

    /// <summary>Initializes a new instance of the <see cref="AdminController"/> class.</summary>
    /// <param name="setup">Library setup.</param>
    /// <param name="services">Stores.</param>
    /// <param name="library">ROM library.</param>
    /// <param name="users">Jellyfin users.</param>
    /// <param name="home">Keeps games off the home page.</param>
    /// <param name="boxArt">Switches box art on for games libraries.</param>
    public AdminController(GamesLibrarySetup setup, ShoalcadeServices services, RomLibrary library, IUserManager users, HomePageHider home, BoxArtSwitch boxArt)
    {
        _home = home;
        _boxArt = boxArt;
        _setup = setup;
        _services = services;
        _library = library;
        _users = users;
    }

    /// <summary>Every library with its game count, and which plugin finds ROMs.</summary>
    /// <returns>Overview.</returns>
    [HttpGet("libraries")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    public ActionResult<GamesLibraryOverviewDto> GetLibraries() => Overview();

    /// <summary>Creates a games library (Books type, never writes into the ROM folder) and scans it.</summary>
    /// <param name="body">Name and folder.</param>
    /// <returns>201, or 422 with the reason.</returns>
    [HttpPost("libraries")]
    [Consumes("application/json")]
    [ProducesResponseType(StatusCodes.Status201Created)]
    [ProducesResponseType(StatusCodes.Status422UnprocessableEntity)]
    public async Task<ActionResult> CreateLibrary([FromBody] CreateGamesLibraryDto body)
    {
        var err = _setup.Validate(body?.Name, body?.Path);
        if (err is not null)
        {
            return UnprocessableEntity(new ApiError("bad_library", err));
        }

        await _setup.CreateAsync(body!.Name!, body.Path!).ConfigureAwait(false);

        // Off the home page straight away (if the admin setting is on), before the first scan finds any games.
        var id = _setup.FindId(body.Name!);
        await _home.SyncQuietlyAsync(id is Guid g ? [g] : null).ConfigureAwait(false);
        await _boxArt.SyncQuietlyAsync(id is Guid b ? [b] : null).ConfigureAwait(false);
        return StatusCode(StatusCodes.Status201Created, Overview());
    }

    /// <summary>Scans the libraries for new or removed ROMs.</summary>
    /// <returns>202.</returns>
    [HttpPost("scan")]
    [ProducesResponseType(StatusCodes.Status202Accepted)]
    public ActionResult Scan()
    {
        _setup.Scan();
        return Accepted();
    }

    /// <summary>
    /// Storage used per user and per game, against the per-user quotas, with server totals (item 9). View only: sizes,
    /// counts and names, never a save's contents, and nothing here can delete or download another user's data.
    /// </summary>
    /// <returns>The report.</returns>
    [HttpGet("storage")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    public ActionResult<StorageReportDto> GetStorage()
    {
        var c = ShoalcadeServices.Config;
        var accounts = _users.GetUsers().ToDictionary(u => u.Id);
        var users = accounts.Values.Select(u => (u.Id, u.Username)).ToList();
        StorageGameRef? Find(Guid user, Guid item)
        {
            // "In your library" means this user can still see it (same rule as their own save list).
            var rom = accounts.TryGetValue(user, out var u) ? _library.GetRom(item, u) : null;
            return rom is null ? null : new StorageGameRef(rom.Name, rom.Console.Key);
        }

        return _services.Storage.Build(users, Find, Math.Max(0, c.SaveQuotaMegabytes) * 1024L * 1024L, Math.Max(0, c.SnapshotQuotaMegabytes) * 1024L * 1024L);
    }

    private GamesLibraryOverviewDto Overview()
    {
        var libs = _setup.List();
        List<BoxArtStatusDto>? art = null;
        foreach (var l in libs.Where(l => l.Suitable && l.RomCount > 0))
        {
            if (Guid.TryParse(l.Id, out var id) && _boxArt.IsOn(id) is bool on)
            {
                (art ??= []).Add(new BoxArtStatusDto(l.Name, on));
            }
        }

        var (text, problem) = MigrationText(_services.DataMigration, Plugin.ConfigCarryOver);
        return new GamesLibraryOverviewDto(_setup.RomScanner, libs, art, text, problem);
    }

    /// <summary>The JellyGames carry-over result for the settings page (G6 dev 2 L8): it used to reach only the log.</summary>
    /// <param name="data">What the data copy did this start.</param>
    /// <param name="config">What the settings file copy did.</param>
    /// <returns>Plain-English text (null = nothing to tell) and whether it is a problem.</returns>
    internal static (string? Text, bool Problem) MigrationText(DataMigrationResult data, ConfigMigrationOutcome config)
    {
        ArgumentNullException.ThrowIfNull(data);
        var bits = new List<string>();
        var problem = false;
        switch (data.Outcome)
        {
            case DataMigrationOutcome.Copied:
                bits.Add($"Saves, snapshots and settings were copied over from JellyGames this start ({data.Files} files, all checked). The old jellygames folder is kept, unchanged.");
                break;
            case DataMigrationOutcome.FailedUsingOld:
                problem = true;
                bits.Add("Copying the JellyGames data failed, so Shoalcade is using the old jellygames folder for now and will try again at the next start. Nothing was lost. Check the server log" + (data.Error is null ? "." : ": " + data.Error));
                break;
        }

        if (config == ConfigMigrationOutcome.Failed)
        {
            problem = true;
            bits.Add("The JellyGames settings couldn't be copied, so these are default settings: check them and press Save. No old save versions are tidied away until then.");
        }

        return (bits.Count == 0 ? null : string.Join(" ", bits), problem);
    }
}
