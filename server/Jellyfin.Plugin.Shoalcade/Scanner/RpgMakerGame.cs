using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.RegularExpressions;

namespace Jellyfin.Plugin.Shoalcade.Scanner;

/// <summary>One file of an RPG Maker game folder that may be sent.</summary>
/// <param name="Path">Path inside the game folder, with <c>/</c> (e.g. <c>Data/Map001.rxdata</c>).</param>
/// <param name="FullPath">The file on disk.</param>
/// <param name="Size">Bytes.</param>
/// <param name="Modified">Last-write time, UTC ticks.</param>
public sealed record RpgMakerFile(string Path, string FullPath, long Size, long Modified);

/// <summary>What <c>Game.ini</c> says about a game.</summary>
/// <param name="Title">The <c>[Game] Title</c>, or null.</param>
/// <param name="Engine">"XP", "VX" or "VX Ace", or null if it can't be told.</param>
public sealed record RpgMakerIni(string? Title, string? Engine);

/// <summary>
/// RPG Maker XP / VX / VX Ace games (D147): a game is a <b>folder</b> holding <c>Game.ini</c>, directly inside an RPG
/// Maker console folder (<c>RPG Maker/Pokemon Reborn/Game.ini</c>), exactly as the Windows copy unzips. Pure file-system
/// rules (no Jellyfin types), read-only: nothing is ever written, moved or deleted in a game folder.
/// <list type="bullet">
/// <item>Nothing inside a game folder is ever its own game.</item>
/// <item>Windows-only files (<c>*.exe</c>, <c>*.dll</c>, <c>*.lnk</c>, <c>desktop.ini</c>, <c>Thumbs.db</c>) and dotfiles
/// / dot-folders are never listed or sent.</item>
/// <item>Symbolic links, junctions and other reparse points are never followed or sent.</item>
/// </list>
/// </summary>
public static partial class RpgMakerGame
{
    /// <summary>Most files listed for one game (Pokémon Reborn has 12,634; anything far bigger is not a game).</summary>
    public const int MaxFiles = 100_000;

    /// <summary>Deepest folder nesting listed.</summary>
    public const int MaxDepth = 32;

    /// <summary>Largest Game.ini read (a real one is under 1 KB).</summary>
    internal const int MaxIniBytes = 64 * 1024;

    /// <summary>The console tag of RPG Maker folders (ConsoleDetector aliases).</summary>
    internal const string Tag = "RPG Maker";

    /// <summary>DLLs games often ship that don't stop mkxp-z (it has its own engine, audio and Ruby): a game with only
    /// these gets no "may not work" badge.</summary>
    private static readonly Regex[] HarmlessDlls = new[]
    {
        "RGSS*.dll", "x64-msvcrt-ruby*.dll", "msvcrt-ruby*.dll", "zlib1.dll", "libssl*", "libcrypto*", "discord_game_sdk.dll",
        "libTolk.dll", "nvdaControllerClient*.dll", "SDL2*.dll", "steam_api*.dll", "libgcc*", "libstdc++*", "libwinpthread*",
    }.Select(Glob).ToArray();

    /// <summary>Engine by the <c>Scripts</c> file's extension, in the order a folder's own <c>Data/Scripts.*</c> is tried.</summary>
    internal static readonly (string Extension, string Engine)[] ScriptEngines = [(".rvdata2", "VX Ace"), (".rvdata", "VX"), (".rxdata", "XP")];

    private static readonly (string Prefix, string Engine)[] LibraryEngines = [("RGSS1", "XP"), ("RGSS2", "VX"), ("RGSS3", "VX Ace")];

    private static readonly Encoding Utf8Strict = new UTF8Encoding(false, throwOnInvalidBytes: true);

    // Every entry of one folder, links included (callers skip them), so nothing is hidden from the checks.
    private static readonly EnumerationOptions AllEntries = new() { RecurseSubdirectories = false, IgnoreInaccessible = true, AttributesToSkip = 0, ReturnSpecialDirectories = false };

    // Real files of one folder, names matched in any case.
    private static readonly EnumerationOptions TopFiles = new() { MatchCasing = MatchCasing.CaseInsensitive, RecurseSubdirectories = false, IgnoreInaccessible = true, AttributesToSkip = FileAttributes.ReparsePoint };

    /// <summary>True for an RPG Maker console folder name ("RPG Maker", "rpgxp", "RPG Maker VX Ace"...).</summary>
    /// <param name="name">Folder name.</param>
    /// <returns>Whether it is one.</returns>
    public static bool IsConsoleFolderName(string? name) =>
        !string.IsNullOrEmpty(name) && ConsoleDetector.Aliases.TryGetValue(name.Trim(), out var tag) && tag == Tag;

