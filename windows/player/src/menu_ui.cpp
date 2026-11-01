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
// The phone's drawer colours (GameDrawer.kt): PANEL, ROW, FOCUS, MUTED, the lit row and the border.
const ImU32 PANEL = IM_COL32(0x10, 0x10, 0x18, 0xFF);
const ImU32 ROW = IM_COL32(0x18, 0x18, 0x22, 0xFF);
const ImU32 LIT = IM_COL32(0x20, 0x34, 0x48, 0xFF);
const ImU32 FOCUS = IM_COL32(0x78, 0xC8, 0xFF, 0xFF);
const ImU32 MUTED = IM_COL32(0xA8, 0xA8, 0xB4, 0xFF);
const ImU32 EDGE = IM_COL32(0x2C, 0x2C, 0x3A, 0xFF);
const ImU32 WHITE = IM_COL32(0xFF, 0xFF, 0xFF, 0xFF);
const ImU32 SCRIM = IM_COL32(0, 0, 0, 0x99);
const ImU32 INK = IM_COL32(0x10, 0x10, 0x18, 0xFF);

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
    st.WindowRounding = 0;
    st.WindowBorderSize = 0;
    st.ScrollbarRounding = 6;
    st.Colors[ImGuiCol_WindowBg] = ImGui::ColorConvertU32ToFloat4(PANEL);
    st.Colors[ImGuiCol_ChildBg] = ImVec4(0, 0, 0, 0);
    st.Colors[ImGuiCol_ScrollbarBg] = ImGui::ColorConvertU32ToFloat4(PANEL);
    st.Colors[ImGuiCol_ScrollbarGrab] = ImGui::ColorConvertU32ToFloat4(EDGE);
    st.Colors[ImGuiCol_ScrollbarGrabHovered] = ImGui::ColorConvertU32ToFloat4(MUTED);
    st.Colors[ImGuiCol_ScrollbarGrabActive] = ImGui::ColorConvertU32ToFloat4(FOCUS);
    st.Colors[ImGuiCol_Text] = ImGui::ColorConvertU32ToFloat4(WHITE);
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
    const float pad = std::round(fs * 0.55f);
    const float row_min = std::round(fs * 2.2f); // the phone's 48 dp rows
    ImFont *reg = (ImFont *)regular_, *bold = (ImFont *)bold_;

    rects_.clear();
    if (v.open && v.sections) {
        ImDrawList *bg = ImGui::GetBackgroundDrawList();
        bg->AddRectFilled(ImVec2(0, 0), ImVec2(W, H), SCRIM);
        const float panel_w = std::min(std::round(fs * 18), std::round(W * 0.85f));
        ImGui::SetNextWindowPos(ImVec2(0, 0));
        ImGui::SetNextWindowSize(ImVec2(panel_w, H));
        ImGui::PushStyleVar(ImGuiStyleVar_WindowPadding, ImVec2(pad, pad));
        ImGui::PushStyleVar(ImGuiStyleVar_ScrollbarSize, std::round(fs * 0.55f));
        ImGui::PushStyleVar(ImGuiStyleVar_ItemSpacing, ImVec2(0, std::round(fs * 0.3f)));
        ImGui::PushFont(reg, fs);
        ImGui::Begin("##menu", nullptr,
                     ImGuiWindowFlags_NoDecoration | ImGuiWindowFlags_NoMove | ImGuiWindowFlags_NoSavedSettings |
                         ImGuiWindowFlags_NoResize | ImGuiWindowFlags_NoBringToFrontOnFocus);
        ImDrawList *dl = ImGui::GetWindowDrawList();

        // Title row: the game's name (bold, up to two lines) and the close button.
        {
            const float btn = row_min;
            ImVec2 p = ImGui::GetCursorScreenPos();
            float tw = ImGui::GetContentRegionAvail().x - btn - pad;
            float title_size = std::round(fs * 1.15f);
            std::string title = v.title.empty() ? menu::TITLE : v.title;
            float th = std::min(text_height(bold, title_size, title, tw), title_size * 2.6f);
            float h = std::max(btn, th);
            dl->PushClipRect(p, ImVec2(p.x + tw, p.y + h), true);
            dl->AddText(bold, title_size, ImVec2(p.x, p.y + (h - th) / 2), WHITE, title.c_str(), nullptr, tw);
            dl->PopClipRect();
            ImGui::SetCursorScreenPos(ImVec2(p.x + tw + pad, p.y + (h - btn) / 2));
            if (ImGui::InvisibleButton("##close", ImVec2(btn, btn))) out.close = true;
            ImVec2 a = ImGui::GetItemRectMin(), b = ImGui::GetItemRectMax();
            if (ImGui::IsItemHovered()) dl->AddRectFilled(a, b, LIT, fs * 0.45f);
            ImVec2 c((a.x + b.x) / 2, (a.y + b.y) / 2);
            float r = fs * 0.4f, t = std::max(2.0f, fs * 0.12f);
            dl->AddLine(ImVec2(c.x - r, c.y - r), ImVec2(c.x + r, c.y + r), WHITE, t);
            dl->AddLine(ImVec2(c.x - r, c.y + r), ImVec2(c.x + r, c.y - r), WHITE, t);
            ImGui::SetItemTooltip("%s", menu::CLOSE_MENU);
            ImGui::SetCursorScreenPos(ImVec2(p.x, p.y + h + pad * 0.5f));
        }

        ImGui::BeginChild("##list", ImVec2(0, 0), ImGuiChildFlags_None, ImGuiWindowFlags_NoSavedSettings);
        if (v.scroll_to_top) ImGui::SetScrollY(0);
        dl = ImGui::GetWindowDrawList();
        for (const auto &sec : *v.sections) {
            // Section title: blue capitals, bold (GameDrawer.kt Line.Title).
            ImGui::Dummy(ImVec2(0, std::round(fs * 0.35f)));
            ImGui::PushFont(bold, small);
            ImGui::PushStyleColor(ImGuiCol_Text, FOCUS);
            ImGui::TextUnformatted(upper(sec.title).c_str());
            ImGui::PopStyleColor();
            ImGui::PopFont();
            for (const auto &it : sec.items) {
                ImGui::PushID(it.id.c_str());
                const float width = ImGui::GetContentRegionAvail().x;
                ImVec2 p = ImGui::GetCursorScreenPos();
                if (it.kind == menu::Kind::Heading) {
                    ImGui::Dummy(ImVec2(0, std::round(fs * 0.1f)));
                    ImGui::PushFont(bold, small);
                    ImGui::PushStyleColor(ImGuiCol_Text, MUTED);
                    ImGui::TextUnformatted(it.label.c_str());
                    ImGui::PopStyleColor();
                    ImGui::PopFont();
                    ImGui::PopID();
                    continue;
                }
                if (it.kind == menu::Kind::Note) {
                    ImGui::PushFont(reg, small);
                    ImGui::PushStyleColor(ImGuiCol_Text, MUTED);
                    ImGui::PushTextWrapPos(ImGui::GetCursorPos().x + width);
                    ImGui::TextUnformatted(it.label.c_str());
                    ImGui::PopTextWrapPos();
                    ImGui::PopStyleColor();
                    ImGui::PopFont();
                    ImGui::PopID();
                    continue;
                }
                if (it.kind == menu::Kind::Slot) {
                    // Snapshot slot: picture (if any), name, time. Its Save / Load rows follow.
                    const Picture *pic = picture(it.picture);
                    float ph = std::round(fs * 2.8f), pw = 0;
                    if (pic) pw = std::min(std::round(ph * pic->w / std::max(1, pic->h)), std::round(fs * 6));
                    float tx = p.x + (pic ? pw + pad : 0), tw = width - (tx - p.x);
                    float lh = text_height(bold, fs, it.label, tw), hh = text_height(reg, small, it.hint, tw);
                    float h = std::max(pic ? ph : 0.0f, lh + hh + fs * 0.15f);
                    ImGui::Dummy(ImVec2(width, std::round(fs * 0.25f)));
                    p = ImGui::GetCursorScreenPos();
                    if (pic) {
                        float py = p.y + (h - ph) / 2;
                        dl->AddImage(ImTextureRef((ImTextureID)(intptr_t)pic->tex), ImVec2(p.x, py), ImVec2(p.x + pw, py + ph));
                        dl->AddRect(ImVec2(p.x, py), ImVec2(p.x + pw, py + ph), EDGE, 0, 0, 1.0f);
                    }
                    float ty = p.y + (h - lh - hh) / 2;
                    dl->AddText(bold, fs, ImVec2(tx, ty), WHITE, it.label.c_str(), nullptr, tw);
                    dl->AddText(reg, small, ImVec2(tx, ty + lh + fs * 0.1f), MUTED, it.hint.c_str(), nullptr, tw);
                    ImGui::Dummy(ImVec2(width, h));
                    ImGui::PopID();
                    continue;
                }
                // A row that can be picked: Action, Choice, Toggle, Slider, Info.
                const float inner = pad * 0.9f;
                float left = 0, right = 0;
                if (it.kind == menu::Kind::Choice) left = fs * 1.4f;
                if (it.kind == menu::Kind::Toggle) right = fs * 2.6f;
                if (it.kind == menu::Kind::Slider) right = fs * 2.8f;
                float tw = width - 2 * inner - left - right;
                float lh = text_height(reg, fs, it.label, tw);
                float hh = it.hint.empty() ? 0 : text_height(reg, small, it.hint, tw) + fs * 0.1f;
                float bar = it.kind == menu::Kind::Slider ? fs * 1.2f : 0;
                float h = std::max(row_min, lh + hh + bar + inner * 1.2f);
                bool clicked = ImGui::InvisibleButton("##row", ImVec2(width, h));
                bool hovered = ImGui::IsItemHovered();
                bool focused = v.show_focus && v.focus == it.id;
                bool lit = focused || hovered;
                ImVec2 a = ImGui::GetItemRectMin(), b = ImGui::GetItemRectMax();
                float round = fs * 0.45f;
                dl->AddRectFilled(a, b, lit ? LIT : ROW, round);
                dl->AddRect(a, b, lit ? FOCUS : EDGE, round, 0, focused ? std::max(2.0f, fs * 0.1f) : 1.0f);
                float ty = a.y + (h - lh - hh - bar) / 2;
                float tx = a.x + inner + left;
                dl->AddText(reg, fs, ImVec2(tx, ty), WHITE, it.label.c_str(), nullptr, tw);
                if (!it.hint.empty())
                    dl->AddText(reg, small, ImVec2(tx, ty + lh + fs * 0.1f), MUTED, it.hint.c_str(), nullptr, tw);
                float cy = (a.y + b.y) / 2;
                if (it.kind == menu::Kind::Choice) {
                    // Radio button (the phone's RadioButton): ring, filled dot when chosen.
                    ImVec2 c(a.x + inner + fs * 0.55f, cy);
                    dl->AddCircle(c, fs * 0.45f, it.on ? FOCUS : MUTED, 0, std::max(2.0f, fs * 0.1f));
                    if (it.on) dl->AddCircleFilled(c, fs * 0.24f, FOCUS);
                } else if (it.kind == menu::Kind::Toggle) {
                    // Switch: blue track and white knob on the right when on; grey, knob left, when off.
                    float sw = fs * 2.1f, sh = fs * 1.15f;
                    ImVec2 s0(b.x - inner - sw, cy - sh / 2), s1(b.x - inner, cy + sh / 2);
                    dl->AddRectFilled(s0, s1, it.on ? FOCUS : EDGE, sh / 2);
                    if (!it.on) dl->AddRect(s0, s1, MUTED, sh / 2, 0, 1.5f);
                    float kr = sh / 2 - fs * 0.14f;
                    ImVec2 k(it.on ? s1.x - sh / 2 : s0.x + sh / 2, cy);
                    dl->AddCircleFilled(k, kr, it.on ? INK : MUTED);
                    if (it.on) {
                        // A tick inside the knob, so "on" doesn't rely on colour alone.
                        float r = kr * 0.55f, t = std::max(1.5f, fs * 0.08f);
                        dl->AddLine(ImVec2(k.x - r, k.y), ImVec2(k.x - r * 0.25f, k.y + r * 0.7f), FOCUS, t);
                        dl->AddLine(ImVec2(k.x - r * 0.25f, k.y + r * 0.7f), ImVec2(k.x + r, k.y - r * 0.6f), FOCUS, t);
                    }
                } else if (it.kind == menu::Kind::Slider) {
                    std::string val = std::to_string(it.value) + "%";
                    ImVec2 vs = reg->CalcTextSizeA(fs, FLT_MAX, 0, val.c_str());
                    dl->AddText(reg, fs, ImVec2(b.x - inner - vs.x, ty), WHITE, val.c_str());
                    float bx0 = tx, bx1 = b.x - inner, by = ty + lh + hh + bar * 0.5f;
                    float th = std::max(4.0f, fs * 0.22f);
                    float x = bx0 + (bx1 - bx0) * it.value / 100.0f;
                    dl->AddRectFilled(ImVec2(bx0, by - th / 2), ImVec2(bx1, by + th / 2), EDGE, th / 2);
                    dl->AddRectFilled(ImVec2(bx0, by - th / 2), ImVec2(x, by + th / 2), FOCUS, th / 2);
                    dl->AddCircleFilled(ImVec2(x, by), fs * 0.38f, WHITE);
                    if (ImGui::IsItemActive() && ImGui::IsMouseDown(0)) {
                        float f = std::clamp((io.MousePos.x - bx0) / std::max(1.0f, bx1 - bx0), 0.0f, 1.0f);
                        int nv = (int)std::lround(f * 20) * 5; // steps of 5 %
                        if (nv != it.value) out.slider = it.id, out.slider_value = nv;
                    }
                    clicked = false;
                }
                // Only rows inside the list's visible part count as "shown" (menu.state reports where).
                ImVec2 clip0 = ImGui::GetWindowPos(), clip1 = clip0 + ImGui::GetWindowSize();
                if (a.y >= clip0.y && b.y <= clip1.y) rects_[it.id] = Rect{a.x, a.y, b.x - a.x, b.y - a.y};
                if (clicked && it.kind != menu::Kind::Info) out.pick = it.id;
                if (focused && v.scroll_to_focus) ImGui::SetScrollHereY(0.35f);
                ImGui::PopID();
            }
        }
        ImGui::Dummy(ImVec2(0, fs));
        ImGui::EndChild();
        bool over_panel = ImGui::IsWindowHovered(ImGuiHoveredFlags_RootAndChildWindows | ImGuiHoveredFlags_AllowWhenBlockedByActiveItem);
        ImGui::End();
        ImGui::PopFont();
        ImGui::PopStyleVar(3);
        // A click on the scrim beside the panel closes the menu (GameDrawer.kt).
        if (ImGui::IsMouseClicked(0) && !over_panel && io.MousePos.x > panel_w) out.close = true;
    }

    if (!v.corner.empty() && !v.open) {
        // GameActivity.watchLink: a small line in the top-right corner, white on translucent black.
        ImDrawList *fg = ImGui::GetForegroundDrawList();
        float cs = fs * 0.7f, cp = cs * 0.4f;
        ImVec2 ts = reg->CalcTextSizeA(cs, FLT_MAX, 0, v.corner.c_str());
        ImVec2 p0(W - ts.x - cp * 2, 0), p1(W, ts.y + cp * 2);
        fg->AddRectFilled(p0, p1, IM_COL32(0, 0, 0, 0x99));
        fg->AddText(reg, cs, ImVec2(p0.x + cp, cp), WHITE, v.corner.c_str());
    }

    if (!v.toast.empty()) {
        ImDrawList *fg = ImGui::GetForegroundDrawList();
        float wrap = std::min(W * 0.8f, fs * 22);
        ImVec2 ts = reg->CalcTextSizeA(fs, FLT_MAX, wrap, v.toast.c_str());
        ImVec2 p0((W - ts.x) / 2 - pad, H - ts.y - pad * 4), p1((W + ts.x) / 2 + pad, H - pad * 2);
        fg->AddRectFilled(p0, p1, PANEL, fs * 0.45f);
        fg->AddRect(p0, p1, FOCUS, fs * 0.45f, 0, 2.0f);
        fg->AddText(reg, fs, ImVec2(p0.x + pad, p0.y + pad), WHITE, v.toast.c_str(), nullptr, wrap);
    }

    ImGui::Render();
    ImGui_ImplOpenGL3_RenderDrawData(ImGui::GetDrawData());
    return out;
}
} // namespace sc
