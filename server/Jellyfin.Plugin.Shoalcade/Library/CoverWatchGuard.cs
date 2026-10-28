using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Linq;

namespace Jellyfin.Plugin.Shoalcade.Library;

/// <summary>
/// Loop guard for the cover watch (G5 dev 2 L1). Putting a cover back updates the item, which raises Jellyfin's
/// ItemUpdated, which runs the watch again. Normally the second run sees the cover in place and stops; if that check ever
/// stays false (an odd path form on a Docker bind-mount or symlinked data folder, an unreadable file), the watch would
/// write the item for ever. So a game's cover is put back at most <see cref="MaxPutBacks"/> times in any
/// <see cref="Window"/>; after that it waits for the window to end, and the trip is logged once.
/// A few put-backs are allowed (not one) because one refresh can save an item more than once, and a scan followed by
/// "Replace all metadata" a few seconds later must still get the cover back at once.
/// </summary>
public sealed class CoverWatchGuard
{
    /// <summary>The time window.</summary>
    public static readonly TimeSpan Window = TimeSpan.FromSeconds(30);

    /// <summary>Put-backs allowed per game in one <see cref="Window"/>.</summary>
    public const int MaxPutBacks = 3;

    private readonly ConcurrentDictionary<Guid, Queue<DateTimeOffset>> _putBacks = new();
    private readonly ConcurrentDictionary<Guid, byte> _told = new();
    private readonly TimeProvider _clock;

    /// <summary>Initializes a new instance of the <see cref="CoverWatchGuard"/> class.</summary>
    /// <param name="clock">Clock (tests use a fake one).</param>
    public CoverWatchGuard(TimeProvider? clock = null)
    {
        _clock = clock ?? TimeProvider.System;
    }

    /// <summary>
    /// How long to wait before this game's cover may be put back again; <see cref="TimeSpan.Zero"/> = go ahead.
    /// </summary>
    /// <param name="id">Item id.</param>
    /// <returns>Time until the oldest put-back in the window leaves it.</returns>
    public TimeSpan Wait(Guid id)
    {
        if (!_putBacks.TryGetValue(id, out var q))
        {
            return TimeSpan.Zero;
        }

        lock (q)
        {
            var now = _clock.GetUtcNow();
            Trim(q, now);
            return q.Count < MaxPutBacks ? TimeSpan.Zero : q.Peek() + Window - now;
        }
    }

    /// <summary>Records that this game's cover was just put back.</summary>
    /// <param name="id">Item id.</param>
    public void PutBack(Guid id)
    {
        var now = _clock.GetUtcNow();
        var q = _putBacks.GetOrAdd(id, _ => new Queue<DateTimeOffset>());
        lock (q)
        {
            Trim(q, now);
            q.Enqueue(now);
        }

        // Forget games with nothing in the window, so the list stays small; they may trip (and log) again later.
        foreach (var kv in _putBacks.Where(kv => kv.Key != id))
        {
            lock (kv.Value)
            {
                Trim(kv.Value, now);
                if (kv.Value.Count == 0 && _putBacks.TryRemove(kv))
                {
                    _told.TryRemove(kv.Key, out _);
                }
            }
        }
    }

    /// <summary>True the first time the guard trips for a game (log it then), false after.</summary>
    /// <param name="id">Item id.</param>
    /// <returns>Whether to log.</returns>
    public bool FirstTrip(Guid id) => _told.TryAdd(id, 0);

    private static void Trim(Queue<DateTimeOffset> q, DateTimeOffset now)
    {
        while (q.Count > 0 && q.Peek() + Window <= now)
        {
            q.Dequeue();
        }
    }
}
