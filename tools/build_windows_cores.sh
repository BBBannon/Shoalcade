#!/usr/bin/env bash
# Builds every libretro core for Shoalcade for Windows (x64 DLL) and, on Linux, the same commits as Linux x86_64 .so
# files (for the player's Linux tests). Two hosts:
#  - Linux: cross-built with mingw-w64 GCC (Debian), DLLs load-tested under Wine. The 7 phone cores' rows in
#    windows/cores/cores.lock were built this way.
#  - Windows (Git Bash + MSYS2's MinGW-w64 in C:\msys64, SHOALCADE_MSYS2): built natively, DLLs load-tested natively.
#    Windows only builds the Windows DLLs. Dolphin, LRPS2 and PCEE2 are built on Windows only (that is how they were
#    proven, plan-windows-gc-wii-ps2.md); the Linux host refuses them.
#
# The phone's cores are built from the EXACT commit the phone app ships (docs/licences.md), so saves behave the same
# and the GPL source we publish is exact. Azahar (3DS) is built with the built-in key blob OFF (D153) and Shoalcade's
# patches (tools/azahar-patches/, D154). Dolphin (GameCube + Wii), LRPS2 and PCEE2 (PS2: the user picks, D222 D-3) are
# Windows-only cores (D218), pinned in the table below, with tools/dolphin-patches/ (cheats), tools/lrps2-patches/
# (MinGW build fixes, cheats) and tools/pcee2-patches/ (per-game memory card, cheats).
# Decisions and results: docs/windows/phase0-cores.md, docs/plan-windows-gc-wii-ps2.md.
#
# Usage:  bash tools/build_windows_cores.sh [--win|--linux|--both] [core ...]
#   cores: mgba gpsp melonds pcsx mupen ppsspp azahar dolphin lrps2 pcee2
#   default: every core this host builds (Linux: the first 7; Windows: all 10), in that order.
#   --both is the default on Linux, --win the only choice on Windows. Builds one core at a time.
#
# Environment (all optional):
#   CORE_SRC   source checkouts            (default ~/deps/core-src)
#   CORE_BUILD build trees                 (default ~/deps/core-build; on Windows C:/scb: short, so Dolphin's
#                                           deep build paths stay under Windows' 260-character limit)
#   OUT_WIN    Windows DLL output          (default ~/deps/cores-win)
#   OUT_LINUX  Linux .so output            (default ~/deps/cores-linux)
#   CORES_LOCK lock file to update         (default windows/cores/cores.lock; point it elsewhere for a compare-only build)
#   JOBS       parallel jobs               (default 2; Azahar uses AZAHAR_JOBS, default 2; Dolphin, LRPS2 and
#                                           PCEE2 use BIG_JOBS, default = JOBS)
#   KEEP_BUILD=1  keep each build tree afterwards (default: deleted to save disk)
#   WINE_CHECK=0  skip the Wine load test (Linux host)
#
# Output: one file per core and target, plus the lock file updated with the SHA-256 of every file built. Each file is
# checked: Windows DLLs may import only Windows system DLLs (no libstdc++/libgcc/libwinpthread/libcurl...), and each
# must load and report retro_api_version() == 1 (DLL natively on Windows or under Wine on Linux, .so natively) via
# tools/windows-cores/loadcore.c. Support folders written next to the cores: PPSSPP/ (+ PPSSPP.zip),
# dolphin-emu/Sys/ (Dolphin's Data/Sys at the pinned commit; the app ships it to system\dolphin-emu\Sys\) and
# pcsx2/resources/ (PCEE2's bin/resources; shipped to system\pcsx2\resources\).
set -euo pipefail
# Each recipe runs inside f="$(build_x … | tail -1)". Without this, bash turns -e OFF inside a command substitution,
# so a failed step (a patch that didn't apply, a failed configure) would be ignored and the build would carry on.
shopt -s inherit_errexit

case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) WINHOST=1 ;; *) WINHOST=0 ;; esac
MINGW=x86_64-w64-mingw32
if [ $WINHOST = 1 ]; then
  MSYS2=$(cygpath -u "${SHOALCADE_MSYS2:-C:/msys64}")
  # MSYS2's MinGW-w64 first (not devkitPro's MSYS2 or Git's own tools); its usr/bin last. Native programs (cmake,
  # gcc, git) get C:/... paths as they are.
  export PATH="$MSYS2/mingw64/bin:$PATH:$MSYS2/usr/bin" MSYS_NO_PATHCONV=1
  HERE="$(cd "$(dirname "$0")/.." && pwd -W)"
  CORE_SRC=$(cygpath -m "${CORE_SRC:-$HOME/deps/core-src}")
  CORE_BUILD=$(cygpath -m "${CORE_BUILD:-C:/scb}")
  OUT_WIN=$(cygpath -m "${OUT_WIN:-$HOME/deps/cores-win}")
  OUT_LINUX=unused
  WCC=gcc WCXX=g++ WAR=ar WLD=ld WSTRIP=strip WOBJDUMP=objdump
  MAKE="$MSYS2/usr/bin/make" # never devkitPro's make, which may come first on PATH
  # Native: no toolchain file; the runtime libraries are linked statically, as the cross toolchain file does.
  WIN_CMAKE=(-DCMAKE_C_COMPILER=gcc -DCMAKE_CXX_COMPILER=g++
             "-DCMAKE_SHARED_LINKER_FLAGS=-static -static-libgcc -static-libstdc++"
             "-DCMAKE_EXE_LINKER_FLAGS=-static -static-libgcc -static-libstdc++")
