/*
 * ShoalcadePlayer: one game session (part of Shoalcade).
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Order of a session: load the core -> set options -> retro_init -> retro_load_game -> open the window (and the GL
 * context the core asked for) -> sound -> controls -> load the save -> cheats -> `ready` -> frames until closed ->
 * write the save -> unload -> `exited`.
 */
#include "player.h"

#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstdarg>
#include <cstring>
#include <ctime>

#include "files.h"
#include "log.h"
#include "sha256.h"

namespace sc {
namespace {
Player *g_player = nullptr;

bool env_cb(unsigned cmd, void *data) {
    try {
        return g_player ? g_player->environment(cmd, data) : false;
    } catch (const std::exception &e) {
        LOGE("environment %u: %s", cmd, e.what());
        return false;
    }
}
void video_cb(const void *data, unsigned w, unsigned h, size_t pitch) { g_player->video_refresh(data, w, h, pitch); }
void audio_sample_cb(int16_t l, int16_t r) {
    int16_t f[2] = {l, r};
    g_player->audio_batch(f, 1);
}
size_t audio_batch_cb(const int16_t *data, size_t frames) { return g_player->audio_batch(data, frames); }
void input_poll_cb() { g_player->input_poll(); }
int16_t input_state_cb(unsigned port, unsigned device, unsigned index, unsigned id) {
    return g_player->input_state(port, device, index, id);
}
uintptr_t get_fb_cb() { return g_player->hw_framebuffer(); }
retro_proc_address_t get_proc_cb(const char *sym) { return (retro_proc_address_t)SDL_GL_GetProcAddress(sym); }
bool rumble_cb(unsigned port, enum retro_rumble_effect effect, uint16_t strength) {
    return g_player->rumble(port, (unsigned)effect, strength);
}

void core_log_cb(enum retro_log_level level, const char *fmt, ...) {
    char buf[2048];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof buf, fmt, ap);
    va_end(ap);
    LogLevel l = level == RETRO_LOG_ERROR ? LogLevel::Error
               : level == RETRO_LOG_WARN  ? LogLevel::Warn
               : level == RETRO_LOG_INFO  ? LogLevel::Info
                                          : LogLevel::Debug;
    logf(l, "[core] %s", buf);
}

// Performance interface: some cores insist on having one.
retro_time_t perf_time_usec() { return (retro_time_t)(SDL_GetTicksNS() / 1000); }
uint64_t perf_cpu_features() {
    uint64_t f = 0;
    if (SDL_HasSSE()) f |= RETRO_SIMD_SSE;
    if (SDL_HasSSE2()) f |= RETRO_SIMD_SSE2;
    if (SDL_HasSSE3()) f |= RETRO_SIMD_SSE3;
    if (SDL_HasSSE41()) f |= RETRO_SIMD_SSE4;
    if (SDL_HasSSE42()) f |= RETRO_SIMD_SSE42;
    if (SDL_HasAVX()) f |= RETRO_SIMD_AVX;
    if (SDL_HasAVX2()) f |= RETRO_SIMD_AVX2;
    if (SDL_HasNEON()) f |= RETRO_SIMD_NEON;
    return f;
}
retro_perf_tick_t perf_counter() { return (retro_perf_tick_t)SDL_GetPerformanceCounter(); }
void perf_register(struct retro_perf_counter *c) { c->registered = true; }
void perf_start(struct retro_perf_counter *c) { c->start = perf_counter(); }
void perf_stop(struct retro_perf_counter *c) {
    c->total += perf_counter() - c->start;
    c->call_cnt++;
}
void perf_log() {}

std::string str(const json &m, const char *k, const std::string &def = "") {
    return m.contains(k) && m[k].is_string() ? m[k].get<std::string>() : def;
}
bool boolean(const json &m, const char *k, bool def) {
    return m.contains(k) && m[k].is_boolean() ? m[k].get<bool>() : def;
}
double number(const json &m, const char *k, double def) {
    return m.contains(k) && m[k].is_number() ? m[k].get<double>() : def;
}

bool from_hex(const std::string &hex, std::vector<uint8_t> &out) {
    if (hex.size() % 2) return false;
    out.clear();
    for (size_t i = 0; i < hex.size(); i += 2) {
        auto nib = [](char c) -> int {
            if (c >= '0' && c <= '9') return c - '0';
            if (c >= 'a' && c <= 'f') return c - 'a' + 10;
            if (c >= 'A' && c <= 'F') return c - 'A' + 10;
            return -1;
        };
        int a = nib(hex[i]), b = nib(hex[i + 1]);
        if (a < 0 || b < 0) return false;
        out.push_back((uint8_t)(a * 16 + b));
    }
    return true;
}

std::string timestamp() {
    std::time_t t = std::time(nullptr);
    std::tm tm{};
#ifdef _WIN32
    localtime_s(&tm, &t);
#else
    localtime_r(&t, &tm);
#endif
    char buf[32];
    std::strftime(buf, sizeof buf, "%Y-%m-%d_%H-%M-%S", &tm);
    return buf;
}

/** "31/10/2026 18:45" (UK date, 24-hour clock). */
std::string when_text(int64_t t) {
    std::time_t tt = (std::time_t)t;
    std::tm tm{};
#ifdef _WIN32
    localtime_s(&tm, &tt);
#else
    localtime_r(&tt, &tm);
#endif
    char buf[32];
    std::strftime(buf, sizeof buf, "%d/%m/%Y %H:%M", &tm);
    return buf;
}

/** A slot name from a message: "auto", or a number 1-3 written as text. */
std::string slot_name_of(const json &v) {
    if (v.is_string()) return v.get<std::string>();
    if (v.is_number()) return std::to_string(v.get<int>());
    return "1";
}

std::string safe_name(const std::string &s) {
    std::string out;
    for (char c : s) out += (strchr("<>:\"/\\|?*", c) || (unsigned char)c < 32) ? '_' : c;
    return out.empty() ? "Shoalcade" : out;
}
} // namespace

double Player::now_s() const { return SDL_GetTicksNS() / 1e9; }

void Player::core_message(const char *text, bool show) {
    // The same text again within 5 s (some cores repeat a note every frame) is logged once and sent once.
    double now = now_s();
    if (text == last_core_message_ && now - last_core_message_at_ < 5) return;
    last_core_message_ = text;
    last_core_message_at_ = now;
    LOGI("[core message] %s", text);
    if (show) send("coreMessage", {{"text", text}});
}

// ---------------------------------------------------------------------------------------------------------------
// Environment

