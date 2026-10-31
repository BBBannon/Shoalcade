using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Text.RegularExpressions;
using Jellyfin.Plugin.Shoalcade.Consoles;

namespace Jellyfin.Plugin.Shoalcade.Scanner;

/// <summary>
/// Works out a ROM's console, region and disc from its file and folder names, the same way JellyEMU 0.9.2.4 does, so
/// games keep the console tags they had (D42). Ported from JellyEMU's <c>PlatformResolver</c> (MIT, © 2026 Jellyfin GP;
/// the MIT notice is in THIRD_PARTY_NOTICES.md, shipped in the plugin zip, and docs/licences.md). JellyEMU's full alias tables are kept (not only our consoles) so that, for example, a
/// "PS2" folder is never mistaken for PSP; the scanner then keeps only consoles Shoalcade supports.
/// </summary>
public static partial class ConsoleDetector
{
    /// <summary>JellyEMU's tag for "no console found".</summary>
    public const string Unknown = "Unknown";

    /// <summary>Region words in brackets, e.g. <c>(USA)</c>, <c>(Europe)</c>, <c>(U)</c>, mapped to one tag each.</summary>
    internal static readonly Dictionary<string, string> RegionAliases = new(StringComparer.OrdinalIgnoreCase)
    {
        { "usa", "USA" }, { "us", "USA" }, { "ntsc-u", "USA" }, { "u", "USA" }, { "america", "USA" }, { "north america", "USA" },
        { "europe", "Europe" }, { "eur", "Europe" }, { "pal", "Europe" }, { "e", "Europe" }, { "eu", "Europe" }, { "uk", "Europe" },
        { "japan", "Japan" }, { "jpn", "Japan" }, { "jp", "Japan" }, { "j", "Japan" }, { "ntsc-j", "Japan" },
        { "world", "World" }, { "w", "World" },
        { "australia", "Australia" }, { "aus", "Australia" }, { "brazil", "Brazil" }, { "bra", "Brazil" }, { "br", "Brazil" },
        { "canada", "Canada" }, { "can", "Canada" }, { "china", "China" }, { "chn", "China" }, { "cn", "China" },
        { "france", "France" }, { "fra", "France" }, { "f", "France" }, { "fr", "France" }, { "germany", "Germany" },
        { "ger", "Germany" }, { "deu", "Germany" }, { "de", "Germany" }, { "italy", "Italy" }, { "ita", "Italy" }, { "it", "Italy" },
        { "korea", "Korea" }, { "kor", "Korea" }, { "k", "Korea" }, { "kr", "Korea" }, { "netherlands", "Netherlands" },
        { "ned", "Netherlands" }, { "nl", "Netherlands" }, { "russia", "Russia" }, { "rus", "Russia" }, { "ru", "Russia" }, { "spain", "Spain" },
        { "spa", "Spain" }, { "esp", "Spain" }, { "es", "Spain" }, { "sweden", "Sweden" }, { "swe", "Sweden" },
        { "asia", "Asia" }, { "scandinavia", "Scandinavia" },
        { "unlicensed", "Unlicensed" }, { "unl", "Unlicensed" }, { "proto", "Prototype" },
        { "prototype", "Prototype" }, { "demo", "Demo" }, { "sample", "Sample" },
    };