else
  HERE="$(cd "$(dirname "$0")/.." && pwd)"
  CORE_SRC="${CORE_SRC:-$HOME/deps/core-src}"
  CORE_BUILD="${CORE_BUILD:-$HOME/deps/core-build}"
  OUT_WIN="${OUT_WIN:-$HOME/deps/cores-win}"
  OUT_LINUX="${OUT_LINUX:-$HOME/deps/cores-linux}"
  WCC=$MINGW-gcc-posix WCXX=$MINGW-g++-posix WAR=$MINGW-ar WLD=$MINGW-ld WSTRIP=$MINGW-strip WOBJDUMP=$MINGW-objdump
  MAKE=make
  WIN_CMAKE=(-DCMAKE_TOOLCHAIN_FILE="$HERE/tools/windows-cores/mingw-w64-x86_64.cmake")
fi
TOOLS="$HERE/tools/windows-cores"
LOCK="${CORES_LOCK:-$HERE/windows/cores/cores.lock}"
JOBS="${JOBS:-2}"
AZAHAR_JOBS="${AZAHAR_JOBS:-2}"
BIG_JOBS="${BIG_JOBS:-$JOBS}"
KEEP_BUILD="${KEEP_BUILD:-0}"
WINE_CHECK="${WINE_CHECK:-1}"

# ---------------------------------------------------------------------------------------------
# The pins. Columns:
#   name | git URL | full commit | licence (as in docs/licences.md) | output base name
# Notes:
#  - The first 7 are the phone's commits (docs/licences.md).
#  - mGBA: the phone's 7a12d6d is a commit of libretro's mGBA fork (libretro/mgba), not of
#    mgba-emu/mgba (that repo doesn't have it).
#  - melonDS DS v1.4.0 fetches melonDS from a BRANCH (jtg/fix-uninitialized-opengl). We pin
#    that branch's head (16f127d, 14/09/2026, before the v1.4.0 tag on 27/09/2026) so the
#    build can't drift. Its other dependencies are pinned by commit/tag inside melonDS DS.
#  - Azahar: tag 2126.2 = commit 219d743.
#  - Dolphin: libretro/dolphin 29/10/2026 ("aspect-fix" merge); every dependency is its own bundled Externals
#    submodule, linked statically (USE_SYSTEM_LIBS=OFF). LRPS2: libretro/parallel-ps2 01/11/2026, no submodules.
CORES=(
  "mgba|https://github.com/libretro/mgba.git|7a12d6d4b9acb14c0ae62c9166b6a2f3d08007f6|MPL-2.0|mgba_libretro"
  "gpsp|https://github.com/libretro/gpsp.git|5819380c2ffb0900219d700a382ee68c464ebb99|GPL-2.0-or-later|gpsp_libretro"
  "melonds|https://github.com/JesseTG/melonds-ds.git|f394adbacb5722ee97c1b37c8064da9a25818310|GPL-3.0|melondsds_libretro"
  "pcsx|https://github.com/libretro/pcsx_rearmed.git|c8816799b50388e61cfe237fe2cdbb7d8175f20a|GPL-2.0-or-later|pcsx_rearmed_libretro"
  "mupen|https://github.com/libretro/mupen64plus-libretro-nx.git|15bada85906593376e8fd697f1b0b3af3a20f512|GPL-3.0 as combined|mupen64plus_next_libretro"
  "ppsspp|https://github.com/hrydgard/ppsspp.git|902e5ccd8c1400b4387b73942491cded655e5f9c|GPL-2.0-or-later (Windows build: + tools/ppsspp-patches build fix)|ppsspp_libretro"
  "azahar|https://github.com/azahar-emu/azahar.git|219d743a1e698101fe1ab8bef43e6563126c3c27|GPL-3.0-or-later (modified: tools/azahar-patches)|azahar_libretro"
  "dolphin|https://github.com/libretro/dolphin.git|b93001ed50c8b34e6ead4eed713c2005a80386e4|GPL-2.0-or-later (modified: tools/dolphin-patches)|dolphin_libretro"
  "lrps2|https://github.com/libretro/parallel-ps2.git|083de59f52159ce4dd2f63a63f74fa9f0e936ee3|GPL-3.0 (modified: tools/lrps2-patches)|pcsx2_libretro"
  "pcee2|https://github.com/WizzardSK/pcee2-libretro.git|5719627731beb90e379ff1be1223391b7f563b3b|GPL-3.0-or-later (modified: tools/pcee2-patches)|pcee2_libretro"
)
MELONDS_PIN=16f127dbb587a73371827ce79689c5d237c4b59b   # JesseTG/melonDS, see above
AZAHAR_TAG=2126.2
WINDOWS_HOST_ONLY=" dolphin lrps2 pcee2 " # built natively on Windows only (never cross-built on Linux)

field() { local IFS='|' a; read -r -a a <<< "$1"; echo "${a[$(( $2 - 1 ))]}"; }
row() { local c; for c in "${CORES[@]}"; do [ "$(field "$c" 1)" = "$1" ] && { echo "$c"; return; }; done; echo "Unknown core: $1" >&2; exit 2; }
log() { printf '\n=== %s\n' "$*"; }