bool Player::environment(unsigned cmd, void *data) {
    switch (cmd) {
    case RETRO_ENVIRONMENT_SET_NETPACKET_INTERFACE:
        // Link play (link.h). Always accepted, as RetroArch and the phone's shim do; used only in a link session.
        link_.set_interface(static_cast<const retro_netpacket_callback *>(data));
        return true;
    case RETRO_ENVIRONMENT_SET_ROTATION:
        if (data && *static_cast<const unsigned *>(data) == 0) return true;
        log_once("rotation", "core asked for screen rotation; not supported");
        return false;
    case RETRO_ENVIRONMENT_GET_OVERSCAN:
        if (data) *static_cast<bool *>(data) = false;
        return true;
    case RETRO_ENVIRONMENT_GET_CAN_DUPE:
        if (data) *static_cast<bool *>(data) = true;
        return true;
    case RETRO_ENVIRONMENT_SET_MESSAGE: {
        auto *m = static_cast<const retro_message *>(data);
        if (m && m->msg) core_message(m->msg, true);
        return true;
    }
    case RETRO_ENVIRONMENT_SET_MESSAGE_EXT: {
        auto *m = static_cast<const retro_message_ext *>(data);
        if (m && m->msg) core_message(m->msg, m->target != RETRO_MESSAGE_TARGET_LOG);
        return true;
    }
    case RETRO_ENVIRONMENT_SHUTDOWN:
        running_ = false;
        close_reason_ = "core";
        return true;
    case RETRO_ENVIRONMENT_SET_PERFORMANCE_LEVEL:
        return true;
    case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
    case RETRO_ENVIRONMENT_GET_CORE_ASSETS_DIRECTORY:
        if (data) *static_cast<const char **>(data) = system_dir_.c_str();
        return true;
    case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
        if (data) *static_cast<const char **>(data) = core_save_dir_.c_str();
        return true;
    case RETRO_ENVIRONMENT_GET_LIBRETRO_PATH:
        if (data) *static_cast<const char **>(data) = core_.path.c_str();
        return true;
    case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: {
        auto f = *static_cast<const retro_pixel_format *>(data);
        if (f != RETRO_PIXEL_FORMAT_0RGB1555 && f != RETRO_PIXEL_FORMAT_XRGB8888 && f != RETRO_PIXEL_FORMAT_RGB565)
            return false;
        video_.set_pixel_format(f);
        LOGI("pixel format %s", f == RETRO_PIXEL_FORMAT_XRGB8888 ? "XRGB8888" : f == RETRO_PIXEL_FORMAT_RGB565 ? "RGB565" : "0RGB1555");
        return true;
    }
    case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
    case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
    case RETRO_ENVIRONMENT_SET_SUBSYSTEM_INFO:
    case RETRO_ENVIRONMENT_SET_MEMORY_MAPS:
    case RETRO_ENVIRONMENT_SET_SUPPORT_ACHIEVEMENTS:
    case RETRO_ENVIRONMENT_SET_PROC_ADDRESS_CALLBACK:
    case RETRO_ENVIRONMENT_SET_CONTENT_INFO_OVERRIDE:
    case RETRO_ENVIRONMENT_SET_MINIMUM_AUDIO_LATENCY:
    case RETRO_ENVIRONMENT_SET_HW_SHARED_CONTEXT:
    case RETRO_ENVIRONMENT_SET_DISK_CONTROL_INTERFACE:
    case RETRO_ENVIRONMENT_SET_DISK_CONTROL_EXT_INTERFACE:
        // Noted; nothing for the player to do (disc swapping comes with the pause menu).
        return true;
    case RETRO_ENVIRONMENT_SET_SERIALIZATION_QUIRKS:
        if (data) LOGI("serialization quirks 0x%llx", (unsigned long long)*static_cast<uint64_t *>(data));
        return true;
    case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME:
        return false; // the player always loads a game: "no game" mode isn't offered
    case RETRO_ENVIRONMENT_SET_HW_RENDER: {
        auto *cb = static_cast<retro_hw_render_callback *>(data);
        if (cb->context_type != RETRO_HW_CONTEXT_OPENGL && cb->context_type != RETRO_HW_CONTEXT_OPENGL_CORE) {
            LOGI("core offered hardware context type %d; only OpenGL is supported", (int)cb->context_type);
            return false;
        }
        cb->get_current_framebuffer = get_fb_cb;
        cb->get_proc_address = get_proc_cb;
        hw_ = *cb;
        hw_requested_ = true;
        LOGI("core wants OpenGL%s %u.%u (depth %d, stencil %d, bottom-left %d)",
             cb->context_type == RETRO_HW_CONTEXT_OPENGL_CORE ? " core" : "", cb->version_major, cb->version_minor,
             cb->depth, cb->stencil, cb->bottom_left_origin);
        return true;
    }
    case RETRO_ENVIRONMENT_GET_PREFERRED_HW_RENDER:
        if (data) *static_cast<unsigned *>(data) = RETRO_HW_CONTEXT_OPENGL_CORE;
        return true;
    case RETRO_ENVIRONMENT_GET_VARIABLE: {
        auto *v = static_cast<retro_variable *>(data);
        if (!v || !v->key) return false;
        v->value = options_.get(v->key);
        return v->value != nullptr;
    }
    case RETRO_ENVIRONMENT_SET_VARIABLES:
        options_.define_v0(static_cast<const retro_variable *>(data));
        return true;
    case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
        if (data) *static_cast<bool *>(data) = options_.take_updated();
        return true;
    case RETRO_ENVIRONMENT_SET_VARIABLE: {
        if (!data) return true; // "is this supported?"
        auto *v = static_cast<const retro_variable *>(data);
        return options_.core_set(v->key, v->value);
    }
    case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION:
        if (data) *static_cast<unsigned *>(data) = 2;
        return true;
    case RETRO_ENVIRONMENT_SET_CORE_OPTIONS:
        options_.define_v1(static_cast<const retro_core_option_definition *>(data));
        return true;
    case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_INTL: {
        auto *intl = static_cast<const retro_core_options_intl *>(data);
        options_.define_v1(intl ? intl->us : nullptr); // English labels: the values are the same in every language
        return true;
    }
    case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2:
        options_.define_v2(static_cast<const retro_core_options_v2 *>(data));
        return true;
    case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2_INTL: {
        auto *intl = static_cast<const retro_core_options_v2_intl *>(data);
        options_.define_v2(intl ? intl->us : nullptr);
        return true;
    }
    case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_DISPLAY: {
        auto *d = static_cast<const retro_core_option_display *>(data);
        if (d) options_.set_visible(d->key, d->visible);
        return true;
    }
    case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_UPDATE_DISPLAY_CALLBACK:
        return true; // options are shown by the shell, from the `options` list in `ready`
    case RETRO_ENVIRONMENT_SET_FRAME_TIME_CALLBACK: {
        auto *f = static_cast<const retro_frame_time_callback *>(data);
        if (f) frame_time_ = *f, have_frame_time_ = f->callback != nullptr;
        return true;
    }
    case RETRO_ENVIRONMENT_SET_AUDIO_BUFFER_STATUS_CALLBACK: {
        auto *a = static_cast<const retro_audio_buffer_status_callback *>(data);
        audio_status_cb_ = a ? a->callback : nullptr;
        return true;
    }
    case RETRO_ENVIRONMENT_GET_RUMBLE_INTERFACE: {
        auto *r = static_cast<retro_rumble_interface *>(data);
        r->set_rumble_state = rumble_cb;
        return true;
    }
    case RETRO_ENVIRONMENT_GET_INPUT_DEVICE_CAPABILITIES:
        if (data)
            *static_cast<uint64_t *>(data) =
                (1ull << RETRO_DEVICE_JOYPAD) | (1ull << RETRO_DEVICE_ANALOG) | (1ull << RETRO_DEVICE_POINTER);
        return true;
    case RETRO_ENVIRONMENT_GET_INPUT_BITMASKS:
        return true;
    case RETRO_ENVIRONMENT_GET_INPUT_MAX_USERS:
        if (data) *static_cast<unsigned *>(data) = (unsigned)input_.ports();
        return true;
    case RETRO_ENVIRONMENT_GET_LOG_INTERFACE: {
        auto *l = static_cast<retro_log_callback *>(data);
        l->log = core_log_cb;
        return true;
    }
    case RETRO_ENVIRONMENT_GET_PERF_INTERFACE: {
        auto *p = static_cast<retro_perf_callback *>(data);
        p->get_time_usec = perf_time_usec;
        p->get_cpu_features = perf_cpu_features;
        p->get_perf_counter = perf_counter;
        p->perf_register = perf_register;
        p->perf_start = perf_start;
        p->perf_stop = perf_stop;
        p->perf_log = perf_log;
        return true;
    }
    case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO: {
        auto *a = static_cast<const retro_system_av_info *>(data);
        av_ = *a;
        video_.set_geometry(a->geometry);
        if (hw_requested_ && video_.window()) {
            std::string err;
            if (!video_.ensure_hw_fbo(a->geometry.max_width, a->geometry.max_height, err)) LOGE("%s", err.c_str());
        }
        audio_.set_rate(a->timing.sample_rate);
        LOGI("core changed timing: %.3f fps, %.1f Hz, %ux%u", a->timing.fps, a->timing.sample_rate,
             a->geometry.base_width, a->geometry.base_height);
        return true;
    }
    case RETRO_ENVIRONMENT_SET_GEOMETRY: {
        auto *g = static_cast<const retro_game_geometry *>(data);
        av_.geometry.base_width = g->base_width;
        av_.geometry.base_height = g->base_height;
        av_.geometry.aspect_ratio = g->aspect_ratio;
        video_.set_geometry(*g);
        return true;
    }
    case RETRO_ENVIRONMENT_GET_LANGUAGE:
        if (data) *static_cast<unsigned *>(data) = RETRO_LANGUAGE_ENGLISH;
        return true;
    case RETRO_ENVIRONMENT_GET_AUDIO_VIDEO_ENABLE:
        if (data) *static_cast<int *>(data) = av_enable_;
        return true;
    case RETRO_ENVIRONMENT_GET_FASTFORWARDING:
        if (data) *static_cast<bool *>(data) = ff_effective_;
        return true;
    case RETRO_ENVIRONMENT_GET_TARGET_REFRESH_RATE:
        if (data) *static_cast<float *>(data) = (float)(av_.timing.fps > 0 ? av_.timing.fps : 60.0);
        return true;
    case RETRO_ENVIRONMENT_GET_THROTTLE_STATE: {
        auto *t = static_cast<retro_throttle_state *>(data);
        double fps = av_.timing.fps > 0 ? av_.timing.fps : 60.0;
        if (paused_) t->mode = RETRO_THROTTLE_FRAME_STEPPING, t->rate = 0;
        else if (rewinding_) t->mode = RETRO_THROTTLE_REWINDING, t->rate = (float)fps;
        else if (ff_effective_) t->mode = RETRO_THROTTLE_FAST_FORWARD, t->rate = (float)(fps * ff_speed_);
        else if (cfg_.fast) t->mode = RETRO_THROTTLE_UNBLOCKED, t->rate = 0;
        else t->mode = RETRO_THROTTLE_NONE, t->rate = (float)fps;
        return true;
    }
    case RETRO_ENVIRONMENT_GET_SAVESTATE_CONTEXT:
        if (data) *static_cast<int *>(data) = RETRO_SAVESTATE_CONTEXT_NORMAL;
        return true;
    case RETRO_ENVIRONMENT_GET_JIT_CAPABLE:
        if (data) *static_cast<bool *>(data) = true;
        return true;
    case RETRO_ENVIRONMENT_GET_DISK_CONTROL_INTERFACE_VERSION:
        if (data) *static_cast<unsigned *>(data) = 1;
        return true;
    case RETRO_ENVIRONMENT_GET_MESSAGE_INTERFACE_VERSION:
        if (data) *static_cast<unsigned *>(data) = 1;
        return true;
    default: {
        bool exp = (cmd & RETRO_ENVIRONMENT_EXPERIMENTAL) != 0;
        unsigned base = cmd & 0xffff;
        log_once("env" + std::to_string(cmd), "environment call %u%s isn't supported (answered false)", base,
                 exp ? " (experimental)" : "");
        return false;
    }
    }
}

void Player::video_refresh(const void *data, unsigned w, unsigned h, size_t pitch) { video_.frame(data, w, h, pitch); }

size_t Player::audio_batch(const int16_t *data, size_t frames) {
    if (frames) core_sound_seen_ = true;
    // Only the shown frame's sound (and none while fast or rewinding) reaches the speakers.
    if (!ff_effective_ && !rewinding_) audio_stage_.insert(audio_stage_.end(), data, data + frames * 2);
    else audio_.note_muted(frames);
    return frames;
}

void Player::input_poll() {
    if (cfg_.headless && input_.mouse_forced()) {
        input_.poll(video_.game_rect_window(), true); // debug.mouse: the picture's place in the (hidden) window
        return;
    }
    if (cfg_.headless) {
        input_.poll(SDL_FRect{0, 0, 0, 0}, true); // no real mouse in headless runs: keeps tests repeatable
        return;
    }
    bool focused = video_.window() && (SDL_GetWindowFlags(video_.window()) & SDL_WINDOW_INPUT_FOCUS);
    input_.poll(video_.game_rect_window(), focused);
}

int16_t Player::input_state(unsigned port, unsigned device, unsigned index, unsigned id) {
    if (menu_open_) return 0; // a link session keeps running under the menu: the menu's keys mustn't reach the game
    return input_.state(port, device & RETRO_DEVICE_MASK, index, id);
}

// ---------------------------------------------------------------------------------------------------------------
// Session

