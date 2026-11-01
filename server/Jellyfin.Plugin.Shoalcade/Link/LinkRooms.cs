using System;
using System.Collections.Generic;
using System.Linq;
using System.Security.Cryptography;
using System.Threading;

namespace Jellyfin.Plugin.Shoalcade.Link;

/// <summary>Limits for internet link play. The defaults are what the plugin uses; tests shrink them.</summary>
internal sealed record LinkRelayOptions
{
    /// <summary>Gets the most rooms open on the server at once.</summary>
    public int MaxRooms { get; init; } = 16;

    /// <summary>Gets how long a room waits for its second phone.</summary>
    public TimeSpan UnjoinedLifetime { get; init; } = TimeSpan.FromMinutes(10);

    /// <summary>Gets how long a seat may go without any request before it is dropped (the app sends one every 10 s).</summary>
    public TimeSpan SeatIdle { get; init; } = TimeSpan.FromSeconds(30);

    /// <summary>Gets the longest quiet time on a down stream before a heartbeat record.</summary>
    public TimeSpan Heartbeat { get; init; } = TimeSpan.FromSeconds(15);

    /// <summary>Gets the most bytes waiting for one phone; more and the room closes (that phone stopped reading).</summary>
    public int MaxUndelivered { get; init; } = 128 * 1024;

    /// <summary>Gets the bytes kept after writing, so a phone can reconnect its down stream without losing any.</summary>
    public int Retain { get; init; } = 128 * 1024;

    /// <summary>Gets the largest single upload.</summary>
    public int MaxPostBytes { get; init; } = 64 * 1024;

    /// <summary>Gets uploads per second per seat (sustained); the burst is twice this.</summary>
    public double PostsPerSecond { get; init; } = 50;

    /// <summary>Gets bytes per second per seat (sustained); the burst is four times this.</summary>
    public double BytesPerSecond { get; init; } = 32 * 1024;

    /// <summary>Gets rooms one user may open per <see cref="CreateWindow"/>.</summary>
    public int CreatesPerWindow { get; init; } = 10;

    /// <summary>Gets the window for <see cref="CreatesPerWindow"/>.</summary>
    public TimeSpan CreateWindow { get; init; } = TimeSpan.FromMinutes(10);

    /// <summary>Gets join tries one user may make per minute (right or wrong room code).</summary>
    public int JoinsPerMinute { get; init; } = 10;

    /// <summary>Gets down-stream opens per seat per minute (reconnects).</summary>
    public int DownOpensPerMinute { get; init; } = 30;
}

/// <summary>What happened to an upload.</summary>
internal enum UpOutcome
{
    /// <summary>Passed on to the other phone.</summary>
    Sent,

    /// <summary>Already had that one (a retry): ignored.</summary>
    Duplicate,

    /// <summary>A sequence number was skipped.</summary>
    BadSequence,

    /// <summary>No other phone in that generation (it left).</summary>
    PeerGone,

    /// <summary>Over the rate limit: try the same one again shortly.</summary>
    TooFast,

    /// <summary>The room is closed (also when the other phone stopped reading).</summary>
    Closed,
}

/// <summary>One phone's place in a room. Known to its phone only by <see cref="Token"/> (a header, never a URL).</summary>
internal sealed class LinkSeat
{
    internal LinkSeat(string token, Guid user, LinkRoom room, bool isHost, int gen, LinkRelayOptions o, DateTimeOffset now)
    {
        Token = token;
        User = user;
        Room = room;
        IsHost = isHost;
        Gen = gen;
        Down = new DownStream(o.MaxUndelivered, o.Retain);
        LastSeen = now;
        Posts = new TokenBucket(o.PostsPerSecond * 2, o.PostsPerSecond, now);
        Bytes = new TokenBucket(o.BytesPerSecond * 4, o.BytesPerSecond, now);
        Opens = new TokenBucket(o.DownOpensPerMinute, o.DownOpensPerMinute / 60.0, now);
    }

    public string Token { get; }

    public Guid User { get; }

    public LinkRoom Room { get; }

    public bool IsHost { get; }

    /// <summary>Gets the joiner's generation (the host's is the room's current one).</summary>
    public int Gen { get; }

    public DownStream Down { get; }

