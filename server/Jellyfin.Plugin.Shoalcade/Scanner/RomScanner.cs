using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using Jellyfin.Plugin.Shoalcade.Consoles;

namespace Jellyfin.Plugin.Shoalcade.Scanner;

/// <summary>One game found in the library by the Shoalcade scanner.</summary>
/// <param name="Path">The item's path (its Jellyfin id comes from this): the ROM file, the .bin of a one-.cue folder, or a multi-disc folder.</param>
/// <param name="Name">Name people see.</param>
/// <param name="Console">Console.</param>
/// <param name="Region">First region in the name, if any.</param>
/// <param name="SeriesName">Containing folder name, unless it is just a console name (as JellyEMU does).</param>
/// <param name="IsInMixedFolder">True for a ROM file sitting among others; false when the item stands for a whole folder.</param>
/// <param name="MultiDisc">True for a disc set (multi-disc folder or .j3u list).</param>
/// <param name="HashPath">The file whose MD5 identifies the game (first disc, the .bin of a .cue, the ROM itself).</param>
public sealed record ScannedRom(string Path, string Name, GameConsole Console, string? Region, string? SeriesName, bool IsInMixedFolder, bool MultiDisc, string HashPath)
{
    /// <summary>Tag on every item our scanner makes; <see cref="Library.RomLibrary"/> treats it as a ROM.</summary>
    public const string ScannerTag = "Shoalcade";

    /// <summary>Tag on disc sets.</summary>
    public const string MultiDiscTag = "MultiDisc";

    /// <summary>Tag on 3DS games, for the planned hand-off to the Azahar app (D2).</summary>
    public const string AzaharTag = "Azahar";

    /// <summary>Gets the item tags: Shoalcade, the console (JellyEMU's exact tag, D42), region, MultiDisc, Azahar for 3DS.</summary>
    public IReadOnlyList<string> Tags
    {
        get
        {
            var tags = new List<string> { ScannerTag, Console.Tags[0] };
            if (!string.IsNullOrEmpty(Region))
            {
                tags.Add(Region);
            }

            if (MultiDisc)
            {
                tags.Add(MultiDiscTag);
            }

            if (Console.Key == "3ds")
            {
                tags.Add(AzaharTag);
            }

            return tags;
        }
    }
}

/// <summary>
/// The path rules of the Shoalcade scanner. Pure file-system logic (no Jellyfin types) so it can be tested on real
/// folders. Only reads: it never writes, moves or deletes anything near the ROMs.
/// <para>Rules (kept as JellyEMU's where sensible, so game ids match where possible, D89):</para>
/// <list type="bullet">
/// <item>A ROM file of a supported console is one game, path = the file (ids match JellyEMU).</item>
/// <item>A folder holding exactly one .cue (whose first data file exists) is one game, path = that .bin (ids match).</item>
/// <item>A folder holding two or more discs ("(Disc 1)", "(Disc 2)"...) is one game, path = the folder, tagged MultiDisc
/// (ids match). Unlike JellyEMU, a folder holding one ROM, or several different games, is NOT turned into one item: its
/// files are found one by one, so each can be downloaded (a folder item can't be).</item>
/// <item>A .j3u disc list (JellyEMU's format) is one game, tagged MultiDisc; the files it lists are not separate games.</item>
/// <item>A console folder ("gba", "PSP", "Game Boy Advance") is never one game itself (JellyEMU would turn a console
/// folder holding one .cue into a single game).</item>
/// <item>Only consoles Shoalcade supports become games; other ROMs are left alone.</item>
/// <item>A ROM file's extension must be one of <b>its own</b> console's (or .zip/.7z), so a PSP .iso in a PS1 folder is
/// not a PS1 game. PS1 (D137) is one .chd or .pbp file only: no archives, .cue/.bin, discs folders or .j3u lists.
/// A .pbp whose console isn't named anywhere is a PS1 game if its header says so (<c>PSISOIMG</c>/<c>PSTITLEIMG</c>).</item>
/// </list>
/// <para>The folder, disc and .cue/.j3u rules are ported from JellyEMU 0.9.2.4 (<c>RetroGamesCore</c> path handling and
/// <c>NameCleaner</c>; MIT, © 2026 Jellyfin GP). The MIT notice is in THIRD_PARTY_NOTICES.md (shipped in the plugin zip)
/// and docs/licences.md.</para>
/// </summary>
public static class RomScanner
{
    private static readonly string[] ArchiveExtensions = [".zip", ".7z"];