bool Player::start(const json &msg, std::string &err) {
    std::string console_key = str(msg, "console");
    console_ = find_console(console_key);
    if (!console_) return err = "unknown console \"" + console_key + "\"", false;
    std::string core_path = str(msg, "core");
    rom_path_ = str(msg, "rom");
    if (core_path.empty() || rom_path_.empty()) return err = "start needs core and rom", false;
    if (!file_exists(rom_path_)) return err = "the game file isn't there: " + rom_path_, false;
    system_dir_ = str(msg, "systemDir", join_path(parent_dir(core_path), "system"));
    make_dirs(system_dir_);
    std::string save_folder = str(msg, "saveFolder");
    if (console_->folder_saves) {
        if (save_folder.empty()) return err = std::string(console_->short_name) + " needs saveFolder", false;
        core_save_dir_ = save_folder;
    } else {
        core_save_dir_ = str(msg, "coreSaveDir", join_path(system_dir_, "coresaves"));
    }
    if (!make_dirs(core_save_dir_)) return err = "couldn't make the save folder " + core_save_dir_, false;
    states_dir_ = str(msg, "statesDir");
    screenshot_dir_ = str(msg, "screenshotDir");
    title_ = str(msg, "title", "Shoalcade");

    if (!core_.open(core_path, err)) return false;
    g_player = this;

    // Options: console rules first (locked ones can't be replaced), then the shell's choices.
    const std::vector<ForcedOption> &forced = forced_options(console_key);
    for (const auto &o : forced) options_.want(o.key, o.value, o.locked);
    if (msg.contains("options") && msg["options"].is_object())
        for (auto it = msg["options"].begin(); it != msg["options"].end(); ++it) {
            if (!it.value().is_string()) continue;
            if (!options_.change(it.key(), it.value().get<std::string>()))
                send("warning", {{"message", "Option " + it.key() + " is fixed by Shoalcade for this console; kept."}});
        }
    if (msg.contains("layout") && msg["layout"].is_object() && console_key == "nds") {
        std::string want = str(msg["layout"], "ds", ds_layout_);
        bool swap = boolean(msg["layout"], "swap", false);
        auto ds = ds_layout_options(want, swap);
        for (const auto &kv : ds) options_.change(kv.first, kv.second);
        if (!ds.empty()) ds_layout_ = want, ds_swap_ = swap;
    }
    if (msg.contains("layout") && msg["layout"].is_object() && console_key == "3ds") {
        // The shell keeps the person's choice (D206); an unknown name keeps the default with a warning.
        std::string want = str(msg["layout"], "n3ds", n3ds_layout_);
        bool flip = boolean(msg["layout"], "flip", false);
        auto o = n3ds_layout_options(want, flip);
        if (o.empty()) send("warning", {{"message", "Unknown 3DS layout \"" + want + "\"; Top and bottom is used."}});
        else n3ds_layout_ = want, n3ds_flip_ = flip;
    }
    if (console_key == "3ds")
        for (const auto &kv : n3ds_layout_options(n3ds_layout_, n3ds_flip_)) options_.change(kv.first, kv.second);
    // What the menu offers (the shell decides: a link session has no snapshots, speed or cheats).
    can_upload_ = boolean(msg, "canUpload", true);
    snapshots_allowed_ = boolean(msg, "snapshotsAllowed", true);
    speed_allowed_ = boolean(msg, "speedAllowed", true);
    cheats_allowed_ = boolean(msg, "cheatsAllowed", true);
    rewind_allowed_ = boolean(msg, "rewindAllowed", true);
    auto_snapshot_on_close_ = boolean(msg, "autoSnapshotOnClose", false);
    if (msg.contains("check") && msg["check"].is_object()) {
        // The 3DS check (Game3dsActivity check mode): the test scene, measured; no menu, snapshots, cheats or speed.
        if (console_key != "3ds") return err = "the check is for the 3DS", false;
        check_ = true;
        check_warm_ = (int)std::clamp(number(msg["check"], "warmUp", 10), 0.0, 120.0);
        check_measure_ = (int)std::clamp(number(msg["check"], "measure", 30), 1.0, 300.0);
        snapshots_allowed_ = speed_allowed_ = cheats_allowed_ = rewind_allowed_ = false;
        auto_snapshot_on_close_ = false;
        check_line_ = "3DS check: getting ready...";
    }
    if (msg.contains("link") && !msg["link"].is_null() && !start_link(msg, console_key, err)) return false;

    core_.set_environment(env_cb);
    core_.set_video_refresh(video_cb);
    core_.set_audio_sample(audio_sample_cb);
    core_.set_audio_sample_batch(audio_batch_cb);
    core_.set_input_poll(input_poll_cb);
    core_.set_input_state(input_state_cb);
    input_.configure(*console_, msg.contains("controls") ? msg["controls"] : json::object());

    unsigned api = core_.api_version();
    if (api != RETRO_API_VERSION) return err = "core speaks libretro API " + std::to_string(api), false;
    core_.init();
    inited_ = true;
    core_.get_system_info(&sys_info_);
    LOGI("core %s %s (need_fullpath %d)", sys_info_.library_name ? sys_info_.library_name : "?",
         sys_info_.library_version ? sys_info_.library_version : "?", sys_info_.need_fullpath);

    retro_game_info game{};
    game.path = rom_path_.c_str();
    if (!sys_info_.need_fullpath) {
        std::string rerr;
        if (!read_file(rom_path_, rom_data_, &rerr)) return err = "couldn't read the game file: " + rerr, false;
        game.data = rom_data_.data();
        game.size = rom_data_.size();
    }
    if (!core_.load_game(&game)) return err = "the emulator couldn't start this game", false;
    loaded_ = true;
    core_.get_system_av_info(&av_);
    LOGI("game: %ux%u (max %ux%u) aspect %.3f, %.3f fps, %.1f Hz", av_.geometry.base_width, av_.geometry.base_height,
         av_.geometry.max_width, av_.geometry.max_height, av_.geometry.aspect_ratio, av_.timing.fps,
         av_.timing.sample_rate);

    if (msg.contains("display") && msg["display"].is_object()) {
        DisplaySettings d;
        d.integer_scale = boolean(msg["display"], "integerScale", false);
        d.smooth = boolean(msg["display"], "smooth", false);
        video_.set_display(d);
    }
    if (!video_.create("Shoalcade - " + title_, av_.geometry, hw_requested_ ? &hw_ : nullptr, cfg_.headless, err))
        return false;
    if (msg.contains("display") && msg["display"].is_object() && boolean(msg["display"], "fullscreen", false))
        video_.set_fullscreen(true);
    if (!cfg_.headless) {
        // The pause menu draws over the game. Without it the game still plays (the menu works through messages).
        std::string uerr;
        if (ui_.init(video_.window(), video_.context(), uerr)) video_.overlay = [this](int, int) { draw_overlay(); };
        else send("warning", {{"message", "The pause menu couldn't start: " + uerr}});
    }
    if (hw_requested_) {
        if (!video_.ensure_hw_fbo(av_.geometry.max_width, av_.geometry.max_height, err)) return false;
        if (hw_.context_reset) hw_.context_reset();
    }

    audio_.open(av_.timing.sample_rate);
    audio_.set_volume((float)number(msg, "volume", 1.0));
    audio_.set_muted(boolean(msg, "muted", false));

    for (int p = 0; p < input_.ports(); p++) core_.set_controller_port_device((unsigned)p, RETRO_DEVICE_JOYPAD);

    saves_.configure(*console_, str(msg, "saveFile"), str(msg, "clockFile"));
    saves_.load(core_);

    if (msg.contains("ff") && msg["ff"].is_object()) ff_speed_ = (int)number(msg["ff"], "speed", 2);
    ff_speed_ = std::clamp(ff_speed_, 1, console_->max_ff);
    if (msg.contains("rewind") && msg["rewind"].is_object() && boolean(msg["rewind"], "enabled", false)) {
        if (console_->rewind) {
            double mb = std::clamp(number(msg["rewind"], "mb", 64), 4.0, 1024.0);
            rewind_mb_ = (size_t)mb;
            rewind_.configure((size_t)(mb * 1024 * 1024));
            rewind_interval_ = (unsigned)std::clamp(number(msg["rewind"], "every", console_key == "psx" ? 2 : 1), 1.0, 60.0);
            rewind_enabled_ = can_snapshot();
            if (!rewind_enabled_) send("warning", {{"message", "Rewind isn't available: the emulator can't snapshot."}});
        } else {
            send("warning", {{"message", std::string("Rewind isn't available for ") + console_->short_name + "."}});
        }
    }
    if (msg.contains("cheats") && !link_session_ && !check_) pending_cheats_ = msg["cheats"];
    scan_slots();
    if (msg.contains("slots")) load_slots(msg["slots"]);
    pending_snapshot_ = link_session_ || check_ ? "" : str(msg, "snapshot");
    if (link_session_) {
        // As GameActivity.kt: no snapshots, cheats, fast-forward or rewind in link play (the other side wouldn't follow).
        for (const char *k : {"snapshot", "cheats"})
            if (msg.contains(k) && !msg[k].is_null() && !(msg[k].is_array() && msg[k].empty()))
                send("warning", {{"message", std::string(k) + " is off in link play; ignored."}});
        if (rewind_enabled_) send("warning", {{"message", "Rewind is off in link play."}});
        rewind_enabled_ = false;
        rewind_.clear();
    }
    if (msg.contains("saveIntervalSeconds")) save_interval_s_ = std::clamp(number(msg, "saveIntervalSeconds", 30), 1.0, 600.0);

    send("ready", {{"console", console_->key},
                   {"core", {{"name", sys_info_.library_name ? sys_info_.library_name : ""},
                             {"version", sys_info_.library_version ? sys_info_.library_version : ""},
                             {"path", core_path}}},
                   {"video", {{"width", av_.geometry.base_width}, {"height", av_.geometry.base_height},
                              {"aspect", av_.geometry.aspect_ratio}, {"fps", av_.timing.fps},
                              {"hardware", hw_requested_}}},
                   {"audio", {{"sampleRate", av_.timing.sample_rate}, {"active", audio_.active()}}},
                   {"save", {{"enabled", saves_.enabled()}, {"why", saves_.disabled_reason()}}},
                   {"snapshots", can_snapshot()},
                   {"rewind", rewind_enabled_},
                   {"menu", {{"drawn", ui_.ready()}, {"keys", "Esc, controller Guide, or Start + Select"}}},
                   {"ports", input_.ports()},
                   {"link", link_session_ ? link_json() : json(nullptr)},
                   {"layout", console_key == "3ds" ? json{{"n3ds", n3ds_layout_}, {"flip", n3ds_flip_}}
                              : console_key == "nds" ? json{{"ds", ds_layout_}, {"swap", ds_swap_}} : json(nullptr)},
                   {"check", check_ ? json{{"warmUp", check_warm_}, {"measure", check_measure_}} : json(nullptr)},
                   {"options", options_.describe()}});
    return true;
}

bool Player::start_link(const json &msg, const std::string &console_key, std::string &err) {
    const json &l = msg["link"];
    if (!l.is_object()) return err = "link must be an object {port, role, ...}", false;
    if (console_key != "gba" && console_key != "nds")
        return err = std::string("link play is for the GBA and the DS, not ") + console_->short_name, false;
    std::string role = str(l, "role");
    if (role != "host" && role != "join") return err = "link.role must be \"host\" or \"join\"", false;
    Link::Config c;
    c.port = (int)number(l, "port", 0);
    c.host = role == "host";
    c.token = str(l, "token");
    c.connect_timeout_ms = (int)std::clamp(number(l, "connectTimeoutMs", 10000), 100.0, 60000.0);
    link_cable_ = boolean(l, "cable", false);
    // Core options for link play, as GameActivity.kt. Locked: the shell's `options` can't undo them mid-session.
    if (console_key == "gba") {
        options_.want("gpsp_bios", "builtin", true);
        // auto = Ruby/Sapphire use the cable, Emerald/FireRed/LeafGreen the wireless adapter; cable = mul_poke.
        options_.want("gpsp_serial", link_cable_ ? "mul_poke" : "auto", true);
    } else {
        std::string mac = str(l, "dsMac");
        if (!mac.empty()) {
            // melonDS DS needs a literal "xx:xx:xx:xx:xx:xx" (LinkRules.randomDsMac); anything else is ignored.
            bool ok = mac.size() == 17;
            for (size_t i = 0; ok && i < 17; i++)
                ok = (i % 3 == 2) ? mac[i] == ':' : std::isxdigit((unsigned char)mac[i]) != 0;
            if (ok) options_.want("melonds_mac_address_mode", mac, true);
            else send("warning", {{"message", "link.dsMac isn't a MAC address (xx:xx:xx:xx:xx:xx); the DS keeps its own."}});
        }
    }
    link_peer_ = str(l, "peer");
    link_.on_change = [this] {
        send("linkStatus", link_json());
        // GameActivity.watchLink: said once when the link drops (or never starts); the game carries on.
        if (!link_warned_ && link_.state() >= Link::Lost) {
            link_warned_ = true;
            toast("The link stopped. You can keep playing; save, quit and link again to retry.", 8);
        }
    };
    if (!link_.open(c, err)) return false;
    link_session_ = true;
    snapshots_allowed_ = speed_allowed_ = cheats_allowed_ = rewind_allowed_ = false;
    auto_snapshot_on_close_ = false;
    return true;
}

