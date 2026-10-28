using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Serialization;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;

namespace Jellyfin.Plugin.Shoalcade.Web;

/// <summary>The phone app bundled in the plugin (D134): its version, size and SHA-256 from <c>app/app.json</c>.</summary>
/// <param name="Version">App version, e.g. "0.6.0".</param>
/// <param name="Size">APK size in bytes.</param>
/// <param name="Sha256">APK SHA-256, lower-case hex.</param>
public sealed record PhoneAppPackage(
    [property: JsonPropertyName("version")] string Version,
    [property: JsonPropertyName("size")] long Size,
    [property: JsonPropertyName("sha256")] string Sha256);

/// <summary>
/// The Android app that <c>server/build.sh</c> copies into the plugin folder (<c>app/Shoalcade.apk</c> plus
/// <c>app/app.json</c>, D134), so a person can install the phone app from their own Jellyfin with no outside contact.
/// A deliberate exception to "only ROM items are served": this one fixed file, never anything a request names.
/// A build without the APK still works; the Settings page then says the app isn't included.
/// </summary>
public sealed class PhoneApp
{
    /// <summary>The APK's file name in the plugin's <c>app/</c> folder and in the download.</summary>
    public const string FileName = "Shoalcade.apk";

    /// <summary>Android's type for an APK.</summary>
    public const string ContentType = "application/vnd.android.package-archive";

    private readonly Func<bool> _enabled;

    /// <summary>Initializes a new instance of the <see cref="PhoneApp"/> class.</summary>
    /// <param name="folder">The plugin's <c>app/</c> folder.</param>
    /// <param name="enabled">Whether the admin offers the download (plugin setting, read on every request).</param>
    /// <param name="logger">Logger.</param>
    public PhoneApp(string folder, Func<bool> enabled, ILogger? logger = null)
    {
        _enabled = enabled;
        logger ??= NullLogger.Instance;
        ApkPath = Path.Combine(folder, FileName);
        Package = Load(folder, ApkPath, out var problem);
        if (problem is not null)
        {
            logger.LogInformation("Phone app download not available: {Problem}", problem);
        }
    }

    /// <summary>Gets a value indicating whether the admin offers the phone app download.</summary>
    public bool Enabled => _enabled();

    /// <summary>Gets the bundled app, or null when this build has none (or its files don't match).</summary>
    public PhoneAppPackage? Package { get; }

    /// <summary>Gets the APK's full path (only served when <see cref="Package"/> is set).</summary>
    public string ApkPath { get; }

    /// <summary>The plugin's own <c>app/</c> folder, next to the DLL.</summary>
    /// <returns>The folder.</returns>
    public static string DefaultFolder() =>
        Path.Combine(Path.GetDirectoryName(typeof(PhoneApp).Assembly.Location) ?? ".", "app");

    private static PhoneAppPackage? Load(string folder, string apk, out string? problem)
    {
        var json = Path.Combine(folder, "app.json");
        if (!File.Exists(json) || !File.Exists(apk))
        {
            problem = "this plugin build doesn't include the app.";
            return null;
        }

        PhoneAppPackage? p;
        try
        {
            p = JsonSerializer.Deserialize<PhoneAppPackage>(File.ReadAllBytes(json));
        }
        catch (JsonException)
        {
            p = null;
        }

        if (p is null || string.IsNullOrWhiteSpace(p.Version) || p.Size <= 0 || p.Sha256 is not { Length: 64 } || !p.Sha256.All(Uri.IsHexDigit))
        {
            problem = "app/app.json is damaged.";
            return null;
        }

        if (new FileInfo(apk).Length != p.Size)
        {
            problem = "the app file's size doesn't match app/app.json.";
            return null;
        }

        problem = null;
        return p with { Sha256 = p.Sha256.ToLowerInvariant() };
    }
}

/// <summary>
/// Short-lived download tickets for the phone app (D134): a phone scanning the Settings page's QR code has no Jellyfin
/// sign-in, so a signed-in person asks for a ticket and the QR code carries it. 128 random bits, URL-safe, valid
/// <see cref="Lifetime"/>, usable <see cref="MaxUses"/> times (download retries), kept in memory only (a restart clears
/// them) and never logged. A ticket only ever gets the bundled APK.
/// </summary>
public sealed class AppTickets
{
    /// <summary>How long a ticket works.</summary>
    public static readonly TimeSpan Lifetime = TimeSpan.FromMinutes(10);

