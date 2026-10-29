using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using Jellyfin.Plugin.Shoalcade.Saves;

namespace Jellyfin.Plugin.Shoalcade.Consoles;

/// <summary>Where a console's browser saves go.</summary>
public enum BrowserSaveMode
{
    /// <summary>Not playable in the browser.</summary>
    None,

    /// <summary>Browser shares the one save with the app (proven byte-compatible: D25, DS by D91).</summary>
    Shared,
}

/// <summary>A console Shoalcade knows about. Everything else is not a ROM and is never served.</summary>
public sealed class GameConsole
{
    /// <summary>Clock data mGBA (the GB/GBC core, D81) keeps after the save (checked against mGBA's src/gb/mbc*, 24/10/2026):
    /// MBC3 clock 48 bytes (<c>GBMBCRTCSaveBuffer</c>; mGBA also reads the older 44-byte form), TAMA5 40, HuC-3 136.</summary>
    private static readonly long[] GbFooters = [0, 40, 44, 48, 136];

    /// <summary>DS save sizes melonDS uses (both the app's melonDS DS core and EmulatorJS's melonds core), from
    /// melonDS's <c>src/NDSCart/CartRetail.cpp</c> <c>sramlengths[]</c> (checked 24/10/2026): EEPROM 512 B, 8 KB, 64 KB,
    /// 128 KB; FLASH 256 KB, 512 KB, 1 MB, 8 MB; NAND 16 MB (64 MB NAND is above the 16 MB upload limit). melonDS writes
    /// the raw chip contents, no footer (it pads a short file with 0xFF and cuts a long one, so a wrong size would
    /// quietly damage a save: refused, D91).</summary>
    private static readonly long[] NdsSaveSizes = [512, 8192, 65536, 131072, 262144, 524288, 1048576, 8388608, 16777216];

    /// <summary>A PS1 memory card image: 16 blocks of 8 KB (PCSX ReARMed's <c>MCD_SIZE</c>).</summary>
    private const long PsxCardSize = 128 * 1024;

    /// <summary>A Nintendo 64 save: Mupen64Plus-Next's whole save RAM (EEPROM 2 KB + four Controller Paks of 32 KB + SRAM
    /// 32 KB + FlashRAM 128 KB, <c>sizeof(saved_memory)</c>), the same block in the app and the browser (D146).</summary>
    private const long N64SaveSize = N64Save.Size;

    private static readonly GameConsole[] AllConsoles =
    [
        new("gb", "Game Boy", ["Game Boy"], [".gb"], "mgba", BrowserSaveMode.Shared, "sav", 256 * 1024 + 64,
            [512, 2048, 8192, 32768, 65536, 131072], GbFooters),
        new("gbc", "Game Boy Color", ["Game Boy Color"], [".gbc"], "mgba", BrowserSaveMode.Shared, "sav", 256 * 1024 + 64,
            [512, 2048, 8192, 32768, 65536, 131072], GbFooters),
        new("gba", "Game Boy Advance", ["Game Boy Advance"], [".gba"], "mgba", BrowserSaveMode.Shared, "sav", 256 * 1024 + 64,
            [512, 8192, 32768, 65536, 131072], [0, 16]),

        // Nintendo 64 (D146): the core's whole save RAM, one exact size. JellyEMU's tag is "N64". Cartridges, so a
        // .zip/.7z holding one is a game too (like GBA). Listed in the platform order GB, GBC, GBA, N64, DS.
        new("n64", "Nintendo 64", ["N64"], [".z64", ".n64", ".v64"], "mupen64plus_next", BrowserSaveMode.Shared, "sav", N64SaveSize,
            [N64SaveSize], [0], exactSizes: true, filesOnly: true),
        new("nds", "Nintendo DS", ["Nintendo DS"], [".nds"], "melonds", BrowserSaveMode.Shared, "sav", 16 * 1024 * 1024,
            NdsSaveSizes, [0], exactSizes: true),
        new("3ds", "Nintendo 3DS", ["Nintendo 3DS"], [".3ds", ".cci", ".cia"], null, BrowserSaveMode.None, "sav", 64L * 1024 * 1024,
            [], [0]),
        new("psp", "PSP", ["PSP"], [".cso", ".iso", ".pbp", ".chd"], null, BrowserSaveMode.None, "zip", 64L * 1024 * 1024,
            [], [0]),

        // PS1 (D137): memory card 1 = PCSX ReARMed's save RAM, a raw 128 KB card, the same bytes in the app and the
        // browser. One .chd or .pbp file per game only (no .cue/.bin, discs folders, .j3u lists or archives yet).
        // Placed AFTER psp, so a .chd/.pbp with no console in its name or folder still falls back to PSP (D89).
        new("psx", "PlayStation", ["PlayStation"], [".chd", ".pbp"], "pcsx_rearmed", BrowserSaveMode.Shared, "sav", PsxCardSize,
            [PsxCardSize], [0], exactSizes: true, singleFileOnly: true),
    ];

