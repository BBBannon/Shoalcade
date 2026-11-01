/*
 * ShoalcadePlayer: loads a libretro core (dlopen / LoadLibrary) and holds its functions (part of Shoalcade).
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#pragma once
#include <string>

#include "libretro/libretro.h"

namespace sc {
struct Core {
    void *handle = nullptr;
    std::string path;

    void (*set_environment)(retro_environment_t) = nullptr;
    void (*set_video_refresh)(retro_video_refresh_t) = nullptr;
    void (*set_audio_sample)(retro_audio_sample_t) = nullptr;
    void (*set_audio_sample_batch)(retro_audio_sample_batch_t) = nullptr;
    void (*set_input_poll)(retro_input_poll_t) = nullptr;
    void (*set_input_state)(retro_input_state_t) = nullptr;
    void (*init)(void) = nullptr;
    void (*deinit)(void) = nullptr;
    unsigned (*api_version)(void) = nullptr;
    void (*get_system_info)(retro_system_info *) = nullptr;
    void (*get_system_av_info)(retro_system_av_info *) = nullptr;
    void (*set_controller_port_device)(unsigned, unsigned) = nullptr;
    void (*reset)(void) = nullptr;
    void (*run)(void) = nullptr;
    size_t (*serialize_size)(void) = nullptr;
    bool (*serialize)(void *, size_t) = nullptr;
    bool (*unserialize)(const void *, size_t) = nullptr;
    void (*cheat_reset)(void) = nullptr;
    void (*cheat_set)(unsigned, bool, const char *) = nullptr;
    bool (*load_game)(const retro_game_info *) = nullptr;
    void (*unload_game)(void) = nullptr;
    void *(*get_memory_data)(unsigned) = nullptr;
    size_t (*get_memory_size)(unsigned) = nullptr;

    /** Opens the library and finds every function. On failure `err` says what was wrong. */
    bool open(const std::string &path, std::string &err);
    void close();
};
} // namespace sc
