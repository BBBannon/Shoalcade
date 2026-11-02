/*
 * ShoalcadePlayer: the pause menu drawn with Dear ImGui over the game (part of Shoalcade).
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#include "menu_ui.h"

#include <algorithm>
#include <cmath>

#include "gl.h"
#include "imgui.h"
#include "backends/imgui_impl_opengl3.h"
#include "backends/imgui_impl_sdl3.h"
#include "log.h"

#define STB_IMAGE_IMPLEMENTATION
#define STBI_ONLY_PNG
// Snapshot pictures can come from the server: a small PNG that claims to be 20000 x 20000 is refused before stb
// asks for gigabytes (security review 02/11 P-L3). Real game pictures are far smaller.
#define STBI_MAX_DIMENSIONS 4096
#define STBI_WINDOWS_UTF8 // file names are UTF-8 on Windows too
#if defined(__GNUC__)
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wunused-function"
#endif
#include "stb/stb_image.h"
#if defined(__GNUC__)
#pragma GCC diagnostic pop
#endif

// Atkinson Hyperlegible, embedded at build time (CMakeLists.txt, third_party/fonts).
extern "C" const unsigned char sc_font_regular[];
extern "C" const unsigned int sc_font_regular_size;
extern "C" const unsigned char sc_font_bold[];
extern "C" const unsigned int sc_font_bold_size;

namespace sc {
namespace {
// Pocket (D55 / D217): the plugin's tokens (Pages/sc.css :root) and its player toolbar / settings panel colours.
const ImU32 BG = IM_COL32(0xD7, 0xDD, 0xBE, 0xFF);          // --bg: toolbar text, selected segment text
const ImU32 INK = IM_COL32(0x22, 0x2A, 0x1C, 0xFF);         // --ink / --p-ink: text, borders, toolbar and tab rail
const ImU32 MUTED = IM_COL32(0x56, 0x61, 0x4A, 0xFF);       // --p-muted
const ImU32 ACCENT = IM_COL32(0x3B, 0x52, 0x30, 0xFF);      // --accent: toolbar hover, selected tab, slider fill
const ImU32 LED = IM_COL32(0xC8, 0x32, 0x4B, 0xFF);         // --led: a switch that is on
const ImU32 FOCUS = IM_COL32(0xC8, 0x32, 0x4B, 0xFF);       // --focus: the 3 px keyboard / controller ring
const ImU32 P_BG = IM_COL32(0xEC, 0xEF, 0xDB, 0xFF);        // --p-bg: the panel
const ImU32 P_LINE = IM_COL32(0xCB, 0xD2, 0xB0, 0xFF);      // --p-line: between groups, a switch that is off
const ImU32 P_WELL = IM_COL32(0xDC, 0xE2, 0xC3, 0xFF);      // --p-well: hover, an empty picture
const ImU32 P_RAISE = IM_COL32(0xF7, 0xF8, 0xEE, 0xFF);     // --p-raise: buttons
const ImU32 TAB_DIM = IM_COL32(0xA1, 0xA7, 0x8D, 0xFF);     // --bg at .7 over --ink (a tab not chosen), 6:1
const ImU32 WHITE = IM_COL32(0xFF, 0xFF, 0xFF, 0xFF);
const ImU32 SCRIM = IM_COL32(0x15, 0x1A, 0x12, 0x99);       // the player's #151A12, so the game shows dimmed
const ImU32 SHADE = IM_COL32(0x22, 0x2A, 0x1C, 0xD9);       // rgba(34, 42, 28, .85): the corner line's backing

ImVec2 operator+(ImVec2 a, ImVec2 b) { return ImVec2(a.x + b.x, a.y + b.y); }

std::string upper(const std::string &s) {
    std::string o = s;
    for (char &c : o) c = (char)std::toupper((unsigned char)c);
    return o;
}

float text_height(ImFont *f, float size, const std::string &t, float wrap) {
    if (t.empty()) return 0;
    return f->CalcTextSizeA(size, FLT_MAX, wrap, t.c_str()).y;
}

/** The widest single word of t (a label under an icon wraps between words, never inside one). */
float widest_word(ImFont *f, float size, const std::string &t) {
    float w = 0;
    size_t i = 0;
    while (i < t.size()) {
        size_t j = t.find(' ', i);
        if (j == std::string::npos) j = t.size();
        w = std::max(w, f->CalcTextSizeA(size, FLT_MAX, 0, t.c_str() + i, t.c_str() + j).x);
        i = j + 1;
    }
    return w;
}

/** Wrapped text with every line centred in [x, x + w] (labels under the toolbar and tab icons, button words). */
void centred_text(ImDrawList *dl, ImFont *f, float size, ImVec2 p, float w, ImU32 col, const std::string &t) {
    const char *s = t.c_str(), *end = s + t.size();
    while (s < end) {
        const char *e = f->CalcWordWrapPosition(size, s, end, w);
        if (e == s) e = s + 1;
        const char *te = e;
        while (te > s && (te[-1] == ' ' || te[-1] == '\n')) te--;
        float lw = f->CalcTextSizeA(size, FLT_MAX, 0, s, te).x;
        dl->AddText(f, size, ImVec2(std::round(p.x + (w - lw) / 2), p.y), col, s, te);
        p.y += size; // ImGui's line height is the font size
        s = e;
        while (s < end && (*s == ' ' || *s == '\n')) s++;
    }
}

/**
 * The line icons of the toolbar and the tabs (sc.css .ico: 1.8 px strokes on 20 px), drawn with lines because the
 * player has no icon font. c = centre, d = size, t = stroke. Unknown ids get a plain dot.
 */
