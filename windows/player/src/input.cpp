/*
 * ShoalcadePlayer: keyboard, controllers and mouse -> the core (part of Shoalcade).
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#include "input.h"

#include <algorithm>
#include <cmath>

#include "libretro/libretro.h"
#include "log.h"

namespace sc {
using nlohmann::json;
using Names = std::map<std::string, std::vector<std::string>>;

namespace {
// play.js DEFAULT_KEYS (KeyboardEvent.code names).
const Names kDefaultKeys = {
    {"Up", {"ArrowUp"}}, {"Down", {"ArrowDown"}}, {"Left", {"ArrowLeft"}}, {"Right", {"ArrowRight"}},
    {"A", {"KeyX"}}, {"B", {"KeyZ"}}, {"X", {"KeyS"}}, {"Y", {"KeyA"}}, {"L", {"KeyQ"}}, {"R", {"KeyW"}},
    {"L2", {"KeyE"}}, {"R2", {"KeyR"}}, {"Start", {"Enter"}}, {"Select", {"ShiftRight", "Backspace"}},
};
// play.js DEFAULT_KEYS_BY_CONSOLE.n64: arrows = the stick, D-pad on Y G H B, C buttons on I J K L, Z on A.
const Names kN64Keys = {
    {"StickUp", {"ArrowUp"}}, {"StickDown", {"ArrowDown"}}, {"StickLeft", {"ArrowLeft"}}, {"StickRight", {"ArrowRight"}},
    {"Up", {"KeyY"}}, {"Down", {"KeyB"}}, {"Left", {"KeyG"}}, {"Right", {"KeyH"}}, {"A", {"KeyX"}}, {"B", {"KeyZ"}},
    {"Z", {"KeyA"}}, {"CUp", {"KeyI"}}, {"CDown", {"KeyK"}}, {"CLeft", {"KeyJ"}}, {"CRight", {"KeyL"}},
    {"L", {"KeyQ"}}, {"R", {"KeyW"}}, {"Start", {"Enter"}},
};
// play.js DEFAULT_PAD: Nintendo layout by position.
const Names kDefaultPad = {
    {"Up", {"up", "a1-"}}, {"Down", {"down", "a1+"}}, {"Left", {"left", "a0-"}}, {"Right", {"right", "a0+"}},
    {"A", {"east"}}, {"B", {"south"}}, {"X", {"north"}}, {"Y", {"west"}}, {"L", {"l1"}}, {"R", {"r1"}},
    {"L2", {"l2"}}, {"R2", {"r2"}}, {"L3", {"l3"}}, {"R3", {"r3"}}, {"Start", {"start"}}, {"Select", {"select"}},
};
// play.js DEFAULT_PAD_BY_CONSOLE.n64: left stick = N64 stick, right stick = C buttons, bottom = A, left = B,
// both triggers = Z.
const Names kN64Pad = {
    {"StickUp", {"a1-"}}, {"StickDown", {"a1+"}}, {"StickLeft", {"a0-"}}, {"StickRight", {"a0+"}},
    {"Up", {"up"}}, {"Down", {"down"}}, {"Left", {"left"}}, {"Right", {"right"}}, {"A", {"south"}}, {"B", {"west"}},
    {"Z", {"l2", "r2"}}, {"CUp", {"a3-"}}, {"CDown", {"a3+"}}, {"CLeft", {"a2-"}}, {"CRight", {"a2+"}},
    {"L", {"l1"}}, {"R", {"r1"}}, {"Start", {"start"}},
};
// PSP / 3DS (phone: the left stick is the analogue stick, not the D-pad).
const Names kStickPad = {
    {"Up", {"up"}}, {"Down", {"down"}}, {"Left", {"left"}}, {"Right", {"right"}},
    {"StickUp", {"a1-"}}, {"StickDown", {"a1+"}}, {"StickLeft", {"a0-"}}, {"StickRight", {"a0+"}},
};
// GameCube, Wii, PS2 (Windows only): the right stick is the game's second stick (GC C-stick, PS2 right stick).
// Keyboard: I J K L (as the N64's C buttons).
const Names kRightStickKeys = {
    {"RStickUp", {"KeyI"}}, {"RStickDown", {"KeyK"}}, {"RStickLeft", {"KeyJ"}}, {"RStickRight", {"KeyL"}},
};
const Names kRightStickPad = {
    {"RStickUp", {"a3-"}}, {"RStickDown", {"a3+"}}, {"RStickLeft", {"a2-"}}, {"RStickRight", {"a2+"}},
};
// GameCube (and the Wii Remote's A/B): Dolphin libretro reads RETRO A = GC A, B = B, X = X, Y = Y, L2/R2 = the
// analogue L/R triggers, R = Z (Input.cpp descGC). On an Xbox-style pad by position, as Dolphin's own Xbox
// profile: A = bottom, B = left, X = right, Y = top; L/R = the triggers (P4: their real level), Z = right shoulder.
const Names kGcPad = {
    {"A", {"south"}}, {"B", {"west"}}, {"X", {"east"}}, {"Y", {"north"}},
};
// play-ui.js HOTKEYS, plus Rewind (new on Windows) and Esc = menu.
const std::vector<std::pair<std::string, std::pair<Hotkey, std::vector<std::string>>>> kHotkeys = {
    {"pause", {Hotkey::Pause, {"KeyP"}}},          {"ff", {Hotkey::FastForward, {"Space"}}},
    {"quickSave", {Hotkey::QuickSave, {"F2"}}},    {"quickLoad", {Hotkey::QuickLoad, {"F4"}}},
    {"screenshot", {Hotkey::Screenshot, {"F9"}}},  {"fullscreen", {Hotkey::Fullscreen, {"KeyF", "F11"}}},
    {"rewind", {Hotkey::Rewind, {"Backquote"}}},   {"menu", {Hotkey::Menu, {"Escape"}}},
    // 3DS only: the phone's landscape Flip button (Game3dsActivity FLIP). Controller: the left stick click.
    {"flip", {Hotkey::Flip, {"Tab"}}},
};

const char *kPadButtons[] = {"south", "east", "west", "north", "l1", "r1", "l2", "r2", "select", "start",
                             "l3", "r3", "up", "down", "left", "right", "home"};

/**
 * A browser "standard" mapping index ("b0".."b16") becomes its name; any other name stays as it is. False: an index
 * past the list (the name is left unchanged).
 */
bool standard_button(std::string &n) {
    if (!(n.size() >= 2 && n[0] == 'b' && std::all_of(n.begin() + 1, n.end(), ::isdigit))) return true;
    size_t i = std::stoul(n.substr(1));
    if (i >= sizeof kPadButtons / sizeof *kPadButtons) return false;
    n = kPadButtons[i];
    return true;
}

