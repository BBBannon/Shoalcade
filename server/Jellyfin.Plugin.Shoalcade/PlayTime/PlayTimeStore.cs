using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Plugin.Shoalcade.Common;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;

namespace Jellyfin.Plugin.Shoalcade.PlayTime;

/// <summary>One finished play session as a device sends it.</summary>
/// <param name="Id">Id the device made for it (a UUID), so a resend is never counted twice.</param>
/// <param name="Seconds">Seconds played.</param>
/// <param name="EndedAt">When it ended (epoch ms, the device's clock; kept for future use, not trusted).</param>
public sealed record PlaySession(
    [property: JsonPropertyName("id")] string? Id,
    [property: JsonPropertyName("seconds")] long Seconds,
    [property: JsonPropertyName("endedAt")] long EndedAt);

/// <summary>A game's play time for one person.</summary>
public sealed class PlayTimeDoc
{
    /// <summary>Gets or sets the total seconds played, from every device.</summary>
    [JsonPropertyName("seconds")]
    public long Seconds { get; set; }

    /// <summary>Gets or sets the ids of the most recent sessions counted, oldest first (at most <see cref="PlayTimeStore.KeepIds"/>).</summary>
    [JsonPropertyName("recent")]
    public List<string> Recent { get; set; } = [];
}

/// <summary>
/// Play time per person per game, added up from every device (phone app and browser player), at
/// <c>{root}/{userN}/{itemN}.json</c>, laid out like the cheats store. Devices send finished sessions, each with its own
/// id; a session id already counted is skipped, so a retried send never counts twice. Writes are one lock per file
/// and an atomic replace. A damaged file is moved aside (kept, never deleted) and read as zero.
/// Totals are cached in memory for the game list (this store is the only writer).
/// </summary>
public sealed class PlayTimeStore
{
    /// <summary>Longest session accepted (one day).</summary>
    public const long MaxSessionSeconds = 24 * 3600;

    /// <summary>Most sessions in one call.</summary>
    public const int MaxSessions = 100;

    /// <summary>Session ids remembered per game, so a resend is spotted.</summary>
    public const int KeepIds = 500;

    /// <summary>Largest request body the controller reads.</summary>
    public const int MaxBodyBytes = 16 * 1024;

    private static readonly JsonSerializerOptions Json = new() { WriteIndented = false };

    private readonly string _root;
    private readonly TimeProvider _clock;
    private readonly ILogger _logger;
    private readonly KeyedLocks _locks = new();
    private readonly ConcurrentDictionary<string, long> _totals = new(StringComparer.Ordinal);

    /// <summary>Initializes a new instance of the <see cref="PlayTimeStore"/> class.</summary>
    /// <param name="root">Play time root folder.</param>
    /// <param name="clock">Clock (names the copy kept of a damaged file).</param>
    /// <param name="logger">Logger.</param>
    public PlayTimeStore(string root, TimeProvider? clock = null, ILogger? logger = null)
    {
        _root = Path.GetFullPath(root);
        _clock = clock ?? TimeProvider.System;
        _logger = logger ?? NullLogger.Instance;
    }

    /// <summary>Gets the root folder.</summary>
    public string Root => _root;

    /// <summary>True if the text is a sane session id: 8 to 64 of letters, digits, "-" and "_".</summary>
    /// <param name="id">Text.</param>
    /// <returns>Whether it is one.</returns>
    public static bool IsSessionId(string? id) =>
        id is { Length: >= 8 and <= 64 } && id.All(c => char.IsAsciiLetterOrDigit(c) || c is '-' or '_');

    /// <summary>Checks a batch: 1 to <see cref="MaxSessions"/> sessions, each with a sane id and 1 to
    /// <see cref="MaxSessionSeconds"/> seconds.</summary>
    /// <param name="sessions">Sessions as sent.</param>
    /// <returns>Null if fine, else the reason (for the person).</returns>
    public static string? Validate(IReadOnlyList<PlaySession?>? sessions)
    {
        if (sessions is null || sessions.Count == 0)
        {
            return "No play sessions were sent.";
        }

        if (sessions.Count > MaxSessions)
        {
            return "Too many play sessions at once (" + MaxSessions + " at most).";
        }

        foreach (var s in sessions)
        {
            if (s is null || !IsSessionId(s.Id))
            {
                return "A play session has a missing or bad id.";
            }

            if (s.Seconds < 1 || s.Seconds > MaxSessionSeconds)
            {
                return "A play session must be between 1 second and 24 hours.";
            }
        }

        return null;
    }

    /// <summary>Total seconds played (0 if none).</summary>
    /// <param name="user">User id.</param>
    /// <param name="item">Item id.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>Seconds.</returns>
    public async Task<long> GetAsync(Guid user, Guid item, CancellationToken cancellationToken)
    {
        var path = PathFor(user, item);
        if (_totals.TryGetValue(path, out var cached))
        {
            return cached;
        }

        using (await _locks.AcquireAsync(path, cancellationToken).ConfigureAwait(false))
        {
            var doc = await ReadAsync(path, cancellationToken).ConfigureAwait(false);
            _totals[path] = doc.Seconds;
            return doc.Seconds;
        }
    }

