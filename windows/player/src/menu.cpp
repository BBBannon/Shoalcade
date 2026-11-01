/*
 * ShoalcadePlayer: the in-game menu's contents and controller navigation (part of Shoalcade).
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#include "menu.h"

#include <algorithm>
#include <cmath>

namespace sc {
namespace menu {
namespace {
const char *const SPEED_PREFIX = "speed.x";
const char *const CHEAT_PREFIX = "cheat.";

Item action(const std::string &id, const std::string &label, const std::string &hint = "") {
    Item i;
    i.kind = Kind::Action, i.id = id, i.label = label, i.hint = hint;
    return i;
}
Item toggle(const std::string &id, const std::string &label, bool on, const std::string &hint = "") {
    Item i;
    i.kind = Kind::Toggle, i.id = id, i.label = label, i.on = on, i.hint = hint;
    return i;
}
Item choice(const std::string &id, const std::string &label, bool selected) {
    Item i;
    i.kind = Kind::Choice, i.id = id, i.label = label, i.on = selected;
    return i;
}
Item heading(const std::string &id, const std::string &label) {
    Item i;
    i.kind = Kind::Heading, i.id = id, i.label = label;
    return i;
}
Item note(const std::string &id, const std::string &label) {
    Item i;
    i.kind = Kind::Note, i.id = id, i.label = label;
    return i;
}

const SlotInfo *slot_of(const Context &c, const std::string &slot) {
    for (const auto &s : c.slots)
        if (s.slot == slot) return &s;
    return nullptr;
}

Section game_section(const Context &c) {
    // F9: a game no longer in the library has nowhere to upload to, so it has no "and Upload".
    Section s{"game", GAME, {}};
    s.items.push_back(action(ID_RESUME, RESUME));
    s.items.push_back(action(ID_SCREENSHOT, SCREENSHOT));
    s.items.push_back(action(ID_CLOSE, CLOSE));
    if (c.can_upload) s.items.push_back(action(ID_CLOSE_UPLOAD, CLOSE_UPLOAD));
    return s;
}

/** Windows: Automatic + slots 1-3, as the browser (play-ui.js snapRow). Each slot: its card, then Save / Load. */
Section snapshot_section(const Context &c) {
    Section s{"snapshots", SNAPSHOTS, {}};
    for (const char *slot : {"auto", "1", "2", "3"}) {
        const SlotInfo *info = slot_of(c, slot);
        bool has = info && info->has;
        bool is_auto = std::string(slot) == "auto";
        Item card;
        card.kind = Kind::Slot, card.id = std::string("snap.slot.") + slot, card.label = slot_label(slot);
        if (!has) card.hint = is_auto ? "Taken when you leave the game" : "Empty";
        else {
            card.hint = info->when.empty() ? "Saved" : info->when;
            if (!info->loadable) card.hint += ". Made by a different emulator version, so it can't be loaded";
            card.picture = info->png;
        }
        if (info && !info->note.empty()) card.hint += ". " + info->note;
        s.items.push_back(card);
        if (!is_auto) {
            std::string id = save_id(slot);
            std::string hint = c.armed == id ? "Press again to replace " + slot_label(slot) + "." : "";
            s.items.push_back(action(id, SAVE_STATE, hint));
        }
        if (has && info->loadable) {
            std::string id = load_id(slot);
            std::string hint = c.armed == id ? "Press again to load. Play since then is lost unless the game saved." : "";
            s.items.push_back(action(id, LOAD_STATE, hint));
        }
    }
    return s;
}

Section speed_section(const Context &c) {
    Section s{"speed", SPEED, {}};
    if (c.max_ff > 1) {
        s.items.push_back(toggle(ID_FAST_FORWARD, FAST_FORWARD, c.ff_on, c.ff_key.empty() ? "" : "Or hold " + c.ff_key));
        s.items.push_back(heading("speed.choice", FF_SPEED));
        for (int n = 2; n <= std::min(4, c.max_ff); n++) s.items.push_back(choice(speed_id(n), speed_label(n), c.ff_speed == n));
    }
    if (c.rewind_possible) s.items.push_back(toggle(ID_REWIND, REWIND, c.rewind_on, c.rewind_key.empty() ? "" : "When on, hold " + c.rewind_key + " to go back"));
    return s;
}

/** D143: one switch per cheat. Adding and editing cheats is in Shoalcade's game page (the shell owns the list). */
Section cheats_section(const Context &c) {
    Section s{"cheats", CHEATS, {}};
    int on = (int)std::count_if(c.cheats.begin(), c.cheats.end(), [](const Cheat &x) { return x.on; });
    s.items.push_back(heading("cheats.count", cheats_on(on)));
    for (const auto &x : c.cheats) s.items.push_back(toggle(cheat_id(x.id), x.name.empty() ? x.code : x.name, x.on));
    if (!c.cheats.empty()) s.items.push_back(note("cheats.note", CHEATS_OFF_NOTE));
    return s;
}