bool pad_source(const std::string &name, int &kind, int &code) {
    // kind: 0 button, 1 axis +, 2 axis -
    static const std::map<std::string, SDL_GamepadButton> buttons = {
        {"south", SDL_GAMEPAD_BUTTON_SOUTH}, {"east", SDL_GAMEPAD_BUTTON_EAST}, {"west", SDL_GAMEPAD_BUTTON_WEST},
        {"north", SDL_GAMEPAD_BUTTON_NORTH}, {"l1", SDL_GAMEPAD_BUTTON_LEFT_SHOULDER},
        {"r1", SDL_GAMEPAD_BUTTON_RIGHT_SHOULDER}, {"select", SDL_GAMEPAD_BUTTON_BACK},
        {"start", SDL_GAMEPAD_BUTTON_START}, {"l3", SDL_GAMEPAD_BUTTON_LEFT_STICK},
        {"r3", SDL_GAMEPAD_BUTTON_RIGHT_STICK}, {"up", SDL_GAMEPAD_BUTTON_DPAD_UP},
        {"down", SDL_GAMEPAD_BUTTON_DPAD_DOWN}, {"left", SDL_GAMEPAD_BUTTON_DPAD_LEFT},
        {"right", SDL_GAMEPAD_BUTTON_DPAD_RIGHT}, {"home", SDL_GAMEPAD_BUTTON_GUIDE},
    };
    std::string n = name;
    if (!standard_button(n)) return false;
    if (n == "l2") return kind = 1, code = SDL_GAMEPAD_AXIS_LEFT_TRIGGER, true;
    if (n == "r2") return kind = 1, code = SDL_GAMEPAD_AXIS_RIGHT_TRIGGER, true;
    auto b = buttons.find(n);
    if (b != buttons.end()) return kind = 0, code = b->second, true;
    if (n.size() == 3 && n[0] == 'a' && n[1] >= '0' && n[1] <= '3' && (n[2] == '+' || n[2] == '-')) {
        static const SDL_GamepadAxis axes[] = {SDL_GAMEPAD_AXIS_LEFTX, SDL_GAMEPAD_AXIS_LEFTY, SDL_GAMEPAD_AXIS_RIGHTX,
                                               SDL_GAMEPAD_AXIS_RIGHTY};
        kind = n[2] == '+' ? 1 : 2;
        code = axes[n[1] - '0'];
        return true;
    }
    return false;
}

Names merged(const Names &base, const Names &over) {
    Names out = base;
    for (const auto &kv : over) out[kv.first] = kv.second;
    return out;
}

std::vector<std::string> as_list(const json &v) {
    std::vector<std::string> out;
    if (v.is_string()) out.push_back(v.get<std::string>());
    else if (v.is_array())
        for (const auto &x : v)
            if (x.is_string()) out.push_back(x.get<std::string>());
    return out;
}
/** Every KeyboardEvent.code name the player knows, and its scancode. */
const std::map<std::string, SDL_Scancode> &key_table() {
    static const std::map<std::string, SDL_Scancode> table = [] {
        std::map<std::string, SDL_Scancode> table;
        for (char ch = 'A'; ch <= 'Z'; ch++)
            table[std::string("Key") + ch] = (SDL_Scancode)(SDL_SCANCODE_A + (ch - 'A'));
        table["Digit0"] = SDL_SCANCODE_0;
        for (char ch = '1'; ch <= '9'; ch++)
            table[std::string("Digit") + ch] = (SDL_Scancode)(SDL_SCANCODE_1 + (ch - '1'));
        for (int i = 1; i <= 12; i++) table["F" + std::to_string(i)] = (SDL_Scancode)(SDL_SCANCODE_F1 + i - 1);
        table["Numpad0"] = SDL_SCANCODE_KP_0;
        for (char ch = '1'; ch <= '9'; ch++)
            table[std::string("Numpad") + ch] = (SDL_Scancode)(SDL_SCANCODE_KP_1 + (ch - '1'));
        const std::pair<const char *, SDL_Scancode> rest[] = {
            {"ArrowUp", SDL_SCANCODE_UP}, {"ArrowDown", SDL_SCANCODE_DOWN}, {"ArrowLeft", SDL_SCANCODE_LEFT},
            {"ArrowRight", SDL_SCANCODE_RIGHT}, {"Enter", SDL_SCANCODE_RETURN}, {"NumpadEnter", SDL_SCANCODE_KP_ENTER},
            {"Space", SDL_SCANCODE_SPACE}, {"Backspace", SDL_SCANCODE_BACKSPACE}, {"Tab", SDL_SCANCODE_TAB},
            {"Escape", SDL_SCANCODE_ESCAPE}, {"ShiftLeft", SDL_SCANCODE_LSHIFT}, {"ShiftRight", SDL_SCANCODE_RSHIFT},
            {"ControlLeft", SDL_SCANCODE_LCTRL}, {"ControlRight", SDL_SCANCODE_RCTRL},
            {"AltLeft", SDL_SCANCODE_LALT}, {"AltRight", SDL_SCANCODE_RALT}, {"Minus", SDL_SCANCODE_MINUS},
            {"Equal", SDL_SCANCODE_EQUALS}, {"BracketLeft", SDL_SCANCODE_LEFTBRACKET},
            {"BracketRight", SDL_SCANCODE_RIGHTBRACKET}, {"Backslash", SDL_SCANCODE_BACKSLASH},
            {"Semicolon", SDL_SCANCODE_SEMICOLON}, {"Quote", SDL_SCANCODE_APOSTROPHE},
            {"Backquote", SDL_SCANCODE_GRAVE}, {"Comma", SDL_SCANCODE_COMMA}, {"Period", SDL_SCANCODE_PERIOD},
            {"Slash", SDL_SCANCODE_SLASH}, {"CapsLock", SDL_SCANCODE_CAPSLOCK}, {"Home", SDL_SCANCODE_HOME},
            {"End", SDL_SCANCODE_END}, {"PageUp", SDL_SCANCODE_PAGEUP}, {"PageDown", SDL_SCANCODE_PAGEDOWN},
            {"Insert", SDL_SCANCODE_INSERT}, {"Delete", SDL_SCANCODE_DELETE},
            {"NumpadAdd", SDL_SCANCODE_KP_PLUS}, {"NumpadSubtract", SDL_SCANCODE_KP_MINUS},
            {"NumpadMultiply", SDL_SCANCODE_KP_MULTIPLY}, {"NumpadDivide", SDL_SCANCODE_KP_DIVIDE},
            {"NumpadDecimal", SDL_SCANCODE_KP_PERIOD}, {"IntlBackslash", SDL_SCANCODE_NONUSBACKSLASH},
        };
        for (const auto &kv : rest) table[kv.first] = kv.second;
        return table;
    }();
    return table;
}
} // namespace

SDL_Scancode scancode_from_code(const std::string &c) {
    auto it = key_table().find(c);
    return it == key_table().end() ? SDL_SCANCODE_UNKNOWN : it->second;
}

