/*
 * Shoalcade link shim (GPL-3.0, part of Shoalcade).
 *
 * LibretroDroid has no netplay, so it can't give cores the libretro "netpacket" interface
 * (RETRO_ENVIRONMENT_SET_NETPACKET_INTERFACE, 78). This library pretends to be the core:
 * it loads the real core (gpSP or melonDS DS), forwards every call, and answers env call 78
 * itself, carrying the core's packets over a TCP socket that Kotlin has already connected
 * to the other phone.
 *
 * Configured by environment variables, set by Kotlin before the game starts:
 *   SC_REAL_CORE  absolute path of the real core .so
 *   SC_LINK_FD    connected TCP socket (owned by this library from now on)
 *   SC_LINK_ROLE  "host" (client id 0, link master) or "join" (client id 1)
 *
 * Wire format (both directions): u32 big-endian payload length, then the payload.
 * Rules follow RetroArch's behaviour (see docs/multiplayer.md):
 *  - host: start(0) then connected(1); joiner: start(1). Both already connected by then.
 *  - each frame: read socket -> receive() for each packet -> poll() -> retro_run.
 *  - all sends are reliable (TCP); sends addressed to ourselves are dropped.
 */
#include <arpa/inet.h>
#include <dlfcn.h>
#include <errno.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>
#include <android/log.h>

#define TAG "SCLink"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define EXPORT __attribute__((visibility("default")))

/* ---- The parts of libretro.h we need (ABI-identical copies) ---------------------------- */

#define RETRO_ENVIRONMENT_SET_NETPACKET_INTERFACE 78
#define RETRO_NETPACKET_BROADCAST 0xFFFF
#define MAX_PACKET (64 * 1024)

typedef bool (*retro_environment_t)(unsigned cmd, void *data);
typedef void (*retro_netpacket_send_t)(int flags, const void *buf, size_t len, uint16_t client_id);
typedef void (*retro_netpacket_poll_receive_t)(void);
typedef void (*retro_netpacket_start_t)(uint16_t, retro_netpacket_send_t, retro_netpacket_poll_receive_t);
typedef void (*retro_netpacket_receive_t)(const void *buf, size_t len, uint16_t client_id);
typedef void (*retro_netpacket_stop_t)(void);
typedef void (*retro_netpacket_poll_t)(void);
typedef bool (*retro_netpacket_connected_t)(uint16_t client_id);
typedef void (*retro_netpacket_disconnected_t)(uint16_t client_id);

struct retro_netpacket_callback {
    retro_netpacket_start_t start;
    retro_netpacket_receive_t receive;
    retro_netpacket_stop_t stop;
    retro_netpacket_poll_t poll;
    retro_netpacket_connected_t connected;
    retro_netpacket_disconnected_t disconnected;
    const char *protocol_version;
};

/* ---- Link state lives in libsclinkstate.so so Kotlin can read it without loading this shim
 * (if Kotlin loaded the shim, it would never unload and old state would leak into the next game). */
#include "linkstate.h"

static int g_fd = -1;
static bool g_host = false;
static uint16_t g_self_id = 0, g_peer_id = 1;
static bool g_have_iface = false, g_started = false, g_in_poll = false, g_stop_pending = false;
static struct retro_netpacket_callback g_iface; /* copied: melonDS DS passes a temporary */

static uint8_t g_in[MAX_PACKET + 4];
static size_t g_in_len = 0;
static uint8_t *g_out = NULL; /* bytes the socket couldn't take yet */
static size_t g_out_len = 0, g_out_cap = 0;

/* ---- Real core --------------------------------------------------------------------------- */

static void *g_core = NULL;
static retro_environment_t g_frontend_env = NULL;

