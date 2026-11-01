/*
 * ShoalcadePlayer: rewind ring buffer (part of Shoalcade).
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Keeps the newest state whole and, for each older step, only the XOR difference to the next one, with runs of
 * zero bytes squeezed out (consecutive states differ in a few bytes). Stepping back = XOR the newest difference into
 * the current state. The oldest differences are dropped once the buffer passes its cap.
 */
#pragma once
#include <cstddef>
#include <cstdint>
#include <deque>
#include <vector>

namespace sc {
class Rewind {
public:
    void configure(size_t cap_bytes) { cap_ = cap_bytes; }
    /** A new state (the newest). */
    void push(const std::vector<uint8_t> &state);
    /** Steps back one state; false if there is nothing older. `out` = the older state. */
    bool step_back(std::vector<uint8_t> &out);
    void clear();
    size_t bytes() const { return used_; }
    size_t steps() const { return deltas_.size(); }

private:
    static void encode(const std::vector<uint8_t> &a, const std::vector<uint8_t> &b, std::vector<uint8_t> &out);
    static bool apply(const std::vector<uint8_t> &delta, std::vector<uint8_t> &state);
    size_t cap_ = 64u << 20;
    std::vector<uint8_t> current_;
    std::deque<std::vector<uint8_t>> deltas_; // deltas_.back() turns current_ into the one before it
    size_t used_ = 0;
};
} // namespace sc
