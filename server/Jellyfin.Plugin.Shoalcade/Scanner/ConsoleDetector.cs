using System;
using System.Collections.Concurrent;
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
        // GameCube and Wii (D218: played by the Windows app). Were library-only until then.
        { "gamecube", "GameCube" }, { "nintendo gamecube", "GameCube" }, { "gc", "GameCube" }, { "game cube", "GameCube" },
        { "wii", "Wii" }, { "nintendo wii", "Wii" },
        // Not ours, but here so "Wii U" is never taken for "wii" (these names are matched before the library-only ones).
        { "wii u", "Wii U" }, { "wiiu", "Wii U" },
    };

    /// <summary>Consoles JellyEMU only lists (not playable); kept so their folders never match one of ours.</summary>
    internal static readonly Dictionary<string, string> LibraryOnlyAliases = new(StringComparer.OrdinalIgnoreCase)
    {
        { "dreamcast", "Dreamcast" }, { "dc", "Dreamcast" }, { "sega dreamcast", "Dreamcast" },
        { "ps3", "PlayStation 3" }, { "playstation 3", "PlayStation 3" }, { "play station 3", "PlayStation 3" },
        { "xbox", "Xbox" }, { "xbox 360", "Xbox 360" }, { "x360", "Xbox 360" },
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
        // GameCube / Wii only formats (D218). .rvz, .wia, .gcz and .ciso are both consoles' (Dolphin's own formats):
        // the folder or the name decides, else the disc's own header (DiscHeaderTag).
        { ".gcm", "GameCube" }, { ".tgc", "GameCube" }, { ".wbfs", "Wii" }, { ".wad", "Wii" },
    };

    /// <summary>JellyEMU's other-platform extensions (only so a zip holding one is never taken for one of ours).</summary>
    private static readonly Dictionary<string, string> LibraryOnlyExtensions = new(StringComparer.OrdinalIgnoreCase)
    {
        { ".xex", "Xbox 360" },
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

        // A disc image with no console in its name or folders: the disc says what it is (the .iso clash: PSP, PS2,
        // GameCube and Wii all use .iso).
        if (DiscExtensions.Contains(ext, StringComparer.OrdinalIgnoreCase))
        {
            return DiscHeaderTag(path) ?? Unknown;
        }

        return Unknown;
    }

    /// <summary>The console a disc image's own header names (see <see cref="DiscHeaderTag"/>), only for the disc
    /// extensions that can clash (.iso and Dolphin's formats); null for anything else or a header that says nothing.</summary>
    /// <param name="path">File path.</param>
    /// <returns>Console tag or null.</returns>
    internal static string? DiscTag(string? path) => TryDiscTag(path, out var tag) ? tag : null;

    /// <summary>
    /// <see cref="DiscTag"/> for the library (review #4): the header is read once per file version (path, size, time)
    /// and kept, so listing games never re-reads discs. When the file can't be read now (a sleeping NAS), the last answer
    /// for that path is used; with none, this returns false ("don't know now") and the caller must not guess.
    /// </summary>
    /// <param name="path">File path.</param>
    /// <param name="tag">Console tag, or null when the header names none (or the file isn't a clashing disc type).</param>
    /// <returns>False only when the file couldn't be read and nothing is known about it.</returns>
    internal static bool TryDiscTag(string? path, out string? tag)
    {
        tag = null;
        if (string.IsNullOrEmpty(path) || !DiscExtensions.Contains(Path.GetExtension(path), StringComparer.OrdinalIgnoreCase))
        {
            return true;
        }

        try
        {
            var info = new FileInfo(path);
            if (!info.Exists)
            {
                throw new FileNotFoundException(null, path);
            }

            var stamp = (info.Length, info.LastWriteTimeUtc);
            if (DiscTags.TryGetValue(path, out var known) && known.Stamp == stamp)
            {
                tag = known.Tag;
                return true;
            }

            tag = ReadDiscHeaderTag(path);
            if (DiscTags.Count >= MaxDiscTags)
            {
                DiscTags.Clear();
            }

            DiscTags[path] = (stamp, tag);
            return true;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            if (DiscTags.TryGetValue(path, out var last))
            {
                tag = last.Tag;
                return true;
            }

            return false;
        }
        catch (ArgumentException)
        {
            return true; // not a usable path: nothing to read, the header names nothing
        }
    }

    /// <summary>Header answers per path (review #4); a few thousand untagged discs at most.</summary>
    private static readonly ConcurrentDictionary<string, ((long Length, DateTime Time) Stamp, string? Tag)> DiscTags = new(StringComparer.Ordinal);

    private const int MaxDiscTags = 20000;

    /// <summary>Plain disc images whose own header can name their console.</summary>
    private static readonly string[] DiscExtensions = [".iso", ".rvz", ".wia", ".gcz", ".ciso"];

    /// <summary>
    /// The console a disc image's own header names, or null. GameCube / Wii: the disc magic (<c>C2339F3D</c> at 0x1C,
    /// <c>5D1C9EA3</c> at 0x18) in a plain image or a Dolphin CISO (data from 0x8000), the disc type of a WIA / RVZ
    /// (1 GameCube, 2 Wii, at 0x48) or the sub type of a GCZ (0 GameCube, 1 Wii, at 4). Else an ISO 9660 disc whose root
    /// holds <c>PSP_GAME</c> (PSP) or a <c>SYSTEM.CNF</c> with <c>BOOT2</c> (PS2). Reads a few KB at most; any read
    /// problem is "don't know".
    /// </summary>
    /// <param name="path">File path.</param>
    /// <returns>Console tag or null.</returns>
    internal static string? DiscHeaderTag(string? path)
    {
        if (string.IsNullOrEmpty(path) || !File.Exists(path))
        {
            return null;
        }

        try
        {
            return ReadDiscHeaderTag(path);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or ArgumentException)
        {
            return null;
        }
    }

    /// <summary><see cref="DiscHeaderTag"/>, but a file that can't be opened or read throws (IOException,
    /// UnauthorizedAccessException) instead of answering "none".</summary>
    private static string? ReadDiscHeaderTag(string path)
    {
        using var f = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite);
        var head = ReadAt(f, 0, 0x4C);
        if (head is null)
        {
            return null;
        }

        if (GcWiiMagic(head, 0) is string plain)
        {
            return plain;
        }

        if (head.AsSpan(0, 4).SequenceEqual("WIA\u0001"u8) || head.AsSpan(0, 4).SequenceEqual("RVZ\u0001"u8))
        {
            return System.Buffers.Binary.BinaryPrimitives.ReadUInt32BigEndian(head.AsSpan(0x48)) switch { 1 => "GameCube", 2 => "Wii", _ => null };
        }

        if (System.Buffers.Binary.BinaryPrimitives.ReadUInt32LittleEndian(head) == 0xB10BC001)
        {
            return System.Buffers.Binary.BinaryPrimitives.ReadUInt32LittleEndian(head.AsSpan(4)) switch { 0 => "GameCube", 1 => "Wii", _ => null };
        }

        if (head.AsSpan(0, 4).SequenceEqual("CISO"u8))
        {
            // Dolphin's CISO keeps the disc from 0x8000; PSP / PS2 CISO (.cso) does not, and is left to its folder.
            return ReadAt(f, 0x8000, 0x20) is byte[] ciso ? GcWiiMagic(ciso, 0) : null;
        }

        if (!string.Equals(Path.GetExtension(path), ".iso", StringComparison.OrdinalIgnoreCase))
        {
            return null;
        }

        return Iso9660Tag(f);
    }

    /// <summary>An .iso's console from its ISO 9660 root folder: <c>PSP_GAME</c> = PSP, a <c>SYSTEM.CNF</c> naming BOOT2 =
    /// PlayStation 2. Null for anything else.</summary>
    private static string? Iso9660Tag(FileStream f)
    {
        // ISO 9660: the primary volume descriptor at sector 16, its root directory record at byte 156.
        const int Sector = 2048;
        var pvd = ReadAt(f, 16L * Sector, Sector);
        if (pvd is null || pvd[0] != 1 || pvd[1] != (byte)'C' || pvd[2] != (byte)'D' || pvd[3] != (byte)'0' || pvd[4] != (byte)'0' || pvd[5] != (byte)'1')
        {
            return null;
        }

        var rootLba = BitConverter.ToUInt32(pvd, 156 + 2);
        var rootSize = (int)Math.Min(BitConverter.ToUInt32(pvd, 156 + 10), 4 * Sector);
        var dir = ReadAt(f, (long)rootLba * Sector, rootSize);
        if (dir is null)
        {
            return null;
        }

        for (var i = 0; i < dir.Length;)
        {
            int len = dir[i];
            if (len == 0)
            {
                i = ((i / Sector) + 1) * Sector; // records never cross a sector: the rest of this one is padding
                continue;
            }

            if (i + len > dir.Length || len < 34)
            {
                break;
            }

            var nameLen = dir[i + 32];
            var name = System.Text.Encoding.ASCII.GetString(dir, i + 33, Math.Min(nameLen, len - 33));
            if (name.Equals("PSP_GAME", StringComparison.OrdinalIgnoreCase))
            {
                return "PSP";
            }

            if (name.StartsWith("SYSTEM.CNF", StringComparison.OrdinalIgnoreCase))
            {
                var lba = BitConverter.ToUInt32(dir, i + 2);
                var size = (int)Math.Min(BitConverter.ToUInt32(dir, i + 10), 1024);
                var cnf = ReadAt(f, (long)lba * Sector, size);
                if (cnf is not null && System.Text.Encoding.ASCII.GetString(cnf).Contains("BOOT2", StringComparison.OrdinalIgnoreCase))
                {
                    return "PlayStation 2";
                }

                return null; // a SYSTEM.CNF without BOOT2 is a PS1 disc (PS1 .iso isn't a Shoalcade file type)
            }

            i += len;
        }

        return null;
    }

    private static string? GcWiiMagic(byte[] b, int at)
    {
        if (b.Length < at + 0x20)
        {
            return null;
        }

        if (b[at + 0x1C] == 0xC2 && b[at + 0x1D] == 0x33 && b[at + 0x1E] == 0x9F && b[at + 0x1F] == 0x3D)
        {
            return "GameCube";
        }

        return b[at + 0x18] == 0x5D && b[at + 0x19] == 0x1C && b[at + 0x1A] == 0x9E && b[at + 0x1B] == 0xA3 ? "Wii" : null;
    }

    private static byte[]? ReadAt(FileStream f, long offset, int count)
    {
        if (count <= 0 || offset < 0 || offset + count > f.Length)
        {
            return null;
        }

        f.Seek(offset, SeekOrigin.Begin);
        var b = new byte[count];
        var got = 0;
        while (got < count)
        {
            var n = f.Read(b, got, count - got);
            if (n <= 0)
            {
                return null;
            }

            got += n;
        }

        return b;
    }

    /// <summary>The console tag an extension names on its own (ours or another console's, e.g. ".sfc" = SNES), or null.</summary>
    /// <param name="ext">Extension with the dot.</param>
    /// <returns>Console tag or null.</returns>
    internal static string? ExtensionTag(string? ext) =>
        !string.IsNullOrEmpty(ext) && (UnambiguousExtensions.TryGetValue(ext, out var tag) || LibraryOnlyExtensions.TryGetValue(ext, out tag)) ? tag : null;

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

    [GeneratedRegex(@"(?:[\[\(]\s*dis[ck]\s*([1-9][0-9]{0,2}|[IVX]{1,4})\s*[\]\)]|(?<![a-zA-Z])dis[ck]\s*([1-9][0-9]{0,2}|[IVX]{1,4})(?![a-zA-Z0-9]))", RegexOptions.IgnoreCase)]
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
