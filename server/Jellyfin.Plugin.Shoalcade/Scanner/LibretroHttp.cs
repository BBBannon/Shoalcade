using System;
using System.Diagnostics.CodeAnalysis;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Threading;
using System.Threading.Tasks;

namespace Jellyfin.Plugin.Shoalcade.Scanner;

/// <summary>What came back from one libretro request.</summary>
/// <param name="Status">HTTP status (200, 304, 404...).</param>
/// <param name="Body">The body for a 200, otherwise null.</param>
/// <param name="ContentType">The body's media type, if given.</param>
/// <param name="ETag">The ETag, if given (for "has it changed?" checks next time).</param>
public sealed record LibretroResponse(HttpStatusCode Status, byte[]? Body, string? ContentType, string? ETag);

/// <summary>
/// The only way the plugin talks to libretro's servers (game database files on GitHub, box art on
/// thumbnails.libretro.com, D48). Polite by design: one request at a time across the whole plugin, a short gap
/// between requests, a timeout on each, a size limit, and a clear User-Agent. Callers only ask for our own games,
/// and only while our scanner is running (never while JellyEMU is installed).
/// <para>The two addresses can be pointed somewhere else with the environment variables
/// <c>SHOALCADE_LIBRETRO_DATABASE_URL</c> and <c>SHOALCADE_LIBRETRO_THUMBNAILS_URL</c> (the test-bed points them at
/// a local stand-in, so tests never touch the real servers).</para>
/// </summary>
public sealed class LibretroHttp : IDisposable
{
    /// <summary>The real libretro-database "metadat" folder (raw files on GitHub).</summary>
    public const string DefaultDatabaseUrl = "https://raw.githubusercontent.com/libretro/libretro-database/master/metadat/";

    /// <summary>The real libretro-thumbnails server.</summary>
    public const string DefaultThumbnailsUrl = "https://thumbnails.libretro.com/";

    private static readonly TimeSpan Gap = TimeSpan.FromMilliseconds(250);

    private readonly Func<HttpClient> _client;
    private readonly TimeProvider _clock;
    [SuppressMessage("Usage", "CA2213:Disposable fields should be disposed", Justification = "Not disposed on purpose: Jellyfin's in-process restart disposes this service while a task may still wait on or hold the lock, and a SemaphoreSlim only needs disposing once its AvailableWaitHandle is used (never here)")]
    private readonly SemaphoreSlim _one = new(1, 1);
    private DateTimeOffset _last = DateTimeOffset.MinValue;

    /// <summary>Initializes a new instance of the <see cref="LibretroHttp"/> class.</summary>
    /// <param name="client">Gives the HTTP client to use (Jellyfin's, from its client factory).</param>
    /// <param name="clock">Clock.</param>
    /// <param name="databaseUrl">Base address of the database files (null = environment variable or the real one).</param>
    /// <param name="thumbnailsUrl">Base address of the box art server (null = environment variable or the real one).</param>
    public LibretroHttp(Func<HttpClient> client, TimeProvider clock, string? databaseUrl = null, string? thumbnailsUrl = null)
    {
        _client = client;
        _clock = clock;
        DatabaseBase = BaseUri(databaseUrl ?? Environment.GetEnvironmentVariable("SHOALCADE_LIBRETRO_DATABASE_URL") ?? DefaultDatabaseUrl);
        ThumbnailsBase = BaseUri(thumbnailsUrl ?? Environment.GetEnvironmentVariable("SHOALCADE_LIBRETRO_THUMBNAILS_URL") ?? DefaultThumbnailsUrl);
    }

    /// <summary>Gets the User-Agent sent with every request.</summary>
    public static string UserAgent { get; } =
        $"Shoalcade/{typeof(LibretroHttp).Assembly.GetName().Version?.ToString(2) ?? "0"} (Jellyfin plugin; personal game library: game names and box art)";

    /// <summary>Gets the base address of the database files (ends with /).</summary>
    public Uri DatabaseBase { get; }

    /// <summary>Gets the base address of the box art server (ends with /).</summary>
    public Uri ThumbnailsBase { get; }

    /// <summary>Gets how many requests have been sent (tests).</summary>
    internal int RequestsSent { get; private set; }

    /// <summary>
    /// GETs one address, waiting for any other libretro request to finish first. The whole body is read before the
    /// next request may start. Throws <see cref="HttpRequestException"/> if the server can't be reached, the body is
    /// too big, or the request takes longer than <paramref name="timeout"/>.
    /// </summary>
    /// <param name="url">Address.</param>
    /// <param name="etag">ETag from last time (asks "only if changed"; a 304 means unchanged), or null.</param>
    /// <param name="maxBytes">Largest body accepted.</param>
    /// <param name="timeout">Time limit for the whole request.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>The response.</returns>
    public async Task<LibretroResponse> GetAsync(Uri url, string? etag, long maxBytes, TimeSpan timeout, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(url);
        await _one.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            var wait = _last + Gap - _clock.GetUtcNow();
            if (wait > TimeSpan.Zero)
            {
                await Task.Delay(wait, cancellationToken).ConfigureAwait(false);
            }

            using var timer = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            timer.CancelAfter(timeout);
            using var request = new HttpRequestMessage(HttpMethod.Get, url);
            request.Headers.UserAgent.Clear();
            request.Headers.TryAddWithoutValidation("User-Agent", UserAgent);
            if (!string.IsNullOrEmpty(etag) && EntityTagHeaderValue.TryParse(etag, out var tag))
            {
                request.Headers.IfNoneMatch.Add(tag);
            }

            RequestsSent++;
            try
            {
                using var response = await _client().SendAsync(request, HttpCompletionOption.ResponseHeadersRead, timer.Token).ConfigureAwait(false);
                var type = response.Content.Headers.ContentType?.MediaType;
                var newTag = response.Headers.ETag?.ToString();
                if (response.StatusCode != HttpStatusCode.OK)
                {
                    return new LibretroResponse(response.StatusCode, null, type, newTag);
                }

                if (response.Content.Headers.ContentLength > maxBytes)
                {
                    throw new HttpRequestException($"{url} is larger than {maxBytes} bytes");
                }

                var body = await ReadLimitedAsync(response.Content, maxBytes, timer.Token).ConfigureAwait(false);
                return new LibretroResponse(HttpStatusCode.OK, body, type, newTag);
            }
            catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
            {
                throw new HttpRequestException($"{url} took longer than {timeout.TotalSeconds:0} seconds");
            }
        }
        finally
        {
            _last = _clock.GetUtcNow();
            _one.Release();
        }
    }

    /// <inheritdoc />
    /// <remarks>The lock is not disposed (see its field): a Jellyfin restart can dispose this service while a
    /// start-up task or scan still uses it.</remarks>
    public void Dispose()
    {
    }

    private static Uri BaseUri(string url) => new(url.EndsWith('/') ? url : url + "/", UriKind.Absolute);

    private static async Task<byte[]> ReadLimitedAsync(HttpContent content, long maxBytes, CancellationToken cancellationToken)
    {
        var stream = await content.ReadAsStreamAsync(cancellationToken).ConfigureAwait(false);
        await using (stream.ConfigureAwait(false))
        {
            using var buffer = new MemoryStream();
            var chunk = new byte[81920];
            int read;
            while ((read = await stream.ReadAsync(chunk, cancellationToken).ConfigureAwait(false)) > 0)
            {
                if (buffer.Length + read > maxBytes)
                {
                    throw new HttpRequestException($"The download is larger than {maxBytes} bytes");
                }

                buffer.Write(chunk, 0, read);
            }

            return buffer.ToArray();
        }
    }
}
