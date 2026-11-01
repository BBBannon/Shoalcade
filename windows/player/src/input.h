/*
 * ShoalcadePlayer: keyboard, controllers and mouse -> the core (part of Shoalcade).
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Same button names, key names (KeyboardEvent.code) and controller names as the browser player
 * (server/.../Pages/play.js DEFAULT_KEYS / DEFAULT_PAD / *_BY_CONSOLE, play-ui.js HOTKEYS), so a control setup can be
 * shared. Controllers are Nintendo-by-position like the phone: south = B, east = A, west = Y, north = X.
 * N64: each controller is its own player (ports 0-3); everything else plays as player 1.
 */
#pragma once
#include <SDL3/SDL.h>

#include <array>
#include <cstdint>
#include <map>
#include <string>
#include <vector>

#include "console.h"
#include "nlohmann/json.hpp"

namespace sc {
enum class Hotkey { None, Pause, FastForward, QuickSave, QuickLoad, Screenshot, Fullscreen, Rewind, Menu, Flip };

class Input {
public:
    static constexpr int kPorts = 4;
    /** controls = the start message's `controls` ({keyboard, gamepad, hotkeys}, all optional). */
    void configure(const ConsoleInfo &console, const nlohmann::json &controls);
    /** Gamepad add/remove and key presses. Returns a hotkey on its key-down (never a held one). */
    Hotkey handle_event(const SDL_Event &e);
    /** Fast-forward / rewind hold keys. */
    bool held(Hotkey h) const;
    /** Called from the core's input_poll. `game` = the picture's place in window coordinates (mouse = touch). */
    void poll(SDL_FRect game, bool window_focused);
    int16_t state(unsigned port, unsigned device, unsigned index, unsigned id) const;
    bool rumble(unsigned port, unsigned effect, uint16_t strength);
    /** Test hook: hold a button on a port (button = Shoalcade name, e.g. "A") until released. */
    bool force_button(unsigned port, const std::string &name, bool on);
    /** Test hook: the mouse held at window (x, y), button down or up, instead of the real mouse; off = real mouse. */
    void force_mouse(bool on, float x, float y, bool down) { mouse_forced_ = on, mouse_x_ = x, mouse_y_ = y, mouse_down_ = down; }
    bool mouse_forced() const { return mouse_forced_; }
    /** Test hook: the pointer as the core last read it, and how often it read "pressed" as 1. */
    struct PointerSeen {
        int16_t x = 0, y = 0;
        bool down = false;
        uint64_t pressed_reads = 0;
    };
    PointerSeen pointer_seen() const { return seen_; }
    int ports() const { return players_; }
    void close();

    /** For the menu's Controls list: each game button and what presses it, in plain words. */
    struct ControlText {
        std::string what, keys, pad;
    };
    std::vector<ControlText> describe_buttons() const;
    std::vector<ControlText> describe_hotkeys() const;
    /** The keys of one shortcut, e.g. "Space" ("" if none). */
    std::string hotkey_keys(Hotkey h) const;
    /** After the menu closes: the game gets no buttons until every key, button and the mouse are let go. */
    void block_until_released() { blocked_ = true; }

private:
    struct Target {
        enum Kind { Button, Analog } kind = Button;
        unsigned id = 0;   // RETRO_DEVICE_ID_JOYPAD_* for buttons
        unsigned stick = 0; // RETRO_DEVICE_INDEX_ANALOG_LEFT / RIGHT
        unsigned axis = 0;  // RETRO_DEVICE_ID_ANALOG_X / Y
        int sign = 1;
    };
    struct PadSource {
        enum Kind { Button, AxisPos, AxisNeg } kind = Button;
        int code = 0; // SDL_GamepadButton or SDL_GamepadAxis
    };
    struct Port {
        uint16_t buttons = 0;
        int16_t analog[2][2] = {{0, 0}, {0, 0}};
    };
    struct Pad {
        SDL_Gamepad *pad = nullptr;
        SDL_JoystickID id = 0;
        int slot = 0;
        uint16_t strong = 0, weak = 0;
    };

    float key_level(const std::vector<SDL_Scancode> &keys, const bool *ks) const;
    float pad_level(const std::vector<PadSource> &srcs, SDL_Gamepad *pad, bool analog) const;
    void add_pad(SDL_JoystickID id);
    void remove_pad(SDL_JoystickID id);

    const ConsoleInfo *console_ = nullptr;
    bool mouse_forced_ = false, mouse_down_ = false;
    float mouse_x_ = 0, mouse_y_ = 0;
    mutable PointerSeen seen_;
    int players_ = 1;
    std::map<std::string, Target> targets_;
    std::map<std::string, std::vector<SDL_Scancode>> keys_;
    std::map<std::string, std::vector<PadSource>> pad_;
    std::map<std::string, std::vector<std::string>> pad_names_; // as configured, for describe_buttons()
    bool blocked_ = false;
    std::map<Hotkey, std::vector<SDL_Scancode>> hotkeys_;
    std::vector<bool> hotkey_scancode_; // keys used by shortcuts never reach the game
    std::vector<Pad> pads_;
    std::array<Port, kPorts> ports_{};
    std::array<uint16_t, kPorts> forced_{};
    int16_t ptr_x_ = 0, ptr_y_ = 0;
    bool ptr_down_ = false;
};

/** Browser KeyboardEvent.code name -> SDL scancode (SDL_SCANCODE_UNKNOWN if not known). */
SDL_Scancode scancode_from_code(const std::string &code);
} // namespace sc