    private static readonly Dictionary<string, GameConsole> ByKey = AllConsoles.ToDictionary(c => c.Key, StringComparer.Ordinal);

    private GameConsole(
        string key,
        string displayName,
        string[] tags,
        string[] extensions,
        string? browserCore,
        BrowserSaveMode browserSaves,
        string saveExtension,
        long maxSaveBytes,
        long[] typicalSaveSizes,
        long[] footerSizes,
        bool exactSizes = false,
        bool singleFileOnly = false,
        bool filesOnly = false)
    {
        Key = key;
        DisplayName = displayName;
        Tags = tags;
        Extensions = extensions;
        BrowserCore = browserCore;
        BrowserSaves = browserCore is null ? BrowserSaveMode.None : browserSaves;
        SaveExtension = saveExtension;
        MaxSaveBytes = maxSaveBytes;
        TypicalSaveSizes = typicalSaveSizes;
        FooterSizes = footerSizes;
        ExactSaveSizes = exactSizes;
        SingleFileOnly = singleFileOnly;
        FilesOnly = filesOnly || singleFileOnly;
    }

    /// <summary>Gets every known console.</summary>
    public static IReadOnlyList<GameConsole> All => AllConsoles;

    /// <summary>Gets the short key used in URLs and folders (gb, gbc, gba, n64, nds, 3ds, psp, psx).</summary>
    public string Key { get; }

    /// <summary>Gets the name shown to people.</summary>
    public string DisplayName { get; }

    /// <summary>Gets the exact JellyEMU console tags (D42: our scanner must write the same).</summary>
    public IReadOnlyList<string> Tags { get; }

    /// <summary>Gets the ROM file extensions, lower case with dot.</summary>
    public IReadOnlyList<string> Extensions { get; }

    /// <summary>Gets the EmulatorJS core for the browser, or null if not playable in the browser.</summary>
    public string? BrowserCore { get; }

    /// <summary>Gets how browser saves relate to the app's save.</summary>
    public BrowserSaveMode BrowserSaves { get; }

    /// <summary>Gets the file extension used for stored save versions.</summary>
    public string SaveExtension { get; }

    /// <summary>Gets the hard upper limit for one save of this console.</summary>
    public long MaxSaveBytes { get; }

    /// <summary>Gets the usual save sizes (others get a warning, not a refusal).</summary>
    public IReadOnlyList<long> TypicalSaveSizes { get; }

    /// <summary>Gets clock/footer sizes some emulators add to the end of a save.</summary>
    public IReadOnlyList<long> FooterSizes { get; }

    /// <summary>Gets a value indicating whether a save of any other size is refused (DS, D91), not just warned about.</summary>
    public bool ExactSaveSizes { get; }