# ---------------------------------------------------------------------------------------------
# Sources: clone once, check out the pinned commit, refuse to build anything else.
# Git options for every checkout: long paths (Dolphin's Externals pass Windows' 260-character limit) and the bytes as
# committed (Git for Windows converts to CRLF by default; Linux never does, so this changes nothing there).
GITO=(-c core.longpaths=true -c core.autocrlf=false)
fetch() { # name url commit [submodules]
  local dir="$CORE_SRC/$1"
  if [ ! -d "$dir/.git" ]; then
    if [ "$1" = azahar ]; then # big: shallow clone of the tag, shallow submodules
      git "${GITO[@]}" clone -q --depth 1 --branch "$AZAHAR_TAG" --recurse-submodules --shallow-submodules "$2" "$dir"
    else
      git "${GITO[@]}" clone -q --filter=blob:none --no-checkout "$2" "$dir"
    fi
  fi
  # A licence-only tree from tools/fetch_core_licences.sh (sparse) can't be built: say so rather than build half a tree.
  if [ "$(git -C "$dir" config --bool core.sparseCheckout || true)" = true ]; then
    echo "$1: $dir is a sparse licence-only tree (tools/fetch_core_licences.sh); build with another CORE_SRC" >&2; exit 1
  fi
  git -C "$dir" config core.longpaths true; git -C "$dir" config core.autocrlf false
  if [ "$(git -C "$dir" rev-parse HEAD 2>/dev/null)" != "$3" ]; then
    git -C "$dir" "${GITO[@]}" -c advice.detachedHead=false checkout -q "$3"
  elif [ -z "$(git -C "$dir" ls-files | head -1)" ]; then
    # Fresh --no-checkout clone whose default branch IS the pin: nothing is checked out yet.
    git -C "$dir" "${GITO[@]}" -c advice.detachedHead=false checkout -q -f --detach "$3"
  fi
  [ "$(git -C "$dir" rev-parse HEAD)" = "$3" ] || { echo "$1: HEAD is not $3" >&2; exit 1; }
  if [ "${4:-}" = submodules ]; then git -C "$dir" "${GITO[@]}" submodule update -q --init --recursive; fi
}

