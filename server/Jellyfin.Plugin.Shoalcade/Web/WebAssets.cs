using System;
using System.Collections.Generic;
using System.Collections.Frozen;
using System.IO;
using System.Linq;
using System.Reflection;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Shoalcade.Web;

/// <summary>A file the plugin can serve.</summary>
/// <param name="ContentType">MIME type.</param>
/// <param name="ResourceName">Embedded resource name (our pages), or null.</param>
/// <param name="FilePath">File on disk (EmulatorJS), or null.</param>
/// <param name="ETag">Entity tag.</param>
public sealed record WebAsset(string ContentType, string? ResourceName, string? FilePath, string ETag);

/// <summary>
/// The fixed list of files the plugin serves. Requests are looked up by exact name in a list built at start-up,
/// so a request can never reach any other file (no path tricks). Our pages are embedded in the DLL;
/// EmulatorJS (pinned v4.2.3, D34) sits in the <c>ejs/</c> folder next to the DLL.
/// </summary>
public sealed class WebAssets
{
    /// <summary>Gets the EmulatorJS version bundled: the <c>emulatorjs</c> line of <c>server/emulatorjs.lock</c>, embedded at build time.</summary>
    public static string EmulatorJsVersion { get; } = ParseLockedVersion(ReadLockFile());

    private const string ResourcePrefix = "Jellyfin.Plugin.Shoalcade.Pages.";

    private static readonly FrozenDictionary<string, string> Types = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase)
    {
        [".html"] = "text/html; charset=utf-8",
        [".js"] = "text/javascript; charset=utf-8",
        [".css"] = "text/css; charset=utf-8",
        [".json"] = "application/json; charset=utf-8",
        [".svg"] = "image/svg+xml",
        [".png"] = "image/png",
        [".ico"] = "image/x-icon",
        [".wasm"] = "application/wasm",
        [".data"] = "application/octet-stream",
        [".zip"] = "application/zip",
        [".txt"] = "text/plain; charset=utf-8",
        [".woff2"] = "font/woff2",
    }.ToFrozenDictionary(StringComparer.OrdinalIgnoreCase);

    private readonly FrozenDictionary<string, WebAsset> _pages;
    private readonly FrozenDictionary<string, WebAsset> _ejs;

    /// <summary>Initializes a new instance of the <see cref="WebAssets"/> class.</summary>
    /// <param name="logger">Logger.</param>
    public WebAssets(ILogger<WebAssets> logger)
    {
        var asm = typeof(WebAssets).Assembly;
        _pages = asm.GetManifestResourceNames()
            .Where(n => n.StartsWith(ResourcePrefix, StringComparison.Ordinal))
            .Select(n => (Name: n[ResourcePrefix.Length..], Resource: n))
            .Where(x => Types.ContainsKey(Path.GetExtension(x.Name)))
            .ToFrozenDictionary(x => x.Name, x => new WebAsset(Types[Path.GetExtension(x.Name)], x.Resource, null, ContentTag(asm, x.Resource)), StringComparer.Ordinal);

        var ejsDir = Path.Combine(Path.GetDirectoryName(asm.Location) ?? ".", "ejs");
        var ejs = new Dictionary<string, WebAsset>(StringComparer.Ordinal);
        if (Directory.Exists(ejsDir))
        {
            var root = Path.GetFullPath(ejsDir);
            foreach (var f in Directory.EnumerateFiles(root, "*", SearchOption.AllDirectories))
            {
                var ext = Path.GetExtension(f);
                if (!Types.TryGetValue(ext, out var type))
                {
                    continue;
                }

                var info = new FileInfo(f);
                var rel = Path.GetRelativePath(root, f).Replace('\\', '/');
                ejs[rel] = new WebAsset(type, null, info.FullName, "\"" + info.Length.ToString("x", System.Globalization.CultureInfo.InvariantCulture) + "-" + info.LastWriteTimeUtc.Ticks.ToString("x", System.Globalization.CultureInfo.InvariantCulture) + "\"");
            }
        }
        else
        {
            logger.LogWarning("EmulatorJS folder missing at {Dir}: the browser player can't start games. Reinstall the plugin zip.", ejsDir);
        }

        _ejs = ejs.ToFrozenDictionary(StringComparer.Ordinal);
        EmulatorAvailable = _ejs.ContainsKey("src/emulator.js");
    }

    /// <summary>Gets a value indicating whether EmulatorJS is installed next to the plugin.</summary>
    public bool EmulatorAvailable { get; }

    /// <summary>Finds one of our pages/scripts by name (e.g. "play.js").</summary>
    /// <param name="name">File name.</param>
    /// <returns>The asset or null.</returns>
    public WebAsset? FindPage(string? name) => name is not null && _pages.TryGetValue(name, out var a) ? a : null;

    /// <summary>Finds an EmulatorJS file by its path inside <c>ejs/</c> (e.g. "cores/mgba-wasm.data").</summary>
    /// <param name="path">Relative path.</param>
    /// <returns>The asset or null.</returns>
    public WebAsset? FindEmulatorFile(string? path) => path is not null && _ejs.TryGetValue(path, out var a) ? a : null;

    /// <summary>
    /// Entity tag from the file's contents, so a rebuilt page is fetched again even when the plugin version
    /// hasn't changed (a version-only tag let browsers keep old scripts after an update).
    /// </summary>
    /// <param name="asm">Assembly.</param>
    /// <param name="resource">Resource name.</param>
    /// <returns>The quoted tag.</returns>
    private static string ContentTag(Assembly asm, string resource)
    {
        using var s = asm.GetManifestResourceStream(resource) ?? throw new FileNotFoundException(resource);
        return "\"" + Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(s), 0, 12).ToLowerInvariant() + "\"";
    }

    /// <summary>Opens an asset.</summary>
    /// <param name="asset">Asset.</param>
    /// <returns>A read stream.</returns>
    public static Stream Open(WebAsset asset)
    {
        ArgumentNullException.ThrowIfNull(asset);
        if (asset.ResourceName is not null)
        {
            return typeof(WebAssets).Assembly.GetManifestResourceStream(asset.ResourceName) ?? throw new FileNotFoundException(asset.ResourceName);
        }

        return new FileStream(asset.FilePath!, FileMode.Open, FileAccess.Read, FileShare.Read, 81920, FileOptions.Asynchronous | FileOptions.SequentialScan);
    }

    /// <summary>Finds the <c>emulatorjs</c> version in the lock file's text (lines of <c>name version sha256</c>).</summary>
    /// <param name="lockText">The lock file.</param>
    /// <returns>The version.</returns>
    internal static string ParseLockedVersion(string lockText)
    {
        foreach (var line in lockText.Split('\n'))
        {
            var parts = line.Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
            if (parts.Length >= 2 && !parts[0].StartsWith('#') && parts[0] == "emulatorjs")
            {
                return parts[1];
            }
        }

        throw new InvalidDataException("emulatorjs.lock has no emulatorjs line.");
    }

    private static string ReadLockFile()
    {
        const string Name = "Jellyfin.Plugin.Shoalcade.emulatorjs.lock";
        using var s = typeof(WebAssets).Assembly.GetManifestResourceStream(Name) ?? throw new FileNotFoundException(Name);
        using var r = new StreamReader(s);
        return r.ReadToEnd();
    }
}
