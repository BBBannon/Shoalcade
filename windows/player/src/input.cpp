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
    if (n.size() >= 2 && n[0] == 'b' && std::all_of(n.begin() + 1, n.end(), ::isdigit)) {
        size_t i = std::stoul(n.substr(1)); // browser "standard" mapping index
        if (i >= sizeof kPadButtons / sizeof *kPadButtons) return false;
        n = kPadButtons[i];
    }
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
} // namespace

SDL_Scancode scancode_from_code(const std::string &c) {
    static std::map<std::string, SDL_Scancode> table;
    if (table.empty()) {
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
    }
    auto it = table.find(c);
    return it == table.end() ? SDL_SCANCODE_UNKNOWN : it->second;
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
    if (key == "3ds") {
        // The 3DS has no stick clicks. Azahar swaps the screens itself on L3 (citra_libretro.cpp retro_run), behind
        // the player's back; here the left stick click is Flip instead (handle_event), so the menu stays right.
        targets_.erase("L3");
    }
    if (console.analogue_stick) {
        analog("StickUp", RETRO_DEVICE_INDEX_ANALOG_LEFT, RETRO_DEVICE_ID_ANALOG_Y, -1);
        analog("StickDown", RETRO_DEVICE_INDEX_ANALOG_LEFT, RETRO_DEVICE_ID_ANALOG_Y, 1);
        analog("StickLeft", RETRO_DEVICE_INDEX_ANALOG_LEFT, RETRO_DEVICE_ID_ANALOG_X, -1);
        analog("StickRight", RETRO_DEVICE_INDEX_ANALOG_LEFT, RETRO_DEVICE_ID_ANALOG_X, 1);
    }

    Names keys = key == "n64" ? kN64Keys : kDefaultKeys;
    Names pad = key == "n64" ? kN64Pad : console.analogue_stick ? merged(kDefaultPad, kStickPad) : kDefaultPad;
    if (controls.is_object()) {
        auto apply = [&](const char *field, Names &into) {
            if (!controls.contains(field) || !controls[field].is_object()) return;
            for (auto it = controls[field].begin(); it != controls[field].end(); ++it)
                if (targets_.count(it.key())) into[it.key()] = as_list(it.value());
        };
        apply("keyboard", keys);
        apply("gamepad", pad);
    }
    keys_.clear();
    pad_.clear();
    pad_names_.clear();
    for (const auto &kv : keys) {
        if (!targets_.count(kv.first)) continue;
        for (const auto &code : kv.second) {
            SDL_Scancode sc = scancode_from_code(code);
            if (sc == SDL_SCANCODE_UNKNOWN) LOGW("controls: unknown key \"%s\" for %s", code.c_str(), kv.first.c_str());
            else keys_[kv.first].push_back(sc);
        }
    }
    for (const auto &kv : pad) {
        if (!targets_.count(kv.first)) continue;
        for (const auto &name : kv.second) {
            int kind = 0, code = 0;
            if (!pad_source(name, kind, code)) {
                LOGW("controls: unknown controller input \"%s\" for %s", name.c_str(), kv.first.c_str());
                continue;
            }
            pad_[kv.first].push_back(PadSource{(PadSource::Kind)kind, code});
            pad_names_[kv.first].push_back(name);
        }
    }
    hotkeys_.clear();
    hotkey_scancode_.assign(SDL_SCANCODE_COUNT, false);
    json hk = controls.is_object() && controls.contains("hotkeys") ? controls["hotkeys"] : json::object();
    for (const auto &h : kHotkeys) {
        if (h.second.first == Hotkey::Flip && key != "3ds") continue;
        std::vector<std::string> names = h.second.second;
        if (hk.is_object() && hk.contains(h.first)) names = as_list(hk[h.first]);
        for (const auto &code : names) {
            SDL_Scancode sc = scancode_from_code(code);
            if (sc == SDL_SCANCODE_UNKNOWN) continue;
            hotkeys_[h.second.first].push_back(sc);
            hotkey_scancode_[sc] = true;
        }
    }
}