    public DateTimeOffset LastSeen { get; set; }

    /// <summary>Gets or sets the last upload sequence number taken (uploads start at 1).</summary>
    public long LastSeq { get; set; }

    public TokenBucket Posts { get; }

    public TokenBucket Bytes { get; }

    public TokenBucket Opens { get; }
}

/// <summary>A room: one host seat and at most one joiner seat.</summary>
internal sealed class LinkRoom
{
    internal LinkRoom(string code, string console, DateTimeOffset now)
    {
        Code = code;
        Console = console;
        WaitingSince = now;
    }

    public string Code { get; }

    public string Console { get; }

    public LinkSeat Host { get; set; } = null!;

    public LinkSeat? Joiner { get; set; }

    /// <summary>Gets or sets the generation: goes up by one for each phone that joins, so bytes for one never reach the next.</summary>
    public int Gen { get; set; }

    /// <summary>Gets or sets when the room last had no second phone.</summary>
    public DateTimeOffset WaitingSince { get; set; }

    public bool Closed { get; set; }
}

/// <summary>A token bucket on a clock.</summary>
internal sealed class TokenBucket
{
    private readonly double _capacity;
    private readonly double _perSecond;
    private double _tokens;
    private DateTimeOffset _at;

    public TokenBucket(double capacity, double perSecond, DateTimeOffset now)
    {
        _capacity = capacity;
        _perSecond = perSecond;
        _tokens = capacity;
        _at = now;
    }

    public bool TryTake(double n, DateTimeOffset now)
    {
        var secs = Math.Max(0, (now - _at).TotalSeconds);
        _tokens = Math.Min(_capacity, _tokens + (secs * _perSecond));
        _at = now;
        if (_tokens < n)
        {
            return false;
        }

        _tokens -= n;
        return true;
    }
}

/// <summary>
/// Internet link play rooms (D-link-relay): two phones logged in to this Jellyfin swap their link bytes through here.
/// <b>A dumb byte pipe</b>: nothing here reads, keeps or logs the bytes, which only ever sit in memory, bounded. The
/// phones' own PIN handshake (D133) runs end to end on top, so this server never sees the PIN.
/// One room per host user (opening another closes the old one), two seats per room, rooms expire unjoined after
/// <see cref="LinkRelayOptions.UnjoinedLifetime"/>, and a seat with no request for <see cref="LinkRelayOptions.SeatIdle"/>
/// is dropped (a host's drop closes the room).
/// </summary>
public sealed class LinkRooms
{
    /// <summary>Room code letters: no 0/O or 1/I, so a code read across a room is typed right.</summary>
    internal const string CodeAlphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    /// <summary>Room code length (32^6 ≈ a billion codes, with 10 join tries a minute per user).</summary>
    internal const int CodeLength = 6;

    private readonly Lock _gate = new();
    private readonly TimeProvider _clock;
    private readonly Dictionary<string, LinkRoom> _rooms = new(StringComparer.Ordinal);
    private readonly Dictionary<string, LinkSeat> _seats = new(StringComparer.Ordinal);
    private readonly Dictionary<Guid, Queue<DateTimeOffset>> _creates = [];
    private readonly Dictionary<Guid, Queue<DateTimeOffset>> _joins = [];

    /// <summary>Initializes a new instance of the <see cref="LinkRooms"/> class.</summary>
    /// <param name="clock">Clock.</param>
    public LinkRooms(TimeProvider clock)
        : this(clock, new LinkRelayOptions())
    {
    }

    internal LinkRooms(TimeProvider clock, LinkRelayOptions options)
    {
        _clock = clock;
        Options = options;
    }

    internal LinkRelayOptions Options { get; }

    internal int RoomCount
    {
        get
        {
            lock (_gate)
            {
                return _rooms.Count;
            }
        }
    }

    /// <summary>Normalises a typed room code (upper case, spaces and dashes dropped); null if it can't be one.</summary>
    internal static string? CleanCode(string? typed)
    {
        if (typed is null)
        {
            return null;
        }

        var c = new string(typed.Where(ch => ch is not (' ' or '-')).Select(char.ToUpperInvariant).ToArray());
        return c.Length == CodeLength && c.All(ch => CodeAlphabet.Contains(ch, StringComparison.Ordinal)) ? c : null;
    }

