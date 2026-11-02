/*
 * ShoalcadePlayer: the core options Shoalcade sets for each console (part of Shoalcade).
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * ONE table, copied from the Android app so the phone, the browser and Windows give the cores the same settings
 * (same settings = same save bytes). Each block names its Android source. If you change one here, change the phone
 * too (and the browser's play.js where noted), or saves stop matching.
 *
 * melonDS DS values must be lower case or the core silently falls back to its default (Console.kt comment).
 */
#include <map>

#include "console.h"

namespace sc {
static const std::map<std::string, std::vector<ForcedOption>> kTable = {
    // Console.kt coreVariables NDS (and GameLayout.kt DsLayout.variables for the screen layout).
    // Firmware/BIOS: melonDS DS's built-in stand-ins (no BIOS is ever bundled). Touch mode "touch": the mouse is the
    // stylus on the bottom screen. Console mode "ds" (not DSi).
    {"nds",
     {
         {"melonds_sysfile_mode", "builtin", true},
         {"melonds_console_mode", "ds", true},
         {"melonds_touch_mode", "touch", true},
         {"melonds_number_of_screen_layouts", "1", false},
         {"melonds_screen_layout1", "top-bottom", false},
     }},
    // Console.kt coreVariables PSP. Frame duplication: Lemuroid's only PSP default (smoother below 60 fps).
    // Cheats: D143, PPSSPP only applies cheats with this on.
    {"psp",
     {
         {"ppsspp_cheats", "enabled", true},
         {"ppsspp_frame_duplication", "enabled", false},
     }},
    // Console.kt coreVariables PSX (D135, same three as the browser): HLE BIOS stand-in (no BIOS bundled), card 1 =
    // the synced save (RETRO_MEMORY_SAVE_RAM, 131,072 bytes), no card 2 (its default "shared" would be a file
    // Shoalcade never syncs).
    {"psx",
     {
         {"pcsx_rearmed_bios", "HLE", true},
         {"pcsx_rearmed_memcard1", "libretro", true},
         {"pcsx_rearmed_memcard2", "none", true},
     }},
    // Console.kt coreVariables N64 (plan-n64 Step 0, same as the browser): Controller Pak 1 = memory pak (inside the
    // synced save RAM), paks 2-4 none (never rumble or transfer). Renderer, C-button mode, deadzone: core defaults.
    {"n64",
     {
         {"mupen64plus-pak1", "memory", true},
         {"mupen64plus-pak2", "none", true},
         {"mupen64plus-pak3", "none", true},
         {"mupen64plus-pak4", "none", true},
     }},
    // Game3dsActivity.kt options() (Azahar 2126.2 core_settings.cpp), D151 defaults with the phone's dev switches at
    // their release values. Locked:
    //  - OpenGL: the only hardware context this player gives a core (the phone's Vulkan layer is Android-only).
    //  - the save location (LibRetro Default = <saveFolder>/Azahar/) and the virtual SD card: otherwise the game's
    //    save would go to Azahar's own folder, outside the memory stick Shoalcade backs up and syncs (D158).
    //  - touch on: the mouse is the stylus on the bottom screen (the phone's citra_enable_touch_touchscreen).
    //  - CPU JIT on (the phone's fixed value).
    // Not locked: the picture (resolution factor = the plan's upscaling row), shader switches, and the layout
    // (Windows default "Top and bottom", D206; the shell's `layout` replaces it).
    {"3ds",
     {
         {"citra_graphics_api", "OpenGL", true},
         {"citra_use_libretro_save_path", "LibRetro Default", true},
         {"citra_use_virtual_sd", "enabled", true},
         {"citra_enable_touch_touchscreen", "enabled", true},
         {"citra_use_cpu_jit", "enabled", true},
         {"citra_resolution_factor", "1", false},
         {"citra_use_hw_shader", "enabled", false},
         {"citra_use_shader_jit", "enabled", false},
         {"citra_shaders_accurate_mul", "enabled", false},
         {"citra_use_disk_shader_cache", "enabled", false},
         {"citra_layout_option", "default", false},
         {"citra_swap_screen", "Top", false},
     }},
    // GameCube and Wii: Dolphin libretro (Windows only, D218; plan-windows-gc-wii-ps2.md P3). Locked:
    //  - OSD off: Dolphin draws its messages ("Video Info: ...") into the game's picture.
    //  - Hardware renderer (OpenGL, the only hardware context this player gives) and no GameCube BIOS (none bundled).
    //  - Cheats: "Internal Cheats" on, so Shoalcade's cheats patch (tools/dolphin-patches/0001) applies the codes the
    //    player hands over; "Import cheats" off, so the core never writes a .cht file beside the system folder and
    //    only the person's own codes run.
    //  - Wii: the pointer is the mouse (ir mode 2 = RETRO_DEVICE_POINTER, P6), and each port's Wii controller type is
    //    the one the player sets (P5, "frontend"), not the core's option.
    {"gc",
     {
         {"dolphin_osd_enabled", "disabled", true},
         {"dolphin_renderer", "Hardware", true},
         {"dolphin_skip_gc_bios", "enabled", true},
         {"dolphin_cheats_enabled", "enabled", true},
         {"dolphin_cheats_import", "disabled", true},
     }},
    {"wii",
     {
         {"dolphin_osd_enabled", "disabled", true},
         {"dolphin_renderer", "Hardware", true},
         {"dolphin_skip_gc_bios", "enabled", true},
         {"dolphin_cheats_enabled", "enabled", true},
         {"dolphin_cheats_import", "disabled", true},
         {"dolphin_ir_mode", "2", true},
         {"dolphin_wiimote_default_device", "frontend", true},
     }},
    // PS2: the per-core rules are in kCoreTable below (the two cores' option values differ).
    // GB / GBC / GBA (mGBA): Console.kt sets nothing; core defaults. gpSP (GBA link, GameActivity.kt) gets
    // gpsp_bios=builtin and gpsp_serial from the link settings: link play isn't in the player yet.
};

// Rules for one core of a console, by {console, the core's library_name}. PS2 (D222 D-3: the person picks the core):
//  - LRPS2 ("ParaLLEl PS2"): the game's own memory card in the save folder (<saveFolder>\<game>.ps2), never the
//    shared cards in the system folder (one card for every game and every person); OpenGL renderer.
//  - PCEE2: OpenGL renderer (the player gives no Vulkan; its other APIs copy every frame back). Its card is always
//    the game's own in the save folder (tools/pcee2-patches/0001), so there is no option to lock.
static const std::map<std::pair<std::string, std::string>, std::vector<ForcedOption>> kCoreTable = {
    {{"ps2", "ParaLLEl PS2"},
     {
         {"pcsx2_shared_memory_cards", "disabled", true},
         {"pcsx2_renderer", "OpenGL", true},
     }},
    {{"ps2", "PCEE2"},
     {
         {"pcsx2_renderer", "opengl", true},
     }},
};

const std::vector<ForcedOption> &forced_options(const std::string &console_key) {
    static const std::vector<ForcedOption> none;
    auto it = kTable.find(console_key);
    return it == kTable.end() ? none : it->second;
}

std::vector<ForcedOption> forced_options(const std::string &console_key, const std::string &core_name) {
    std::vector<ForcedOption> out = forced_options(console_key);
    auto it = kCoreTable.find({console_key, core_name});
    if (it != kCoreTable.end()) out.insert(out.end(), it->second.begin(), it->second.end());
    return out;
}
} // namespace sc