# Shoalcade's patches for a core (each explains itself at the top). Applied once, idempotent. A patch that does not
# apply STOPS the build: a core without its patch is worse than no core (PCEE2 without 0001 writes every game's save to
# one shared card that is never backed up or synced).
apply_patches() { # patch dir, source dir
  local p
  for p in "$1"/*.patch; do
    [ -e "$p" ] || continue
    if git -C "$2" apply --reverse --check "$p" 2>/dev/null; then echo "Patch already in: $(basename "$p")" >&2
    else
      git -C "$2" apply "$p" || { echo "PATCH FAILED, build stopped: $p does not apply to $2" >&2; exit 1; }
      echo "Patched: $(basename "$p")" >&2
    fi
  done
}

# After the build: every patch is really in the source that was built, and the built library holds the text each
# patch adds (so a core built from an unpatched tree can never be recorded in the lock file or shipped).
PATCH_DIRS=(ppsspp:ppsspp-patches azahar:azahar-patches dolphin:dolphin-patches lrps2:lrps2-patches pcee2:pcee2-patches)
PATCH_MARKERS=( # core|text the patched library must contain
  "dolphin|Shoalcade: cheat {} added"
  "lrps2|Shoalcade: %zu cheat line(s) active"
  "pcee2|Shoalcade: memory card {}"
  "pcee2|Shoalcade: {} cheat line(s) active"
  "pcee2|Could not find any valid PS2 BIOS file in '{}'."
) # + lrps2's version string, "v2.0.0-<10-character pinned commit>" (lrps2-patches/0002), added in verify_patched
verify_patched() { # name library [source dir]
  local n="$1" f="$2" src="${3:-$CORE_SRC/$1}" e p m markers=("${PATCH_MARKERS[@]}")
  [ "$n" != lrps2 ] || markers+=("lrps2|v2.0.0-$(field "$(row lrps2)" 3 | cut -c1-10)")
  for e in "${PATCH_DIRS[@]}"; do
    [ "${e%%:*}" = "$n" ] || continue
    for p in "$HERE/tools/${e#*:}"/*.patch; do
      [ -e "$p" ] || continue
      git -C "$src" apply --reverse --check "$p" 2>/dev/null ||
        { echo "$n: PATCH NOT IN the built source: $(basename "$p")" >&2; exit 1; }
    done
  done
  for e in "${markers[@]}"; do
    [ "${e%%|*}" = "$n" ] || continue
    m="${e#*|}"
    LC_ALL=C grep -aqF -- "$m" "$f" || { echo "$n: built library lacks \"$m\": a Shoalcade patch is missing" >&2; exit 1; }
  done
  echo "  patches: checked (source + library markers)"
}

# Copy for in-tree Makefile builds (keeps .git so the cores' version strings match the phone).
fresh_copy() { rm -rf "$2"; cp -a "$1" "$2"; }
pick() { local f; for f in "$@"; do [ -f "$f" ] && echo "$f"; done; true; }

# Windows headers spelt with capitals (Windows.h, ShlObj.h...): mingw's are lower case and
# Linux file names are case sensitive, so give the compiler forwarding headers. Windows' own file names ignore case:
# there the folder stays empty.
casefix_dir() {
  local d="$CORE_BUILD/casefix" h
  mkdir -p "$d/lib"
  [ $WINHOST = 0 ] || { echo "$d"; return; }
  for h in AclAPI.h D3Dcommon.h D3Dcompiler.h ShlObj.h WS2tcpip.h Windows.h WindowsX.h Winsock2.h \
           Shlwapi.h ShellAPI.h Psapi.h TlHelp32.h Dbghelp.h VersionHelpers.h WinSock2.h Mmsystem.h; do
    [ -f "$d/$h" ] || echo "#include <$(echo "$h" | tr 'A-Z' 'a-z')>" > "$d/$h"
  done
  ln -sf "/usr/$MINGW/lib/libversion.a" "$d/lib/libVersion.a"
  echo "$d"
}

# ---------------------------------------------------------------------------------------------
# Recipes. Each prints the path of the built (unstripped) library on its last line.
# $1 = win|linux, $2 = build dir.

build_mgba() { # libretro CI: -DLIBMGBA_ONLY=ON -DBUILD_LIBRETRO=ON
  local args=(-G Ninja -S "$CORE_SRC/mgba" -B "$2" -DCMAKE_BUILD_TYPE=Release -DLIBMGBA_ONLY=ON -DBUILD_LIBRETRO=ON)
  [ "$1" = win ] && args+=("${WIN_CMAKE[@]}")
  cmake "${args[@]}" >&2
  cmake --build "$2" -j"$JOBS" >&2
  pick "$2"/mgba_libretro.{dll,so}
}

build_gpsp() { # Makefile; x86 dynarec (CPU_ARCH x86_32 runs on x86_64 with the mmap JIT cache)
  fresh_copy "$CORE_SRC/gpsp" "$2"
  if [ "$1" = win ]; then
    "$MAKE" -C "$2" -j"$JOBS" platform=win CC=$WCC CXX=$WCXX LDFLAGS=-static >&2
    echo "$2/gpsp_libretro.dll"
  else
    make -C "$2" -j"$JOBS" platform=unix >&2
    echo "$2/gpsp_libretro.so"
  fi
}

build_melonds() { # CMake, OpenGL renderer + JIT on (desktop defaults); melonDS pinned (see top)
  local args=(-G Ninja -S "$CORE_SRC/melonds" -B "$2" -DCMAKE_BUILD_TYPE=Release
              -DMELONDS_REPOSITORY_TAG=$MELONDS_PIN -DCMAKE_POLICY_VERSION_MINIMUM=3.5)
  [ "$1" = win ] && args+=("${WIN_CMAKE[@]}")
  cmake "${args[@]}" >&2
  cmake --build "$2" -j"$JOBS" >&2
  grep -q "ENABLE_OPENGL:BOOL=ON" "$2/CMakeCache.txt" || { echo "melonDS DS: OpenGL renderer is off" >&2; exit 1; }
  pick "$2"/src/libretro/melondsds_libretro.{dll,so}
}

build_pcsx() { # Makefile.libretro: lightrec dynarec, gpu_neon (SSSE3 on x86_64), as the buildbot
  fresh_copy "$CORE_SRC/pcsx" "$2"
  if [ "$1" = win ]; then
    # EXTRA_LDFLAGS (not LDFLAGS: a command-line LDFLAGS would drop the Makefile's -shared).
    "$MAKE" -C "$2" -f Makefile.libretro -j"$JOBS" platform=win CC=$WCC CXX=$WCXX \
      AR=$WAR LD=$WLD EXTRA_LDFLAGS=-static >&2
    echo "$2/pcsx_rearmed_libretro.dll"
  else
    make -C "$2" -f Makefile.libretro -j"$JOBS" >&2
    echo "$2/pcsx_rearmed_libretro.so"
  fi
}

build_mupen() { # Desktop OpenGL (not GLES3) + the same four extras as the phone's arm64 build:
                # LLE (cxd4), parallel-RSP, parallel-RDP (Vulkan, loaded at run time), THR_AL.
  fresh_copy "$CORE_SRC/mupen" "$2"
  if [ "$1" = win ]; then
    # Makefile's Windows block already turns the four extras on; MSYSTEM=MINGW64 selects the
    # x86_64 dynarec. -static rides on GL_LIB because LDFLAGS on the command line drops -shared.
    "$MAKE" -C "$2" -j"$JOBS" platform=win MSYSTEM=MINGW64 CC=$WCC CXX=$WCXX \
      AR=$WAR "GL_LIB=-lopengl32 -static" >&2
    echo "$2/mupen64plus_next_libretro.dll"
  else
    make -C "$2" -j"$JOBS" platform=unix WITH_DYNAREC=x86_64 LLE=1 HAVE_PARALLEL_RSP=1 \
      HAVE_PARALLEL_RDP=1 HAVE_THR_AL=1 >&2
    echo "$2/mupen64plus_next_libretro.so"
  fi
}

build_ppsspp() { # CMake -DLIBRETRO=ON (libretro CI). Windows: PPSSPP's prebuilt FFmpeg is MSVC-only,
                 # so build its bundled FFmpeg (same ffmpeg submodule commit) with mingw.
  # Version string: the phone core says "902e5cc" (buildbot clone without tags). Match it.
  git -C "$CORE_SRC/ppsspp" config core.abbrev 7
  local t; t="$(git -C "$CORE_SRC/ppsspp" tag -l)"; [ -z "$t" ] || git -C "$CORE_SRC/ppsspp" tag -d $t >/dev/null
  # Build fix for the MinGW CMake libretro build (tools/ppsspp-patches, explained inside).
  apply_patches "$HERE/tools/ppsspp-patches" "$CORE_SRC/ppsspp"
  local args=(-G Ninja -S "$CORE_SRC/ppsspp" -B "$2" -DCMAKE_BUILD_TYPE=Release -DLIBRETRO=ON)
  if [ "$1" = win ]; then
    local cf; cf="$(casefix_dir)"
    # libVersion.a: PPSSPP links "Version" (capital V); -lwinmm last: the aemu_postoffice
    # static library uses timeGetTime but is linked after winmm.
    args+=("${WIN_CMAKE[@]}" -DBUILD_BUNDLED_FFMPEG=ON
           "-DCMAKE_C_FLAGS=-isystem $cf" "-DCMAKE_CXX_FLAGS=-isystem $cf"
           "-DCMAKE_SHARED_LINKER_FLAGS=-static -static-libgcc -static-libstdc++ -L$cf/lib"
           "-DCMAKE_CXX_STANDARD_LIBRARIES=-lkernel32 -luser32 -lgdi32 -lwinspool -lshell32 -lole32 -loleaut32 -luuid -lcomdlg32 -ladvapi32 -lwinmm")
  fi
  cmake "${args[@]}" >&2
  # PPSSPP's Core/CMakeLists.txt runs Windows/git-version-gen.cmd whenever WIN32 is set. It only
  # writes the standalone PPSSPPWindows.exe version resource (not used by the libretro core) and
  # can't run on a Linux host, so drop that one step from the generated build file there.
  [ "$1" = win ] && [ $WINHOST = 0 ] && sed -i 's#&& [^ ]*/Windows/git-version-gen\.cmd PPSSPPWindows##' "$2/build.ninja"
  cmake --build "$2" --target ppsspp_libretro -j"$JOBS" >&2
  find "$2" -maxdepth 3 \( -name 'ppsspp_libretro.dll' -o -name 'ppsspp_libretro.so' \) | head -1
}