    /// <summary>
    /// True if <paramref name="path"/> is an RPG Maker game: a real folder (not a link) directly inside an RPG Maker
    /// console folder, holding a <c>Game.ini</c> (any case) itself or in its one subfolder (<see cref="GameRoot"/>).
    /// </summary>
    /// <param name="path">Folder path.</param>
    /// <returns>Whether it is a game folder.</returns>
    public static bool IsGameFolder(string? path) => GameRoot(path, inConsoleFolder: true) is not null;

    /// <summary>
    /// The real game folder (the one holding <c>Game.ini</c>) of a game item's folder: the folder itself, or else its
    /// only subfolder, one level down and never deeper, as unzipping often gives (<c>RPG Maker/Rejuvenation/Pokemon
    /// Rejuvenation/Game.ini</c>). Files next to that subfolder (a readme, <c>desktop.ini</c>, <c>.DS_Store</c>) don't
    /// matter; <c>__MACOSX</c> and dot-folders don't count as subfolders. Two or more subfolders: no game (never guessed).
    /// A link is never the game folder. The game item stays the outer folder (what Jellyfin scanned); file paths are
    /// relative to the folder returned here.
    /// </summary>
    /// <param name="folder">Game item folder.</param>
    /// <param name="inConsoleFolder">Also require <paramref name="folder"/> to sit directly inside an RPG Maker console
    /// folder (<see cref="IsGameFolder"/>), so one call both checks a game item and finds its real folder.</param>
    /// <returns>Full path of the folder holding Game.ini, or null.</returns>
    public static string? GameRoot(string? folder, bool inConsoleFolder = false)
    {
        if (string.IsNullOrEmpty(folder))
        {
            return null;
        }

        try
        {
            var dir = new DirectoryInfo(Trim(folder));
            if (!dir.Exists || IsLink(dir) || (inConsoleFolder && !IsConsoleFolderName(dir.Parent?.Name)))
            {
                return null;
            }

            if (FindIni(dir.FullName) is not null)
            {
                return dir.FullName;
            }

            // Links count here (so a link next to a real folder is "two subfolders") but are never picked.
            var subs = dir.EnumerateDirectories("*", AllEntries)
                .Where(d => d.Name.Length > 0 && d.Name[0] != '.' && !string.Equals(d.Name, "__MACOSX", StringComparison.OrdinalIgnoreCase))
                .Take(2)
                .ToList();
            return subs.Count == 1 && !IsLink(subs[0]) && FindIni(subs[0].FullName) is not null ? subs[0].FullName : null;
        }
        catch (Exception ex) when (IsFileSystemError(ex))
        {
            return null;
        }
    }

    /// <summary>
    /// True for a packed game dropped straight into an RPG Maker console folder (<c>.zip</c>, <c>.7z</c>, <c>.rar</c>).
    /// Never a game (D147 #3: folders only); the scan only notes it so the admin knows to unzip it.
    /// </summary>
    /// <param name="path">File path.</param>
    /// <returns>Whether it is one.</returns>
    public static bool IsPackedGame(string? path)
    {
        if (string.IsNullOrEmpty(path))
        {
            return false;
        }

        return HasExtension(path, ".zip", ".7z", ".rar")
            && IsConsoleFolderName(Path.GetFileName(Path.GetDirectoryName(Trim(path))));
    }

    /// <summary>True if <paramref name="path"/> is somewhere inside an RPG Maker game folder (so never a game itself).</summary>
    /// <param name="path">File or folder path.</param>
    /// <returns>Whether it is inside one.</returns>
    public static bool IsInsideGameFolder(string? path)
    {
        if (string.IsNullOrEmpty(path))
        {
            return false;
        }

        var child = Path.GetDirectoryName(Trim(path));
        while (!string.IsNullOrEmpty(child))
        {
            var parent = Path.GetDirectoryName(child);
            if (IsConsoleFolderName(Path.GetFileName(parent)) && IsGameFolder(child))
            {
                return true;
            }

            child = parent;
        }

        return false;
    }

    /// <summary>The game's <c>Game.ini</c> (top level, any case, a real file), or null.</summary>
    /// <param name="folder">Game folder.</param>
    /// <returns>Path or null.</returns>
    public static string? FindIni(string folder)
    {
        return new DirectoryInfo(folder).EnumerateFiles("game.ini", TopFiles)
            .Where(f => string.Equals(f.Name, "Game.ini", StringComparison.OrdinalIgnoreCase) && !IsLink(f))
            .Select(f => f.FullName)
            .OrderBy(f => f, StringComparer.Ordinal)
            .FirstOrDefault();
    }

