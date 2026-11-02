/*
 * ShoalcadePlayer: one game session: the libretro host, its main loop and the shell's messages (part of Shoalcade).
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#pragma once
#include <array>
#include <cstdint>
#include <future>
#include <map>
#include <string>
#include <vector>

#include "audio.h"
#include "console.h"
#include "core.h"
#include "core_options.h"
#include "input.h"
#include "link.h"
#include "menu.h"
#include "menu_ui.h"
#include "protocol.h"
#include "rewind.h"
#include "saves.h"
#include "video.h"

namespace sc {
struct RunConfig {
    bool headless = false;
    bool fast = false;           // don't pace frames (tests)
    uint64_t max_frames = 0;     // 0 = until closed
    uint64_t hash_every = 0;     // send `frame` every N shown frames
    bool test_hooks = false;     // allow debug.* messages
    bool start_from_stdin = true; // stdin closing = the shell is gone
    std::multimap<uint64_t, json> scheduled; // --at N:JSON
};

class Player {
public:
    explicit Player(RunConfig cfg) : cfg_(std::move(cfg)) {}
    /** Runs the whole session from a `start` message. Returns the process exit code. */
    int run(const json &start, Inbox &inbox);

    // libretro callbacks (static trampolines in player.cpp forward here).
    bool environment(unsigned cmd, void *data);
    void video_refresh(const void *data, unsigned w, unsigned h, size_t pitch);
    size_t audio_batch(const int16_t *data, size_t frames);
    void input_poll();
    int16_t input_state(unsigned port, unsigned device, unsigned index, unsigned id);
    uintptr_t hw_framebuffer() const { return video_.hw_fbo(); }
    bool rumble(unsigned port, unsigned effect, uint16_t strength) { return input_.rumble(port, effect, strength); }

