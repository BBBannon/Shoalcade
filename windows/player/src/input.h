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
#include "pad_shortcuts.h"
#include "remap.h"

namespace sc {
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
    /** False (and no rumble) when the person switched Controller rumble off (D246), or on the N64. */
    bool rumble(unsigned port, unsigned effect, uint16_t strength);
    bool rumble_on() const { return rumble_on_; }
    bool pad_shortcuts_on() const { return pad_shortcuts_; }
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

    /**
     * For the menu's Controls list: each game button and what presses it, in plain words. `name` = the button's or
     * shortcut's name in `controls` ("A", "StickUp", "pause"); `fixed` = can't be changed (Esc = menu).
     */
    struct ControlText {
        std::string name, what, keys, pad;
        bool fixed = false;
    };
    std::vector<ControlText> describe_buttons() const;
    std::vector<ControlText> describe_hotkeys() const;
    /** The keys of one shortcut, e.g. "Space" ("" if none). */
    std::string hotkey_keys(Hotkey h) const;
    /** After the menu closes: the game gets no buttons until every key, button and the mouse are let go. */
    void block_until_released() { blocked_ = true; }
    /** The ☰ button (or anything else on top of the game) has the mouse: the game gets no touch until it's let go. */
    void block_pointer_until_released() { pointer_blocked_ = true; }

    // ---- Changing the buttons (the menu's Controls section; remap.h has the rules) ----
    /** The controller the Controls list shows: the one used last (a button press), else the newest connected. */
    struct PadInfo {
        SDL_JoystickID id = 0;
        std::string model, name; // model = "vvvv:pppp" (USB vendor:product), or the name when the driver gives none
    };
    bool shown_pad(PadInfo &out) const;
    bool pad_info(SDL_JoystickID id, PadInfo &out) const;
    /** The words for one game button on this console ("Cross", "C-stick up"), or for a shortcut. */
    std::string button_words(const std::string &name) const;
    std::string shortcut_words(const std::string &name) const;
    /** One result of a change, for the note the menu shows. `taken` = words for what lost it ("B", "pause"). */
    struct Change {
        bool ok = false;
        std::string what, input;       // "A", "Z" / "right button"
        std::vector<std::string> taken; // "B", "Screenshot (now has no key)"
    };
    /** A key (KeyboardEvent.code name) for a game button, or for a shortcut. */
    Change bind_key(const std::string &name, bool shortcut, const std::string &code);
    /** A controller input (`controls.gamepad` names: "east", "l2", "a1-") for a game button, on one controller model. */
    Change bind_pad(SDL_JoystickID pad, const std::string &button, const std::string &input);
    void reset_keys();
    void reset_hotkeys();
    bool reset_pad(SDL_JoystickID pad);
    /** What changed, as `controlsChanged` carries it (PROTOCOL.md): the whole map, or null = back to the defaults. */
    nlohmann::json keyboard_json() const;
    nlohmann::json hotkeys_json() const;
    nlohmann::json pad_json(const std::string &model) const;
    /** The key's KeyboardEvent.code name ("KeyZ", "ArrowUp"), or "" for a key that can't be used. */
    static std::string code_of(SDL_Scancode sc);
    /** A controller button / axis as a `controls.gamepad` name ("south", "l2", "a0-"), or "" for one that can't be used. */
    static std::string pad_input_of_button(SDL_GamepadButton b);
    static std::string pad_input_of_axis(SDL_GamepadAxis a, bool positive);
    /** The plain words for a key ("Z", "Up arrow"). */
    static std::string key_words(const std::string &code);

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
        /** P4: each button's level 0-0x7fff (a trigger's real level), for consoles with analog_buttons. */
        int16_t button_level[16] = {};
    };
    using Names = std::map<std::string, std::vector<std::string>>;
    struct Pad {
        SDL_Gamepad *pad = nullptr;
        SDL_JoystickID id = 0;
        int slot = 0;
        uint16_t strong = 0, weak = 0;
        std::string model, name;
        PadCombo combo;                                     // D247: Select + R1 / L1 / R2 / L2
        std::map<std::string, std::vector<PadSource>> map; // this controller model's buttons
        Names names;                                        // the same, as configured (for the words)
    };

    /** The player a controller plays as (N64: its own slot; every other console: player 1). */
    int port_of(const Pad &p) const { return players_ > 1 ? p.slot : 0; }
    float key_level(const std::vector<SDL_Scancode> &keys, const bool *ks) const;
    float pad_level(const std::vector<PadSource> &srcs, const Pad &pad, bool analog) const;
    /** A source the game doesn't get now: a shoulder or trigger held for a controller shortcut (D247). */
    static bool kept_from_game(const Pad &pad, const PadSource &src);
    /** D247: a shoulder / trigger change on a controller; returns the shortcut it fires. */
    Hotkey pad_combo(SDL_JoystickID id, PadCombo::Part part, float level);
    void add_pad(SDL_JoystickID id);
    void remove_pad(SDL_JoystickID id);
    /** keys_ / hotkeys_ from key_names_ / hot_names_ (after configure and every change). */
    void compile_keys();
    /** A controller's map: the console's defaults, `controls.gamepad`, then its model's own (`controls.gamepads`). */
    void compile_pad(Pad &p);
    Names pad_names_for(const std::string &model) const;
    const Pad *find_pad(SDL_JoystickID id) const;

    const ConsoleInfo *console_ = nullptr;
    bool mouse_forced_ = false, mouse_down_ = false;
    float mouse_x_ = 0, mouse_y_ = 0;
    mutable PointerSeen seen_;
    int players_ = 1;
    std::map<std::string, Target> targets_;
    std::map<std::string, std::vector<SDL_Scancode>> keys_;
    Names key_names_, default_keys_;   // keyboard: now / the console's defaults (KeyboardEvent.code names)
    Names hot_names_, default_hot_;    // shortcuts: now / defaults (the 3DS's flip only on the 3DS)
    Names pad_base_;                   // controller defaults + `controls.gamepad` (every model)
    std::map<std::string, Names> pad_models_; // `controls.gamepads`: each model's own whole map
    SDL_JoystickID shown_pad_ = 0;     // the controller the Controls list shows (0 = the newest connected)
    bool blocked_ = false, pointer_blocked_ = false;
    bool pressure_cross_ = false; // P4b: the right trigger is a pressure-sensitive Cross (B)
    bool rumble_on_ = true;       // D246: `controls.rumble` (Settings → Display → Controller rumble)
    bool pad_shortcuts_ = true;   // D247: `controls.padShortcuts` (Settings → Display → Controller shortcuts)
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
