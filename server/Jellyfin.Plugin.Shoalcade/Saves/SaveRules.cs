using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using Jellyfin.Plugin.Shoalcade.Consoles;

namespace Jellyfin.Plugin.Shoalcade.Saves;

/// <summary>Checks an uploaded save before it is stored: obvious mistakes are refused, odd ones get a warning (D35).</summary>
internal static class SaveRules
{
    /// <summary>Smallest save accepted (same as JellyEMU).</summary>
    public const int MinBytes = 8;

    /// <summary>Checks a save.</summary>
    /// <param name="console">Console of the game.</param>
    /// <param name="data">The save bytes.</param>
    /// <param name="allowBlank">Allow a save that is one repeated byte (normally a game that never saved).</param>
    /// <param name="warnings">Warnings for the person.</param>
    /// <param name="errorCode">Machine-readable reason if refused.</param>
    /// <returns>Null if fine, else the reason it is refused.</returns>
    public static string? Check(GameConsole console, ReadOnlySpan<byte> data, bool allowBlank, List<string> warnings, out string? errorCode)
    {
        errorCode = null;
        if (data.Length < MinBytes)
        {
            errorCode = "size";
            return "That save is empty or too small to be a real save.";
        }

        if (data.Length > console.MaxSaveBytes)
        {
            errorCode = "size";
            return string.Format(
                CultureInfo.InvariantCulture,
                "That file is too big for a {0} save ({1:N0} bytes; the most is {2:N0}).",
                console.DisplayName,
                data.Length,
                console.MaxSaveBytes);
        }

        if (console.SaveExtension == "zip" && !(data[0] == 0x50 && data[1] == 0x4B && data[2] == 0x03 && data[3] == 0x04))
        {
            errorCode = "format";
            return "A " + console.DisplayName + " save must be a .zip of its save folder.";
        }

        if (console.Key is "gc" or "wii" or "ps2")
        {
            var problem = CheckFolderZip(console, data, allowBlank, out errorCode);
            if (problem is not null)
            {
                return problem;
            }
        }

        if (!allowBlank && IsBlank(data))
        {
            errorCode = "blank";
            return "That save is blank (every byte is the same), so the game has probably never saved.";
        }

        if (!allowBlank && console.Key == "psx" && PsxCard.IsEmptyCard(data))
        {
            errorCode = "blank";
            return "That memory card is empty (nothing is saved on it), so the game has probably never saved.";
        }

        if (!allowBlank && console.Key == "n64" && N64Save.IsUntouched(data))
        {
            errorCode = "blank";
            return "That save is untouched (it is just what the emulator makes before a game saves), so the game has probably never saved.";
        }

        if (console.ExactSaveSizes && !console.IsTypicalSize(data.Length))
        {
            errorCode = "size";
            return string.Format(
                CultureInfo.InvariantCulture,
                "That isn't a {0} save: {1:N0} bytes isn't a size a {0} save can be ({2}).",
                console.DisplayName,
                data.Length,
                string.Join(", ", console.TypicalSaveSizes.Select(SizeText)));
        }

        if (!console.IsTypicalSize(data.Length))
        {
            warnings.Add(string.Format(
                CultureInfo.InvariantCulture,
                "Unusual size for a {0} save ({1:N0} bytes). Stored anyway; check the game loads it.",
                console.DisplayName,
                data.Length));
        }

        return null;
    }

    /// <summary>A PS2 memory card: 8 MB of pages with their error-correction bytes (1024 x 528 x 16), the same file in
    /// LRPS2 and PCEE2.</summary>
    public const long Ps2CardSize = 8_650_752;

