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
    /** 3DS (plan-3ds): our own Azahar build on its own Vulkan layer, in [Game3dsActivity] (D153, D155). */
    N3DS("3ds", "Nintendo 3DS", "3DS", "3ds", "libazahar_libretro_android.so", setOf("3ds", "cci", "cia", "3dsx", "cxi", "zcci", "zcxi", "z3dsx")),
    PSP("psp", "PSP", "PSP", "psp", "libppsspp_libretro_android.so", setOf("iso", "cso", "pbp", "chd")),
    /** PS1 (D135): PCSX ReARMed, one .chd or .pbp per game. Save = memory card 1 (131,072 bytes, see PsxCard). */
    PSX("psx", "PlayStation", "PS1", "psx", "libpcsxrearmed_libretro_android.so", setOf("chd", "pbp")),
    /**
     * N64 (plan-n64): Mupen64Plus-Next, GLES3 build (a GL core, like PPSSPP). Tag "N64" is JellyEMU's (the plugin's
     * ConsoleDetector writes the same). Save = the core's whole save RAM, 296,960 bytes ([N64Save]). Last, so no tag clash.
     */
    N64("n64", "N64", "N64", "n64", "libmupen64plusnext_libretro_android.so", setOf("z64", "n64", "v64")),
    /**
     * GameCube, Wii and PS2 (D218, plan-windows-gc-wii-ps2.md): **Windows app only**. No phone core (null), so the phone
     * never downloads or plays them. On Windows: Dolphin (GameCube + Wii) and LRPS2 or PCEE2 (PS2, the person picks,
     * D222 D-3). Folder saves: Dolphin's User folder / the PS2 memory card in the game's save folder ([Storage]).
     * Extensions: plan §4 (no archives: discs are too big to unpack per play; PS2 .bin/.cue out in v1, D-5).
     */
    GC("gc", "GameCube", "GameCube", "gc", null, setOf("iso", "gcm", "rvz", "wia", "gcz", "ciso", "tgc", "dol", "elf")),
    WII("wii", "Wii", "Wii", "wii", null, setOf("iso", "wbfs", "rvz", "wia", "gcz", "ciso", "wad", "dol", "elf")),
    PS2("ps2", "PlayStation 2", "PS2", "ps2", null, setOf("iso", "chd", "cso", "zso", "gz", "ciso", "elf")),
    ;

    /**
     * Maker the library's console bar groups it under ([ConsoleBar]). Same table as the plugin's GameConsole.Maker.
     * No `else`, so a new console must be placed here.
     */
    val maker: Maker get() = when (this) {
        GB, GBC, GBA, NDS, N3DS, N64, GC, WII -> Maker.NINTENDO
        PSP, PSX, PS2 -> Maker.SONY
    }

    val isDualScreen get() = this == NDS

    /** Landscape only, controls drawn over the game: PSP (widescreen) and N64 (stick, D-pad and C-buttons need the room). */
    val isLandscape get() = this == PSP || this == N64

    /** The controller's left stick (and the touch stick) is the game's analogue stick, not the D-pad. 3DS: the circle pad. */
    val hasAnalogueStick get() = this == PSP || this == N64 || this == N3DS || isWindowsOnly

    /**
     * Players the phone can drive: N64 takes one controller per player, up to 4 (core ports 0-3, [PlayerPorts]).
     * Everything else is player 1 only (every controller plays as player 1, as before).
     */
    val players get() = if (this == N64 || isWindowsOnly) 4 else 1

    /** GameCube, Wii, PS2: played by the Windows app only (no phone core, D218). The phone shows them, never plays them. */
    val isWindowsOnly get() = this == GC || this == WII || this == PS2

    /** D-8: a game of this console can be a disc set (several discs and a .m3u naming them, one save). */
    val takesDiscSets get() = this == PSX || this == PS2 || this == GC || this == WII

    /**
     * PSP saves are a folder of files on a pretend memory stick, not one block of save memory.
     * Each game gets its own memory stick folder, uploaded to Jellyfin as one zip (D27). 3DS the same: the core's
     * pretend SD card, `Nintendo 3DS` folder (D158).
     */
    val hasFolderSaves get() = this == PSP || this == N3DS || isWindowsOnly

    /**
     * Consoles whose save is always one exact size: PS1 (memory card, [PsxCard.SIZE]) and N64 (save RAM, [N64Save.SIZE]).
     * The core refuses any other size without saying so, so a wrong-size save is never given to it. Null = sizes vary
     * (GB/GBC clock footers, GBA chips, DS) or folder saves (PSP).
     */
    val exactSaveSize: Int? get() = when (this) {
        PSX -> PsxCard.SIZE
        N64 -> N64Save.SIZE
        else -> null
    }

    /** Null if a file of [size] bytes can be this console's save; otherwise why not ("Replace with a file…" refuses it). */
    fun wrongSaveSize(size: Int): String? {
        val exact = exactSaveSize ?: return null
        if (size == exact) return null
        fun n(v: Int) = String.format(java.util.Locale.UK, "%,d", v)
        return "That file is ${n(size)} bytes, but $shortName saves are always ${n(exact)} bytes. Nothing was replaced."
    }


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
            // plan-n64 Step 0, same as the browser: Controller Pak 1 = memory pak (it lives inside the synced save RAM),
            // paks 2-4 none (never rumble or transfer). Renderer (GLideN64), C-button mode (right stick) and deadzone: core defaults.
            N64 -> listOf(
                "mupen64plus-pak1" to "memory",
                "mupen64plus-pak2" to "none",
                "mupen64plus-pak3" to "none",
                "mupen64plus-pak4" to "none",
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
