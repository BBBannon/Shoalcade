/*
 * ShoalcadePlayer: SHA-256 (FIPS 180-4), used for saveChanged (part of Shoalcade).
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#pragma once
#include <cstddef>
#include <cstdint>
#include <string>

namespace sc {
/** Lower-case hex SHA-256 of the bytes. */
std::string sha256_hex(const void *data, size_t size);
} // namespace sc
