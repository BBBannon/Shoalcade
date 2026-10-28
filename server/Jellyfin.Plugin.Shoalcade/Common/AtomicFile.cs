using System;
using System.IO;
using System.Threading;
using System.Threading.Tasks;

namespace Jellyfin.Plugin.Shoalcade.Common;

/// <summary>
/// Crash-safe file writes: data goes to a temp file in the same folder, is flushed to disk,
/// then swapped in with a rename. A crash at any point leaves either the old file or the new one, never half of one.
/// </summary>
internal static class AtomicFile
{
    /// <summary>Prefix of temp files. Anything starting with this is unfinished and safe to delete.</summary>
    public const string TempPrefix = ".sc-tmp-";

    /// <summary>
    /// Writes <paramref name="data"/> to <paramref name="path"/> atomically.
    /// </summary>
    /// <param name="path">Final file path.</param>
    /// <param name="data">Bytes to write.</param>
    /// <param name="overwrite">False = fail if the file already exists (used for save versions, which are never replaced).</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>A task.</returns>
    public static async Task WriteAsync(string path, ReadOnlyMemory<byte> data, bool overwrite, CancellationToken cancellationToken)
    {
        var dir = Path.GetDirectoryName(path) ?? throw new ArgumentException("Path has no folder.", nameof(path));
        Directory.CreateDirectory(dir);
        var tmp = Path.Combine(dir, TempPrefix + Guid.NewGuid().ToString("N"));
        try
        {
            var fs = new FileStream(tmp, FileMode.CreateNew, FileAccess.Write, FileShare.None, 81920, FileOptions.Asynchronous);
            await using (fs.ConfigureAwait(false))
            {
                await fs.WriteAsync(data, cancellationToken).ConfigureAwait(false);
                await fs.FlushAsync(cancellationToken).ConfigureAwait(false);
                fs.Flush(true);
            }

            if (!overwrite && File.Exists(path))
            {
                throw new IOException("Refusing to replace existing file " + Path.GetFileName(path));
            }

            await MoveWithRetryAsync(tmp, path, overwrite, cancellationToken).ConfigureAwait(false);
        }
        finally
        {
            if (File.Exists(tmp))
            {
                TryDelete(tmp);
            }
        }
    }

    /// <summary>Moves an already-written, flushed file into place, replacing <paramref name="path"/> (same disk only).</summary>
    /// <param name="source">The finished file.</param>
    /// <param name="path">Final path.</param>
    /// <param name="cancellationToken">Cancellation token.</param>
    /// <returns>A task.</returns>
    public static Task MoveInAsync(string source, string path, CancellationToken cancellationToken) =>
        MoveWithRetryAsync(source, path, true, cancellationToken);

    /// <summary>
    /// Swaps the file in. On Windows a virus scanner or indexer can hold a just-written file for a moment
    /// ("access denied"), so try a few times before giving up. The old file is untouched until the swap succeeds.
    /// </summary>
    private static async Task MoveWithRetryAsync(string tmp, string path, bool overwrite, CancellationToken cancellationToken)
    {
        for (var attempt = 1; ; attempt++)
        {
            try
            {
                File.Move(tmp, path, overwrite);
                return;
            }
            catch (Exception ex) when (attempt < 8 && (ex is UnauthorizedAccessException || (ex is IOException && (overwrite || !File.Exists(path)))))
            {
                await Task.Delay(25 * attempt, cancellationToken).ConfigureAwait(false);
            }
        }
    }

    /// <summary>Removes leftover temp files from an interrupted write.</summary>
    /// <param name="dir">Folder to clean.</param>
    public static void CleanTemps(string dir)
    {
        if (!Directory.Exists(dir))
        {
            return;
        }

        foreach (var f in Directory.EnumerateFiles(dir, TempPrefix + "*"))
        {
            TryDelete(f);
        }
    }

    private static void TryDelete(string path)
    {
        try
        {
            File.Delete(path);
        }
        catch (IOException)
        {
            // Left for the next CleanTemps.
        }
        catch (UnauthorizedAccessException)
        {
            // Left for the next CleanTemps.
        }
    }
}
