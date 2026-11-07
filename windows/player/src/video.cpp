/*
 * ShoalcadePlayer: SDL3 window + OpenGL 3.3 picture (part of Shoalcade).
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#include "video.h"
#include "icon.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>

#include "log.h"
#include "vulkan_host.h"

namespace sc {
using namespace sc::gl;

namespace {
const char *kVertex = R"(#version 330 core
uniform vec4 uRect; // x0, y0, x1, y1 in clip space (y0 = bottom)
uniform vec4 uTex;  // u0, v0, u1, v1 (v0 = texture row shown at the bottom)
out vec2 vUV;
void main() {
    vec2 p = vec2((gl_VertexID & 1) != 0 ? 1.0 : 0.0, (gl_VertexID & 2) != 0 ? 1.0 : 0.0);
    gl_Position = vec4(mix(uRect.xy, uRect.zw, p), 0.0, 1.0);
    vUV = mix(uTex.xy, uTex.zw, p);
}
)";
const char *kFragment = R"(#version 330 core
uniform sampler2D uImage;
in vec2 vUV;
out vec4 fragColor;
void main() { fragColor = vec4(texture(uImage, vUV).rgb, 1.0); }
)";

unsigned bytes_per_pixel(retro_pixel_format f) { return f == RETRO_PIXEL_FORMAT_XRGB8888 ? 4 : 2; }

void gl_format(retro_pixel_format f, GLenum &fmt, GLenum &type) {
    switch (f) {
    case RETRO_PIXEL_FORMAT_XRGB8888: fmt = GL_BGRA, type = GL_UNSIGNED_INT_8_8_8_8_REV; break;
    case RETRO_PIXEL_FORMAT_RGB565: fmt = GL_RGB, type = GL_UNSIGNED_SHORT_5_6_5; break;
    default: fmt = GL_BGRA, type = GL_UNSIGNED_SHORT_1_5_5_5_REV; break;
    }
}

GLuint compile(GLenum kind, const char *src, std::string &err) {
    GLuint s = glCreateShader(kind);
    glShaderSource(s, 1, &src, nullptr);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[1024] = {0};
        glGetShaderInfoLog(s, sizeof log, nullptr, log);
        err = std::string("shader didn't compile: ") + log;
        glDeleteShader(s);
        return 0;
    }
    return s;
}
} // namespace

bool Video::create(const std::string &title, const retro_game_geometry &geom, const retro_hw_render_callback *hw,
                   bool headless, std::string &err, bool newest_gl, bool core_context) {
    headless_ = headless;
    geom_ = geom;
    is_hw_ = hw != nullptr;
    int major = 3, minor = 3;
    bool core_profile = true;
    if (hw) {
        hw_bottom_left_ = hw->bottom_left_origin;
        hw_depth_ = hw->depth;
        hw_stencil_ = hw->stencil;
        if (hw->context_type == RETRO_HW_CONTEXT_OPENGL_CORE) {
            if ((int)hw->version_major * 10 + (int)hw->version_minor > 33)
                major = (int)hw->version_major, minor = (int)hw->version_minor;
        } else {
            core_profile = false; // RETRO_HW_CONTEXT_OPENGL: a compatibility context (still 3.3 for our own drawing)
        }
    }
    SDL_GL_SetAttribute(SDL_GL_CONTEXT_MAJOR_VERSION, major);
    SDL_GL_SetAttribute(SDL_GL_CONTEXT_MINOR_VERSION, minor);
    SDL_GL_SetAttribute(SDL_GL_CONTEXT_PROFILE_MASK,
                        core_profile ? SDL_GL_CONTEXT_PROFILE_CORE : SDL_GL_CONTEXT_PROFILE_COMPATIBILITY);
    SDL_GL_SetAttribute(SDL_GL_DOUBLEBUFFER, 1);
    SDL_GL_SetAttribute(SDL_GL_DEPTH_SIZE, 0);
    SDL_GL_SetAttribute(SDL_GL_STENCIL_SIZE, 0);
    if (hw && hw->debug_context) SDL_GL_SetAttribute(SDL_GL_CONTEXT_FLAGS, SDL_GL_CONTEXT_DEBUG_FLAG);

    // Start at 3x the core's size (2x for big pictures), never bigger than most of the screen.
    float aspect = geom.aspect_ratio > 0 ? geom.aspect_ratio : (float)geom.base_width / (float)std::max(1u, geom.base_height);
    int scale = geom.base_height >= 400 ? 2 : 3;
    int win_h = (int)geom.base_height * scale, win_w = (int)std::lround(win_h * aspect);
    if (!headless) {
        SDL_DisplayID d = SDL_GetPrimaryDisplay();
        SDL_Rect usable;
        if (d && SDL_GetDisplayUsableBounds(d, &usable)) {
            float fit = std::min(usable.w * 0.85f / win_w, usable.h * 0.85f / win_h);
            if (fit < 1) win_w = (int)(win_w * fit), win_h = (int)(win_h * fit);
        }
    }
    SDL_WindowFlags flags = SDL_WINDOW_OPENGL | SDL_WINDOW_RESIZABLE | SDL_WINDOW_HIGH_PIXEL_DENSITY;
    if (headless) flags |= SDL_WINDOW_HIDDEN;
    window_ = SDL_CreateWindow(title.c_str(), std::max(win_w, 160), std::max(win_h, 144), flags);
    if (!window_) {
        err = std::string("couldn't open the window: ") + SDL_GetError();
        return false;
    }
    if (newest_gl && core_profile) {
        // P2: the newest core profile the driver has, down to what the core asked for (then that, below).
        static const int kVersions[][2] = {{4, 6}, {4, 5}, {4, 4}, {4, 3}, {4, 2}, {4, 1}, {4, 0}};
        for (const auto &v : kVersions) {
            if (v[0] * 10 + v[1] <= major * 10 + minor) break;
            SDL_GL_SetAttribute(SDL_GL_CONTEXT_MAJOR_VERSION, v[0]);
            SDL_GL_SetAttribute(SDL_GL_CONTEXT_MINOR_VERSION, v[1]);
            ctx_ = SDL_GL_CreateContext(window_);
            if (ctx_) {
                major = v[0], minor = v[1];
                break;
            }
        }
        if (!ctx_) {
            SDL_GL_SetAttribute(SDL_GL_CONTEXT_MAJOR_VERSION, major);
            SDL_GL_SetAttribute(SDL_GL_CONTEXT_MINOR_VERSION, minor);
        }
    }
    if (!ctx_) ctx_ = SDL_GL_CreateContext(window_);
    if (!ctx_) {
        err = std::string("couldn't start OpenGL ") + std::to_string(major) + "." + std::to_string(minor) +
              (core_profile ? " core" : " compatibility") + ": " + SDL_GetError();
        return false;
    }
    SDL_GL_MakeCurrent(window_, ctx_);
    SDL_GL_SetSwapInterval(0); // the player paces frames itself (the core's rate, not the monitor's)
    const char *missing = nullptr;
    if (!gl::load(&missing)) {
        err = std::string("OpenGL function missing: ") + (missing ? missing : "?");
        return false;
    }
    LOGI("OpenGL %s | %s | %s", (const char *)glGetString(GL_VERSION), (const char *)glGetString(GL_RENDERER),
         (const char *)glGetString(GL_VENDOR));
    if (!build_program(err)) return false;
    glGenVertexArrays(1, &vao_);
    glGenTextures(1, &tex_);
    if (core_context) {
        // Same version and profile as ours (those attributes are still set), sharing textures and renderbuffers.
        SDL_GL_SetAttribute(SDL_GL_SHARE_WITH_CURRENT_CONTEXT, 1);
        core_ctx_ = SDL_GL_CreateContext(window_);
        SDL_GL_SetAttribute(SDL_GL_SHARE_WITH_CURRENT_CONTEXT, 0);
        if (!core_ctx_) {
            err = std::string("couldn't start the game's own OpenGL context: ") + SDL_GetError();
            return false;
        }
        SDL_GL_MakeCurrent(window_, ctx_);
        LOGI("the core has its own GL context (shared context)");
    }
    return true;
}

void Video::set_icon(const std::string &console_key) {
    if (!window_) return;
    RgbaImage small = make_icon(console_key, 32), big = make_icon(console_key, 256);
    SDL_Surface *s = SDL_CreateSurfaceFrom(small.width, small.height, SDL_PIXELFORMAT_RGBA32, small.pixels.data(), small.width * 4);
    SDL_Surface *b = SDL_CreateSurfaceFrom(big.width, big.height, SDL_PIXELFORMAT_RGBA32, big.pixels.data(), big.width * 4);
    if (s && b) SDL_AddSurfaceAlternateImage(s, b); // Windows picks the size it needs (title bar, taskbar, Alt+Tab)
    if (s && !SDL_SetWindowIcon(window_, s)) LOGW("window icon not set: %s", SDL_GetError());
    if (b) SDL_DestroySurface(b);
    if (s) SDL_DestroySurface(s);
}

void Video::use_player() {
    if (core_ctx_) SDL_GL_MakeCurrent(window_, ctx_);
}

void Video::use_core() {
    if (core_ctx_) SDL_GL_MakeCurrent(window_, core_ctx_);
}

namespace {
// Our drawing runs in our context; the core's context is current again afterwards.
struct PlayerContext {
    Video &v;
    explicit PlayerContext(Video &video) : v(video) { v.use_player(); }
    ~PlayerContext() { v.use_core(); }
};
} // namespace

bool Video::build_program(std::string &err) {
    GLuint vs = compile(GL_VERTEX_SHADER, kVertex, err);
    if (!vs) return false;
    GLuint fs = compile(GL_FRAGMENT_SHADER, kFragment, err);
    if (!fs) return false;
    program_ = glCreateProgram();
    glAttachShader(program_, vs);
    glAttachShader(program_, fs);
    glLinkProgram(program_);
    glDeleteShader(vs);
    glDeleteShader(fs);
    GLint ok = 0;
    glGetProgramiv(program_, GL_LINK_STATUS, &ok);
    if (!ok) {
        char log[1024] = {0};
        glGetProgramInfoLog(program_, sizeof log, nullptr, log);
        err = std::string("shader didn't link: ") + log;
        return false;
    }
    u_rect_ = glGetUniformLocation(program_, "uRect");
    u_tex_ = glGetUniformLocation(program_, "uTex");
    u_sampler_ = glGetUniformLocation(program_, "uImage");
    return true;
}

void Video::destroy() {
    if (vk_ && ctx_) {
        SDL_GL_MakeCurrent(window_, ctx_);
        vk_release();
        vk_ = nullptr;
    }
    if (core_ctx_) {
        SDL_GL_MakeCurrent(window_, core_ctx_);
        if (core_fbo_) glDeleteFramebuffers(1, &core_fbo_), core_fbo_ = 0;
        SDL_GL_MakeCurrent(window_, ctx_);
        SDL_GL_DestroyContext(core_ctx_);
        core_ctx_ = nullptr;
    }
    if (ctx_) {
        if (fbo_) glDeleteFramebuffers(1, &fbo_);
        if (fbo_tex_) glDeleteTextures(1, &fbo_tex_);
        if (fbo_rb_) glDeleteRenderbuffers(1, &fbo_rb_);
        if (tex_) glDeleteTextures(1, &tex_);
        if (vao_) glDeleteVertexArrays(1, &vao_);
        if (program_) glDeleteProgram(program_);
        SDL_GL_DestroyContext(ctx_);
        ctx_ = nullptr;
    }
    if (window_) SDL_DestroyWindow(window_);
    window_ = nullptr;
}

bool Video::ensure_hw_fbo(unsigned max_w, unsigned max_h, std::string &err) {
    PlayerContext in_ours(*this);
    max_w = std::max(1u, max_w), max_h = std::max(1u, max_h);
    if (fbo_ && max_w <= fbo_w_ && max_h <= fbo_h_) return true;
    if (fbo_) glDeleteFramebuffers(1, &fbo_), fbo_ = 0;
    if (fbo_tex_) glDeleteTextures(1, &fbo_tex_), fbo_tex_ = 0;
    if (fbo_rb_) glDeleteRenderbuffers(1, &fbo_rb_), fbo_rb_ = 0;
    glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
    glGenTextures(1, &fbo_tex_);
    glBindTexture(GL_TEXTURE_2D, fbo_tex_);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, (GLsizei)max_w, (GLsizei)max_h, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glGenFramebuffers(1, &fbo_);
    glBindFramebuffer(GL_FRAMEBUFFER, fbo_);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, fbo_tex_, 0);
    if (hw_depth_ || hw_stencil_) {
        glGenRenderbuffers(1, &fbo_rb_);
        glBindRenderbuffer(GL_RENDERBUFFER, fbo_rb_);
        glRenderbufferStorage(GL_RENDERBUFFER, hw_stencil_ ? GL_DEPTH24_STENCIL8 : GL_DEPTH_COMPONENT24,
                              (GLsizei)max_w, (GLsizei)max_h);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, hw_stencil_ ? GL_DEPTH_STENCIL_ATTACHMENT : GL_DEPTH_ATTACHMENT,
                                  GL_RENDERBUFFER, fbo_rb_);
    }
    GLenum status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    if (status != GL_FRAMEBUFFER_COMPLETE) {
        err = "the game's framebuffer couldn't be made (status " + std::to_string(status) + ")";
        return false;
    }
    glClearColor(0, 0, 0, 1);
    glClear(GL_COLOR_BUFFER_BIT);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    if (core_ctx_) {
        // The core draws through a framebuffer object of its own context, on the same (shared) texture; ours above
        // stays for reading the picture back.
        glFlush();
        SDL_GL_MakeCurrent(window_, core_ctx_);
        if (core_fbo_) glDeleteFramebuffers(1, &core_fbo_), core_fbo_ = 0;
        glGenFramebuffers(1, &core_fbo_);
        glBindFramebuffer(GL_FRAMEBUFFER, core_fbo_);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, fbo_tex_, 0);
        if (fbo_rb_)
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, hw_stencil_ ? GL_DEPTH_STENCIL_ATTACHMENT : GL_DEPTH_ATTACHMENT,
                                      GL_RENDERBUFFER, fbo_rb_);
        GLenum core_status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        SDL_GL_MakeCurrent(window_, ctx_);
        if (core_status != GL_FRAMEBUFFER_COMPLETE) {
            err = "the game's framebuffer couldn't be made in its own context (status " + std::to_string(core_status) + ")";
            return false;
        }
    }
    fbo_w_ = max_w, fbo_h_ = max_h;
    LOGI("game framebuffer %ux%u (depth %d, stencil %d)", max_w, max_h, hw_depth_, hw_stencil_);
    return true;
}

void Video::set_geometry(const retro_game_geometry &g) {
    geom_.base_width = g.base_width;
    geom_.base_height = g.base_height;
    geom_.aspect_ratio = g.aspect_ratio;
    if (g.max_width) geom_.max_width = g.max_width;
    if (g.max_height) geom_.max_height = g.max_height;
}

void Video::frame(const void *data, unsigned w, unsigned h, size_t pitch) {
    if (vk_ && (!data || data == RETRO_HW_FRAME_BUFFER_VALID)) return vk_frame(data, w, h);
    if (!data) return; // dupe: keep showing the last frame
    if (w == 0 || h == 0) return;
    last_w_ = w, last_h_ = h;
    have_frame_ = true;
    if (data == RETRO_HW_FRAME_BUFFER_VALID) {
        last_is_hw_ = true;
        return;
    }
    last_is_hw_ = false;
    unsigned bpp = bytes_per_pixel(format_);
    size_t row = (size_t)w * bpp;
    last_pixels_.resize(row * h);
    const uint8_t *src = static_cast<const uint8_t *>(data);
    for (unsigned y = 0; y < h; y++) memcpy(&last_pixels_[y * row], src + y * pitch, row);
    if (headless_) return; // uploaded only when shown
    GLenum fmt, type;
    gl_format(format_, fmt, type);
    glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, tex_);
    glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
    glPixelStorei(GL_UNPACK_ALIGNMENT, bpp == 4 ? 4 : 2);
    if (w > tex_w_ || h > tex_h_) {
        tex_w_ = std::max(w, std::max(tex_w_, geom_.max_width));
        tex_h_ = std::max(h, std::max(tex_h_, geom_.max_height));
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, (GLsizei)tex_w_, (GLsizei)tex_h_, 0, fmt, type, nullptr);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    }
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, (GLsizei)w, (GLsizei)h, fmt, type, last_pixels_.data());
}

bool Video::vulkan_gpu(GpuId &out, std::string &err) {
    PlayerContext in_ours(*this);
    out = GpuId{};
    const char *renderer = (const char *)glGetString(GL_RENDERER);
    std::string on = std::string(" on ") + (renderer ? renderer : "this graphics card");
    const char *missing = nullptr;
    if (!gl::load_interop(&missing))
        return err = std::string("This game needs a graphics driver that can show Vulkan pictures in OpenGL (") +
                     (missing ? missing : "?") + " missing" + on + "). Install your graphics card's latest driver.", false;
    static const char *kNeeded[] = {"GL_EXT_memory_object", "GL_EXT_memory_object_win32", "GL_EXT_semaphore",
                                    "GL_EXT_semaphore_win32"};
    GLint n = 0;
    glGetIntegerv(GL_NUM_EXTENSIONS, &n);
    for (const char *want : kNeeded) {
        bool have = false;
        for (GLint i = 0; i < n && !have; i++) {
            const char *e = (const char *)glGetStringi(GL_EXTENSIONS, (GLuint)i);
            have = e && std::strcmp(e, want) == 0;
        }
        if (!have)
            return err = std::string("This game needs a graphics driver that can show Vulkan pictures in OpenGL (") +
                         want + " missing" + on + "). Install your graphics card's latest driver.", false;
    }
    while (glGetError() != GL_NO_ERROR) {}
    glGetUnsignedBytevEXT(GL_DEVICE_LUID_EXT, out.luid);
    out.has_luid = glGetError() == GL_NO_ERROR;
    GLint uuids = 0;
    glGetIntegerv(GL_NUM_DEVICE_UUIDS_EXT, &uuids);
    if (uuids > 0) {
        glGetUnsignedBytei_vEXT(GL_DEVICE_UUID_EXT, 0, out.uuid);
        out.has_uuid = glGetError() == GL_NO_ERROR;
    }
    if (!out.has_luid && !out.has_uuid) return err = "OpenGL didn't say which graphics card it runs on" + on, false;
    return true;
}

void Video::attach_vulkan(VulkanHost *vk) {
    vk_ = vk;
    hw_bottom_left_ = false; // Vulkan's row 0 is the top of the picture
}

void Video::detach_vulkan() {
    if (!vk_) return;
    PlayerContext in_ours(*this);
    vk_release();
    vk_ = nullptr;
}

void Video::vk_release() {
    if (!vk_mem_ && !vk_ready_ && !vk_done_ && !fbo_ && !fbo_tex_) return;
    glFinish(); // OpenGL is done with the image before Vulkan frees it
    if (fbo_) glDeleteFramebuffers(1, &fbo_), fbo_ = 0;
    if (fbo_tex_) glDeleteTextures(1, &fbo_tex_), fbo_tex_ = 0;
    if (vk_mem_) glDeleteMemoryObjectsEXT(1, &vk_mem_), vk_mem_ = 0;
    if (vk_ready_) glDeleteSemaphoresEXT(1, &vk_ready_), vk_ready_ = 0;
    if (vk_done_) glDeleteSemaphoresEXT(1, &vk_done_), vk_done_ = 0;
    fbo_w_ = fbo_h_ = 0;
    vk_gl_owns_ = false;
}

bool Video::vk_import(unsigned w, unsigned h, std::string &err) {
    vk_release();
    SharedTarget t;
    if (!vk_->make_target(w, h, t, err)) return false;
    while (glGetError() != GL_NO_ERROR) {}
    glCreateMemoryObjectsEXT(1, &vk_mem_);
    GLint dedicated = 1; // the Vulkan memory is the image's own (VkMemoryDedicatedAllocateInfo)
    glMemoryObjectParameterivEXT(vk_mem_, GL_DEDICATED_MEMORY_OBJECT_EXT, &dedicated);
    glImportMemoryWin32HandleEXT(vk_mem_, t.size, GL_HANDLE_TYPE_OPAQUE_WIN32_EXT, t.memory);
    glGenSemaphoresEXT(1, &vk_ready_);
    glImportSemaphoreWin32HandleEXT(vk_ready_, GL_HANDLE_TYPE_OPAQUE_WIN32_EXT, t.ready);
    glGenSemaphoresEXT(1, &vk_done_);
    glImportSemaphoreWin32HandleEXT(vk_done_, GL_HANDLE_TYPE_OPAQUE_WIN32_EXT, t.done);
    // Importing a Win32 handle doesn't take it over (EXT_external_objects_win32): ours to close.
    close_shared_handle(t.memory), close_shared_handle(t.ready), close_shared_handle(t.done);
    glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
    glGenTextures(1, &fbo_tex_);
    glBindTexture(GL_TEXTURE_2D, fbo_tex_);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_TILING_EXT, GL_OPTIMAL_TILING_EXT);
    glTexStorageMem2DEXT(GL_TEXTURE_2D, 1, GL_RGBA8, (GLsizei)w, (GLsizei)h, vk_mem_, 0);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glGenFramebuffers(1, &fbo_); // for reading the picture back (screenshots, frame hashes)
    glBindFramebuffer(GL_FRAMEBUFFER, fbo_);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, fbo_tex_, 0);
    GLenum status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    GLenum e = glGetError();
    if (e != GL_NO_ERROR || status != GL_FRAMEBUFFER_COMPLETE) {
        char buf[96];
        snprintf(buf, sizeof buf, "OpenGL couldn't take the shared Vulkan picture (GL error 0x%x, framebuffer 0x%x)", e, status);
        vk_release();
        return err = buf, false;
    }
    fbo_w_ = w, fbo_h_ = h;
    return true;
}

void Video::vk_frame(const void *data, unsigned w, unsigned h) {
    if (!vk_error_.empty()) return;
    PlayerContext in_ours(*this);
    // A new frame only with a set_image since the last one; anything else keeps the last picture (a dupe).
    bool copy = data == RETRO_HW_FRAME_BUFFER_VALID && w && h && vk_->has_new_image();
    std::string err;
    if (copy && (!vk_mem_ || w > fbo_w_ || h > fbo_h_)) {
        unsigned tw = std::max(w, std::min(geom_.max_width, 4096u)), th = std::max(h, std::min(geom_.max_height, 4096u));
        if (!vk_import(tw, th, err)) {
            vk_error_ = err;
            return;
        }
    }
    const GLenum layout = GL_LAYOUT_SHADER_READ_ONLY_EXT;
    bool signalled = false;
    if (copy && vk_gl_owns_) {
        glSignalSemaphoreEXT(vk_done_, 0, nullptr, 1, &fbo_tex_, &layout);
        glFlush(); // the signal must reach the GPU before Vulkan waits for it
        signalled = true;
    }
    if (!vk_->frame(copy, w, h, signalled, err)) {
        vk_error_ = err;
        return;
    }
    if (!copy) return;
    glWaitSemaphoreEXT(vk_ready_, 0, nullptr, 1, &fbo_tex_, &layout);
    vk_gl_owns_ = true;
    last_w_ = w, last_h_ = h;
    have_frame_ = true;
    last_is_hw_ = true;
}

void Video::compute_rect(int win_w, int win_h, float &x, float &y, float &w, float &h) const {
    float aspect = geom_.aspect_ratio > 0 ? geom_.aspect_ratio
                                          : (float)std::max(1u, last_w_) / (float)std::max(1u, last_h_);
    float src_h = (float)std::max(1u, last_h_ ? last_h_ : geom_.base_height);
    float src_w = src_h * aspect;
    if (display_.integer_scale) {
        int k = (int)std::floor(std::min(win_w / src_w, win_h / src_h));
        if (k >= 1) {
            w = std::round(src_w * k), h = src_h * k;
            x = std::floor((win_w - w) / 2), y = std::floor((win_h - h) / 2);
            return;
        }
    }
    if (win_w / (float)win_h > aspect) h = (float)win_h, w = std::round(win_h * aspect);
    else w = (float)win_w, h = std::round(win_w / aspect);
    x = std::floor((win_w - w) / 2), y = std::floor((win_h - h) / 2);
}

SDL_FRect Video::game_rect_window() const {
    int ww = 0, wh = 0;
    if (window_) SDL_GetWindowSize(window_, &ww, &wh);
    SDL_FRect r{0, 0, (float)ww, (float)wh};
    if (ww > 0 && wh > 0) compute_rect(ww, wh, r.x, r.y, r.w, r.h);
    return r;
}

void Video::present() {
    if (headless_ || !window_) return;
    PlayerContext in_ours(*this);
    int pw = 0, ph = 0;
    SDL_GetWindowSizeInPixels(window_, &pw, &ph);
    // A GL core may leave any state behind: set everything our drawing depends on.
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_STENCIL_TEST);
    glDisable(GL_BLEND);
    glDisable(GL_SCISSOR_TEST);
    glDisable(GL_CULL_FACE);
    glDisable(GL_FRAMEBUFFER_SRGB);
    glColorMask(1, 1, 1, 1);
    glViewport(0, 0, pw, ph);
    glClearColor(0, 0, 0, 1);
    glClear(GL_COLOR_BUFFER_BIT);
    if (have_frame_ && pw > 0 && ph > 0) {
        float x, y, w, h;
        compute_rect(pw, ph, x, y, w, h);
        // Window pixels (top-left origin) -> clip space (bottom-left origin).
        float x0 = x / pw * 2 - 1, x1 = (x + w) / pw * 2 - 1;
        float y1 = 1 - y / ph * 2, y0 = 1 - (y + h) / ph * 2;
        glUseProgram(program_);
        glBindVertexArray(vao_);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glActiveTexture(GL_TEXTURE0);
        glBindSampler(0, 0);
        GLuint tex = last_is_hw_ ? fbo_tex_ : tex_;
        float tw = (float)(last_is_hw_ ? fbo_w_ : tex_w_), th = (float)(last_is_hw_ ? fbo_h_ : tex_h_);
        glBindTexture(GL_TEXTURE_2D, tex);
        GLint filter = display_.smooth ? GL_LINEAR : GL_NEAREST;
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
        glUniform1i(u_sampler_, 0);
        glUniform4f(u_rect_, x0, y0, x1, y1);
        float u1 = last_w_ / tw, v = last_h_ / th;
        bool flip = last_is_hw_ && hw_bottom_left_; // FBO row 0 is the bottom of the picture
        if (flip) glUniform4f(u_tex_, 0, 0, u1, v);
        else glUniform4f(u_tex_, 0, v, u1, 0);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        glBindVertexArray(0);
        glUseProgram(0);
    }
    if (overlay) {
        overlay(pw, ph);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }
    if (want_window_capture_) {
        want_window_capture_ = false;
        window_capture_.width = pw, window_capture_.height = ph;
        window_capture_.pixels.assign((size_t)pw * ph * 4, 0);
        glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
        glPixelStorei(GL_PACK_ALIGNMENT, 4);
        glPixelStorei(GL_PACK_ROW_LENGTH, 0);
        glReadPixels(0, 0, pw, ph, GL_RGBA, GL_UNSIGNED_BYTE, window_capture_.pixels.data());
        size_t row = (size_t)pw * 4;
        std::vector<uint8_t> tmp(row);
        for (int y = 0; y < ph / 2; y++) {
            uint8_t *a = &window_capture_.pixels[y * row], *b = &window_capture_.pixels[(ph - 1 - y) * row];
            memcpy(tmp.data(), a, row), memcpy(a, b, row), memcpy(b, tmp.data(), row);
        }
        for (size_t i = 3; i < window_capture_.pixels.size(); i += 4) window_capture_.pixels[i] = 255;
        have_window_capture_ = true;
    }
    SDL_GL_SwapWindow(window_);
}

bool Video::take_window_capture(RgbaImage &out) {
    if (!have_window_capture_) return false;
    have_window_capture_ = false;
    out = std::move(window_capture_);
    window_capture_ = RgbaImage{};
    return true;
}

RgbaImage Video::capture() {
    RgbaImage img;
    if (!have_frame_) return img;
    PlayerContext in_ours(*this);
    img.width = (int)last_w_, img.height = (int)last_h_;
    img.pixels.resize((size_t)last_w_ * last_h_ * 4);
    if (last_is_hw_) {
        glBindFramebuffer(GL_READ_FRAMEBUFFER, fbo_);
        glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
        glPixelStorei(GL_PACK_ALIGNMENT, 4);
        glPixelStorei(GL_PACK_ROW_LENGTH, 0);
        glReadPixels(0, 0, (GLsizei)last_w_, (GLsizei)last_h_, GL_RGBA, GL_UNSIGNED_BYTE, img.pixels.data());
        glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
        if (hw_bottom_left_) {
            size_t row = (size_t)last_w_ * 4;
            std::vector<uint8_t> tmp(row);
            for (unsigned y = 0; y < last_h_ / 2; y++) {
                uint8_t *a = &img.pixels[y * row], *b = &img.pixels[(last_h_ - 1 - y) * row];
                memcpy(tmp.data(), a, row);
                memcpy(a, b, row);
                memcpy(b, tmp.data(), row);
            }
        }
        for (size_t i = 3; i < img.pixels.size(); i += 4) img.pixels[i] = 255;
        return img;
    }
    size_t n = (size_t)last_w_ * last_h_;
    uint8_t *out = img.pixels.data();
    if (format_ == RETRO_PIXEL_FORMAT_XRGB8888) {
        for (size_t i = 0; i < n; i++) {
            uint32_t v;
            memcpy(&v, &last_pixels_[i * 4], 4);
            out[i * 4] = (v >> 16) & 255, out[i * 4 + 1] = (v >> 8) & 255, out[i * 4 + 2] = v & 255, out[i * 4 + 3] = 255;
        }
    } else {
        bool is565 = format_ == RETRO_PIXEL_FORMAT_RGB565;
        for (size_t i = 0; i < n; i++) {
            uint16_t v;
            memcpy(&v, &last_pixels_[i * 2], 2);
            unsigned r, g, b;
            if (is565) r = (v >> 11) & 31, g = (v >> 5) & 63, b = v & 31;
            else r = (v >> 10) & 31, g = (v >> 5) & 31, b = v & 31;
            out[i * 4] = (uint8_t)((r << 3) | (r >> 2));
            out[i * 4 + 1] = is565 ? (uint8_t)((g << 2) | (g >> 4)) : (uint8_t)((g << 3) | (g >> 2));
            out[i * 4 + 2] = (uint8_t)((b << 3) | (b >> 2));
            out[i * 4 + 3] = 255;
        }
    }
    return img;
}

void Video::set_fullscreen(bool on) {
    fullscreen_ = on;
    if (headless_ || !window_) return;
    SDL_SetWindowFullscreen(window_, on); // borderless "desktop" full screen (no mode change)
}
} // namespace sc