#define CORE_FN(ret, name, args) static ret (*p_##name) args = NULL;
CORE_FN(void, retro_set_environment, (retro_environment_t))
CORE_FN(void, retro_set_video_refresh, (void *))
CORE_FN(void, retro_set_audio_sample, (void *))
CORE_FN(void, retro_set_audio_sample_batch, (void *))
CORE_FN(void, retro_set_input_poll, (void *))
CORE_FN(void, retro_set_input_state, (void *))
CORE_FN(void, retro_init, (void))
CORE_FN(void, retro_deinit, (void))
CORE_FN(unsigned, retro_api_version, (void))
CORE_FN(void, retro_get_system_info, (void *))
CORE_FN(void, retro_get_system_av_info, (void *))
CORE_FN(void, retro_set_controller_port_device, (unsigned, unsigned))
CORE_FN(void, retro_reset, (void))
CORE_FN(void, retro_run, (void))
CORE_FN(size_t, retro_serialize_size, (void))
CORE_FN(bool, retro_serialize, (void *, size_t))
CORE_FN(bool, retro_unserialize, (const void *, size_t))
CORE_FN(void, retro_cheat_reset, (void))
CORE_FN(void, retro_cheat_set, (unsigned, bool, const char *))
CORE_FN(bool, retro_load_game, (const void *))
CORE_FN(bool, retro_load_game_special, (unsigned, const void *, size_t))
CORE_FN(void, retro_unload_game, (void))
CORE_FN(unsigned, retro_get_region, (void))
CORE_FN(void *, retro_get_memory_data, (unsigned))
CORE_FN(size_t, retro_get_memory_size, (unsigned))

static char g_core_path[1024];

static void load_core(void) {
    const char *path = getenv("SC_REAL_CORE");
    if (!path) { LOGE("SC_REAL_CORE not set"); abort(); }
    if (g_core && strcmp(path, g_core_path) == 0) return;
    if (g_core) { dlclose(g_core); g_core = NULL; g_have_iface = false; } /* different core this time */
    strncpy(g_core_path, path, sizeof(g_core_path) - 1);
    g_core = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    if (!g_core) { LOGE("dlopen %s failed: %s", path, dlerror()); abort(); }
#define BIND(name) p_##name = dlsym(g_core, #name); if (!p_##name) { LOGE("missing " #name); abort(); }
    BIND(retro_set_environment) BIND(retro_set_video_refresh) BIND(retro_set_audio_sample)
    BIND(retro_set_audio_sample_batch) BIND(retro_set_input_poll) BIND(retro_set_input_state)
    BIND(retro_init) BIND(retro_deinit) BIND(retro_api_version) BIND(retro_get_system_info)
    BIND(retro_get_system_av_info) BIND(retro_set_controller_port_device) BIND(retro_reset)
    BIND(retro_run) BIND(retro_serialize_size) BIND(retro_serialize) BIND(retro_unserialize)
    BIND(retro_cheat_reset) BIND(retro_cheat_set) BIND(retro_load_game) BIND(retro_load_game_special)
    BIND(retro_unload_game) BIND(retro_get_region) BIND(retro_get_memory_data) BIND(retro_get_memory_size)
#undef BIND
}

/* Called from retro_init: take this session's socket. The fd variable is cleared so a later
 * session can never reuse a closed socket. */
static void session_begin(void) {
    if (g_fd >= 0) close(g_fd);
    g_fd = -1;
    g_started = g_in_poll = g_stop_pending = false;
    g_in_len = g_out_len = 0;
    sc_link_reset();
    const char *fd = getenv("SC_LINK_FD");
    const char *role = getenv("SC_LINK_ROLE");
    if (fd && *fd && role) {
        g_fd = atoi(fd);
        g_host = strcmp(role, "host") == 0;
        g_self_id = g_host ? 0 : 1;
        g_peer_id = g_host ? 1 : 0;
        sc_link_set_state(LINK_WAITING_CORE);
        LOGI("link configured: %s, fd %d, core %s", role, g_fd, g_core_path);
    }
    unsetenv("SC_LINK_FD");
}

/* ---- Socket ------------------------------------------------------------------------------ */

/* Often called from inside the core (its send/poll_receive calls). melonDS DS polls in a loop and
 * its stop() clears the functions that loop is calling, so stopping the core here crashes it.
 * Only mark it; stop_if_lost() tells the core at the start of the next frame. */
static void link_lost(const char *why) {
    if (g_fd < 0) return;
    LOGI("link lost: %s", why);
    close(g_fd);
    g_fd = -1;
    g_in_len = g_out_len = 0;
    if (g_started) g_stop_pending = true;
    sc_link_set_state(LINK_LOST);
}