std::string Player::link_corner() const {
    if (!link_session_) return "";
    // GameActivity.watchLink's words (the menu font has no emoji, so no 🔗 / ⚠️). Celio Link: the shell's line.
    int st = link_.state();
    if (st == Link::Active && !link_note_.empty()) return link_note_;
    switch (st) {
    case Link::Active:
        return "Linked to " + (link_peer_.empty() ? std::string("the other player") : link_peer_) + " \u00b7 " +
               std::to_string(link_.received()) + " in \u00b7 " + std::to_string(link_.sent()) + " out";
    case Link::WaitingCore: return "Starting link...";
    case Link::Lost: return "Link lost";
    case Link::Refused: return "Link refused by the game";
    case Link::NoSupport: return "This emulator can't link";
    default: return "Link off";
    }
}

json Player::link_json() const {
    return {{"state", Link::state_name(link_.state())}, {"code", link_.state()},
            {"role", link_.host() ? "host" : "join"}, {"cable", link_cable_},
            {"received", link_.received()}, {"sent", link_.sent()},
            {"bytesReceived", link_.bytes_received()}, {"bytesSent", link_.bytes_sent()},
            {"protocol", link_.protocol_version()}, {"why", link_.why()}};
}

bool Player::link_refuses(const char *what) {
    if (check_) { // the 3DS check: just the scene, measured (Game3dsActivity check mode)
        send("warning", {{"message", std::string(what) + " is off during the 3DS check."}});
        return true;
    }
    if (!link_session_) return false;
    send("warning", {{"message", std::string(what) + " is off in link play (the other side wouldn't follow)."}});
    return true;
}

void Player::apply_cheats(const json &list) {
    if (!list.is_array()) return;
    // The shell's list: "CODE" or {code, enabled, id, name}. Kept for the menu's Cheats switches.
    cheats_.clear();
    for (size_t i = 0; i < list.size(); i++) {
        const json &c = list[i];
        menu::Cheat x;
        x.id = std::to_string(i);
        if (c.is_string()) x.code = c.get<std::string>();
        else if (c.is_object()) {
            x.code = str(c, "code"), x.on = boolean(c, "enabled", true), x.name = str(c, "name");
            if (c.contains("id") && c["id"].is_string()) x.id = c["id"].get<std::string>();
            else if (c.contains("id") && c["id"].is_number_integer()) x.id = std::to_string(c["id"].get<long long>());
        }
        if (!x.code.empty()) cheats_.push_back(x);
    }
    apply_cheat_list();
    if (menu_open_) refresh_menu();
}

void Player::apply_cheat_list() {
    core_.cheat_reset();
    unsigned index = 0;
    for (const auto &c : cheats_) {
        if (!c.on) continue;
        core_.cheat_set(index++, true, c.code.c_str());
    }
    cheats_set_ = index;
    // libretro can't say whether a code was accepted; the count is what was handed over.
    send("cheatsApplied", {{"count", index}});
}

void Player::set_paused(bool p, const char *by) {
    if (p == paused_) return;
    paused_ = p;
    audio_.set_paused(p);
    if (p) {
        saves_.flush(core_, "pause");
        report_play_time(true);
        send("paused", {{"by", by}});
    } else {
        send("resumed", {{"by", by}});
    }
}

void Player::update_speed() {
    bool held = input_.held(Hotkey::FastForward) && !link_session_ && !check_;
    bool eff = (ff_on_ || held) && ff_speed_ > 1 && !rewinding_;
    if (eff != ff_effective_) {
        ff_effective_ = eff;
        audio_.set_fast_forward(eff);
        audio_stage_.clear();
    }
    bool rw = rewind_enabled_ && !link_session_ && (rewind_held_proto_ || input_.held(Hotkey::Rewind));
    if (rw != rewinding_) {
        rewinding_ = rw;
        audio_.set_fast_forward(rw || ff_effective_); // rewinding is silent too
        audio_stage_.clear();
    }
}

bool Player::snapshot_save(const json &slot, const std::string &explicit_path, const char *by) {
    size_t size = core_.serialize_size();
    std::vector<uint8_t> buf(size);
    if (!size || !core_.serialize(buf.data(), size)) {
        send("error", {{"message", "The emulator couldn't make a snapshot right now."}, {"fatal", false}});
        return false;
    }
    std::string slot_name = slot_name_of(slot);
    std::string path = explicit_path;
    if (path.empty()) {
        if (states_dir_.empty()) {
            send("error", {{"message", "No snapshot folder was given (statesDir)."}, {"fatal", false}});
            return false;
        }
        make_dirs(states_dir_);
        path = join_path(states_dir_, "slot" + safe_name(slot_name) + ".state");
    } else {
        make_dirs(parent_dir(path));
    }
    std::string err;
    if (!write_file_atomic(path, buf.data(), buf.size(), &err)) {
        send("error", {{"message", "Couldn't write the snapshot: " + err}, {"fatal", false}});
        return false;
    }
    std::string png = path;
    if (png.size() > 6 && png.compare(png.size() - 6, 6, ".state") == 0) png.resize(png.size() - 6);
    png += ".png";
    RgbaImage img = video_.capture();
    if (!write_png(png, img, &err)) png.clear();
    send("snapshotSaved", {{"slot", slot_name}, {"path", path}, {"png", png}, {"size", size},
                           {"sha256", sha256_hex(buf.data(), buf.size())}, {"by", by}});
    // The menu's slot shows the new picture and time straight away (the shell may send `slots` later).
    for (auto &s : slots_)
        if (s.slot == slot_name) {
            s.has = true, s.loadable = true, s.path = path, s.png = png, s.note.clear();
            s.when = when_text((int64_t)std::time(nullptr));
        }
    ui_.forget_pictures();
    if (menu_open_) refresh_menu();
    return true;
}

bool Player::snapshot_load(const std::string &path, std::string &err) {
    std::vector<uint8_t> buf;
    if (!read_file(path, buf, &err)) return false;
    if (!core_.unserialize(buf.data(), buf.size())) {
        err = "the emulator couldn't load this snapshot (made by a different emulator version?)";
        return false;
    }
    rewind_.clear();
    return true;
}

bool Player::screenshot(const std::string &explicit_path) {
    std::string path = explicit_path;
    if (path.empty()) {
        if (screenshot_dir_.empty()) {
            send("error", {{"message", "No screenshot folder was given (screenshotDir)."}, {"fatal", false}});
            return false;
        }
        make_dirs(screenshot_dir_);
        path = join_path(screenshot_dir_, safe_name(title_) + "_" + timestamp() + ".png");
    } else {
        make_dirs(parent_dir(path));
    }
    std::string err;
    RgbaImage img = video_.capture();
    if (!write_png(path, img, &err)) {
        send("error", {{"message", "Couldn't save the screenshot: " + err}, {"fatal", false}});
        return false;
    }
    send("screenshot", {{"path", path}, {"width", img.width}, {"height", img.height}});
    return true;
}

void Player::apply_layout(const json &msg, const char *by) {
    if (std::string(console_->key) == "3ds") {
        set_n3ds_layout(str(msg, "n3ds", n3ds_layout_), boolean(msg, "flip", n3ds_flip_), by);
        return;
    }
    set_ds_layout(str(msg, "ds", ds_layout_), boolean(msg, "swap", ds_swap_), by);
}

void Player::set_n3ds_layout(const std::string &want, bool flip, const char *by) {
    // Game3dsActivity.applyCoreLayout: Azahar's "Screen Layout" and "Swap Screens" options. The core re-reads them
    // on its next frame and tells the player the new frame size (SET_GEOMETRY); the mouse follows the bottom screen.
    auto o = n3ds_layout_options(want, flip);
    if (o.empty()) {
        send("error", {{"message", "Unknown 3DS layout (stacked, single, large or side)."}, {"fatal", false}});
        return;
    }
    for (const auto &kv : o) options_.change(kv.first, kv.second);
    n3ds_layout_ = want, n3ds_flip_ = flip;
    send("layoutChanged", {{"n3ds", n3ds_layout_}, {"flip", n3ds_flip_}, {"by", by}});
    if (menu_open_) refresh_menu();
}

void Player::set_ds_layout(const std::string &want, bool swap, const char *by) {
    if (std::string(console_->key) != "nds") {
        send("warning", {{"message", "Screen layouts are for the DS and the 3DS."}});
        return;
    }
    auto ds = ds_layout_options(want, swap);
    if (ds.empty()) {
        send("error", {{"message", "Unknown DS layout (side, stack or big)."}, {"fatal", false}});
        return;
    }
    for (const auto &kv : ds) options_.change(kv.first, kv.second);
    ds_layout_ = want, ds_swap_ = swap;
    send("layoutChanged", {{"ds", ds_layout_}, {"swap", ds_swap_}, {"by", by}});
    if (menu_open_) refresh_menu();
}

void Player::report_play_time(bool force) {
    double unsent = play_s_ - play_reported_;
    if (unsent < 1 || (!force && now_s() - last_play_report_ < 60)) return;
    int n = (int)unsent;
    play_reported_ += n;
    last_play_report_ = now_s();
    send("playSeconds", {{"n", n}, {"total", (int)play_reported_}});
}

void Player::send_frame_info() {
    RgbaImage img = video_.capture();
    send("frame", {{"n", shown_frames_}, {"coreFrames", core_frames_}, {"width", img.width}, {"height", img.height},
                   {"hash", frame_hash(img)}, {"nonBlack", non_black_pixels(img)},
                   {"pixels", (size_t)img.width * img.height}});
}