    /// <summary>Opens a room for <paramref name="user"/> (closing any room they already host).</summary>
    internal (LinkSeat? Seat, string? Error) Create(Guid user, string console)
    {
        var now = _clock.GetUtcNow();
        lock (_gate)
        {
            SweepLocked(now);
            if (!Allow(_creates, user, Options.CreatesPerWindow, Options.CreateWindow, now))
            {
                return (null, "too_many");
            }

            foreach (var old in _rooms.Values.Where(r => r.Host.User == user).ToList())
            {
                CloseLocked(old, "replaced");
            }

            if (_rooms.Count >= Options.MaxRooms)
            {
                return (null, "busy");
            }

            string code;
            do
            {
                code = new string(RandomNumberGenerator.GetItems<char>(CodeAlphabet, CodeLength));
            }
            while (_rooms.ContainsKey(code));

            var room = new LinkRoom(code, console, now);
            room.Host = new LinkSeat(NewToken(), user, room, true, 0, Options, now);
            _rooms[code] = room;
            _seats[room.Host.Token] = room.Host;
            return (room.Host, null);
        }
    }

    /// <summary>Takes the second seat in the room with this code.</summary>
    internal (LinkSeat? Seat, string? Error) Join(Guid user, string? typedCode, string console)
    {
        var now = _clock.GetUtcNow();
        lock (_gate)
        {
            SweepLocked(now);
            if (!Allow(_joins, user, Options.JoinsPerMinute, TimeSpan.FromMinutes(1), now))
            {
                return (null, "too_many");
            }

            var code = CleanCode(typedCode);
            if (code is null || !_rooms.TryGetValue(code, out var room) || room.Closed)
            {
                return (null, "no_room");
            }

            if (!string.Equals(room.Console, console, StringComparison.Ordinal))
            {
                return (null, "other_console");
            }

            if (room.Joiner is not null)
            {
                return (null, "full");
            }

            room.Gen++;
            var seat = new LinkSeat(NewToken(), user, room, false, room.Gen, Options, now);
            if (!room.Host.Down.AppendGen(LinkRecord.PeerJoined, room.Gen))
            {
                CloseLocked(room, "overflow");
                return (null, "no_room");
            }

            room.Joiner = seat;
            _seats[seat.Token] = seat;
            return (seat, null);
        }
    }

    /// <summary>The caller's seat (and marks it as seen), or null: unknown token, another user's, or closed.</summary>
    internal LinkSeat? Find(Guid user, string? token)
    {
        if (string.IsNullOrEmpty(token) || token.Length > 64)
        {
            return null;
        }

        var now = _clock.GetUtcNow();
        lock (_gate)
        {
            SweepLocked(now);
            if (!_seats.TryGetValue(token, out var seat) || seat.User != user || seat.Room.Closed)
            {
                return null;
            }

            seat.LastSeen = now;
            return seat;
        }
    }

    /// <summary>Starts a down-stream reader for the seat at byte <paramref name="from"/>.</summary>
    internal (long Reader, string? Error) OpenDown(LinkSeat seat, long from)
    {
        lock (_gate)
        {
            if (!seat.Opens.TryTake(1, _clock.GetUtcNow()))
            {
                return (0, "too_many");
            }
        }

        return seat.Down.TryBeginRead(from, out var reader) ? (reader, null) : (0, "gone");
    }

    /// <summary>Passes one upload to the other phone. <paramref name="seq"/> must be one more than the last.</summary>
    internal UpOutcome Up(LinkSeat seat, long seq, int gen, ReadOnlySpan<byte> data)
    {
        var now = _clock.GetUtcNow();
        lock (_gate)
        {
            var room = seat.Room;
            if (room.Closed)
            {
                return UpOutcome.Closed;
            }

            if (seq <= seat.LastSeq)
            {
                return UpOutcome.Duplicate;
            }

            if (seq != seat.LastSeq + 1)
            {
                return UpOutcome.BadSequence;
            }

            var to = seat.IsHost ? room.Joiner : room.Host;
            if (to is null || gen != room.Gen || (!seat.IsHost && seat.Gen != room.Gen))
            {
                seat.LastSeq = seq; // nobody to give it to: taken (and dropped), so a retry is a duplicate
                return UpOutcome.PeerGone;
            }

            if (!seat.Posts.TryTake(1, now) || !seat.Bytes.TryTake(data.Length, now))
            {
                return UpOutcome.TooFast;
            }

            if (!to.Down.Append(LinkRecord.Data, data))
            {
                CloseLocked(room, "overflow");
                return UpOutcome.Closed;
            }

            seat.LastSeq = seq;
            return UpOutcome.Sent;
        }
    }