    private static readonly HashSet<string> AudioExtensions = new(StringComparer.OrdinalIgnoreCase)
    {
        ".mp3", ".m4a", ".m4b", ".mp4", ".wav", ".flac", ".ape", ".ogg", ".wma", ".aac", ".opus",
    };

    /// <summary>Gets the file extensions the scanner looks at: every supported console's, plus .zip and .7z archives.</summary>
    public static IReadOnlySet<string> RomExtensions { get; } = new HashSet<string>(
        GameConsole.All.SelectMany(c => c.Extensions).Concat(ArchiveExtensions),
        StringComparer.OrdinalIgnoreCase);

    /// <summary>Works out whether a path is a game, and which.</summary>
    /// <param name="path">File or folder path.</param>
    /// <param name="isDirectory">Whether it is a folder.</param>
    /// <returns>The game, or null if this path is not one (Jellyfin then handles it as normal).</returns>
    public static ScannedRom? Resolve(string path, bool isDirectory)
    {
        if (string.IsNullOrEmpty(path))
        {
            return null;
        }

        try
        {
            return isDirectory ? ResolveFolder(path) : ResolveFile(path);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            // Unreadable folder or file: not a game this time; the next scan tries again.
            return null;
        }
    }

    /// <summary>
    /// The game an existing library item stands for, from its path (the reverse of <see cref="Resolve"/>): also finds a
    /// one-.cue folder game from its .bin path. Null if the item is not one of our games.
    /// </summary>
    /// <param name="itemPath">The item's path.</param>
    /// <returns>The game, or null.</returns>
    public static ScannedRom? ForItemPath(string? itemPath)
    {
        if (string.IsNullOrEmpty(itemPath))
        {
            return null;
        }

        var rom = Directory.Exists(itemPath) ? Resolve(itemPath, true) : Resolve(itemPath, false);
        if (rom is null && File.Exists(itemPath) && Path.GetDirectoryName(itemPath) is string dir)
        {
            rom = Resolve(dir, true);
        }

        return rom is not null && string.Equals(rom.Path, itemPath, StringComparison.Ordinal) ? rom : null;
    }

    /// <summary>The files of a .cue sheet (FILE lines), resolved next to the .cue. Read-only.</summary>
    /// <param name="cuePath">.cue path.</param>
    /// <returns>Referenced paths, in order.</returns>
    public static IReadOnlyList<string> CueFiles(string cuePath) => ReadList(cuePath, cue: true);

    /// <summary>The files of a .j3u disc list (one path per line, # comments). Read-only.</summary>
    /// <param name="j3uPath">.j3u path.</param>
    /// <returns>Referenced paths, in order.</returns>
    public static IReadOnlyList<string> J3uFiles(string j3uPath) => ReadList(j3uPath, cue: false);

    /// <summary>The first data (non-audio) file of a .cue that exists, or null.</summary>
    /// <param name="cuePath">.cue path.</param>
    /// <returns>Path or null.</returns>
    public static string? CueFirstBin(string cuePath) =>
        CueFiles(cuePath).FirstOrDefault(p => !AudioExtensions.Contains(Path.GetExtension(p)) && File.Exists(p));

    private static ScannedRom? ResolveFolder(string path)
    {
        var trimmed = path.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
        var dirName = Path.GetFileName(trimmed);

        // A console folder ("psp", "Game Boy Advance") is never one game itself.
        if (ConsoleDetector.IsConsoleFolderName(dirName))
        {
            return null;
        }

        // Rule: one .cue in the folder -> the game is its .bin (JellyEMU's rule, same path so the same id).
        var cues = Directory.GetFiles(trimmed, "*.cue");
        if (cues.Length == 1 && CueFirstBin(cues[0]) is string bin)
        {
            var console = ConsoleDetector.Resolve(cues[0]);
            return console is null || console.FilesOnly
                ? null
                : new ScannedRom(bin, ConsoleDetector.CleanName(dirName), console, FirstRegion(cues[0]), SeriesFor(trimmed), false, false, bin);
        }

        // Rule: two or more discs -> the folder is one game.
        var discs = Directory.EnumerateFiles(trimmed)
            .Where(f => IsDiscCandidate(f) && ConsoleDetector.ResolveDisc(f) is not null)
            .OrderBy(f => f, StringComparer.Ordinal)
            .ToList();
        if (discs.Count < 2)
        {
            return null;
        }

        var discConsole = ConsoleDetector.Resolve(discs[0]);
        if (discConsole is null || discConsole.FilesOnly)
        {
            return null;
        }

        var hash = string.Equals(Path.GetExtension(discs[0]), ".cue", StringComparison.OrdinalIgnoreCase) ? CueFirstBin(discs[0]) ?? discs[0] : discs[0];
        return new ScannedRom(path, ConsoleDetector.CleanName(dirName), discConsole, FirstRegion(trimmed) ?? FirstRegion(discs[0]), SeriesFor(trimmed), false, true, hash);
    }