    /// <summary>Console names (in brackets or as folder names) mapped to JellyEMU's console tag.</summary>
    internal static readonly Dictionary<string, string> Aliases = new(StringComparer.OrdinalIgnoreCase)
    {
        { "nes", "NES" }, { "famicom", "NES" }, { "nintendo", "NES" }, { "nintendo entertainment system", "NES" },
        { "family computer disk system", "NES" }, { "famicom disk system", "NES" }, { "fds", "NES" },
        { "snes", "SNES" }, { "super nintendo", "SNES" }, { "super famicom", "SNES" }, { "supernintendo", "SNES" }, { "super nintendo entertainment system", "SNES" },
        { "n64", "N64" }, { "nintendo 64", "N64" }, { "nintendo64", "N64" }, { "nintendo 64dd", "N64" }, { "64dd", "N64" },
        { "gb", "Game Boy" }, { "game boy", "Game Boy" }, { "gameboy", "Game Boy" },
        { "gbc", "Game Boy Color" }, { "game boy color", "Game Boy Color" }, { "gameboy color", "Game Boy Color" }, { "gameboycolor", "Game Boy Color" },
        { "gba", "Game Boy Advance" }, { "game boy advance", "Game Boy Advance" }, { "gameboy advance", "Game Boy Advance" }, { "gameboyadvance", "Game Boy Advance" },
        { "nds", "Nintendo DS" }, { "nintendo ds", "Nintendo DS" }, { "ds", "Nintendo DS" }, { "nintendods", "Nintendo DS" },
        { "vb", "Virtual Boy" }, { "virtual boy", "Virtual Boy" }, { "virtualboy", "Virtual Boy" },
        { "sms", "Master System" }, { "master system", "Master System" }, { "sega master system", "Master System" }, { "mastersystem", "Master System" }, { "master system - mark iii", "Master System" },
        { "gg", "Game Gear" }, { "game gear", "Game Gear" }, { "sega game gear", "Game Gear" }, { "gamegear", "Game Gear" },
        { "genesis", "Sega Genesis" }, { "sega genesis", "Sega Genesis" }, { "megadrive", "Sega Genesis" }, { "mega drive", "Sega Genesis" }, { "sega mega drive", "Sega Genesis" }, { "mega drive - genesis", "Sega Genesis" }, { "md", "Sega Genesis" },
        { "sega cd", "Sega CD" }, { "segacd", "Sega CD" }, { "mega cd", "Sega CD" }, { "sega-cd", "Sega CD" }, { "mega cd - sega cd", "Sega CD" }, { "mega-cd - sega cd", "Sega CD" },
        { "32x", "Sega 32X" }, { "sega 32x", "Sega 32X" },
        { "ss", "Sega Saturn" }, { "saturn", "Sega Saturn" }, { "sega saturn", "Sega Saturn" }, { "segasaturn", "Sega Saturn" },
        { "psx", "PlayStation" }, { "ps1", "PlayStation" }, { "playstation", "PlayStation" }, { "playstation 1", "PlayStation" }, { "ps one", "PlayStation" }, { "playstation1", "PlayStation" }, { "play station", "PlayStation" }, { "play station 1", "PlayStation" },
        { "ps2", "PlayStation 2" }, { "playstation 2", "PlayStation 2" }, { "playstation2", "PlayStation 2" }, { "ps 2", "PlayStation 2" }, { "play station 2", "PlayStation 2" }, { "sony playstation 2", "PlayStation 2" }, { "sony playstation2", "PlayStation 2" }, { "sony ps2", "PlayStation 2" },
        { "atari 2600", "Atari 2600" }, { "2600", "Atari 2600" }, { "atari 7800", "Atari 7800" }, { "7800", "Atari 7800" },
        { "lynx", "Atari Lynx" }, { "atari lynx", "Atari Lynx" }, { "jaguar", "Atari Jaguar" }, { "atari jaguar", "Atari Jaguar" },
        { "ws", "WonderSwan" }, { "wonderswan", "WonderSwan" }, { "wonder swan", "WonderSwan" },
        { "pce", "TurboGrafx-16" }, { "turbografx", "TurboGrafx-16" }, { "turbografx-16", "TurboGrafx-16" }, { "turbografx 16", "TurboGrafx-16" }, { "pc engine", "TurboGrafx-16" }, { "pcengine", "TurboGrafx-16" },
        { "pc engine - turbografx-16", "TurboGrafx-16" }, { "pc engine cd", "TurboGrafx-16" }, { "pc engine cd - turbografx-cd", "TurboGrafx-16" }, { "turbografx-cd", "TurboGrafx-16" }, { "turbografx cd", "TurboGrafx-16" }, { "supergrafx", "TurboGrafx-16" }, { "pc engine supergrafx", "TurboGrafx-16" },
        { "coleco", "ColecoVision" }, { "colecovision", "ColecoVision" },
        { "ngp", "NeoGeo Pocket" }, { "neogeo pocket", "NeoGeo Pocket" }, { "neo geo pocket", "NeoGeo Pocket" }, { "ngpc", "NeoGeo Pocket" },
        { "dos", "DOS" }, { "ms-dos", "DOS" }, { "msdos", "DOS" }, { "pc dos", "DOS" },
        { "arcade", "Arcade" }, { "fbneo", "Arcade" }, { "finalburn neo", "Arcade" }, { "neogeo", "Arcade" }, { "neo geo", "Arcade" },
        { "mame", "MAME 2003" }, { "mame 2003", "MAME 2003" }, { "mame2003", "MAME 2003" },
        { "psp", "PSP" }, { "playstation portable", "PSP" }, { "play station portable", "PSP" },
        { "3do", "3DO" }, { "3do interactive multiplayer", "3DO" }, { "panasonic 3do", "3DO" },
        { "atari 5200", "Atari 5200" }, { "5200", "Atari 5200" },
        { "amiga", "Commodore Amiga" }, { "commodore amiga", "Commodore Amiga" },
        { "c64", "Commodore 64" }, { "commodore 64", "Commodore 64" },
        { "pc-fx", "PC-FX" }, { "pcfx", "PC-FX" }, { "nec pc-fx", "PC-FX" }, { "pc-fx & pc-fxga", "PC-FX" },
        { "3ds", "Nintendo 3DS" }, { "nintendo 3ds", "Nintendo 3DS" }, { "nintendo3ds", "Nintendo 3DS" },
        { "pico-8", "PICO-8" }, { "pico8", "PICO-8" }, { "pico 8", "PICO-8" }, { "pico", "PICO-8" },
    };