void Input::configure(const ConsoleInfo &console, const json &controls) {
    console_ = &console;
    players_ = std::clamp(console.players, 1, kPorts);
    std::string key = console.key;
    targets_.clear();
    auto button = [&](const char *name, unsigned id) { targets_[name] = Target{Target::Button, id, 0, 0, 1}; };
    auto analog = [&](const char *name, unsigned stick, unsigned axis, int sign) {
        targets_[name] = Target{Target::Analog, 0, stick, axis, sign};
    };
    if (key == "n64") {
        // mupen64plus-next's map (emulate_game_controller_via_libretro.c): A = JOYPAD_B, B = JOYPAD_Y, Z = L2,
        // C buttons = right analogue stick (its default C-button mode).
        button("A", RETRO_DEVICE_ID_JOYPAD_B);
        button("B", RETRO_DEVICE_ID_JOYPAD_Y);
        button("Z", RETRO_DEVICE_ID_JOYPAD_L2);
        button("L", RETRO_DEVICE_ID_JOYPAD_L);
        button("R", RETRO_DEVICE_ID_JOYPAD_R);
        button("Start", RETRO_DEVICE_ID_JOYPAD_START);
        button("Up", RETRO_DEVICE_ID_JOYPAD_UP);
        button("Down", RETRO_DEVICE_ID_JOYPAD_DOWN);
        button("Left", RETRO_DEVICE_ID_JOYPAD_LEFT);
        button("Right", RETRO_DEVICE_ID_JOYPAD_RIGHT);
        analog("CUp", RETRO_DEVICE_INDEX_ANALOG_RIGHT, RETRO_DEVICE_ID_ANALOG_Y, -1);
        analog("CDown", RETRO_DEVICE_INDEX_ANALOG_RIGHT, RETRO_DEVICE_ID_ANALOG_Y, 1);
        analog("CLeft", RETRO_DEVICE_INDEX_ANALOG_RIGHT, RETRO_DEVICE_ID_ANALOG_X, -1);
        analog("CRight", RETRO_DEVICE_INDEX_ANALOG_RIGHT, RETRO_DEVICE_ID_ANALOG_X, 1);
    } else {
        const std::pair<const char *, unsigned> std_buttons[] = {
            {"Up", RETRO_DEVICE_ID_JOYPAD_UP}, {"Down", RETRO_DEVICE_ID_JOYPAD_DOWN},
            {"Left", RETRO_DEVICE_ID_JOYPAD_LEFT}, {"Right", RETRO_DEVICE_ID_JOYPAD_RIGHT},
            {"A", RETRO_DEVICE_ID_JOYPAD_A}, {"B", RETRO_DEVICE_ID_JOYPAD_B}, {"X", RETRO_DEVICE_ID_JOYPAD_X},
            {"Y", RETRO_DEVICE_ID_JOYPAD_Y}, {"L", RETRO_DEVICE_ID_JOYPAD_L}, {"R", RETRO_DEVICE_ID_JOYPAD_R},
            {"L2", RETRO_DEVICE_ID_JOYPAD_L2}, {"R2", RETRO_DEVICE_ID_JOYPAD_R2}, {"L3", RETRO_DEVICE_ID_JOYPAD_L3},
            {"R3", RETRO_DEVICE_ID_JOYPAD_R3}, {"Start", RETRO_DEVICE_ID_JOYPAD_START},
            {"Select", RETRO_DEVICE_ID_JOYPAD_SELECT},
        };
        for (const auto &b : std_buttons) button(b.first, b.second);
    }
    if (key == "gc") {
        // GameCube pads have no L1, Select or stick clicks (Dolphin uses them only for Triforce arcade boards).
        for (const char *b : {"L", "Select", "L3", "R3"}) targets_.erase(b);
    }
    if (key == "3ds") {
        // The 3DS has no stick clicks. Azahar swaps the screens itself on L3 (citra_libretro.cpp retro_run), behind
        // the player's back; here the left stick click is Flip instead (handle_event), so the menu stays right.
        targets_.erase("L3");
    }
    auto stick = [&](const std::string &prefix, unsigned index) {
        analog((prefix + "Up").c_str(), index, RETRO_DEVICE_ID_ANALOG_Y, -1);
        analog((prefix + "Down").c_str(), index, RETRO_DEVICE_ID_ANALOG_Y, 1);
        analog((prefix + "Left").c_str(), index, RETRO_DEVICE_ID_ANALOG_X, -1);
        analog((prefix + "Right").c_str(), index, RETRO_DEVICE_ID_ANALOG_X, 1);
    };
    if (console.analogue_stick) stick("Stick", RETRO_DEVICE_INDEX_ANALOG_LEFT);
    if (console.right_stick) stick("RStick", RETRO_DEVICE_INDEX_ANALOG_RIGHT);

    Names keys = key == "n64" ? kN64Keys : kDefaultKeys;
    Names pad = key == "n64" ? kN64Pad : console.analogue_stick ? merged(kDefaultPad, kStickPad) : kDefaultPad;
    if (console.right_stick) {
        keys = merged(keys, kRightStickKeys);
        pad = merged(pad, kRightStickPad);
    }
    if (key == "gc" || key == "wii") pad = merged(pad, kGcPad);
    // P4b (PS2, D-8 kept): "Use the right trigger as a pressure-sensitive Cross" for the few games that want a light
    // press (accelerating, aiming). The right trigger then presses Cross (B) with its real level, as well as the
    // bottom button; R2 moves to the right stick click (R3 then has no button). Only where buttons have levels.
    pressure_cross_ = console.analog_buttons && controls.is_object() && controls.value("pressureCross", false);
    // D246 / D247: Settings → Display, kept on this PC; both on unless the shell says false.
    auto switch_on = [&](const char *name) {
        return !(controls.is_object() && controls.contains(name) && controls[name].is_boolean() && !controls[name].get<bool>());
    };
    rumble_on_ = switch_on("rumble");
    pad_shortcuts_ = switch_on("padShortcuts");
    Names plain = pad, crossed = pad;
    crossed["B"].push_back("r2");
    crossed["R2"] = {"r3"};
    crossed.erase("R3");
    if (pressure_cross_) pad = crossed;
    // Review S7: a saved controller map holds the whole map, so its B / R2 / R3 are what the pressure-cross setting
    // made them when it was saved. Those entries, as either default has them, are not the person's own change: the
    // setting as it is now decides them (else turning it off later leaves B on the trigger, or on does nothing).
    auto setting_made = [&](const std::string &name, const std::vector<std::string> &v) {
        if (name != "B" && name != "R2" && name != "R3") return false;
        auto p = plain.find(name), c = crossed.find(name);
        return (p != plain.end() && p->second == v) || (c != crossed.end() && c->second == v);
    };
    // Only this console's buttons are kept (a saved map may name buttons another console has).
    auto own = [&](const Names &in) {
        Names out;
        for (const auto &kv : in)
            if (targets_.count(kv.first)) out[kv.first] = kv.second;
        return out;
    };
    default_keys_ = own(keys);
    pad_models_.clear();
    if (controls.is_object()) {
        auto apply = [&](const json &from, Names &into, bool is_pad) {
            if (!from.is_object()) return;
            for (auto it = from.begin(); it != from.end(); ++it) {
                if (!targets_.count(it.key())) continue;
                auto v = as_list(it.value());
                if (is_pad && console.analog_buttons && setting_made(it.key(), v)) continue;
                into[it.key()] = v;
            }
        };
        if (controls.contains("keyboard")) apply(controls["keyboard"], keys, false);
        if (controls.contains("gamepad")) apply(controls["gamepad"], pad, true);
        // Each controller model's own buttons (changed in the menu; the phone's per-model maps, D126).
        if (controls.contains("gamepads") && controls["gamepads"].is_object())
            for (auto it = controls["gamepads"].begin(); it != controls["gamepads"].end(); ++it) {
                Names m = pad;
                apply(it.value(), m, true);
                pad_models_[it.key()] = own(m);
            }
    }
    key_names_ = own(keys);
    pad_base_ = own(pad);
    default_hot_.clear();
    for (const auto &h : kHotkeys)
        if (h.second.first != Hotkey::Flip || key == "3ds") default_hot_[h.first] = h.second.second;
    hot_names_ = default_hot_;
    json hk = controls.is_object() && controls.contains("hotkeys") ? controls["hotkeys"] : json::object();
    if (hk.is_object())
        for (auto it = hk.begin(); it != hk.end(); ++it)
            if (default_hot_.count(it.key()) && it.key() != "menu") hot_names_[it.key()] = as_list(it.value()); // Esc stays the menu
    compile_keys();
    for (auto &p : pads_) compile_pad(p);
}

