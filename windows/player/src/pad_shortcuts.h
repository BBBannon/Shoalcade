/*
 * ShoalcadePlayer: the shortcuts, and the controller shortcuts' rule (part of Shoalcade).
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * No SDL here, so the unit tests can check the rule (input.cpp feeds it the controller's buttons).
 */
#pragma once
#include <cstdint>

namespace sc {
// FastForwardToggle: the controller shortcut Select + R2 (D247), on / off (the Space key is held instead).
enum class Hotkey { None, Pause, FastForward, QuickSave, QuickLoad, Screenshot, Fullscreen, Rewind, Menu, Flip, FastForwardToggle };

/**
 * Controller shortcuts (D247, the phone's): with Select (Back / View) held on a controller, R1 = quick snapshot,
 * L1 = load it, R2 = fast-forward on / off, L2 = screenshot. Physical buttons, whatever the button changes say. The
 * shoulder or trigger press that fires one never reaches the game until it is let go; Select still does.
 * One per controller; fed every change of the four inputs (0-1; the triggers' real level).
 */
struct PadCombo {
    enum Part { L1, R1, L2, R2, Parts };
    // A trigger counts as pressed past half way (the rule for a trigger as a button) and let go below 30%, so a
    // trigger resting near half way can't fire twice.
    static constexpr float kPress = 0.5f, kRelease = 0.3f;
    bool down[Parts] = {};
    uint8_t swallowed = 0; // bit per Part: held for a shortcut, kept from the game until let go
    /** The input's new level. Returns the shortcut it fires (a press with Select held and [on]), else None. */
    Hotkey update(Part part, float level, bool select_held, bool on) {
        bool was = down[part];
        bool now = was ? level >= kRelease : level >= kPress;
        down[part] = now;
        if (!now) {
            swallowed &= (uint8_t)~(1u << part); // let go: the game gets it again
            return Hotkey::None;
        }
        if (was || !on || !select_held) return Hotkey::None;
        swallowed |= (uint8_t)(1u << part);
        return shortcut_of(part);
    }
    bool kept_from_game(Part part) const { return (swallowed >> part) & 1; }
    static Hotkey shortcut_of(Part part) {
        switch (part) {
        case R1: return Hotkey::QuickSave;
        case L1: return Hotkey::QuickLoad;
        case R2: return Hotkey::FastForwardToggle;
        case L2: return Hotkey::Screenshot;
        default: return Hotkey::None;
        }
    }
};
} // namespace sc
