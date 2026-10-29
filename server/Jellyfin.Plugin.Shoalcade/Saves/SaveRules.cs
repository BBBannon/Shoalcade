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

        if (!allowBlank && IsBlank(data))
        {
            errorCode = "blank";
            return "That save is blank (every byte is the same), so the game has probably never saved.";
        }

        // RPG Maker (D147): a readable zip of the save folder with safe names; one with no saved data is never stored.
        if (console.FolderGame)
        {
            if (RpgMakerSave.Check(data, out var blank) is string bad)
            {
                errorCode = "format";
                return bad;
            }

            if (!allowBlank && blank)
            {
                errorCode = "blank";
                return "That save folder is empty (nothing is saved in it), so the game has probably never saved.";
            }
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
