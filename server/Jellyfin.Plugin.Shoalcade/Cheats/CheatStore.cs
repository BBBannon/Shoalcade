using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Plugin.Shoalcade.Common;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;

namespace Jellyfin.Plugin.Shoalcade.Cheats;

/// <summary>One cheat in a game's library.</summary>
public sealed class CheatEntry
{
    /// <summary>Gets or sets the cheat's id (12 hex characters, made by the server).</summary>
    [JsonPropertyName("id")]
    public string Id { get; set; } = string.Empty;

    /// <summary>Gets or sets the name the person gave it.</summary>
    [JsonPropertyName("name")]
    public string Name { get; set; } = string.Empty;

    /// <summary>Gets or sets the code: one or more lines, as typed (tidied).</summary>
    [JsonPropertyName("code")]
    public string Code { get; set; } = string.Empty;

    /// <summary>Gets or sets when it was added (UTC).</summary>
    [JsonPropertyName("added")]
    public DateTimeOffset Added { get; set; }
}

/// <summary>A cheat as stored with a snapshot: its name and code, so the snapshot brings back exactly what was on.</summary>
/// <param name="Name">Name.</param>
/// <param name="Code">Code.</param>
public sealed record SnapshotCheat(
    [property: JsonPropertyName("name")] string Name,
    [property: JsonPropertyName("code")] string Code);

/// <summary>A game's cheats for one person: the library, and which ones are on for the in-game save.</summary>
public sealed class CheatDoc
{
    /// <summary>Gets or sets every cheat added for this game, oldest first.</summary>
    [JsonPropertyName("cheats")]
    public List<CheatEntry> Cheats { get; set; } = [];

    /// <summary>Gets or sets the ids of the cheats that are on when playing from the in-game save, in the order they're applied.</summary>
    [JsonPropertyName("active")]
    public List<string> Active { get; set; } = [];
}

/// <summary>What a cheat change did.</summary>
public enum CheatOutcome
{
    /// <summary>Done.</summary>
    Ok,

    /// <summary>Added a new cheat.</summary>
    Created,

    /// <summary>The same code was already there: that cheat is returned.</summary>
    Existing,

    /// <summary>No cheat with that id.</summary>
    NotFound,

    /// <summary>The game has as many cheats as allowed.</summary>
    Full,
}

/// <summary>Result of a cheat change.</summary>
/// <param name="Outcome">What happened.</param>
/// <param name="Entry">The cheat (added, found or changed), if any.</param>
/// <param name="Doc">The game's cheats afterwards.</param>
public sealed record CheatResult(CheatOutcome Outcome, CheatEntry? Entry, CheatDoc Doc);

/// <summary>
/// Cheat codes per person per game (D143), at <c>{root}/{userN}/{itemN}.json</c>. Kept apart from saves so a save move
/// or clean-up never touches them. Only codes the person typed or imported: no cheat database. Every change is a
/// small separate operation (add, edit, delete, set the active list), so the phone and the browser can't wipe out
/// each other's additions. A damaged file is moved aside (kept, never deleted) and read as empty.
/// </summary>
public sealed class CheatStore
{
    /// <summary>Most cheats one game can have.</summary>
    public const int MaxCheats = 200;

    /// <summary>Longest name.</summary>
    public const int MaxName = 60;

    /// <summary>Longest code (all lines).</summary>
    public const int MaxCode = 4000;

    /// <summary>Most lines in one code.</summary>
    public const int MaxLines = 64;

    /// <summary>Largest request body the controller reads.</summary>
    public const int MaxBodyBytes = 16 * 1024;

    private static readonly JsonSerializerOptions Json = new() { WriteIndented = false };

    private readonly string _root;
    private readonly TimeProvider _clock;
    private readonly ILogger _logger;
    private readonly KeyedLocks _locks = new();

    /// <summary>Initializes a new instance of the <see cref="CheatStore"/> class.</summary>
    /// <param name="root">Cheats root folder.</param>
    /// <param name="clock">Clock.</param>
    /// <param name="logger">Logger.</param>
    public CheatStore(string root, TimeProvider? clock = null, ILogger? logger = null)
    {
        _root = Path.GetFullPath(root);
        _clock = clock ?? TimeProvider.System;
        _logger = logger ?? NullLogger.Instance;
    }

