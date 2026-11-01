/*
 * ShoalcadePlayer: the in-game menu's contents and controller navigation (part of Shoalcade).
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Pure C++ (no SDL, no ImGui), so it is unit tested. Mirrors the phone's GameMenu.kt: the same sections in the same
 * order (Game, Snapshots, Speed, Cheats, Sound, Display, Controls), the same words, the same item ids where the phone
 * has them, and DrawerNav's rules (Up / Down a row, L1 / R1 jump a section, A pick, B close, headings skipped).
 * Windows differences: snapshot slots 1-3 + Automatic (as the browser), Rewind, Volume, window / full screen,
 * whole-number scale and smooth picture, and a read-only Controls list. The drawer itself is menu_ui.cpp.
 */
#pragma once
#include <string>
#include <vector>

namespace sc {
namespace menu {
// Words (GameMenu.kt where the phone has them; browser play-ui.js for the snapshot slots).
inline const char *const TITLE = "Game menu";
inline const char *const CLOSE_MENU = "Close menu";
inline const char *const GAME = "Game";
inline const char *const RESUME = "Resume";
inline const char *const SCREENSHOT = "Screenshot";
inline const char *const CLOSE = "Close Game";
inline const char *const CLOSE_UPLOAD = "Close Game (and Upload)";
inline const char *const SNAPSHOTS = "Snapshots";
inline const char *const SAVE_STATE = "Save snapshot";
inline const char *const LOAD_STATE = "Load snapshot";
inline const char *const SPEED = "Speed";
inline const char *const FAST_FORWARD = "Fast-forward";
inline const char *const FF_SPEED = "Fast-forward speed";
inline const char *const REWIND = "Rewind";
inline const char *const CHEATS = "Cheats";
inline const char *const CHEATS_OFF_NOTE = "Switching a cheat off doesn't undo what it already changed.";
inline const char *const SOUND = "Sound";
inline const char *const APP_MUTE = "App Mute";
inline const char *const VOLUME = "Volume";
inline const char *const DISPLAY = "Display";
inline const char *const SCREEN = "Screen";
inline const char *const DS_SCREENS = "DS screens";
inline const char *const DS_SWAP = "Swap the two screens";
inline const char *const N3DS_SCREENS = "3DS screens";
/** The phone's 3DS menu uses GameMenu.DS_SWAP for Flip. */
inline const char *const N3DS_FLIP = "Swap the two screens";
inline const char *const N3DS_FLIP_HINT = "Flip: Tab, or click the left stick";
inline const char *const CONTROLS = "Controls";

// Item ids (stable: the player acts on these and the shell / tests send them, never the words).
inline const char *const ID_RESUME = "resume";
inline const char *const ID_SCREENSHOT = "screenshot";
inline const char *const ID_CLOSE = "close";
inline const char *const ID_CLOSE_UPLOAD = "close_upload";
inline const char *const ID_FAST_FORWARD = "speed.ff";
inline const char *const ID_REWIND = "speed.rewind";
inline const char *const ID_MUTE = "sound.mute";
inline const char *const ID_VOLUME = "sound.volume";
inline const char *const ID_WINDOW = "screen.window";
inline const char *const ID_FULLSCREEN = "screen.full";
inline const char *const ID_INTEGER = "display.integer";
inline const char *const ID_SMOOTH = "display.smooth";
inline const char *const ID_DS_SWAP = "ds.swap";
inline const char *const ID_3DS_FLIP = "3ds.flip";

enum class Kind {
    Action,  // does something, then the menu closes
    Choice,  // one of a group (radio button); the menu stays open
    Toggle,  // on / off switch; the menu stays open
    Slider,  // a number (Volume): Left / Right change it
    Info,    // a line you can move onto but not change (Controls list)
    Heading, // a label over a group; can't be picked
    Note,    // grey words; can't be picked
    Slot,    // a snapshot slot's picture, name and time; can't be picked (its Save / Load rows can)
};

struct Item {
    Kind kind = Kind::Action;
    std::string id, label, hint;
    bool on = false;      // Toggle on, Choice selected
    int value = 0;        // Slider (0-100)
    std::string picture;  // Slot: PNG path ("" = none)
    bool selectable() const { return kind != Kind::Heading && kind != Kind::Note && kind != Kind::Slot; }
};

struct Section {
    std::string id, title;
    std::vector<Item> items;
};

/** One snapshot slot as the menu shows it ("auto", "1", "2", "3"). */
struct SlotInfo {
    std::string slot;
    bool has = false;       // a snapshot is there
    bool loadable = true;   // false = made by a different emulator version (never loaded into another core)
    std::string when;       // "31/10/2026 18:45" (already worded by the shell, or by the player for its own saves)
    std::string png, path;  // picture and state file on this PC ("" = the player's own <statesDir>/slot<N>.*)
    std::string note;       // extra words from the shell (e.g. "Damaged on the server")
};

/** One cheat (the shell owns the list; the player only switches them on and off). */
struct Cheat {
    std::string id, name, code;
    bool on = true;
};

/** One line of the Controls list: a game button or shortcut, and what presses it. */
struct ControlLine {
    std::string what, keys, pad;
};

/** Everything the menu needs to decide what it shows (GameMenu.kt MenuContext). */
struct Context {
    std::string console; // server key: gb gbc gba nds psx n64 psp 3ds
    bool can_upload = true;
    bool snapshots = true;
    std::vector<SlotInfo> slots;
    bool speed_allowed = true;
    int max_ff = 4;
    bool ff_on = false;
    int ff_speed = 2;
    bool rewind_possible = false;
    bool rewind_on = false;
    bool cheats_allowed = true;
    std::vector<Cheat> cheats;
    bool muted = false;
    int volume = 100;
    bool fullscreen = false, integer_scale = false, smooth = false;
    std::string ds = "stack";
    bool ds_swap = false;
    std::string n3ds = "stacked";
    bool n3ds_flip = false;
    std::vector<ControlLine> controls, shortcuts;
    /** Names of the keys held for fast-forward and rewind (for the hints), e.g. "Space". */
    std::string ff_key, rewind_key;
    /** An item waiting for its second press (Load, Save over): its hint says so. */
    std::string armed;
};

/** "No cheats on", "1 cheat on", "3 cheats on" (GameMenu.cheatsOn). */
std::string cheats_on(int n);
/** "2×" (FastForward.speedLabel). */
std::string speed_label(int n);
/** "Automatic" / "Slot 2" (play-ui.js snapLabel). */
std::string slot_label(const std::string &slot);
std::string speed_id(int n);
int speed_of(const std::string &id); // 0 = not a speed choice
std::string cheat_id(const std::string &cheat);
std::string save_id(const std::string &slot);
std::string load_id(const std::string &slot);

/** The DS layouts (GameLayout.kt DsLayout): key and words. */
struct Layout {
    const char *key, *label;
};
const std::vector<Layout> &ds_layouts();
/** The 3DS layouts (Game3dsActivity.kt N3dsLayout). Shown for 3DS only. */
const std::vector<Layout> &n3ds_layouts();

/** Picking this id needs a second press (it would throw away play or replace a snapshot). */
bool needs_confirm(const Context &c, const std::string &id);

/** The sections shown for c: visible ones with at least one item that can be picked (GameMenu.sections). */
std::vector<Section> build(const Context &c);
/** The item with this id, or nullptr. */
const Item *find(const std::vector<Section> &sections, const std::string &id);

/** Keys the drawer understands, from the keyboard, a controller or the `menu.nav` message. */
enum class NavKey { Up, Down, Left, Right, NextSection, PreviousSection, Pick, Back };
bool nav_key_from_name(const std::string &name, NavKey &out);

/**
 * Controller navigation (GameMenu.kt DrawerNav): Up / Down move a row (no wrap), NextSection / PreviousSection jump to
 * the first row of the next / previous section (going round), Pick, Back. Left / Right change a slider. The left stick
 * moves one row per push (back to the middle before the next).
 */
class DrawerNav {
public:
    struct Result {
        enum Type { None, Moved, Select, Close, Adjust } type = None;
        std::string id;
        int by = 0;
    };
    std::string focus() const;
    /** New rows. Keeps the highlight on the same item if it's still there. */
    void set_sections(const std::vector<Section> &sections, bool reset_focus = false);
    /** The menu just opened: a stick already held mustn't move the highlight until it goes back to the middle. */
    void reset(float stick_y = 0) { held_ = direction(stick_y, 0); }
    Result key(NavKey k, bool repeat = false);
    /** Left stick Y (-1 up .. 1 down). */
    Result motion(float y);
    static constexpr float PUSH = 0.5f, BACK = 0.3f;

private:
    struct Row {
        int section;
        std::string id;
        Kind kind;
    };
    static int direction(float v, int now);
    Result move(int by);
    Result section(int by);
    std::vector<Row> rows_;
    int at_ = 0;
    int held_ = 0;
};
} // namespace menu
} // namespace sc