    /// <summary>The host sends the joiner of generation <paramref name="gen"/> away (e.g. a wrong PIN); the seat is free again.</summary>
    internal void Kick(LinkSeat host, int gen)
    {
        lock (_gate)
        {
            if (host.IsHost && host.Room.Joiner is { } j && j.Gen == gen)
            {
                FreeSeatLocked(host.Room, "sent_away", _clock.GetUtcNow());
            }
        }
    }

    /// <summary>The phone leaves: a joiner frees its seat; the host closes the room.</summary>
    internal void Leave(LinkSeat seat)
    {
        lock (_gate)
        {
            if (seat.Room.Closed)
            {
                return;
            }

            if (seat.IsHost)
            {
                CloseLocked(seat.Room, "host_left");
            }
            else if (seat.Room.Joiner == seat)
            {
                FreeSeatLocked(seat.Room, "left", _clock.GetUtcNow());
            }
        }
    }

    /// <summary>Closes expired rooms and drops seats that stopped calling. Runs on every call and on each quiet stream.</summary>
    internal void Sweep()
    {
        lock (_gate)
        {
            SweepLocked(_clock.GetUtcNow());
        }
    }

    private static string NewToken() => Convert.ToHexStringLower(RandomNumberGenerator.GetBytes(16));

    private static bool Allow(Dictionary<Guid, Queue<DateTimeOffset>> log, Guid user, int max, TimeSpan window, DateTimeOffset now)
    {
        if (!log.TryGetValue(user, out var q))
        {
            q = new Queue<DateTimeOffset>();
            log[user] = q;
        }

        while (q.Count > 0 && now - q.Peek() >= window)
        {
            q.Dequeue();
        }

        if (q.Count >= max)
        {
            return false;
        }

        q.Enqueue(now);
        return true;
    }

    private static void Prune(Dictionary<Guid, Queue<DateTimeOffset>> log, TimeSpan window, DateTimeOffset now)
    {
        foreach (var (user, q) in log.ToList())
        {
            while (q.Count > 0 && now - q.Peek() >= window)
            {
                q.Dequeue();
            }

            if (q.Count == 0)
            {
                log.Remove(user);
            }
        }
    }

    private void SweepLocked(DateTimeOffset now)
    {
        foreach (var room in _rooms.Values.ToList())
        {
            if (now - room.Host.LastSeen > Options.SeatIdle)
            {
                CloseLocked(room, "host_gone");
            }
            else if (room.Joiner is { } j && now - j.LastSeen > Options.SeatIdle)
            {
                FreeSeatLocked(room, "gone", now);
            }
            else if (room.Joiner is null && now - room.WaitingSince > Options.UnjoinedLifetime)
            {
                CloseLocked(room, "expired");
            }
        }

        Prune(_creates, Options.CreateWindow, now);
        Prune(_joins, TimeSpan.FromMinutes(1), now);
    }

    private void FreeSeatLocked(LinkRoom room, string reason, DateTimeOffset now)
    {
        var j = room.Joiner;
        if (j is null)
        {
            return;
        }

        room.Joiner = null;
        room.WaitingSince = now;
        _seats.Remove(j.Token);
        j.Down.Close(reason);
        if (!room.Host.Down.AppendGen(LinkRecord.PeerLeft, j.Gen))
        {
            CloseLocked(room, "overflow");
        }
    }

    private void CloseLocked(LinkRoom room, string reason)
    {
        if (room.Closed)
        {
            return;
        }

        room.Closed = true;
        _rooms.Remove(room.Code);
        _seats.Remove(room.Host.Token);
        room.Host.Down.Close(reason);
        if (room.Joiner is { } j)
        {
            _seats.Remove(j.Token);
            j.Down.Close(reason);
        }
    }
}