void icon(ImDrawList *dl, const std::string &id, ImVec2 c, float d, ImU32 col, float t) {
    const float r = d / 2;
    auto P = [&](float x, float y) { return ImVec2(c.x + x * r, c.y + y * r); };
    auto path = [&](std::initializer_list<ImVec2> pts) {
        for (const ImVec2 &q : pts) dl->PathLineTo(q);
        dl->PathStroke(col, ImDrawFlags_None, t);
    };
    if (id == menu::ID_RESUME) {
        dl->AddTriangleFilled(P(-0.55f, -0.8f), P(0.8f, 0), P(-0.55f, 0.8f), col);
    } else if (id == menu::ID_SCREENSHOT) {
        dl->AddRect(P(-0.9f, -0.5f), P(0.9f, 0.75f), col, r * 0.15f, 0, t);
        dl->AddCircle(P(0, 0.12f), r * 0.36f, col, 0, t);
        path({P(-0.4f, -0.5f), P(-0.22f, -0.82f), P(0.22f, -0.82f), P(0.4f, -0.5f)});
    } else if (id == menu::ID_RESTART) {
        // Going round again: most of a circle, an arrow head at its end.
        dl->PathArcTo(c, r * 0.72f, -0.6f, 4.3f);
        dl->PathStroke(col, ImDrawFlags_None, t);
        ImVec2 e(c.x + std::cos(-0.6f) * r * 0.72f, c.y + std::sin(-0.6f) * r * 0.72f);
        dl->AddTriangleFilled(ImVec2(e.x - r * 0.42f, e.y - r * 0.05f), ImVec2(e.x + r * 0.25f, e.y - r * 0.45f),
                              ImVec2(e.x + r * 0.18f, e.y + r * 0.3f), col);
    } else if (id == menu::ID_CLOSE) {
        // Leaving the game: a door frame and an arrow out of it.
        path({P(0.2f, -0.85f), P(-0.8f, -0.85f), P(-0.8f, 0.85f), P(0.2f, 0.85f)});
        path({P(-0.3f, 0), P(0.9f, 0)});
        path({P(0.5f, -0.4f), P(0.9f, 0), P(0.5f, 0.4f)});
    } else if (id == menu::ID_CLOSE_UPLOAD) {
        path({P(-0.85f, 0.3f), P(-0.85f, 0.85f), P(0.85f, 0.85f), P(0.85f, 0.3f)});
        path({P(0, 0.45f), P(0, -0.85f)});
        path({P(-0.42f, -0.43f), P(0, -0.85f), P(0.42f, -0.43f)});
    } else if (id == "snapshots") {
        dl->AddRect(P(-0.9f, -0.45f), P(0.55f, 0.85f), col, r * 0.12f, 0, t);
        path({P(-0.55f, -0.45f), P(-0.55f, -0.85f), P(0.9f, -0.85f), P(0.9f, 0.45f), P(0.55f, 0.45f)});
    } else if (id == "speed") {
        dl->AddTriangleFilled(P(-0.9f, -0.65f), P(0, 0), P(-0.9f, 0.65f), col);
        dl->AddTriangleFilled(P(0, -0.65f), P(0.9f, 0), P(0, 0.65f), col);
    } else if (id == "cheats") {
        // A sparkle: long and short rays.
        for (int k = 0; k < 8; k++) {
            float a = k * 3.14159265f / 4, len = k % 2 ? 0.55f : 0.9f;
            dl->AddLine(P(std::cos(a) * 0.2f, std::sin(a) * 0.2f), P(std::cos(a) * len, std::sin(a) * len), col, t);
        }
    } else if (id == "sound") {
        dl->AddRectFilled(P(-0.85f, -0.3f), P(-0.4f, 0.3f), col);
        dl->AddQuadFilled(P(-0.4f, -0.3f), P(0.1f, -0.75f), P(0.1f, 0.75f), P(-0.4f, 0.3f), col);
        dl->PathArcTo(P(0.15f, 0), r * 0.4f, -0.9f, 0.9f);
        dl->PathStroke(col, ImDrawFlags_None, t);
        dl->PathArcTo(P(0.15f, 0), r * 0.75f, -0.9f, 0.9f);
        dl->PathStroke(col, ImDrawFlags_None, t);
    } else if (id == "display") {
        dl->AddRect(P(-0.9f, -0.75f), P(0.9f, 0.45f), col, r * 0.1f, 0, t);
        path({P(0, 0.45f), P(0, 0.8f)});
        path({P(-0.45f, 0.82f), P(0.45f, 0.82f)});
    } else if (id == "controls") {
        dl->AddRect(P(-0.95f, -0.5f), P(0.95f, 0.6f), col, r * 0.4f, 0, t);
        path({P(-0.62f, 0.05f), P(-0.18f, 0.05f)});
        path({P(-0.4f, -0.17f), P(-0.4f, 0.27f)});
        dl->AddCircleFilled(P(0.32f, 0.15f), r * 0.11f, col);
        dl->AddCircleFilled(P(0.6f, -0.08f), r * 0.11f, col);
    } else {
        dl->AddCircleFilled(c, r * 0.3f, col);
    }
}
} // namespace

bool MenuUi::init(SDL_Window *window, SDL_GLContext ctx, std::string &err) {
    IMGUI_CHECKVERSION();
    ImGui::CreateContext();
    ImGuiIO &io = ImGui::GetIO();
    io.IniFilename = nullptr; // nothing written next to the game
    io.LogFilename = nullptr;
    ImFontConfig cfg;
    cfg.FontDataOwnedByAtlas = false;
    regular_ = io.Fonts->AddFontFromMemoryTTF((void *)sc_font_regular, (int)sc_font_regular_size, 22.0f, &cfg);
    bold_ = io.Fonts->AddFontFromMemoryTTF((void *)sc_font_bold, (int)sc_font_bold_size, 22.0f, &cfg);
    if (!regular_ || !bold_) {
        err = "the menu font didn't load";
        ImGui::DestroyContext();
        return false;
    }
    io.FontDefault = (ImFont *)regular_;
    ImGuiStyle &st = ImGui::GetStyle();
    // Pocket: square windows, the panel's colours, 3 px rounding (--r-s) on the scrollbar, ink-bordered tooltips.
    st.WindowRounding = 0;
    st.WindowBorderSize = 0;
    st.ScrollbarRounding = 3;
    st.PopupRounding = 4;
    st.PopupBorderSize = 2;
    auto c4 = [](ImU32 c) { return ImGui::ColorConvertU32ToFloat4(c); };
    st.Colors[ImGuiCol_WindowBg] = c4(P_BG);
    st.Colors[ImGuiCol_ChildBg] = ImVec4(0, 0, 0, 0);
    st.Colors[ImGuiCol_PopupBg] = c4(P_BG);
    st.Colors[ImGuiCol_Border] = c4(INK);
    st.Colors[ImGuiCol_ScrollbarBg] = c4(P_BG);
    st.Colors[ImGuiCol_ScrollbarGrab] = c4(MUTED);
    st.Colors[ImGuiCol_ScrollbarGrabHovered] = c4(ACCENT);
    st.Colors[ImGuiCol_ScrollbarGrabActive] = c4(INK);
    st.Colors[ImGuiCol_Text] = c4(INK);
    if (!ImGui_ImplSDL3_InitForOpenGL(window, ctx)) {
        err = "the menu couldn't start (SDL)";
        ImGui::DestroyContext();
        return false;
    }
    if (!ImGui_ImplOpenGL3_Init("#version 330")) {
        err = "the menu couldn't start (OpenGL)";
        ImGui_ImplSDL3_Shutdown();
        ImGui::DestroyContext();
        return false;
    }
    ready_ = true;
    return true;
}