    /// <summary>Reads the title and engine from <c>Game.ini</c>. Never throws: an unreadable file gives nulls.</summary>
    /// <param name="iniPath">Game.ini path.</param>
    /// <returns>Title and engine.</returns>
    public static RpgMakerIni ReadIni(string iniPath)
    {
        try
        {
            using var fs = new FileStream(iniPath, FileMode.Open, FileAccess.Read, FileShare.ReadWrite);
            var buffer = new byte[Math.Min(MaxIniBytes, Math.Max(0, fs.Length))];
            var n = fs.ReadAtLeast(buffer, buffer.Length, throwOnEndOfStream: false);
            return ParseIni(buffer.AsSpan(0, n));
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return new RpgMakerIni(null, null);
        }
    }

    /// <summary>Parses <c>Game.ini</c> bytes (UTF-8, else Shift-JIS for a Japanese title, else Windows-1252).</summary>
    /// <param name="data">The file.</param>
    /// <returns>Title and engine.</returns>
    public static RpgMakerIni ParseIni(ReadOnlySpan<byte> data)
    {
        var text = Decode(data);
        string? section = null, title = null, library = null, scripts = null;
        foreach (var raw in text.Split('\n'))
        {
            var line = raw.Trim();
            if (line.Length == 0 || line[0] is ';' or '#')
            {
                continue;
            }

            if (line[0] == '[' && line.EndsWith(']'))
            {
                section = line[1..^1].Trim();
                continue;
            }

            var eq = line.IndexOf('=', StringComparison.Ordinal);
            if (eq <= 0 || !string.Equals(section, "Game", StringComparison.OrdinalIgnoreCase))
            {
                continue;
            }

            var key = line[..eq].Trim();
            var value = line[(eq + 1)..].Trim();
            if (string.Equals(key, "Title", StringComparison.OrdinalIgnoreCase))
            {
                title ??= value;
            }
            else if (string.Equals(key, "Library", StringComparison.OrdinalIgnoreCase))
            {
                library ??= value;
            }
            else if (string.Equals(key, "Scripts", StringComparison.OrdinalIgnoreCase))
            {
                scripts ??= value;
            }
        }

        title = string.IsNullOrWhiteSpace(title) ? null : new string(title.Where(c => !char.IsControl(c)).ToArray()).Trim();
        return new RpgMakerIni(string.IsNullOrEmpty(title) ? null : title, EngineOf(scripts, library));
    }

    /// <summary>The engine from <c>Scripts=</c> (Data\Scripts.rxdata XP, .rvdata VX, .rvdata2 VX Ace), else <c>Library=</c>
    /// (RGSS1 XP, RGSS2 VX, RGSS3 VX Ace).</summary>
    /// <param name="scripts">Scripts value.</param>
    /// <param name="library">Library value.</param>
    /// <returns>"XP", "VX", "VX Ace" or null.</returns>
    public static string? EngineOf(string? scripts, string? library)
    {
        var ext = string.IsNullOrEmpty(scripts) ? string.Empty : Path.GetExtension(scripts.Replace('\\', '/')).ToLowerInvariant();
        if (ScriptEngines.FirstOrDefault(e => e.Extension == ext).Engine is string byScripts)
        {
            return byScripts;
        }

        var lib = Path.GetFileName((library ?? string.Empty).Replace('\\', '/')).ToUpperInvariant();
        return LibraryEngines.FirstOrDefault(e => lib.StartsWith(e.Prefix, StringComparison.Ordinal)).Engine;
    }

    /// <summary>The name people see: the Game.ini title, else the cleaned folder name.</summary>
    /// <param name="folder">Game folder.</param>
    /// <param name="ini">What Game.ini says.</param>
    /// <returns>Name.</returns>
    public static string DisplayName(string folder, RpgMakerIni ini)
    {
        ArgumentNullException.ThrowIfNull(ini);
        if (ini.Title is string t)
        {
            return t;
        }

        var name = Path.GetFileName(Trim(folder));
        var clean = ConsoleDetector.CleanName(name);
        return string.IsNullOrWhiteSpace(clean) ? name : clean;
    }

