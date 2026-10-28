/*
 * Shoalcade clock shim (GPL-3.0, part of Shoalcade).
 *
 * Game Boy cartridges with a clock (MBC3: Pokémon Gold/Silver/Crystal) keep it running on a battery.
 * mGBA stores that clock (48 bytes) straight after the save in the same buffer, but reports it as a
 * separate memory area, which LibretroDroid never reads. This library pretends to be the core,
 * forwards every call to mGBA, and reports the save area as save + clock when the two sit side by
 * side. LibretroDroid then saves the clock with the save, and puts it back before the first frame,
 * which is when mGBA reads it. Kotlin splits the two apart (GbClock.kt).
 *
 * SC_REAL_CORE: absolute path of the real core .so, set by Kotlin before the game starts.
 */
#include <dlfcn.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#define TAG "SCClock"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define EXPORT __attribute__((visibility("default")))

#define RETRO_MEMORY_SAVE_RAM 0
#define RETRO_MEMORY_RTC 1
#define CLOCK_SIZE 48

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

/* Save area size, plus the clock when mGBA keeps it directly after the save. */
EXPORT size_t retro_get_memory_size(unsigned id) {
    size_t n = p_retro_get_memory_size(id);
    if (id != RETRO_MEMORY_SAVE_RAM || n == 0) return n;
    uint8_t *save = p_retro_get_memory_data(RETRO_MEMORY_SAVE_RAM);
    uint8_t *clock = p_retro_get_memory_data(RETRO_MEMORY_RTC);
    if (save && clock == save + n && p_retro_get_memory_size(RETRO_MEMORY_RTC) == CLOCK_SIZE) return n + CLOCK_SIZE;
    return n;
}

EXPORT void retro_set_environment(retro_environment_t cb) { load_core(); p_retro_set_environment(cb); }
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
