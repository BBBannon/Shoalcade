/*
 * ShoalcadePlayer: PNG writing and frame hashes (part of Shoalcade).
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#include "image.h"

#include "files.h"

#define STB_IMAGE_WRITE_IMPLEMENTATION
#define STBI_WRITE_NO_STDIO
#if defined(__GNUC__)
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wmissing-field-initializers"
#endif
#include "stb/stb_image_write.h"
#if defined(__GNUC__)
#pragma GCC diagnostic pop
#endif

namespace sc {
static void append(void *ctx, void *data, int size) {
    auto *out = static_cast<std::vector<uint8_t> *>(ctx);
    auto *p = static_cast<uint8_t *>(data);
    out->insert(out->end(), p, p + size);
}

std::vector<uint8_t> encode_png(const RgbaImage &img) {
    std::vector<uint8_t> out;
    if (img.empty()) return out;
    if (!stbi_write_png_to_func(append, &out, img.width, img.height, 4, img.pixels.data(), img.width * 4)) out.clear();
    return out;
}

bool write_png(const std::string &path, const RgbaImage &img, std::string *err) {
    std::vector<uint8_t> png = encode_png(img);
    if (png.empty()) {
        if (err) *err = "no picture to write";
        return false;
    }
    return write_file_atomic(path, png.data(), png.size(), err);
}

std::string frame_hash(const RgbaImage &img) {
    uint64_t h = 1469598103934665603ull;
    auto mix = [&](uint8_t b) { h = (h ^ b) * 1099511628211ull; };
    for (int i = 0; i < 4; i++) mix((uint8_t)(img.width >> (i * 8)));
    for (int i = 0; i < 4; i++) mix((uint8_t)(img.height >> (i * 8)));
    for (size_t i = 0; i + 3 < img.pixels.size(); i += 4) {
        mix(img.pixels[i]);
        mix(img.pixels[i + 1]);
        mix(img.pixels[i + 2]);
    }
    char buf[17];
    snprintf(buf, sizeof buf, "%016llx", (unsigned long long)h);
    return buf;
}

size_t non_black_pixels(const RgbaImage &img) {
    size_t n = 0;
    for (size_t i = 0; i + 3 < img.pixels.size(); i += 4)
        if (img.pixels[i] > 8 || img.pixels[i + 1] > 8 || img.pixels[i + 2] > 8) n++;
    return n;
}
} // namespace sc
