/*
 * Shoalcade RPG Maker shim (GPL-3.0, part of Shoalcade).
 *
 * LibretroDroid loads this in place of the mkxp-z core (RPG Maker XP / VX / VX Ace, D147). It forwards every call to
 * the real core and answers two of the core's questions differently (see env_filter).
 *
 * SC_REAL_CORE: absolute path of the real core .so, set by Kotlin before the game starts.
 */
#include <dlfcn.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#define TAG "SCRpg"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define EXPORT __attribute__((visibility("default")))

#define RETRO_ENVIRONMENT_SET_HW_RENDER 14
#define RETRO_ENVIRONMENT_GET_VFS_INTERFACE (45 | 0x10000)
#define RETRO_HW_CONTEXT_OPENGLES3 4
#define RETRO_ENVIRONMENT_GET_VARIABLE 15
#define RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION 52
#define RETRO_ENVIRONMENT_SET_CORE_OPTIONS 53
#define RETRO_NUM_CORE_OPTION_VALUES_MAX 128

struct retro_variable { const char *key; const char *value; };
struct retro_core_option_value { const char *value; const char *label; };
struct retro_core_option_definition {
    const char *key, *desc, *info;
    struct retro_core_option_value values[RETRO_NUM_CORE_OPTION_VALUES_MAX];
    const char *default_value;
};

/* The core's option defaults (copied: the core frees its list straight after handing it over). */
#define MAX_OPTIONS 256
static char *g_opt_key[MAX_OPTIONS];
static char *g_opt_default[MAX_OPTIONS];
static int g_opt_count = 0;

static void keep_defaults(const struct retro_core_option_definition *defs) {
    for (int i = 0; i < g_opt_count; i++) { free(g_opt_key[i]); free(g_opt_default[i]); }
    g_opt_count = 0;
    for (; defs && defs->key && g_opt_count < MAX_OPTIONS; defs++) {
        g_opt_key[g_opt_count] = strdup(defs->key);
        g_opt_default[g_opt_count] = strdup(defs->default_value ? defs->default_value : "");
        g_opt_count++;
    }
}

static const char *default_for(const char *key) {
    for (int i = 0; key && i < g_opt_count; i++) if (strcmp(g_opt_key[i], key) == 0) return g_opt_default[i];
    return NULL;
}

/* Only the first field matters here: the context type the core asks for (libretro.h). */
struct hw_render_head { int context_type; };

typedef bool (*retro_environment_t)(unsigned cmd, void *data);

static void *g_core = NULL;
static char g_core_path[1024];

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

static void load_core(void) {
    const char *path = getenv("SC_REAL_CORE");
    if (!path) { LOGE("SC_REAL_CORE not set"); abort(); }
    if (g_core && strcmp(path, g_core_path) == 0) return;
    if (g_core) { dlclose(g_core); g_core = NULL; }
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

static retro_environment_t g_env = NULL;

/*
 * Core options: LibretroDroid only knows the older plain list, and mkxp-z's code for that list crashes (strlen of a
 * missing text at load). So the shim takes the newer list itself, remembers each default, and answers with the
 * default for any option Shoalcade didn't set.
 *
 * LibretroDroid says yes to every graphics request but always makes an OpenGL ES 3 context, and hands the core a
 * file interface older than the one mkxp-z asks for (its missing folder calls are what crashed at load).
 * So: only a GLES 3 request gets through (mkxp-z then works its way down its list to it), and the core uses its
 * own file access instead of LibretroDroid's.
 */
static bool env_filter(unsigned cmd, void *data) {
    if (cmd == RETRO_ENVIRONMENT_GET_VFS_INTERFACE) return false;
    if (cmd == RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION) { if (data) *(unsigned *) data = 1; return data != NULL; }
    if (cmd == RETRO_ENVIRONMENT_SET_CORE_OPTIONS) { keep_defaults(data); return true; }
    if (cmd == RETRO_ENVIRONMENT_GET_VARIABLE && data) {
        if (g_env(cmd, data) && ((struct retro_variable *) data)->value) return true;
        const char *d = default_for(((struct retro_variable *) data)->key);
        ((struct retro_variable *) data)->value = d;
        return d != NULL;
    }
    if (cmd == RETRO_ENVIRONMENT_SET_HW_RENDER && data &&
        ((struct hw_render_head *) data)->context_type != RETRO_HW_CONTEXT_OPENGLES3) return false;
    return g_env(cmd, data);
}

EXPORT size_t retro_get_memory_size(unsigned id) { return p_retro_get_memory_size(id); }

EXPORT void retro_set_environment(retro_environment_t cb) { load_core(); g_env = cb; p_retro_set_environment(env_filter); }
EXPORT void retro_set_video_refresh(void *cb) { load_core(); p_retro_set_video_refresh(cb); }
EXPORT void retro_set_audio_sample(void *cb) { load_core(); p_retro_set_audio_sample(cb); }
EXPORT void retro_set_audio_sample_batch(void *cb) { load_core(); p_retro_set_audio_sample_batch(cb); }
EXPORT void retro_set_input_poll(void *cb) { load_core(); p_retro_set_input_poll(cb); }
EXPORT void retro_set_input_state(void *cb) { load_core(); p_retro_set_input_state(cb); }
EXPORT void retro_init(void) { load_core(); p_retro_init(); }
EXPORT void retro_deinit(void) { p_retro_deinit(); }
EXPORT unsigned retro_api_version(void) { load_core(); return p_retro_api_version(); }
EXPORT void retro_get_system_info(void *info) { load_core(); p_retro_get_system_info(info); }
EXPORT void retro_get_system_av_info(void *info) { p_retro_get_system_av_info(info); }
EXPORT void retro_set_controller_port_device(unsigned port, unsigned dev) { p_retro_set_controller_port_device(port, dev); }
EXPORT void retro_reset(void) { p_retro_reset(); }
EXPORT void retro_run(void) { p_retro_run(); }
EXPORT size_t retro_serialize_size(void) { return p_retro_serialize_size(); }
EXPORT bool retro_serialize(void *d, size_t s) { return p_retro_serialize(d, s); }
EXPORT bool retro_unserialize(const void *d, size_t s) { return p_retro_unserialize(d, s); }
EXPORT void retro_cheat_reset(void) { p_retro_cheat_reset(); }
EXPORT void retro_cheat_set(unsigned i, bool e, const char *c) { p_retro_cheat_set(i, e, c); }
EXPORT bool retro_load_game(const void *g) { load_core(); return p_retro_load_game(g); }
EXPORT bool retro_load_game_special(unsigned t, const void *g, size_t n) { load_core(); return p_retro_load_game_special(t, g, n); }
EXPORT void retro_unload_game(void) { p_retro_unload_game(); }
EXPORT unsigned retro_get_region(void) { return p_retro_get_region(); }
EXPORT void *retro_get_memory_data(unsigned id) { return p_retro_get_memory_data(id); }

__attribute__((destructor)) static void shim_unload(void) {
    if (g_core) dlclose(g_core);
}
