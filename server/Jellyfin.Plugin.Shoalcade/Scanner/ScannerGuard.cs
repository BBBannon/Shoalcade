using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using MediaBrowser.Common;
using MediaBrowser.Controller.Resolvers;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Shoalcade.Scanner;

/// <summary>
/// Never two scanners at once (D42): while JellyEMU is installed it finds the games, and every part of our scanner
/// (resolver, ROM info, MD5 id, box art) does nothing. Removing JellyEMU and restarting Jellyfin swaps ours in, in one pass.
/// </summary>
/// <remarks>
/// "Installed" means JellyEMU's library resolver is part of THIS Jellyfin start, asked of Jellyfin itself
/// (<see cref="IApplicationHost.GetExportTypes{T}"/>: the resolver types Jellyfin's library manager was given). Never the
/// assemblies loaded in the process: Jellyfin's Dashboard Restart restarts inside the same process, and a removed
/// JellyEMU's assembly stays loaded from the previous start (s0.2 dev 1 stood down then, and a scan dropped every game).
/// </remarks>
public sealed class ScannerGuard
{
    /// <summary>JellyEMU's assembly name (its plugin id is 9bab105e-9af0-4e25-a87d-876713b60962).</summary>
    internal const string JellyEmuAssembly = "JellyEmu";

    private readonly ILogger _logger;
    private readonly Lazy<bool> _jellyEmuInstalled;
    private int _logged;

    /// <summary>Initializes a new instance of the <see cref="ScannerGuard"/> class (asks Jellyfin for this start's resolvers).</summary>
    /// <param name="logger">Logger.</param>
    /// <param name="host">This Jellyfin start (not asked until first needed, after every plugin has loaded).</param>
    public ScannerGuard(ILogger<ScannerGuard> logger, IApplicationHost host)
        : this(logger, () => IsJellyEmuResolverActive(host.GetExportTypes<IItemResolver>()))
    {
    }

    /// <summary>Initializes a new instance of the <see cref="ScannerGuard"/> class with a given check (tests).</summary>
    /// <param name="logger">Logger.</param>
    /// <param name="jellyEmuInstalled">Says whether JellyEMU is installed.</param>
    internal ScannerGuard(ILogger logger, Func<bool> jellyEmuInstalled)
    {
        _logger = logger;

        // PublicationOnly: a failed check is asked again next time instead of being remembered as an error.
        _jellyEmuInstalled = new Lazy<bool>(jellyEmuInstalled, LazyThreadSafetyMode.PublicationOnly);
    }

    /// <summary>
    /// Gets a value indicating whether our scanner must do nothing because JellyEMU is installed. Says so once in the
    /// log. (A Jellyfin start finds its plugins' parts once, before the first scan, so the answer is worked out once;
    /// a restart is a new start with a new guard.)
    /// </summary>
    public bool StandDown
    {
        get
        {
            var installed = _jellyEmuInstalled.Value;
            if (installed && Interlocked.Exchange(ref _logged, 1) == 0)
            {
                _logger.LogInformation("JellyEMU is installed, so it finds the games. The Shoalcade scanner is standing down until JellyEMU is removed.");
            }

            return installed;
        }
    }

    /// <summary>Gets which plugin finds ROMs in libraries right now: "JellyEMU" while it is installed, else our own scanner.</summary>
    public string Scanner => StandDown ? "JellyEMU" : "Shoalcade";

    /// <summary>True if one of these library resolvers (this Jellyfin start's) is JellyEMU's.</summary>
    /// <param name="resolverTypes">The resolver types Jellyfin found in its loaded plugins for this start.</param>
    /// <returns>Whether JellyEMU's resolver is active.</returns>
    public static bool IsJellyEmuResolverActive(IEnumerable<Type> resolverTypes) =>
        resolverTypes.Any(t => string.Equals(t.Assembly.GetName().Name, JellyEmuAssembly, StringComparison.Ordinal));
}
