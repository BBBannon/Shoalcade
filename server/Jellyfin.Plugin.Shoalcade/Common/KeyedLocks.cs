using System;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;

namespace Jellyfin.Plugin.Shoalcade.Common;

/// <summary>
/// One async lock per key (e.g. per game folder), so two uploads to the same game never interleave. A key's entry is
/// removed once nobody holds or waits for it (s0.x L2: requests for made-up ids used to grow the table for ever).
/// </summary>
internal sealed class KeyedLocks
{
    private readonly Dictionary<string, Entry> _locks = new(StringComparer.Ordinal);
    private readonly Lock _gate = new();

    /// <summary>Gets how many keys have an entry now (for tests).</summary>
    public int Count
    {
        get
        {
            lock (_gate)
            {
                return _locks.Count;
            }
        }
    }

    /// <summary>Waits for the lock for <paramref name="key"/>.</summary>
    /// <param name="key">Lock key.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>A handle that releases the lock when disposed.</returns>
    public async Task<IDisposable> AcquireAsync(string key, CancellationToken cancellationToken)
    {
        Entry e;
        lock (_gate)
        {
            if (!_locks.TryGetValue(key, out e!))
            {
                e = new Entry();
                _locks[key] = e;
            }

            e.Users++;
        }

        try
        {
            await e.Semaphore.WaitAsync(cancellationToken).ConfigureAwait(false);
        }
        catch
        {
            Leave(key, e, false);
            throw;
        }

        return new Releaser(this, key, e);
    }

    private void Leave(string key, Entry e, bool held)
    {
        lock (_gate)
        {
            if (held)
            {
                e.Semaphore.Release();
            }

            if (--e.Users == 0)
            {
                _locks.Remove(key); // no one holds or waits for it: nothing left behind (the semaphore is just dropped)
            }
        }
    }

    private sealed class Entry
    {
        public SemaphoreSlim Semaphore { get; } = new(1, 1);

        public int Users { get; set; }
    }

    private sealed class Releaser : IDisposable
    {
        private readonly string _key;
        private readonly Entry _entry;
        private KeyedLocks? _owner;

        public Releaser(KeyedLocks owner, string key, Entry entry)
        {
            _owner = owner;
            _key = key;
            _entry = entry;
        }

        public void Dispose() => Interlocked.Exchange(ref _owner, null)?.Leave(_key, _entry, true);
    }
}