    /// <summary>True for a file or folder name that is never listed or sent: Windows-only programs and shortcuts,
    /// Windows' folder files, dotfiles, and names that aren't safe on every system.</summary>
    /// <param name="name">One file or folder name.</param>
    /// <param name="isFile">True for a file (extensions only count for files).</param>
    /// <returns>Whether it is left out.</returns>
    public static bool IsLeftOut(string name, bool isFile)
    {
        // Also names Windows would quietly change or treat as a device ("Data." is "Data", "NUL.txt" is no file), so a
        // request can never mean a different file than the one listed.
        if (string.IsNullOrEmpty(name) || name[0] is '.' or ' ' || name[^1] is '.' or ' '
            || name.Any(c => char.IsControl(c) || c is '\\' or '/' or ':' or '*' or '?' or '"' or '<' or '>' or '|')
            || ReservedNameRegex().IsMatch(name))
        {
            return true;
        }

        if (!isFile)
        {
            return false;
        }

        return HasExtension(name, ".exe", ".dll", ".lnk")
            || name.Equals("desktop.ini", StringComparison.OrdinalIgnoreCase)
            || name.Equals("Thumbs.db", StringComparison.OrdinalIgnoreCase);
    }

    /// <summary>True if the game folder's top level holds a DLL that isn't on the known-harmless list (the game may need
    /// Windows code mkxp-z can't run: "may not work", D147 #11).</summary>
    /// <param name="folder">Game folder.</param>
    /// <returns>Whether to show the badge.</returns>
    public static bool MayNotWork(string folder)
    {
        try
        {
            return new DirectoryInfo(folder).EnumerateFiles("*.dll", TopFiles).Any(f => !IsHarmlessDll(f.Name));
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return false;
        }
    }

    /// <summary>The original RPG Maker engine (RGSS102E.dll, RGSS104E.dll, RGSS301.dll...).</summary>
    private static readonly Regex RgssDll = new(@"^RGSS\d{3}[A-Z]?\.dll$", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);

    /// <summary>The Ruby 2 or later that a modern mkxp-z build brings (x64-msvcrt-ruby310.dll...).</summary>
    private static readonly Regex ModernRubyDll = new(@"ruby[2-9]\d*\.dll$", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);

    /// <summary>
    /// True when the game was made for Ruby 1.8 / 1.9 (the original RPG Maker engine, or an early mkxp built with that
    /// Ruby): it ships an RGSS DLL (top level or <c>System\</c>) and no Ruby 2+ DLL. Such scripts need mkxp-z's syntax
    /// transform (old syntax like <c>when 0:</c>), which mkxp-z otherwise turns off for any game with an mkxp.json.
    /// Games made for modern mkxp-z (Reborn, Rejuvenation: x64-msvcrt-ruby310.dll) are left as they are.
    /// </summary>
    /// <param name="folder">Game folder.</param>
    /// <returns>Whether clients should turn on the syntax transform.</returns>
    public static bool OldRuby(string folder)
    {
        try
        {
            var dir = new DirectoryInfo(folder);
            var dlls = dir.EnumerateFiles("*.dll", TopFiles).Select(f => f.Name)
                .Concat(dir.EnumerateDirectories("System", TopFiles).SelectMany(d => d.EnumerateFiles("*.dll", TopFiles)).Select(f => f.Name))
                .ToList();
            return dlls.Any(RgssDll.IsMatch) && !dlls.Any(ModernRubyDll.IsMatch);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return false;
        }
    }

    /// <summary>True for a DLL name on the known-harmless list.</summary>
    /// <param name="name">File name.</param>
    /// <returns>Whether it is harmless.</returns>
    public static bool IsHarmlessDll(string name) => HarmlessDlls.Any(r => r.IsMatch(name));

    /// <summary>
    /// Every file of the game that may be sent, sorted by path (ordinal). Left out: <see cref="IsLeftOut"/> names (and
    /// everything inside a left-out folder), links/junctions (never followed), anything deeper than <see cref="MaxDepth"/>.
    /// </summary>
    /// <param name="folder">Game folder.</param>
    /// <returns>The files.</returns>
    /// <exception cref="InvalidDataException">More than <see cref="MaxFiles"/> files.</exception>
    public static List<RpgMakerFile> ListFiles(string folder)
    {
        var root = new DirectoryInfo(Trim(folder));
        var result = new List<RpgMakerFile>();
        var pending = new Stack<(DirectoryInfo Dir, string Prefix, int Depth)>();
        pending.Push((root, string.Empty, 0));
        while (pending.Count > 0)
        {
            var (dir, prefix, depth) = pending.Pop();
            foreach (var entry in dir.EnumerateFileSystemInfos("*", AllEntries))
            {
                if (IsLink(entry) || IsLeftOut(entry.Name, entry is FileInfo))
                {
                    continue;
                }

                var rel = prefix + entry.Name;
                if (entry is FileInfo f)
                {
                    if (result.Count >= MaxFiles)
                    {
                        throw new InvalidDataException("Too many files to be a game.");
                    }

                    result.Add(new RpgMakerFile(rel, f.FullName, f.Length, f.LastWriteTimeUtc.Ticks));
                }
                else if (entry is DirectoryInfo d && depth + 1 < MaxDepth)
                {
                    pending.Push((d, rel + "/", depth + 1));
                }
            }
        }

        result.Sort((a, b) => string.CompareOrdinal(a.Path, b.Path));
        return result;
    }

