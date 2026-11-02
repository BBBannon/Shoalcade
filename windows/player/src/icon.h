/*
 * ShoalcadePlayer: the window icon, the Shoalcade logo in each console's colours (part of Shoalcade).
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#pragma once
#include <cstdint>
#include <string>

#include "image.h"

namespace sc {
/** The logo's diagonal fade for a console: top-left [from] to bottom-right [to], 0xRRGGBB. */
struct IconColours {
    uint32_t from, to;
};
/** [console_key] ("ps2", "gc", ...); an unknown key gets Shoalcade's own purple to blue. */
IconColours icon_colours(const std::string &console_key);
/**
 * The Shoalcade logo (docs/assets/logo.svg: a rounded square, the white pixel fish) drawn at [size] x [size] in
 * [console_key]'s colours, see-through outside the rounded corners. Drawn here, so there are no picture files.
 */
RgbaImage make_icon(const std::string &console_key, int size);
} // namespace sc
