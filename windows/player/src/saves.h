/*
 * ShoalcadePlayer: in-game saves (RETRO_MEMORY_SAVE_RAM) and the Game Boy clock (part of Shoalcade).
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Saves are precious:
 *  - A save of the wrong size is never given to the core and never overwritten: the mismatch is reported and saving
 *    stays off this session (Console.kt wrongSaveSize / GameActivity.saveForCore). PS1 is always 131,072 bytes and
 *    N64 296,960.
 *  - Writes are atomic (temp file, flush, rename) and happen only when the bytes changed.
 *  - The shell makes the backup before starting the player and decides what is uploaded.
 */
#pragma once
#include <cstdint>
#include <string>
#include <vector>

#include "console.h"
#include "core.h"
#include "nlohmann/json.hpp"

namespace sc {
class Saves {
public:
    void configure(const ConsoleInfo &console, std::string save_file, std::string clock_file);
    /** After retro_load_game: puts the save (and clock) into the core. Sends `saveLoaded` or `saveRefused`. */
    void load(Core &core);
    /** Writes the save if it changed. Sends `saveChanged {sha256}` after each write. `reason`: timer|pause|close|... */
    void flush(Core &core, const char *reason);
    bool enabled() const { return enabled_; }
    const std::string &disabled_reason() const { return disabled_reason_; }

    // Test hooks (only with --test-hooks).
    bool poke(Core &core, size_t offset, const std::vector<uint8_t> &bytes);
    bool dump(Core &core, const std::string &path, std::string &err);

private:
    void disable(const std::string &why);
    const ConsoleInfo *console_ = nullptr;
    std::string save_file_, clock_file_;
    bool enabled_ = false;
    std::string disabled_reason_;
    bool reported_size_change_ = false;
    std::vector<uint8_t> last_save_, last_clock_;
    bool have_last_ = false;
};
} // namespace sc
