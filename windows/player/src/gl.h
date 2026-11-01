/*
 * ShoalcadePlayer: the few OpenGL 3.3 functions the player uses, loaded through SDL (part of Shoalcade).
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Windows' opengl32.dll only exports OpenGL 1.1, so every function is looked up at run time, on both platforms.
 */
#pragma once
#include <cstddef>
#include <cstdint>

#if defined(_WIN32)
#define SC_GLAPI __stdcall
#else
#define SC_GLAPI
#endif

typedef unsigned int GLenum;
typedef unsigned int GLuint;
typedef int GLint;
typedef int GLsizei;
typedef unsigned int GLbitfield;
typedef float GLfloat;
typedef unsigned char GLboolean;
typedef char GLchar;
typedef unsigned char GLubyte;

#define GL_NO_ERROR 0
#define GL_TRIANGLE_STRIP 0x0005
#define GL_CULL_FACE 0x0B44
#define GL_DEPTH_TEST 0x0B71
#define GL_STENCIL_TEST 0x0B90
#define GL_BLEND 0x0BE2
#define GL_SCISSOR_TEST 0x0C11
#define GL_UNPACK_ROW_LENGTH 0x0CF2
#define GL_UNPACK_ALIGNMENT 0x0CF5
#define GL_PACK_ROW_LENGTH 0x0D02
#define GL_PACK_ALIGNMENT 0x0D05
#define GL_TEXTURE_2D 0x0DE1
#define GL_UNSIGNED_BYTE 0x1401
#define GL_RGB 0x1907
#define GL_RGBA 0x1908
#define GL_VENDOR 0x1F00
#define GL_RENDERER 0x1F01
#define GL_VERSION 0x1F02
#define GL_NEAREST 0x2600
#define GL_LINEAR 0x2601
#define GL_TEXTURE_MAG_FILTER 0x2800
#define GL_TEXTURE_MIN_FILTER 0x2801
#define GL_TEXTURE_WRAP_S 0x2802
#define GL_TEXTURE_WRAP_T 0x2803
#define GL_COLOR_BUFFER_BIT 0x4000
#define GL_RGBA8 0x8058
#define GL_BGRA 0x80E1
#define GL_CLAMP_TO_EDGE 0x812F
#define GL_DEPTH_STENCIL_ATTACHMENT 0x821A
#define GL_UNSIGNED_SHORT_5_6_5 0x8363
#define GL_UNSIGNED_SHORT_1_5_5_5_REV 0x8366
#define GL_UNSIGNED_INT_8_8_8_8_REV 0x8367
#define GL_TEXTURE0 0x84C0
#define GL_DEPTH24_STENCIL8 0x88F0
#define GL_ARRAY_BUFFER 0x8892
#define GL_PIXEL_PACK_BUFFER 0x88EB
#define GL_PIXEL_UNPACK_BUFFER 0x88EC
#define GL_FRAGMENT_SHADER 0x8B30
#define GL_VERTEX_SHADER 0x8B31
#define GL_COMPILE_STATUS 0x8B81
#define GL_LINK_STATUS 0x8B82
#define GL_READ_FRAMEBUFFER 0x8CA8
#define GL_DRAW_FRAMEBUFFER 0x8CA9
#define GL_FRAMEBUFFER_COMPLETE 0x8CD5
#define GL_COLOR_ATTACHMENT0 0x8CE0
#define GL_DEPTH_ATTACHMENT 0x8D00
#define GL_FRAMEBUFFER 0x8D40
#define GL_RENDERBUFFER 0x8D41
#define GL_FRAMEBUFFER_SRGB 0x8DB9
#define GL_DEPTH_COMPONENT24 0x81A6

