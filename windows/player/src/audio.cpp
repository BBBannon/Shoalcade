/*
 * ShoalcadePlayer: game sound with dynamic rate control (part of Shoalcade).
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#include "audio.h"

#include <algorithm>
#include <cmath>

#include "log.h"

namespace sc {
static const int kBytesPerFrame = 4; // stereo int16

bool Audio::open(double core_rate) {
    if (!SDL_InitSubSystem(SDL_INIT_AUDIO)) {
        LOGW("no sound: %s", SDL_GetError());
        return false;
    }
    core_rate_ = core_rate > 0 ? core_rate : 44100;
    declared_rate_ = (int)std::lround(core_rate_);
    base_ratio_ = core_rate_ / declared_rate_;
    SDL_AudioSpec spec{SDL_AUDIO_S16, 2, declared_rate_};
    stream_ = SDL_OpenAudioDeviceStream(SDL_AUDIO_DEVICE_DEFAULT_PLAYBACK, &spec, nullptr, nullptr);
    if (!stream_) {
        LOGW("no sound: couldn't open the sound device: %s", SDL_GetError());
        return false;
    }
    ratio_ = base_ratio_;
    SDL_SetAudioStreamFrequencyRatio(stream_, (float)ratio_);
    apply_gain();
    SDL_ResumeAudioStreamDevice(stream_);
    const char *driver = SDL_GetCurrentAudioDriver();
    LOGI("sound: %s, core %.2f Hz, target %.0f ms, cap %.0f ms", driver ? driver : "?", core_rate_, target_ms,
         max_ms);
    return true;
}

void Audio::close() {
    if (stream_) SDL_DestroyAudioStream(stream_);
    stream_ = nullptr;
}

void Audio::set_rate(double core_rate) {
    if (!stream_ || core_rate <= 0 || core_rate == core_rate_) return;
    core_rate_ = core_rate;
    declared_rate_ = (int)std::lround(core_rate_);
    base_ratio_ = core_rate_ / declared_rate_;
    SDL_AudioSpec spec{SDL_AUDIO_S16, 2, declared_rate_};
    SDL_ClearAudioStream(stream_);
    SDL_SetAudioStreamFormat(stream_, &spec, nullptr);
    ratio_ = base_ratio_;
    SDL_SetAudioStreamFrequencyRatio(stream_, (float)ratio_);
    LOGI("sound: core rate now %.2f Hz", core_rate_);
}

double Audio::queued_ms() const {
    if (!stream_) return 0;
    int bytes = SDL_GetAudioStreamQueued(stream_);
    if (bytes < 0) bytes = 0;
    return bytes / (double)kBytesPerFrame / core_rate_ * 1000.0;
}

void Audio::push(const int16_t *frames, size_t count) {
    if (!stream_ || count == 0) return;
    if (ff_ || paused_) {
        dropped_ff_ += count;
        return;
    }
    double q = queued_ms();
    if (started_ && q <= 0.0) underruns_++;
    double room_ms = max_ms - q;
    size_t room = room_ms <= 0 ? 0 : (size_t)(room_ms / 1000.0 * core_rate_);
    size_t take = std::min(count, room);
    if (take < count) dropped_full_ += count - take;
    if (take == 0) return;
    if (SDL_PutAudioStreamData(stream_, frames, (int)(take * kBytesPerFrame))) {
        pushed_ += take;
        started_ = true;
    }
}

void Audio::update() {
    if (!stream_ || paused_ || ff_) return;
    double q = queued_ms();
    updates_++;
    min_q_ = std::min(min_q_, q), max_q_ = std::max(max_q_, q);
    // Queue above target -> play a touch faster; below -> a touch slower. Max +/-0.5 %.
    double d = std::clamp((q - target_ms) / target_ms, -1.0, 1.0);
    double want = base_ratio_ * (1.0 + 0.005 * d);
    // Smooth the change so the pitch never jumps.
    ratio_ += (want - ratio_) * 0.05;
    min_ratio_ = std::min(min_ratio_, ratio_ / base_ratio_), max_ratio_ = std::max(max_ratio_, ratio_ / base_ratio_);
    SDL_SetAudioStreamFrequencyRatio(stream_, (float)ratio_);
}

void Audio::set_paused(bool paused) {
    if (paused == paused_) return;
    paused_ = paused;
    if (!stream_) return;
    if (paused) SDL_PauseAudioStreamDevice(stream_);
    else {
        SDL_ClearAudioStream(stream_);
        started_ = false;
        SDL_ResumeAudioStreamDevice(stream_);
    }
}

void Audio::set_fast_forward(bool on) {
    if (on == ff_) return;
    ff_ = on;
    if (stream_) SDL_ClearAudioStream(stream_);
    started_ = false;
}

void Audio::set_volume(float v) {
    volume_ = std::clamp(v, 0.0f, 1.0f);
    apply_gain();
}

void Audio::set_muted(bool m) {
    muted_ = m;
    apply_gain();
}

void Audio::apply_gain() {
    if (stream_) SDL_SetAudioStreamGain(stream_, muted_ ? 0.0f : volume_);
}

unsigned Audio::occupancy() const {
    if (!stream_) return 0;
    return (unsigned)std::clamp(queued_ms() / max_ms * 100.0, 0.0, 100.0);
}

bool Audio::running_low() const { return stream_ && queued_ms() < target_ms / 2; }

nlohmann::json Audio::stats() const {
    const char *driver = stream_ ? SDL_GetCurrentAudioDriver() : nullptr;
    return {{"active", stream_ != nullptr},
            {"driver", driver ? driver : ""},
            {"coreRate", core_rate_},
            {"framesPushed", pushed_},
            {"framesDroppedFull", dropped_full_},
            {"framesDroppedFastForward", dropped_ff_},
            {"underruns", underruns_},
            {"minQueuedMs", updates_ ? min_q_ : 0.0},
            {"maxQueuedMs", max_q_},
            {"minRatio", updates_ ? min_ratio_ : 1.0},
            {"maxRatio", updates_ ? max_ratio_ : 1.0}};
}
} // namespace sc