void Input::compile_keys() {
    keys_.clear();
    for (const auto &kv : key_names_)
        for (const auto &code : kv.second) {
            SDL_Scancode sc = scancode_from_code(code);
            if (sc == SDL_SCANCODE_UNKNOWN) LOGW("controls: unknown key \"%s\" for %s", code.c_str(), kv.first.c_str());
            else keys_[kv.first].push_back(sc);
        }
    hotkeys_.clear();
    hotkey_scancode_.assign(SDL_SCANCODE_COUNT, false);
    for (const auto &h : kHotkeys) {
        auto it = hot_names_.find(h.first);
        if (it == hot_names_.end()) continue;
        for (const auto &code : it->second) {
            SDL_Scancode sc = scancode_from_code(code);
            if (sc == SDL_SCANCODE_UNKNOWN) continue;
            hotkeys_[h.second.first].push_back(sc);
            hotkey_scancode_[sc] = true;
        }
    }
}

Input::Names Input::pad_names_for(const std::string &model) const {
    auto it = pad_models_.find(model);
    return it != pad_models_.end() ? it->second : pad_base_;
}

void Input::compile_pad(Pad &p) {
    p.names.clear();
    p.map.clear();
    for (const auto &kv : pad_names_for(p.model)) {
        if (!targets_.count(kv.first)) continue;
        for (const auto &name : kv.second) {
            int kind = 0, code = 0;
            if (!pad_source(name, kind, code)) {
                LOGW("controls: unknown controller input \"%s\" for %s", name.c_str(), kv.first.c_str());
                continue;
            }
            p.map[kv.first].push_back(PadSource{(PadSource::Kind)kind, code});
            p.names[kv.first].push_back(name);
        }
    }
}

namespace {
/** D247: one of the four shortcut inputs as it is now, 0-1 (the physical button or trigger). */
float combo_level(SDL_Gamepad *pad, PadCombo::Part part) {
    switch (part) {
    case PadCombo::L1: return SDL_GetGamepadButton(pad, SDL_GAMEPAD_BUTTON_LEFT_SHOULDER) ? 1.0f : 0.0f;
    case PadCombo::R1: return SDL_GetGamepadButton(pad, SDL_GAMEPAD_BUTTON_RIGHT_SHOULDER) ? 1.0f : 0.0f;
    case PadCombo::L2: return SDL_GetGamepadAxis(pad, SDL_GAMEPAD_AXIS_LEFT_TRIGGER) / 32767.0f;
    case PadCombo::R2: return SDL_GetGamepadAxis(pad, SDL_GAMEPAD_AXIS_RIGHT_TRIGGER) / 32767.0f;
    default: return 0.0f;
    }
}
} // namespace

Hotkey Input::pad_combo(SDL_JoystickID id, PadCombo::Part part, float level) {
    for (auto &p : pads_)
        if (p.id == id) return p.combo.update(part, level, SDL_GetGamepadButton(p.pad, SDL_GAMEPAD_BUTTON_BACK), pad_shortcuts_);
    return Hotkey::None;
}

bool Input::kept_from_game(const Pad &pad, const PadSource &src) {
    if (!pad.combo.swallowed) return false;
    if (src.kind == PadSource::Button)
        return (src.code == SDL_GAMEPAD_BUTTON_LEFT_SHOULDER && pad.combo.kept_from_game(PadCombo::L1)) ||
               (src.code == SDL_GAMEPAD_BUTTON_RIGHT_SHOULDER && pad.combo.kept_from_game(PadCombo::R1));
    if (src.kind == PadSource::AxisPos)
        return (src.code == SDL_GAMEPAD_AXIS_LEFT_TRIGGER && pad.combo.kept_from_game(PadCombo::L2)) ||
               (src.code == SDL_GAMEPAD_AXIS_RIGHT_TRIGGER && pad.combo.kept_from_game(PadCombo::R2));
    return false;
}

void Input::add_pad(SDL_JoystickID id) {
    for (const auto &p : pads_)
        if (p.id == id) return;
    SDL_Gamepad *g = SDL_OpenGamepad(id);
    if (!g) return;
    // First free player slot (N64: each controller its own player; others: all play as player 1).
    int slot = 0;
    while (slot < kPorts && std::any_of(pads_.begin(), pads_.end(), [&](const Pad &p) { return p.slot == slot; })) slot++;
    Pad p;
    p.pad = g, p.id = id, p.slot = std::min(slot, kPorts - 1);
    const char *name = SDL_GetGamepadName(g);
    p.name = name && *name ? name : "Controller";
    // One map per controller model (the phone's rule, D126): by its USB vendor and product, else by its name.
    Uint16 vendor = SDL_GetGamepadVendor(g), product = SDL_GetGamepadProduct(g);
    if (vendor || product) {
        char buf[16];
        std::snprintf(buf, sizeof buf, "%04x:%04x", vendor, product);
        p.model = buf;
    } else {
        p.model = p.name;
    }
    compile_pad(p);
    pads_.push_back(p);
    LOGI("controller connected: %s [%s] (player %d)", p.name.c_str(), p.model.c_str(), players_ > 1 ? slot + 1 : 1);
}

