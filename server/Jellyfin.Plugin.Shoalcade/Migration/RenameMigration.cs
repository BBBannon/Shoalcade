using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text.Json;
using Jellyfin.Plugin.Shoalcade.Common;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Shoalcade.Migration;

/// <summary>
/// The plugin was called JellyGames before s0.2 dev 2 (D124). These are the old names that data, settings and items
/// may still carry. Only the migration code reads them.
/// </summary>
public static class LegacyNames
{
    /// <summary>The old data folder under Jellyfin's data path.</summary>
    public const string DataFolder = "jellygames";

    /// <summary>The old settings file in <c>plugins/configurations</c> (named after the old assembly).</summary>
    public const string ConfigFile = "Jellyfin.Plugin.JellyGames.xml";

    /// <summary>The old item tag our scanner put on every game.</summary>
    public const string Tag = "JellyGames";

    /// <summary>The old address of the browser pages (also the old Jellyfin menu link).</summary>
    public const string Route = "jellygames";

    /// <summary>The old Jellyfin menu link name.</summary>
    public const string MenuName = "JellyGames";

    /// <summary>Old provider names (as stored in library settings) and the new ones.</summary>
    public static readonly IReadOnlyDictionary<string, string> Providers = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase)
    {
        ["JellyGames box art (libretro)"] = Scanner.BoxArtProvider.ProviderName,
        ["JellyGames ROM info"] = Scanner.RomInfoProvider.ProviderName,
    };
}

/// <summary>What the data folder step did.</summary>
public enum DataMigrationOutcome
{
    /// <summary>No old folder: a fresh install, nothing to carry over.</summary>
    NothingToCopy,

    /// <summary>The new folder already exists (copied on an earlier start, or made fresh): left alone.</summary>
    AlreadyDone,

    /// <summary>Old data copied into the new folder and checked.</summary>
    Copied,

    /// <summary>The copy failed or didn't check out; the old folder is used for this start and the copy is tried again next start.</summary>
    FailedUsingOld,
}

/// <summary>What the settings file step did.</summary>
public enum ConfigMigrationOutcome
{
    /// <summary>No old settings file, or the new one already exists: nothing done.</summary>
    NothingToCopy,

    /// <summary>The old settings file was copied to the new name.</summary>
    Copied,

    /// <summary>An old settings file exists but couldn't be copied: the plugin runs on default settings this start.</summary>
    Failed,
}

/// <summary>Result of the data folder step.</summary>
/// <param name="Outcome">What happened.</param>
/// <param name="Folder">The folder the plugin must use for this start.</param>
/// <param name="Files">Files copied (and checked).</param>
/// <param name="Bytes">Bytes copied.</param>
/// <param name="Error">Why it failed, if it did.</param>
public sealed record DataMigrationResult(DataMigrationOutcome Outcome, string Folder, int Files, long Bytes, string? Error);

/// <summary>
/// Carries JellyGames data over to Shoalcade (D124). <b>Copy, never move:</b> the old folder is never changed or
/// deleted by this version. The copy goes into a side folder first, every file is checked (size + SHA-256), a note is
/// written, and only then is the side folder renamed into place in one step. So a crash or power cut part-way leaves
/// no new folder and the next start simply copies again. Once the new folder exists it is never copied over again.
/// </summary>
public static class RenameMigration
{
    /// <summary>The note written into the new folder after a copy.</summary>
    public const string NoteFile = "migrated-from-jellygames.json";

    /// <summary>The side folder a copy is made in before it is renamed into place.</summary>
    public const string StagingSuffix = ".copying";

    /// <summary>Half-written temporary files of the old version (AtomicFile). Never copied: they are unfinished writes.</summary>
    private const string OldTempPrefix = ".jg-tmp-";

    private static readonly JsonSerializerOptions NoteJson = new() { WriteIndented = true };

    /// <summary>The folders holding what people made: saves, snapshots, settings.</summary>
    private static readonly string[] PreciousFolders = ["saves", "states", "prefs"];

