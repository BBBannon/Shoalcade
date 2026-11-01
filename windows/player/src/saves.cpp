/*
 * ShoalcadePlayer: in-game saves and the Game Boy clock (part of Shoalcade).
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#include "saves.h"

#include <cstring>

#include "files.h"
#include "log.h"
#include "protocol.h"
#include "sha256.h"

namespace sc {
static const size_t kClockSize = 48; // GbClock.SIZE: mGBA's GBMBCRTCSaveBuffer

static std::string commas(size_t v) {
    std::string s = std::to_string(v);
    for (int i = (int)s.size() - 3; i > 0; i -= 3) s.insert((size_t)i, ",");
    return s;
}

void Saves::configure(const ConsoleInfo &console, std::string save_file, std::string clock_file) {
    console_ = &console;
    save_file_ = std::move(save_file);
    clock_file_ = std::move(clock_file);
}

void Saves::disable(const std::string &why) {
    enabled_ = false;
    disabled_reason_ = why;
    LOGW("saving is off this session: %s", why.c_str());
}

void Saves::load(Core &core) {
    enabled_ = false;
    if (console_->folder_saves) {
        disabled_reason_ = "folder save (the core writes it itself)";
        return;
    }
    if (save_file_.empty()) {
        disable("no save file given");
        return;
    }
    size_t size = core.get_memory_size(RETRO_MEMORY_SAVE_RAM);
    uint8_t *mem = static_cast<uint8_t *>(core.get_memory_data(RETRO_MEMORY_SAVE_RAM));
    bool exists = file_exists(save_file_);
    std::vector<uint8_t> file;
    std::string err;
    if (exists && !read_file(save_file_, file, &err)) {
        disable("couldn't read the save file (" + err + ")");
        send("saveRefused", {{"path", save_file_}, {"reason", "unreadable"}, {"message",
              "Couldn't read your save file, so it wasn't loaded and won't be changed this session."}});
        return;
    }
    if (console_->exact_save_size && size != 0 && size != console_->exact_save_size) {
        disable("the core's save memory is " + commas(size) + " bytes, not " + commas(console_->exact_save_size));
        send("saveRefused", {{"path", save_file_}, {"reason", "coreSize"}, {"coreSize", size},
                             {"expected", console_->exact_save_size},
                             {"message", "This emulator's save memory is the wrong size, so saving is off this "
                                         "session. Your save file is unchanged."}});
        return;
    }
    if (size == 0 || !mem) {
        if (exists) {
            disable("the core has no save memory for this game");
            send("saveRefused", {{"path", save_file_}, {"reason", "noSaveMemory"}, {"fileSize", file.size()},
                                 {"message", "This game has no save memory in the emulator, so your save file "
                                             "wasn't loaded and won't be changed."}});
            return;
        }
        // No save yet and nothing to save into right now; memory may appear later (checked on each flush).
        enabled_ = true;
        have_last_ = false;
        send("saveLoaded", {{"path", save_file_}, {"loaded", false}, {"coreSize", 0}});
        return;
    }
    if (exists) {
        size_t expected = console_->exact_save_size ? console_->exact_save_size : size;
        if (file.size() != expected && (console_->exact_save_size || file.size() > size)) {
            disable("the save file is " + commas(file.size()) + " bytes, but " + console_->short_name +
                    " saves here are " + commas(expected) + " bytes");
            send("saveRefused",
                 {{"path", save_file_}, {"reason", "wrongSize"}, {"fileSize", file.size()}, {"expected", expected},
                  {"message", std::string("That save is ") + commas(file.size()) + " bytes, but " +
                                  console_->short_name + " saves are " + commas(expected) +
                                  " bytes. It wasn't loaded, and saving is off this session so it stays unchanged."}});
            return;
        }
        memcpy(mem, file.data(), file.size());
        if (file.size() < size)
            send("warning", {{"message", "The save file (" + commas(file.size()) + " bytes) is smaller than this "
                                         "game's save memory (" + commas(size) + " bytes); the rest starts blank."}});
    }
    last_save_.assign(mem, mem + size);
    have_last_ = true;
    enabled_ = true;

    // Game Boy clock cartridges: the clock lives in its own file beside the save, as on the phone.
    if (console_->gb_clock && !clock_file_.empty()) {
        size_t csize = core.get_memory_size(RETRO_MEMORY_RTC);
        uint8_t *cmem = static_cast<uint8_t *>(core.get_memory_data(RETRO_MEMORY_RTC));
        if (csize == kClockSize && cmem) {
            std::vector<uint8_t> clock;
            if (file_exists(clock_file_) && read_file(clock_file_, clock) && clock.size() == kClockSize)
                memcpy(cmem, clock.data(), kClockSize);
            else if (file_exists(clock_file_))
                LOGW("clock file isn't %zu bytes; the game's clock starts fresh", kClockSize);
            last_clock_.assign(cmem, cmem + kClockSize);
            LOGI("game clock: %zu bytes, from %s", csize, file_exists(clock_file_) ? "the clock file" : "nothing (new)");
        } else {
            LOGI("no game clock in this cartridge (core clock memory %zu bytes)", csize);
        }
    }
    send("saveLoaded", {{"path", save_file_}, {"loaded", exists}, {"coreSize", size},
                        {"sha256", sha256_hex(mem, size)}});
}

void Saves::flush(Core &core, const char *reason) {
    if (!enabled_) return;
    size_t size = core.get_memory_size(RETRO_MEMORY_SAVE_RAM);
    uint8_t *mem = static_cast<uint8_t *>(core.get_memory_data(RETRO_MEMORY_SAVE_RAM));
    if (size == 0 || !mem) return;
    if (console_->exact_save_size && size != console_->exact_save_size) {
        if (!reported_size_change_) {
            reported_size_change_ = true;
            LOGE("save memory changed to %zu bytes (must be %zu); not written", size, console_->exact_save_size);
        }
        return;
    }
    bool changed = !have_last_ || last_save_.size() != size || memcmp(last_save_.data(), mem, size) != 0;
    if (changed) {
        std::vector<uint8_t> copy(mem, mem + size);
        std::string err;
        if (!write_file_atomic(save_file_, copy.data(), copy.size(), &err)) {
            LOGE("couldn't write the save: %s", err.c_str());
            send("error", {{"message", "Couldn't write your save to disk: " + err}, {"fatal", false}});
            return;
        }
        last_save_ = std::move(copy);
        have_last_ = true;
        std::string sha = sha256_hex(last_save_.data(), last_save_.size());
        LOGI("save written (%s, %zu bytes, %s)", reason, size, sha.c_str());
        send("saveChanged", {{"sha256", sha}, {"path", save_file_}, {"size", size}, {"reason", reason}});
    }
    if (console_->gb_clock && !clock_file_.empty() && (changed || std::strcmp(reason, "close") == 0)) {
        size_t csize = core.get_memory_size(RETRO_MEMORY_RTC);
        uint8_t *cmem = static_cast<uint8_t *>(core.get_memory_data(RETRO_MEMORY_RTC));
        bool have = csize == kClockSize && cmem;
        bool differs = have && (last_clock_.size() != kClockSize || memcmp(last_clock_.data(), cmem, kClockSize) != 0);
        if (have && (differs || !file_exists(clock_file_))) {
            std::string err;
            if (write_file_atomic(clock_file_, cmem, kClockSize, &err)) last_clock_.assign(cmem, cmem + kClockSize);
            else LOGE("couldn't write the clock: %s", err.c_str());
        }
    }
}

bool Saves::poke(Core &core, size_t offset, const std::vector<uint8_t> &bytes) {
    size_t size = core.get_memory_size(RETRO_MEMORY_SAVE_RAM);
    uint8_t *mem = static_cast<uint8_t *>(core.get_memory_data(RETRO_MEMORY_SAVE_RAM));
    if (!mem || offset + bytes.size() > size) return false;
    memcpy(mem + offset, bytes.data(), bytes.size());
    return true;
}

bool Saves::dump(Core &core, const std::string &path, std::string &err) {
    size_t size = core.get_memory_size(RETRO_MEMORY_SAVE_RAM);
    uint8_t *mem = static_cast<uint8_t *>(core.get_memory_data(RETRO_MEMORY_SAVE_RAM));
    if (!mem || !size) {
        err = "no save memory";
        return false;
    }
    return write_file_atomic(path, mem, size, &err);
}
} // namespace sc