# PPSSPP system folder (fonts, compat.ini, UI atlas...). Same layout as the buildbot's
# assets/system/PPSSPP.zip in the phone app: assets/ + LICENSE.TXT, debugger/ left empty.
ppsspp_assets() { # $1 = output dir (gets PPSSPP/)
  local dst="$1/PPSSPP"
  rm -rf "$dst"; mkdir -p "$dst"
  (cd "$CORE_SRC/ppsspp/assets" && tar --exclude='./debugger/*' -cf - .) | (cd "$dst" && tar -xf -)
  cp "$CORE_SRC/ppsspp/LICENSE.TXT" "$dst/"
  (cd "$1" && rm -f PPSSPP.zip && zip -qrX PPSSPP.zip PPSSPP)
}

build_azahar() { # Azahar's own Windows libretro CI also cross-builds with MinGW GCC (MXE).
  # Same two patches as the phone (D154). Both only touch libretro_vk.cpp (the Vulkan path);
  # they are applied anyway so the Windows source is exactly the published phone source.
  apply_patches "$HERE/tools/azahar-patches" "$CORE_SRC/azahar"
  local args=(-G Ninja -S "$CORE_SRC/azahar" -B "$2" -DCMAKE_BUILD_TYPE=Release
              -DENABLE_LIBRETRO=ON -DENABLE_BUILTIN_KEYBLOB=OFF -DENABLE_LTO=OFF
              -DCITRA_WARNINGS_AS_ERRORS=OFF)
  if [ "$1" = win ]; then
    local cf; cf="$(casefix_dir)"
    if [ $WINHOST = 0 ]; then
      # Azahar's CMakeLists assumes MXE when cross-building on Linux: it reads LIBTYPE and sets
      # CMAKE_AR to x86_64-w64-mingw32.static-gcc-ar. Give it that name for Debian's gcc-ar.
      local mxe="$CORE_BUILD/mxe-shim"; mkdir -p "$mxe"
      ln -sf "$(command -v $MINGW-gcc-ar-posix)" "$mxe/$MINGW.static-gcc-ar"
      export PATH="$mxe:$PATH"
    fi
    args+=("${WIN_CMAKE[@]}" -DLIBTYPE=STATIC
           "-DCMAKE_C_FLAGS=-isystem $cf" "-DCMAKE_CXX_FLAGS=-isystem $cf")
  fi
  cmake "${args[@]}" >&2
  # Proof the key blob is off, in the generated build files (as the Android script does):
  grep -q "ENABLE_BUILTIN_KEYBLOB:BOOL=OFF" "$2/CMakeCache.txt" || { echo "Key blob not OFF" >&2; exit 1; }
  if grep -q "DENABLE_BUILTIN_KEYBLOB\|default_keys" "$2/build.ninja"; then echo "Key blob in build.ninja" >&2; exit 1; fi
  echo "Key blob: OFF (checked CMakeCache.txt and build.ninja)" >&2
  cmake --build "$2" --target azahar_libretro -j"$AZAHAR_JOBS" >&2
  find "$2" \( -name 'azahar_libretro.dll' -o -name 'azahar_libretro.so' \) | head -1
}