void Player::handle(const json &msg) {
    std::string type = msg["type"].get<std::string>();
    if (type == "pause") {
        if (!check_ && !link_refuses("Pausing")) set_paused(true, "shell");
    }
    else if (type == "resume") {
        if (menu_open_) close_menu("shell", false);
        set_paused(false, "shell");
    } else if (type == "menu" || type == "menu.open") {
        open_menu("shell");
    } else if (type == "menu.close") {
        close_menu("shell");
    } else if (type == "menu.pick") {
        if (!menu_open_) refresh_menu();
        menu_pick(str(msg, "id"), "shell");
    } else if (type == "menu.nav") {
        menu::NavKey k;
        if (!menu_open_) send("error", {{"message", "menu.nav needs the menu open."}, {"fatal", false}});
        else if (!menu::nav_key_from_name(str(msg, "key"), k))
            send("error", {{"message", "menu.nav key: up, down, left, right, next, previous, pick or back."}, {"fatal", false}});
        else menu_nav(k, false, "shell");
    } else if (type == "menu.state") {
        if (!menu_open_) refresh_menu();
        send_menu_state();
    } else if (type == "slots") {
        load_slots(msg.contains("slots") ? msg["slots"] : json::array());
    } else if (type == "rewind.enable") {
        if (!link_refuses("Rewind")) set_rewind_enabled(boolean(msg, "on", true), "shell");
    } else if (type == "cheat.toggle") {
        if (link_refuses("Cheats")) return;
        std::string id = msg.contains("id") && msg["id"].is_number_integer() ? std::to_string(msg["id"].get<long long>()) : str(msg, "id");
        toggle_cheat(id, boolean(msg, "on", true), "shell");
    } else if (type == "close") {
        running_ = false;
        close_reason_ = "shell";
    } else if (type == "snapshot.save") {
        if (link_refuses("Snapshots")) return;
        snapshot_save(msg.contains("slot") ? msg["slot"] : json(1), str(msg, "path"));
    } else if (type == "snapshot.load") {
        if (link_refuses("Snapshots")) return;
        std::string path = str(msg, "path"), err;
        if (path.empty() && msg.contains("slot")) path = slot_state_path(slot_name_of(msg["slot"]));
        if (!first_frame_sent_) pending_snapshot_ = path;
        else if (snapshot_load(path, err)) send("snapshotLoaded", {{"path", path}, {"by", "shell"}});
        else send("error", {{"message", "Couldn't load the snapshot: " + err}, {"fatal", false}});
    } else if (type == "cheats.set") {
        if (link_refuses("Cheats")) return;
        apply_cheats(msg.contains("cheats") ? msg["cheats"] : json::array());
    } else if (type == "ff") {
        if (link_refuses("Fast-forward")) return;
        set_ff(boolean(msg, "on", ff_on_), (int)number(msg, "speed", ff_speed_), "shell");
    } else if (type == "rewind") {
        if (link_refuses("Rewind")) return;
        rewind_held_proto_ = boolean(msg, "on", false);
        if (!rewind_enabled_ && rewind_held_proto_) send("warning", {{"message", "Rewind is off for this game."}});
    } else if (type == "mute") {
        set_mute(boolean(msg, "on", true), "shell");
    } else if (type == "volume") {
        set_volume((float)number(msg, "level", 1.0), "shell");
    } else if (type == "screenshot") {
        screenshot(str(msg, "path"));
    } else if (type == "layout") {
        apply_layout(msg);
    } else if (type == "display") {
        DisplaySettings d = video_.display();
        d.integer_scale = boolean(msg, "integerScale", d.integer_scale);
        d.smooth = boolean(msg, "smooth", d.smooth);
        video_.set_display(d);
        if (msg.contains("fullscreen")) video_.set_fullscreen(boolean(msg, "fullscreen", video_.fullscreen()));
        report_display("shell");
    } else if (type == "options.set") {
        json applied = json::array(), refused = json::array();
        if (msg.contains("options") && msg["options"].is_object())
            for (auto it = msg["options"].begin(); it != msg["options"].end(); ++it) {
                if (it.value().is_string() && options_.change(it.key(), it.value().get<std::string>()))
                    applied.push_back(it.key());
                else
                    refused.push_back(it.key());
            }
        send("optionsChanged", {{"applied", applied}, {"refused", refused}});
    } else if (type == "save.flush") {
        saves_.flush(core_, "shell");
        send("saveFlushed", {{"enabled", saves_.enabled()}});
    } else if (type == "link") {
        // Link sessions are set up in `start` (start.link); this asks how the link is doing.
        if (link_session_) send("linkStatus", link_json());
        else send("error", {{"message", "This game isn't a link session (link is set in start)."}, {"fatal", false}});
    } else if (type == "link.note") {
        // Celio Link: the shell's own status line for the corner (the stream here is only to the shell's bridge).
        if (link_session_) link_note_ = str(msg, "text");
    } else if (type == "start") {
        send("error", {{"message", "A game is already running."}, {"fatal", false}});
    } else if (type.rfind("debug.", 0) == 0) {
        if (!cfg_.test_hooks) {
            send("error", {{"message", "debug messages need --test-hooks"}, {"fatal", false}});
            return;
        }
        if (type == "debug.pokeSave") {
            std::vector<uint8_t> bytes;
            bool ok = from_hex(str(msg, "hex"), bytes) && saves_.poke(core_, (size_t)number(msg, "offset", 0), bytes);
            send("debug.result", {{"what", type}, {"ok", ok}});
        } else if (type == "debug.dumpSave") {
            std::string err;
            bool ok = saves_.dump(core_, str(msg, "path"), err);
            send("debug.result", {{"what", type}, {"ok", ok}, {"error", err}});
        } else if (type == "debug.button") {
            bool ok = input_.force_button((unsigned)number(msg, "port", 0), str(msg, "button"), boolean(msg, "on", true));
            send("debug.result", {{"what", type}, {"ok", ok}});
        } else if (type == "debug.readMemory") {
            // RETRO_MEMORY_* id (2 = system RAM), offset, length -> hex.
            unsigned id = (unsigned)number(msg, "id", RETRO_MEMORY_SYSTEM_RAM);
            size_t off = (size_t)number(msg, "offset", 0), len = (size_t)number(msg, "length", 1);
            size_t size = core_.get_memory_size(id);
            auto *mem = static_cast<const uint8_t *>(core_.get_memory_data(id));
            std::string hex;
            bool ok = mem && off + len <= size && len <= 4096;
            if (ok) {
                static const char *digits = "0123456789abcdef";
                for (size_t i = 0; i < len; i++) hex += digits[mem[off + i] >> 4], hex += digits[mem[off + i] & 15];
            }
            send("debug.result", {{"what", type}, {"ok", ok}, {"hex", hex}, {"size", size}});
        } else if (type == "debug.windowShot") {
            // The next drawn window (not headless) is read back and written as a PNG, with where the game is.
            window_shot_path_ = str(msg, "path");
            video_.request_window_capture();
        } else if (type == "debug.key") {
            // A real key press through SDL's event queue (the menu's keyboard path): {key: SDL key name, shift}.
            SDL_Scancode sc = SDL_GetScancodeFromName(str(msg, "key").c_str());
            bool ok = sc != SDL_SCANCODE_UNKNOWN && video_.window();
            if (ok) {
                SDL_Event e{};
                e.type = SDL_EVENT_KEY_DOWN;
                e.key.windowID = SDL_GetWindowID(video_.window());
                e.key.scancode = sc;
                e.key.key = SDL_GetKeyFromScancode(sc, SDL_KMOD_NONE, false);
                e.key.mod = boolean(msg, "shift", false) ? SDL_KMOD_LSHIFT : SDL_KMOD_NONE;
                e.key.down = true;
                SDL_PushEvent(&e);
                e.type = SDL_EVENT_KEY_UP, e.key.down = false;
                SDL_PushEvent(&e);
            }
            send("debug.result", {{"what", type}, {"ok", ok}});
        } else if (type == "debug.mouse") {
            // The mouse held at {x, y} (window coordinates), button {down}; {off: true} = the real mouse again.
            // Replies where the picture is and, for the 3DS, where on the bottom screen that touches (or not).
            bool off = boolean(msg, "off", false);
            float x = (float)number(msg, "x", 0), y = (float)number(msg, "y", 0);
            input_.force_mouse(!off, x, y, boolean(msg, "down", false));
            SDL_FRect g = video_.game_rect_window();
            json r = {{"what", "debug.mouse"}, {"ok", true}, {"game", {g.x, g.y, g.w, g.h}}};
            if (std::string(console_->key) == "3ds") {
                const N3dsLayout *l = n3ds_layout(n3ds_layout_, n3ds_flip_);
                N3dsTouch t;
                bool on = l && n3ds_touch_point(*l, g.x, g.y, g.w, g.h, x, y, t);
                r["touch"] = on ? json{{"x", t.x}, {"y", t.y}, {"frameX", t.frame_x}, {"frameY", t.frame_y}} : json(nullptr);
                if (l) r["frame"] = {l->frame_w, l->frame_h};
            }
            send("debug.result", r);
        } else if (type == "debug.pointer") {
            auto p = input_.pointer_seen();
            send("debug.result", {{"what", "debug.pointer"}, {"ok", true}, {"x", p.x}, {"y", p.y}, {"down", p.down},
                                  {"pressedReads", p.pressed_reads}});
        } else if (type == "debug.click") {
            // A left click at {x, y} (window coordinates) through SDL's event queue (the menu's mouse path).
            bool ok = video_.window() != nullptr;
            if (ok) {
                float x = (float)number(msg, "x", 0), y = (float)number(msg, "y", 0);
                SDL_WarpMouseInWindow(video_.window(), x, y);
                SDL_Event e{};
                e.type = SDL_EVENT_MOUSE_MOTION;
                e.motion.windowID = SDL_GetWindowID(video_.window());
                e.motion.x = x, e.motion.y = y;
                SDL_PushEvent(&e);
                SDL_Event d{};
                d.type = SDL_EVENT_MOUSE_BUTTON_DOWN;
                d.button.windowID = e.motion.windowID;
                d.button.button = SDL_BUTTON_LEFT, d.button.down = true, d.button.clicks = 1;
                d.button.x = x, d.button.y = y;
                SDL_PushEvent(&d);
                d.type = SDL_EVENT_MOUSE_BUTTON_UP, d.button.down = false;
                SDL_PushEvent(&d);
            }
            send("debug.result", {{"what", type}, {"ok", ok}});
        } else if (type == "debug.pad") {
            // A virtual controller (SDL's own), so the controller paths are tested without a real one.
            // {attach:true} first, then {button: south|east|west|north|back|guide|start|l1|r1|up|down|left|right, on}.
            static const char *names[] = {"south", "east", "west", "north", "back", "guide", "start", "l3", "r3",
                                          "l1", "r1", "up", "down", "left", "right"};
            bool ok = true;
            if (!test_pad_) {
                SDL_VirtualJoystickDesc d;
                SDL_INIT_INTERFACE(&d);
                d.type = SDL_JOYSTICK_TYPE_GAMEPAD;
                d.nbuttons = 15, d.button_mask = (1u << 15) - 1;
                d.naxes = 6, d.axis_mask = 0x3f;
                d.name = "Shoalcade test controller";
                SDL_JoystickID id = SDL_AttachVirtualJoystick(&d);
                test_pad_ = id ? SDL_OpenJoystick(id) : nullptr;
                ok = test_pad_ != nullptr;
            }
            std::string name = str(msg, "button");
            if (ok && !name.empty()) {
                int b = -1;
                for (int i = 0; i < 15; i++)
                    if (name == names[i]) b = i;
                ok = b >= 0 && SDL_SetJoystickVirtualButton(test_pad_, b, boolean(msg, "on", true));
            }
            send("debug.result", {{"what", type}, {"ok", ok}});
        } else if (type == "debug.stats") {
            json st = {{"audio", audio_.stats()}, {"shownFrames", shown_frames_}, {"coreFrames", core_frames_},
                       {"rewindBytes", rewind_.bytes()}, {"rewindSteps", rewind_.steps()}};
            if (link_session_) st["link"] = link_json();
            send("stats", st);
        } else if (type == "debug.frame") {
            send_frame_info();
        } else {
            send("error", {{"message", "unknown debug message " + type}, {"fatal", false}});
        }
    } else {
        send("error", {{"message", "Unknown message type \"" + type + "\"."}, {"fatal", false}});
    }
}