    /// <summary>Consoles JellyEMU only lists (not playable); kept so their folders never match one of ours.</summary>
    internal static readonly Dictionary<string, string> LibraryOnlyAliases = new(StringComparer.OrdinalIgnoreCase)
    {
        { "dreamcast", "Dreamcast" }, { "dc", "Dreamcast" }, { "sega dreamcast", "Dreamcast" },
        { "ps3", "PlayStation 3" }, { "playstation 3", "PlayStation 3" }, { "play station 3", "PlayStation 3" },
        { "xbox", "Xbox" }, { "xbox 360", "Xbox 360" }, { "x360", "Xbox 360" },
        { "gamecube", "GameCube" }, { "nintendo gamecube", "GameCube" }, { "gc", "GameCube" },
        { "wii", "Wii" }, { "nintendo wii", "Wii" }, { "wii u", "Wii U" }, { "wiiu", "Wii U" },
        { "switch", "Nintendo Switch" }, { "nintendo switch", "Nintendo Switch" },
        { "psvita", "PlayStation Vita" }, { "ps vita", "PlayStation Vita" }, { "playstation vita", "PlayStation Vita" }, { "play station vita", "PlayStation Vita" },
        { "windows", "Windows" }, { "win", "Windows" },
        { "linux", "Linux" },
        { "macos", "MacOS" }, { "mac", "MacOS" }, { "osx", "MacOS" }, { "mac os", "MacOS" }, { "mac os x", "MacOS" },
        { "android", "Android" },
    };

    /// <summary>Extensions that name one console on their own.</summary>
    private static readonly Dictionary<string, string> UnambiguousExtensions = new(StringComparer.OrdinalIgnoreCase)
    {
        { ".nes", "NES" }, { ".fds", "NES" }, { ".unf", "NES" }, { ".unif", "NES" },
        { ".smc", "SNES" }, { ".sfc", "SNES" }, { ".swc", "SNES" }, { ".fig", "SNES" },
        { ".z64", "N64" }, { ".n64", "N64" }, { ".v64", "N64" },
        { ".gb", "Game Boy" }, { ".gbc", "Game Boy Color" }, { ".gba", "Game Boy Advance" },
        { ".nds", "Nintendo DS" }, { ".vb", "Virtual Boy" }, { ".sms", "Master System" }, { ".gg", "Game Gear" },
        { ".md", "Sega Genesis" }, { ".smd", "Sega Genesis" }, { ".gen", "Sega Genesis" }, { ".68k", "Sega Genesis" }, { ".sgd", "Sega Genesis" },
        { ".32x", "Sega 32X" }, { ".a26", "Atari 2600" }, { ".a78", "Atari 7800" }, { ".lnx", "Atari Lynx" },
        { ".jag", "Atari Jaguar" }, { ".j64", "Atari Jaguar" }, { ".ws", "WonderSwan" }, { ".wsc", "WonderSwan" },
        { ".pce", "TurboGrafx-16" }, { ".col", "ColecoVision" }, { ".cv", "ColecoVision" },
        { ".ngp", "NeoGeo Pocket" }, { ".ngc", "NeoGeo Pocket" }, { ".cso", "PSP" }, { ".p8", "PICO-8" },
        { ".3ds", "Nintendo 3DS" }, { ".cci", "Nintendo 3DS" }, { ".cia", "Nintendo 3DS" },
        // plan-3ds step 8: the other files Azahar opens (D161), compressed ones included.
        { ".cxi", "Nintendo 3DS" }, { ".3dsx", "Nintendo 3DS" }, { ".zcci", "Nintendo 3DS" }, { ".zcxi", "Nintendo 3DS" }, { ".z3dsx", "Nintendo 3DS" },
    };