    /// <summary>Copies <c>{dataPath}/jellygames</c> to <c>{dataPath}/{newFolder}</c> if needed. Never throws.</summary>
    /// <param name="dataPath">Jellyfin's data path.</param>
    /// <param name="newFolder">The new folder's name (<c>shoalcade</c>).</param>
    /// <param name="logger">Logger.</param>
    /// <param name="now">Time for the note.</param>
    /// <returns>What happened and which folder to use.</returns>
    public static DataMigrationResult MigrateDataFolder(string dataPath, string newFolder, ILogger logger, DateTimeOffset now)
    {
        ArgumentNullException.ThrowIfNull(logger);
        var oldDir = Path.Combine(dataPath, LegacyNames.DataFolder);
        var newDir = Path.Combine(dataPath, newFolder);
        var staging = newDir + StagingSuffix;

        if (Directory.Exists(newDir))
        {
            return new DataMigrationResult(DataMigrationOutcome.AlreadyDone, newDir, 0, 0, null);
        }

        if (!Directory.Exists(oldDir))
        {
            return new DataMigrationResult(DataMigrationOutcome.NothingToCopy, newDir, 0, 0, null);
        }

        try
        {
            if (Directory.Exists(staging))
            {
                // Left by a start that stopped part-way: never trusted, made again from the old folder.
                logger.LogInformation("Removing an unfinished copy of the JellyGames data at {Staging} and copying again", staging);
                Directory.Delete(staging, true);
            }

            var files = SourceFiles(oldDir).ToList();
            long bytes = 0;
            Directory.CreateDirectory(staging);
            foreach (var d in Directory.EnumerateDirectories(oldDir, "*", SearchOption.AllDirectories))
            {
                Directory.CreateDirectory(Path.Combine(staging, Path.GetRelativePath(oldDir, d)));
            }

            foreach (var f in files)
            {
                var to = Path.Combine(staging, Path.GetRelativePath(oldDir, f));
                File.Copy(f, to, false);
                File.SetLastWriteTimeUtc(to, File.GetLastWriteTimeUtc(f));
                bytes += new FileInfo(to).Length;
            }

            // Check every file against the old one before the copy is used.
            foreach (var f in files)
            {
                var to = Path.Combine(staging, Path.GetRelativePath(oldDir, f));
                if (!File.Exists(to) || new FileInfo(to).Length != new FileInfo(f).Length || !SameBytes(f, to))
                {
                    throw new IOException("Copied file doesn't match the original: " + Path.GetRelativePath(oldDir, f));
                }
            }

            var note = new Dictionary<string, object>
            {
                ["from"] = oldDir,
                ["copied"] = now.ToString("o", CultureInfo.InvariantCulture),
                ["files"] = files.Count,
                ["bytes"] = bytes,
                ["note"] = "Copied from the JellyGames data folder when the plugin was renamed to Shoalcade (D124). The old folder was left as it was; delete it yourself once you're happy.",
            };
            File.WriteAllBytes(Path.Combine(staging, NoteFile), JsonSerializer.SerializeToUtf8Bytes(note, NoteJson));

            // One step: either the whole checked copy is in place, or (crash before this) there is no new folder at all.
            Directory.Move(staging, newDir);
            logger.LogInformation(
                "Shoalcade: copied the JellyGames data ({Files} files, {Bytes} bytes) from {Old} to {New} and checked every file. The old folder was left untouched.",
                files.Count,
                bytes,
                oldDir,
                newDir);
            return new DataMigrationResult(DataMigrationOutcome.Copied, newDir, files.Count, bytes, null);
        }
#pragma warning disable CA1031 // never stop Jellyfin starting; fall back to the old folder (saves stay reachable)
        catch (Exception ex)
#pragma warning restore CA1031
        {
            logger.LogError(
                ex,
                "Shoalcade: could not copy the JellyGames data from {Old} to {New}. Using the old folder for now (nothing lost); it will be tried again at the next start.",
                oldDir,
                newDir);
            TryDelete(staging, logger);
            return new DataMigrationResult(DataMigrationOutcome.FailedUsingOld, oldDir, 0, 0, ex.Message);
        }
    }

    /// <summary>Copies the old settings file to the new name if the new one doesn't exist yet. Never throws.</summary>
    /// <param name="newConfigPath">Full path of the new settings file.</param>
    /// <param name="error">Why the copy failed (only for <see cref="ConfigMigrationOutcome.Failed"/>).</param>
    /// <returns>What happened. On <see cref="ConfigMigrationOutcome.Failed"/> the plugin runs on default settings, so
    /// <see cref="CarryOverFromJellyGamesTask"/> logs an error and <see cref="ShoalcadeServices.SaveOptions"/> deletes
    /// no old save versions for that start (G6 dev 2 M1: the admin's keep rule may be longer than the default).</returns>
    public static ConfigMigrationOutcome MigrateConfigFile(string newConfigPath, out string? error)
    {
        error = null;
        try
        {
            var dir = Path.GetDirectoryName(newConfigPath);
            if (string.IsNullOrEmpty(dir) || File.Exists(newConfigPath))
            {
                return ConfigMigrationOutcome.NothingToCopy;
            }

            var old = Path.Combine(dir, LegacyNames.ConfigFile);
            if (!File.Exists(old) || string.Equals(Path.GetFullPath(old), Path.GetFullPath(newConfigPath), StringComparison.OrdinalIgnoreCase))
            {
                return ConfigMigrationOutcome.NothingToCopy;
            }

            // Copy to a side file, then rename: a half-written settings file would be read as defaults.
            var tmp = newConfigPath + StagingSuffix;
            File.Copy(old, tmp, true);
            File.Move(tmp, newConfigPath, false);
            return ConfigMigrationOutcome.Copied;
        }
#pragma warning disable CA1031 // settings fall back to defaults rather than stopping Jellyfin; reported by the caller
        catch (Exception ex)
#pragma warning restore CA1031
        {
            error = ex.Message;
            return ConfigMigrationOutcome.Failed;
        }
    }

