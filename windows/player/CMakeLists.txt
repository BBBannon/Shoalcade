# ShoalcadePlayer: the libretro player of Shoalcade for Windows (part of Shoalcade).
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Linux:   cmake -S windows/player -B build/linux -G Ninja && cmake --build build/linux -j2
# Windows: cmake -S windows/player -B build/win -G Ninja \
#            -DCMAKE_TOOLCHAIN_FILE=windows/player/cmake/mingw64.cmake \
#            -DCMAKE_PREFIX_PATH=$HOME/deps/SDL3-3.2.24/x86_64-w64-mingw32
#          cmake --build build/win -j2      (SDL3.dll is copied next to the exe)
cmake_minimum_required(VERSION 3.20)
project(ShoalcadePlayer VERSION 0.1.0 LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE RelWithDebInfo)
endif()

find_package(SDL3 REQUIRED CONFIG COMPONENTS SDL3-shared)

# Dear ImGui 1.92.4 (MIT) for the pause menu: the core files and the SDL3 + OpenGL 3 backends, unchanged.
set(IMGUI third_party/imgui)
add_library(sc_imgui STATIC
  ${IMGUI}/imgui.cpp ${IMGUI}/imgui_draw.cpp ${IMGUI}/imgui_tables.cpp ${IMGUI}/imgui_widgets.cpp
  ${IMGUI}/backends/imgui_impl_sdl3.cpp ${IMGUI}/backends/imgui_impl_opengl3.cpp)
target_include_directories(sc_imgui PUBLIC ${IMGUI})
target_compile_definitions(sc_imgui PUBLIC IMGUI_DISABLE_DEMO_WINDOWS IMGUI_DISABLE_DEBUG_TOOLS
  IMGUI_DISABLE_WIN32_DEFAULT_IME_FUNCTIONS)
target_link_libraries(sc_imgui PUBLIC SDL3::SDL3)
if(NOT WIN32)
  target_link_libraries(sc_imgui PUBLIC ${CMAKE_DL_LIBS})
endif()

# The menu font (Atkinson Hyperlegible, SIL OFL 1.1), embedded so the player is still one exe + SDL3.dll.
set(SC_FONT_SOURCES)
foreach(f regular:Regular bold:Bold)
  string(REPLACE ":" ";" parts ${f})
  list(GET parts 0 key)
  list(GET parts 1 style)
  set(in ${CMAKE_CURRENT_SOURCE_DIR}/third_party/fonts/AtkinsonHyperlegible-${style}.ttf)
  set(out ${CMAKE_CURRENT_BINARY_DIR}/font_${key}.c)
  add_custom_command(OUTPUT ${out}
    COMMAND ${CMAKE_COMMAND} -DIN=${in} -DOUT=${out} -DNAME=sc_font_${key} -P ${CMAKE_CURRENT_SOURCE_DIR}/cmake/embed.cmake
    DEPENDS ${in} ${CMAKE_CURRENT_SOURCE_DIR}/cmake/embed.cmake VERBATIM)
  list(APPEND SC_FONT_SOURCES ${out})
endforeach()

add_executable(ShoalcadePlayer
  src/main.cpp
  src/player.cpp
  src/core.cpp
  src/core_options.cpp
  src/console.cpp
  src/forced_options.cpp
  src/video.cpp
  src/gl.cpp
  src/audio.cpp
  src/input.cpp
  src/saves.cpp
  src/rewind.cpp
  src/protocol.cpp
  src/files.cpp
  src/sha256.cpp
  src/image.cpp
  src/icon.cpp
  src/log.cpp
  src/menu.cpp
  src/menu_ui.cpp
  src/link.cpp
  src/statefile.cpp
  src/remap.cpp
  ${SC_FONT_SOURCES}
)
target_include_directories(ShoalcadePlayer PRIVATE src third_party third_party/imgui)
target_link_libraries(ShoalcadePlayer PRIVATE sc_imgui)
target_compile_definitions(ShoalcadePlayer PRIVATE SC_PLAYER_VERSION="${PROJECT_VERSION}")
target_link_libraries(ShoalcadePlayer PRIVATE SDL3::SDL3)

# zstd (BSD-3-Clause) for the GameCube / Wii / PS2 snapshots (plan P8, src/statefile.cpp). Windows: MSYS2's static
# libzstd.a (mingw-w64-x86_64-zstd), linked in, so the player is still one exe + SDL3.dll. Linux: the system's.
find_path(ZSTD_INCLUDE_DIR zstd.h REQUIRED)
if(WIN32)
  find_library(ZSTD_LIBRARY NAMES libzstd.a REQUIRED)
else()
  find_library(ZSTD_LIBRARY NAMES zstd REQUIRED)
endif()
target_include_directories(ShoalcadePlayer PRIVATE ${ZSTD_INCLUDE_DIR})
target_link_libraries(ShoalcadePlayer PRIVATE ${ZSTD_LIBRARY})

if(CMAKE_CXX_COMPILER_ID MATCHES "GNU|Clang")
  target_compile_options(ShoalcadePlayer PRIVATE -Wall -Wextra -Wno-unused-parameter)
endif()

if(WIN32)
  target_compile_definitions(sc_imgui PRIVATE __USE_MINGW_ANSI_STDIO=1)
  # C99 printf (%zu, %lld) from MinGW's own stdio, not msvcrt's.
  target_compile_definitions(ShoalcadePlayer PRIVATE __USE_MINGW_ANSI_STDIO=1)
  # One exe plus SDL3.dll: libstdc++, libgcc and winpthread are linked in statically. (-static still links
  # SDL3 and the Windows DLLs through their import libraries.)
  target_link_options(ShoalcadePlayer PRIVATE -static -static-libgcc -static-libstdc++)
  target_link_libraries(ShoalcadePlayer PRIVATE ws2_32) # link play: the local stream from the shell
  # Console subsystem: the shell talks to us over stdin/stdout pipes. The shell starts us with
  # CREATE_NO_WINDOW, so no console window is shown to the player.
  add_custom_command(TARGET ShoalcadePlayer POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E copy_if_different $<TARGET_FILE:SDL3::SDL3> $<TARGET_FILE_DIR:ShoalcadePlayer>)
else()
  target_link_libraries(ShoalcadePlayer PRIVATE ${CMAKE_DL_LIBS} pthread)
endif()

# Unit tests (no core, no window): ShoalcadePlayerUnitTests <scratch dir>. Builds for Linux and Windows.
add_executable(ShoalcadePlayerUnitTests
  tests/unit_tests.cpp src/rewind.cpp src/sha256.cpp src/files.cpp src/log.cpp src/core_options.cpp
  src/console.cpp src/forced_options.cpp src/menu.cpp src/link.cpp src/statefile.cpp src/remap.cpp src/icon.cpp)
target_include_directories(ShoalcadePlayerUnitTests PRIVATE src third_party ${ZSTD_INCLUDE_DIR})
target_link_libraries(ShoalcadePlayerUnitTests PRIVATE ${ZSTD_LIBRARY})
if(WIN32)
  target_compile_definitions(ShoalcadePlayerUnitTests PRIVATE __USE_MINGW_ANSI_STDIO=1)
  target_link_options(ShoalcadePlayerUnitTests PRIVATE -static -static-libgcc -static-libstdc++)
  target_link_libraries(ShoalcadePlayerUnitTests PRIVATE ws2_32)
else()
  target_link_libraries(ShoalcadePlayerUnitTests PRIVATE pthread)
endif()
