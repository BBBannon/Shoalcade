/*
 * ShoalcadePlayer: changing which key or controller button presses a game button (part of Shoalcade).
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#include "remap.h"

#include <algorithm>

namespace sc {
namespace remap {
bool is_direction(const std::string &b) {
    static const char *dirs[] = {"Up", "Down", "Left", "Right"};
    for (const char *prefix : {"", "Stick", "RStick", "C"})
        for (const char *d : dirs)
            if (b == std::string(prefix) + d) return true;
    return false;
}

bool is_axis(const std::string &i) {
    return i.size() == 3 && i[0] == 'a' && i[1] >= '0' && i[1] <= '9' && (i[2] == '+' || i[2] == '-');
}

namespace {
/** Takes `input` off every entry of `map` except `keep`; adds each one that had it to `out`. */
void take_off(Names &map, const std::string &keep, const std::string &input, bool shortcut, std::vector<Taken> &out) {
    for (auto &kv : map) {
        if (kv.first == keep) continue;
        auto &list = kv.second;
        auto it = std::remove(list.begin(), list.end(), input);
        if (it == list.end()) continue;
        list.erase(it, list.end());
        out.push_back(Taken{shortcut, kv.first, list.empty()});
    }
}
} // namespace

std::vector<Taken> assign_key(Names &keys, Names &hotkeys, bool shortcut, const std::string &name, const std::string &code) {
    std::vector<Taken> out;
    take_off(keys, shortcut ? std::string() : name, code, false, out);
    take_off(hotkeys, shortcut ? name : std::string(), code, true, out);
    (shortcut ? hotkeys : keys)[name] = {code};
    return out;
}

std::vector<Taken> assign_pad(Names &pad, const std::string &button, const std::string &input) {
    std::vector<Taken> out;
    take_off(pad, button, input, false, out);
    std::vector<std::string> now = {input};
    if (is_direction(button))
        for (const auto &x : pad[button])
            if (is_axis(x) != is_axis(input) && x != input) now.push_back(x);
    pad[button] = now;
    return out;
}
} // namespace remap
} // namespace sc