    /// <summary>Every game this person has play time for: item id → seconds (games with none are left out).</summary>
    /// <param name="user">User id.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>Totals.</returns>
    public async Task<IReadOnlyDictionary<Guid, long>> ListAsync(Guid user, CancellationToken cancellationToken)
    {
        if (user == Guid.Empty)
        {
            throw new ArgumentException("Missing user.", nameof(user));
        }

        var dir = Path.Combine(_root, user.ToString("N"));
        var result = new Dictionary<Guid, long>();
        if (!Directory.Exists(dir))
        {
            return result;
        }

        foreach (var f in Directory.EnumerateFiles(dir, "*.json"))
        {
            if (Guid.TryParseExact(Path.GetFileNameWithoutExtension(f), "N", out var item))
            {
                var s = await GetAsync(user, item, cancellationToken).ConfigureAwait(false);
                if (s > 0)
                {
                    result[item] = s;
                }
            }
        }

        return result;
    }

    /// <summary>
    /// Adds sessions (already checked with <see cref="Validate"/>). A session id already counted for this game, or twice
    /// in the batch, is skipped. Only the last <see cref="KeepIds"/> ids are remembered.
    /// </summary>
    /// <param name="user">User id.</param>
    /// <param name="item">Item id.</param>
    /// <param name="sessions">Sessions.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>The new total, and how many sessions were counted now.</returns>
    public async Task<(long Total, int Added)> AddAsync(Guid user, Guid item, IEnumerable<PlaySession> sessions, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(sessions);
        var path = PathFor(user, item);
        using (await _locks.AcquireAsync(path, cancellationToken).ConfigureAwait(false))
        {
            var doc = await ReadAsync(path, cancellationToken).ConfigureAwait(false);
            var seen = doc.Recent.ToHashSet(StringComparer.Ordinal);
            var added = 0;
            foreach (var s in sessions)
            {
                if (s.Id is null || !seen.Add(s.Id))
                {
                    continue;
                }

                doc.Seconds += Math.Clamp(s.Seconds, 0, MaxSessionSeconds);
                doc.Recent.Add(s.Id);
                added++;
            }

            if (added > 0)
            {
                Trim(doc);
                await WriteAsync(path, doc, cancellationToken).ConfigureAwait(false);
            }

            _totals[path] = doc.Seconds;
            return (doc.Seconds, added);
        }
    }

    /// <summary>Moves a game's play time to another game (its save moved there, B9): added to the target's, and the
    /// remembered session ids joined. The old game's file is removed once the target's is written.</summary>
    /// <param name="user">User id.</param>
    /// <param name="from">Old item id.</param>
    /// <param name="to">New item id.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>Seconds moved.</returns>
    public async Task<long> MoveAsync(Guid user, Guid from, Guid to, CancellationToken cancellationToken)
    {
        if (from == to)
        {
            return 0;
        }

        var src = PathFor(user, from);
        var dst = PathFor(user, to);
        var (first, second) = string.CompareOrdinal(src, dst) < 0 ? (src, dst) : (dst, src);
        using (await _locks.AcquireAsync(first, cancellationToken).ConfigureAwait(false))
        using (await _locks.AcquireAsync(second, cancellationToken).ConfigureAwait(false))
        {
            if (!File.Exists(src))
            {
                return 0;
            }

            var a = await ReadAsync(src, cancellationToken).ConfigureAwait(false);
            var b = await ReadAsync(dst, cancellationToken).ConfigureAwait(false);
            var seen = b.Recent.ToHashSet(StringComparer.Ordinal);
            b.Seconds += a.Seconds;
            b.Recent.AddRange(a.Recent.Where(seen.Add));
            Trim(b);
            await WriteAsync(dst, b, cancellationToken).ConfigureAwait(false);
            _totals[dst] = b.Seconds;
            File.Delete(src);
            _totals[src] = 0;
            return a.Seconds;
        }
    }

    private static void Trim(PlayTimeDoc doc)
    {
        if (doc.Recent.Count > KeepIds)
        {
            doc.Recent.RemoveRange(0, doc.Recent.Count - KeepIds);
        }
    }

    private async Task<PlayTimeDoc> ReadAsync(string path, CancellationToken cancellationToken)
    {
        if (!File.Exists(path))
        {
            return new PlayTimeDoc();
        }

        var bytes = await File.ReadAllBytesAsync(path, cancellationToken).ConfigureAwait(false);
        try
        {
            var doc = JsonSerializer.Deserialize<PlayTimeDoc>(bytes, Json);
            if (doc is not null && doc.Seconds >= 0 && doc.Recent is not null && doc.Recent.TrueForAll(IsSessionId))
            {
                return doc;
            }
        }
        catch (JsonException)
        {
            // handled below
        }

        var keep = AtomicFile.KeepDamaged(path, _clock.GetUtcNow());
        _logger.LogWarning("Play time file {Path} was damaged; kept it as {Copy} and started that game's play time afresh", path, keep);
        return new PlayTimeDoc();
    }

    private static Task WriteAsync(string path, PlayTimeDoc doc, CancellationToken cancellationToken) =>
        AtomicFile.WriteAsync(path, JsonSerializer.SerializeToUtf8Bytes(doc, Json), true, cancellationToken);

    private string PathFor(Guid user, Guid item)
    {
        if (user == Guid.Empty || item == Guid.Empty)
        {
            throw new ArgumentException("Missing user or game.");
        }

        return Path.Combine(_root, user.ToString("N"), item.ToString("N") + ".json");
    }
}
