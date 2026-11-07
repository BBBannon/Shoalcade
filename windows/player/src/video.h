/*
 * ShoalcadePlayer: SDL3 window + OpenGL 3.3 picture (part of Shoalcade).
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Software cores: each frame (XRGB8888, RGB565 or 0RGB1555) is uploaded to a texture. GL cores (SET_HW_RENDER) draw
 * into our framebuffer object. Vulkan cores: each frame is copied into a Vulkan image shared with OpenGL
 * (vulkan_host.h), which is then our framebuffer object's texture. Either way the picture is drawn letterboxed at the
 * core's aspect ratio, optionally at a whole-number scale, with sharp or smooth filtering.
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
class VulkanHost;
struct GpuId;

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
     * core_context (the core sent SET_HW_SHARED_CONTEXT: Dolphin, LRPS2, PCEE2): the core gets a GL context of its
     * own, sharing textures with ours, as RetroArch does. Those cores keep GL state between frames and skip setting
     * it again, so our drawing must never touch their context (Dolphin showed black frames when we did, 02/11/2026).
     * The core's context is the current one except while we draw (present, capture, framebuffer set-up).
     */
    bool create(const std::string &title, const retro_game_geometry &geom, const retro_hw_render_callback *hw,
                bool headless, std::string &err, bool newest_gl = false, bool core_context = false);
    void destroy();

    /** Framebuffer for GL cores (sized to the core's max geometry). Called again if the geometry grows. */
    bool ensure_hw_fbo(unsigned max_w, unsigned max_h, std::string &err);
    /** The framebuffer the core draws into (in the core's own context when it has one). */
    GLuint hw_fbo() const { return core_ctx_ ? core_fbo_ : fbo_; }
    /** Makes our context current (before the pause menu shuts down); the core's comes back with use_core(). */
    void use_player();
    void use_core();

    /**
     * Vulkan cores: the OpenGL context's GPU, if its driver can show Vulkan pictures (GL_EXT_memory_object_win32 and
     * GL_EXT_semaphore_win32). False = it can't (err says so, for the person).
     */
    bool vulkan_gpu(GpuId &out, std::string &err);
    /** Vulkan cores: frames come from vk from now on (before the core's context_reset). */
    void attach_vulkan(VulkanHost *vk);
    /** Frees OpenGL's side of the shared picture (before vk is destroyed). */
    void detach_vulkan();
    /** Set when a Vulkan frame couldn't be shown: the session can't go on. */
    const std::string &vulkan_error() const { return vk_error_; }

    void set_pixel_format(retro_pixel_format f) { format_ = f; }
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

    void set_fullscreen(bool on);
    void note_fullscreen(bool on) { fullscreen_ = on; }
    /** Full screen as last asked (the window manager carries it out a moment later; headless: just remembered). */
    bool fullscreen() const { return fullscreen_; }
    SDL_Window *window() const { return window_; }
    SDL_GLContext context() const { return ctx_; }
    bool headless() const { return headless_; }
    /** The window icon: the Shoalcade logo in [console_key]'s colours (icon.h), 32 px plus a 256 px version. */
    void set_icon(const std::string &console_key);
    /** Drawn over the game picture each time the window is drawn, before the swap (the pause menu). Args: pixels. */
    std::function<void(int, int)> overlay;
    /** Where the game picture is in window coordinates (for the mouse = touch screen). */
    SDL_FRect game_rect_window() const;

private:
    bool build_program(std::string &err);
    void vk_frame(const void *data, unsigned w, unsigned h);
    bool vk_import(unsigned w, unsigned h, std::string &err);
    void vk_release();
    void compute_rect(int win_w, int win_h, float &x, float &y, float &w, float &h) const;

    SDL_Window *window_ = nullptr;
    SDL_GLContext ctx_ = nullptr;
    SDL_GLContext core_ctx_ = nullptr; // the core's own context (core_context), else none
    GLuint core_fbo_ = 0;              // the core's framebuffer object (FBOs aren't shared between contexts)
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
    // Vulkan cores: the shared picture is fbo_tex_ (fbo_ reads it back); its memory and the two semaphores.
    VulkanHost *vk_ = nullptr;
    GLuint vk_mem_ = 0, vk_ready_ = 0, vk_done_ = 0;
    bool vk_gl_owns_ = false; // OpenGL waited for the last copy: it signals vk_done_ before the next
    std::string vk_error_;
    bool want_window_capture_ = false, have_window_capture_ = false;
    RgbaImage window_capture_;
};
} // namespace sc
