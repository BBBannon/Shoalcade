/*
 * ShoalcadePlayer: rewind ring buffer (part of Shoalcade).
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#include "rewind.h"

#include <algorithm>
#include <cstring>

namespace sc {
namespace {
void put_varint(std::vector<uint8_t> &out, uint64_t v) {
    while (v >= 0x80) {
        out.push_back((uint8_t)(v | 0x80));
        v >>= 7;
    }
    out.push_back((uint8_t)v);
}
bool get_varint(const std::vector<uint8_t> &in, size_t &pos, uint64_t &v) {
    v = 0;
    for (int shift = 0; shift < 64; shift += 7) {
        if (pos >= in.size()) return false;
        uint8_t b = in[pos++];
        v |= (uint64_t)(b & 0x7f) << shift;
        if (!(b & 0x80)) return true;
    }
    return false;
}
} // namespace

// Format: varint size of the older state, then pairs of (varint zero-run, varint literal length, literal XOR bytes).
void Rewind::encode(const std::vector<uint8_t> &newer, const std::vector<uint8_t> &older, std::vector<uint8_t> &out) {
    out.clear();
    put_varint(out, older.size());
    size_t n = older.size(), i = 0;
    auto x = [&](size_t k) -> uint8_t { return (uint8_t)(older[k] ^ (k < newer.size() ? newer[k] : 0)); };
    while (i < n) {
        size_t z = i;
        // Equal stretches 8 bytes at a time (a 100 MB state is mostly unchanged between two steps: P10).
        const size_t m = std::min(n, newer.size());
        while (z + 8 <= m) {
            uint64_t a, b;
            memcpy(&a, older.data() + z, 8);
            memcpy(&b, newer.data() + z, 8);
            if (a != b) break;
            z += 8;
        }
        while (z < n && x(z) == 0) z++;
        size_t lit = z;
        // A literal ends at the next run of 8+ zero bytes (short runs are cheaper to keep inline).
        size_t zeros = 0;
        while (lit < n) {
            if (x(lit) == 0) {
                if (++zeros >= 8) {
                    lit -= 7;
                    break;
                }
            } else {
                zeros = 0;
            }
            lit++;
        }
        if (lit > n) lit = n;
        put_varint(out, z - i);
        put_varint(out, lit - z);
        for (size_t k = z; k < lit; k++) out.push_back(x(k));
        i = lit;
    }
}

bool Rewind::apply(const std::vector<uint8_t> &delta, std::vector<uint8_t> &state) {
    size_t pos = 0;
    uint64_t size;
    if (!get_varint(delta, pos, size)) return false;
    std::vector<uint8_t> out(size, 0);
    memcpy(out.data(), state.data(), std::min<size_t>(size, state.size()));
    size_t i = 0;
    while (pos < delta.size()) {
        uint64_t zr, lit;
        if (!get_varint(delta, pos, zr) || !get_varint(delta, pos, lit)) return false;
        i += zr;
        if (i + lit > size || pos + lit > delta.size()) return false;
        for (uint64_t k = 0; k < lit; k++) out[i + k] ^= delta[pos + k];
        i += lit;
        pos += lit;
    }
    // Bytes past the newer state's end were XORed with 0, so they are stored whole: already right.
    state.swap(out);
    return true;
}

void Rewind::push(const std::vector<uint8_t> &state) {
    if (!current_.empty()) {
        std::vector<uint8_t> d;
        encode(state, current_, d);
        used_ += d.size();
        deltas_.push_back(std::move(d));
        while (((used_ + state.size() > cap_) || (max_steps_ && deltas_.size() > max_steps_)) && !deltas_.empty()) {
            used_ -= deltas_.front().size();
            deltas_.pop_front();
        }
    }
    current_ = state;
}

bool Rewind::step_back(std::vector<uint8_t> &out) {
    if (deltas_.empty()) return false;
    std::vector<uint8_t> d = std::move(deltas_.back());
    deltas_.pop_back();
    used_ -= d.size();
    if (!apply(d, current_)) {
        clear();
        return false;
    }
    out = current_;
    return true;
}

void Rewind::clear() {
    deltas_.clear();
    current_.clear();
    used_ = 0;
}

std::string Rewind::finish_push(std::future<void> &job) {
    if (!job.valid()) return "";
    try {
        job.get();
        return "";
    } catch (const std::exception &e) {
        clear();
        return e.what()[0] ? e.what() : "unknown error";
    } catch (...) {
        clear();
        return "unknown error";
    }
}
} // namespace sc
