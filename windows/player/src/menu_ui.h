/*
 * ShoalcadePlayer: the pause menu drawn with Dear ImGui over the game (part of Shoalcade).
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * The plugin's "Pocket" player look (D217; Pages/sc.css + play.css): the Game section as the dark toolbar rail down the
 * left (icon over label), every other section a tab of the settings panel on the right (dark icon tab rail, the game's
 * name and a close button at the top, then that tab's rows), and a scrim over the game between them (a click there
 * closes the menu). The tab shown follows the keyboard / controller highlight (L1 / R1 jump a section = a tab); a
 * click on a tab only changes what is shown. Atkinson Hyperlegible (OFL) at a size that grows with the window.
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
        bool menu_button = false;  // the ☰ button (menu closed, the mouse moved lately)
        bool menu_button_hover = false;
    };
    /**
     * Where the ☰ button goes in a window of w x h (window coordinates: x, y, w, h): the top-left corner, as the
     * browser player's toolbar rail is on the left. It grows with the window, like the menu's words.
     */
    static void menu_button_rect(float w, float h, float out[4]);
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
    std::string tab_, shown_tab_, last_focus_; // the panel tab shown, the one drawn last frame, the highlight then
};
} // namespace sc
