/*
 * ShoalcadePlayer: OpenGL function loader (part of Shoalcade).
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#include "gl.h"

#include <SDL3/SDL.h>

namespace sc::gl {
#define SC_GL_DEFINE(ret, name, args) PFN_##name name = nullptr;
SC_GL_FUNCS(SC_GL_DEFINE)
#undef SC_GL_DEFINE

bool load(const char **missing) {
#define SC_GL_LOAD(ret, name, args)                                                  \
    name = reinterpret_cast<PFN_##name>(SDL_GL_GetProcAddress(#name));               \
    if (!name) {                                                                     \
        if (missing) *missing = #name;                                               \
        return false;                                                                \
    }
    SC_GL_FUNCS(SC_GL_LOAD)
#undef SC_GL_LOAD
    return true;
}
} // namespace sc::gl
