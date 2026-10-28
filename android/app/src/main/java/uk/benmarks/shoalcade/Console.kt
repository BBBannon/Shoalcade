package uk.benmarks.shoalcade

/**
 * Consoles the app knows. `key` is the Shoalcade server's console key (`/shoalcade/info` consoles[].key).
 * `tag` is the JellyEMU tag string (kept for reading v0.4 game-list caches).
 * `core` is the bundled libretro core file in jniLibs (null = not playable in-app yet).
 * GB/GBC use mGBA, not gambatte: gambatte is GPL-2.0-only, which clashes with the app's GPL-3.0 (D25).
 */
enum class Console(
    val key: String,
    val tag: String,
    val shortName: String,
    val folder: String,
    val core: String?,
    val romExtensions: Set<String>,
) {
    GBA("gba", "Game Boy Advance", "GBA", "gba", "libmgba_libretro_android.so", setOf("gba")),
    GBC("gbc", "Game Boy Color", "GBC", "gbc", "libmgba_libretro_android.so", setOf("gbc", "gb")),
    GB("gb", "Game Boy", "GB", "gb", "libmgba_libretro_android.so", setOf("gb", "gbc")),
    NDS("nds", "Nintendo DS", "DS", "nds", "libmelondsds_libretro_android.so", setOf("nds")),
    N3DS("3ds", "Nintendo 3DS", "3DS", "3ds", null, setOf("3ds", "cci", "cia", "3dsx", "cxi")),
    PSP("psp", "PSP", "PSP", "psp", "libppsspp_libretro_android.so", setOf("iso", "cso", "pbp", "chd")),
    /** PS1 (D135): PCSX ReARMed, one .chd or .pbp per game. Save = memory card 1 (131,072 bytes, see PsxCard). */
    PSX("psx", "PlayStation", "PS1", "psx", "libpcsxrearmed_libretro_android.so", setOf("chd", "pbp")),
    ;

    val isDualScreen get() = this == NDS

    /** Widescreen: played in landscape with the controls drawn over the game. */
    val isLandscape get() = this == PSP

    /**
     * PSP saves are a folder of files on a pretend memory stick, not one block of save memory.
     * Each game gets its own memory stick folder, uploaded to Jellyfin as one zip (D27).
     */
    val hasFolderSaves get() = this == PSP


    /** Core options. melonDS DS values must be lower case or they silently fall back. */
    val coreVariables: List<Pair<String, String>>
        get() = when (this) {
            NDS -> listOf(
                "melonds_number_of_screen_layouts" to "1",
                "melonds_screen_layout1" to "top-bottom",
                "melonds_touch_mode" to "touch",
                "melonds_sysfile_mode" to "builtin",
                "melonds_console_mode" to "ds",
            )
            // Lemuroid's only PSP default: smoother picture when the game runs below 60 fps.
            // D143: PPSSPP only applies cheats with this on.
            PSP -> listOf("ppsspp_frame_duplication" to "enabled", "ppsspp_cheats" to "enabled")
            // D135, same three as the browser: built-in BIOS stand-in (no BIOS bundled), card 1 = the synced save,
            // no second card (its default "shared" would be a card file Shoalcade never syncs).
            PSX -> listOf(
                "pcsx_rearmed_bios" to "HLE",
                "pcsx_rearmed_memcard1" to "libretro",
                "pcsx_rearmed_memcard2" to "none",
            )
            else -> emptyList()
        }

    companion object {
        /** Server console key → console. Null for a console this app doesn't know (shown as "Unknown console"). */
        fun fromKey(key: String?): Console? = entries.firstOrNull { it.key == key }

        /** Most specific tag wins: entries are ordered GBA, GBC, GB so "Game Boy" never shadows the others. */
        fun fromTags(tags: List<String>): Console? = entries.firstOrNull { c -> tags.any { it.equals(c.tag, true) } }
    }
}
