using System;
using System.Buffers.Binary;
using System.Threading;
using System.Threading.Tasks;

namespace Jellyfin.Plugin.Shoalcade.Link;

/// <summary>What one record in a seat's down stream is (internet link play). The app reads the same values.</summary>
internal enum LinkRecord : byte
{
    /// <summary>Nothing to say: keeps proxies and the phone's read timeout from closing a quiet stream.</summary>
    Heartbeat = 0,

    /// <summary>Bytes from the other phone, exactly as it sent them. Never looked at here.</summary>
    Data = 1,

    /// <summary>Host only: a phone took the second seat. Payload: its 4-byte generation number.</summary>
    PeerJoined = 2,

    /// <summary>Host only: that phone left (or was sent away). Payload: its 4-byte generation number.</summary>
    PeerLeft = 3,

    /// <summary>The room is closed; the stream ends after this. Payload: a short reason code (ASCII).</summary>
    Closed = 4,
}

/// <summary>
/// One seat's down stream: records waiting for its phone, plus the last few that were already written, so a phone whose
/// connection dropped can carry on from the byte it last got (header <c>X-Shoalcade-From</c>). A record is
/// <c>[type: 1 byte][length: 4 bytes big-endian][payload]</c>; offsets count every byte of every record.
/// Memory is bounded: at most <see cref="_maxUndelivered"/> bytes not yet written (more = the phone stopped reading,
/// and <see cref="Append"/> says so) plus <see cref="_retain"/> already written.
/// </summary>
internal sealed class DownStream
{
    /// <summary>Bytes in a record header.</summary>
    public const int HeaderBytes = 5;

    /// <summary>Most bytes written to the response at once.</summary>
    public const int MaxWrite = 64 * 1024;

    private readonly Lock _gate = new();
    private readonly int _maxUndelivered;
    private readonly int _retain;
    private byte[] _buf = new byte[4096];
    private int _start;
    private int _count;
    private long _base;
    private long _delivered;
    private long _reader;
    private TaskCompletionSource _signal = NewSignal();

    /// <summary>Initializes a new instance of the <see cref="DownStream"/> class.</summary>
    /// <param name="maxUndelivered">Most bytes waiting to be written.</param>
    /// <param name="retain">Bytes kept after writing, for a reconnect.</param>
    public DownStream(int maxUndelivered, int retain)
    {
        _maxUndelivered = maxUndelivered;
        _retain = retain;
    }

    /// <summary>Gets a value indicating whether the room closed (the Closed record is the last one).</summary>
    public bool IsClosed { get; private set; }

    /// <summary>Gets the stream offset just after the last record.</summary>
    public long End
    {
        get
        {
            lock (_gate)
            {
                return _base + _count;
            }
        }
    }

    /// <summary>Gets bytes held in memory now (for tests: never more than the two limits).</summary>
    public int Held
    {
        get
        {
            lock (_gate)
            {
                return _count;
            }
        }
    }

    /// <summary>Adds a record, unless it would leave more than the limit unwritten (the phone isn't reading).</summary>
    /// <param name="type">Record type.</param>
    /// <param name="payload">Payload.</param>
    /// <returns>False if it didn't fit or the room is closed.</returns>
    public bool Append(LinkRecord type, ReadOnlySpan<byte> payload)
    {
        lock (_gate)
        {
            if (IsClosed || (_base + _count - _delivered) + HeaderBytes + payload.Length > _maxUndelivered)
            {
                return false;
            }

            Put(type, payload);
        }

        Pulse();
        return true;
    }

    /// <summary>Adds a record carrying a generation number (PeerJoined / PeerLeft).</summary>
    /// <param name="type">Record type.</param>
    /// <param name="gen">Generation.</param>
    /// <returns>False if it didn't fit or the room is closed.</returns>
    public bool AppendGen(LinkRecord type, int gen)
    {
        Span<byte> b = stackalloc byte[4];
        BinaryPrimitives.WriteInt32BigEndian(b, gen);
        return Append(type, b);
    }

    /// <summary>Ends the stream with a Closed record (always added, whatever the limit), then wakes the reader.</summary>
    /// <param name="reason">Short ASCII reason code, e.g. "host_left".</param>
    public void Close(string reason)
    {
        lock (_gate)
        {
            if (IsClosed)
            {
                return;
            }

            Put(LinkRecord.Closed, System.Text.Encoding.ASCII.GetBytes(reason));
            IsClosed = true;
        }

        Pulse();
    }

