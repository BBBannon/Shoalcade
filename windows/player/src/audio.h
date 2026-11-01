/*
 * ShoalcadePlayer: game sound through an SDL3 audio stream with dynamic rate control (part of Shoalcade).
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * The player paces frames by the clock; the sound card runs on its own clock. Dynamic rate control keeps the queue
 * near its target by nudging the playback rate up to +/-0.5 % (too small to hear), so the queue never runs dry and
 * never grows. A hard cap drops samples instead of ever growing the queue past `max_ms`, at any speed. While
 * fast-forwarding, the sound is muted (dropped), as on the phone and in the browser.
 */
#pragma once
#include <SDL3/SDL.h>

#include <cstddef>
#include <cstdint>

#include "nlohmann/json.hpp"

namespace sc {
class Audio {
public:
    /** Opens the default playback device. False = no sound this session (never fatal). */
    bool open(double core_rate);
    void close();
    void set_rate(double core_rate);

    void push(const int16_t *stereo_frames, size_t frames);
    /** Once per shown frame: adjusts the rate from the queue fill. */
    void update();

    void set_paused(bool paused);
    void set_fast_forward(bool on);
    void set_volume(float v); // 0..1
    void set_muted(bool m);
    float volume() const { return volume_; }
    bool muted() const { return muted_; }
    /** Sound the player dropped itself (fast-forward, rewind): counted for the stats. */
    void note_muted(size_t frames) { dropped_ff_ += frames; }

    /** For RETRO_ENVIRONMENT_SET_AUDIO_BUFFER_STATUS_CALLBACK: 0-100 and "running low". */
    unsigned occupancy() const;
    bool running_low() const;
    bool active() const { return stream_ != nullptr; }
    double queued_ms() const;

    nlohmann::json stats() const;

    double target_ms = 64, max_ms = 200;

private:
    void apply_gain();
    SDL_AudioStream *stream_ = nullptr;
    double core_rate_ = 0, base_ratio_ = 1, ratio_ = 1;
    int declared_rate_ = 0;
    bool paused_ = false, ff_ = false, muted_ = false;
    float volume_ = 1;
    bool started_ = false;
    // Stats (for logs and the tests).
    uint64_t pushed_ = 0, dropped_full_ = 0, dropped_ff_ = 0, underruns_ = 0, updates_ = 0;
    double min_q_ = 1e9, max_q_ = 0, min_ratio_ = 10, max_ratio_ = 0;
};
} // namespace sc