    /// <summary>JellyEMU's other-platform extensions (only so a zip holding one is never taken for one of ours).</summary>
    private static readonly Dictionary<string, string> LibraryOnlyExtensions = new(StringComparer.OrdinalIgnoreCase)
    {
        { ".gcm", "GameCube" }, { ".gcz", "GameCube" }, { ".rvz", "Wii" },
        { ".wbfs", "Wii" }, { ".wad", "Wii" }, { ".xex", "Xbox 360" },
        { ".xiso", "Xbox" }, { ".vpk", "PlayStation Vita" },
        { ".exe", "Windows" }, { ".msi", "Windows" },
        { ".appimage", "Linux" }, { ".deb", "Linux" }, { ".flatpak", "Linux" }, { ".snap", "Linux" }, { ".sh", "Linux" }, { ".x86_64", "Linux" }, { ".x86", "Linux" },
        { ".app", "MacOS" }, { ".dmg", "MacOS" }, { ".pkg", "MacOS" },
        { ".apk", "Android" }, { ".xapk", "Android" },
    };

    /// <summary>Nintendo consoles named inside a folder name: such a folder is never the plain "nintendo" (NES) alias.</summary>
    private static readonly string[] SpecificNintendoKeys =
    [
        "game boy", "gameboy", "gb", "gbc", "gba", "ds", "nds", "n64", "snes", "super nintendo", "super famicom", "virtual boy", "vb",
        "gamecube", "wii", "3ds", "switch",
    ];

    // Longest first, as JellyEMU does, so "game boy advance" wins over "game boy".
    private static readonly KeyValuePair<string, string>[] AliasesLongestFirst = Aliases.OrderByDescending(k => k.Key.Length).ToArray();
    private static readonly KeyValuePair<string, string>[] LibraryOnlyLongestFirst = LibraryOnlyAliases.OrderByDescending(k => k.Key.Length).ToArray();

    /// <summary>True if a folder name is just a console name (e.g. "gba", "Game Boy Advance").</summary>
    /// <param name="name">Folder name.</param>
    /// <returns>Whether it names a console.</returns>
    public static bool IsConsoleFolderName(string? name) =>
        !string.IsNullOrEmpty(name) && (Aliases.ContainsKey(name) || LibraryOnlyAliases.ContainsKey(name));

    /// <summary>
    /// JellyEMU's console tag for a path: a console in brackets in the name, else a console folder up to three levels up,
    /// else the extension (looking inside a .zip if needed). <see cref="Unknown"/> if none.
    /// </summary>
    /// <param name="path">File or folder path.</param>
    /// <returns>Console tag.</returns>
    public static string ResolveTag(string? path)
    {
        if (string.IsNullOrEmpty(path))
        {
            return Unknown;
        }

        var fromToken = MatchAliasFromTokens(Path.GetFileName(path));
        if (fromToken is not null)
        {
            return fromToken;
        }

        var dirPath = Path.GetDirectoryName(path);
        if (!string.IsNullOrEmpty(dirPath))
        {
            var fromFolder = MatchAliasFromFolders(dirPath);
            if (fromFolder is not null)
            {
                return fromFolder;
            }
        }

        var ext = Path.GetExtension(path);
        if (string.IsNullOrEmpty(ext))
        {
            return Unknown;
        }

        if (UnambiguousExtensions.TryGetValue(ext, out var extTag) || LibraryOnlyExtensions.TryGetValue(ext, out extTag))
        {
            return extTag;
        }

        if (string.Equals(ext, ".zip", StringComparison.OrdinalIgnoreCase) && File.Exists(path))
        {
            return ZipInnerTag(path) ?? Unknown;
        }

        return Unknown;
    }

    /// <summary>The Shoalcade console for a path, or null if it isn't one Shoalcade supports.</summary>
    /// <param name="path">File or folder path.</param>
    /// <returns>Console or null.</returns>
    public static GameConsole? Resolve(string? path)
    {
        var tag = ResolveTag(path);
        return GameConsole.All.FirstOrDefault(c => c.Tags.Contains(tag, StringComparer.Ordinal));
    }

