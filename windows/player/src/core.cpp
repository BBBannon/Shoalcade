/*
 * ShoalcadePlayer: core loader (part of Shoalcade).
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#include "core.h"

#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#else
#include <dlfcn.h>
#endif

namespace sc {
static void *find(void *h, const char *name) {
#ifdef _WIN32
    return reinterpret_cast<void *>(GetProcAddress(static_cast<HMODULE>(h), name));
#else
    return dlsym(h, name);
#endif
}

bool Core::open(const std::string &p, std::string &err) {
    path = p;
#ifdef _WIN32
    int n = MultiByteToWideChar(CP_UTF8, 0, p.c_str(), -1, nullptr, 0);
    std::wstring w(n, L'\0');
    MultiByteToWideChar(CP_UTF8, 0, p.c_str(), -1, w.data(), n);
    // The core's own folder is searched for any DLLs it needs.
    handle = LoadLibraryExW(w.c_str(), nullptr, LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR | LOAD_LIBRARY_SEARCH_DEFAULT_DIRS);
    if (!handle) {
        err = "couldn't load the core (Windows error " + std::to_string(GetLastError()) + ")";
        return false;
    }
#else
    handle = dlopen(p.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!handle) {
        const char *e = dlerror();
        err = std::string("couldn't load the core: ") + (e ? e : "unknown error");
        return false;
    }
#endif
#define BIND(field, name)                                                       \
    *reinterpret_cast<void **>(&field) = find(handle, name);                    \
    if (!field) {                                                               \
        err = std::string("the core has no ") + name + " (not a libretro core?)"; \
        close();                                                                \
        return false;                                                           \
    }
    BIND(set_environment, "retro_set_environment")
    BIND(set_video_refresh, "retro_set_video_refresh")
    BIND(set_audio_sample, "retro_set_audio_sample")
    BIND(set_audio_sample_batch, "retro_set_audio_sample_batch")
    BIND(set_input_poll, "retro_set_input_poll")
    BIND(set_input_state, "retro_set_input_state")
    BIND(init, "retro_init")
    BIND(deinit, "retro_deinit")
    BIND(api_version, "retro_api_version")
    BIND(get_system_info, "retro_get_system_info")
    BIND(get_system_av_info, "retro_get_system_av_info")
    BIND(set_controller_port_device, "retro_set_controller_port_device")
    BIND(reset, "retro_reset")
    BIND(run, "retro_run")
    BIND(serialize_size, "retro_serialize_size")
    BIND(serialize, "retro_serialize")
    BIND(unserialize, "retro_unserialize")
    BIND(cheat_reset, "retro_cheat_reset")
    BIND(cheat_set, "retro_cheat_set")
    BIND(load_game, "retro_load_game")
    BIND(unload_game, "retro_unload_game")
    BIND(get_memory_data, "retro_get_memory_data")
    BIND(get_memory_size, "retro_get_memory_size")
#undef BIND
    return true;
}

void Core::close() {
    if (!handle) return;
#ifdef _WIN32
    FreeLibrary(static_cast<HMODULE>(handle));
#else
    dlclose(handle);
#endif
    handle = nullptr;
}
} // namespace sc