const Input::Pad *Input::find_pad(SDL_JoystickID id) const {
    for (const auto &p : pads_)
        if (p.id == id) return &p;
    return nullptr;
}

bool Input::pad_info(SDL_JoystickID id, PadInfo &out) const {
    const Pad *p = find_pad(id);
    if (!p) return false;
    out = PadInfo{p->id, p->model, p->name};
    return true;
}

bool Input::shown_pad(PadInfo &out) const {
    if (pads_.empty()) return false;
    if (shown_pad_ && pad_info(shown_pad_, out)) return true;
    return pad_info(pads_.back().id, out);
}

void Input::remove_pad(SDL_JoystickID id) {
    for (auto it = pads_.begin(); it != pads_.end(); ++it)
        if (it->id == id) {
            SDL_CloseGamepad(it->pad);
            pads_.erase(it);
            LOGI("controller disconnected");
            return;
        }
}

void Input::close() {
    for (auto &p : pads_) SDL_CloseGamepad(p.pad);
    pads_.clear();
}

Hotkey Input::handle_event(const SDL_Event &e) {
    if (e.type == SDL_EVENT_GAMEPAD_BUTTON_DOWN) shown_pad_ = e.gbutton.which; // the Controls list shows this one
    if (e.type == SDL_EVENT_GAMEPAD_ADDED) add_pad(e.gdevice.which);
    else if (e.type == SDL_EVENT_GAMEPAD_REMOVED) remove_pad(e.gdevice.which);
    else if (e.type == SDL_EVENT_GAMEPAD_BUTTON_DOWN && console_ && std::string(console_->key) == "3ds" &&
             e.gbutton.button == SDL_GAMEPAD_BUTTON_LEFT_STICK)
        return Hotkey::Flip;
    else if ((e.type == SDL_EVENT_GAMEPAD_BUTTON_DOWN || e.type == SDL_EVENT_GAMEPAD_BUTTON_UP) &&
             (e.gbutton.button == SDL_GAMEPAD_BUTTON_LEFT_SHOULDER || e.gbutton.button == SDL_GAMEPAD_BUTTON_RIGHT_SHOULDER))
        return pad_combo(e.gbutton.which, e.gbutton.button == SDL_GAMEPAD_BUTTON_LEFT_SHOULDER ? PadCombo::L1 : PadCombo::R1,
                         e.gbutton.down ? 1.0f : 0.0f);
    else if (e.type == SDL_EVENT_GAMEPAD_AXIS_MOTION &&
             (e.gaxis.axis == SDL_GAMEPAD_AXIS_LEFT_TRIGGER || e.gaxis.axis == SDL_GAMEPAD_AXIS_RIGHT_TRIGGER))
        return pad_combo(e.gaxis.which, e.gaxis.axis == SDL_GAMEPAD_AXIS_LEFT_TRIGGER ? PadCombo::L2 : PadCombo::R2,
                         std::max(0, (int)e.gaxis.value) / 32767.0f);
    else if (e.type == SDL_EVENT_KEY_DOWN && !e.key.repeat) {
        if (e.key.scancode == SDL_SCANCODE_RETURN && (e.key.mod & SDL_KMOD_ALT)) return Hotkey::Fullscreen;
        for (const auto &kv : hotkeys_) {
            if (kv.first == Hotkey::FastForward || kv.first == Hotkey::Rewind) continue; // held, not pressed
            for (auto sc : kv.second)
                if (sc == e.key.scancode) return kv.first;
        }
    }
    return Hotkey::None;
}

bool Input::held(Hotkey h) const {
    auto it = hotkeys_.find(h);
    if (it == hotkeys_.end()) return false;
    const bool *ks = SDL_GetKeyboardState(nullptr);
    for (auto sc : it->second)
        if (ks && ks[sc]) return true;
    return false;
}

float Input::key_level(const std::vector<SDL_Scancode> &keys, const bool *ks) const {
    for (auto sc : keys)
        if (ks[sc] && !hotkey_scancode_[sc]) return 1.0f;
    return 0.0f;
}

float Input::pad_level(const std::vector<PadSource> &srcs, const Pad &p, bool analog) const {
    SDL_Gamepad *pad = p.pad;
    float best = 0;
    for (const auto &s : srcs) {
        if (kept_from_game(p, s)) continue; // D247: pressed for a controller shortcut
        float v = 0;
        if (s.kind == PadSource::Button) v = SDL_GetGamepadButton(pad, (SDL_GamepadButton)s.code) ? 1.0f : 0.0f;
        else {
            float a = SDL_GetGamepadAxis(pad, (SDL_GamepadAxis)s.code) / 32767.0f;
            v = s.kind == PadSource::AxisPos ? std::max(0.0f, a) : std::max(0.0f, -a);
            // play.js: below 0.1 a stick sends 0 (the core adds its own dead zone). As a button: half way.
            if (analog) v = v < 0.1f ? 0 : std::min(1.0f, v);
            else v = v >= 0.5f ? 1.0f : 0.0f;
        }
        best = std::max(best, v);
    }
    return best;
}