    /// <summary>Every region named in brackets in the file name, in order (e.g. "(USA, Europe)" gives USA then Europe).</summary>
    /// <param name="path">File or folder path.</param>
    /// <returns>Region tags.</returns>
    public static IReadOnlyList<string> ResolveRegions(string? path)
    {
        var regions = new List<string>();
        if (string.IsNullOrEmpty(path))
        {
            return regions;
        }

        foreach (Match m in TokenRegex().Matches(Path.GetFileNameWithoutExtension(path)))
        {
            foreach (var part in m.Groups[1].Value.Trim().Split(',', StringSplitOptions.TrimEntries))
            {
                if (RegionAliases.TryGetValue(part, out var region) && !regions.Contains(region))
                {
                    regions.Add(region);
                }
            }
        }

        return regions;
    }

    /// <summary>"Disc 1", "Disc 2"... if the name carries a disc number (e.g. "(Disc 2)", "Disk II"), else null.</summary>
    /// <param name="path">File path.</param>
    /// <returns>Disc label or null.</returns>
    public static string? ResolveDisc(string? path)
    {
        if (string.IsNullOrEmpty(path))
        {
            return null;
        }

        var m = DiscTokenRegex().Match(Path.GetFileNameWithoutExtension(path));
        if (!m.Success)
        {
            return null;
        }

        var raw = (m.Groups[1].Success ? m.Groups[1].Value : m.Groups[2].Value).Trim();
        if (int.TryParse(raw, System.Globalization.NumberStyles.None, System.Globalization.CultureInfo.InvariantCulture, out var n))
        {
            return "Disc " + n.ToString(System.Globalization.CultureInfo.InvariantCulture);
        }

        return raw.ToUpperInvariant() switch
        {
            "I" => "Disc 1",
            "II" => "Disc 2",
            "III" => "Disc 3",
            "IV" => "Disc 4",
            "V" => "Disc 5",
            "VI" => "Disc 6",
            "VII" => "Disc 7",
            "VIII" => "Disc 8",
            var other => "Disc " + other,
        };
    }

    /// <summary>
    /// The name people see: brackets holding a console, region or disc removed (JellyEMU's <c>CleanDisplayName</c>), then
    /// every remaining bracket, underscore and dash tidied away (JellyEMU's <c>NameCleaner</c>).
    /// "Pokemon - Red Version (USA, Europe) (SGB Enhanced)" becomes "Pokemon Red Version".
    /// </summary>
    /// <param name="raw">File name without extension, or folder name.</param>
    /// <returns>Display name.</returns>
    public static string CleanName(string? raw)
    {
        if (string.IsNullOrEmpty(raw))
        {
            return string.Empty;
        }

        var result = TokenRegex().Replace(raw, m =>
        {
            var inner = m.Groups[1].Value.Trim();
            if (inner.StartsWith("igdb-", StringComparison.OrdinalIgnoreCase) || inner.StartsWith("rawg-", StringComparison.OrdinalIgnoreCase))
            {
                return string.Empty;
            }

            if (Aliases.ContainsKey(inner))
            {
                return string.Empty;
            }

            var parts = inner.Split(',', StringSplitOptions.TrimEntries);
            if (parts.Length > 0 && parts.All(RegionAliases.ContainsKey))
            {
                return string.Empty;
            }

            return DiscTokenRegex().IsMatch("(" + inner + ")") ? string.Empty : m.Value;
        });
        result = DiscTokenRegex().Replace(result, string.Empty);
        result = SpacesRegex().Replace(result, " ").Trim();
        if (string.IsNullOrWhiteSpace(result))
        {
            return result;
        }

        string previous;
        do
        {
            previous = result;
            result = NestedBracketsRegex().Replace(result, " ");
        }
        while (!string.Equals(result, previous, StringComparison.Ordinal));

        result = DanglingBracketsRegex().Replace(result.Replace('_', ' ').Replace('-', ' '), string.Empty);
        return SpacesRegex().Replace(result, " ").Trim();
    }

    private static string? ZipInnerTag(string path)
    {
        try
        {
            using var zip = ZipFile.OpenRead(path);
            foreach (var entry in zip.Entries)
            {
                var innerExt = Path.GetExtension(entry.FullName);
                if (string.IsNullOrEmpty(innerExt))
                {
                    continue;
                }

                if (UnambiguousExtensions.TryGetValue(innerExt, out var tag) || LibraryOnlyExtensions.TryGetValue(innerExt, out tag))
                {
                    return tag;
                }
            }
        }
        catch (Exception ex) when (ex is IOException or InvalidDataException or UnauthorizedAccessException)
        {
            // Not a readable zip: no console from its contents.
        }

        return null;
    }