void Input::add_pad(SDL_JoystickID id) {
    for (const auto &p : pads_)
        if (p.id == id) return;
    SDL_Gamepad *g = SDL_OpenGamepad(id);
    if (!g) return;
    // First free player slot (N64: each controller its own player; others: all play as player 1).
    int slot = 0;
    while (slot < kPorts && std::any_of(pads_.begin(), pads_.end(), [&](const Pad &p) { return p.slot == slot; })) slot++;
    pads_.push_back(Pad{g, id, std::min(slot, kPorts - 1), 0, 0});
    const char *name = SDL_GetGamepadName(g);
    LOGI("controller connected: %s (player %d)", name ? name : "?", players_ > 1 ? slot + 1 : 1);
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
    if (e.type == SDL_EVENT_GAMEPAD_ADDED) add_pad(e.gdevice.which);
    else if (e.type == SDL_EVENT_GAMEPAD_REMOVED) remove_pad(e.gdevice.which);
    else if (e.type == SDL_EVENT_GAMEPAD_BUTTON_DOWN && console_ && std::string(console_->key) == "3ds" &&
             e.gbutton.button == SDL_GAMEPAD_BUTTON_LEFT_STICK)
        return Hotkey::Flip;
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

float Input::pad_level(const std::vector<PadSource> &srcs, SDL_Gamepad *pad, bool analog) const {
    float best = 0;
    for (const auto &s : srcs) {
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
    if (blocked_) {
        // The key or button that closed the menu (or picked in it) must not reach the game.
        bool any = (SDL_GetMouseState(nullptr, nullptr) & SDL_BUTTON_LMASK) != 0;
        for (const auto &k : keys_)
            for (auto sc : k.second) any |= ks[sc];
        for (const auto &p : pad_)
            for (const auto &pad : pads_) any |= pad_level(p.second, pad.pad, false) >= 0.5f;
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
            float level = 0;
            if (p == 0 && window_focused) {
                auto k = keys_.find(t.first);
                if (k != keys_.end()) level = key_level(k->second, ks);
            }
            auto ps = pad_.find(t.first);
            if (ps != pad_.end())
                for (const auto &pad : pads_) {
                    int pport = players_ > 1 ? pad.slot : 0;
                    if (pport == p) level = std::max(level, pad_level(ps->second, pad.pad, analog));
                }
            if (analog) acc[t.second.stick][t.second.axis] += level * t.second.sign;
            else if (level >= 0.5f) port.buttons |= (uint16_t)(1u << t.second.id);
        }
        port.buttons |= forced_[p];
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
    ptr_down_ = window_focused && inside && (mb & SDL_BUTTON_LMASK);
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
    bool any = false;
    for (auto &pad : pads_) {
        int pport = players_ > 1 ? pad.slot : 0;
        if ((unsigned)pport != port) continue;
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
    if (n.size() >= 2 && n[0] == 'b' && std::all_of(n.begin() + 1, n.end(), ::isdigit)) {
        size_t i = std::stoul(n.substr(1));
        if (i < sizeof kPadButtons / sizeof *kPadButtons) n = kPadButtons[i];
    }
    auto it = names.find(n);
    return it != names.end() ? it->second : raw;
}

std::string join(const std::vector<std::string> &parts, const char *sep = ", ") {
    std::string out;
    for (const auto &p : parts) out += (out.empty() ? "" : sep) + p;
    return out;
}
} // namespace

std::vector<Input::ControlText> Input::describe_buttons() const {
    static const char *order[] = {"Up", "Down", "Left", "Right", "StickUp", "StickDown", "StickLeft", "StickRight",
                                  "A", "B", "X", "Y", "Z", "L", "R", "L2", "R2", "L3", "R3", "CUp", "CDown",
                                  "CLeft", "CRight", "Start", "Select"};
    static const std::map<std::string, std::string> words = {
        {"StickUp", "Stick up"}, {"StickDown", "Stick down"}, {"StickLeft", "Stick left"}, {"StickRight", "Stick right"},
        {"CUp", "C up"}, {"CDown", "C down"}, {"CLeft", "C left"}, {"CRight", "C right"}};
    std::vector<ControlText> out;
    for (const char *b : order) {
        if (!targets_.count(b)) continue;
        std::vector<std::string> k, p;
        auto ki = keys_.find(b);
        if (ki != keys_.end())
            for (auto sc : ki->second) k.push_back(key_name(sc));
        auto pi = pad_names_.find(b);
        if (pi != pad_names_.end())
            for (const auto &n : pi->second) p.push_back(pad_name(n));
        if (k.empty() && p.empty()) continue;
        auto w = words.find(b);
        out.push_back(ControlText{w != words.end() ? w->second : b, join(k), join(p)});
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
    // play-ui.js HOTKEYS words, plus Rewind and the menu.
    const std::vector<std::pair<Hotkey, const char *>> list = {
        {Hotkey::Menu, "Menu"}, {Hotkey::Pause, "Pause"}, {Hotkey::FastForward, "Fast-forward (hold)"},
        {Hotkey::Rewind, "Rewind (hold, when on)"}, {Hotkey::QuickSave, "Quick snapshot (slot 1)"},
        {Hotkey::QuickLoad, "Load snapshot (slot 1)"}, {Hotkey::Screenshot, "Screenshot"},
        {Hotkey::Fullscreen, "Full screen"}, {Hotkey::Flip, "Flip the 3DS screens"}};
    std::vector<ControlText> out;
    for (const auto &h : list) {
        if (h.first == Hotkey::Flip && !(console_ && std::string(console_->key) == "3ds")) continue;
        std::string k = hotkey_keys(h.first);
        if (h.first == Hotkey::Fullscreen) k += k.empty() ? "Alt+Enter" : " or Alt+Enter";
        out.push_back(ControlText{h.second, k, h.first == Hotkey::Menu ? "Guide button, or Start + Select together"
                                               : h.first == Hotkey::Flip ? "Left stick click" : ""});
    }
    return out;
}

bool Input::force_button(unsigned port, const std::string &name, bool on) {
    auto t = targets_.find(name);
    if (port >= (unsigned)kPorts || t == targets_.end() || t->second.kind != Target::Button) return false;
    if (on) forced_[port] |= (uint16_t)(1u << t->second.id);
    else forced_[port] &= (uint16_t)~(1u << t->second.id);
    return true;
}
} // namespace sc
