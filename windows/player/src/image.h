/*
 * ShoalcadePlayer: a frame as plain RGBA pixels, PNG writing and frame hashes (part of Shoalcade).
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#pragma once
#include <cstdint>
#include <string>
#include <vector>

namespace sc {
/** Top row first, 4 bytes a pixel (R, G, B, A=255). */
struct RgbaImage {
    int width = 0, height = 0;
    std::vector<uint8_t> pixels;
    bool empty() const { return width <= 0 || height <= 0 || pixels.empty(); }
};

/** PNG bytes of the image (stb_image_write). Empty on failure. */
std::vector<uint8_t> encode_png(const RgbaImage &img);
/** Encodes and writes atomically. */
bool write_png(const std::string &path, const RgbaImage &img, std::string *err = nullptr);
/** FNV-1a 64 of the RGB bytes (alpha ignored), as 16 hex digits. Used by the headless tests. */
std::string frame_hash(const RgbaImage &img);
/** Pixels that aren't black (any of R, G, B above 8). */
size_t non_black_pixels(const RgbaImage &img);
} // namespace sc