void Input::poll(SDL_FRect game, bool window_focused) {
    const bool *ks = SDL_GetKeyboardState(nullptr);
    // D247: a shoulder or trigger let go while the menu had the events is the game's again (and can fire again).
    for (auto &pad : pads_)
        for (int i = 0; i < PadCombo::Parts; i++) {
            if (!pad.combo.down[i]) continue;
            auto part = (PadCombo::Part)i;
            float level = combo_level(pad.pad, part);
            if (level < PadCombo::kRelease) pad.combo.update(part, level, false, false);
        }
    if (blocked_) {
        // The key or button that closed the menu (or picked in it) must not reach the game.
        bool any = (SDL_GetMouseState(nullptr, nullptr) & SDL_BUTTON_LMASK) != 0;
        for (const auto &k : keys_)
            for (auto sc : k.second) any |= ks[sc];
        for (const auto &pad : pads_)
            for (const auto &p : pad.map) any |= pad_level(p.second, pad, false) >= 0.5f;
        for (int sc = 0; sc < SDL_SCANCODE_COUNT && !any; sc++) any |= ks[sc];
        if (any) {
            ports_ = {};
            ptr_down_ = false;
            return;
        }
        blocked_ = false;
    }
    for (int p = 0; p < kPorts; p++) {
        Port &port = ports_[p];
        port = Port{};
        float acc[2][2] = {{0, 0}, {0, 0}};
        for (const auto &t : targets_) {
            bool analog = t.second.kind == Target::Analog;
            float key = 0; // keys are all or nothing (1 or 0)
            if (p == 0 && window_focused) {
                auto k = keys_.find(t.first);
                if (k != keys_.end()) key = key_level(k->second, ks);
            }
            float level = key;
            for (const auto &pad : pads_) {
                auto ps = pad.map.find(t.first);
                if (port_of(pad) == p && ps != pad.map.end()) level = std::max(level, pad_level(ps->second, pad, analog));
            }
            if (analog) acc[t.second.stick][t.second.axis] += level * t.second.sign;
            else if (level >= 0.5f) port.buttons |= (uint16_t)(1u << t.second.id);
            if (!analog && console_ && console_->analog_buttons && t.second.id < 16) {
                // P4: the trigger's real level. Keys and real buttons are all or nothing; a trigger (an axis) "clicks"
                // its digital button only near the end of its travel on GameCube/Wii (Dolphin's own threshold is
                // 90%: trigger_click), so a half press is a half press, not a full one. PS2 L2/R2: half way.
                bool hard = key >= 0.5f;
                float a = hard ? 1.0f : 0.0f;
                for (const auto &pad : pads_) {
                    auto ps = pad.map.find(t.first);
                    if (port_of(pad) != p || ps == pad.map.end()) continue;
                    for (const auto &src : ps->second)
                        if (src.kind == PadSource::Button && !kept_from_game(pad, src) &&
                            SDL_GetGamepadButton(pad.pad, (SDL_GamepadButton)src.code))
                            hard = true;
                    a = std::max(a, pad_level(ps->second, pad, true));
                }
                int16_t v = (int16_t)std::lround(std::clamp(a, 0.0f, 1.0f) * 0x7fff);
                port.button_level[t.second.id] = std::max(port.button_level[t.second.id], v);
                if (hard || a >= console_->trigger_click) port.buttons |= (uint16_t)(1u << t.second.id);
                else port.buttons &= (uint16_t)~(1u << t.second.id);
            }
        }
        port.buttons |= forced_[p];
        if (console_ && console_->analog_buttons)
            for (int b = 0; b < 16; b++)
                if (forced_[p] & (1u << b)) port.button_level[b] = 0x7fff;
        for (int s = 0; s < 2; s++)
            for (int a = 0; a < 2; a++)
                port.analog[s][a] = (int16_t)std::lround(std::clamp(acc[s][a], -1.0f, 1.0f) * 0x7fff);
    }
    // Mouse = stylus / pointer over the game picture.
    float mx = 0, my = 0;
    SDL_MouseButtonFlags mb = SDL_GetMouseState(&mx, &my);
    if (mouse_forced_) mx = mouse_x_, my = mouse_y_, mb = mouse_down_ ? SDL_BUTTON_LMASK : 0, window_focused = true;
    bool inside = game.w > 0 && game.h > 0 && mx >= game.x && mx < game.x + game.w && my >= game.y &&
                  my < game.y + game.h;
    if (game.w > 0 && game.h > 0) {
        float nx = std::clamp((mx - game.x) / game.w, 0.0f, 1.0f), ny = std::clamp((my - game.y) / game.h, 0.0f, 1.0f);
        ptr_x_ = (int16_t)std::lround(nx * 0xfffe - 0x7fff);
        ptr_y_ = (int16_t)std::lround(ny * 0xfffe - 0x7fff);
    }
    // A press that began on the ☰ button is the button's, not a touch on the game, until it's let go.
    if (pointer_blocked_ && !(mb & SDL_BUTTON_LMASK)) pointer_blocked_ = false;
    ptr_down_ = window_focused && inside && (mb & SDL_BUTTON_LMASK) && !pointer_blocked_;
}

int16_t Input::state(unsigned port, unsigned device, unsigned index, unsigned id) const {
    if (port >= (unsigned)kPorts) return 0;
    const Port &p = ports_[port];
    switch (device) {
    case RETRO_DEVICE_JOYPAD:
        if (id == RETRO_DEVICE_ID_JOYPAD_MASK) return (int16_t)p.buttons;
        return id < 16 ? (int16_t)((p.buttons >> id) & 1) : 0;
    case RETRO_DEVICE_ANALOG:
        if (index < 2 && id < 2) return p.analog[index][id];
        if (index == RETRO_DEVICE_INDEX_ANALOG_BUTTON && id < 16 && console_ && console_->analog_buttons)
            return p.button_level[id]; // P4 (GameCube L/R, PS2 pressure); 0 for every other console, as before
        return 0;
    case RETRO_DEVICE_POINTER:
        if (port != 0 || index != 0) return 0;
        if (id == RETRO_DEVICE_ID_POINTER_X) return seen_.x = ptr_x_;
        if (id == RETRO_DEVICE_ID_POINTER_Y) return seen_.y = ptr_y_;
        if (id == RETRO_DEVICE_ID_POINTER_PRESSED) {
            seen_.down = ptr_down_;
            if (ptr_down_) seen_.pressed_reads++;
            return ptr_down_ ? 1 : 0;
        }
        if (id == RETRO_DEVICE_ID_POINTER_COUNT) return ptr_down_ ? 1 : 0;
        return 0;
    default:
        return 0;
    }
}

bool Input::rumble(unsigned port, unsigned effect, uint16_t strength) {
    // D246: Controller rumble off in Settings = the core is told no and no controller is asked to shake. The N64
    // never rumbles (Controller Pak 1 holds the save, paks 2-4 none: forced_options.cpp); this keeps it so.
    if (!rumble_on_ || (console_ && std::string(console_->key) == "n64")) return false;
    bool any = false;
    for (auto &pad : pads_) {
        if ((unsigned)port_of(pad) != port) continue;
        if (effect == RETRO_RUMBLE_STRONG) pad.strong = strength;
        else pad.weak = strength;
        // Cores re-send the level while it lasts; 250 ms covers a missed update without buzzing on forever.
        any |= SDL_RumbleGamepad(pad.pad, pad.strong, pad.weak, 250);
    }
    return any;
}

namespace {
std::string key_name(SDL_Scancode sc) {
    switch (sc) {
    case SDL_SCANCODE_RETURN: return "Enter";
    case SDL_SCANCODE_UP: return "Up arrow";
    case SDL_SCANCODE_DOWN: return "Down arrow";
    case SDL_SCANCODE_LEFT: return "Left arrow";
    case SDL_SCANCODE_RIGHT: return "Right arrow";
    case SDL_SCANCODE_GRAVE: return "` (under Esc)";
    case SDL_SCANCODE_ESCAPE: return "Esc";
    default: break;
    }
    const char *n = SDL_GetScancodeName(sc);
    return n && *n ? n : "?";
}

/** Controller inputs by position (so they read the same on Xbox, PlayStation and Switch pads). */
std::string pad_name(const std::string &raw) {
    static const std::map<std::string, std::string> names = {
        {"south", "bottom button"}, {"east", "right button"}, {"west", "left button"}, {"north", "top button"},
        {"l1", "left shoulder"}, {"r1", "right shoulder"}, {"l2", "left trigger"}, {"r2", "right trigger"},
        {"select", "Back / Select"}, {"start", "Start"}, {"l3", "left stick click"}, {"r3", "right stick click"},
        {"up", "D-pad up"}, {"down", "D-pad down"}, {"left", "D-pad left"}, {"right", "D-pad right"},
        {"home", "Guide button"}, {"a0-", "left stick left"}, {"a0+", "left stick right"}, {"a1-", "left stick up"},
        {"a1+", "left stick down"}, {"a2-", "right stick left"}, {"a2+", "right stick right"},
        {"a3-", "right stick up"}, {"a3+", "right stick down"},
    };
    std::string n = raw;
    standard_button(n);
    auto it = names.find(n);
    return it != names.end() ? it->second : raw;
}

std::string join(const std::vector<std::string> &parts, const char *sep = ", ") {
    std::string out;
    for (const auto &p : parts) out += (out.empty() ? "" : sep) + p;
    return out;
}
} // namespace