    /// <summary>Downloads one ticket allows (a phone may retry or resume).</summary>
    public const int MaxUses = 5;

    /// <summary>Most tickets kept at once; past that the one closest to running out is dropped.</summary>
    public const int MaxLive = 1000;

    /// <summary>
    /// Most live tickets one person can hold (security review L1): past that their own oldest is dropped, so one
    /// account can't push out everyone else's codes. The Settings page makes a new code every 9 minutes and each
    /// Download click takes one, so a person only ever needs two or three.
    /// </summary>
    public const int MaxPerUser = 10;

    private readonly TimeProvider _clock;
    private readonly Dictionary<string, Entry> _live = new(StringComparer.Ordinal);
    private readonly object _gate = new();

    /// <summary>Initializes a new instance of the <see cref="AppTickets"/> class.</summary>
    /// <param name="clock">Clock.</param>
    public AppTickets(TimeProvider? clock = null) => _clock = clock ?? TimeProvider.System;

    /// <summary>Gets how many tickets are held now (tests).</summary>
    internal int Count
    {
        get
        {
            lock (_gate)
            {
                return _live.Count;
            }
        }
    }

    /// <summary>Makes a new ticket.</summary>
    /// <param name="user">Id of the signed-in person asking (for the per-person cap).</param>
    /// <returns>The ticket (22 URL-safe characters).</returns>
    public string Issue(Guid user)
    {
        var ticket = System.Buffers.Text.Base64Url.EncodeToString(RandomNumberGenerator.GetBytes(16));
        var now = _clock.GetUtcNow();
        lock (_gate)
        {
            Prune(now);
            var mine = _live.Where(kv => kv.Value.User == user).ToList();
            if (mine.Count >= MaxPerUser)
            {
                _live.Remove(mine.MinBy(kv => kv.Value.Expires).Key);
            }

            if (_live.Count >= MaxLive)
            {
                _live.Remove(_live.MinBy(kv => kv.Value.Expires).Key);
            }

            _live[ticket] = new Entry(now + Lifetime, MaxUses, user);
        }

        return ticket;
    }

    /// <summary>Whether the ticket would work now, without using it up.</summary>
    /// <param name="ticket">Ticket from the URL.</param>
    /// <returns>True if valid.</returns>
    public bool IsValid(string? ticket)
    {
        if (!LooksRight(ticket))
        {
            return false;
        }

        lock (_gate)
        {
            return _live.TryGetValue(ticket!, out var e) && e.Expires > _clock.GetUtcNow() && e.UsesLeft > 0;
        }
    }

    /// <summary>Uses the ticket once.</summary>
    /// <param name="ticket">Ticket from the URL.</param>
    /// <returns>True if it was valid (and one use is now gone).</returns>
    public bool TryUse(string? ticket)
    {
        if (!LooksRight(ticket))
        {
            return false;
        }

        lock (_gate)
        {
            var now = _clock.GetUtcNow();
            if (!_live.TryGetValue(ticket!, out var e) || e.Expires <= now || e.UsesLeft <= 0)
            {
                _live.Remove(ticket!);
                return false;
            }

            if (e.UsesLeft == 1)
            {
                _live.Remove(ticket!);
            }
            else
            {
                _live[ticket!] = e with { UsesLeft = e.UsesLeft - 1 };
            }

            return true;
        }
    }

    private static bool LooksRight(string? t) =>
        t is { Length: 22 } && t.All(c => char.IsAsciiLetterOrDigit(c) || c == '-' || c == '_');

    private void Prune(DateTimeOffset now)
    {
        foreach (var k in _live.Where(kv => kv.Value.Expires <= now).Select(kv => kv.Key).ToList())
        {
            _live.Remove(k);
        }
    }

    private readonly record struct Entry(DateTimeOffset Expires, int UsesLeft, Guid User);
}
