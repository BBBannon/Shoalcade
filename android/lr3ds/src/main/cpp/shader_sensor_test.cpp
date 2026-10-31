#include <jni.h>
#include "video.h"
#include "libretro/libretro-common/include/libretro.h"

#include <EGL/egl.h>
#include <EGL/eglext.h>

namespace {

struct OffscreenContext {
    EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    EGLContext context = EGL_NO_CONTEXT;
    EGLSurface surface = EGL_NO_SURFACE;

    bool initialize() {
        if (!eglInitialize(display, nullptr, nullptr)) return false;
        const EGLint attributes[] = {
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
            EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
            EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8,
            EGL_NONE
        };
        EGLConfig config;
        EGLint count = 0;
        if (!eglChooseConfig(display, attributes, &config, 1, &count) || count != 1) return false;
        const EGLint contextAttributes[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
        context = eglCreateContext(display, config, EGL_NO_CONTEXT, contextAttributes);
        const EGLint surfaceAttributes[] = {EGL_WIDTH, 8, EGL_HEIGHT, 12, EGL_NONE};
        surface = eglCreatePbufferSurface(display, config, surfaceAttributes);
        return context != EGL_NO_CONTEXT && surface != EGL_NO_SURFACE &&
            eglMakeCurrent(display, surface, surface, context);
    }

    ~OffscreenContext() {
        eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (surface != EGL_NO_SURFACE) eglDestroySurface(display, surface);
        if (context != EGL_NO_CONTEXT) eglDestroyContext(display, context);
        eglTerminate(display);
    }
};

}

extern "C" JNIEXPORT jstring JNICALL
Java_com_swordfish_libretrodroid3ds_ShaderSensorTest_verifyShaderChain(
    JNIEnv* env, jobject, jboolean hardware
) {
    OffscreenContext context;
    if (!context.initialize()) return env->NewStringUTF("Cannot create offscreen GLES context");
    using namespace libretrodroid;
    constexpr int motionSensors = Video::SENSOR_ACCELEROMETER | Video::SENSOR_GYROSCOPE;
    const std::string vertex = R"(
        attribute vec4 VertexCoord;
        attribute vec2 TexCoord;
        uniform vec3 Gyroscope;
        varying vec2 uv;
        varying float gyroValid;
        void main() {
            gl_Position = VertexCoord;
            uv = TexCoord;
            gyroValid = all(equal(Gyroscope, vec3(0.25, -0.5, 0.75))) ? 1.0 : 0.0;
        }
    )";
    const std::string fragment = R"(
        precision highp float;
        varying vec2 uv;
        varying float gyroValid;
        uniform sampler2D Texture;
        uniform vec3 Accelerometer;
        uniform vec3 AccelerometerRest;
        void main() {
            bool valid = gyroValid > 0.9
                && all(equal(Accelerometer, vec3(2.0, 6.0, 8.0)))
                && all(equal(AccelerometerRest, vec3(1.0, 5.0, 7.0)));
            #ifdef FIRST_PASS
            valid = valid && texture2D(Texture, uv).r < 0.5;
            #else
            valid = valid && texture2D(Texture, uv).g > 0.9;
            #endif
            gl_FragColor = valid ? vec4(0.0, 1.0, 0.0, 1.0) : vec4(1.0, 0.0, 0.0, 1.0);
        }
    )";
    const std::string plainVertex = "attribute vec4 VertexCoord; attribute vec2 TexCoord; varying vec2 uv; void main() { gl_Position = VertexCoord; uv = TexCoord; }";
    const std::string inactiveFragment = R"(
        precision highp float;
        // AccelerometerRest
        #if 0
        uniform vec3 Accelerometer;
        #endif
        uniform vec3 Gyroscope;
        uniform sampler2D Texture;
        varying vec2 uv;
        void main() { gl_FragColor = vec4(vec3(1.0) - texture2D(Texture, uv).rgb, 1.0); }
    )";
    ShaderManager::Config active {
        ShaderManager::Type::SHADER_CUSTOM, {}, {
            {vertex, "#define FIRST_PASS\n" + fragment, false, 2.0f},
            {vertex, fragment, false, 1.0f}
        }
    };
    Video::RenderingOptions options {hardware == JNI_TRUE, 4, 6, false, false, 3, RETRO_PIXEL_FORMAT_XRGB8888};
    Video video(options, active, true, 0.0f, true, false, Rect(0, 0, 1, 1), {});
    video.updateScreenSize(8, 12);
    video.updateAspectRatio(2.0f / 3.0f);
    if (hardware) {
        video.bindHWContext();
        glBindFramebuffer(GL_FRAMEBUFFER, video.getCurrentFramebuffer());
        glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT);
        glFinish();
        video.bindMainContext();
        video.onNewFrame(RETRO_HW_FRAME_BUFFER_VALID, 4, 6, 0);
    } else {
        uint32_t pixels[24] {};
        video.onNewFrame(pixels, 4, 6, 4 * sizeof(uint32_t));
    }
    auto expect = [&](const char* checkpoint, int sensors, int red, int green, int blue = 0) -> jstring {
        int actualUsage = video.prepareShaderAndGetSensorUsage();
        video.renderFrame();
        unsigned char pixel[4] {};
        glReadPixels(4, 6, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
        GLenum error = glGetError();
        if (actualUsage == sensors && pixel[0] == red && pixel[1] == green && pixel[2] == blue && error == GL_NO_ERROR) return nullptr;
        std::string message = std::string(checkpoint) + ": sensors=" + std::to_string(actualUsage) +
            ", RGB=" + std::to_string(pixel[0]) + "," + std::to_string(pixel[1]) + "," + std::to_string(pixel[2]) +
            ", GL error=" + std::to_string(error);
        return env->NewStringUTF(message.c_str());
    };
    if (auto failure = expect("initial neutral state", motionSensors, 255, 0)) return failure;
    video.setShaderSensorState({0.25f, -0.5f, 0.75f, 2.0f, 6.0f, 8.0f, 1.0f, 5.0f, 7.0f});
    if (auto failure = expect("sensor update without new core frame", motionSensors, 0, 255)) return failure;
    video.setShaderSensorState({});
    if (auto failure = expect("sensor reset", motionSensors, 255, 0)) return failure;

    video.updateShaderType({ShaderManager::Type::SHADER_CUSTOM, {}, {{plainVertex, inactiveFragment, false, 1.0f}}});
    if (auto failure = expect("inactive declarations and shader replacement", 0, 255, 255, 255)) return failure;
    video.updateShaderType({ShaderManager::Type::SHADER_DEFAULT});
    if (auto failure = expect("ordinary shader", 0, 0, 0)) return failure;
    video.updateShaderType({ShaderManager::Type::SHADER_CUSTOM, {}, {{vertex,
        "precision highp float; varying vec2 uv; varying float gyroValid; uniform sampler2D Texture; void main() { gl_FragColor = texture2D(Texture, uv) + vec4(gyroValid); }", false, 1.0f}}});
    if (auto failure = expect("vertex-only usage", motionSensors, 0, 0)) return failure;
    video.updateShaderType({ShaderManager::Type::SHADER_CUSTOM, {}, {{plainVertex,
        "precision highp float; varying vec2 uv; uniform sampler2D Texture; uniform vec3 AccelerometerRest; void main() { gl_FragColor = vec4(texture2D(Texture, uv).rgb + AccelerometerRest, 1.0); }", false, 1.0f}}});
    if (auto failure = expect("fragment-only usage", Video::SENSOR_ACCELEROMETER, 0, 0)) return failure;
    video.updateShaderType({ShaderManager::Type::SHADER_CUSTOM, {}, {
        {plainVertex, inactiveFragment, false, 2.0f}, {vertex, fragment, false, 1.0f}
    }});
    if (auto failure = expect("later-pass usage", motionSensors, 255, 0)) return failure;
    video.setFilterMode(1);
    if (auto failure = expect("filter rebuild", motionSensors, 255, 0)) return failure;
    video.updateShaderType({ShaderManager::Type::SHADER_CUSTOM, {}, {
        {vertex, fragment, false, 1.0f}, {plainVertex, "invalid shader", false, 1.0f}
    }});
    if (auto failure = expect("failed chain fallback", 0, 0, 0)) return failure;
    video.updateShaderType(active);
    return expect("valid replacement after fallback", motionSensors, 255, 0);
}