    /// <summary>
    /// GameCube / Wii / PS2 (D218): the zip the Windows app makes of the game's save files (Storage S1) must hold only
    /// what that console's save is, so a wrong or damaged upload can never become the save another PC restores:
    /// GameCube = Dolphin's User/GC card folders (with or without <c>SRAM.raw</c>, the console's settings: the app now
    /// leaves it out, older builds put it in; <c>SRAM.raw</c> alone is no save),
    /// Wii = only <c>&lt;hi&gt;/&lt;lo&gt;/data/…</c> under User/Wii/title, PS2 = exactly one memory card
    /// <c>&lt;game&gt;.ps2</c> at the top, 8,650,752 bytes, not blank. No entry may climb out of its folder.
    /// </summary>
    private static string? CheckFolderZip(GameConsole console, ReadOnlySpan<byte> data, bool allowBlank, out string? errorCode)
    {
        errorCode = "format";
        var files = new List<(string Name, long Length, System.IO.Compression.ZipArchiveEntry Entry)>();
        try
        {
            using var ms = new System.IO.MemoryStream(data.ToArray(), writable: false);
            using var zip = new System.IO.Compression.ZipArchive(ms, System.IO.Compression.ZipArchiveMode.Read);
            foreach (var e in zip.Entries)
            {
                var name = e.FullName.Replace('\\', '/');
                if (name.EndsWith('/'))
                {
                    continue; // a folder entry
                }

                if (name.StartsWith('/') || name.Contains(':', StringComparison.Ordinal) || name.Split('/').Any(p => p is ".." or "."))
                {
                    return "That " + console.DisplayName + " save has a file outside its save folder (" + name + ").";
                }

                files.Add((name, e.Length, e));
            }

            if (files.Count == 0)
            {
                errorCode = "blank";
                return "That " + console.DisplayName + " save is an empty zip, so the game has probably never saved.";
            }

            switch (console.Key)
            {
                case "ps2":
                    if (files.Count != 1 || files[0].Name.Contains('/', StringComparison.Ordinal)
                        || !files[0].Name.EndsWith(".ps2", StringComparison.OrdinalIgnoreCase))
                    {
                        return "A PS2 save must be a .zip of the game's one memory card (a .ps2 file).";
                    }

                    if (files[0].Length != Ps2CardSize)
                    {
                        errorCode = "size";
                        return string.Format(
                            CultureInfo.InvariantCulture,
                            "That isn't a PS2 memory card: it is {0:N0} bytes (a card is {1:N0}).",
                            files[0].Length,
                            Ps2CardSize);
                    }

                    if (!allowBlank)
                    {
                        using var card = files[0].Entry.Open();
                        var bytes = new byte[Ps2CardSize];
                        card.ReadExactly(bytes);
                        if (IsBlank(bytes))
                        {
                            errorCode = "blank";
                            return "That PS2 memory card is empty (never formatted), so the game has probably never saved.";
                        }
                    }

                    break;
                case "wii":
                    var notData = files.FirstOrDefault(f => !WiiData.IsMatch(f.Name));
                    if (notData.Name is not null)
                    {
                        return "A Wii save holds only the games' save data (title/…/data); this zip has " + notData.Name + ".";
                    }

                    break;
                case "gc":
                    if (files.All(f => string.Equals(f.Name, "SRAM.raw", StringComparison.OrdinalIgnoreCase)))
                    {
                        errorCode = "blank";
                        return "That GameCube save has only the console's settings (SRAM.raw), no memory card files.";
                    }

                    break;
            }
        }
        catch (Exception ex) when (ex is System.IO.InvalidDataException or System.IO.IOException)
        {
            // Not a zip / bad data (InvalidDataException is not an IOException), or an entry shorter than it says
            // (EndOfStreamException, review #7).
            return "That " + console.DisplayName + " save isn't a readable .zip.";
        }

        errorCode = null;
        return null;
    }

    private static readonly System.Text.RegularExpressions.Regex WiiData = new("^[^/]+/[^/]+/data/.+$", System.Text.RegularExpressions.RegexOptions.CultureInvariant);

    private static string SizeText(long n) => n >= 1024 * 1024 ? (n / (1024 * 1024)).ToString(CultureInfo.InvariantCulture) + " MB"
        : n >= 1024 ? (n / 1024).ToString(CultureInfo.InvariantCulture) + " KB" : n.ToString(CultureInfo.InvariantCulture) + " bytes";

    /// <summary>True if every byte is the same (an erased chip: all 0x00 or all 0xFF).</summary>
    /// <param name="data">Bytes.</param>
    /// <returns>Whether the data is blank.</returns>
    public static bool IsBlank(ReadOnlySpan<byte> data)
    {
        return data.Length > 0 && !data[1..].ContainsAnyExcept(data[0]);
    }
}
