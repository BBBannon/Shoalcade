/*
 * ShoalcadePlayer: what the player needs to know about each console (part of Shoalcade).
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Mirrors android/app/src/main/java/uk/benmarks/shoalcade/Console.kt. The key is the server's console key.
 */
#pragma once
#include <cstddef>
#include <string>
#include <utility>
#include <vector>

namespace sc {
struct ConsoleInfo {
    const char *key;        // "gba", "gbc", "gb", "nds", "psp", "psx", "n64", "3ds", "gc", "wii", "ps2", "xbox360"
    const char *short_name; // for messages
    /** Save is always exactly this many bytes (Console.exactSaveSize), 0 = sizes vary. */
    size_t exact_save_size;
    /** Save is a folder the core writes itself (Console.hasFolderSaves: PSP, 3DS). */
    bool folder_saves;
    /** Rewind allowed (plan 3.2: states small enough). */
    bool rewind;
    /** Ports the player drives (Console.players: N64 4, else 1). */
    int players;
    /** Left stick is the game's analogue stick (Console.hasAnalogueStick). */
    bool analogue_stick;
    /** Game Boy clock cartridges can carry a 48-byte clock beside the save (GbClock.kt). */
    bool gb_clock;
    /** Fastest fast-forward allowed (D183: 4x everywhere on Windows, once each console's 4x sound test passes). */
    int max_ff;
    // Windows-only consoles (GameCube, Wii, PS2: D218, plan-windows-gc-wii-ps2.md section 3.2). Each is false for
    // the phone's consoles, so nothing changes for them.
    /** P4: analogue buttons (RETRO_DEVICE_INDEX_ANALOG_BUTTON): the triggers' real level (GC L/R, PS2 L2/R2). */
    bool analog_buttons = false;
    /** P2: ask the driver for the newest OpenGL core version it has (4.6 down to the core's own), not the core's. */
    bool newest_gl = false;
    /** P7: on close, unload the game before the GL context is destroyed (the core flushes GPU work on unload). */
    bool unload_before_gl_destroy = false;
    /** The right stick is a stick for the game (GC C-stick, PS2 right stick, Wii pointer when not the mouse). */
    bool right_stick = false;
    /** P8: snapshots are written compressed (statefile.h): 90-110 MB raw, mostly zeros. */
    bool compress_states = false;
    /**
     * P10: coarse rewind: a state every this many frames (about a second), ~30 kept, each step back = one of them.
     * 0 = the usual rewind (a state every frame or two).
     */
    int coarse_rewind_frames = 0;
    /**
     * P4: how far a trigger (an axis) must travel before it also presses its digital button, for analog_buttons
     * consoles. GameCube/Wii: 90% (Dolphin's own threshold; the pad's L/R click at the end). PS2: half way, as every
     * other button-from-an-axis (review #14).
     */
    float trigger_click = 0.5f;
    /**
     * The core gets a GL context of its own even though it doesn't ask (SET_HW_SHARED_CONTEXT is honoured for any
     * core). Dolphin's OpenGL core profile doesn't ask, yet keeps GL state between frames: in ours, our drawing
     * undid it and still menus and 30 fps games showed black (02/11/2026).
     */
    bool own_gl_context = false;
    /** X-5 (Xbox 360, Xenia): the core has no cheats (retro_cheat_set is empty): none handed over, no Cheats menu. */
    bool cheats = true;
    /** X-5: the core has no snapshots (retro_serialize_size is 0): no Snapshots menu, Automatic snapshot never taken. */
    bool snapshots = true;
};

const ConsoleInfo *find_console(const std::string &key);

/**
 * Frames between rewind states unless the start message says otherwise: the one rule for the start message and the
 * menu's Rewind switch. PS1 every 2nd frame (bigger states), coarse rewind (gc/wii/ps2, P10) its own interval, else 1.
 */
unsigned rewind_every(const ConsoleInfo &c);

/** P5: Wii controller types (Dolphin libretro's retro_set_controller_port_device ids, Input.cpp). */
struct WiiController {
    const char *key;   // remote, sideways, nunchuk, classic (what the shell sends and remembers per game)
    const char *label; // menu words
    unsigned device;   // RETRO_DEVICE_JOYPAD with Dolphin's subclass
};
const std::vector<WiiController> &wii_controllers();
/** The type for a key, or nullptr if unknown. */
const WiiController *wii_controller(const std::string &key);

/** One core option the player sets. */
struct ForcedOption {
    const char *key;
    const char *value;
    /**
     * true  = safety rule, always wins (PS1 BIOS stand-in, memory cards, N64 paks, DS firmware): the shell can't change it.
     * false = starting default the shell's `options` / `layout` may replace (DS screen layout, PSP frame duplication).
     */
    bool locked;
};

/** Options for a console, from the table in forced_options.cpp. */
const std::vector<ForcedOption> &forced_options(const std::string &console_key);
/**
 * The same plus the rules for one core of that console, by the core's library_name (PS2 has two cores the person
 * picks between, D222 D-3: LRPS2 "ParaLLEl PS2" and "PCEE2", whose option values differ).
 */
std::vector<ForcedOption> forced_options(const std::string &console_key, const std::string &core_name);

/** melonDS DS options for a DS screen layout (GameLayout.kt DsLayout.variables). Empty if the layout is unknown. */
std::vector<std::pair<std::string, std::string>> ds_layout_options(const std::string &layout, bool swap);

/** A rectangle in the core's frame (pixels). w == 0: not shown. */
struct ScreenRect {
    int x = 0, y = 0, w = 0, h = 0;
};

/**
 * One 3DS screen layout (Game3dsActivity.kt N3dsLayout + Azahar 2126.2's ComputeLayoutGeometry and framebuffer
 * layouts at resolution factor 1, large-screen proportion 4): the core options, the frame the core draws, and where
 * each screen is in it. The mouse is the stylus on [bottom] (Azahar maps the pointer through the same layout).
 */
struct N3dsLayout {
    const char *key;   // stacked, single, large, side (the phone's keys)
    const char *core;  // citra_layout_option value
    int frame_w, frame_h;
    ScreenRect top, bottom;
};

/** The layout for a key and Flip, or nullptr if the key is unknown. */
const N3dsLayout *n3ds_layout(const std::string &key, bool flip);

/** Azahar options for a 3DS layout + Flip (citra_layout_option, citra_swap_screen). Empty if the key is unknown. */
std::vector<std::pair<std::string, std::string>> n3ds_layout_options(const std::string &key, bool flip);

/**
 * Where a mouse at window (mx, my) touches the 3DS bottom screen, given where the game picture is drawn in the window
 * (gx, gy, gw, gh): the player's pointer (-0x7fff..0x7fff over the picture, input.cpp) run through Azahar's own
 * mapping (mouse_tracker.cpp: pointer -> frame pixel -> inside bottom_screen -> bottom-screen pixel). False = not on
 * the bottom screen (no touch). Used by the tests to know where a click must land.
 */
struct N3dsTouch {
    int frame_x = 0, frame_y = 0; // the touched pixel in the core's frame (where Azahar draws its cursor)
    int x = 0, y = 0;             // the 3DS bottom-screen pixel the game gets (0-319, 0-239)
};
bool n3ds_touch_point(const N3dsLayout &l, float gx, float gy, float gw, float gh, float mx, float my, N3dsTouch &t);
} // namespace sc