void Player::run_frame() {
    update_speed();
    if (rewinding_) {
        if (rewind_.step_back(rewind_scratch_)) {
            core_.unserialize(rewind_scratch_.data(), rewind_scratch_.size());
        }
        // One frame to show where we are (silent); not added to the ring.
        if (have_frame_time_) frame_time_.callback(frame_time_.reference);
        core_.run();
        core_frames_++;
        audio_stage_.clear();
        return;
    }
    int runs = ff_effective_ ? ff_speed_ : 1;
    for (int i = 0; i < runs; i++) {
        if (rewind_enabled_ && core_frames_ % rewind_interval_ == 0) {
            size_t size = core_.serialize_size();
            rewind_scratch_.resize(size);
            if (size && core_.serialize(rewind_scratch_.data(), size)) rewind_.push(rewind_scratch_);
        }
        // Fast-forward: only the last run of a shown frame needs its picture (GL cores always draw).
        av_enable_ = (i + 1 < runs && !hw_requested_) ? 2 : 3;
        if (have_frame_time_) frame_time_.callback(frame_time_.reference);
        if (link_session_) link_.before_run(); // linkshim.c retro_run: lost -> start -> read -> poll
        core_.run();
        if (link_session_) link_.after_run();
        core_frames_++;
    }
    av_enable_ = 3;
    if (!audio_stage_.empty()) {
        audio_.push(audio_stage_.data(), audio_stage_.size() / 2);
        audio_stage_.clear();
    }
    audio_.update();
}

void Player::loop(Inbox &inbox) {
    double period = 1.0 / (av_.timing.fps > 0 ? av_.timing.fps : 60.0);
    double next = now_s();
    double last = now_s();
    last_save_flush_ = last_play_report_ = now_s();
    if (pending_cheats_.is_array()) apply_cheats(pending_cheats_);

    while (running_) {
        // 1. Window and controller events.
        SDL_Event e;
        while (SDL_PollEvent(&e)) {
            if (e.type == SDL_EVENT_QUIT || e.type == SDL_EVENT_WINDOW_CLOSE_REQUESTED) {
                running_ = false;
                close_reason_ = "window";
            } else if (e.type == SDL_EVENT_WINDOW_FOCUS_GAINED) focused_ = true;
            else if (e.type == SDL_EVENT_WINDOW_FOCUS_LOST) focused_ = false;
            else if (e.type == SDL_EVENT_WINDOW_LEAVE_FULLSCREEN && video_.fullscreen()) {
                video_.note_fullscreen(false); // left full screen another way (the window manager)
                report_display("user");
            }
            if (menu_open_) {
                // Everything goes to the menu; the game is paused and gets nothing.
                ui_.event(e);
                if (e.type == SDL_EVENT_GAMEPAD_ADDED || e.type == SDL_EVENT_GAMEPAD_REMOVED) input_.handle_event(e);
                else menu_event(e);
                continue;
            }
            if (e.type == SDL_EVENT_GAMEPAD_BUTTON_DOWN) {
                // Controller: the Guide button, or Start and Select together, opens the menu.
                SDL_Gamepad *pad = SDL_GetGamepadFromID(e.gbutton.which);
                auto b = (SDL_GamepadButton)e.gbutton.button;
                bool chord = pad && ((b == SDL_GAMEPAD_BUTTON_START && SDL_GetGamepadButton(pad, SDL_GAMEPAD_BUTTON_BACK)) ||
                                     (b == SDL_GAMEPAD_BUTTON_BACK && SDL_GetGamepadButton(pad, SDL_GAMEPAD_BUTTON_START)));
                if (b == SDL_GAMEPAD_BUTTON_GUIDE || chord) {
                    open_menu("user");
                    continue;
                }
            }
            switch (input_.handle_event(e)) {
            case Hotkey::Pause:
                if (!check_ && !link_refuses("Pausing")) set_paused(!paused_, "user");
                break;
            case Hotkey::Flip:
                if (std::string(console_->key) == "3ds" && !check_) set_n3ds_layout(n3ds_layout_, !n3ds_flip_, "user");
                break;
            case Hotkey::Menu: open_menu("user"); break;
            case Hotkey::Fullscreen: set_fullscreen(!video_.fullscreen(), "user"); break;
            case Hotkey::QuickSave:
                if (!link_refuses("Snapshots")) snapshot_save(json(1), "", "user");
                break;
            case Hotkey::QuickLoad: {
                if (link_refuses("Snapshots")) break;
                std::string err, path = states_dir_.empty() ? "" : join_path(states_dir_, "slot1.state");
                if (path.empty() || !snapshot_load(path, err))
                    send("error", {{"message", "Couldn't load snapshot 1: " + (path.empty() ? std::string("no statesDir") : err)}, {"fatal", false}});
                else send("snapshotLoaded", {{"path", path}, {"slot", "1"}, {"by", "user"}});
                break;
            }
            case Hotkey::Screenshot: screenshot(""); break;
            default: break;
            }
        }
        // 2. Messages from the shell (and test messages due at this frame).
        while (auto m = inbox.pop()) handle(*m);
        for (auto it = cfg_.scheduled.begin(); it != cfg_.scheduled.end() && it->first <= shown_frames_;) {
            handle(it->second);
            it = cfg_.scheduled.erase(it);
        }
        if (cfg_.start_from_stdin && inbox.eof()) {
            running_ = false;
            close_reason_ = "shellGone";
        }
        if (!running_) break;
        menu_tick();

        double t = now_s();
        if (paused_) {
            video_.present();
            after_present();
            if (!running_) break;
            if (auto m = inbox.wait(16)) handle(*m); // wait() takes the message off the queue: handle it here
            next = last = now_s();
            last_save_flush_ = now_s(); // the pause wrote the save already
            continue;
        }

        // 3. Emulate one shown frame (several when fast-forwarding).
        run_frame();
        shown_frames_++;
        video_.present();
        after_present();
        if (!first_frame_sent_ && video_.has_frame()) {
            first_frame_sent_ = true;
            send("firstFrame", {{"n", shown_frames_}});
        }
        if (!pending_snapshot_.empty() && first_frame_sent_) {
            std::string err, path = pending_snapshot_;
            pending_snapshot_.clear();
            if (snapshot_load(path, err)) send("snapshotLoaded", {{"path", path}});
            else send("error", {{"message", "Couldn't load the snapshot: " + err}, {"fatal", false}});
        }
        if (cfg_.hash_every && shown_frames_ % cfg_.hash_every == 0) send_frame_info();
        if (check_ && first_frame_sent_) check_tick();

        // 4. Play time, the 30-second save, the frame limit.
        double now = now_s();
        if (cfg_.headless || focused_) play_s_ += std::min(now - last, 0.25);
        last = now;
        report_play_time(false);
        if (now - last_save_flush_ >= save_interval_s_) {
            saves_.flush(core_, "timer");
            last_save_flush_ = now;
        }
        if (cfg_.max_frames && shown_frames_ >= cfg_.max_frames) {
            running_ = false;
            close_reason_ = "frames";
        }

        // 5. Pace to the core's frame rate.
        if (!cfg_.fast) {
            next += period;
            double wait = next - now_s();
            if (wait > 0) SDL_DelayPrecise((Uint64)(wait * 1e9));
            else if (wait < -3 * period) next = now_s(); // fell behind (slow PC, debugger): don't race to catch up
        }
        (void)t;
    }
}

void Player::after_present() {
    RgbaImage win;
    if (video_.take_window_capture(win)) {
        std::string err;
        bool ok = !window_shot_path_.empty() && write_png(window_shot_path_, win, &err);
        SDL_FRect g = video_.game_rect_window();
        int ww = 0, wh = 0;
        SDL_GetWindowSize(video_.window(), &ww, &wh);
        send("debug.result", {{"what", "debug.windowShot"}, {"ok", ok}, {"path", window_shot_path_},
                              {"width", win.width}, {"height", win.height}, {"nonBlack", non_black_pixels(win)},
                              {"game", {g.x, g.y, g.w, g.h}}, {"window", {ww, wh}}, {"menu", menu_open_}});
    }
    // Mouse picks made while the menu was drawn (they are carried out here, outside the drawing).
    MenuUi::Out o = std::move(ui_out_);
    ui_out_ = MenuUi::Out{};
    if (!o.slider.empty()) menu_adjust(o.slider, o.slider_value, "user");
    if (!o.pick.empty()) {
        show_focus_ = false;
        menu_pick(o.pick, "user");
    } else if (o.close) {
        close_menu("user");
    }
}

bool Player::can_snapshot() const {
    if (can_snapshot_ < 0) {
        bool yes = const_cast<Core &>(core_).serialize_size() > 0;
        if (!yes) return false; // not cached: some cores can only snapshot once the game is running
        can_snapshot_ = 1;
    }
    return can_snapshot_ == 1;
}

void Player::check_tick() {
    // Game3dsActivity.checkTick: once a second, count down, keep the measured seconds (frames shown that second).
    double now = now_s();
    if (check_next_ == 0) {
        check_next_ = now + 1;
        check_frames_at_ = shown_frames_;
        return;
    }
    while (running_ && now >= check_next_) {
        int f = (int)(shown_frames_ - check_frames_at_);
        check_frames_at_ = shown_frames_;
        check_next_ += 1;
        check_second_++;
        if (check_second_ > check_warm_) check_fps_.push_back(f);
        int left = check_warm_ + check_measure_ - check_second_;
        bool warming = check_second_ <= check_warm_;
        // The phone's words (N3dsCheck / Game3dsActivity), on this PC's corner line.
        check_line_ = warming ? "3DS check: warming up... " + std::to_string(left) + " s"
                              : "3DS check: measuring... " + std::to_string(left) + " s \u00b7 " + std::to_string(f) + " fps";
        send("checkTick", {{"second", check_second_}, {"phase", warming ? "warmUp" : "measure"}, {"left", left}, {"fps", f}});
        if (left <= 0) stop_check("done");
    }
}

void Player::stop_check(const char *reason) {
    if (!check_ || !check_reason_.empty()) return;
    check_reason_ = reason;
    running_ = false;
    close_reason_ = "check";
}

void Player::shutdown(const char *reason) {
    if (loaded_) {
        saves_.flush(core_, "close");
        report_play_time(true);
        if (cfg_.hash_every) send_frame_info();
        json st = {{"audio", audio_.stats()}, {"shownFrames", shown_frames_}, {"coreFrames", core_frames_},
                   {"rewindBytes", rewind_.bytes()}, {"rewindSteps", rewind_.steps()}};
        if (link_session_) st["link"] = link_json();
        send("stats", st);
        // PPSSPP boots on its own thread; closing before that finishes crashes inside the core's retro_unload_game
        // (seen with the buildbot core, 31/10/2026). It makes sound once booted, so wait for that (max 5 s, silent).
        if (sys_info_.library_name && std::strcmp(sys_info_.library_name, "PPSSPP") == 0 && !core_sound_seen_) {
            LOGI("closing: waiting for PPSSPP to finish booting");
            ff_effective_ = true; // keeps the sound out of the speakers
            for (double end = now_s() + 5; now_s() < end && !core_sound_seen_;) {
                core_.run();
                SDL_Delay(10);
            }
        }
        // GL context first: PPSSPP's retro_unload_game deletes the object its context_destroy uses.
        if (hw_requested_ && hw_.context_destroy) {
            LOGI("closing: GL context destroy");
            hw_.context_destroy();
        }
        if (link_session_) link_.shutdown(); // linkshim.c: stop the core's session before retro_unload_game
        LOGI("closing: unload game");
        core_.unload_game();
        loaded_ = false;
    }
    if (inited_) {
        LOGI("closing: core deinit");
        core_.deinit();
        inited_ = false;
    }
    audio_.close();
    input_.close();
    video_.overlay = nullptr;
    ui_.shutdown();
    video_.destroy();
    LOGI("closing: done");
    // The core library stays loaded until the process ends: some cores' static destructors misbehave on dlclose.
    (void)reason;
}