namespace {
const char *kButtonOrder[] = {"Up", "Down", "Left", "Right", "StickUp", "StickDown", "StickLeft", "StickRight",
                              "RStickUp", "RStickDown", "RStickLeft", "RStickRight",
                              "A", "B", "X", "Y", "Z", "L", "R", "L2", "R2", "L3", "R3", "CUp", "CDown",
                              "CLeft", "CRight", "Start", "Select"};
// play-ui.js HOTKEYS words, plus Rewind and the menu (Hotkey order = the list's order).
const std::vector<std::pair<std::string, const char *>> kHotkeyWords = {
    {"menu", "Menu"}, {"pause", "Pause"}, {"ff", "Fast-forward (hold)"}, {"rewind", "Rewind (hold, when on)"},
    {"quickSave", "Quick snapshot (slot 1)"}, {"quickLoad", "Load snapshot (slot 1)"}, {"screenshot", "Screenshot"},
    {"fullscreen", "Full screen"}, {"flip", "Flip the 3DS screens"}};

Hotkey hotkey_of(const std::string &name) {
    for (const auto &h : kHotkeys)
        if (h.first == name) return h.second.first;
    return Hotkey::None;
}
} // namespace

std::string Input::button_words(const std::string &b) const {
    static const std::map<std::string, std::string> common = {
        {"StickUp", "Stick up"}, {"StickDown", "Stick down"}, {"StickLeft", "Stick left"}, {"StickRight", "Stick right"},
        {"RStickUp", "Right stick up"}, {"RStickDown", "Right stick down"}, {"RStickLeft", "Right stick left"},
        {"RStickRight", "Right stick right"}, {"CUp", "C up"}, {"CDown", "C down"}, {"CLeft", "C left"},
        {"CRight", "C right"}};
    // The console's own names where they differ from the button's (Dolphin libretro Input.cpp descGC / descWiimote).
    static const std::map<std::string, std::map<std::string, std::string>> by_console = {
        {"gc", {{"R", "Z"}, {"L2", "L (trigger)"}, {"R2", "R (trigger)"}, {"RStickUp", "C-stick up"},
                {"RStickDown", "C-stick down"}, {"RStickLeft", "C-stick left"}, {"RStickRight", "C-stick right"}}},
        {"wii", {{"X", "1"}, {"Y", "2"}, {"Start", "+"}, {"Select", "-"}, {"R3", "Home"}, {"R2", "Shake"}}},
        {"ps2", {{"A", "Circle"}, {"B", "Cross"}, {"X", "Triangle"}, {"Y", "Square"}, {"L", "L1"}, {"R", "R1"}}},
    };
    if (console_) {
        auto bc = by_console.find(console_->key);
        if (bc != by_console.end()) {
            auto w = bc->second.find(b);
            if (w != bc->second.end()) return w->second;
        }
    }
    auto w = common.find(b);
    return w != common.end() ? w->second : b;
}

std::string Input::shortcut_words(const std::string &name) const {
    for (const auto &h : kHotkeyWords)
        if (h.first == name) return h.second;
    return name;
}

std::vector<Input::ControlText> Input::describe_buttons() const {
    Names pad_names = pad_base_;
    PadInfo shown;
    if (shown_pad(shown)) {
        if (const Pad *p = find_pad(shown.id)) pad_names = p->names;
    }
    // The console's own buttons, as the browser's play.js BUTTONS and the phone's GameButton.forConsole (a GB has no
    // X or L2). Consoles not named here list every button the core reads.
    static const std::map<std::string, std::vector<std::string>> shown_by_console = {
        {"gb", {"A", "B", "Start", "Select"}},
        {"gbc", {"A", "B", "Start", "Select"}},
        {"gba", {"A", "B", "L", "R", "Start", "Select"}},
        {"nds", {"A", "B", "X", "Y", "L", "R", "Start", "Select"}},
        {"psx", {"X", "A", "B", "Y", "L", "R", "L2", "R2", "Start", "Select"}},
        {"psp", {"A", "B", "X", "Y", "L", "R", "Start", "Select"}},
    };
    auto only = console_ ? shown_by_console.find(console_->key) : shown_by_console.end();
    auto listed = [&](const std::string &b) {
        if (only == shown_by_console.end() || remap::is_direction(b)) return true; // D-pad (and PSP's stick) always
        return std::find(only->second.begin(), only->second.end(), b) != only->second.end();
    };
    std::vector<ControlText> out;
    for (const char *b : kButtonOrder) {
        if (!targets_.count(b) || !listed(b)) continue;
        std::vector<std::string> k, p;
        auto ki = keys_.find(b);
        if (ki != keys_.end())
            for (auto sc : ki->second) k.push_back(key_name(sc));
        auto pi = pad_names.find(b);
        if (pi != pad_names.end())
            for (const auto &n : pi->second) p.push_back(pad_name(n));
        // Every button is listed, even one with nothing on it now (it can be given a key again).
        out.push_back(ControlText{b, button_words(b), join(k), join(p)});
    }
    return out;
}

std::string Input::hotkey_keys(Hotkey h) const {
    auto it = hotkeys_.find(h);
    if (it == hotkeys_.end()) return "";
    std::vector<std::string> k;
    for (auto sc : it->second) k.push_back(key_name(sc));
    return join(k, " or ");
}

std::vector<Input::ControlText> Input::describe_hotkeys() const {
    std::vector<ControlText> out;
    for (const auto &h : kHotkeyWords) {
        if (!default_hot_.count(h.first)) continue; // Flip: the 3DS only
        Hotkey hk = hotkey_of(h.first);
        std::string k = hotkey_keys(hk);
        if (hk == Hotkey::Fullscreen) k += k.empty() ? "Alt+Enter" : " or Alt+Enter";
        std::string pad = hk == Hotkey::Menu ? "Guide button, or Start + Select together"
                          : hk == Hotkey::Flip ? "Left stick click" : "";
        if (pad_shortcuts_) { // D247: Back / Select held, then a shoulder or trigger
            if (hk == Hotkey::QuickSave) pad = "Hold Select, press right shoulder";
            if (hk == Hotkey::QuickLoad) pad = "Hold Select, press left shoulder";
            if (hk == Hotkey::FastForward) pad = "Hold Select, press right trigger (on / off)";
            if (hk == Hotkey::Screenshot) pad = "Hold Select, press left trigger";
        }
        out.push_back(ControlText{h.first, h.second, k, pad, hk == Hotkey::Menu});
    }
    return out;
}