/* Called only from retro_run, outside any core callback. */
static void stop_if_lost(void) {
    if (!g_stop_pending) return;
    g_stop_pending = false;
    if (g_host && g_iface.disconnected) g_iface.disconnected(g_peer_id);
    if (g_iface.stop) g_iface.stop();
    g_started = false;
}

/* Push queued bytes without blocking. */
static void flush_out(void) {
    while (g_fd >= 0 && g_out_len > 0) {
        ssize_t n = send(g_fd, g_out, g_out_len, MSG_DONTWAIT | MSG_NOSIGNAL);
        if (n > 0) {
            memmove(g_out, g_out + n, g_out_len - (size_t)n);
            g_out_len -= (size_t)n;
        } else if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR)) {
            return;
        } else {
            link_lost("send failed");
            return;
        }
    }
}

static void queue_out(const void *data, size_t len) {
    if (g_out_len + len > g_out_cap) {
        size_t cap = g_out_cap ? g_out_cap : 4096;
        while (cap < g_out_len + len) cap *= 2;
        if (cap > 8 * 1024 * 1024) { link_lost("peer stopped reading"); return; }
        uint8_t *p = realloc(g_out, cap);
        if (!p) { link_lost("out of memory"); return; }
        g_out = p;
        g_out_cap = cap;
    }
    memcpy(g_out + g_out_len, data, len);
    g_out_len += len;
}

static void net_send(int flags, const void *buf, size_t len, uint16_t client_id) {
    (void)flags; /* TCP: everything is reliable and ordered, like RetroArch */
    if (g_fd < 0) return;
    if (buf && len > 0 && len <= MAX_PACKET && client_id != g_self_id &&
        (client_id == RETRO_NETPACKET_BROADCAST || client_id == g_peer_id)) {
        uint8_t hdr[4];
        uint32_t be = htonl((uint32_t)len);
        memcpy(hdr, &be, 4);
        queue_out(hdr, 4);
        queue_out(buf, len);
        sc_link_count(0, 1);
    }
    flush_out(); /* NULL/0 is a flush-only call; we always send straight away */
}

/* Read everything waiting and hand complete packets to the core. Safe to call re-entrantly. */
static void net_poll_receive(void) {
    if (g_fd < 0 || g_in_poll) return;
    g_in_poll = true;
    for (;;) {
        ssize_t n = recv(g_fd, g_in + g_in_len, sizeof(g_in) - g_in_len, MSG_DONTWAIT);
        if (n == 0) { link_lost("other phone left"); break; }
        if (n < 0) {
            if (errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) link_lost("receive failed");
            break;
        }
        g_in_len += (size_t)n;
        size_t off = 0;
        while (g_fd >= 0 && g_in_len - off >= 4) {
            uint32_t be;
            memcpy(&be, g_in + off, 4);
            uint32_t len = ntohl(be);
            if (len == 0 || len > MAX_PACKET) { link_lost("bad packet"); break; }
            if (g_in_len - off < 4 + len) break;
            sc_link_count(1, 0);
            if (g_started && g_iface.receive) g_iface.receive(g_in + off + 4, len, g_peer_id);
            off += 4 + len;
        }
        if (g_fd < 0) break;
        memmove(g_in, g_in + off, g_in_len - off);
        g_in_len -= off;
    }
    g_in_poll = false;
}

/* Start the session once the game is loaded (RetroArch also starts after load). */
static void maybe_start(void) {
    if (g_started || g_fd < 0) return;
    if (!g_have_iface) {
        LOGE("core did not offer link support");
        sc_link_set_state(LINK_NO_SUPPORT);
        return;
    }
    g_started = true;
    g_iface.start(g_self_id, net_send, net_poll_receive);
    if (g_host && g_iface.connected && !g_iface.connected(g_peer_id)) {
        LOGE("core refused the other phone");
        sc_link_set_state(LINK_REFUSED);
        if (g_iface.stop) g_iface.stop();
        g_started = false;
        close(g_fd);
        g_fd = -1;
        return;
    }
    sc_link_set_state(LINK_ACTIVE);
    LOGI("link started as client %u (protocol %s)", g_self_id,
         g_iface.protocol_version ? g_iface.protocol_version : "none");
}