    /// <summary>
    /// Starts a reader at <paramref name="from"/>. Any earlier reader for this seat stops (a phone that reconnected).
    /// A reader starting at the end gets a heartbeat first, so the answer's headers and first bytes go out at once.
    /// </summary>
    /// <param name="from">Stream offset the phone has got up to.</param>
    /// <param name="reader">This reader's id.</param>
    /// <returns>False if those bytes are no longer held (or never existed).</returns>
    public bool TryBeginRead(long from, out long reader)
    {
        lock (_gate)
        {
            reader = 0;
            if (from < _base || from > _base + _count)
            {
                return false;
            }

            reader = ++_reader;
            if (from == _base + _count && !IsClosed)
            {
                Put(LinkRecord.Heartbeat, ReadOnlySpan<byte>.Empty);
            }
        }

        Pulse();
        return true;
    }

    /// <summary>
    /// Writes records to the phone as they arrive, one flush per write, until the room closes, a newer reader takes
    /// over or the request ends. When nothing has arrived for <paramref name="heartbeat"/>, a heartbeat is added
    /// and <paramref name="onIdle"/> runs (the room list's tidy-up).
    /// </summary>
    /// <param name="reader">Id from <see cref="TryBeginRead"/>.</param>
    /// <param name="from">Start offset.</param>
    /// <param name="write">Writes and flushes one chunk.</param>
    /// <param name="heartbeat">Longest quiet time.</param>
    /// <param name="onIdle">Runs on each quiet spell.</param>
    /// <param name="cancellationToken">The request's token.</param>
    /// <returns>A task that ends with the stream.</returns>
    public async Task PumpAsync(
        long reader,
        long from,
        Func<ReadOnlyMemory<byte>, CancellationToken, Task> write,
        TimeSpan heartbeat,
        Action? onIdle,
        CancellationToken cancellationToken)
    {
        var pos = from;
        while (true)
        {
            cancellationToken.ThrowIfCancellationRequested();
            byte[]? chunk = null;
            Task wait;
            lock (_gate)
            {
                if (reader != _reader || pos < _base)
                {
                    return; // a newer reader took over, or (never expected) the bytes were let go
                }

                var end = _base + _count;
                if (pos < end)
                {
                    var n = (int)Math.Min(end - pos, MaxWrite);
                    chunk = new byte[n];
                    Array.Copy(_buf, _start + (int)(pos - _base), chunk, 0, n);
                }
                else if (IsClosed)
                {
                    return;
                }

                wait = _signal.Task;
            }

            if (chunk is not null)
            {
                await write(chunk, cancellationToken).ConfigureAwait(false);
                pos += chunk.Length;
                Delivered(pos);
                continue;
            }

            try
            {
                await wait.WaitAsync(heartbeat, cancellationToken).ConfigureAwait(false);
            }
            catch (TimeoutException)
            {
                onIdle?.Invoke();
                lock (_gate)
                {
                    if (!IsClosed && reader == _reader && pos == _base + _count)
                    {
                        Put(LinkRecord.Heartbeat, ReadOnlySpan<byte>.Empty);
                    }
                }
            }
        }
    }

    private static TaskCompletionSource NewSignal() => new(TaskCreationOptions.RunContinuationsAsynchronously);

    private void Delivered(long pos)
    {
        lock (_gate)
        {
            if (pos > _delivered)
            {
                _delivered = pos;
            }
        }
    }

    private void Pulse()
    {
        TaskCompletionSource old;
        lock (_gate)
        {
            old = _signal;
            _signal = NewSignal();
        }

        old.TrySetResult();
    }

    /// <summary>Appends one record (caller holds the lock), first letting go of written bytes beyond the kept window.</summary>
    private void Put(LinkRecord type, ReadOnlySpan<byte> payload)
    {
        var keepFrom = Math.Max(_base, _delivered - _retain);
        var drop = (int)(keepFrom - _base);
        _start += drop;
        _count -= drop;
        _base = keepFrom;

        var need = HeaderBytes + payload.Length;
        if (_start + _count + need > _buf.Length)
        {
            var size = _buf.Length;
            while (size < _count + need)
            {
                size *= 2;
            }

            var next = size == _buf.Length ? _buf : new byte[size];
            Array.Copy(_buf, _start, next, 0, _count);
            _buf = next;
            _start = 0;
        }

        var at = _start + _count;
        _buf[at] = (byte)type;
        BinaryPrimitives.WriteInt32BigEndian(_buf.AsSpan(at + 1, 4), payload.Length);
        payload.CopyTo(_buf.AsSpan(at + HeaderBytes));
        _count += need;
    }
}