Section sound_section(const Context &c) {
    Section s{"sound", SOUND, {}};
    s.items.push_back(toggle(ID_MUTE, APP_MUTE, c.muted, "Silence the game"));
    Item v;
    v.kind = Kind::Slider, v.id = ID_VOLUME, v.label = VOLUME, v.value = std::clamp(c.volume, 0, 100);
    s.items.push_back(v);
    return s;
}

Section display_section(const Context &c) {
    Section s{"display", DISPLAY, {}};
    s.items.push_back(heading("screen", SCREEN));
    s.items.push_back(choice(ID_WINDOW, "In a window", !c.fullscreen));
    s.items.push_back(choice(ID_FULLSCREEN, "Full screen", c.fullscreen));
    s.items.push_back(toggle(ID_INTEGER, "Whole-number scale", c.integer_scale, "Sharpest pixels; may leave a border"));
    s.items.push_back(toggle(ID_SMOOTH, "Smooth picture", c.smooth, "Softens the pixels"));
    if (c.console == "nds") {
        s.items.push_back(heading("ds", DS_SCREENS));
        for (const auto &l : ds_layouts()) s.items.push_back(choice(std::string("ds.") + l.key, l.label, c.ds == l.key));
        s.items.push_back(toggle(ID_DS_SWAP, DS_SWAP, c.ds_swap));
    } else if (c.console == "3ds") {
        // Game3dsActivity.displaySection: the four layouts and the phone's "Swap the two screens" (Flip).
        s.items.push_back(heading("3ds", N3DS_SCREENS));
        for (const auto &l : n3ds_layouts()) s.items.push_back(choice(std::string("3ds.") + l.key, l.label, c.n3ds == l.key));
        s.items.push_back(toggle(ID_3DS_FLIP, N3DS_FLIP, c.n3ds_flip, N3DS_FLIP_HINT));
    }
    return s;
}

Section controls_section(const Context &c) {
    Section s{"controls", CONTROLS, {}};
    auto line = [](const std::string &id, const ControlLine &l) {
        Item i;
        i.kind = Kind::Info, i.id = id, i.label = l.what;
        if (!l.keys.empty()) i.hint = "Keyboard: " + l.keys;
        if (!l.pad.empty()) i.hint += (i.hint.empty() ? "" : "\n") + std::string("Controller: ") + l.pad;
        return i;
    };
    if (!c.controls.empty()) s.items.push_back(heading("controls.game", "Game buttons"));
    for (size_t k = 0; k < c.controls.size(); k++) s.items.push_back(line("control." + std::to_string(k), c.controls[k]));
    if (!c.shortcuts.empty()) s.items.push_back(heading("controls.shortcuts", "Shortcuts"));
    for (size_t k = 0; k < c.shortcuts.size(); k++) s.items.push_back(line("shortcut." + std::to_string(k), c.shortcuts[k]));
    s.items.push_back(note("controls.later", "Changing the buttons comes in a later version."));
    return s;
}
} // namespace

std::string cheats_on(int n) {
    if (n == 0) return "No cheats on";
    if (n == 1) return "1 cheat on";
    return std::to_string(n) + " cheats on";
}
std::string speed_label(int n) { return std::to_string(n) + "\xC3\x97"; } // ×
std::string slot_label(const std::string &slot) { return slot == "auto" ? "Automatic" : "Slot " + slot; }
std::string speed_id(int n) { return SPEED_PREFIX + std::to_string(n); }
int speed_of(const std::string &id) {
    if (id.rfind(SPEED_PREFIX, 0) != 0 || id.size() != 8) return 0;
    int n = id[7] - '0';
    return n >= 2 && n <= 4 ? n : 0;
}
std::string cheat_id(const std::string &cheat) { return CHEAT_PREFIX + cheat; }
std::string save_id(const std::string &slot) { return "snap.save." + slot; }
std::string load_id(const std::string &slot) { return "snap.load." + slot; }

const std::vector<Layout> &ds_layouts() {
    static const std::vector<Layout> l = {{"side", "Side by side"}, {"stack", "Stacked"}, {"big", "One big, one small"}};
    return l;
}
const std::vector<Layout> &n3ds_layouts() {
    static const std::vector<Layout> l = {{"stacked", "Top and bottom"}, {"single", "One screen (Flip to swap)"},
                                          {"large", "Big screen, small screen"}, {"side", "Side by side"}};
    return l;
}