static void link_shutdown(void) {
    if (g_started && g_iface.stop) g_iface.stop();
    g_started = g_stop_pending = false;
    if (g_fd >= 0) { close(g_fd); g_fd = -1; }
    if (sc_link_state() == LINK_ACTIVE) sc_link_set_state(LINK_OFF);
}

/* ---- Environment wrapper ----------------------------------------------------------------- */

static bool env_wrapper(unsigned cmd, void *data) {
    if (cmd == RETRO_ENVIRONMENT_SET_NETPACKET_INTERFACE) {
        if (data) {
            g_iface = *(const struct retro_netpacket_callback *)data;
            g_have_iface = g_iface.start != NULL && g_iface.receive != NULL;
        } else {
            g_have_iface = false;
        }
        return true;
    }
    return g_frontend_env ? g_frontend_env(cmd, data) : false;
}

/* ---- Exported libretro API: forward everything ----------------------------------------- */

EXPORT void retro_set_environment(retro_environment_t cb) { load_core(); g_frontend_env = cb; p_retro_set_environment(env_wrapper); }
EXPORT void retro_set_video_refresh(void *cb) { load_core(); p_retro_set_video_refresh(cb); }
EXPORT void retro_set_audio_sample(void *cb) { load_core(); p_retro_set_audio_sample(cb); }
EXPORT void retro_set_audio_sample_batch(void *cb) { load_core(); p_retro_set_audio_sample_batch(cb); }
EXPORT void retro_set_input_poll(void *cb) { load_core(); p_retro_set_input_poll(cb); }
EXPORT void retro_set_input_state(void *cb) { load_core(); p_retro_set_input_state(cb); }
EXPORT void retro_init(void) { load_core(); session_begin(); p_retro_init(); }
EXPORT void retro_deinit(void) { link_shutdown(); p_retro_deinit(); }
EXPORT unsigned retro_api_version(void) { load_core(); return p_retro_api_version(); }
EXPORT void retro_get_system_info(void *info) { load_core(); p_retro_get_system_info(info); }
EXPORT void retro_get_system_av_info(void *info) { p_retro_get_system_av_info(info); }
EXPORT void retro_set_controller_port_device(unsigned port, unsigned dev) { p_retro_set_controller_port_device(port, dev); }
EXPORT void retro_reset(void) { p_retro_reset(); }
EXPORT size_t retro_serialize_size(void) { return p_retro_serialize_size(); }
EXPORT bool retro_serialize(void *d, size_t s) { return p_retro_serialize(d, s); }
EXPORT bool retro_unserialize(const void *d, size_t s) { return p_retro_unserialize(d, s); }
EXPORT void retro_cheat_reset(void) { p_retro_cheat_reset(); }
EXPORT void retro_cheat_set(unsigned i, bool e, const char *c) { p_retro_cheat_set(i, e, c); }
EXPORT bool retro_load_game(const void *g) { load_core(); return p_retro_load_game(g); }
EXPORT bool retro_load_game_special(unsigned t, const void *g, size_t n) { load_core(); return p_retro_load_game_special(t, g, n); }
EXPORT void retro_unload_game(void) { link_shutdown(); p_retro_unload_game(); }
EXPORT unsigned retro_get_region(void) { return p_retro_get_region(); }
EXPORT void *retro_get_memory_data(unsigned id) { return p_retro_get_memory_data(id); }
EXPORT size_t retro_get_memory_size(unsigned id) { return p_retro_get_memory_size(id); }

EXPORT void retro_run(void) {
    stop_if_lost();
    maybe_start();
    if (g_started) {
        net_poll_receive();
        if (g_started && g_iface.poll) g_iface.poll();
    }
    p_retro_run();
    if (g_fd >= 0) flush_out();
}

/* Unloaded by the frontend: release the real core too. */
__attribute__((destructor)) static void shim_unload(void) {
    if (g_fd >= 0) close(g_fd);
    if (g_core) dlclose(g_core);
    free(g_out);
}