    private static ScannedRom? ResolveFile(string path)
    {
        var ext = Path.GetExtension(path);
        var dir = Path.GetDirectoryName(path) ?? string.Empty;
        var name = ConsoleDetector.CleanName(Path.GetFileNameWithoutExtension(path));

        if (string.Equals(ext, ".j3u", StringComparison.OrdinalIgnoreCase))
        {
            var refs = J3uFiles(path).Where(File.Exists).ToList();
            var console = refs.Count == 0 ? null : ConsoleDetector.Resolve(refs[0]);
            return console is null || console.FilesOnly ? null : new ScannedRom(path, name, console, FirstRegion(path), SeriesFor(path), true, true, refs[0]);
        }

        if (string.Equals(ext, ".cue", StringComparison.OrdinalIgnoreCase))
        {
            // A lone .cue in a game folder belongs to the folder rule; otherwise each .cue is a game.
            if (CueFirstBin(path) is not string bin
                || (Directory.GetFiles(dir, "*.cue").Length == 1 && !ConsoleDetector.IsConsoleFolderName(Path.GetFileName(dir))))
            {
                return null;
            }

            var console = ConsoleDetector.Resolve(path);
            return console is null || console.FilesOnly || IsListedByJ3u(path) ? null : new ScannedRom(path, name, console, FirstRegion(path), SeriesFor(path), true, false, bin);
        }

        if (!RomExtensions.Contains(ext) || IsListedByJ3u(path))
        {
            return null;
        }

        var c = ConsoleDetector.Resolve(path) ?? SniffUnknown(path);
        return c is null || !AllowsFile(c, path) ? null : new ScannedRom(path, name, c, FirstRegion(path), SeriesFor(path), true, false, path);
    }

    /// <summary>True if a ROM file of this console may have this extension: one of its own, or an archive unless the
    /// console takes single files only (PS1).</summary>
    private static bool AllowsFile(GameConsole console, string path)
    {
        var ext = Path.GetExtension(path);
        return console.Extensions.Contains(ext, StringComparer.OrdinalIgnoreCase)
            || (!console.SingleFileOnly && ArchiveExtensions.Contains(ext, StringComparer.OrdinalIgnoreCase));
    }

    /// <summary>
    /// Only for a file whose name and folders name no console at all: a .pbp whose DATA.PSAR starts with
    /// <c>PSISOIMG</c> (one disc) or <c>PSTITLEIMG</c> (several) is a PS1 game. CHD headers are NOT read: a CD-type CHD
    /// could be any CD console and a DVD-type one could be PS2, so a .chd still needs a PS1 or PSP folder or name.
    /// </summary>
    private static GameConsole? SniffUnknown(string path)
    {
        if (!string.Equals(Path.GetExtension(path), ".pbp", StringComparison.OrdinalIgnoreCase)
            || !string.Equals(ConsoleDetector.ResolveTag(path), ConsoleDetector.Unknown, StringComparison.Ordinal))
        {
            return null;
        }

        return IsPs1Pbp(path) ? GameConsole.FromKey("psx") : null;
    }

    /// <summary>True if the file is a PS1 EBOOT.PBP (header <c>\0PBP</c>, DATA.PSAR = <c>PSISOIMG</c> or <c>PSTITLEIMG</c>).</summary>
    /// <param name="path">File path.</param>
    /// <returns>Whether it is a PS1 PBP. Read-only; false for anything unreadable.</returns>
    internal static bool IsPs1Pbp(string path)
    {
        Span<byte> head = stackalloc byte[40];
        Span<byte> psar = stackalloc byte[10];
        using var f = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite);
        if (f.Read(head) != head.Length || !head[..4].SequenceEqual("\0PBP"u8))
        {
            return false;
        }

