using System;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Plugin.Shoalcade.Consoles;
using Jellyfin.Plugin.Shoalcade.Library;

namespace Jellyfin.Plugin.Shoalcade.Scanner;

/// <summary>A cover fetched from libretro-thumbnails: the picture, or why there isn't one.</summary>
/// <param name="Data">Picture bytes (checked), or null.</param>
/// <param name="Info">What it is, or null.</param>
/// <param name="Missing">True if libretro has no cover by that name (as opposed to a network problem).</param>
/// <param name="Error">Plain-English reason when there is no picture.</param>
public sealed record FoundCover(byte[]? Data, CoverInfo? Info, bool Missing, string? Error);

/// <summary>
/// "Find a cover" for the admin game editor (D122): searches the console's libretro-database list (already used for
/// game names, D108) for names like the one the admin typed, and fetches a named cover from libretro-thumbnails. Same
/// rules as box art: only through <see cref="LibretroHttp"/> (one request at a time, our User-Agent), "not found"
/// remembered 30 days, only PNG/JPEG/WebP with a sensible size kept, nothing while JellyEMU is installed.
/// </summary>
public sealed class CoverFinder
{
    /// <summary>Most names a search returns.</summary>
    public const int MaxResults = 12;

    /// <summary>Longest name or search accepted.</summary>
    public const int MaxNameLength = 300;

    private static readonly TimeSpan Timeout = TimeSpan.FromSeconds(30);

    private readonly ScannerGuard _guard;
    private readonly GameDatabase _db;
    private readonly LibretroHttp _http;
    private readonly ThumbnailMisses _misses;

    /// <summary>Initializes a new instance of the <see cref="CoverFinder"/> class.</summary>
    /// <param name="guard">Stands down while JellyEMU is installed.</param>
    /// <param name="db">Game database.</param>
    /// <param name="http">The polite libretro client.</param>
    /// <param name="misses">Remembered missing pictures.</param>
    public CoverFinder(ScannerGuard guard, GameDatabase db, LibretroHttp http, ThumbnailMisses misses)
    {
        _guard = guard;
        _db = db;
        _http = http;
        _misses = misses;
    }

    /// <summary>Gets a value indicating whether covers can be looked up now (not while JellyEMU is installed).</summary>
    public bool Available => !_guard.StandDown;

    /// <summary>
    /// Names to offer for what the admin typed, best first. A typed name that looks like a full libretro name (it has a
    /// bracket, e.g. "Dissidia - Final Fantasy (USA)") is offered too, last, in case the list doesn't have it.
    /// </summary>
    /// <param name="console">The game's console.</param>
    /// <param name="query">What was typed.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>Names.</returns>
    public async Task<IReadOnlyList<string>> SearchAsync(GameConsole console, string query, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(console);
        query = (query ?? string.Empty).Trim();
        if (!Available || query.Length == 0 || query.Length > MaxNameLength)
        {
            return [];
        }

        var names = (await _db.SearchAsync(console, query, MaxResults, cancellationToken).ConfigureAwait(false)).ToList();
        if (query.Contains('(', StringComparison.Ordinal) && !names.Contains(query, StringComparer.Ordinal))
        {
            if (names.Count == MaxResults)
            {
                names.RemoveAt(names.Count - 1);
            }

            names.Add(query);
        }

        return names;
    }

    /// <summary>Fetches the cover libretro has under a name for this console.</summary>
    /// <param name="console">The game's console.</param>
    /// <param name="name">libretro game name.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>The cover, or why not.</returns>
    public async Task<FoundCover> FetchAsync(GameConsole console, string name, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(console);
        name = (name ?? string.Empty).Trim();
        if (!Available)
        {
            return new FoundCover(null, null, false, "Covers can't be looked up while JellyEMU is installed.");
        }

        if (LibretroNames.SystemName(console) is not string system)
        {
            return new FoundCover(null, null, true, "There are no covers for this console.");
        }

        if (name.Length == 0 || name.Length > MaxNameLength || name.Any(char.IsControl))
        {
            return new FoundCover(null, null, true, "No cover by that name.");
        }

        var url = LibretroNames.BoxArtUrl(_http.ThumbnailsBase, system, LibretroNames.ThumbnailName(name));
        if (_misses.IsMissing(url.AbsoluteUri))
        {
            return new FoundCover(null, null, true, "No cover by that name.");
        }

        LibretroResponse r;
        try
        {
            r = await _http.GetAsync(url, null, CoverImage.MaxBytes, Timeout, cancellationToken).ConfigureAwait(false);
        }
        catch (HttpRequestException)
        {
            return new FoundCover(null, null, false, "Couldn't reach the cover server. Try again later.");
        }

        if (r.Status is HttpStatusCode.NotFound or HttpStatusCode.Gone)
        {
            await _misses.AddAsync(url.AbsoluteUri, cancellationToken).ConfigureAwait(false);
            return new FoundCover(null, null, true, "No cover by that name.");
        }

        if (r.Status != HttpStatusCode.OK || r.Body is null)
        {
            return new FoundCover(null, null, false, "The cover server said no (" + (int)r.Status + "). Try again later.");
        }

        if (CoverImage.Check(r.Body, out var info) is string err)
        {
            return new FoundCover(null, null, false, err);
        }

        return new FoundCover(r.Body, info, false, null);
    }
}