    /// <summary>
    /// Save, snapshot and settings files written to the old <c>jellygames</c> folder after it was copied (G6 dev 2 M2):
    /// at the first full start an old JellyGames copy can still run next to Shoalcade and write there, and Shoalcade
    /// never copies again, so those files would be kept but unseen. Report only; nothing is moved or deleted. Never throws.
    /// </summary>
    /// <param name="newDir">Shoalcade's data folder (holding the copy note).</param>
    /// <returns>Paths relative to the old folder, oldest first.</returns>
    public static IReadOnlyList<string> WrittenToOldFolderSinceCopy(string newDir)
    {
        try
        {
            var note = Path.Combine(newDir, NoteFile);
            var parent = Path.GetDirectoryName(Path.TrimEndingDirectorySeparator(newDir));
            if (!File.Exists(note) || string.IsNullOrEmpty(parent))
            {
                return [];
            }

            var oldDir = Path.Combine(parent, LegacyNames.DataFolder);
            using var doc = JsonDocument.Parse(File.ReadAllBytes(note));
            if (!Directory.Exists(oldDir)
                || !doc.RootElement.TryGetProperty("copied", out var c)
                || !DateTimeOffset.TryParse(c.GetString(), CultureInfo.InvariantCulture, DateTimeStyles.None, out var copied))
            {
                return [];
            }

            var since = copied.UtcDateTime.AddSeconds(2); // the copy keeps each file's time, so copied files are older
            return [.. PreciousFolders
                .Select(d => Path.Combine(oldDir, d))
                .Where(Directory.Exists)
                .SelectMany(d => Directory.EnumerateFiles(d, "*", SearchOption.AllDirectories))
                .Where(f => !IsTempFile(f))
                .Select(f => (Path: f, Time: File.GetLastWriteTimeUtc(f)))
                .Where(f => f.Time > since)
                .OrderBy(f => f.Time)
                .Select(f => Path.GetRelativePath(oldDir, f.Path).Replace('\\', '/'))];
        }
#pragma warning disable CA1031 // a report only: never stop the start-up task
        catch (Exception)
#pragma warning restore CA1031
        {
            return [];
        }
    }

    /// <summary>Replaces old provider names in a list (keeps order, drops a duplicate the rename would make).</summary>
    /// <param name="names">Names, or null.</param>
    /// <returns>The new list, or null if nothing changed.</returns>
    public static string[]? RenameProviders(IEnumerable<string>? names)
    {
        if (names is null)
        {
            return null;
        }

        var list = names.ToList();
        if (!list.Any(n => n is not null && LegacyNames.Providers.ContainsKey(n)))
        {
            return null;
        }

        var result = new List<string>();
        foreach (var n in list)
        {
            var v = n is not null && LegacyNames.Providers.TryGetValue(n, out var renamed) ? renamed : n;
            if (v is not null && !result.Contains(v, StringComparer.OrdinalIgnoreCase))
            {
                result.Add(v);
            }
        }

        return [.. result];
    }

    // Half-written files of either version: the old ".jg-tmp-", and ours (".sc-tmp-"), which a start that fell back to
    // the old folder writes there (G6 dev 2 L7).
    private static bool IsTempFile(string path)
    {
        var name = Path.GetFileName(path);
        return name.StartsWith(OldTempPrefix, StringComparison.Ordinal) || name.StartsWith(AtomicFile.TempPrefix, StringComparison.Ordinal);
    }

    private static IEnumerable<string> SourceFiles(string oldDir) =>
        Directory.EnumerateFiles(oldDir, "*", SearchOption.AllDirectories)
            .Where(f => !IsTempFile(f));

    private static bool SameBytes(string a, string b)
    {
        using var fa = File.OpenRead(a);
        using var fb = File.OpenRead(b);
        return SHA256.HashData(fa).AsSpan().SequenceEqual(SHA256.HashData(fb));
    }

    private static void TryDelete(string dir, ILogger logger)
    {
        try
        {
            if (Directory.Exists(dir))
            {
                Directory.Delete(dir, true);
            }
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            logger.LogInformation("Could not remove {Dir} ({Message}); it is removed at the next start", dir, ex.Message);
        }
    }
}
