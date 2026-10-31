using System;
using System.Globalization;
using System.IO;
using System.Linq;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Shoalcade.Saves;

/// <summary>
/// "Delete all saves" for a removed game first keeps a hidden copy here for <see cref="KeepFor"/> (D168), so the server
/// admin can undo a mistake: <c>{DataRoot}/deleted/{user}/{item}-{yyyyMMdd-HHmmss}/</c> holding <c>saves/</c>,
/// <c>snapshots/</c> and a read-me saying where they go back. Not counted in anyone's storage. Cleared by the
/// "Clear out deleted saves" task once older than <see cref="KeepFor"/>.
/// </summary>
public sealed class DeletedSaves
{
    /// <summary>How long a hidden copy is kept.</summary>
    public static readonly TimeSpan KeepFor = TimeSpan.FromDays(7);

    private const string Stamp = "yyyyMMdd-HHmmss";
    private const int ItemLength = 32; // Guid "N"

    private readonly string _root;
    private readonly TimeProvider _clock;
    private readonly ILogger _logger;

    /// <summary>Initializes a new instance of the <see cref="DeletedSaves"/> class.</summary>
    /// <param name="root">Folder for the hidden copies.</param>
    /// <param name="clock">Clock.</param>
    /// <param name="logger">Logger.</param>
    public DeletedSaves(string root, TimeProvider clock, ILogger logger)
    {
        _root = Path.GetFullPath(root);
        _clock = clock;
        _logger = logger;
    }

    /// <summary>Gets the folder holding the hidden copies.</summary>
    public string Root => _root;

    /// <summary>
    /// Copies a game's save and snapshot folders into a new hidden copy. Throws <see cref="IOException"/> or
    /// <see cref="UnauthorizedAccessException"/> if the copy can't be made in full (nothing is left behind): the caller
    /// then deletes nothing.
    /// </summary>
    /// <param name="user">User id.</param>
    /// <param name="item">Item id.</param>
    /// <param name="gameName">Game name for the read-me, if known.</param>
    /// <param name="savesDir">The game's save folder.</param>
    /// <param name="snapshotsDir">The game's snapshot folder.</param>
    /// <returns>The hidden copy's folder.</returns>
    public string Keep(Guid user, Guid item, string? gameName, string savesDir, string snapshotsDir)
    {
        var at = _clock.GetUtcNow();
        var dest = Path.Combine(_root, user.ToString("N"), item.ToString("N") + "-" + at.ToString(Stamp, CultureInfo.InvariantCulture));
        if (Directory.Exists(dest))
        {
            dest += "-" + Guid.NewGuid().ToString("N")[..6]; // two in the same second: the stamp is still read from its fixed place
        }

        try
        {
            CopyTree(savesDir, Path.Combine(dest, "saves"));
            CopyTree(snapshotsDir, Path.Combine(dest, "snapshots"));
            File.WriteAllText(Path.Combine(dest, "read-me.txt"), string.Join(
                Environment.NewLine,
                "Shoalcade: saves deleted with \"Delete all saves for this game\".",
                "Game: " + (string.IsNullOrWhiteSpace(gameName) ? "(name not known)" : gameName),
                "Deleted: " + at.ToString("dd/MM/yyyy HH:mm 'UTC'", CultureInfo.InvariantCulture),
                "This copy is removed automatically after " + KeepFor.TotalDays.ToString(CultureInfo.InvariantCulture) + " days.",
                string.Empty,
                "To bring them back: stop Jellyfin, then copy",
                "  saves" + Path.DirectorySeparatorChar + "     into  " + savesDir,
                "  snapshots" + Path.DirectorySeparatorChar + " into  " + snapshotsDir,
                "and start Jellyfin again.",
                string.Empty));
            return dest;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            Discard(dest);
            throw;
        }
    }

    /// <summary>Removes a hidden copy that turned out not to be needed (nothing was deleted after all).</summary>
    /// <param name="dir">The folder <see cref="Keep"/> returned.</param>
    public void Discard(string dir)
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
            _logger.LogWarning(ex, "Could not remove the unused copy {Dir}", dir);
        }
    }

    /// <summary>Removes hidden copies older than <see cref="KeepFor"/>.</summary>
    /// <returns>How many were removed.</returns>
    public int Purge()
    {
        if (!Directory.Exists(_root))
        {
            return 0;
        }

        var cutoff = _clock.GetUtcNow() - KeepFor;
        var n = 0;
        foreach (var userDir in Directory.EnumerateDirectories(_root))
        {
            foreach (var dir in Directory.EnumerateDirectories(userDir))
            {
                var name = Path.GetFileName(dir);
                if (name.Length < ItemLength + 1 + Stamp.Length
                    || !DateTimeOffset.TryParseExact(name.AsSpan(ItemLength + 1, Stamp.Length), Stamp, CultureInfo.InvariantCulture, DateTimeStyles.AssumeUniversal, out var at)
                    || at > cutoff)
                {
                    continue;
                }

                try
                {
                    Directory.Delete(dir, true);
                    n++;
                }
                catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
                {
                    _logger.LogWarning(ex, "Could not clear out the old deleted saves {Dir}", dir);
                }
            }

            try
            {
                if (!Directory.EnumerateFileSystemEntries(userDir).Any())
                {
                    Directory.Delete(userDir);
                }
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
            {
                // An empty folder left behind is harmless.
            }
        }

        return n;
    }

    private static void CopyTree(string from, string to)
    {
        Directory.CreateDirectory(to);
        if (!Directory.Exists(from))
        {
            return;
        }

        foreach (var f in Directory.EnumerateFiles(from, "*", SearchOption.AllDirectories))
        {
            var target = Path.Combine(to, Path.GetRelativePath(from, f));
            Directory.CreateDirectory(Path.GetDirectoryName(target)!);
            File.Copy(f, target);
        }
    }
}
