/*
 * ShoalcadePlayer: the in-game menu's contents and controller navigation (part of Shoalcade).
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Pure C++ (no SDL, no ImGui), so it is unit tested. Mirrors the phone's GameMenu.kt: the same sections in the same
 * order (Game, Snapshots, Speed, Cheats, Sound, Display, Controls), the same words, the same item ids where the phone
 * has them, and DrawerNav's rules (Up / Down a row, L1 / R1 jump a section, A pick, B close, headings skipped).
 * Windows differences: snapshot slots 1-3 + Automatic (as the browser), Rewind, Volume, window / full screen,
 * whole-number scale and smooth picture, Restart (the browser's), and Controls where keys, controller buttons and
 * shortcut keys are changed (the phone's and the browser's rules). The drawer itself is menu_ui.cpp.
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
/** Restart (the browser player's toolbar button and its words, play-ui.js act "restart"). */
inline const char *const RESTART = "Restart";
inline const char *const RESTART_AGAIN = "Press Restart again to restart the game.";
inline const char *const RESTARTED = "Game restarted.";
// Changing the buttons: the phone's Controls screen (ControlsScreen.kt ControlsWords) and the browser's (play-ui.js).
inline const char *const GAME_BUTTONS = "Game buttons";
inline const char *const SHORTCUTS = "Shortcuts";
inline const char *const CONTROLS_FINE = "Pick a game button, then press the key or controller button you want for it. "
                                         "Esc and the Guide button always open the game menu, so they can't be changed.";
inline const char *const NO_CONTROLLER = "No controller found";
inline const char *const NO_CONTROLLER_HELP =
    "Connect a controller and press any button on it. Each controller model gets its own buttons.";
inline const char *const LISTEN = "Press a key or a controller button\xE2\x80\xA6 Esc cancels.";
inline const char *const LISTEN_KEY = "Press a key\xE2\x80\xA6 Esc cancels.";
inline const char *const NOTHING_PRESSED = "Nothing pressed, so nothing changed.";
inline const char *const RESET_PAD = "Reset this controller to defaults";
inline const char *const RESET_PAD_DONE = "This controller is back to the default buttons.";
inline const char *const SHORTCUTS_FINE = "Shortcuts work while the game is running and the menu is closed.";
inline const char *const NOT_SET = "Not set";
/** P5: the Wii controller type (player 1), as the shell remembers it per game. */
inline const char *const WII_CONTROLLER = "Wii controller";
inline const char *const WII_CONTROLLER_PREFIX = "wii.ctrl.";
/** P12: the disc in the drive, for a game on more than one disc. Item ids disc.0, disc.1, ... */
inline const char *const CHANGE_DISC = "Change disc";
inline const std::string DISC_PREFIX = "disc.";

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
inline const char *const ID_RESTART = "restart";
inline const char *const ID_RESET_KEYS = "controls.reset.keys";
inline const char *const ID_RESET_PAD = "controls.reset.pad";
inline const char *const ID_RESET_SHORTCUTS = "controls.reset.shortcuts";
/** A game button's row: control.<button> ("control.A"); a shortcut's: shortcut.<name> ("shortcut.pause"). */
inline const std::string CONTROL_PREFIX = "control.";
inline const std::string SHORTCUT_PREFIX = "shortcut.";

enum class Kind {
    Action,  // does something, then the menu closes
    Choice,  // one of a group (radio button); the menu stays open
    Toggle,  // on / off switch; the menu stays open
    Slider,  // a number (Volume): Left / Right change it
    Info,    // a line you can move onto but not change (a shortcut that can't be changed)
    Bind,    // a game button or shortcut and what presses it: pick it, then press the new key or button
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
    std::string name, what, keys, pad; // name = the button's or shortcut's name ("A", "pause")
    bool fixed = false;                // can't be changed (the menu's own key)
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
    std::string rewind_off_why; // P10: why rewind is off for this game (shown instead of the row)
    bool cheats_allowed = true;
    std::vector<Cheat> cheats;
    bool muted = false;
    int volume = 100;
    bool fullscreen = false, integer_scale = false, smooth = false;
    std::string ds = "stack";
    bool ds_swap = false;
    std::string n3ds = "stacked";
    bool n3ds_flip = false;
    std::string wii_controller = "remote"; // player 1's (console.h wii_controllers keys)
    std::vector<std::string> discs;        // P12: each disc's name (2 or more = a "Change disc" row)
    int disc = 0;                          // the one in the drive
    std::vector<ControlLine> controls, shortcuts;
    std::string console_name;  // "GBA" (for "Reset GBA keys to defaults")
    std::string pad_name;      // the controller the list shows ("" = none connected)
    std::string listening;     // the Controls row waiting for a key or button press ("" = none)
    bool can_restart = true;   // not in link play
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