int Player::run(const json &start_msg, Inbox &inbox) {
    std::string err;
    if (!start(start_msg, err)) {
        LOGE("start failed: %s", err.c_str());
        send("error", {{"message", err}, {"fatal", true}});
        send("closing", {{"reason", "error"}});
        shutdown("error");
        send("exited", {{"code", 1}});
        return 1;
    }
    loop(inbox);
    if (close_reason_.empty()) close_reason_ = "window";
    // The browser's Automatic snapshot: taken when you leave the game (if the shell asked for it).
    if (auto_snapshot_on_close_ && snapshots_allowed_ && first_frame_sent_ && close_reason_ != "core" &&
        can_snapshot() && !states_dir_.empty())
        snapshot_save(json("auto"), "", "close");
    if (check_) {
        bool done = check_reason_ == "done";
        send("checkResult", {{"done", done}, {"reason", check_reason_.empty() ? close_reason_ : check_reason_},
                             {"fps", check_fps_}, {"warmUp", check_warm_}, {"measure", check_measure_}});
    }
    json closing = {{"reason", close_reason_}};
    if (close_reason_ == "menu") closing["upload"] = close_upload_; // the shell uploads for "Close Game (and Upload)"
    send("closing", closing);
    shutdown(close_reason_.c_str());
    send("exited", {{"code", 0}});
    return 0;
}
// ---------------------------------------------------------------------------------------------------------------
// The pause menu. What shows is menu.cpp (the phone's GameMenu.kt); the drawing is menu_ui.cpp. Every pick also
// arrives as `menu.pick {id}`, and every change is reported to the shell.

menu::Context Player::menu_context() const {
    menu::Context c;
    c.console = console_->key;
    c.can_upload = can_upload_;
    c.snapshots = snapshots_allowed_ && can_snapshot();
    c.slots = slots_;
    c.speed_allowed = speed_allowed_;
    c.max_ff = console_->max_ff;
    c.ff_on = ff_on_;
    c.ff_speed = ff_speed_;
    c.rewind_possible = console_->rewind && rewind_allowed_ && can_snapshot();
    c.rewind_on = rewind_enabled_;
    c.cheats_allowed = cheats_allowed_;
    c.cheats = cheats_;
    c.muted = audio_.muted();
    c.volume = (int)std::lround(audio_.volume() * 100);
    c.fullscreen = video_.fullscreen();
    c.integer_scale = video_.display().integer_scale;
    c.smooth = video_.display().smooth;
    c.ds = ds_layout_;
    c.ds_swap = ds_swap_;
    c.n3ds = n3ds_layout_;
    c.n3ds_flip = n3ds_flip_;
    for (const auto &l : input_.describe_buttons()) c.controls.push_back({l.what, l.keys, l.pad});
    for (const auto &l : input_.describe_hotkeys()) c.shortcuts.push_back({l.what, l.keys, l.pad});
    c.ff_key = input_.hotkey_keys(Hotkey::FastForward);
    c.rewind_key = input_.hotkey_keys(Hotkey::Rewind);
    c.armed = armed_;
    return c;
}

void Player::refresh_menu(bool reset_focus) {
    sections_ = menu::build(menu_context());
    nav_.set_sections(sections_, reset_focus);
}

void Player::open_menu(const char *by) {
    if (check_) { // as the phone: no menu during the 3DS check; Esc (Home / Back on the phone) stops it
        stop_check("cancelled");
        return;
    }
    if (menu_open_) {
        if (std::string(by) == "user") close_menu(by); // the same key again closes it
        return;
    }
    menu_was_paused_ = paused_;
    // A link session is never paused (GameActivity.pauseForMenu): the other side would lose the link.
    if (!link_session_) set_paused(true, "menu");
    menu_open_ = true;
    show_focus_ = false;
    scroll_to_top_ = true;
    armed_.clear();
    pad_repeat_dir_ = 0;
    refresh_menu(true);
    nav_.reset();
    send("menuOpened", {{"by", by}});
}

void Player::close_menu(const char *by, bool resume) {
    if (!menu_open_) return;
    menu_open_ = false;
    armed_.clear();
    pad_repeat_dir_ = 0;
    input_.block_until_released(); // the key or button that closed it mustn't reach the game
    send("menuClosed", {{"by", by}});
    if (resume && !menu_was_paused_) set_paused(false, "menu");
}

void Player::toast(const std::string &text, double seconds) {
    toast_ = text;
    toast_until_ = now_s() + seconds;
}

void Player::menu_tick() {
    double now = now_s();
    if (!toast_.empty() && now >= toast_until_) toast_.clear();
    if (!armed_.empty() && now - armed_at_ > 5) {
        armed_.clear(); // a second press has to come within 5 s
        if (menu_open_) refresh_menu();
    }
    if (menu_open_ && pad_repeat_dir_ && now >= pad_repeat_at_) {
        menu_nav(pad_repeat_dir_ < 0 ? menu::NavKey::Up : menu::NavKey::Down, true, "user");
        pad_repeat_at_ = now + 0.11;
    }
}

void Player::menu_nav(menu::NavKey k, bool repeat, const char *by) {
    if (!menu_open_) return;
    bool moving = k == menu::NavKey::Up || k == menu::NavKey::Down || k == menu::NavKey::NextSection ||
                  k == menu::NavKey::PreviousSection;
    if (moving && !show_focus_) {
        // The first press only shows where the highlight is (on Resume), so it never skips a row unseen.
        show_focus_ = scroll_to_focus_ = true;
        return;
    }
    auto r = nav_.key(k, repeat);
    switch (r.type) {
    case menu::DrawerNav::Result::Moved: show_focus_ = scroll_to_focus_ = true; break;
    case menu::DrawerNav::Result::Select: menu_pick(r.id, by); break;
    case menu::DrawerNav::Result::Close: close_menu(by); break;
    case menu::DrawerNav::Result::Adjust: {
        const menu::Item *it = menu::find(sections_, r.id);
        if (it) menu_adjust(r.id, std::clamp(it->value + r.by * 10, 0, 100), by);
        break;
    }
    default: break;
    }
}

void Player::menu_event(const SDL_Event &e) {
    using menu::NavKey;
    if (e.type == SDL_EVENT_KEY_DOWN) {
        bool rep = e.key.repeat;
        switch (e.key.scancode) {
        case SDL_SCANCODE_UP: menu_nav(NavKey::Up, rep, "user"); break;
        case SDL_SCANCODE_DOWN: menu_nav(NavKey::Down, rep, "user"); break;
        case SDL_SCANCODE_LEFT: menu_nav(NavKey::Left, rep, "user"); break;
        case SDL_SCANCODE_RIGHT: menu_nav(NavKey::Right, rep, "user"); break;
        case SDL_SCANCODE_TAB:
            menu_nav((e.key.mod & SDL_KMOD_SHIFT) ? NavKey::PreviousSection : NavKey::NextSection, rep, "user");
            break;
        case SDL_SCANCODE_PAGEDOWN: menu_nav(NavKey::NextSection, rep, "user"); break;
        case SDL_SCANCODE_PAGEUP: menu_nav(NavKey::PreviousSection, rep, "user"); break;
        case SDL_SCANCODE_RETURN:
        case SDL_SCANCODE_KP_ENTER:
        case SDL_SCANCODE_SPACE:
            if (!(e.key.mod & SDL_KMOD_ALT)) menu_nav(NavKey::Pick, rep, "user");
            else if (!rep) set_fullscreen(!video_.fullscreen(), "user"); // Alt+Enter still works
            break;
        case SDL_SCANCODE_ESCAPE:
        case SDL_SCANCODE_BACKSPACE: menu_nav(NavKey::Back, rep, "user"); break;
        default: break;
        }
    } else if (e.type == SDL_EVENT_GAMEPAD_BUTTON_DOWN) {
        SDL_Gamepad *pad = SDL_GetGamepadFromID(e.gbutton.which);
        auto b = (SDL_GamepadButton)e.gbutton.button;
        switch (b) {
        case SDL_GAMEPAD_BUTTON_DPAD_UP:
        case SDL_GAMEPAD_BUTTON_DPAD_DOWN:
            pad_repeat_dir_ = b == SDL_GAMEPAD_BUTTON_DPAD_UP ? -1 : 1;
            pad_repeat_at_ = now_s() + 0.4;
            menu_nav(pad_repeat_dir_ < 0 ? NavKey::Up : NavKey::Down, false, "user");
            return;
        case SDL_GAMEPAD_BUTTON_DPAD_LEFT: menu_nav(NavKey::Left, false, "user"); return;
        case SDL_GAMEPAD_BUTTON_DPAD_RIGHT: menu_nav(NavKey::Right, false, "user"); return;
        case SDL_GAMEPAD_BUTTON_LEFT_SHOULDER: menu_nav(NavKey::PreviousSection, false, "user"); return;
        case SDL_GAMEPAD_BUTTON_RIGHT_SHOULDER: menu_nav(NavKey::NextSection, false, "user"); return;
        case SDL_GAMEPAD_BUTTON_GUIDE: menu_nav(NavKey::Back, false, "user"); return;
        case SDL_GAMEPAD_BUTTON_START:
        case SDL_GAMEPAD_BUTTON_BACK:
            if (pad && SDL_GetGamepadButton(pad, b == SDL_GAMEPAD_BUTTON_START ? SDL_GAMEPAD_BUTTON_BACK : SDL_GAMEPAD_BUTTON_START))
                menu_nav(NavKey::Back, false, "user"); // Start + Select again closes it
            return;
        default: break;
        }
        // "A pick, B back" by the button's own label: A / Cross picks and B / Circle goes back, whatever the make
        // (Xbox A is the bottom button, Nintendo A the right one).
        SDL_GamepadButtonLabel label = pad ? SDL_GetGamepadButtonLabel(pad, b) : SDL_GAMEPAD_BUTTON_LABEL_UNKNOWN;
        if (label == SDL_GAMEPAD_BUTTON_LABEL_A || label == SDL_GAMEPAD_BUTTON_LABEL_CROSS) menu_nav(NavKey::Pick, false, "user");
        else if (label == SDL_GAMEPAD_BUTTON_LABEL_B || label == SDL_GAMEPAD_BUTTON_LABEL_CIRCLE) menu_nav(NavKey::Back, false, "user");
        else if (label == SDL_GAMEPAD_BUTTON_LABEL_UNKNOWN && b == SDL_GAMEPAD_BUTTON_SOUTH) menu_nav(NavKey::Pick, false, "user");
        else if (label == SDL_GAMEPAD_BUTTON_LABEL_UNKNOWN && b == SDL_GAMEPAD_BUTTON_EAST) menu_nav(NavKey::Back, false, "user");
    } else if (e.type == SDL_EVENT_GAMEPAD_BUTTON_UP) {
        auto b = (SDL_GamepadButton)e.gbutton.button;
        if (b == SDL_GAMEPAD_BUTTON_DPAD_UP || b == SDL_GAMEPAD_BUTTON_DPAD_DOWN) pad_repeat_dir_ = 0;
    } else if (e.type == SDL_EVENT_GAMEPAD_AXIS_MOTION && e.gaxis.axis == SDL_GAMEPAD_AXIS_LEFTY) {
        auto r = nav_.motion(e.gaxis.value / 32767.0f);
        if (r.type == menu::DrawerNav::Result::Moved) show_focus_ = scroll_to_focus_ = true;
    }
}

