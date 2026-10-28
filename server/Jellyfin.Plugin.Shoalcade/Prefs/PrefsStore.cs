using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Plugin.Shoalcade.Common;
using Jellyfin.Plugin.Shoalcade.Consoles;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;

namespace Jellyfin.Plugin.Shoalcade.Prefs;

/// <summary>
/// Per-user, per-console settings (controls, touch layout, snapshots on/off: D33, D38). The server is the only
/// settings store, so they follow the person between browsers. Stored as <c>{root}/{userId}/{scope}.json</c>.
/// Scopes are a console key or "global".
/// </summary>
public sealed class PrefsStore
{
    /// <summary>Largest settings document accepted.</summary>
    public const int MaxBytes = 64 * 1024;

    private const int MaxDepth = 8;

    private readonly string _root;
    private readonly TimeProvider _clock;
    private readonly ILogger _logger;
    private readonly KeyedLocks _locks = new();

    /// <summary>Initializes a new instance of the <see cref="PrefsStore"/> class.</summary>
    /// <param name="root">Settings root folder.</param>
    /// <param name="clock">Clock (names the copy kept of a damaged file).</param>
    /// <param name="logger">Logger.</param>
    public PrefsStore(string root, TimeProvider? clock = null, ILogger? logger = null)
    {
        _root = Path.GetFullPath(root);
        _clock = clock ?? TimeProvider.System;
        _logger = logger ?? NullLogger.Instance;
    }

    /// <summary>Gets the allowed scopes.</summary>
    public static IEnumerable<string> Scopes => GameConsole.All.Select(c => c.Key).Append("global");

    /// <summary>True if the scope is allowed.</summary>
    /// <param name="scope">Scope.</param>
    /// <returns>Whether it is valid.</returns>
    public static bool IsValidScope(string? scope) => scope is not null && Scopes.Contains(scope, StringComparer.Ordinal);

    /// <summary>
    /// Checks a settings document: a JSON object, not too big, not too deep.
    /// </summary>
    /// <param name="data">Bytes.</param>
    /// <param name="obj">Parsed object.</param>
    /// <returns>Null if fine, else the reason.</returns>
    public static string? Validate(ReadOnlySpan<byte> data, out JsonObject? obj)
    {
        obj = null;
        if (data.Length > MaxBytes)
        {
            return "Settings are too big.";
        }

        try
        {
            var node = JsonNode.Parse(data, documentOptions: new JsonDocumentOptions { MaxDepth = MaxDepth });
            if (node is not JsonObject o)
            {
                return "Settings must be a JSON object.";
            }

            obj = o;
            return null;
        }
        catch (JsonException)
        {
            return "Settings are not valid JSON.";
        }
    }

    /// <summary>
    /// Reads a settings document ({} if none). A damaged file is moved aside as <c>{scope}.json.damaged-{time}</c>
    /// (kept, never deleted), logged once, and read as {}, so the page can save that group again.
    /// </summary>
    /// <param name="user">User id.</param>
    /// <param name="scope">Scope.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>The JSON bytes (always a valid settings object).</returns>
    public async Task<byte[]> GetAsync(Guid user, string scope, CancellationToken cancellationToken)
    {
        var path = PathFor(user, scope);
        using (await _locks.AcquireAsync(path, cancellationToken).ConfigureAwait(false))
        {
            if (!File.Exists(path))
            {
                return "{}"u8.ToArray();
            }

            var bytes = await File.ReadAllBytesAsync(path, cancellationToken).ConfigureAwait(false);
            var err = Validate(bytes, out _);
            if (err is null)
            {
                return bytes;
            }

            var stamp = _clock.GetUtcNow().ToString("yyyyMMddHHmmss", CultureInfo.InvariantCulture);
            var keep = path + ".damaged-" + stamp;
            for (var n = 2; File.Exists(keep); n++)
            {
                keep = path + ".damaged-" + stamp + "-" + n.ToString(CultureInfo.InvariantCulture);
            }

            File.Move(path, keep);
            _logger.LogWarning("Settings file {Path} was damaged ({Reason}); kept it as {Copy} and started that group afresh", path, err, keep);
            return "{}"u8.ToArray();
        }
    }

    /// <summary>Replaces a settings document.</summary>
    /// <param name="user">User id.</param>
    /// <param name="scope">Scope.</param>
    /// <param name="obj">Validated settings.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>A task.</returns>
    public async Task PutAsync(Guid user, string scope, JsonObject obj, CancellationToken cancellationToken)
    {
        var path = PathFor(user, scope);
        using (await _locks.AcquireAsync(path, cancellationToken).ConfigureAwait(false))
        {
            await AtomicFile.WriteAsync(path, JsonSerializer.SerializeToUtf8Bytes(obj), true, cancellationToken).ConfigureAwait(false);
        }
    }

    private string PathFor(Guid user, string scope)
    {
        if (!IsValidScope(scope))
        {
            throw new ArgumentException("Unknown settings scope.", nameof(scope));
        }

        return Path.Combine(_root, user.ToString("N"), scope + ".json");
    }
}