private:
    bool start(const json &msg, std::string &err);
    void loop(Inbox &inbox);
    void handle(const json &msg);
    void run_frame();
    void shutdown(const char *reason);

    void set_paused(bool p, const char *by);
    bool start_link(const json &msg, const std::string &console_key, std::string &err);
    json link_json() const;
    /** In a link session or the 3DS check: sends a `warning` that `what` is off and returns true. */
    bool link_refuses(const char *what);
    void update_speed();
    void apply_cheats(const json &list);
    void apply_cheat_list();
    bool snapshot_save(const json &slot, const std::string &path, const char *by = "shell");
    bool snapshot_load(const std::string &path, std::string &err);
    bool screenshot(const std::string &path);
    void apply_layout(const json &msg, const char *by = "shell");
    void set_ds_layout(const std::string &ds, bool swap, const char *by);
    void set_n3ds_layout(const std::string &key, bool flip, const char *by);
    /** P5: a Wii controller type on a port (Dolphin's device id); reports `controllerChanged`. */
    bool set_wii_controller(unsigned port, const std::string &type, const char *by);
    /** P12: the core's discs (disk control interface): names, and which is in the drive (-1 = none). */
    std::vector<std::string> disc_names() const;
    int disc_index() const;
    /** P12: put disc `index` in the drive (eject, swap, close); reports `discChanged`. */
    bool set_disc(int index, const char *by);
    void after_present();
    void check_tick();
    void stop_check(const char *reason);
    std::string check_name() const;
    bool can_snapshot() const;

    // The pause menu (menu.h / menu_ui.h).
    menu::Context menu_context() const;
    void refresh_menu(bool reset_focus = false);
    void open_menu(const char *by);
    void close_menu(const char *by, bool resume = true);
    void menu_pick(const std::string &id, const char *by);
    void menu_adjust(const std::string &id, int value, const char *by);
    void menu_nav(menu::NavKey k, bool repeat, const char *by);
    void menu_event(const SDL_Event &e);
    void menu_tick();
    void draw_overlay();
    void send_menu_state();
    void toast(const std::string &text, double seconds = 2.5);
    /** Restart (the browser's): the save is written first, then the core is reset (retro_reset). */
    bool restart_game(const char *by);
    // Changing the buttons (Controls): waiting for the press, and what it does.
    void start_listening(const std::string &id);
    void stop_listening();
    /** Handles e while a Controls row waits for a press. */
    void listen_event(const SDL_Event &e);
    void bind_done(const Input::Change &c, bool pad);
    /** Tells the shell what changed (`controlsChanged`), so it keeps it for this console. */
    void send_controls(bool keyboard, bool hotkeys, const std::string &pad_model);
    /** Link play: the corner line (the phone's watchLink words), "" outside a link session. */
    std::string link_corner() const;
    void load_slots(const json &list);
    void scan_slots();
    std::string slot_state_path(const std::string &slot) const;
    void set_fullscreen(bool on, const char *by);
    void report_display(const char *by);
    void set_rewind_enabled(bool on, const char *by);
    void configure_rewind();
    /** P10: waits for the step being packed (before anything else touches the rewind ring). */
    void rewind_wait();
    void note_rewind_capture(double ms, double core_ms);
    json rewind_capture_json() const {
        return {{"steps", rewind_capture_n_}, {"average", rewind_capture_n_ ? rewind_capture_total_ms_ / rewind_capture_n_ : 0.0},
                {"most", rewind_capture_max_ms_}};
    }
    void set_ff(bool on, int speed, const char *by);
    void set_mute(bool on, const char *by);
    void set_volume(float level, const char *by);
    void toggle_cheat(const std::string &id, bool on, const char *by);
    void report_play_time(bool force);
    void send_frame_info();
    double now_s() const;
    void core_message(const char *text, bool show);

    RunConfig cfg_;
    const ConsoleInfo *console_ = nullptr;
    Core core_;
    CoreOptions options_;
    Video video_;
    Audio audio_;
    Input input_;
    Saves saves_;
    Rewind rewind_;
    Link link_;
    bool link_session_ = false, link_cable_ = false, link_warned_ = false;
    std::string link_peer_, link_note_;

    std::string title_, rom_path_, system_dir_, core_save_dir_, states_dir_, screenshot_dir_;
    std::vector<uint8_t> rom_data_;
    retro_system_info sys_info_{};
    retro_system_av_info av_{};
    bool hw_requested_ = false;
    bool hw_shared_ = false; // SET_HW_SHARED_CONTEXT: the core gets a GL context of its own (Video::create)
    retro_hw_render_callback hw_{};
    bool loaded_ = false, inited_ = false;
    retro_frame_time_callback frame_time_{};
    bool have_frame_time_ = false;
    retro_audio_buffer_status_callback_t audio_status_cb_ = nullptr;
    int av_enable_ = 3;

    std::vector<int16_t> audio_stage_;
    bool running_ = true;
    std::string close_reason_;
    bool paused_ = false;
    bool ff_on_ = false; int ff_speed_ = 2; bool ff_effective_ = false;
    bool rewind_enabled_ = false, rewind_held_proto_ = false, rewinding_ = false;
    unsigned rewind_interval_ = 1;
    uint64_t shown_frames_ = 0, core_frames_ = 0;
    bool first_frame_sent_ = false;
    std::string pending_snapshot_;
    json pending_cheats_;
    bool focused_ = true;
    double play_s_ = 0, play_reported_ = 0, last_play_report_ = 0;
    double last_save_flush_ = 0;
    double save_interval_s_ = 30;
    uint64_t cheats_set_ = 0;
    std::vector<uint8_t> rewind_scratch_;
    std::string last_core_message_;
    double last_core_message_at_ = -100;
    bool core_sound_seen_ = false;
    std::string window_shot_path_;

    // Menu state.
    MenuUi ui_;
    menu::DrawerNav nav_;
    std::vector<menu::Section> sections_;
    bool menu_open_ = false, menu_was_paused_ = false;
    bool show_focus_ = false, scroll_to_focus_ = false, scroll_to_top_ = false;
    std::string armed_;
    double armed_at_ = 0;
    std::string toast_;
    double toast_until_ = 0;
    std::vector<menu::SlotInfo> slots_;
    std::vector<menu::Cheat> cheats_;
    bool can_upload_ = true, snapshots_allowed_ = true, speed_allowed_ = true, cheats_allowed_ = true;
    bool rewind_allowed_ = true, auto_snapshot_on_close_ = false;
    size_t rewind_mb_ = 64;
    // P10: how long saving a coarse rewind step takes (the hitch), and why rewind went off ("" = it didn't).
    uint64_t rewind_capture_n_ = 0, rewind_shown_ = 0;
    double rewind_capture_total_ms_ = 0, rewind_capture_max_ms_ = 0, rewind_first_ms_ = 0;
    double rewind_max_hitch_ms_ = 0;
    // P10: the step being packed on another thread, from rewind_job_buf_ into rewind_. Declared AFTER both, so it is
    // destroyed first: a std::async future waits for its job there, before the buffer and ring are freed (review #12).
    std::vector<uint8_t> rewind_job_buf_;
    std::future<void> rewind_job_;
    std::string rewind_off_why_;
    std::string ds_layout_ = "stack";
    bool ds_swap_ = false;
    std::string n3ds_layout_ = "stacked";
    bool n3ds_flip_ = false;
    // P5: each port's Wii controller type (console.cpp wii_controllers keys). The shell remembers it per game.
    std::vector<std::string> wii_controller_ = std::vector<std::string>(4, "remote");
    // P12: the core's disk control interface (the extended one, or the basic one with the extra calls null).
    retro_disk_control_ext_callback disk_{};
    bool have_disk_ = false;
    // The 3DS check (D151; advisory on Windows, D-Q6): `start.check`. Frames shown each wall-clock second, after a
    // warm-up, reported to the shell, which works out the result with the phone's N3dsCheck rules.
    bool check_ = false;
    int check_warm_ = 10, check_measure_ = 30, check_second_ = 0;
    double check_target_fps_ = 0;
    double check_next_ = 0;
    uint64_t check_frames_at_ = 0;
    std::vector<int> check_fps_;
    bool check_picture_ = true; // false: the check's scene showed nothing at all (an all-black picture at the end)
    std::string check_reason_, check_line_;
    // Snapshots possible with this core: asked once (Azahar takes a whole state to answer, citra_libretro.cpp).
    mutable int can_snapshot_ = -1;
    bool close_upload_ = false;
    // Controller held in the menu: D-pad repeat, and Start + Select together.
    int pad_repeat_dir_ = 0;
    double pad_repeat_at_ = 0;
    MenuUi::Out ui_out_;
    SDL_Joystick *test_pad_ = nullptr; // debug.pad
    // Changing a button (Controls): the row waiting for a press, since when, and where each controller's axes rested
    // then (a trigger resting pushed in isn't a new press: the phone's BindListener).
    std::string listen_id_;
    double listen_at_ = 0;
    std::map<SDL_JoystickID, std::array<float, SDL_GAMEPAD_AXIS_COUNT>> listen_rest_;
    std::string listen_cancelled_;     // a mouse click cancelled this row's wait (a click on the row itself mustn't
    double listen_cancelled_at_ = 0;   // start it again)
    // The ☰ button (mouse-only use, as the browser's toolbar rail): shown for 2.5 s after the mouse moves.
    double mouse_moved_at_ = -1e9;
    bool menu_button_shown() const;
    bool menu_button_rect(float out[4]) const;
};
} // namespace sc