void Player::menu_pick(const std::string &id, const char *by) {
    menu::Context ctx = menu_context();
    const menu::Item *item = menu::find(sections_, id);
    if (!item || !item->selectable()) {
        send("error", {{"message", "There's no \"" + id + "\" in the menu here."}, {"fatal", false}});
        return;
    }
    if (menu::needs_confirm(ctx, id) && !(armed_ == id && now_s() - armed_at_ <= 5)) {
        // Load (play since then is lost) and Save over (replaces a snapshot) need a second press, as in the browser.
        armed_ = id;
        armed_at_ = now_s();
        refresh_menu();
        send("menuConfirm", {{"id", id}, {"by", by}});
        return;
    }
    armed_.clear();
    const std::string snap_save = "snap.save.", snap_load = "snap.load.";
    if (id == menu::ID_RESUME) {
        close_menu(by);
    } else if (id == menu::ID_SCREENSHOT) {
        close_menu(by);
        toast(screenshot("") ? "Screenshot saved." : "Couldn't save the screenshot.");
    } else if (id == menu::ID_CLOSE || id == menu::ID_CLOSE_UPLOAD) {
        // The player writes the save as it closes; the shell uploads it for "(and Upload)" (closing.upload).
        close_upload_ = id == menu::ID_CLOSE_UPLOAD;
        close_reason_ = "menu";
        running_ = false;
        return;
    } else if (id.rfind(snap_save, 0) == 0) {
        std::string slot = id.substr(snap_save.size());
        close_menu(by);
        if (snapshot_save(json(slot), "", by)) toast("Saved to " + menu::slot_label(slot) + ".");
        else toast("Couldn't save the snapshot.");
    } else if (id.rfind(snap_load, 0) == 0) {
        std::string slot = id.substr(snap_load.size()), err, path = slot_state_path(slot);
        close_menu(by);
        if (snapshot_load(path, err)) {
            send("snapshotLoaded", {{"path", path}, {"slot", slot}, {"by", by}});
            toast("Loaded " + menu::slot_label(slot) + ".");
        } else {
            send("error", {{"message", "Couldn't load the snapshot: " + err}, {"fatal", false}});
            toast("Couldn't load " + menu::slot_label(slot) + ".");
        }
    } else if (id == menu::ID_FAST_FORWARD) {
        set_ff(!ff_on_, ff_speed_, by);
    } else if (int n = menu::speed_of(id)) {
        set_ff(ff_on_, n, by);
    } else if (id == menu::ID_REWIND) {
        set_rewind_enabled(!rewind_enabled_, by);
    } else if (id.rfind("cheat.", 0) == 0) {
        toggle_cheat(id.substr(6), !item->on, by);
    } else if (id == menu::ID_MUTE) {
        set_mute(!audio_.muted(), by);
    } else if (id == menu::ID_WINDOW || id == menu::ID_FULLSCREEN) {
        set_fullscreen(id == menu::ID_FULLSCREEN, by);
    } else if (id == menu::ID_INTEGER || id == menu::ID_SMOOTH) {
        DisplaySettings d = video_.display();
        if (id == menu::ID_INTEGER) d.integer_scale = !d.integer_scale;
        else d.smooth = !d.smooth;
        video_.set_display(d);
        report_display(by);
    } else if (id == menu::ID_DS_SWAP) {
        set_ds_layout(ds_layout_, !ds_swap_, by);
    } else if (id.rfind("ds.", 0) == 0) {
        set_ds_layout(id.substr(3), ds_swap_, by);
    } else if (id == menu::ID_3DS_FLIP) {
        apply_layout(json{{"n3ds", n3ds_layout_}, {"flip", !n3ds_flip_}}, by);
    } else if (id.rfind("3ds.", 0) == 0) {
        apply_layout(json{{"n3ds", id.substr(4)}, {"flip", n3ds_flip_}}, by);
    }
    // Volume (a slider) and the Controls lines do nothing when picked.
    if (menu_open_) refresh_menu();
}

void Player::menu_adjust(const std::string &id, int value, const char *by) {
    if (id == menu::ID_VOLUME) set_volume(std::clamp(value, 0, 100) / 100.0f, by);
    if (menu_open_) refresh_menu();
}

void Player::set_ff(bool on, int speed, const char *by) {
    ff_on_ = on;
    ff_speed_ = std::clamp(speed, 1, console_->max_ff);
    send("ffChanged", {{"on", ff_on_}, {"speed", ff_speed_}, {"by", by}});
    if (menu_open_) refresh_menu();
}

void Player::set_rewind_enabled(bool on, const char *by) {
    if (on && !(console_->rewind && rewind_allowed_ && can_snapshot())) {
        send("warning", {{"message", std::string("Rewind isn't available for ") + console_->short_name + " here."}});
        return;
    }
    if (on && !rewind_enabled_) rewind_.configure(rewind_mb_ * 1024 * 1024);
    rewind_enabled_ = on;
    if (!on) {
        rewind_held_proto_ = false;
        rewind_.clear(); // frees the memory
    }
    send("rewindChanged", {{"enabled", rewind_enabled_}, {"by", by}});
    if (menu_open_) refresh_menu();
}

void Player::set_mute(bool on, const char *by) {
    audio_.set_muted(on);
    send("muteChanged", {{"on", on}, {"by", by}});
}

void Player::set_volume(float level, const char *by) {
    level = std::clamp(level, 0.0f, 1.0f);
    audio_.set_volume(level);
    send("volumeChanged", {{"level", std::round(level * 100.0) / 100.0}, {"by", by}});
}

void Player::set_fullscreen(bool on, const char *by) {
    video_.set_fullscreen(on);
    report_display(by);
}

void Player::report_display(const char *by) {
    send("displayChanged", {{"fullscreen", video_.fullscreen()}, {"integerScale", video_.display().integer_scale},
                            {"smooth", video_.display().smooth}, {"by", by}});
    if (menu_open_) refresh_menu();
}

void Player::toggle_cheat(const std::string &id, bool on, const char *by) {
    for (size_t i = 0; i < cheats_.size(); i++) {
        if (cheats_[i].id != id) continue;
        cheats_[i].on = on;
        int count = (int)std::count_if(cheats_.begin(), cheats_.end(), [](const menu::Cheat &c) { return c.on; });
        // The shell owns the cheat list: it hears which switch changed, then what the core was given.
        send("cheatToggled", {{"id", id}, {"index", i}, {"on", on}, {"count", count}, {"by", by}});
        apply_cheat_list();
        if (menu_open_) refresh_menu();
        return;
    }
    send("error", {{"message", "No cheat \"" + id + "\"."}, {"fatal", false}});
}

std::string Player::slot_state_path(const std::string &slot) const {
    for (const auto &s : slots_)
        if (s.slot == slot && !s.path.empty()) return s.path;
    return states_dir_.empty() ? "" : join_path(states_dir_, "slot" + safe_name(slot) + ".state");
}

void Player::scan_slots() {
    // What is already in statesDir (the shell can say more with `slots`).
    slots_.clear();
    for (const char *slot : {"auto", "1", "2", "3"}) {
        menu::SlotInfo s;
        s.slot = slot;
        if (!states_dir_.empty()) {
            std::string state = join_path(states_dir_, std::string("slot") + slot + ".state");
            std::string png = join_path(states_dir_, std::string("slot") + slot + ".png");
            if (file_exists(state)) {
                s.has = true, s.path = state, s.when = when_text(file_mtime(state));
                if (file_exists(png)) s.png = png;
            }
        }
        slots_.push_back(s);
    }
}

void Player::load_slots(const json &list) {
    // [{slot, when, png, path, loadable, note, empty}], from the shell (it owns the server copies).
    if (!list.is_array()) return;
    for (const auto &e : list) {
        if (!e.is_object() || !e.contains("slot")) continue;
        std::string name = slot_name_of(e["slot"]);
        for (auto &s : slots_) {
            if (s.slot != name) continue;
            s.has = !boolean(e, "empty", false);
            s.loadable = boolean(e, "loadable", true);
            s.when = str(e, "when");
            s.png = str(e, "png");
            s.path = str(e, "path");
            s.note = str(e, "note");
        }
    }
    ui_.forget_pictures();
    if (menu_open_) refresh_menu();
}

void Player::draw_overlay() {
    if (!ui_.ready() || (!menu_open_ && toast_.empty() && !link_session_ && !check_)) return;
    MenuUi::View v;
    v.open = menu_open_;
    v.title = title_;
    v.sections = &sections_;
    v.focus = nav_.focus();
    v.show_focus = show_focus_;
    v.scroll_to_focus = scroll_to_focus_;
    v.scroll_to_top = scroll_to_top_;
    v.toast = toast_;
    v.corner = check_ ? check_line_ : link_corner();
    MenuUi::Out o = ui_.draw(v);
    scroll_to_focus_ = scroll_to_top_ = false;
    if (!o.pick.empty() || o.close || !o.slider.empty()) ui_out_ = o;
}

void Player::send_menu_state() {
    static const char *kinds[] = {"action", "choice", "toggle", "slider", "info", "heading", "note", "slot"};
    json secs = json::array();
    for (const auto &s : sections_) {
        json items = json::array();
        for (const auto &i : s.items) {
            json j = {{"id", i.id}, {"kind", kinds[(int)i.kind]}, {"label", i.label}};
            if (!i.hint.empty()) j["hint"] = i.hint;
            if (i.kind == menu::Kind::Toggle || i.kind == menu::Kind::Choice) j["on"] = i.on;
            if (i.kind == menu::Kind::Slider) j["value"] = i.value;
            if (!i.picture.empty()) j["picture"] = i.picture;
            float r[4];
            if (menu_open_ && ui_.rect_of(i.id, r)) j["rect"] = {r[0], r[1], r[2], r[3]};
            items.push_back(j);
        }
        secs.push_back({{"id", s.id}, {"title", s.title}, {"items", items}});
    }
    send("menuState", {{"open", menu_open_}, {"focus", nav_.focus()}, {"showFocus", show_focus_}, {"armed", armed_},
                       {"drawn", ui_.ready()}, {"sections", secs}});
}
} // namespace sc
