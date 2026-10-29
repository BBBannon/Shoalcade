using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using Jellyfin.Plugin.Shoalcade.Consoles;

namespace Jellyfin.Plugin.Shoalcade.Scanner;

/// <summary>Names and addresses used with libretro-database and libretro-thumbnails (D48).</summary>
public static class LibretroNames
{
    private const string Forbidden = "&*/:`<>?\\|\"";

    private static readonly Dictionary<string, string> Systems = new(StringComparer.Ordinal)
    {
        ["gb"] = "Nintendo - Game Boy",
        ["gbc"] = "Nintendo - Game Boy Color",
        ["gba"] = "Nintendo - Game Boy Advance",
        ["n64"] = "Nintendo - Nintendo 64",
        ["nds"] = "Nintendo - Nintendo DS",
        ["3ds"] = "Nintendo - Nintendo 3DS",
        ["psp"] = "Sony - PlayStation Portable",
        ["psx"] = "Sony - PlayStation",
    };

    private static readonly string[] Articles = ["The", "A", "An"];

    /// <summary>libretro's name for a console ("Nintendo - Game Boy"), used for both the database file and the box art folder.</summary>
    /// <param name="console">Console.</param>
    /// <returns>The system name, or null for a console libretro has no list for.</returns>
    public static string? SystemName(GameConsole console) =>
        console is not null && Systems.TryGetValue(console.Key, out var name) ? name : null;

    /// <summary>A name as libretro-thumbnails stores it: each of <c>&amp;*/:`&lt;&gt;?\|"</c> becomes <c>_</c>.</summary>
    /// <param name="name">Game name.</param>
    /// <returns>File name without ".png".</returns>
    public static string ThumbnailName(string name)
    {
        ArgumentNullException.ThrowIfNull(name);
        var sb = new StringBuilder(name.Length);
        foreach (var c in name)
        {
            sb.Append(Forbidden.Contains(c, StringComparison.Ordinal) ? '_' : c);
        }

        return sb.ToString().Trim();
    }

    /// <summary>
    /// The names to try for box art, in order (D48): the file name, the database name, then the name cut at the first
    /// "(" (so "Game (USA) (Rev 1)" also finds "Game"). Duplicates and empty names are dropped.
    /// </summary>
    /// <param name="itemPath">The game's path (ROM file, or disc-set folder).</param>
    /// <param name="databaseName">The No-Intro/Redump name from the ROM's hash, if known.</param>
    /// <returns>Names, best first.</returns>
    public static IReadOnlyList<string> BoxArtCandidates(string? itemPath, string? databaseName)
    {
        var fileName = FileStem(itemPath);
        var names = new List<string?> { fileName, databaseName, CutAtBracket(databaseName ?? fileName) };
        return names
            .Where(n => !string.IsNullOrWhiteSpace(n))
            .Select(n => ThumbnailName(n!))
            .Where(n => n.Length > 0)
            .Distinct(StringComparer.Ordinal)
            .ToList();
    }

    /// <summary>The box art address for a name on a console.</summary>
    /// <param name="thumbnailsBase">Base address of the box art server.</param>
    /// <param name="system">libretro system name.</param>
    /// <param name="thumbnailName">Name already passed through <see cref="ThumbnailName"/>.</param>
    /// <returns>Address of the .png.</returns>
    public static Uri BoxArtUrl(Uri thumbnailsBase, string system, string thumbnailName) =>
        new(thumbnailsBase, Uri.EscapeDataString(system) + "/Named_Boxarts/" + Uri.EscapeDataString(thumbnailName) + ".png");

    /// <summary>The address of one database file, e.g. <c>no-intro/Nintendo - Game Boy.dat</c>.</summary>
    /// <param name="databaseBase">Base address of the database (the "metadat" folder).</param>
    /// <param name="folder">Folder (no-intro, redump, developer, publisher, releaseyear, genre).</param>
    /// <param name="system">libretro system name.</param>
    /// <returns>Address.</returns>
    public static Uri DatUrl(Uri databaseBase, string folder, string system) =>
        new(databaseBase, folder + "/" + Uri.EscapeDataString(system) + ".dat");

    /// <summary>
    /// The title people see, from a No-Intro/Redump name: the bracketed extras (region, languages, revision) are
    /// dropped (the region stays as a tag), and a trailing article goes back to the front
    /// ("Legend of Zelda, The - Link's Awakening DX (USA, Europe) (Rev 2)" becomes "The Legend of Zelda - Link's Awakening DX").
    /// </summary>
    /// <param name="databaseName">Database name.</param>
    /// <returns>Clean title, or null if nothing is left.</returns>
    public static string? DisplayTitle(string? databaseName)
    {
        var title = CutAtBracket(databaseName);
        if (string.IsNullOrEmpty(title))
        {
            return null;
        }

        // "Main, The - Subtitle" or "Main, The"
        var dash = title.IndexOf(" - ", StringComparison.Ordinal);
        var main = dash < 0 ? title : title[..dash];
        var rest = dash < 0 ? string.Empty : title[dash..];
        foreach (var article in Articles)
        {
            var suffix = ", " + article;
            if (main.EndsWith(suffix, StringComparison.Ordinal) && main.Length > suffix.Length)
            {
                return article + " " + main[..^suffix.Length] + rest;
            }
        }

        return title;
    }

    /// <summary>A name cut at the first "(" or "[" ("Game (USA) [b]" becomes "Game").</summary>
    /// <param name="name">Name.</param>
    /// <returns>The cut name (trimmed), or null.</returns>
    public static string? CutAtBracket(string? name)
    {
        if (string.IsNullOrWhiteSpace(name))
        {
            return null;
        }

        var cut = name.IndexOfAny(['(', '[']);
        var result = (cut < 0 ? name : name[..cut]).Trim();
        return result.Length == 0 ? null : result;
    }

    private static string? FileStem(string? itemPath)
    {
        if (string.IsNullOrEmpty(itemPath))
        {
            return null;
        }

        var trimmed = itemPath.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
        return Directory.Exists(trimmed) ? Path.GetFileName(trimmed) : Path.GetFileNameWithoutExtension(trimmed);
    }
}
