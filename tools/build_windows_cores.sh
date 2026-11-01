#!/usr/bin/env bash
# Builds every libretro core for Shoalcade for Windows (x64 DLL, cross-built on Linux with
# mingw-w64 GCC) and the same commits as Linux x86_64 .so files (for the player's Linux tests).
#
# Every core is built from the EXACT commit the phone app ships (docs/licences.md), so saves
# behave the same and the GPL source we publish is exact. Azahar (3DS) is built with the
# built-in key blob OFF (D153) and Shoalcade's patches (tools/azahar-patches/, D154).
# Decisions and results: docs/windows/phase0-cores.md.
#
# Usage:  bash tools/build_windows_cores.sh [--win|--linux|--both] [core ...]
#   cores: mgba gpsp melonds pcsx mupen ppsspp azahar   (default: all, in that order)
#   --both is the default. Builds one core at a time (this machine has 5 GB RAM).
#
# Environment (all optional):
#   CORE_SRC   source checkouts            (default ~/deps/core-src)
#   CORE_BUILD build trees                 (default ~/deps/core-build)
#   OUT_WIN    Windows DLL output          (default ~/deps/cores-win)
#   OUT_LINUX  Linux .so output            (default ~/deps/cores-linux)
#   JOBS       parallel jobs               (default 2; Azahar uses AZAHAR_JOBS, default 2)
#   KEEP_BUILD=1  keep each build tree afterwards (default: deleted to save disk)
#   WINE_CHECK=0  skip the Wine load test
#
# Output: one file per core and target, plus windows/cores/cores.lock updated with the
# SHA-256 of every file built. Each file is checked: Windows DLLs may import only Windows
# system DLLs (no libstdc++/libgcc/libwinpthread), and each must load and report
# retro_api_version() == 1 (DLL under Wine, .so natively) via tools/windows-cores/loadcore.c.
set -euo pipefail

HERE="$(cd "$(dirname "$0")/.." && pwd)"
TOOLS="$HERE/tools/windows-cores"
LOCK="$HERE/windows/cores/cores.lock"
CORE_SRC="${CORE_SRC:-$HOME/deps/core-src}"
CORE_BUILD="${CORE_BUILD:-$HOME/deps/core-build}"
OUT_WIN="${OUT_WIN:-$HOME/deps/cores-win}"
OUT_LINUX="${OUT_LINUX:-$HOME/deps/cores-linux}"
JOBS="${JOBS:-2}"
AZAHAR_JOBS="${AZAHAR_JOBS:-2}"
KEEP_BUILD="${KEEP_BUILD:-0}"
WINE_CHECK="${WINE_CHECK:-1}"
MINGW=x86_64-w64-mingw32
TC="$TOOLS/mingw-w64-x86_64.cmake"

# ---------------------------------------------------------------------------------------------
# The pins. Same commits as the phone (docs/licences.md). Columns:
#   name | git URL | full commit | licence (as in docs/licences.md) | output base name
# Notes:
#  - mGBA: the phone's 7a12d6d is a commit of libretro's mGBA fork (libretro/mgba), not of
#    mgba-emu/mgba (that repo doesn't have it).
#  - melonDS DS v1.4.0 fetches melonDS from a BRANCH (jtg/fix-uninitialized-opengl). We pin
#    that branch's head (16f127d, 14/09/2026, before the v1.4.0 tag on 27/09/2026) so the
#    build can't drift. Its other dependencies are pinned by commit/tag inside melonDS DS.
#  - Azahar: tag 2126.2 = commit 219d743.
CORES=(
  "mgba|https://github.com/libretro/mgba.git|7a12d6d4b9acb14c0ae62c9166b6a2f3d08007f6|MPL-2.0|mgba_libretro"
  "gpsp|https://github.com/libretro/gpsp.git|5819380c2ffb0900219d700a382ee68c464ebb99|GPL-2.0-or-later|gpsp_libretro"
  "melonds|https://github.com/JesseTG/melonds-ds.git|f394adbacb5722ee97c1b37c8064da9a25818310|GPL-3.0|melondsds_libretro"
  "pcsx|https://github.com/libretro/pcsx_rearmed.git|c8816799b50388e61cfe237fe2cdbb7d8175f20a|GPL-2.0-or-later|pcsx_rearmed_libretro"
  "mupen|https://github.com/libretro/mupen64plus-libretro-nx.git|15bada85906593376e8fd697f1b0b3af3a20f512|GPL-3.0 as combined|mupen64plus_next_libretro"
  "ppsspp|https://github.com/hrydgard/ppsspp.git|902e5ccd8c1400b4387b73942491cded655e5f9c|GPL-2.0-or-later (Windows build: + tools/ppsspp-patches build fix)|ppsspp_libretro"
  "azahar|https://github.com/azahar-emu/azahar.git|219d743a1e698101fe1ab8bef43e6563126c3c27|GPL-3.0-or-later (modified: tools/azahar-patches)|azahar_libretro"
)
MELONDS_PIN=16f127dbb587a73371827ce79689c5d237c4b59b   # JesseTG/melonDS, see above
AZAHAR_TAG=2126.2