build_dolphin() { # GameCube + Wii. CMake -DLIBRETRO=ON, as libretro/dolphin's own CI. Shoalcade's patch:
  # tools/dolphin-patches (cheats through retro_cheat_set, explained inside).
  # USE_SYSTEM_LIBS=OFF: every library from Dolphin's own Externals, linked in statically (with AUTO it picked up
  # MSYS2's shared libcurl/libzstd/liblz4/liblzma/libbz2). Nothing is lost: the bundled copies are the same libraries
  # (curl HTTP-only, as Dolphin's own Windows builds). The version string comes from `git describe` of the clone
  # (tags included): "<release>.0.<commits since it>+<10-char commit>".
  local src="$CORE_SRC/dolphin"
  case "$(git -C "$src" describe --always --long)" in
    *-*-g*) ;; *) echo "dolphin: no release tag reachable, the version string would be wrong" >&2; exit 1 ;;
  esac
  # Only our patches may change the tree (the version string then says "-dirty"; the core drops that part).
  git -C "$src" submodule foreach -q --recursive '[ -z "$(git status --porcelain --untracked-files=no)" ]' ||
    { echo "dolphin: a submodule has changes" >&2; exit 1; }
  apply_patches "$HERE/tools/dolphin-patches" "$src"
  local args=(-G Ninja -S "$src" -B "$2" -DCMAKE_BUILD_TYPE=Release -DLIBRETRO=ON -DUSE_SYSTEM_LIBS=OFF "${WIN_CMAKE[@]}")
  cmake "${args[@]}" >&2
  grep -q "USE_SYSTEM_LIBS:STRING=OFF" "$2/CMakeCache.txt" || { echo "dolphin: USE_SYSTEM_LIBS is not OFF" >&2; exit 1; }
  cmake --build "$2" --target dolphin_libretro -j"$BIG_JOBS" >&2
  find "$2" -name 'dolphin_libretro.dll' | head -1
}

# Dolphin's Sys folder (Data/Sys at the pinned commit: GameSettings, Gecko code handler, Dolphin's own free DSP ROM
# replacements, fonts made from Droid Sans, wiitdb names...; nothing from Nintendo). The core reads it from
# <system>/dolphin-emu/Sys. Whole folder shipped (D222, D-9).
dolphin_sys() { # $1 = output dir (gets dolphin-emu/Sys/)
  local dst="$1/dolphin-emu"
  rm -rf "$dst"; mkdir -p "$dst"
  cp -a "$CORE_SRC/dolphin/Data/Sys" "$dst/Sys"
  [ -f "$dst/Sys/codehandler.bin" ] && [ -d "$dst/Sys/GameSettings" ] || { echo "dolphin: Sys folder incomplete" >&2; exit 1; }
}

build_pcee2() { # PS2, the second choice (D222, D-3: the user picks LRPS2 or PCEE2). Current PCSX2 + a libretro layer.
  # Its dependencies come from its own script (pcee2-libretro/scripts/build-deps-linux.sh, which also runs the
  # libretro buildbot's MinGW job with HOST set) at the revisions its deps.versions pins, built static into
  # $CORE_BUILD/pcee2-deps once (rebuilt when deps.versions changes). libbacktrace is left out: Linux only.
  # Shoalcade's patches: tools/pcee2-patches (per-game memory card in the save directory, cheats).
  local src="$CORE_SRC/pcee2" deps="$CORE_BUILD/pcee2-deps"
  apply_patches "$HERE/tools/pcee2-patches" "$src"
  local want; want="$(sha256sum "$src/pcee2-libretro/scripts/deps.versions" | cut -d' ' -f1)"
  if [ "$(cat "$deps/prefix/.deps-versions" 2>/dev/null)" != "$want" ]; then
    rm -rf "$deps"; mkdir -p "$deps/work"
    cp "$src/pcee2-libretro/scripts/deps.versions" "$deps/work/"
    sed '/libbacktrace/d' "$src/pcee2-libretro/scripts/build-deps-linux.sh" > "$deps/work/build-deps.sh"
    (cd "$deps/work" && GIT_CONFIG_COUNT=2 GIT_CONFIG_KEY_0=core.longpaths GIT_CONFIG_VALUE_0=true \
        GIT_CONFIG_KEY_1=core.autocrlf GIT_CONFIG_VALUE_1=false \
        CMAKE=cmake HOST=$MINGW PCEE2_SDL_STATIC=1 bash ./build-deps.sh "$deps/prefix") >&2
    [ -f "$deps/prefix/lib/libshaderc_combined.a" ] || { echo "pcee2: dependencies did not build" >&2; exit 1; }
    echo "$want" > "$deps/prefix/.deps-versions"
  fi
  local args=(-G Ninja -S "$src" -B "$2" -DCMAKE_BUILD_TYPE=Release -DENABLE_LIBRETRO=ON -DENABLE_QT_UI=OFF
              -DENABLE_TESTS=OFF -DDISABLE_ADVANCE_SIMD=ON -DSHADERC_STATIC=ON -DCMAKE_PREFIX_PATH="$deps/prefix"
              -DSHADERC_LIBRARY="$deps/prefix/lib/libshaderc_combined.a" "${WIN_CMAKE[@]}")
  cmake "${args[@]}" >&2
  cmake --build "$2" --target pcee2_libretro -j"$BIG_JOBS" >&2
  find "$2" -name 'pcee2_libretro.dll' | head -1
}

