/*
 * ShoalcadePlayer: command line and start-up (part of Shoalcade).
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Normally the Shoalcade shell starts this program and sends a `start` message on stdin (see PROTOCOL.md).
 * For tests: --start FILE reads the start message from a file, --headless keeps the window hidden.
 */
#include <SDL3/SDL.h>
#include <SDL3/SDL_main.h>

#include <cstdlib>
#include <cstring>
#include <string>

#include "files.h"
#include "log.h"
#include "player.h"
#include "protocol.h"

using namespace sc;

static const char *kUsage =
    "ShoalcadePlayer " SC_PLAYER_VERSION " - the game player of Shoalcade (GPL-3.0-or-later)\n"
    "Usage: ShoalcadePlayer [options]\n"
    "  (no options)          wait for a {\"type\":\"start\",...} message on stdin\n"
    "  --start FILE          read the start message from a JSON file instead\n"
    "  --headless            no visible window, no sound device (SDL dummy sound driver)\n"
    "  --frames N            close after N shown frames\n"
    "  --fast                don't pace frames to the game's speed\n"
    "  --frame-hashes K      send a `frame` message every K shown frames (and at the end)\n"
    "  --at N:JSON           handle message JSON when shown frame N is reached (repeatable)\n"
    "  --test-hooks          allow debug.* messages (tests only)\n"
    "  --log FILE            also write the log to FILE\n"
    "  --verbose             debug-level log\n"
    "  --video-driver NAME   SDL video driver (e.g. offscreen, x11, windows)\n"
    "  --audio-driver NAME   SDL audio driver (e.g. dummy, wasapi)\n"
    "  --version\n";

int main(int argc, char **argv) {
    protocol_init(); // from here on, stdout = protocol only
    RunConfig cfg;
    std::string start_file, video_driver, audio_driver;
    for (int i = 1; i < argc; i++) {
        std::string a = argv[i];
        auto need = [&](const char *what) -> std::string {
            if (i + 1 >= argc) {
                fprintf(stderr, "%s needs %s\n%s", a.c_str(), what, kUsage);
                exit(2);
            }
            return argv[++i];
        };
        if (a == "--start") start_file = need("a file");
        else if (a == "--headless") cfg.headless = true;
        else if (a == "--frames") cfg.max_frames = std::strtoull(need("a number").c_str(), nullptr, 10);
        else if (a == "--fast") cfg.fast = true;
        else if (a == "--frame-hashes") cfg.hash_every = std::strtoull(need("a number").c_str(), nullptr, 10);
        else if (a == "--test-hooks") cfg.test_hooks = true;
        else if (a == "--log") log_open_file(need("a file"));
        else if (a == "--verbose") log_set_level(LogLevel::Debug);
        else if (a == "--video-driver") video_driver = need("a name");
        else if (a == "--audio-driver") audio_driver = need("a name");
        else if (a == "--at") {
            std::string v = need("N:JSON");
            size_t colon = v.find(':');
            json m = colon == std::string::npos ? json() : json::parse(v.substr(colon + 1), nullptr, false);
            if (colon == std::string::npos || m.is_discarded() || !m.is_object() || !m.contains("type")) {
                fprintf(stderr, "--at wants N:{\"type\":...}, got %s\n", v.c_str());
                return 2;
            }
            cfg.scheduled.emplace(std::strtoull(v.substr(0, colon).c_str(), nullptr, 10), m);
        } else if (a == "--version") {
            fprintf(stderr, "ShoalcadePlayer %s\n", SC_PLAYER_VERSION);
            send("version", {{"player", SC_PLAYER_VERSION}});
            return 0;
        } else if (a == "--help" || a == "-h") {
            fprintf(stderr, "%s", kUsage);
            return 0;
        } else {
            fprintf(stderr, "unknown option %s\n%s", a.c_str(), kUsage);
            return 2;
        }
    }

    if (!video_driver.empty()) SDL_SetHint(SDL_HINT_VIDEO_DRIVER, video_driver.c_str());
    if (!audio_driver.empty()) SDL_SetHint(SDL_HINT_AUDIO_DRIVER, audio_driver.c_str());
    else if (cfg.headless) SDL_SetHint(SDL_HINT_AUDIO_DRIVER, "dummy");
    // Controllers only act on the focused window; tests (hidden window, virtual controller) need them anyway.
    SDL_SetHint(SDL_HINT_JOYSTICK_ALLOW_BACKGROUND_EVENTS, cfg.test_hooks ? "1" : "0");
    SDL_SetAppMetadata("Shoalcade", SC_PLAYER_VERSION, "uk.benmarks.shoalcade.player");

    Inbox inbox;
    json start;
    if (!start_file.empty()) {
        std::vector<uint8_t> bytes;
        std::string err;
        if (!read_file(start_file, bytes, &err)) {
            send("error", {{"message", "can't read " + start_file + ": " + err}, {"fatal", true}});
            send("exited", {{"code", 2}});
            return 2;
        }
        start = json::parse(bytes.begin(), bytes.end(), nullptr, false);
        if (start.is_discarded() || !start.is_object()) {
            send("error", {{"message", start_file + " isn't a JSON object"}, {"fatal", true}});
            send("exited", {{"code", 2}});
            return 2;
        }
        cfg.start_from_stdin = false;
        inbox.start_stdin_reader(); // later messages may still come on stdin
    } else {
        inbox.start_stdin_reader();
        send("hello", {{"player", SC_PLAYER_VERSION}, {"protocol", 1}});
        for (;;) {
            auto m = inbox.wait(1000);
            if (m) {
                if ((*m)["type"] == "start") {
                    start = *m;
                    break;
                }
                send("error", {{"message", "waiting for start"}, {"fatal", false}});
            } else if (inbox.eof()) {
                send("exited", {{"code", 2}});
                return 2;
            }
        }
    }

    if (!SDL_Init(SDL_INIT_VIDEO | SDL_INIT_GAMEPAD | SDL_INIT_EVENTS)) {
        // No display at all (e.g. a headless test with no X server): the offscreen driver still gives us EGL.
        LOGW("SDL video failed (%s); trying the offscreen driver", SDL_GetError());
        SDL_SetHint(SDL_HINT_VIDEO_DRIVER, "offscreen");
        if (!SDL_Init(SDL_INIT_VIDEO | SDL_INIT_GAMEPAD | SDL_INIT_EVENTS)) {
            send("error", {{"message", std::string("couldn't start SDL: ") + SDL_GetError()}, {"fatal", true}});
            send("exited", {{"code", 1}});
            return 1;
        }
    }
    LOGI("ShoalcadePlayer %s, SDL %d.%d.%d, video driver %s", SC_PLAYER_VERSION, SDL_MAJOR_VERSION, SDL_MINOR_VERSION,
         SDL_MICRO_VERSION, SDL_GetCurrentVideoDriver() ? SDL_GetCurrentVideoDriver() : "?");
    int code;
    {
        Player player(cfg);
        code = player.run(start, inbox);
    }
    SDL_Quit();
    // The stdin reader thread may still be blocked in a read; leave without waiting for it.
    fflush(stderr);
    _Exit(code);
}
