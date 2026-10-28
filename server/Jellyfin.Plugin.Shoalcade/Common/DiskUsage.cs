using System;
using System.IO;
using System.IO.Enumeration;

namespace Jellyfin.Plugin.Shoalcade.Common;

/// <summary>
/// Folder size walks for the quota checks and the storage report. Saving and tidying run alongside them, so a file or
/// folder that vanishes or can't be read mid-scan (Windows reports a folder being deleted as "access denied") counts as
/// 0 instead of failing the upload. Sizes come from the folder listing itself, so no file is opened.
/// </summary>
internal static class DiskUsage
{
    /// <summary>Every file in every subfolder, hidden ones included; unreadable or vanished folders are skipped.</summary>
    public static readonly EnumerationOptions AllFiles = new()
    {
        RecurseSubdirectories = true,
        IgnoreInaccessible = true,
        AttributesToSkip = 0,
        MatchType = MatchType.Simple,
    };

    /// <summary>Total bytes of every file under a folder; 0 if the folder is missing or can't be read.</summary>
    /// <param name="dir">Folder.</param>
    /// <returns>Bytes.</returns>
    public static long Bytes(string dir)
    {
        try
        {
            long total = 0;
            foreach (var length in new FileSystemEnumerable<long>(dir, (ref FileSystemEntry e) => e.Length, AllFiles)
            {
                ShouldIncludePredicate = (ref FileSystemEntry e) => !e.IsDirectory,
            })
            {
                total += length;
            }

            return total;
        }
        catch (Exception ex) when (ex is DirectoryNotFoundException or UnauthorizedAccessException)
        {
            return 0; // the folder itself is gone (or going): nothing in it to count
        }
    }
}
