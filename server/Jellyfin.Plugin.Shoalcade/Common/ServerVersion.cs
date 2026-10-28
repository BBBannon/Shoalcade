using System;
using System.Globalization;

namespace Jellyfin.Plugin.Shoalcade.Common;

/// <summary>
/// How this plugin's version is named (D41, D88). The single source is server/Directory.Build.props: the assembly
/// version's 4th part is the dev build number. 0.2.0.1 = "s0.2 dev 1", 0.2.0.2 = "s0.2 dev 2", 1.0.0.0 = "s1.0".
/// </summary>
public static class ServerVersion
{
    /// <summary>Gets this plugin's own version (from the assembly).</summary>
    public static Version Current => typeof(ServerVersion).Assembly.GetName().Version ?? new Version(0, 0, 0, 0);

    /// <summary>"s" + major.minor, e.g. "s0.2". What the app compares (the dev number is left out).</summary>
    /// <param name="v">Version.</param>
    /// <returns>Short name.</returns>
    public static string Name(Version v)
    {
        ArgumentNullException.ThrowIfNull(v);
        return "s" + v.Major.ToString(CultureInfo.InvariantCulture) + "." + v.Minor.ToString(CultureInfo.InvariantCulture);
    }

    /// <summary>The dev build number (the 4th part), or 0 for a release.</summary>
    /// <param name="v">Version.</param>
    /// <returns>Dev number.</returns>
    public static int Dev(Version v)
    {
        ArgumentNullException.ThrowIfNull(v);
        return Math.Max(0, v.Revision);
    }

    /// <summary>What people see: "s0.2 dev 1" for a dev build, "s1.0" for a release.</summary>
    /// <param name="v">Version.</param>
    /// <returns>Display name.</returns>
    public static string Display(Version v)
    {
        var dev = Dev(v);
        return dev > 0 ? Name(v) + " dev " + dev.ToString(CultureInfo.InvariantCulture) : Name(v);
    }
}