# PCEE2's resources (game database with per-game fixes, RedumpDatabase, fonts, shaders...: bin/resources at the pinned
# commit). The core reads them from <system>/pcsx2/resources; without them it runs but loses the game fixes.
pcee2_resources() { # $1 = output dir (gets pcsx2/resources/)
  local dst="$1/pcsx2"
  rm -rf "$dst"; mkdir -p "$dst"
  cp -a "$CORE_SRC/pcee2/bin/resources" "$dst/resources"
  [ -f "$dst/resources/GameIndex.yaml" ] || { echo "pcee2: resources incomplete" >&2; exit 1; }
}

build_lrps2() { # PS2. CMake -DLIBRETRO=ON. Shoalcade's patches (tools/lrps2-patches, each explained inside): two
  # MinGW build fixes (GCC's -mavx flags instead of MSVC's /arch:AVX; the version string from LRPS2's own commit,
  # "v2.0.0-<commit>") and cheats through retro_cheat_set.
  local src="$CORE_SRC/lrps2"
  git -C "$src" config core.abbrev 10
  apply_patches "$HERE/tools/lrps2-patches" "$src"
  local args=(-G Ninja -S "$src" -B "$2" -DCMAKE_BUILD_TYPE=Release -DLIBRETRO=ON -DDISABLE_ADVANCE_SIMD=ON
              -DUSE_VULKAN=ON -DUSE_OPENGL=ON "${WIN_CMAKE[@]}")
  cmake "${args[@]}" >&2
  cmake --build "$2" -j"$BIG_JOBS" >&2
  find "$2" -name 'pcsx2_libretro.dll' | head -1
}

# ---------------------------------------------------------------------------------------------
# Checks and the lock file.
LOADER_WIN="$CORE_BUILD/loadcore.exe"; LOADER_LINUX="$CORE_BUILD/loadcore"
loaders() {
  mkdir -p "$CORE_BUILD"
  [ -x "$LOADER_WIN" ] || $WCC -O2 -static -o "$LOADER_WIN" "$TOOLS/loadcore.c"
  [ $WINHOST = 1 ] || [ -x "$LOADER_LINUX" ] || gcc -O2 -o "$LOADER_LINUX" "$TOOLS/loadcore.c" -ldl
}

# Windows system DLLs a core may import. Anything else (libstdc++-6, libgcc_s_seh-1,
# libwinpthread-1, zlib1...) would have to be shipped, so it fails the build.
SYSTEM_DLLS='^(kernel32|user32|gdi32|advapi32|shell32|shlwapi|ole32|oleaut32|msvcrt|ws2_32|iphlpapi|opengl32|winmm|winhttp|crypt32|bcrypt|dbghelp|version|setupapi|cfgmgr32|powrprof|imm32|dwmapi|uxtheme|comdlg32|comctl32|psapi|userenv|ntdll|secur32|dxgi|d3d11|d3d12|d3dcompiler_47|xinput1_4|hid|mfplat|mfreadwrite|mf|mfuuid|avrt|synchronization|api-ms-win-.*)\.dll$'

check_and_record() { # target name file
  local t="$1" n="$2" f="$3" imports="" load=""
  if [ "$t" = win ]; then
    imports="$($WOBJDUMP -p "$f" | tr -d '\r' | awk '/DLL Name:/{print $3}' | sort -fu | tr '\n' ' ')"
    local d; for d in $imports; do
      echo "$d" | tr 'A-Z' 'a-z' | grep -Eq "$SYSTEM_DLLS" || { echo "$n: imports non-system DLL $d" >&2; exit 1; }
    done
    if [ $WINHOST = 1 ]; then load="$(timeout 300 "$LOADER_WIN" "$f" 2>&1 | tr -d '\r' | grep -E '^(OK|LOAD|MISSING)' | head -1 || true)"
    elif [ "$WINE_CHECK" = 1 ]; then load="$(WINEDEBUG=-all timeout 300 wine "$LOADER_WIN" "$f" 2>&1 | tr -d '\r' | grep -E '^(OK|LOAD|MISSING)' | head -1 || true)"
    else load="(Wine check skipped)"; fi
  else
    imports="$(readelf -d "$f" | awk -F'[][]' '/NEEDED/{print $2}' | tr '\n' ' ')"
    load="$("$LOADER_LINUX" "$f" 2>&1 || true)"
  fi
  case "$load" in "OK api=1 "*|"(Wine check skipped)") ;; *) echo "$n ($t): load test failed: $load" >&2; exit 1;; esac
  local sha size; sha="$(sha256sum "$f" | cut -d' ' -f1)"; size="$(stat -c %s "$f")"
  echo "$n $t: $(basename "$f") $size bytes sha256 $sha"
  echo "  file:    $(file -b "$f")"
  echo "  imports: $imports"
  echo "  load:    $load"
  lock_put "$n" "$t" "$(basename "$f")" "$sha" "$size"
}