field() { local IFS='|' a; read -r -a a <<< "$1"; echo "${a[$(( $2 - 1 ))]}"; }
row() { local c; for c in "${CORES[@]}"; do [ "$(field "$c" 1)" = "$1" ] && { echo "$c"; return; }; done; echo "Unknown core: $1" >&2; exit 2; }
log() { printf '\n=== %s\n' "$*"; }

# ---------------------------------------------------------------------------------------------
# Sources: clone once, check out the pinned commit, refuse to build anything else.
fetch() { # name url commit [submodules]
  local dir="$CORE_SRC/$1"
  if [ ! -d "$dir/.git" ]; then
    if [ "$1" = azahar ]; then # big: shallow clone of the tag, shallow submodules
      git clone -q --depth 1 --branch "$AZAHAR_TAG" --recurse-submodules --shallow-submodules "$2" "$dir"
    else
      git clone -q --filter=blob:none --no-checkout "$2" "$dir"
    fi
  fi
  if [ "$(git -C "$dir" rev-parse HEAD 2>/dev/null)" != "$3" ]; then
    git -C "$dir" -c advice.detachedHead=false checkout -q "$3"
  fi
  [ "$(git -C "$dir" rev-parse HEAD)" = "$3" ] || { echo "$1: HEAD is not $3" >&2; exit 1; }
  if [ "${4:-}" = submodules ]; then git -C "$dir" submodule update -q --init --recursive; fi
}