    /// <summary>
    /// Gets a value indicating whether a game of this console is only ever one file with one of <see cref="Extensions"/>
    /// (PS1, D137): no .zip/.7z, .cue/.bin, discs folder or .j3u list.
    /// </summary>
    public bool SingleFileOnly { get; }

    /// <summary>
    /// Gets a value indicating whether a game of this console is always one file: one of <see cref="Extensions"/>, or a
    /// .zip/.7z unless <see cref="SingleFileOnly"/>; never a .cue/.bin, discs folder or .j3u list. PS1 (D137) and N64
    /// (D146, new, so no item ids to keep): an item another scanner (JellyEMU) made for some other file in such a
    /// console's folder (a .gb or a PS1 disc in an "n64" folder) is never served.
    /// </summary>
    public bool FilesOnly { get; }

    /// <summary>
    /// Gets the save format family. Saves can only move between games of the same family: Game Boy and Game Boy Color
    /// share one (same core, same .sav layout); every other console is its own.
    /// </summary>
    public string SaveFormat => Key is "gb" or "gbc" ? "gb" : Key;

    /// <summary>Looks up a console by key.</summary>
    /// <param name="key">Console key.</param>
    /// <returns>The console, or null.</returns>
    public static GameConsole? FromKey(string? key) => key is not null && ByKey.TryGetValue(key, out var c) ? c : null;

    /// <summary>
    /// Works out the console of a library item from its tags (JellyEMU's), falling back to the file extension.
    /// </summary>
    /// <param name="tags">Item tags.</param>
    /// <param name="path">Item path.</param>
    /// <returns>The console, or null if it is not a known ROM.</returns>
    public static GameConsole? Detect(IReadOnlyCollection<string>? tags, string? path)
    {
        if (tags is not null)
        {
            foreach (var c in AllConsoles)
            {
                if (tags.Any(t => c.Tags.Contains(t, StringComparer.Ordinal)))
                {
                    return c;
                }
            }
        }

        var ext = string.IsNullOrEmpty(path) ? string.Empty : Path.GetExtension(path).ToLowerInvariant();
        return AllConsoles.FirstOrDefault(c => c.Extensions.Contains(ext, StringComparer.Ordinal));
    }

    /// <summary>
    /// True if a library item at <paramref name="path"/> can be a game of this console. Only <see cref="FilesOnly"/>
    /// consoles (PS1, N64) are strict: their file must have one of the console's extensions (or, N64, be a .zip/.7z),
    /// so an item another scanner (JellyEMU) made for a PS1 .iso, .bin or folder, or for a stray file in an N64 folder,
    /// is never served. Other consoles keep every item they had (D89).
    /// </summary>
    /// <param name="path">Item path.</param>
    /// <returns>Whether the item can be this console's game.</returns>
    public bool AcceptsItemPath(string? path)
    {
        if (!FilesOnly)
        {
            return true;
        }

        var ext = string.IsNullOrEmpty(path) ? string.Empty : Path.GetExtension(path).ToLowerInvariant();
        return ext.Length > 0
            && (Extensions.Contains(ext, StringComparer.Ordinal) || (!SingleFileOnly && ext is ".zip" or ".7z"));
    }

    /// <summary>True if a save of this console can be moved to a game of <paramref name="other"/>.</summary>
    /// <param name="other">Console of the target game.</param>
    /// <returns>Whether the save formats match.</returns>
    public bool SharesSaveFormatWith(GameConsole other)
    {
        ArgumentNullException.ThrowIfNull(other);
        return string.Equals(SaveFormat, other.SaveFormat, StringComparison.Ordinal);
    }

    /// <summary>True if <paramref name="size"/> is a usual save size for this console.</summary>
    /// <param name="size">Save size in bytes.</param>
    /// <returns>Whether the size is typical.</returns>
    public bool IsTypicalSize(long size)
    {
        if (TypicalSaveSizes.Count == 0)
        {
            return true;
        }

        return TypicalSaveSizes.Any(t => FooterSizes.Any(f => t + f == size));
    }
}
