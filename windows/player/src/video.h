/*
 * ShoalcadePlayer: SDL3 window + OpenGL 3.3 picture (part of Shoalcade).
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Software cores: each frame (XRGB8888, RGB565 or 0RGB1555) is uploaded to a texture. GL cores (SET_HW_RENDER) draw
 * into our framebuffer object. Either way the picture is drawn letterboxed at the core's aspect ratio, optionally at
 * a whole-number scale, with sharp or smooth filtering.
 */
#pragma once
#include <SDL3/SDL.h>

#include <cstdint>
#include <functional>
#include <string>
#include <vector>

#include "gl.h"
#include "image.h"
#include "libretro/libretro.h"

namespace sc {
struct DisplaySettings {
    bool integer_scale = false;
    bool smooth = false;
};

class Video {
public:
    /**
     * Opens the window and its GL context. `hw` = the core's SET_HW_RENDER request, or nullptr for a software core.
     * Headless: the window is never shown (a GL context still exists, for GL cores and frame capture).
     * newest_gl (GameCube, Wii, PS2: plan P2): a core-profile context of the newest version the driver gives (4.6
     * down to the core's own), not exactly the version the core asked for (Intel then gives exactly 3.3).
     */
    bool create(const std::string &title, const retro_game_geometry &geom, const retro_hw_render_callback *hw,
                bool headless, std::string &err, bool newest_gl = false);
    void destroy();

    /** Framebuffer for GL cores (sized to the core's max geometry). Called again if the geometry grows. */
    bool ensure_hw_fbo(unsigned max_w, unsigned max_h, std::string &err);
    GLuint hw_fbo() const { return fbo_; }

    void set_pixel_format(retro_pixel_format f) { format_ = f; }
    retro_pixel_format pixel_format() const { return format_; }
    void set_geometry(const retro_game_geometry &g);
    void set_display(const DisplaySettings &d) { display_ = d; }
    const DisplaySettings &display() const { return display_; }

    /** A frame from video_refresh. data == RETRO_HW_FRAME_BUFFER_VALID means "it's in the FBO"; nullptr = dupe. */
    void frame(const void *data, unsigned w, unsigned h, size_t pitch);
    bool has_frame() const { return have_frame_; }

    /** Draws the last frame and swaps. Skipped when headless. */
    void present();
    /** The last frame as RGBA, top row first (CPU copy or a read-back from the FBO). */
    RgbaImage capture();

    /** Test hook: read the drawn window back (just before the next swap). */
    void request_window_capture() { want_window_capture_ = true; }
    bool take_window_capture(RgbaImage &out);

    void toggle_fullscreen();
    void set_fullscreen(bool on);
    void note_fullscreen(bool on) { fullscreen_ = on; }
    /** Full screen as last asked (the window manager carries it out a moment later; headless: just remembered). */
    bool fullscreen() const { return fullscreen_; }
    SDL_Window *window() const { return window_; }
    SDL_GLContext context() const { return ctx_; }
    bool headless() const { return headless_; }
    /** Drawn over the game picture each time the window is drawn, before the swap (the pause menu). Args: pixels. */
    std::function<void(int, int)> overlay;
    /** Where the game picture is in window coordinates (for the mouse = touch screen). */
    SDL_FRect game_rect_window() const;
    unsigned frame_width() const { return last_w_; }
    unsigned frame_height() const { return last_h_; }

private:
    bool build_program(std::string &err);
    void compute_rect(int win_w, int win_h, float &x, float &y, float &w, float &h) const;

    SDL_Window *window_ = nullptr;
    SDL_GLContext ctx_ = nullptr;
    bool headless_ = false;
    bool fullscreen_ = false;
    bool is_hw_ = false;
    bool hw_bottom_left_ = true;
    bool hw_depth_ = false, hw_stencil_ = false;
    retro_pixel_format format_ = RETRO_PIXEL_FORMAT_0RGB1555; // libretro's default
    retro_game_geometry geom_{};
    DisplaySettings display_;

    GLuint program_ = 0, vao_ = 0, tex_ = 0;
    GLint u_rect_ = -1, u_tex_ = -1, u_sampler_ = -1;
    unsigned tex_w_ = 0, tex_h_ = 0;
    GLuint fbo_ = 0, fbo_tex_ = 0, fbo_rb_ = 0;
    unsigned fbo_w_ = 0, fbo_h_ = 0;

    bool have_frame_ = false;
    bool last_is_hw_ = false;
    unsigned last_w_ = 0, last_h_ = 0;
    std::vector<uint8_t> last_pixels_; // software frames: tightly packed copy in the core's format
    bool want_window_capture_ = false, have_window_capture_ = false;
    RgbaImage window_capture_;
};
} // namespace sc
