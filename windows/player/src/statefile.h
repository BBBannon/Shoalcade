/*
 * ShoalcadePlayer: compressed snapshot files (part of Shoalcade).
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Plan P8 (GameCube, Wii, PS2): their snapshots are 90-110 MB raw (RAM, ARAM, video memory...), mostly zeros. They
 * are written as "SCSTATE1" (8 bytes), the raw size (8 bytes, little-endian), then one zstd frame. Every other
 * console's snapshot stays the core's raw bytes, as before; loading accepts both, so every existing snapshot still
 * loads.
 */
#pragma once
#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace sc {
/** True if these bytes are a compressed snapshot (the header, whole). */
bool is_packed_state(const uint8_t *data, size_t size);
/** The core's state -> a compressed snapshot file's bytes. False only if zstd fails. */
bool pack_state(const uint8_t *raw, size_t size, std::vector<uint8_t> &out, std::string *err = nullptr);
/**
 * A snapshot file's bytes -> the core's state: a compressed one is unpacked (and checked: the size in the header,
 * the frame's own size), anything else is copied as it is (the raw snapshots of before).
 */
bool unpack_state(const std::vector<uint8_t> &file, std::vector<uint8_t> &raw, std::string *err = nullptr);
} // namespace sc