# Shoalcade's patches for a core (each explains itself at the top). Applied once, idempotent.
apply_patches() { # patch dir, source dir
  local p
  for p in "$1"/*.patch; do
    [ -e "$p" ] || continue
    if git -C "$2" apply --reverse --check "$p" 2>/dev/null; then echo "Patch already in: $(basename "$p")" >&2
    else git -C "$2" apply "$p" && echo "Patched: $(basename "$p")" >&2; fi
  done
}

# Copy for in-tree Makefile builds (keeps .git so the cores' version strings match the phone).
fresh_copy() { rm -rf "$2"; cp -a "$1" "$2"; }
pick() { local f; for f in "$@"; do [ -f "$f" ] && echo "$f"; done; true; }

# Windows headers spelt with capitals (Windows.h, ShlObj.h...): mingw's are lower case and
# Linux file names are case sensitive, so give the compiler forwarding headers.
casefix_dir() {
  local d="$CORE_BUILD/casefix" h
  mkdir -p "$d"
  for h in AclAPI.h D3Dcommon.h D3Dcompiler.h ShlObj.h WS2tcpip.h Windows.h WindowsX.h Winsock2.h \
           Shlwapi.h ShellAPI.h Psapi.h TlHelp32.h Dbghelp.h VersionHelpers.h WinSock2.h Mmsystem.h; do
    [ -f "$d/$h" ] || echo "#include <$(echo "$h" | tr 'A-Z' 'a-z')>" > "$d/$h"
  done
  mkdir -p "$d/lib"; ln -sf "/usr/$MINGW/lib/libversion.a" "$d/lib/libVersion.a"
  echo "$d"
}

# ---------------------------------------------------------------------------------------------
# Recipes. Each prints the path of the built (unstripped) library on its last line.
# $1 = win|linux, $2 = build dir.

build_mgba() { # libretro CI: -DLIBMGBA_ONLY=ON -DBUILD_LIBRETRO=ON
  local args=(-G Ninja -S "$CORE_SRC/mgba" -B "$2" -DCMAKE_BUILD_TYPE=Release -DLIBMGBA_ONLY=ON -DBUILD_LIBRETRO=ON)
  [ "$1" = win ] && args+=(-DCMAKE_TOOLCHAIN_FILE="$TC")
  cmake "${args[@]}" >&2 && cmake --build "$2" -j"$JOBS" >&2
  pick "$2"/mgba_libretro.{dll,so}
}

build_gpsp() { # Makefile; x86 dynarec (CPU_ARCH x86_32 runs on x86_64 with the mmap JIT cache)
  fresh_copy "$CORE_SRC/gpsp" "$2"
  if [ "$1" = win ]; then
    make -C "$2" -j"$JOBS" platform=win CC=$MINGW-gcc-posix CXX=$MINGW-g++-posix LDFLAGS=-static >&2
    echo "$2/gpsp_libretro.dll"
  else
    make -C "$2" -j"$JOBS" platform=unix >&2
    echo "$2/gpsp_libretro.so"
  fi
}

build_melonds() { # CMake, OpenGL renderer + JIT on (desktop defaults); melonDS pinned (see top)
  local args=(-G Ninja -S "$CORE_SRC/melonds" -B "$2" -DCMAKE_BUILD_TYPE=Release
              -DMELONDS_REPOSITORY_TAG=$MELONDS_PIN -DCMAKE_POLICY_VERSION_MINIMUM=3.5)
  [ "$1" = win ] && args+=(-DCMAKE_TOOLCHAIN_FILE="$TC")
  cmake "${args[@]}" >&2 && cmake --build "$2" -j"$JOBS" >&2
  grep -q "ENABLE_OPENGL:BOOL=ON" "$2/CMakeCache.txt" || { echo "melonDS DS: OpenGL renderer is off" >&2; exit 1; }
  pick "$2"/src/libretro/melondsds_libretro.{dll,so}
}

build_pcsx() { # Makefile.libretro: lightrec dynarec, gpu_neon (SSSE3 on x86_64), as the buildbot
  fresh_copy "$CORE_SRC/pcsx" "$2"
  if [ "$1" = win ]; then
    # EXTRA_LDFLAGS (not LDFLAGS: a command-line LDFLAGS would drop the Makefile's -shared).
    make -C "$2" -f Makefile.libretro -j"$JOBS" platform=win CC=$MINGW-gcc-posix CXX=$MINGW-g++-posix \
      AR=$MINGW-ar LD=$MINGW-ld EXTRA_LDFLAGS=-static >&2
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
    make -C "$2" -j"$JOBS" platform=win MSYSTEM=MINGW64 CC=$MINGW-gcc-posix CXX=$MINGW-g++-posix \
      AR=$MINGW-ar "GL_LIB=-lopengl32 -static" >&2
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
    args+=(-DCMAKE_TOOLCHAIN_FILE="$TC" -DBUILD_BUNDLED_FFMPEG=ON
           "-DCMAKE_C_FLAGS=-isystem $cf" "-DCMAKE_CXX_FLAGS=-isystem $cf"
           "-DCMAKE_SHARED_LINKER_FLAGS=-static -static-libgcc -static-libstdc++ -L$cf/lib"
           "-DCMAKE_CXX_STANDARD_LIBRARIES=-lkernel32 -luser32 -lgdi32 -lwinspool -lshell32 -lole32 -loleaut32 -luuid -lcomdlg32 -ladvapi32 -lwinmm")
  fi
  cmake "${args[@]}" >&2
  # PPSSPP's Core/CMakeLists.txt runs Windows/git-version-gen.cmd whenever WIN32 is set. It only
  # writes the standalone PPSSPPWindows.exe version resource (not used by the libretro core) and
  # can't run on a Linux host, so drop that one step from the generated build file.
  [ "$1" = win ] && sed -i 's#&& [^ ]*/Windows/git-version-gen\.cmd PPSSPPWindows##' "$2/build.ninja"
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
    # Azahar's CMakeLists assumes MXE when cross-building on Linux: it reads LIBTYPE and sets
    # CMAKE_AR to x86_64-w64-mingw32.static-gcc-ar. Give it that name for Debian's gcc-ar.
    local mxe="$CORE_BUILD/mxe-shim"; mkdir -p "$mxe"
    ln -sf "$(command -v $MINGW-gcc-ar-posix)" "$mxe/$MINGW.static-gcc-ar"
    export PATH="$mxe:$PATH"
    args+=(-DCMAKE_TOOLCHAIN_FILE="$TC" -DLIBTYPE=STATIC
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

# ---------------------------------------------------------------------------------------------
# Checks and the lock file.
LOADER_WIN="$CORE_BUILD/loadcore.exe"; LOADER_LINUX="$CORE_BUILD/loadcore"
loaders() {
  mkdir -p "$CORE_BUILD"
  [ -x "$LOADER_WIN" ] || $MINGW-gcc-posix -O2 -static -o "$LOADER_WIN" "$TOOLS/loadcore.c"
  [ -x "$LOADER_LINUX" ] || gcc -O2 -o "$LOADER_LINUX" "$TOOLS/loadcore.c" -ldl
}

# Windows system DLLs a core may import. Anything else (libstdc++-6, libgcc_s_seh-1,
# libwinpthread-1, zlib1...) would have to be shipped, so it fails the build.
SYSTEM_DLLS='^(kernel32|user32|gdi32|advapi32|shell32|shlwapi|ole32|oleaut32|msvcrt|ws2_32|iphlpapi|opengl32|winmm|winhttp|crypt32|bcrypt|dbghelp|version|setupapi|imm32|dwmapi|uxtheme|comdlg32|comctl32|psapi|userenv|ntdll|secur32|dxgi|d3d11|d3d12|d3dcompiler_47|xinput1_4|hid|mfplat|mfreadwrite|mf|mfuuid|avrt|synchronization|api-ms-win-.*)\.dll$'

check_and_record() { # target name file
  local t="$1" n="$2" f="$3" imports="" load=""
  if [ "$t" = win ]; then
    imports="$($MINGW-objdump -p "$f" | awk '/DLL Name:/{print $3}' | sort -fu | tr '\n' ' ')"
    local d; for d in $imports; do
      echo "$d" | tr 'A-Z' 'a-z' | grep -Eq "$SYSTEM_DLLS" || { echo "$n: imports non-system DLL $d" >&2; exit 1; }
    done
    if [ "$WINE_CHECK" = 1 ]; then load="$(WINEDEBUG=-all timeout 300 wine "$LOADER_WIN" "$f" 2>&1 | tr -d '\r' | grep -E '^(OK|LOAD|MISSING)' | head -1 || true)"
    else load="(Wine check skipped)"; fi
  else
    imports="$(readelf -d "$f" | awk -F'[][]' '/NEEDED/{print $2}' | tr '\n' ' ')"
    load="$("$LOADER_LINUX" "$f" 2>&1 || true)"
  fi
  case "$load" in OK*|"(Wine check skipped)") ;; *) echo "$n ($t): load test failed: $load" >&2; exit 1;; esac
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
  { grep '^#' "$tmp"; grep -v '^#' "$tmp" | sort -t$'\t' -k1,1 -k2,2; } > "$LOCK"; rm -f "$tmp"
}