        long at = System.Buffers.Binary.BinaryPrimitives.ReadUInt32LittleEndian(head[36..]);
        if (at < head.Length || at > f.Length - psar.Length)
        {
            return false;
        }

        f.Position = at;
        return f.Read(psar) == psar.Length && (psar[..8].SequenceEqual("PSISOIMG"u8) || psar.SequenceEqual("PSTITLEIMG"u8));
    }

    private static bool IsDiscCandidate(string file)
    {
        var ext = Path.GetExtension(file);
        if (string.Equals(ext, ".cue", StringComparison.OrdinalIgnoreCase))
        {
            return CueFirstBin(file) is not null;
        }

        return RomExtensions.Contains(ext);
    }

    private static bool IsListedByJ3u(string path)
    {
        var dir = Path.GetDirectoryName(path);
        if (string.IsNullOrEmpty(dir))
        {
            return false;
        }

        var file = Path.GetFileName(path);
        return Directory.EnumerateFiles(dir, "*.j3u")
            .Any(j => J3uFiles(j).Any(r => string.Equals(Path.GetFileName(r), file, StringComparison.OrdinalIgnoreCase)));
    }

    private static string? FirstRegion(string path)
    {
        var regions = ConsoleDetector.ResolveRegions(path);
        return regions.Count > 0 ? regions[0] : null;
    }

    private static string? SeriesFor(string path)
    {
        var parent = Path.GetFileName(Path.GetDirectoryName(path));
        return string.IsNullOrEmpty(parent) || ConsoleDetector.IsConsoleFolderName(parent) ? null : parent;
    }

    private static List<string> ReadList(string listPath, bool cue)
    {
        var result = new List<string>();
        var dir = Path.GetDirectoryName(listPath) ?? string.Empty;
        var info = new FileInfo(listPath);
        if (!info.Exists || info.Length > 1024 * 1024)
        {
            return result; // a disc list is tiny; anything else isn't one
        }

        foreach (var line in File.ReadLines(listPath))
        {
            var entry = cue ? CueFileName(line) : J3uLine(line);
            if (string.IsNullOrWhiteSpace(entry))
            {
                continue;
            }

            // Only files inside the list's own folder (G5 s0.2 M1): an absolute or ../ entry could otherwise make any
            // file the server can read (jellyfin.db, config) a downloadable "game".
            if (Path.IsPathRooted(entry))
            {
                continue;
            }

            // The path is kept as written (not normalised) so game ids stay the same as before and as JellyEMU's.
            var combined = Path.Combine(dir, entry);
            if (IsInside(combined, dir))
            {
                result.Add(combined);
            }
        }

        return result;
    }

    /// <summary>Gets the path comparison the file system uses: case-insensitive on Windows and macOS, exact on Linux.</summary>
    internal static StringComparison PathComparison { get; } =
        OperatingSystem.IsWindows() || OperatingSystem.IsMacOS() ? StringComparison.OrdinalIgnoreCase : StringComparison.Ordinal;

    /// <summary>True if <paramref name="path"/> is strictly inside <paramref name="folder"/> (after resolving <c>..</c>).</summary>
    /// <param name="path">File path.</param>
    /// <param name="folder">Folder path.</param>
    /// <returns>Whether it is inside.</returns>
    public static bool IsInside(string path, string folder)
    {
        if (string.IsNullOrEmpty(path) || string.IsNullOrEmpty(folder))
        {
            return false;
        }

        var f = Path.GetFullPath(folder).TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar) + Path.DirectorySeparatorChar;
        var p = Path.GetFullPath(path);
        return p.Length > f.Length && p.StartsWith(f, PathComparison);
    }

    private static string? J3uLine(string line)
    {
        var t = line.Trim();
        return t.Length == 0 || t.StartsWith('#') ? null : t;
    }

    private static string? CueFileName(string line)
    {
        var t = line.TrimStart();
        if (!t.StartsWith("FILE", StringComparison.OrdinalIgnoreCase))
        {
            return null;
        }

        var rest = t[4..].TrimStart();
        if (rest.StartsWith('"'))
        {
            var close = rest.IndexOf('"', 1);
            return close < 0 ? null : rest[1..close];
        }

        var space = rest.IndexOfAny([' ', '\t']);
        return space >= 0 ? rest[..space] : rest;
    }
}