void MenuUi::shutdown() {
    if (!ready_) return;
    forget_pictures();
    ImGui_ImplOpenGL3_Shutdown();
    ImGui_ImplSDL3_Shutdown();
    ImGui::DestroyContext();
    ready_ = false;
}

void MenuUi::event(const SDL_Event &e) {
    if (ready_) ImGui_ImplSDL3_ProcessEvent(&e);
}

void MenuUi::menu_button_rect(float w, float h, float out[4]) {
    (void)w;
    const float fs = std::clamp(h / 22.0f, 18.0f, 34.0f); // the menu's text size (draw)
    const float size = std::round(fs * 2.2f), margin = std::round(fs * 0.5f);
    out[0] = margin, out[1] = margin, out[2] = size, out[3] = size;
}

bool MenuUi::rect_of(const std::string &id, float out[4]) const {
    auto it = rects_.find(id);
    if (it == rects_.end()) return false;
    out[0] = it->second.x, out[1] = it->second.y, out[2] = it->second.w, out[3] = it->second.h;
    return true;
}

void MenuUi::forget_pictures() {
    for (auto &kv : pictures_)
        if (kv.second.tex) gl::glDeleteTextures(1, &kv.second.tex);
    pictures_.clear();
}

const MenuUi::Picture *MenuUi::picture(const std::string &path) {
    if (path.empty()) return nullptr;
    auto it = pictures_.find(path);
    if (it != pictures_.end()) return it->second.tex ? &it->second : nullptr;
    Picture p;
    int n = 0;
    unsigned char *px = stbi_load(path.c_str(), &p.w, &p.h, &n, 4);
    if (px) {
        gl::glGenTextures(1, &p.tex);
        gl::glBindTexture(GL_TEXTURE_2D, p.tex);
        gl::glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
        gl::glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
        gl::glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, p.w, p.h, 0, GL_RGBA, GL_UNSIGNED_BYTE, px);
        gl::glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        gl::glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        gl::glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        gl::glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        stbi_image_free(px);
    } else {
        LOGW("menu: couldn't read the picture %s", path.c_str());
    }
    auto &slot = pictures_[path] = p;
    return slot.tex ? &slot : nullptr;
}

