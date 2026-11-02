/*
 * ShoalcadePlayer: compressed snapshot files (part of Shoalcade).
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#include "statefile.h"

#include <cstring>

#include <zstd.h>

namespace sc {
namespace {
const char kMagic[8] = {'S', 'C', 'S', 'T', 'A', 'T', 'E', '1'};
constexpr size_t kHeader = 16;
// zstd level 3 (its default): a 100 MB state in about 0.1 s here, a few hundred KB for our test scenes.
constexpr int kLevel = 3;
// Never unpack to more than this (a damaged or hostile header can't ask for more memory than any core's state).
constexpr uint64_t kMaxRaw = 1ull << 31;

void fail(std::string *err, const std::string &why) {
    if (err) *err = why;
}
} // namespace

bool is_packed_state(const uint8_t *data, size_t size) {
    return size >= kHeader && std::memcmp(data, kMagic, sizeof kMagic) == 0;
}

bool pack_state(const uint8_t *raw, size_t size, std::vector<uint8_t> &out, std::string *err) {
    size_t bound = ZSTD_compressBound(size);
    out.resize(kHeader + bound);
    std::memcpy(out.data(), kMagic, sizeof kMagic);
    uint64_t n = size;
    for (int i = 0; i < 8; i++) out[8 + i] = (uint8_t)(n >> (8 * i));
    size_t z = ZSTD_compress(out.data() + kHeader, bound, raw, size, kLevel);
    if (ZSTD_isError(z)) {
        fail(err, std::string("compressing the snapshot failed: ") + ZSTD_getErrorName(z));
        out.clear();
        return false;
    }
    out.resize(kHeader + z);
    return true;
}

bool unpack_state(const std::vector<uint8_t> &file, std::vector<uint8_t> &raw, std::string *err) {
    if (!is_packed_state(file.data(), file.size())) {
        raw = file; // a raw snapshot (every console before P8, and the 8 phone consoles still)
        return true;
    }
    uint64_t n = 0;
    for (int i = 0; i < 8; i++) n |= (uint64_t)file[8 + i] << (8 * i);
    unsigned long long frame = ZSTD_getFrameContentSize(file.data() + kHeader, file.size() - kHeader);
    if (n == 0 || n > kMaxRaw || frame == ZSTD_CONTENTSIZE_ERROR || frame == ZSTD_CONTENTSIZE_UNKNOWN || frame != n)
        return fail(err, "the snapshot file is damaged (its sizes don't match)"), false;
    raw.resize((size_t)n);
    size_t got = ZSTD_decompress(raw.data(), raw.size(), file.data() + kHeader, file.size() - kHeader);
    if (ZSTD_isError(got) || got != n) {
        raw.clear();
        return fail(err, "the snapshot file is damaged (it doesn't unpack)"), false;
    }
    return true;
}
} // namespace sc