lock_put() { # name target file sha size
  local r url commit lic
  r="$(row "$1")"; url="$(field "$r" 2)"; commit="$(field "$r" 3)"; lic="$(field "$r" 4)"
  mkdir -p "$(dirname "$LOCK")"
  if [ ! -f "$LOCK" ]; then
    { echo "# Shoalcade Windows/Linux libretro cores. Written by tools/build_windows_cores.sh; do not edit by hand."
      echo "# Same commits as the phone app (docs/licences.md). Tab-separated:"
      printf '# core\ttarget\trepo\tcommit\tlicence\tfile\tbytes\tsha256\n'; } > "$LOCK"
  fi
  local tmp; tmp="$(mktemp)"
  awk -F'\t' -v n="$1" -v t="$2" '!($1==n && $2==t)' "$LOCK" > "$tmp"
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$1" "$2" "$url" "$commit" "$lic" "$3" "$5" "$4" >> "$tmp"
  { grep '^#' "$tmp"; grep -v '^#' "$tmp" | LC_ALL=C sort -t$'\t' -k1,1 -k2,2; } > "$LOCK"; rm -f "$tmp"
}

build_one() { # name target
  local n="$1" t="$2" r url commit out bld f dst start
  r="$(row "$n")"; url="$(field "$r" 2)"; commit="$(field "$r" 3)"; out="$(field "$r" 5)"
  case "$n" in ppsspp|azahar|dolphin|pcee2) fetch "$n" "$url" "$commit" submodules ;; *) fetch "$n" "$url" "$commit" ;; esac
  bld="$CORE_BUILD/$n-$t"; start=$(date +%s)
  log "$n ($t) from $commit"
  f="$("build_$n" "$t" "$bld" | tail -1)"
  [ -n "$f" ] && [ -f "$f" ] || { echo "$n ($t): build gave no library" >&2; exit 1; }
  if [ "$t" = win ]; then dst="$OUT_WIN/$out.dll"; mkdir -p "$OUT_WIN"; $WSTRIP --strip-unneeded -o "$dst" "$f"
  else dst="$OUT_LINUX/$out.so"; mkdir -p "$OUT_LINUX"; strip --strip-unneeded -o "$dst" "$f"; fi
  verify_patched "$n" "$dst"
  check_and_record "$t" "$n" "$dst"
  if [ "$n" = ppsspp ]; then
    if [ "$t" = win ]; then ppsspp_assets "$OUT_WIN"; else ppsspp_assets "$OUT_LINUX"; fi
    echo "  PPSSPP system folder written next to the core (PPSSPP/ and PPSSPP.zip)"
  fi
  if [ "$n" = dolphin ]; then
    dolphin_sys "$OUT_WIN"
    echo "  Dolphin system folder written next to the core (dolphin-emu/Sys/, $(find "$OUT_WIN/dolphin-emu/Sys" -type f | wc -l) files)"
  fi
  if [ "$n" = pcee2 ]; then
    pcee2_resources "$OUT_WIN"
    echo "  PCEE2 resources written next to the core (pcsx2/resources/, $(find "$OUT_WIN/pcsx2/resources" -type f | wc -l) files)"
  fi
  echo "  time:    $(( $(date +%s) - start )) s"
  [ "$KEEP_BUILD" = 1 ] || rm -rf "$bld"
}

# ---------------------------------------------------------------------------------------------
if [ $WINHOST = 1 ]; then TARGETS=(win); else TARGETS=(win linux); fi
LIST=()
for a in "$@"; do
  case "$a" in
    --win) TARGETS=(win) ;; --linux) TARGETS=(linux) ;; --both) TARGETS=(win linux) ;;
    -h|--help) sed -n 2,39p "$0"; exit 0 ;;
    *) row "$a" >/dev/null; LIST+=("$a") ;;
  esac
done
if [ $WINHOST = 1 ]; then
  [ "${TARGETS[*]}" = win ] || { echo "On Windows only --win is built (the Linux .so files are built on Linux)" >&2; exit 2; }
  [ ${#LIST[@]} -gt 0 ] || LIST=(mgba gpsp melonds pcsx mupen ppsspp azahar dolphin lrps2 pcee2)
else
  [ ${#LIST[@]} -gt 0 ] || LIST=(mgba gpsp melonds pcsx mupen ppsspp azahar)
  for n in "${LIST[@]}"; do
    [[ "$WINDOWS_HOST_ONLY" != *" $n "* ]] || { echo "$n is built natively on Windows only (Git Bash + MSYS2)" >&2; exit 2; }
  done
fi

need_tools=(cmake ninja git "$WCC" "$WCXX")
for n in "${LIST[@]}"; do
  case "$n" in gpsp|pcsx|mupen) need_tools+=("$MAKE") ;; ppsspp) need_tools+=(nasm zip) ;; esac
done
for tool in "${need_tools[@]}"; do
  command -v "$tool" >/dev/null || { echo "Missing tool: $tool ($([ $WINHOST = 1 ] && echo 'MSYS2: pacman -S ...' || echo 'sudo apt-get install ...'))" >&2; exit 1; }
done
if [ $WINHOST = 1 ]; then
  [ "$(command -v gcc)" = "$MSYS2/mingw64/bin/gcc" ] || { echo "gcc is not MSYS2's MinGW-w64: $(command -v gcc)" >&2; exit 1; }
fi
[[ $WINHOST = 0 && " ${TARGETS[*]} " == *" win "* && "$WINE_CHECK" = 1 ]] && { command -v wine >/dev/null || { echo "Missing wine (or WINE_CHECK=0)" >&2; exit 1; }; }
loaders
for n in "${LIST[@]}"; do for t in "${TARGETS[@]}"; do build_one "$n" "$t"; done; done
log "Done. Lock file: $LOCK"