#define SC_GL_FUNCS(X)                                                                                         \
    X(void, glGenTextures, (GLsizei n, GLuint * t))                                                            \
    X(void, glDeleteTextures, (GLsizei n, const GLuint *t))                                                    \
    X(void, glBindTexture, (GLenum target, GLuint t))                                                          \
    X(void, glTexImage2D, (GLenum, GLint, GLint, GLsizei, GLsizei, GLint, GLenum, GLenum, const void *))        \
    X(void, glTexSubImage2D, (GLenum, GLint, GLint, GLint, GLsizei, GLsizei, GLenum, GLenum, const void *))     \
    X(void, glTexParameteri, (GLenum, GLenum, GLint))                                                          \
    X(void, glPixelStorei, (GLenum, GLint))                                                                    \
    X(void, glActiveTexture, (GLenum))                                                                         \
    X(void, glViewport, (GLint, GLint, GLsizei, GLsizei))                                                      \
    X(void, glClearColor, (GLfloat, GLfloat, GLfloat, GLfloat))                                                \
    X(void, glClear, (GLbitfield))                                                                             \
    X(void, glEnable, (GLenum))                                                                                \
    X(void, glDisable, (GLenum))                                                                               \
    X(void, glColorMask, (GLboolean, GLboolean, GLboolean, GLboolean))                                         \
    X(GLenum, glGetError, (void))                                                                              \
    X(const GLubyte *, glGetString, (GLenum))                                                                  \
    X(void, glReadPixels, (GLint, GLint, GLsizei, GLsizei, GLenum, GLenum, void *))                            \
    X(void, glFinish, (void))                                                                                  \
    X(void, glGenFramebuffers, (GLsizei, GLuint *))                                                            \
    X(void, glDeleteFramebuffers, (GLsizei, const GLuint *))                                                   \
    X(void, glBindFramebuffer, (GLenum, GLuint))                                                               \
    X(void, glFramebufferTexture2D, (GLenum, GLenum, GLenum, GLuint, GLint))                                   \
    X(void, glFramebufferRenderbuffer, (GLenum, GLenum, GLenum, GLuint))                                       \
    X(GLenum, glCheckFramebufferStatus, (GLenum))                                                              \
    X(void, glGenRenderbuffers, (GLsizei, GLuint *))                                                           \
    X(void, glDeleteRenderbuffers, (GLsizei, const GLuint *))                                                  \
    X(void, glBindRenderbuffer, (GLenum, GLuint))                                                              \
    X(void, glRenderbufferStorage, (GLenum, GLenum, GLsizei, GLsizei))                                         \
    X(GLuint, glCreateShader, (GLenum))                                                                        \
    X(void, glDeleteShader, (GLuint))                                                                          \
    X(void, glShaderSource, (GLuint, GLsizei, const GLchar *const *, const GLint *))                           \
    X(void, glCompileShader, (GLuint))                                                                         \
    X(void, glGetShaderiv, (GLuint, GLenum, GLint *))                                                          \
    X(void, glGetShaderInfoLog, (GLuint, GLsizei, GLsizei *, GLchar *))                                        \
    X(GLuint, glCreateProgram, (void))                                                                         \
    X(void, glDeleteProgram, (GLuint))                                                                         \
    X(void, glAttachShader, (GLuint, GLuint))                                                                  \
    X(void, glLinkProgram, (GLuint))                                                                           \
    X(void, glGetProgramiv, (GLuint, GLenum, GLint *))                                                         \
    X(void, glGetProgramInfoLog, (GLuint, GLsizei, GLsizei *, GLchar *))                                       \
    X(void, glUseProgram, (GLuint))                                                                            \
    X(GLint, glGetUniformLocation, (GLuint, const GLchar *))                                                   \
    X(void, glUniform1i, (GLint, GLint))                                                                       \
    X(void, glUniform4f, (GLint, GLfloat, GLfloat, GLfloat, GLfloat))                                          \
    X(void, glGenVertexArrays, (GLsizei, GLuint *))                                                            \
    X(void, glDeleteVertexArrays, (GLsizei, const GLuint *))                                                   \
    X(void, glBindVertexArray, (GLuint))                                                                       \
    X(void, glBindBuffer, (GLenum, GLuint))                                                                    \
    X(void, glBindSampler, (GLuint, GLuint))                                                                   \
    X(void, glDrawArrays, (GLenum, GLint, GLsizei))

namespace sc::gl {
#define SC_GL_DECLARE(ret, name, args) typedef ret(SC_GLAPI *PFN_##name) args; extern PFN_##name name;
SC_GL_FUNCS(SC_GL_DECLARE)
#undef SC_GL_DECLARE
/** Looks every function up with SDL_GL_GetProcAddress. False names the first missing one in `missing`. */
bool load(const char **missing);
} // namespace sc::gl
