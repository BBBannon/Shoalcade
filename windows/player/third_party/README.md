# Vendored third-party code

Copied unchanged from upstream. None of it is ours; each keeps its own licence.

| Folder | What | Version / source | Licence |
|---|---|---|---|
| `libretro/libretro.h` | libretro API header | libretro-common `master` at `d9597c359a5ca8399a4e244f816e7e975a92a2aa` (fetched 31/10/2026) | MIT (text at the top of the file) |
| `nlohmann/json.hpp` | JSON for Modern C++, single header | v3.11.3 release asset | MIT (`nlohmann/LICENSE.MIT`) |
| `stb/stb_image_write.h` | PNG writer | stb `master`, v1.16 | Public domain or MIT (text at the end of the file) |
| `imgui/` | Dear ImGui: `imgui*.cpp/.h`, `imconfig.h`, `imstb_*.h` and the SDL3 + OpenGL 3 backends (`backends/imgui_impl_sdl3.*`, `imgui_impl_opengl3.*`, `imgui_impl_opengl3_loader.h`). Only the files the pause menu uses (no demo, no other backends) | v1.92.4 release (`~/deps/imgui-1.92.4`) | MIT (`imgui/LICENSE.txt`) |
| `stb/stb_image.h` | PNG reader (snapshot pictures in the menu) | stb `master` at `003501f6d7a8668ce8b6d875e34dd97ad0d5d5b5`, v2.30 (fetched 31/10/2026) | Public domain or MIT (text at the end of the file) |
| `fonts/AtkinsonHyperlegible-{Regular,Bold}.ttf` | The menu's font: Atkinson Hyperlegible by the Braille Institute, made to be easy to read (clear letter shapes, slashed zero). Embedded in the exe at build time (`cmake/embed.cmake`) | googlefonts/atkinson-hyperlegible `main` at `1cb311624b2ddf88e9e37873999d165a8cd28b46`, version 1.006 (fetched 31/10/2026) | SIL Open Font License 1.1 (`fonts/OFL.txt`); the font files are unchanged |