MenuUi::Out MenuUi::draw(const View &v) {
    Out out;
    if (!ready_) return out;
    ImGui_ImplOpenGL3_NewFrame();
    ImGui_ImplSDL3_NewFrame();
    ImGui::NewFrame();
    ImGuiIO &io = ImGui::GetIO();
    const float W = io.DisplaySize.x, H = io.DisplaySize.y;
    // Text size grows with the window: about 22 px in a 480-high window, never under 18 or over 34.
    const float fs = std::clamp(H / 22.0f, 18.0f, 34.0f);
    const float small = std::round(fs * 0.85f);
    // One CSS pixel of play.css / sc.css at the usual text size (22 px here: the page's 15 px body text is too small
    // to read from across a room), so every box of the Pocket design grows with the words.
    const float s = fs / 22.0f;
    auto px = [s](float css) { return std::max(1.0f, std::round(css * s)); };
    const float lab = std::max(12.0f, std::round(fs * 0.64f)); // the words under the toolbar and tab icons
    const float line = px(1), border = std::max(2.0f, px(2)), stroke = std::max(1.5f, fs * 0.085f);
    const float r_s = px(3), r_m = px(4); // --r-s, --r-m
    ImFont *reg = (ImFont *)regular_, *bold = (ImFont *)bold_;
    // sc.css :focus-visible: a 3 px --focus ring 2 px outside whatever has the keyboard / controller highlight.
    auto ring = [&](ImDrawList *d, ImVec2 a, ImVec2 b, float round) {
        float t = std::max(3.0f, px(3)), o = px(2) + t / 2;
        d->AddRect(ImVec2(a.x - o, a.y - o), ImVec2(b.x + o, b.y + o), FOCUS, round + o, 0, t);
    };
    const ImGuiWindowFlags fixed = ImGuiWindowFlags_NoDecoration | ImGuiWindowFlags_NoMove |
                                   ImGuiWindowFlags_NoSavedSettings | ImGuiWindowFlags_NoResize |
                                   ImGuiWindowFlags_NoBringToFrontOnFocus;

    rects_.clear();
    if (v.open && v.sections) {
        ImDrawList *bg = ImGui::GetBackgroundDrawList();
        bg->AddRectFilled(ImVec2(0, 0), ImVec2(W, H), SCRIM);
        // The Game section is the toolbar; every other section is a tab of the settings panel.
        const menu::Section *game = nullptr;
        std::vector<const menu::Section *> tabs;
        std::string focus_sec;
        for (const auto &sec : *v.sections) {
            if (sec.id == "game" && !game) game = &sec;
            else tabs.push_back(&sec);
            for (const auto &it : sec.items)
                if (it.id == v.focus) focus_sec = sec.id;
        }
        // The tab shown follows the highlight when it moves into a tab (Up / Down past a tab's end, L1 / R1).
        const bool focus_in_tab = !focus_sec.empty() && (!game || focus_sec != game->id);
        if (v.scroll_to_top) tab_.clear(); // just opened
        if (focus_in_tab && (v.focus != last_focus_ || tab_.empty())) tab_ = focus_sec;
        last_focus_ = v.focus;
        const menu::Section *cur = nullptr;
        for (const auto *t : tabs)
            if (t->id == tab_) cur = t;
        if (!cur && !tabs.empty()) cur = tabs[0];
        tab_ = cur ? cur->id : "";
        const bool tab_changed = tab_ != shown_tab_;
        shown_tab_ = tab_;
        std::string clicked_tab;

        ImGui::PushStyleVar(ImGuiStyleVar_WindowPadding, ImVec2(0, 0));
        ImGui::PushStyleVar(ImGuiStyleVar_ItemSpacing, ImVec2(0, 0));
        ImGui::PushStyleVar(ImGuiStyleVar_ScrollbarSize, px(12));
        ImGui::PushFont(reg, fs);

        // ---- toolbar rail (sc.css .toolbar): ink, a 2 px accent edge, 56 px icon-over-label buttons ----
        // Atkinson Hyperlegible is wider than the page's condensed font, so the rail widens to fit its longest word.
        float btn_w = px(56);
        if (game)
            for (const auto &it : game->items) btn_w = std::max(btn_w, widest_word(bold, lab, it.label) + px(8) + 1);
        const float rail_w = btn_w + px(8);
        ImGui::SetNextWindowPos(ImVec2(0, 0));
        ImGui::SetNextWindowSize(ImVec2(rail_w, H));
        ImGui::PushStyleColor(ImGuiCol_WindowBg, INK);
        ImGui::Begin("##rail", nullptr, fixed | ImGuiWindowFlags_NoScrollWithMouse);
        ImGui::PopStyleColor();
        ImDrawList *dl = ImGui::GetWindowDrawList();
        dl->AddRectFilled(ImVec2(rail_w - border, 0), ImVec2(rail_w, H), ACCENT);
        if (game) {
            const float inner = rail_w - border;
            float y = px(12);
            std::string t = upper(game->title);
            centred_text(dl, bold, lab, ImVec2(0, y), inner, TAB_DIM, t);
            y += text_height(bold, lab, t, inner) + px(8);
            for (const auto &it : game->items) {
                if (!it.selectable()) continue;
                ImGui::PushID(it.id.c_str());
                const float ic = px(22), wrap = btn_w - px(4);
                const float h = px(7) + ic + px(3) + text_height(bold, lab, it.label, wrap) + px(7);
                ImGui::SetCursorScreenPos(ImVec2(std::round((inner - btn_w) / 2), y));
                bool clicked = ImGui::InvisibleButton("##row", ImVec2(btn_w, h));
                bool focused = v.show_focus && v.focus == it.id;
                ImVec2 a = ImGui::GetItemRectMin(), b = ImGui::GetItemRectMax();
                if (focused || ImGui::IsItemHovered()) dl->AddRectFilled(a, b, ACCENT, r_m);
                icon(dl, it.id, ImVec2((a.x + b.x) / 2, a.y + px(7) + ic / 2), ic, BG, stroke);
                centred_text(dl, bold, lab, ImVec2(a.x + px(2), a.y + px(7) + ic + px(3)), wrap, BG, it.label);
                if (focused) ring(dl, a, b, r_m);
                if (!it.hint.empty()) ImGui::SetItemTooltip("%s", it.hint.c_str());
                if (b.y <= H) rects_[it.id] = Rect{a.x, a.y, b.x - a.x, b.y - a.y};
                if (clicked && it.kind != menu::Kind::Info) out.pick = it.id;
                ImGui::PopID();
                y = b.y + px(2);
            }
        }
        ImGui::End();

        // ---- settings panel (sc.css .panel): right side, 440 px, a 72 px ink tab rail, the head, then the tab ----
        float tab_w = px(72);
        for (const auto *t : tabs) tab_w = std::max(tab_w, widest_word(bold, lab, t->title) + px(16) + 1);
        // In a narrow window (the DS stacked) some of the game stays showing, to click on to close the menu.
        const float panel_w = std::max(std::min(px(440), W * 0.85f - rail_w), std::min(W - rail_w, tab_w + fs * 8));
        const float x0 = W - panel_w;
        ImGui::SetNextWindowPos(ImVec2(x0, 0));
        ImGui::SetNextWindowSize(ImVec2(panel_w, H));
        ImGui::Begin("##panel", nullptr, fixed | ImGuiWindowFlags_NoScrollWithMouse);
        dl = ImGui::GetWindowDrawList();
        dl->AddRectFilled(ImVec2(x0, 0), ImVec2(x0 + tab_w, H), INK); // the tab rail (the panel's 2 px ink edge too)
        {
            // .panel-tabs button: icon over its name; the chosen tab --accent with white words.
            float y = px(10);
            for (const auto *t : tabs) {
                ImGui::PushID(t->id.c_str());
                const float ic = px(22), wrap = tab_w - px(16);
                const float h = px(10) + ic + px(3) + text_height(bold, lab, t->title, wrap) + px(10);
                ImGui::SetCursorScreenPos(ImVec2(x0 + px(6), y));
                if (ImGui::InvisibleButton("##tab", ImVec2(tab_w - px(12), h))) clicked_tab = t->id;
                ImVec2 a = ImGui::GetItemRectMin(), b = ImGui::GetItemRectMax();
                const bool sel = t == cur;
                if (sel) dl->AddRectFilled(a, b, ACCENT, r_m);
                ImU32 col = sel ? WHITE : ImGui::IsItemHovered() ? BG : TAB_DIM;
                icon(dl, t->id, ImVec2((a.x + b.x) / 2, a.y + px(10) + ic / 2), ic, col, stroke);
                centred_text(dl, bold, lab, ImVec2(a.x + px(2), a.y + px(10) + ic + px(3)), wrap, col, t->title);
                ImGui::PopID();
                y = b.y + px(4);
            }
        }
        const float cx = x0 + tab_w, cw = panel_w - tab_w;
        float head_h;
        {
            // .panel-head: the game's name (h2, up to two lines) over the tab's name, the close button (.x), a 2 px
            // ink line under it all.
            const float btn = std::max(px(40), std::round(fs * 1.8f));
            const float hx = px(16), hy = px(14);
            const float tw = std::max(1.0f, cw - 2 * hx - btn - px(12));
            const float ts = std::round(fs * 1.15f);
            std::string title = v.title.empty() ? menu::TITLE : v.title;
            std::string sub = cur ? cur->title : "";
            float th = std::min(text_height(bold, ts, title, tw), ts * 2), sh = text_height(reg, small, sub, tw);
            float body = std::max(btn, th + px(2) + sh);
            head_h = hy * 2 + body;
            float ty = hy + (body - th - px(2) - sh) / 2;
            dl->PushClipRect(ImVec2(cx + hx, ty), ImVec2(cx + hx + tw, ty + th), true);
            dl->AddText(bold, ts, ImVec2(cx + hx, ty), INK, title.c_str(), nullptr, tw);
            dl->PopClipRect();
            dl->AddText(reg, small, ImVec2(cx + hx, ty + th + px(2)), MUTED, sub.c_str(), nullptr, tw);
            ImGui::SetCursorScreenPos(ImVec2(cx + cw - hx - btn, hy + (body - btn) / 2));
            if (ImGui::InvisibleButton("##close", ImVec2(btn, btn))) out.close = true;
            ImVec2 a = ImGui::GetItemRectMin(), b = ImGui::GetItemRectMax();
            ImVec2 c((a.x + b.x) / 2, (a.y + b.y) / 2);
            if (ImGui::IsItemHovered()) dl->AddCircleFilled(c, btn / 2, P_WELL);
            float r = fs * 0.38f, t = std::max(2.0f, fs * 0.1f);
            dl->AddLine(ImVec2(c.x - r, c.y - r), ImVec2(c.x + r, c.y + r), INK, t);
            dl->AddLine(ImVec2(c.x - r, c.y + r), ImVec2(c.x + r, c.y - r), INK, t);
            ImGui::SetItemTooltip("%s", menu::CLOSE_MENU);
            dl->AddRectFilled(ImVec2(cx, head_h - border), ImVec2(W, head_h), INK);
        }

        // ---- the tab's rows (.panel-body, 16 px sides) ----
        ImGui::SetCursorScreenPos(ImVec2(cx, head_h));
        ImGui::PushStyleVar(ImGuiStyleVar_WindowPadding, ImVec2(px(16), px(4)));
        ImGui::BeginChild("##body", ImVec2(cw, H - head_h), ImGuiChildFlags_AlwaysUseWindowPadding,
                          ImGuiWindowFlags_NoSavedSettings);
        ImGui::PopStyleVar();
        if (v.scroll_to_top || tab_changed) ImGui::SetScrollY(0);
        dl = ImGui::GetWindowDrawList();
        auto shown = [&](const std::string &id, ImVec2 a, ImVec2 b) {
            // Only rows inside the panel's visible part count as "shown" (menu.state reports where).
            ImVec2 c0 = ImGui::GetWindowPos(), c1 = c0 + ImGui::GetWindowSize();
            if (a.y >= c0.y && b.y <= c1.y) rects_[id] = Rect{a.x, a.y, b.x - a.x, b.y - a.y};
        };
        auto separator = [&]() { // .ps border-bottom: 1 px --p-line
            ImVec2 p = ImGui::GetCursorScreenPos();
            dl->AddRectFilled(p, ImVec2(p.x + ImGui::GetContentRegionAvail().x, p.y + line), P_LINE);
            ImGui::Dummy(ImVec2(0, line));
        };
        // .btn: 2 px ink border, --p-raise, bold words (the hint under them), a hard 3 px ink shadow on hover.
        auto buttons = [&](const std::vector<const menu::Item *> &row, float width) {
            const size_t n = row.size();
            const float gap = px(8), pad = px(8);
            const float bw = (width - gap * (n - 1)) / n, tw = std::max(1.0f, bw - 2 * px(14));
            float h = px(44);
            for (const auto *it : row)
                h = std::max(h, 2 * pad + text_height(bold, fs, it->label, tw) +
                                    (it->hint.empty() ? 0 : px(2) + text_height(reg, small, it->hint, tw)));
            ImVec2 p = ImGui::GetCursorScreenPos();
            for (size_t k = 0; k < n; k++) {
                const menu::Item &it = *row[k];
                ImGui::PushID(it.id.c_str());
                ImGui::SetCursorScreenPos(ImVec2(std::round(p.x + k * (bw + gap)), p.y));
                bool clicked = ImGui::InvisibleButton("##row", ImVec2(std::round(bw), h));
                bool focused = v.show_focus && v.focus == it.id;
                ImVec2 a = ImGui::GetItemRectMin(), b = ImGui::GetItemRectMax();
                if (ImGui::IsItemHovered() && !ImGui::IsItemActive())
                    dl->AddRectFilled(a + ImVec2(px(3), px(3)), b + ImVec2(px(3), px(3)), INK, r_m);
                dl->AddRectFilled(a, b, P_RAISE, r_m);
                dl->AddRect(a, b, INK, r_m, 0, border);
                float lh = text_height(bold, fs, it.label, tw);
                float hh = it.hint.empty() ? 0 : px(2) + text_height(reg, small, it.hint, tw);
                float ty = a.y + (h - lh - hh) / 2;
                centred_text(dl, bold, fs, ImVec2(a.x + px(14), ty), tw, INK, it.label);
                if (!it.hint.empty()) centred_text(dl, reg, small, ImVec2(a.x + px(14), ty + lh + px(2)), tw, MUTED, it.hint);
                if (focused) ring(dl, a, b, r_m);
                shown(it.id, a, b);
                if (clicked) out.pick = it.id;
                if (focused && v.scroll_to_focus) ImGui::SetScrollHereY(0.35f);
                ImGui::PopID();
            }
            ImGui::SetCursorScreenPos(ImVec2(p.x, p.y + h));
            ImGui::Dummy(ImVec2(0, 0));
        };
        // .seg: one group of choices in a 2 px ink frame, the chosen one ink with light words. Side by side when every
        // name fits on one line, otherwise one under another (the 3DS layouts' longer names).
        auto segments = [&](const std::vector<const menu::Item *> &g, float width) {
            const size_t n = g.size();
            float widest = 0;
            for (const auto *it : g) widest = std::max(widest, bold->CalcTextSizeA(fs, FLT_MAX, 0, it->label.c_str()).x);
            const bool across = widest + px(14) <= width / n;
            const float seg_w = across ? std::floor(width / n) : width, tw = seg_w - px(12);
            const float across_h = std::max(px(38), fs + px(16));
            ImVec2 p = ImGui::GetCursorScreenPos();
            std::vector<ImVec2> r0(n), r1(n);
            float y = p.y;
            for (size_t k = 0; k < n; k++) {
                const menu::Item &it = *g[k];
                float th = text_height(bold, fs, it.label, tw);
                float h = across ? across_h : std::max(px(38), th + px(16));
                ImVec2 a = across ? ImVec2(p.x + k * seg_w, p.y) : ImVec2(p.x, y);
                ImGui::PushID(it.id.c_str());
                ImGui::SetCursorScreenPos(a);
                bool clicked = ImGui::InvisibleButton("##row", ImVec2(k + 1 == n && across ? width - k * seg_w : seg_w, h));
                ImVec2 b = ImGui::GetItemRectMax();
                ImDrawFlags corners = ImDrawFlags_RoundCornersNone;
                if (n == 1) corners = ImDrawFlags_RoundCornersAll;
                else if (k == 0) corners = across ? ImDrawFlags_RoundCornersLeft : ImDrawFlags_RoundCornersTop;
                else if (k + 1 == n) corners = across ? ImDrawFlags_RoundCornersRight : ImDrawFlags_RoundCornersBottom;
                if (it.on) dl->AddRectFilled(a, b, INK, r_m, corners);
                else if (ImGui::IsItemHovered()) dl->AddRectFilled(a, b, P_WELL, r_m, corners);
                centred_text(dl, bold, fs, ImVec2(a.x + px(6), a.y + (h - th) / 2), tw, it.on ? BG : MUTED, it.label);
                r0[k] = a, r1[k] = b;
                shown(it.id, a, b);
                if (clicked) out.pick = it.id;
                if (v.show_focus && v.focus == it.id && v.scroll_to_focus) ImGui::SetScrollHereY(0.35f);
                ImGui::PopID();
                y = b.y;
            }
            ImVec2 end(p.x + width, across ? p.y + across_h : y);
            for (size_t k = 1; k < n; k++) {
                if (across) dl->AddRectFilled(ImVec2(r0[k].x - border / 2, p.y), ImVec2(r0[k].x + border / 2, end.y), INK);
                else dl->AddRectFilled(ImVec2(p.x, r0[k].y - border / 2), ImVec2(end.x, r0[k].y + border / 2), INK);
            }
            dl->AddRect(p, end, INK, r_m, 0, border);
            for (size_t k = 0; k < n; k++)
                if (v.show_focus && v.focus == g[k]->id) ring(dl, r0[k], r1[k], r_m);
            ImGui::SetCursorScreenPos(ImVec2(p.x, end.y));
            ImGui::Dummy(ImVec2(0, 0));
        };
        // .tog (the switch on the right), the volume slider, or a Controls line (.binds): the name over its hint.
        auto row = [&](const menu::Item &it, float width) {
            const float pad = px(8);
            std::string val = it.kind == menu::Kind::Slider ? std::to_string(it.value) + "%" : "";
            float right = 0;
            if (it.kind == menu::Kind::Toggle) right = px(40) + px(12);
            if (!val.empty()) right = bold->CalcTextSizeA(fs, FLT_MAX, 0, val.c_str()).x + px(12);
            const float tw = std::max(1.0f, width - 2 * pad - right);
            float lh = text_height(reg, fs, it.label, tw);
            float hh = it.hint.empty() ? 0 : px(2) + text_height(reg, small, it.hint, tw);
            float bar = it.kind == menu::Kind::Slider ? px(28) : 0;
            float h = std::max(px(44), lh + hh + bar + 2 * pad);
            bool clicked = ImGui::InvisibleButton("##row", ImVec2(width, h));
            bool focused = v.show_focus && v.focus == it.id;
            ImVec2 a = ImGui::GetItemRectMin(), b = ImGui::GetItemRectMax();
            if (it.kind == menu::Kind::Bind && it.on) {
                // Waiting for the new key or button: the row stands out (--p-well, a --led edge), not by colour alone:
                // its words say "Press a key...".
                dl->AddRectFilled(a, b, P_WELL, r_m);
                dl->AddRectFilled(a, ImVec2(a.x + px(4), b.y), LED, r_m, ImDrawFlags_RoundCornersLeft);
            } else if (it.kind == menu::Kind::Info || it.kind == menu::Kind::Bind) {
                if (it.kind == menu::Kind::Bind && ImGui::IsItemHovered()) dl->AddRectFilled(a, b, P_WELL, r_m);
                dl->AddRectFilled(ImVec2(a.x, b.y - line), b, P_LINE);
            } else if (ImGui::IsItemHovered()) dl->AddRectFilled(a, b, P_WELL, r_m);
            float ty = a.y + (h - lh - hh - bar) / 2, tx = a.x + pad;
            dl->AddText(reg, fs, ImVec2(tx, ty), INK, it.label.c_str(), nullptr, tw);
            if (!it.hint.empty())
                dl->AddText(reg, small, ImVec2(tx, ty + lh + px(2)), MUTED, it.hint.c_str(), nullptr, tw);
            float cy = (a.y + b.y) / 2;
            if (it.kind == menu::Kind::Toggle) {
                // .tog-ui: 40 x 24, --p-line when off (with a muted edge so it shows on the panel), --led when on; a
                // white knob that moves right, with a tick in it so "on" doesn't rely on colour alone.
                float sw = px(40), sh = px(24), kr = px(9);
                ImVec2 s0(b.x - pad - sw, cy - sh / 2), s1(b.x - pad, cy + sh / 2);
                dl->AddRectFilled(s0, s1, it.on ? LED : P_LINE, sh / 2);
                if (!it.on) dl->AddRect(s0, s1, MUTED, sh / 2, 0, line);
                ImVec2 k(it.on ? s1.x - px(3) - kr : s0.x + px(3) + kr, cy);
                dl->AddCircleFilled(k + ImVec2(0, line), kr, IM_COL32(0, 0, 0, 0x4D));
                dl->AddCircleFilled(k, kr, WHITE);
                if (it.on) {
                    float r = kr * 0.55f, t = std::max(1.5f, fs * 0.08f);
                    dl->AddLine(ImVec2(k.x - r, k.y), ImVec2(k.x - r * 0.25f, k.y + r * 0.7f), LED, t);
                    dl->AddLine(ImVec2(k.x - r * 0.25f, k.y + r * 0.7f), ImVec2(k.x + r, k.y - r * 0.6f), LED, t);
                }
            } else if (it.kind == menu::Kind::Slider) {
                // The value on the right, then the range: a --p-line track, --accent up to the value, an ink-ringed knob.
                ImVec2 vs = bold->CalcTextSizeA(fs, FLT_MAX, 0, val.c_str());
                dl->AddText(bold, fs, ImVec2(b.x - pad - vs.x, ty), INK, val.c_str());
                const float kr = px(9), th = px(6);
                float bx0 = tx + kr, bx1 = b.x - pad - kr, by = ty + lh + hh + bar * 0.5f;
                float x = bx0 + (bx1 - bx0) * it.value / 100.0f;
                dl->AddRectFilled(ImVec2(bx0, by - th / 2), ImVec2(bx1, by + th / 2), P_LINE, th / 2);
                dl->AddRect(ImVec2(bx0, by - th / 2), ImVec2(bx1, by + th / 2), MUTED, th / 2, 0, line);
                dl->AddRectFilled(ImVec2(bx0, by - th / 2), ImVec2(x, by + th / 2), ACCENT, th / 2);
                dl->AddCircleFilled(ImVec2(x, by), kr, P_RAISE);
                dl->AddCircle(ImVec2(x, by), kr, INK, 0, border);
                if (ImGui::IsItemActive() && ImGui::IsMouseDown(0)) {
                    float f = std::clamp((io.MousePos.x - bx0) / std::max(1.0f, bx1 - bx0), 0.0f, 1.0f);
                    int nv = (int)std::lround(f * 20) * 5; // steps of 5 %
                    if (nv != it.value) out.slider = it.id, out.slider_value = nv;
                }
                clicked = false;
            }
            if (focused) ring(dl, a, b, r_m);
            shown(it.id, a, b);
            if (clicked && it.kind != menu::Kind::Info) out.pick = it.id;
            if (focused && v.scroll_to_focus) ImGui::SetScrollHereY(0.35f);
        };
        if (cur) {
            const auto &items = cur->items;
            bool top = true; // nothing drawn yet in this tab
            for (size_t i = 0; i < items.size();) {
                const menu::Item &it = items[i];
                const float width = ImGui::GetContentRegionAvail().x;
                if (it.kind == menu::Kind::Heading) {
                    // .ps h4: a new group, with a 1 px line above it (not at the top).
                    if (!top) {
                        ImGui::Dummy(ImVec2(0, px(14)));
                        separator();
                    }
                    ImGui::Dummy(ImVec2(0, px(14)));
                    ImGui::PushFont(bold, std::round(fs * 0.9f));
                    ImGui::PushTextWrapPos(ImGui::GetCursorPos().x + width);
                    ImGui::TextUnformatted(it.label.c_str());
                    ImGui::PopTextWrapPos();
                    ImGui::PopFont();
                    ImGui::Dummy(ImVec2(0, px(8)));
                    i++;
                } else if (it.kind == menu::Kind::Note) {
                    // .fine: small muted words.
                    ImGui::Dummy(ImVec2(0, px(10)));
                    ImGui::PushFont(reg, small);
                    ImGui::PushStyleColor(ImGuiCol_Text, MUTED);
                    ImGui::PushTextWrapPos(ImGui::GetCursorPos().x + width);
                    ImGui::TextUnformatted(it.label.c_str());
                    ImGui::PopTextWrapPos();
                    ImGui::PopStyleColor();
                    ImGui::PopFont();
                    ImGui::Dummy(ImVec2(0, px(4)));
                    i++;
                } else if (it.kind == menu::Kind::Slot) {
                    // .snap: the picture (3:2, 96 px, a 2 px ink frame) beside the name and time, its Save / Load
                    // buttons side by side under them (.sv-acts), a 1 px line under the lot.
                    ImGui::Dummy(ImVec2(0, px(12)));
                    ImVec2 p = ImGui::GetCursorScreenPos();
                    const Picture *pic = picture(it.picture);
                    const float pw = px(96), ph = px(64);
                    const float tx = p.x + pw + px(12), tw = std::max(1.0f, width - pw - px(12));
                    float lh = text_height(bold, fs, it.label, tw), hh = text_height(reg, small, it.hint, tw);
                    float h = std::max(ph, lh + px(2) + hh);
                    ImVec2 f0(p.x, std::round(p.y + (h - ph) / 2)), f1(p.x + pw, f0.y + ph);
                    dl->AddRectFilled(f0, f1, pic ? IM_COL32(0, 0, 0, 0xFF) : P_WELL, r_s);
                    if (pic) {
                        // object-fit: contain
                        float k = std::min(pw / std::max(1, pic->w), ph / std::max(1, pic->h));
                        float iw = std::round(pic->w * k), ih = std::round(pic->h * k);
                        ImVec2 i0(std::round(f0.x + (pw - iw) / 2), std::round(f0.y + (ph - ih) / 2));
                        dl->AddImage(ImTextureRef((ImTextureID)(intptr_t)pic->tex), i0, i0 + ImVec2(iw, ih));
                    }
                    dl->AddRect(f0, f1, INK, r_s, 0, border);
                    float ty = p.y + (h - lh - px(2) - hh) / 2;
                    dl->AddText(bold, fs, ImVec2(tx, ty), INK, it.label.c_str(), nullptr, tw);
                    dl->AddText(reg, small, ImVec2(tx, ty + lh + px(2)), MUTED, it.hint.c_str(), nullptr, tw);
                    ImGui::Dummy(ImVec2(width, h));
                    i++;
                    std::vector<const menu::Item *> acts;
                    while (i < items.size() && items[i].kind == menu::Kind::Action) acts.push_back(&items[i++]);
                    if (!acts.empty()) {
                        ImGui::Dummy(ImVec2(0, px(8)));
                        buttons(acts, width);
                    }
                    ImGui::Dummy(ImVec2(0, px(12)));
                    separator();
                } else if (it.kind == menu::Kind::Choice) {
                    std::vector<const menu::Item *> g;
                    while (i < items.size() && items[i].kind == menu::Kind::Choice) g.push_back(&items[i++]);
                    ImGui::Dummy(ImVec2(0, px(4)));
                    segments(g, width);
                    ImGui::Dummy(ImVec2(0, px(8)));
                } else if (it.kind == menu::Kind::Action) {
                    ImGui::Dummy(ImVec2(0, px(6)));
                    buttons({&it}, width);
                    i++;
                } else {
                    ImGui::PushID(it.id.c_str());
                    row(it, width);
                    ImGui::PopID();
                    i++;
                }
                top = false;
            }
        }
        ImGui::Dummy(ImVec2(0, px(24)));
        ImGui::EndChild();
        ImGui::End();
        ImGui::PopFont();
        ImGui::PopStyleVar(3);
        if (!clicked_tab.empty()) tab_ = clicked_tab; // shown from the next frame
        // A click on the game between the toolbar and the panel (the scrim) closes the menu (GameDrawer.kt).
        if (ImGui::IsMouseClicked(0) && io.MousePos.x > rail_w && io.MousePos.x < x0) out.close = true;
    }

    if (v.menu_button && !v.open) {
        // The ☰ button (sc.css .toolbar button): Pocket ink with light lines, --accent under the mouse, and a light
        // 2 px edge so it shows on a dark game picture as well as a light one.
        ImDrawList *fg = ImGui::GetForegroundDrawList();
        float r[4];
        menu_button_rect(W, H, r);
        ImVec2 a(r[0], r[1]), b(r[0] + r[2], r[1] + r[3]);
        fg->AddRectFilled(a, b, v.menu_button_hover ? ACCENT : INK, r_m);
        fg->AddRect(a, b, BG, r_m, 0, border);
        const float lw = r[2] * 0.5f, t = std::max(2.0f, std::round(r[3] * 0.08f)), gap = r[3] * 0.18f;
        const float cx = a.x + r[2] / 2, cy = a.y + r[3] / 2;
        for (int k = -1; k <= 1; k++)
            fg->AddRectFilled(ImVec2(std::round(cx - lw / 2), std::round(cy + k * gap - t / 2)),
                              ImVec2(std::round(cx + lw / 2), std::round(cy + k * gap - t / 2) + t), BG, t / 2);
        if (v.menu_button_hover) {
            // "Game menu (Esc)" beside it, as the toolbar's labels: light words on ink.
            const char *tip = "Game menu (Esc)";
            float ts = std::round(fs * 0.8f), tp = std::round(ts * 0.4f);
            ImVec2 sz = reg->CalcTextSizeA(ts, FLT_MAX, 0, tip);
            ImVec2 t0(b.x + px(6), std::round(cy - sz.y / 2 - tp)), t1(t0.x + sz.x + 2 * tp, t0.y + sz.y + 2 * tp);
            fg->AddRectFilled(t0, t1, INK, r_s);
            fg->AddText(reg, ts, ImVec2(t0.x + tp, t0.y + tp), BG, tip);
        }
    }

    const float pad = px(12);
    if (!v.corner.empty() && !v.open) {
        // GameActivity.watchLink: a small line in the top-right corner, light words on Pocket ink at .85.
        ImDrawList *fg = ImGui::GetForegroundDrawList();
        float cs = fs * 0.7f, cp = cs * 0.4f;
        ImVec2 ts = reg->CalcTextSizeA(cs, FLT_MAX, 0, v.corner.c_str());
        ImVec2 p0(W - ts.x - cp * 2, 0), p1(W, ts.y + cp * 2);
        fg->AddRectFilled(p0, p1, SHADE);
        fg->AddText(reg, cs, ImVec2(p0.x + cp, cp), P_BG, v.corner.c_str());
    }

    if (!v.toast.empty()) {
        // sc.css .toast: ink, light bold words, a 6 px --led edge on the left, 24 px off the bottom.
        ImDrawList *fg = ImGui::GetForegroundDrawList();
        const float edge = px(6), py = px(9);
        float wrap = std::min(W * 0.8f, fs * 22);
        ImVec2 ts = bold->CalcTextSizeA(fs, FLT_MAX, wrap, v.toast.c_str());
        float w = ts.x + edge + 2 * pad;
        ImVec2 p0(std::round((W - w) / 2), H - px(24) - ts.y - 2 * py), p1(p0.x + w, H - px(24));
        fg->AddRectFilled(p0, p1, INK, r_m);
        fg->AddRectFilled(p0, ImVec2(p0.x + edge, p1.y), LED, r_m, ImDrawFlags_RoundCornersLeft);
        fg->AddText(bold, fs, ImVec2(p0.x + edge + pad, p0.y + py), BG, v.toast.c_str(), nullptr, wrap);
    }

    ImGui::Render();
    ImGui_ImplOpenGL3_RenderDrawData(ImGui::GetDrawData());
    return out;
}
} // namespace sc
