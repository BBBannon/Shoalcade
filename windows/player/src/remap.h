/*
 * ShoalcadePlayer: changing which key or controller button presses a game button (part of Shoalcade).
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Pure C++ (no SDL), so it is unit tested. The rules are the phone's (Controls.kt ControllerMap.assign) and the
 * browser's (play-ui.js bindKey / bindPad):
 *  - One input does one thing: it is taken off every other button (and, for keys, every shortcut) first.
 *  - A direction keeps its stick when it gets a new button, and keeps its buttons when it gets a stick.
 *  - A key or controller button picked for a button replaces what that button had (one key per button).
 */
#pragma once
#include <map>
#include <string>
#include <vector>

namespace sc {
namespace remap {
using Names = std::map<std::string, std::vector<std::string>>;

/** Up / Down / Left / Right, the sticks and the N64 C buttons (play-ui.js bindPad's direction rule). */
bool is_direction(const std::string &button);
/** A stick direction ("a0+" .. "a3-"); triggers ("l2", "r2") count as buttons. */
bool is_axis(const std::string &input);

/** What lost an input to the new choice: a game button or a shortcut, and whether it has nothing left. */
struct Taken {
    bool shortcut = false;
    std::string name;
    bool now_empty = false;
};

/**
 * Keyboard: gives key `code` to `name` (a game button in `keys`, or a shortcut in `hotkeys` when `shortcut`), taking it
 * off everything else in both maps first. Returns what lost it.
 */
std::vector<Taken> assign_key(Names &keys, Names &hotkeys, bool shortcut, const std::string &name, const std::string &code);

/** Controller: gives `input` to `button` in `pad`, taking it off every other button first. Returns what lost it. */
std::vector<Taken> assign_pad(Names &pad, const std::string &button, const std::string &input);
} // namespace remap
} // namespace sc
