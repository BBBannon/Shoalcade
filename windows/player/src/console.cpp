/*
 * ShoalcadePlayer: console table (part of Shoalcade).
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#include "console.h"

#include <algorithm>
#include <cmath>

namespace sc {
// Source: android/app/src/main/java/uk/benmarks/shoalcade/Console.kt (exactSaveSize, hasFolderSaves, players,
// hasAnalogueStick), PsxCard.SIZE = 131,072, N64Save.SIZE = 296,960.
static const ConsoleInfo kConsoles[] = {
    //  key    name   exact save  folder rewind players stick  clock  maxFF
    {"gba", "GBA", 0, false, true, 1, false, false, 4},
    {"gbc", "GBC", 0, false, true, 1, false, true, 4},
    {"gb", "GB", 0, false, true, 1, false, true, 4},
    {"nds", "DS", 0, false, false, 1, false, false, 4},
    {"psp", "PSP", 0, true, false, 1, true, false, 4},
    {"psx", "PS1", 131072, false, true, 1, false, false, 4},
    {"n64", "N64", 296960, false, false, 4, true, false, 4},
    {"3ds", "3DS", 0, true, false, 1, true, false, 4},
    // Windows only (D218; plan-windows-gc-wii-ps2.md P1): folder saves (Dolphin's User folder; the PS2 memory card
    // in the save folder), coarse rewind (P10, D-8 kept: 90-110 MB states, one a second), four players, sticks,
    // analogue triggers, newest GL, unload before the GL context goes.
    //  key    name        save  folder rewind pl stick  clock  maxFF  abtn  newGL  unload rstick packed coarse-rewind click
    {"gc", "GameCube", 0, true, true, 4, true, false, 4, true, true, true, true, true, 60, 0.9f},
    {"wii", "Wii", 0, true, true, 4, true, false, 4, true, true, true, true, true, 60, 0.9f},
    {"ps2", "PS2", 0, true, true, 4, true, false, 4, true, true, true, true, true, 60},
};

const ConsoleInfo *find_console(const std::string &key) {
    for (const auto &c : kConsoles)
        if (key == c.key) return &c;
    return nullptr;
}

unsigned rewind_every(const ConsoleInfo &c) {
    if (c.coarse_rewind_frames > 0) return (unsigned)c.coarse_rewind_frames;
    return std::string(c.key) == "psx" ? 2 : 1;
}

const std::vector<WiiController> &wii_controllers() {
    // Dolphin libretro Input.cpp: RETRO_DEVICE_WIIMOTE = JOYPAD, _SW = (2 << 8), _NC = (3 << 8), _CC = (4 << 8).
    static const std::vector<WiiController> l = {
        {"remote", "Wii Remote", 1},
        {"sideways", "Wii Remote, sideways", (2u << 8) | 1},
        {"nunchuk", "Wii Remote + Nunchuk", (3u << 8) | 1},
        {"classic", "Classic Controller", (4u << 8) | 1},
    };
    return l;
}

const WiiController *wii_controller(const std::string &key) {
    for (const auto &c : wii_controllers())
        if (key == c.key) return &c;
    return nullptr;
}

std::vector<std::pair<std::string, std::string>> ds_layout_options(const std::string &layout, bool swap) {
    // GameLayout.kt DsLayout: SIDE left-right/right-left, STACK top-bottom/bottom-top, BIG hybrid-top/hybrid-bottom.
    const char *normal = nullptr, *swapped = nullptr;
    if (layout == "side") normal = "left-right", swapped = "right-left";
    else if (layout == "stack") normal = "top-bottom", swapped = "bottom-top";
    else if (layout == "big") normal = "hybrid-top", swapped = "hybrid-bottom";
    else return {};
    return {
        {"melonds_number_of_screen_layouts", "1"},
        {"melonds_screen_layout1", swap ? swapped : normal},
        {"melonds_hybrid_small_screen", swap ? "top" : "bottom"},
    };
}

// Measured from our Azahar build's own frames (docs/windows/phase6-3ds.md "Layouts"), same numbers as
// libretro_window.cpp ComputeLayoutGeometry and the phone's N3dsLayout aspect table.
static const N3dsLayout kN3dsLayouts[] = {
    //  key        core option       frame        top                    bottom
    {"stacked", "default", 400, 480, {0, 0, 400, 240}, {40, 240, 320, 240}},
    {"single", "single_screen", 400, 240, {0, 0, 400, 240}, {0, 0, 0, 0}},
    {"large", "large_screen", 1920, 960, {0, 0, 1600, 960}, {1600, 720, 320, 240}},
    {"side", "side_by_side", 720, 240, {0, 0, 400, 240}, {400, 0, 320, 240}},
};
static const N3dsLayout kN3dsFlipped[] = {
    {"stacked", "default", 400, 480, {0, 240, 400, 240}, {40, 0, 320, 240}},
    {"single", "single_screen", 320, 240, {0, 0, 0, 0}, {0, 0, 320, 240}},
    {"large", "large_screen", 1680, 960, {1280, 720, 400, 240}, {0, 0, 1280, 960}},
    {"side", "side_by_side", 720, 240, {320, 0, 400, 240}, {0, 0, 320, 240}},
};

const N3dsLayout *n3ds_layout(const std::string &key, bool flip) {
    for (const auto &l : flip ? kN3dsFlipped : kN3dsLayouts)
        if (key == l.key) return &l;
    return nullptr;
}

std::vector<std::pair<std::string, std::string>> n3ds_layout_options(const std::string &key, bool flip) {
    // Game3dsActivity.applyCoreLayout: citra_layout_option = the layout, citra_swap_screen = Bottom when flipped.
    const N3dsLayout *l = n3ds_layout(key, flip);
    if (!l) return {};
    return {{"citra_layout_option", l->core}, {"citra_swap_screen", flip ? "Bottom" : "Top"}};
}

bool n3ds_touch_point(const N3dsLayout &l, float gx, float gy, float gw, float gh, float mx, float my, N3dsTouch &t) {
    if (gw <= 0 || gh <= 0 || l.bottom.w == 0) return false;
    if (mx < gx || mx >= gx + gw || my < gy || my >= gy + gh) return false; // input.cpp: no touch off the picture
    // input.cpp Input::poll
    float nx = std::clamp((mx - gx) / gw, 0.0f, 1.0f), ny = std::clamp((my - gy) / gh, 0.0f, 1.0f);
    long px = std::lround(nx * 0xfffe - 0x7fff), py = std::lround(ny * 0xfffe - 0x7fff);
    // mouse_tracker.cpp MouseTracker::Update (float maths, then truncation, as the core does)
    int fx = static_cast<int>((px + 0x7fff) / (float)(0x7fff * 2) * l.frame_w);
    int fy = static_cast<int>((py + 0x7fff) / (float)(0x7fff * 2) * l.frame_h);
    const ScreenRect &b = l.bottom;
    if (fx < b.x || fx >= b.x + b.w || fy < b.y || fy >= b.y + b.h) return false;
    // EmuWindow::TouchPressed: frame pixel -> 3DS pixel by the bottom screen's size.
    t.frame_x = fx, t.frame_y = fy;
    t.x = (fx - b.x) * 320 / b.w, t.y = (fy - b.y) * 240 / b.h;
    return true;
}
} // namespace sc