bool needs_confirm(const Context &c, const std::string &id) {
    if (id.rfind("snap.load.", 0) == 0) return true;
    if (id.rfind("snap.save.", 0) == 0) {
        const SlotInfo *s = slot_of(c, id.substr(10));
        return s && s->has; // replacing one
    }
    return false;
}

std::vector<Section> build(const Context &c) {
    // Order as the phone (GameMenu.SECTIONS): things done mid-game first, then Sound, Display, Controls.
    std::vector<Section> all;
    all.push_back(game_section(c));
    if (c.snapshots) all.push_back(snapshot_section(c));
    if (c.speed_allowed) all.push_back(speed_section(c));
    if (c.cheats_allowed) all.push_back(cheats_section(c));
    all.push_back(sound_section(c));
    all.push_back(display_section(c));
    all.push_back(controls_section(c));
    std::vector<Section> shown;
    for (auto &s : all)
        if (std::any_of(s.items.begin(), s.items.end(), [](const Item &i) { return i.selectable(); })) shown.push_back(std::move(s));
    return shown;
}

const Item *find(const std::vector<Section> &sections, const std::string &id) {
    for (const auto &s : sections)
        for (const auto &i : s.items)
            if (i.id == id) return &i;
    return nullptr;
}

bool nav_key_from_name(const std::string &n, NavKey &out) {
    if (n == "up") out = NavKey::Up;
    else if (n == "down") out = NavKey::Down;
    else if (n == "left") out = NavKey::Left;
    else if (n == "right") out = NavKey::Right;
    else if (n == "next" || n == "r1") out = NavKey::NextSection;
    else if (n == "previous" || n == "l1") out = NavKey::PreviousSection;
    else if (n == "pick" || n == "a") out = NavKey::Pick;
    else if (n == "back" || n == "b") out = NavKey::Back;
    else return false;
    return true;
}

std::string DrawerNav::focus() const { return at_ >= 0 && at_ < (int)rows_.size() ? rows_[at_].id : ""; }

void DrawerNav::set_sections(const std::vector<Section> &sections, bool reset_focus) {
    std::string was = focus();
    rows_.clear();
    for (size_t i = 0; i < sections.size(); i++)
        for (const auto &it : sections[i].items)
            if (it.selectable()) rows_.push_back(Row{(int)i, it.id, it.kind});
    at_ = 0;
    if (!reset_focus)
        for (size_t i = 0; i < rows_.size(); i++)
            if (rows_[i].id == was) at_ = (int)i;
}

DrawerNav::Result DrawerNav::key(NavKey k, bool repeat) {
    Result r;
    switch (k) {
    case NavKey::Up: return move(-1);
    case NavKey::Down: return move(1);
    case NavKey::NextSection: return repeat ? r : section(1);
    case NavKey::PreviousSection: return repeat ? r : section(-1);
    case NavKey::Left:
    case NavKey::Right:
        if (at_ < (int)rows_.size() && rows_[at_].kind == Kind::Slider) {
            r.type = Result::Adjust, r.id = rows_[at_].id, r.by = k == NavKey::Left ? -1 : 1;
        }
        return r;
    case NavKey::Pick:
        if (!repeat && !rows_.empty()) r.type = Result::Select, r.id = focus();
        return r;
    case NavKey::Back:
        if (!repeat) r.type = Result::Close;
        return r;
    }
    return r;
}

int DrawerNav::direction(float v, int now) {
    if (v <= -PUSH) return -1;
    if (v >= PUSH) return 1;
    if (std::fabs(v) < BACK) return 0;
    return now; // between the two: keep what it was, so a wobbling stick can't move twice
}

DrawerNav::Result DrawerNav::motion(float y) {
    int d = direction(y, held_);
    if (d == held_) return Result{};
    held_ = d;
    return d == 0 ? Result{} : move(d);
}

DrawerNav::Result DrawerNav::move(int by) {
    Result r;
    if (rows_.empty()) return r;
    int next = std::clamp(at_ + by, 0, (int)rows_.size() - 1);
    if (next == at_) return r;
    at_ = next;
    r.type = Result::Moved, r.id = focus();
    return r;
}

DrawerNav::Result DrawerNav::section(int by) {
    Result r;
    if (rows_.empty()) return r;
    std::vector<int> sections;
    for (const auto &row : rows_)
        if (sections.empty() || sections.back() != row.section) sections.push_back(row.section);
    int i = (int)(std::find(sections.begin(), sections.end(), rows_[at_].section) - sections.begin());
    int n = (int)sections.size();
    int target = sections[((i + by) % n + n) % n];
    int next = 0;
    while (rows_[next].section != target) next++;
    if (next == at_) return r;
    at_ = next;
    r.type = Result::Moved, r.id = focus();
    return r;
}
} // namespace menu
} // namespace sc
