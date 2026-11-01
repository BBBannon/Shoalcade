/*
 * ShoalcadePlayer: the pause menu drawn with Dear ImGui over the game (part of Shoalcade).
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * A side drawer from the left, as the phone's GameDrawer.kt: dark panel, a scrim over the rest of the window (a click
 * there closes the menu), the game's name and a close button at the top, then every section with its title in blue
 * capitals and rows at least 48 px tall. Atkinson Hyperlegible (OFL) at a size that grows with the window.
 * Only draws: what the rows mean and what a pick does is menu.cpp and player.cpp. Never used when headless.
 */
#pragma once
#include <SDL3/SDL.h>

#include <map>
#include <string>
#include <vector>

#include "menu.h"

namespace sc {
class MenuUi {
public:
    bool init(SDL_Window *window, SDL_GLContext ctx, std::string &err);
    void shutdown();
    bool ready() const { return ready_; }
    /** Mouse and window events for ImGui (only called while the menu or a note is on screen). */
    void event(const SDL_Event &e);

    struct View {
        bool open = false;
        std::string title;
        const std::vector<menu::Section> *sections = nullptr;
        std::string focus;
        bool show_focus = false;   // the keyboard / controller highlight is drawn once it has been moved
        bool scroll_to_focus = false;
        bool scroll_to_top = false; // just opened: start at the top
        std::string toast;         // a short note at the bottom ("Saved to slot 2."), "" = none
        std::string corner;        // link play: the link's state in the top corner, as the phone shows it ("" = none)
    };
    struct Out {
        std::string pick;          // a row clicked with the mouse
        std::string slider;        // a slider dragged with the mouse ...
        int slider_value = -1;     // ... to this value
        bool close = false;        // the scrim or the close button was clicked
    };
    /** Draws one frame of the menu over the game (call inside Video::overlay). */
    Out draw(const View &v);
    /** Snapshot pictures changed on disk: load them again next time. */
    void forget_pictures();
    /** Where a row was drawn last time (window coordinates: x, y, w, h), for tests. False if it wasn't shown. */
    bool rect_of(const std::string &id, float out[4]) const;

private:
    struct Picture {
        unsigned tex = 0;
        int w = 0, h = 0;
    };
    const Picture *picture(const std::string &path);
    bool ready_ = false;
    void *regular_ = nullptr, *bold_ = nullptr; // ImFont*
    std::map<std::string, Picture> pictures_;
    struct Rect {
        float x, y, w, h;
    };
    std::map<std::string, Rect> rects_;
};
} // namespace sc
