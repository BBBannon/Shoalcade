using System;
using System.Collections.Generic;
using System.Threading;

namespace Jellyfin.Plugin.Shoalcade.Common;

/// <summary>
/// Caps how many uploads one user can have going at once (security M1), so parallel uploads can't eat the server's
/// memory or disk bandwidth. A request over the cap is refused straight away (429), never queued.
/// </summary>
internal sealed class UploadLimiter
{
    /// <summary>Uploads one user may have going at once (saves, snapshots, images and settings together).</summary>
    public const int DefaultPerUser = 3;

    private readonly Dictionary<Guid, int> _active = new();
    private readonly Lock _gate = new();
    private readonly int _perUser;

    /// <summary>Initializes a new instance of the <see cref="UploadLimiter"/> class.</summary>
    /// <param name="perUser">Uploads one user may have going at once.</param>
    public UploadLimiter(int perUser = DefaultPerUser) => _perUser = Math.Max(1, perUser);

    /// <summary>Gets the shared limiter used by the API.</summary>
    public static UploadLimiter Shared { get; } = new();

    /// <summary>Starts an upload for <paramref name="user"/> if they are under the cap.</summary>
    /// <param name="user">User id.</param>
    /// <returns>A handle that ends the upload when disposed, or null if the user already has too many going.</returns>
    public IDisposable? TryEnter(Guid user)
    {
        lock (_gate)
        {
            _active.TryGetValue(user, out var n);
            if (n >= _perUser)
            {
                return null;
            }

            _active[user] = n + 1;
        }

        return new Slot(this, user);
    }

    /// <summary>Uploads going now for a user (for tests).</summary>
    /// <param name="user">User id.</param>
    /// <returns>Count.</returns>
    public int Active(Guid user)
    {
        lock (_gate)
        {
            return _active.TryGetValue(user, out var n) ? n : 0;
        }
    }

    private void Leave(Guid user)
    {
        lock (_gate)
        {
            if (_active.TryGetValue(user, out var n) && n > 1)
            {
                _active[user] = n - 1;
            }
            else
            {
                _active.Remove(user); // No entry left behind, so the table never grows.
            }
        }
    }

    private sealed class Slot : IDisposable
    {
        private readonly Guid _user;
        private UploadLimiter? _owner;

        public Slot(UploadLimiter owner, Guid user)
        {
            _owner = owner;
            _user = user;
        }

        public void Dispose() => Interlocked.Exchange(ref _owner, null)?.Leave(_user);
    }
}
