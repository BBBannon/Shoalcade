using System;
using System.IO;
using System.IO.Compression;
using System.Linq;

namespace Jellyfin.Plugin.Shoalcade.Saves;

/// <summary>
/// An RPG Maker save (D147): the mkxp-z core's whole <c>mkxp-z/Saves/</c> folder as one zip, entries like
/// <c>Pokemon Reborn-/Game.rxdata</c> (same zip rules as PSP: sorted entries, fixed times, made by the app/browser).
/// Only the zip's list of entries is read here (nothing is unpacked on the server).
/// </summary>
internal static class RpgMakerSave
{
    /// <summary>Most entries in one save zip (a real one has a handful).</summary>
    public const int MaxEntries = 10_000;

    /// <summary>
    /// Why a save zip is refused, or null if it is fine. <paramref name="blank"/> is true for a zip that holds no saved
    /// data at all (no files, only empty files, or only logs): the game has never saved, so it is never stored.
    /// </summary>
    /// <param name="data">The zip bytes.</param>
    /// <param name="blank">Set when the zip is empty or untouched.</param>
    /// <returns>Null if fine, else the reason.</returns>
    public static string? Check(ReadOnlySpan<byte> data, out bool blank)
    {
        blank = false;
        try
        {
            using var ms = new MemoryStream(data.ToArray(), writable: false);
            using var zip = new ZipArchive(ms, ZipArchiveMode.Read);
            if (zip.Entries.Count > MaxEntries)
            {
                return "That save zip has too many files to be an RPG Maker save folder.";
            }

            if (zip.Entries.FirstOrDefault(e => !IsSafeName(e.FullName)) is ZipArchiveEntry bad)
            {
                return "That save zip has a file name that isn't allowed (" + bad.FullName + ").";
            }

            // Saved data = a file (not a folder entry, which ends in '/') with bytes that the clients don't leave out.
            blank = !zip.Entries.Any(e => !e.FullName.EndsWith('/') && e.Length > 0 && !IsLeftOut(e.FullName));
            return null;
        }
        catch (InvalidDataException)
        {
            return "That save isn't a readable .zip of the game's save folder.";
        }
    }

    /// <summary>True for files the clients leave out of the zip (they aren't saves): <c>*.log</c>, <c>errorlog.txt</c>.</summary>
    /// <param name="path">Entry path.</param>
    /// <returns>Whether it is left out.</returns>
    public static bool IsLeftOut(string path)
    {
        var file = path[(path.LastIndexOf('/') + 1)..];
        return file.EndsWith(".log", StringComparison.OrdinalIgnoreCase) || file.Equals("errorlog.txt", StringComparison.OrdinalIgnoreCase);
    }

    /// <summary>True for a relative entry name with <c>/</c> separators and no <c>..</c>, drive or root, so unpacking it can
    /// never write outside the save folder on any device.</summary>
    /// <param name="name">Entry full name.</param>
    /// <returns>Whether it is safe.</returns>
    public static bool IsSafeName(string name)
    {
        if (string.IsNullOrEmpty(name) || name.Length > 512 || name[0] == '/' || name.Contains('\\', StringComparison.Ordinal) || name.Contains(':', StringComparison.Ordinal)
            || name.Any(char.IsControl))
        {
            return false;
        }

        return !name.TrimEnd('/').Split('/').Any(p => p is "" or "." or "..");
    }
}