build_one() { # name target
  local n="$1" t="$2" r url commit out bld f dst start
  r="$(row "$n")"; url="$(field "$r" 2)"; commit="$(field "$r" 3)"; out="$(field "$r" 5)"
  case "$n" in ppsspp|azahar) fetch "$n" "$url" "$commit" submodules ;; *) fetch "$n" "$url" "$commit" ;; esac
  bld="$CORE_BUILD/$n-$t"; start=$(date +%s)
  log "$n ($t) from $commit"
  f="$("build_$n" "$t" "$bld" | tail -1)"
  [ -n "$f" ] && [ -f "$f" ] || { echo "$n ($t): build gave no library" >&2; exit 1; }
  if [ "$t" = win ]; then dst="$OUT_WIN/$out.dll"; mkdir -p "$OUT_WIN"; $MINGW-strip --strip-unneeded -o "$dst" "$f"
  else dst="$OUT_LINUX/$out.so"; mkdir -p "$OUT_LINUX"; strip --strip-unneeded -o "$dst" "$f"; fi
  check_and_record "$t" "$n" "$dst"
  if [ "$n" = ppsspp ]; then
    if [ "$t" = win ]; then ppsspp_assets "$OUT_WIN"; else ppsspp_assets "$OUT_LINUX"; fi
    echo "  PPSSPP system folder written next to the core (PPSSPP/ and PPSSPP.zip)"
  fi
  echo "  time:    $(( $(date +%s) - start )) s"
  [ "$KEEP_BUILD" = 1 ] || rm -rf "$bld"
}

# ---------------------------------------------------------------------------------------------
TARGETS=(win linux); LIST=()
for a in "$@"; do
  case "$a" in
    --win) TARGETS=(win) ;; --linux) TARGETS=(linux) ;; --both) TARGETS=(win linux) ;;
    -h|--help) sed -n 2,26p "$0"; exit 0 ;;
    *) row "$a" >/dev/null; LIST+=("$a") ;;
  esac
done
[ ${#LIST[@]} -gt 0 ] || LIST=(mgba gpsp melonds pcsx mupen ppsspp azahar)

for tool in cmake ninja git make nasm zip $MINGW-gcc-posix $MINGW-g++-posix; do
  command -v "$tool" >/dev/null || { echo "Missing tool: $tool (sudo apt-get install ...)" >&2; exit 1; }
done
[[ " ${TARGETS[*]} " == *" win "* && "$WINE_CHECK" = 1 ]] && { command -v wine >/dev/null || { echo "Missing wine (or WINE_CHECK=0)" >&2; exit 1; }; }
loaders
for n in "${LIST[@]}"; do for t in "${TARGETS[@]}"; do build_one "$n" "$t"; done; done
log "Done. Lock file: $LOCK"