    /// <summary>
    /// The file on disk for a requested path, only if it is one <see cref="ListFiles"/> would list: relative, <c>/</c>
    /// separators, no empty, <c>.</c> or <c>..</c> parts, no left-out names, every folder on the way and the file itself a
    /// real one (no links), inside the game folder. Anything else is null (never served).
    /// </summary>
    /// <param name="folder">Game folder.</param>
    /// <param name="path">Requested path, e.g. <c>Data/Map001.rxdata</c>.</param>
    /// <returns>The file, or null.</returns>
    public static FileInfo? Resolve(string folder, string? path)
    {
        if (string.IsNullOrEmpty(path) || path.Length > 1024 || path[0] == '/' || Path.IsPathRooted(path))
        {
            return null;
        }

        // IsLeftOut also refuses empty, "." and ".." parts (empty, or starting with a dot).
        var parts = path.Split('/');
        if (parts.Length > MaxDepth || parts[..^1].Any(p => IsLeftOut(p, isFile: false)) || IsLeftOut(parts[^1], isFile: true))
        {
            return null;
        }

        try
        {
            var root = new DirectoryInfo(Trim(folder));
            var current = root.FullName;
            foreach (var part in parts[..^1])
            {
                var dir = new DirectoryInfo(Path.Combine(current, part));
                if (!dir.Exists || IsLink(dir))
                {
                    return null;
                }

                current = dir.FullName;
            }

            var file = new FileInfo(Path.Combine(current, parts[^1]));
            return file.Exists && !IsLink(file) && RomScanner.IsInside(file.FullName, root.FullName) ? file : null;
        }
        catch (Exception ex) when (IsFileSystemError(ex))
        {
            return null;
        }
    }

    /// <summary>True for a symbolic link, junction or other reparse point (never followed).</summary>
    /// <param name="info">File or folder.</param>
    /// <returns>Whether it is a link.</returns>
    /// <remarks>.NET marks symbolic links with <see cref="FileAttributes.ReparsePoint"/> on every system, so the
    /// attributes (already read while listing) are enough: no extra call per file.</remarks>
    internal static bool IsLink(FileSystemInfo info) => (info.Attributes & FileAttributes.ReparsePoint) != 0;

    private static bool IsFileSystemError(Exception ex) =>
        ex is IOException or UnauthorizedAccessException or ArgumentException or NotSupportedException or System.Security.SecurityException;

    private static bool HasExtension(string name, params string[] extensions)
    {
        var ext = Path.GetExtension(name);
        return extensions.Any(e => ext.Equals(e, StringComparison.OrdinalIgnoreCase));
    }

    private static string Trim(string path) => path.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);

    private static string Decode(ReadOnlySpan<byte> data)
    {
        if (data.StartsWith((ReadOnlySpan<byte>)[0xEF, 0xBB, 0xBF]))
        {
            data = data[3..];
        }

        try
        {
            return Utf8Strict.GetString(data);
        }
        catch (DecoderFallbackException)
        {
            // Not UTF-8: an older Windows game, Japanese (Shift-JIS) or Western (Windows-1252).
        }

        var provider = CodePagesEncodingProvider.Instance;
        try
        {
            var sjis = provider.GetEncoding(932, EncoderFallback.ExceptionFallback, DecoderFallback.ExceptionFallback);
            if (sjis is not null)
            {
                var text = sjis.GetString(data);

                // Only taken when it reads as Japanese (kana): Windows-1252 accents (é, ü) often happen to be valid
                // Shift-JIS byte pairs too, which would turn "Pokémon" into a kanji.
                if (text.Any(c => c is >= '぀' and <= 'ヿ'))
                {
                    return text;
                }
            }
        }
        catch (DecoderFallbackException)
        {
            // Not Shift-JIS.
        }

        return (provider.GetEncoding(1252) ?? Encoding.Latin1).GetString(data);
    }

    [GeneratedRegex(@"^(CON|PRN|AUX|NUL|COM[0-9¹²³]|LPT[0-9¹²³])(\..*)?$", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant)]
    private static partial Regex ReservedNameRegex();

    private static Regex Glob(string pattern) =>
        new("^" + Regex.Escape(pattern).Replace(@"\*", ".*", StringComparison.Ordinal) + "$", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
}