// ---- Changing the buttons ----

std::string Input::code_of(SDL_Scancode sc) {
    // Every name scancode_from_code knows, the other way round (each scancode has one name).
    static const std::map<SDL_Scancode, std::string> back = [] {
        std::map<SDL_Scancode, std::string> back;
        for (const auto &kv : key_table()) back[kv.second] = kv.first;
        return back;
    }();
    auto it = back.find(sc);
    return it == back.end() ? "" : it->second;
}

std::string Input::pad_input_of_button(SDL_GamepadButton b) {
    switch (b) {
    case SDL_GAMEPAD_BUTTON_SOUTH: return "south";
    case SDL_GAMEPAD_BUTTON_EAST: return "east";
    case SDL_GAMEPAD_BUTTON_WEST: return "west";
    case SDL_GAMEPAD_BUTTON_NORTH: return "north";
    case SDL_GAMEPAD_BUTTON_LEFT_SHOULDER: return "l1";
    case SDL_GAMEPAD_BUTTON_RIGHT_SHOULDER: return "r1";
    case SDL_GAMEPAD_BUTTON_BACK: return "select";
    case SDL_GAMEPAD_BUTTON_START: return "start";
    case SDL_GAMEPAD_BUTTON_LEFT_STICK: return "l3";
    case SDL_GAMEPAD_BUTTON_RIGHT_STICK: return "r3";
    case SDL_GAMEPAD_BUTTON_DPAD_UP: return "up";
    case SDL_GAMEPAD_BUTTON_DPAD_DOWN: return "down";
    case SDL_GAMEPAD_BUTTON_DPAD_LEFT: return "left";
    case SDL_GAMEPAD_BUTTON_DPAD_RIGHT: return "right";
    default: return ""; // Guide always opens the menu; paddles and touchpads have no name the browser shares
    }
}

std::string Input::pad_input_of_axis(SDL_GamepadAxis a, bool positive) {
    switch (a) {
    case SDL_GAMEPAD_AXIS_LEFTX: return positive ? "a0+" : "a0-";
    case SDL_GAMEPAD_AXIS_LEFTY: return positive ? "a1+" : "a1-";
    case SDL_GAMEPAD_AXIS_RIGHTX: return positive ? "a2+" : "a2-";
    case SDL_GAMEPAD_AXIS_RIGHTY: return positive ? "a3+" : "a3-";
    case SDL_GAMEPAD_AXIS_LEFT_TRIGGER: return positive ? "l2" : "";
    case SDL_GAMEPAD_AXIS_RIGHT_TRIGGER: return positive ? "r2" : "";
    default: return "";
    }
}

std::string Input::key_words(const std::string &code) {
    SDL_Scancode sc = scancode_from_code(code);
    return sc == SDL_SCANCODE_UNKNOWN ? code : key_name(sc);
}

Input::Change Input::bind_key(const std::string &name, bool shortcut, const std::string &code) {
    Change c;
    if (code.empty() || code == "Escape" || scancode_from_code(code) == SDL_SCANCODE_UNKNOWN) return c; // Esc = menu
    if (shortcut ? (!default_hot_.count(name) || name == "menu") : !targets_.count(name)) return c;
    for (const auto &t : remap::assign_key(key_names_, hot_names_, shortcut, name, code))
        c.taken.push_back((t.shortcut ? shortcut_words(t.name) : button_words(t.name)) + (t.now_empty ? " (now has no key)" : ""));
    compile_keys();
    c.ok = true;
    c.what = shortcut ? shortcut_words(name) : button_words(name);
    c.input = key_words(code);
    return c;
}

Input::Change Input::bind_pad(SDL_JoystickID id, const std::string &button, const std::string &input) {
    Change c;
    const Pad *found = find_pad(id);
    int kind = 0, code = 0;
    if (!found || !targets_.count(button) || input == "home" || !pad_source(input, kind, code)) return c;
    std::string model = found->model;
    Names map = pad_names_for(model);
    for (const auto &t : remap::assign_pad(map, button, input))
        c.taken.push_back(button_words(t.name) + (t.now_empty ? " (now has no button)" : ""));
    pad_models_[model] = map;
    for (auto &p : pads_)
        if (p.model == model) compile_pad(p); // every controller of this model
    shown_pad_ = id;
    c.ok = true;
    c.what = button_words(button);
    c.input = pad_name(input);
    return c;
}

void Input::reset_keys() {
    // Shortcut keys taken by a game key go back too (they were only lost to it).
    key_names_ = default_keys_;
    for (const auto &kv : default_keys_)
        for (const auto &code : kv.second)
            for (auto &h : hot_names_) h.second.erase(std::remove(h.second.begin(), h.second.end(), code), h.second.end());
    compile_keys();
}

void Input::reset_hotkeys() {
    hot_names_ = default_hot_;
    for (const auto &kv : default_hot_)
        for (const auto &code : kv.second)
            for (auto &k : key_names_) k.second.erase(std::remove(k.second.begin(), k.second.end(), code), k.second.end());
    compile_keys();
}

bool Input::reset_pad(SDL_JoystickID id) {
    const Pad *found = find_pad(id);
    if (!found) return false;
    std::string model = found->model;
    pad_models_.erase(model);
    for (auto &p : pads_)
        if (p.model == model) compile_pad(p);
    return true;
}

json Input::keyboard_json() const {
    if (key_names_ == default_keys_) return nullptr;
    json o = json::object();
    for (const auto &kv : key_names_) o[kv.first] = kv.second;
    return o;
}

json Input::hotkeys_json() const {
    if (hot_names_ == default_hot_) return nullptr;
    json o = json::object();
    for (const auto &kv : hot_names_)
        if (kv.first != "menu") o[kv.first] = kv.second;
    return o;
}

json Input::pad_json(const std::string &model) const {
    auto it = pad_models_.find(model);
    if (it == pad_models_.end() || it->second == pad_base_) return nullptr;
    json o = json::object();
    for (const auto &kv : it->second) o[kv.first] = kv.second;
    return o;
}
bool Input::force_button(unsigned port, const std::string &name, bool on) {
    auto t = targets_.find(name);
    if (port >= (unsigned)kPorts || t == targets_.end() || t->second.kind != Target::Button) return false;
    if (on) forced_[port] |= (uint16_t)(1u << t->second.id);
    else forced_[port] &= (uint16_t)~(1u << t->second.id);
    return true;
}
} // namespace sc
