using System;
using System.Collections.Concurrent;
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

    /// <summary>Files that sit next to ROMs (pictures, notes, saves, checksums) and are never games: not reported as skipped.</summary>
    private static readonly HashSet<string> CompanionExtensions = new(StringComparer.OrdinalIgnoreCase)
    {
        ".png", ".jpg", ".jpeg", ".gif", ".bmp", ".webp", ".txt", ".nfo", ".xml", ".json", ".pdf", ".htm", ".html",
        ".ini", ".cfg", ".db", ".srm", ".sav", ".state", ".md5", ".sha1", ".sfv", ".url", ".lnk",
    };

    /// <summary>Disc-image and list files of any console (ours or not): worth reporting when they are not a game.</summary>
    private static readonly HashSet<string> DiscExtensions = new(StringComparer.OrdinalIgnoreCase)
    {
        ".iso", ".bin", ".img", ".cue", ".chd", ".cso", ".pbp", ".cdi", ".gdi", ".mdf", ".mds", ".ccd", ".m3u", ".j3u",
    };

    /// <summary>
    /// Why a file in a games library is not a game, for the scan log (D227; lessons 29/10/2026: the scanner is never silent
    /// about what it leaves out). Null when the file is a game, part of one (a disc of a disc folder, the .bin of a
    /// game's .cue, a file a .j3u list names), or plainly not a game file (a picture, notes, a save, a hidden file).
    /// Read-only, like <see cref="Resolve"/>.
    /// </summary>
    /// <param name="path">File path.</param>
    /// <returns>A short reason in plain words, or null.</returns>
    public static string? SkipReason(string path) => SkipReason(path, knownNotAGame: false);

    /// <summary><see cref="SkipReason(string)"/> for a path the caller has just resolved to no game (review #16: not
    /// resolved twice).</summary>
    /// <param name="path">File path.</param>
    /// <param name="knownNotAGame">True when <see cref="Resolve"/> just answered null for it.</param>
    /// <returns>A short reason in plain words, or null.</returns>
    internal static string? SkipReason(string path, bool knownNotAGame)
    {
        if (string.IsNullOrEmpty(path))
        {
            return null;
        }

        try
        {
            var ext = Path.GetExtension(path);
            if (Path.GetFileName(path).StartsWith('.') || string.IsNullOrEmpty(ext) || CompanionExtensions.Contains(ext))
            {
                return null;
            }

            return (knownNotAGame || Resolve(path, false) is null) && !IsPartOfAGame(path) ? NotAGameReason(path, ext) : null;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return null;
        }
    }

    private static string? NotAGameReason(string path, string ext)
    {
        var gameLike = RomExtensions.Contains(ext) || DiscExtensions.Contains(ext) || ConsoleDetector.ExtensionTag(ext) is not null;
        var tag = ConsoleDetector.ResolveTag(path);
        var console = ConsoleDetector.Resolve(path);
        if (console is null)
        {
            // Another console's file (a SNES folder, a .sfc): anything there but pictures and notes is worth a line.
            if (!string.Equals(tag, ConsoleDetector.Unknown, StringComparison.Ordinal))
            {
                return tag + " games are not played by Shoalcade";
            }

            return gameLike ? "no console named in its name or folders" : null;
        }

        if (!gameLike)
        {
            return null;
        }

        var takes = Takes(console);
        if (console.Key == "ps2" && (string.Equals(ext, ".cue", StringComparison.OrdinalIgnoreCase) || string.Equals(ext, ".bin", StringComparison.OrdinalIgnoreCase)))
        {
            return Ps2BinCue; // D-5: PCSX2 reads .bin/.cue, but one file per game keeps saves and ids simple
        }

        if (console.FilesOnly && (string.Equals(ext, ".cue", StringComparison.OrdinalIgnoreCase) || string.Equals(ext, ".j3u", StringComparison.OrdinalIgnoreCase)))
        {
            return $"{console.DisplayName} games must be one file ({takes}), not a .cue sheet or .j3u list";
        }

        return console.Extensions.Contains(ext, StringComparer.OrdinalIgnoreCase)
            ? $"not a {console.DisplayName} game file" // its own file type, but e.g. a .pbp whose header isn't a game's
            : $"{ext} is not a {console.DisplayName} file type (it takes {takes})";
    }

    /// <summary>The scan-log reason for a PS2 .bin / .cue game (D-5): it is skipped, and the fix is one .chd.</summary>
    internal const string Ps2BinCue = "PlayStation 2 .bin/.cue games are skipped: make a .chd of the disc (chdman createcd, or createdvd for a DVD)";

    /// <summary>
    /// Why a folder that looks like one game (one .cue sheet, or two or more discs) is not one, for the scan log (D227).
    /// Null when it is a game, a console folder, or not a game-looking folder. Such a folder's files are often never
    /// shown to the scanner one by one (Jellyfin's audiobook rule takes a .cue folder), so it is reported here.
    /// </summary>
    /// <param name="path">Folder path.</param>
    /// <returns>A short reason in plain words, or null.</returns>
    public static string? FolderSkipReason(string path)
    {
        if (string.IsNullOrEmpty(path))
        {
            return null;
        }

        try
        {
            var trimmed = path.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
            if (ConsoleDetector.IsConsoleFolderName(Path.GetFileName(trimmed)) || ResolveFolder(trimmed) is not null)
            {
                return null;
            }

            var cues = Directory.GetFiles(trimmed, "*.cue");
            var first = cues.Length == 1 && CueFirstBin(cues[0]) is not null ? cues[0] : null;
            if (first is null)
            {
                var discs = Directory.EnumerateFiles(trimmed)
                    .Where(f => IsDiscCandidate(f) && ConsoleDetector.ResolveDisc(f) is not null)
                    .OrderBy(f => f, StringComparer.Ordinal)
                    .ToList();
                first = discs.Count >= 2 ? discs[0] : null;
            }

            if (first is null)
            {
                return null;
            }

            var console = ConsoleDetector.Resolve(first);
            if (console is null)
            {
                var tag = ConsoleDetector.ResolveTag(first);
                return string.Equals(tag, ConsoleDetector.Unknown, StringComparison.Ordinal)
                    ? "no console named in its name or folders"
                    : tag + " games are not played by Shoalcade";
            }

            return console.Key == "ps2" && cues.Length > 0 ? Ps2BinCue
                : console.TakesDiscSets && cues.Length == 0 ? $"a {console.DisplayName} disc set needs two or more discs in its own formats ({Takes(console)})"
                : console.FilesOnly ? $"{console.DisplayName} games must be one file ({Takes(console)}), not a .cue sheet or a folder of discs" : null;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return null;
        }
    }

    /// <summary>The file types a console's games may be, for the log ("it takes .chd, .pbp").</summary>
    private static string Takes(GameConsole console) =>
        string.Join(", ", console.Extensions) + (console.SingleFileOnly ? string.Empty : ", .zip, .7z");

    /// <summary>True if a file belongs to a game that is another path: a disc of a disc folder, the .bin of a .cue game,
    /// or a file a .j3u list names.</summary>
    private static bool IsPartOfAGame(string path)
    {
        var dir = Path.GetDirectoryName(path);
        if (string.IsNullOrEmpty(dir))
        {
            return false;
        }

        var parts = PartsOf(dir);
        return parts.FolderIsAGame || parts.Names.Contains(Path.GetFileName(path));
    }

    /// <summary>What in a folder belongs to a game (review #16): whether the folder is one game, and the file names its
    /// .j3u lists, game .m3u lists and game .cue sheets name. Worked out once per folder version (its time) and kept two
    /// minutes, so a scan doesn't parse every list once per file in the folder.</summary>
    private static FolderParts PartsOf(string dir)
    {
        var time = Directory.GetLastWriteTimeUtc(dir);
        var now = DateTime.UtcNow;
        if (Parts.TryGetValue(dir, out var known) && known.FolderTime == time && now - known.Made < PartsLife)
        {
            return known;
        }

        var names = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (var j in Directory.EnumerateFiles(dir, "*.j3u"))
        {
            names.UnionWith(J3uFiles(j).Select(Path.GetFileName).OfType<string>());
        }

        foreach (var list in Directory.EnumerateFiles(dir, "*.m3u").Concat(Directory.EnumerateFiles(dir, "*.cue")))
        {
            if (Resolve(list, false) is not null)
            {
                var cue = string.Equals(Path.GetExtension(list), ".cue", StringComparison.OrdinalIgnoreCase);
                names.UnionWith(ReadList(list, cue).Select(Path.GetFileName).OfType<string>());
            }
        }

        var parts = new FolderParts(now, time, ResolveFolder(dir) is not null, names);
        if (Parts.Count >= MaxParts)
        {
            Parts.Clear();
        }

        Parts[dir] = parts;
        return parts;
    }

    private sealed record FolderParts(DateTime Made, DateTime FolderTime, bool FolderIsAGame, HashSet<string> Names);

    private static readonly ConcurrentDictionary<string, FolderParts> Parts = new(StringComparer.Ordinal);

    private static readonly TimeSpan PartsLife = TimeSpan.FromMinutes(2);

    private const int MaxParts = 4096;

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
        if (discConsole is not null && discConsole.TakesDiscSets)
        {
            // D-8: PS1 / PS2 / GameCube / Wii disc sets, every disc in the console's own formats (no .cue/.bin).
            var set = DiscSet(trimmed, discConsole);
            return set.Count < 2 ? null
                : new ScannedRom(path, ConsoleDetector.CleanName(dirName), discConsole, FirstRegion(trimmed) ?? FirstRegion(set[0]), SeriesFor(trimmed), false, true, set[0]);
        }

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

        if (string.Equals(ext, ".m3u", StringComparison.OrdinalIgnoreCase))
        {
            var console = M3uConsole(path);
            IReadOnlyList<string> set = console is null ? [] : DiscSet(path, console);
            return set.Count < 2 ? null : new ScannedRom(path, name, console!, FirstRegion(path) ?? FirstRegion(set[0]), SeriesFor(path), true, true, set[0]);
        }

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

        if (!RomExtensions.Contains(ext) || IsListedByJ3u(path) || IsListedByM3u(path))
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

    /// <summary>
    /// The discs of a disc set (D-8), in play order: for a game folder, its "(Disc n)" files by disc number; for a .m3u,
    /// the files it lists. Only files inside the set's own folder, in <paramref name="console"/>'s own formats, that
    /// exist. Read-only. Empty when it isn't a set (or the console takes none).
    /// </summary>
    /// <param name="itemPath">The game's path: the folder or the .m3u.</param>
    /// <param name="console">The game's console.</param>
    /// <returns>Full paths of the discs (the last ones read when the folder can't be read now; empty when never read).</returns>
    public static IReadOnlyList<string> DiscSet(string itemPath, GameConsole console) =>
        TryDiscSet(itemPath, console, out var set) ? set : [];

    /// <summary>
    /// <see cref="DiscSet"/> for the library (review S4/S5): worked out once per folder version (the folder's time, and
    /// the list's for a .m3u) and kept two minutes, so listing games doesn't list every set folder twice. When the folder
    /// or list can't be read now (a sleeping NAS), the last set read for that path is used; with none, this returns false
    /// ("don't know now"), so the caller can answer "try again" instead of "no such game". A set whose folder or list is
    /// gone from a folder that can be read is empty (true).
    /// </summary>
    /// <param name="itemPath">The game's path: the folder or the .m3u.</param>
    /// <param name="console">The game's console.</param>
    /// <param name="set">Full paths of the discs, in play order.</param>
    /// <returns>False only when it couldn't be read and nothing is known about it.</returns>
    internal static bool TryDiscSet(string itemPath, GameConsole console, out IReadOnlyList<string> set)
    {
        ArgumentNullException.ThrowIfNull(console);
        set = [];
        if (!console.TakesDiscSets || string.IsNullOrEmpty(itemPath))
        {
            return true;
        }

        var key = console.Key + "|" + itemPath;
        try
        {
            bool Own(string f) => console.Extensions.Contains(Path.GetExtension(f), StringComparer.OrdinalIgnoreCase) && File.Exists(f);
            var isList = string.Equals(Path.GetExtension(itemPath), ".m3u", StringComparison.OrdinalIgnoreCase);
            if (isList ? !File.Exists(itemPath) : !Directory.Exists(itemPath))
            {
                // Gone, or not readable now: only a readable folder around it says which (Exists is false for both).
                var parent = Path.GetDirectoryName(itemPath.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar));
                if (string.IsNullOrEmpty(parent) || !Directory.Exists(parent))
                {
                    throw new DirectoryNotFoundException();
                }

                _ = Directory.EnumerateFileSystemEntries(parent).Any(); // throws if it can't be listed
                DiscSets.TryRemove(key, out _);
                return true;
            }

            var dir = isList ? Path.GetDirectoryName(itemPath) ?? string.Empty : itemPath.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
            var stamp = (Directory.GetLastWriteTimeUtc(dir), isList ? File.GetLastWriteTimeUtc(itemPath) : default);
            var now = DateTime.UtcNow;
            if (DiscSets.TryGetValue(key, out var known) && known.Stamp == stamp && now - known.Made < PartsLife)
            {
                set = known.Set;
                return true;
            }

            if (isList)
            {
                var list = ReadList(itemPath, cue: false);
                set = list.Count > 0 && list.All(Own) ? list : [];
            }
            else
            {
                set = Directory.EnumerateFiles(dir)
                    .Where(f => Own(f) && ConsoleDetector.ResolveDisc(f) is not null)
                    .OrderBy(DiscNumber)
                    .ThenBy(f => f, StringComparer.Ordinal)
                    .ToList();
            }

            if (DiscSets.Count >= MaxParts)
            {
                DiscSets.Clear();
            }

            DiscSets[key] = (now, stamp, set);
            return true;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            if (DiscSets.TryGetValue(key, out var last))
            {
                set = last.Set;
                return true;
            }

            return false;
        }
    }

    private static readonly ConcurrentDictionary<string, (DateTime Made, (DateTime Folder, DateTime List) Stamp, IReadOnlyList<string> Set)> DiscSets =
        new(StringComparer.Ordinal);

    private static int DiscNumber(string file)
    {
        var d = ConsoleDetector.ResolveDisc(file);
        return d is not null && d.StartsWith("Disc ", StringComparison.Ordinal)
            && int.TryParse(d.AsSpan(5), System.Globalization.NumberStyles.None, System.Globalization.CultureInfo.InvariantCulture, out var n) ? n : int.MaxValue;
    }

    /// <summary>The console of a .m3u: named by its own name or folders, else by its first listed file.</summary>
    private static GameConsole? M3uConsole(string m3u)
    {
        var console = ConsoleDetector.Resolve(m3u);
        if (console is null)
        {
            var first = ReadList(m3u, cue: false).FirstOrDefault(File.Exists);
            console = first is null ? null : ConsoleDetector.Resolve(first);
        }

        return console is not null && console.TakesDiscSets ? console : null;
    }

    /// <summary>True if a game .m3u next to this file lists it (the set is the game, not each disc, D-8).</summary>
    private static bool IsListedByM3u(string path)
    {
        var dir = Path.GetDirectoryName(path);
        if (string.IsNullOrEmpty(dir))
        {
            return false;
        }

        var file = Path.GetFileName(path);
        return Directory.EnumerateFiles(dir, "*.m3u")
            .Any(m => ReadList(m, cue: false).Any(r => string.Equals(Path.GetFileName(r), file, StringComparison.OrdinalIgnoreCase))
                && Resolve(m, false) is not null);
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
