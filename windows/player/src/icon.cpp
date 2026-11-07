/*
 * ShoalcadePlayer: the window icon (icon.h). Part of Shoalcade, GPL-3.0-or-later.
 */
#include "icon.h"

#include <algorithm>
#include <cmath>

namespace sc {
namespace {
// docs/assets/logo.svg on its 24 x 24 grid: the corner radius and the fish (x, y, width; each 1 high).
constexpr float kGrid = 24.0f, kRadius = 5.25f;
struct Cell {
    int x, y, w;
};
constexpr Cell kFish[] = {{14, 6, 2},  {8, 7, 2},   {12, 7, 1},  {14, 7, 3},  {6, 8, 1},  {8, 8, 3},  {13, 8, 4},
                          {7, 9, 4},   {12, 9, 1},  {14, 9, 2},  {6, 10, 1},  {8, 10, 2}, {13, 12, 2}, {9, 13, 1},
                          {12, 13, 5}, {9, 14, 6},  {16, 14, 2}, {10, 15, 8}, {9, 16, 8}, {9, 17, 1},  {12, 17, 4}};

bool in_fish(float gx, float gy) {
    for (const Cell &c : kFish)
        if (gx >= c.x && gx < c.x + c.w && gy >= c.y && gy < c.y + 1) return true;
    return false;
}

bool in_square(float gx, float gy) {
    if (gx < 0 || gy < 0 || gx >= kGrid || gy >= kGrid) return false;
    float cx = std::clamp(gx, kRadius, kGrid - kRadius), cy = std::clamp(gy, kRadius, kGrid - kRadius);
    float dx = gx - cx, dy = gy - cy;
    return dx * dx + dy * dy <= kRadius * kRadius;
}

float channel(uint32_t rgb, int shift) { return (float)((rgb >> shift) & 0xff); }
} // namespace

IconColours icon_colours(const std::string &k) {
    // Each console's own colours (its hardware or its logo's), as a diagonal fade.
    if (k == "ps2") return {0x0B0B10, 0x1F4FB0}; // black and blue
    if (k == "gc") return {0x6A5ACD, 0x2E2470};  // indigo purple
    if (k == "wii") return {0x6CCDF2, 0x1A7FC0}; // light blue
    if (k == "xbox360") return {0x9BD33C, 0x1E5C12}; // lime to dark green (colours only: no logo, X-7)
    if (k == "gb") return {0x8BAC0F, 0x0F380F};  // the first Game Boy's screen greens
    if (k == "gbc") return {0x7B3FA8, 0x2CB5A9}; // grape to teal
    if (k == "gba") return {0x4B3C9E, 0x8E7CD9}; // indigo to lavender
    if (k == "nds") return {0xA7AFB8, 0x3C434C}; // silver to graphite
    if (k == "3ds") return {0xD3122A, 0x6B0715}; // red
    if (k == "psp") return {0x101014, 0x6E7480}; // black to silver
    if (k == "psx") return {0xB4AEA2, 0x5A554C}; // the first PlayStation's warm grey
    if (k == "n64") return {0x2B2B30, 0xC8202F}; // charcoal to red
    return {0xAA5CC3, 0x00A4DC};                 // Shoalcade
}

RgbaImage make_icon(const std::string &console_key, int size) {
    RgbaImage img;
    if (size <= 0) return img;
    const IconColours c = icon_colours(console_key);
    img.width = img.height = size;
    img.pixels.assign((size_t)size * size * 4, 0);
    const int ss = 4; // 4 x 4 samples a pixel: smooth corners, a crisp fish at every size
    const float scale = kGrid / (float)size;
    for (int y = 0; y < size; y++)
        for (int x = 0; x < size; x++) {
            float r = 0, g = 0, b = 0, a = 0;
            for (int sy = 0; sy < ss; sy++)
                for (int sx = 0; sx < ss; sx++) {
                    float gx = (x + (sx + 0.5f) / ss) * scale, gy = (y + (sy + 0.5f) / ss) * scale;
                    if (!in_square(gx, gy)) continue;
                    a += 1;
                    if (in_fish(gx, gy)) {
                        r += 255, g += 255, b += 255;
                        continue;
                    }
                    float t = std::clamp((gx + gy) / (2 * kGrid), 0.0f, 1.0f);
                    r += channel(c.from, 16) + (channel(c.to, 16) - channel(c.from, 16)) * t;
                    g += channel(c.from, 8) + (channel(c.to, 8) - channel(c.from, 8)) * t;
                    b += channel(c.from, 0) + (channel(c.to, 0) - channel(c.from, 0)) * t;
                }
            uint8_t *p = &img.pixels[((size_t)y * size + x) * 4];
            if (a > 0) {
                p[0] = (uint8_t)std::lround(r / a), p[1] = (uint8_t)std::lround(g / a), p[2] = (uint8_t)std::lround(b / a);
                p[3] = (uint8_t)std::lround(a * 255 / (ss * ss));
            }
        }
    return img;
}
} // namespace sc