    private static string? MatchAliasFromTokens(string fileName)
    {
        foreach (Match m in TokenRegex().Matches(fileName))
        {
            var inner = m.Groups[1].Value.Trim();
            if (inner.StartsWith("igdb-", StringComparison.OrdinalIgnoreCase) || inner.StartsWith("rawg-", StringComparison.OrdinalIgnoreCase))
            {
                continue;
            }

            foreach (var part in inner.Split(',', StringSplitOptions.TrimEntries))
            {
                if (RegionAliases.ContainsKey(part))
                {
                    continue;
                }

                if (Aliases.TryGetValue(part, out var tag) || LibraryOnlyAliases.TryGetValue(part, out tag))
                {
                    return tag;
                }
            }
        }

        return null;
    }

    private static string? MatchAliasFromFolders(string dirPath)
    {
        var dir = new DirectoryInfo(dirPath);
        for (var depth = 0; depth < 3 && dir is not null; depth++, dir = dir.Parent)
        {
            var folderClean = BrandPrefixRegex().Replace(dir.Name, string.Empty).Trim();
            if (Aliases.TryGetValue(folderClean, out var tag) || LibraryOnlyAliases.TryGetValue(folderClean, out tag))
            {
                return tag;
            }

            var folderCollapsed = SpacesRegex().Replace(folderClean, string.Empty);
            foreach (var kvp in AliasesLongestFirst)
            {
                if (kvp.Key.Length > 2 && KeywordMatch(folderClean, folderCollapsed, kvp.Key))
                {
                    return kvp.Value;
                }
            }

            foreach (var kvp in LibraryOnlyLongestFirst)
            {
                if (kvp.Key.Length > 2 && KeywordMatch(folderClean, folderCollapsed, kvp.Key))
                {
                    return kvp.Value;
                }
            }
        }

        return null;
    }

    private static bool KeywordMatch(string folder, string folderCollapsed, string aliasKey)
    {
        if (string.Equals(aliasKey, "nintendo", StringComparison.OrdinalIgnoreCase)
            && SpecificNintendoKeys.Any(k => folder.Contains(k, StringComparison.OrdinalIgnoreCase)
                || folderCollapsed.Contains(SpacesRegex().Replace(k, string.Empty), StringComparison.OrdinalIgnoreCase)))
        {
            return false;
        }

        if (WholeWordAt(folder, aliasKey))
        {
            return true;
        }

        var aliasCollapsed = SpacesRegex().Replace(aliasKey, string.Empty);
        return aliasCollapsed.Length > 2 && WholeWordAt(folderCollapsed, aliasCollapsed);
    }

    // JellyEMU checks only the first place the word appears, then whether it stands alone there.
    private static bool WholeWordAt(string text, string word)
    {
        var idx = text.IndexOf(word, StringComparison.OrdinalIgnoreCase);
        if (idx < 0)
        {
            return false;
        }

        var before = idx > 0 ? text[idx - 1] : ' ';
        var after = idx + word.Length < text.Length ? text[idx + word.Length] : ' ';
        return !char.IsLetterOrDigit(before) && !char.IsLetterOrDigit(after);
    }

    [GeneratedRegex(@"[\[\(]([^\]\)]+)[\]\)]", RegexOptions.IgnoreCase)]
    private static partial Regex TokenRegex();

    [GeneratedRegex(@"(?:[\[\(]\s*dis[ck]\s*([1-9IVX]{1,4})\s*[\]\)]|(?<![a-zA-Z])dis[ck]\s*([1-9IVX]{1,4})(?![a-zA-Z0-9]))", RegexOptions.IgnoreCase)]
    private static partial Regex DiscTokenRegex();

    [GeneratedRegex(@"^(?:sony|nintendo|sega|atari|nec|commodore|snk)\s+(?=\S)|^(?:sony|nintendo|sega|atari|nec|commodore|snk)\s*-\s*(?=\S)", RegexOptions.IgnoreCase)]
    private static partial Regex BrandPrefixRegex();

    [GeneratedRegex(@"\s*\([^)]*\)|\s*\[[^\]]*\]|\s*\{[^}]*\}|\s*<[^>]*>")]
    private static partial Regex NestedBracketsRegex();

    [GeneratedRegex(@"[()\[\]{}<>]")]
    private static partial Regex DanglingBracketsRegex();

    [GeneratedRegex(@"\s+")]
    private static partial Regex SpacesRegex();
}