    /// <summary>Gets the root folder.</summary>
    public string Root => _root;

    /// <summary>
    /// Tidies a name: trimmed, inner runs of spaces made single. Null if empty, too long or has control characters.
    /// </summary>
    /// <param name="name">Name as sent.</param>
    /// <returns>The tidy name, or null.</returns>
    public static string? CleanName(string? name)
    {
        if (name is null)
        {
            return null;
        }

        var s = string.Join(' ', name.Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries));
        if (s.Length == 0 || s.Length > MaxName || s.Any(char.IsControl))
        {
            return null;
        }

        return s;
    }

    /// <summary>
    /// Tidies a code: each line trimmed, inner spaces made single, blank lines dropped, lines joined with "\n".
    /// Allowed: letters, digits, space and <c>+ - : _ . ,</c> (GameShark, Action Replay, CodeBreaker, Game Genie,
    /// CWCheat). Null if empty, too long, too many lines or anything else in it. Letter case is kept as typed.
    /// </summary>
    /// <param name="code">Code as sent.</param>
    /// <returns>The tidy code, or null.</returns>
    public static string? CleanCode(string? code)
    {
        if (code is null)
        {
            return null;
        }

        var lines = code.Replace("\r\n", "\n", StringComparison.Ordinal).Replace('\r', '\n').Split('\n')
            .Select(l => string.Join(' ', l.Split([' ', '\t'], StringSplitOptions.RemoveEmptyEntries)))
            .Where(l => l.Length > 0)
            .ToList();
        var s = string.Join('\n', lines);
        if (s.Length == 0 || s.Length > MaxCode || lines.Count > MaxLines)
        {
            return null;
        }

        foreach (var c in s)
        {
            if (!(char.IsAsciiLetterOrDigit(c) || c is ' ' or '\n' or '+' or '-' or ':' or '_' or '.' or ','))
            {
                return null;
            }
        }

        return s;
    }

    /// <summary>The same code, compared ignoring spaces, line breaks and letter case (to spot a cheat added twice).</summary>
    /// <param name="code">A tidy code.</param>
    /// <returns>The comparison key.</returns>
    public static string CodeKey(string code)
    {
        var b = new StringBuilder(code.Length);
        foreach (var c in code)
        {
            if (c is not (' ' or '\n' or '+'))
            {
                b.Append(char.ToUpperInvariant(c));
            }
        }

        return b.ToString();
    }

    /// <summary>Reads a game's cheats (empty if none).</summary>
    /// <param name="user">User id.</param>
    /// <param name="item">Item id.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>The cheats.</returns>
    public async Task<CheatDoc> GetAsync(Guid user, Guid item, CancellationToken cancellationToken)
    {
        var path = PathFor(user, item);
        using (await _locks.AcquireAsync(path, cancellationToken).ConfigureAwait(false))
        {
            return await ReadAsync(path, cancellationToken).ConfigureAwait(false);
        }
    }

    /// <summary>Adds a cheat. A code already in the list (ignoring spaces and case) isn't added twice: that one is returned.</summary>
    /// <param name="user">User id.</param>
    /// <param name="item">Item id.</param>
    /// <param name="name">Tidy name.</param>
    /// <param name="code">Tidy code.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>Created, Existing or Full.</returns>
    public Task<CheatResult> AddAsync(Guid user, Guid item, string name, string code, CancellationToken cancellationToken) =>
        MutateAsync(user, item, doc =>
        {
            var key = CodeKey(code);
            var same = doc.Cheats.Find(c => CodeKey(c.Code) == key);
            if (same is not null)
            {
                return (CheatOutcome.Existing, same, false);
            }

            if (doc.Cheats.Count >= MaxCheats)
            {
                return (CheatOutcome.Full, null, false);
            }

            var e = new CheatEntry { Id = NewId(doc), Name = name, Code = code, Added = _clock.GetUtcNow() };
            doc.Cheats.Add(e);
            return (CheatOutcome.Created, e, true);
        }, cancellationToken);

    /// <summary>Renames a cheat and/or changes its code.</summary>
    /// <param name="user">User id.</param>
    /// <param name="item">Item id.</param>
    /// <param name="id">Cheat id.</param>
    /// <param name="name">New tidy name, or null to keep.</param>
    /// <param name="code">New tidy code, or null to keep.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>Ok or NotFound.</returns>
    public Task<CheatResult> UpdateAsync(Guid user, Guid item, string id, string? name, string? code, CancellationToken cancellationToken) =>
        MutateAsync(user, item, doc =>
        {
            var e = doc.Cheats.Find(c => c.Id == id);
            if (e is null)
            {
                return (CheatOutcome.NotFound, null, false);
            }

            e.Name = name ?? e.Name;
            e.Code = code ?? e.Code;
            return (CheatOutcome.Ok, e, true);
        }, cancellationToken);

    /// <summary>Deletes a cheat (and takes it off the active list).</summary>
    /// <param name="user">User id.</param>
    /// <param name="item">Item id.</param>
    /// <param name="id">Cheat id.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>Ok or NotFound.</returns>
    public Task<CheatResult> DeleteAsync(Guid user, Guid item, string id, CancellationToken cancellationToken) =>
        MutateAsync(user, item, doc =>
        {
            var removed = doc.Cheats.RemoveAll(c => c.Id == id);
            doc.Active.RemoveAll(a => a == id);
            return removed == 0 ? (CheatOutcome.NotFound, null, false) : (CheatOutcome.Ok, null, true);
        }, cancellationToken);

    /// <summary>Sets which cheats are on for the in-game save. Unknown ids and repeats are dropped; order is kept.</summary>
    /// <param name="user">User id.</param>
    /// <param name="item">Item id.</param>
    /// <param name="ids">Cheat ids.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>Ok.</returns>
    public Task<CheatResult> SetActiveAsync(Guid user, Guid item, IEnumerable<string> ids, CancellationToken cancellationToken) =>
        MutateAsync(user, item, doc =>
        {
            var known = doc.Cheats.Select(c => c.Id).ToHashSet(StringComparer.Ordinal);
            doc.Active = ids.Where(known.Contains).Distinct(StringComparer.Ordinal).ToList();
            return (CheatOutcome.Ok, null, true);
        }, cancellationToken);

    /// <summary>
    /// Moves a game's cheats to another game (its save moved there, B9). Cheats are added to the target's library
    /// (codes already there aren't doubled; past the limit the rest stay with the old game). The target's active list
    /// is kept if it has one, else the moved one is used. The old game's file is removed only once everything moved.
    /// </summary>
    /// <param name="user">User id.</param>
    /// <param name="from">Old item id.</param>
    /// <param name="to">New item id.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>How many cheats moved, and how many stayed behind (target full).</returns>
    public async Task<(int Moved, int Kept)> MoveAsync(Guid user, Guid from, Guid to, CancellationToken cancellationToken)
    {
        if (from == to)
        {
            return (0, 0);
        }

        var src = PathFor(user, from);
        var dst = PathFor(user, to);
        var (first, second) = string.CompareOrdinal(src, dst) < 0 ? (src, dst) : (dst, src);
        using (await _locks.AcquireAsync(first, cancellationToken).ConfigureAwait(false))
        using (await _locks.AcquireAsync(second, cancellationToken).ConfigureAwait(false))
        {
            if (!File.Exists(src))
            {
                return (0, 0);
            }

            var a = await ReadAsync(src, cancellationToken).ConfigureAwait(false);
            var b = await ReadAsync(dst, cancellationToken).ConfigureAwait(false);
            var map = new Dictionary<string, string>(StringComparer.Ordinal);
            var left = new List<CheatEntry>();
            foreach (var c in a.Cheats)
            {
                var same = b.Cheats.Find(x => CodeKey(x.Code) == CodeKey(c.Code));
                if (same is not null)
                {
                    map[c.Id] = same.Id;
                }
                else if (b.Cheats.Count < MaxCheats)
                {
                    var e = new CheatEntry { Id = NewId(b), Name = c.Name, Code = c.Code, Added = c.Added };
                    b.Cheats.Add(e);
                    map[c.Id] = e.Id;
                }
                else
                {
                    left.Add(c);
                }
            }

            if (b.Active.Count == 0)
            {
                b.Active = a.Active.Where(map.ContainsKey).Select(x => map[x]).Distinct(StringComparer.Ordinal).ToList();
            }

            await WriteAsync(dst, b, cancellationToken).ConfigureAwait(false);
            if (left.Count == 0)
            {
                File.Delete(src);
            }
            else
            {
                a.Cheats = left;
                a.Active.RemoveAll(x => !left.Exists(c => c.Id == x));
                await WriteAsync(src, a, cancellationToken).ConfigureAwait(false);
            }

            return (map.Count, left.Count);
        }
    }

    private async Task<CheatResult> MutateAsync(Guid user, Guid item, Func<CheatDoc, (CheatOutcome Outcome, CheatEntry? Entry, bool Changed)> change, CancellationToken cancellationToken)
    {
        var path = PathFor(user, item);
        using (await _locks.AcquireAsync(path, cancellationToken).ConfigureAwait(false))
        {
            var doc = await ReadAsync(path, cancellationToken).ConfigureAwait(false);
            var (outcome, entry, changed) = change(doc);
            if (changed)
            {
                await WriteAsync(path, doc, cancellationToken).ConfigureAwait(false);
            }

            return new CheatResult(outcome, entry, doc);
        }
    }

    private async Task<CheatDoc> ReadAsync(string path, CancellationToken cancellationToken)
    {
        if (!File.Exists(path))
        {
            return new CheatDoc();
        }

        var bytes = await File.ReadAllBytesAsync(path, cancellationToken).ConfigureAwait(false);
        try
        {
            var doc = JsonSerializer.Deserialize<CheatDoc>(bytes, Json);
            if (doc is not null && doc.Cheats is not null && doc.Active is not null && doc.Cheats.TrueForAll(Valid))
            {
                return doc;
            }
        }
        catch (JsonException)
        {
            // handled below
        }

        var stamp = _clock.GetUtcNow().ToString("yyyyMMddHHmmss", CultureInfo.InvariantCulture);
        var keep = path + ".damaged-" + stamp;
        for (var n = 2; File.Exists(keep); n++)
        {
            keep = path + ".damaged-" + stamp + "-" + n.ToString(CultureInfo.InvariantCulture);
        }

        File.Move(path, keep);
        _logger.LogWarning("Cheats file {Path} was damaged; kept it as {Copy} and started that game's cheats afresh", path, keep);
        return new CheatDoc();
    }

    private static bool Valid(CheatEntry? c) =>
        c is not null && IsId(c.Id) && CleanName(c.Name) == c.Name && CleanCode(c.Code) == c.Code;

    /// <summary>True if the text looks like a cheat id (12 lower-case hex characters).</summary>
    /// <param name="id">Text.</param>
    /// <returns>Whether it is one.</returns>
    public static bool IsId(string? id) => id is { Length: 12 } && id.All(c => c is >= '0' and <= '9' or >= 'a' and <= 'f');

    private static Task WriteAsync(string path, CheatDoc doc, CancellationToken cancellationToken) =>
        AtomicFile.WriteAsync(path, JsonSerializer.SerializeToUtf8Bytes(doc, Json), true, cancellationToken);

    private static string NewId(CheatDoc doc)
    {
        while (true)
        {
            var id = Convert.ToHexString(RandomNumberGenerator.GetBytes(6)).ToLowerInvariant();
            if (!doc.Cheats.Exists(c => c.Id == id))
            {
                return id;
            }
        }
    }

    private string PathFor(Guid user, Guid item)
    {
        if (user == Guid.Empty || item == Guid.Empty)
        {
            throw new ArgumentException("Missing user or game.");
        }

        return Path.Combine(_root, user.ToString("N"), item.ToString("N") + ".json");
    }
}
