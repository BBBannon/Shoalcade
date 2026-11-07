#!/usr/bin/env bash
# Shoalcade: the core source files windows/installer/licences.sh reads, without full clones. Part of Shoalcade,
# GPL-3.0-or-later.
#
#   tools/fetch_core_licences.sh        (into SHOALCADE_CORE_SRC, default ~/deps/core-src; Linux or Windows Git Bash)
#
# For each Windows core in windows/cores/cores.lock: a shallow, blobless fetch of the pinned commit with a sparse
# checkout of just its licence files (+ .gitmodules), so the index is the whole tree and `git submodule status` reads
# as it does in a full checkout. PPSSPP, Azahar, Dolphin, PCEE2 and Xenia, whose submodules tools/build_windows_cores.sh initialises
# (--recursive), get every submodule the same way, at the commit the tree records: their licence files (PPSSPP's
# ffmpeg) and the nested submodule list in SOURCE.txt are then as in the build tree. A core folder that is a full
# checkout (a real build tree) is left alone.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CORE_SRC=${SHOALCADE_CORE_SRC:-$HOME/deps/core-src}
LOCK=$ROOT/windows/cores/cores.lock

# The files licences.sh reads, per core folder (the pcsx deps and ffmpeg extras are optional there: [ -f ]).
files() {
  case "$1" in
    mgba) echo LICENSE ;;
    gpsp) echo COPYING ;;
    melonds) echo LICENSE ;;
    pcsx) echo COPYING deps/libchdr/LICENSE deps/lightrec/COPYING deps/lightning/COPYING deps/libretro-common/LICENSE ;;
    mupen) echo LICENSE ;;
    ppsspp) echo LICENSE.TXT ffmpeg/COPYING.LGPLv2.1 ffmpeg/LICENSE.md ffmpeg/LICENSE ;;
    azahar) echo license.txt ;;
    dolphin) echo COPYING Externals/licenses.md Data/Sys/GC/font-licenses.txt ;;
    lrps2) echo COPYING.GPLv3 3rdparty/glslang/glslang/LICENSE.txt 3rdparty/vixl/LICENCE 3rdparty/vulkan-headers/LICENSE.md ;;
    pcee2) echo COPYING.GPLv3 THIRD_PARTY_LICENSES.html ;;
    # Xenia: its licence and every library linked or compiled into the DLL (from the build's link line and headers).
    # Left out: build-only tools (DirectXShaderCompiler, llvm, clang-format, binutils) and macOS/ARM-only parts.
    xenia) local t=third_party
           echo LICENSE $t/FFmpeg/LICENSE.md $t/FFmpeg/COPYING.LGPLv2.1 $t/mspack/COPYING.LIB $t/libusb/COPYING \
                $t/mesa/docs/license.rst $t/mesa/licenses/MIT $t/mesa/licenses/BSL-1.0 $t/mesa/licenses/Apache-2.0 \
                $t/mesa/licenses/SGI-B-2.0 $t/DirectX-Headers/LICENSE $t/FidelityFX-CAS/LICENSE.txt \
                $t/FidelityFX-FSR/license.txt $t/SPIRV-Headers/LICENSE $t/SPIRV-Tools/LICENSE $t/Vulkan-Headers/LICENSE.md \
                $t/VulkanMemoryAllocator/LICENSE.txt $t/aes_128/LICENSE $t/asio/LICENSE_1_0.txt \
                $t/capstone/LICENSES/LICENSE.TXT $t/capstone/LICENSES/LICENSE_BSD_3_CLAUSE.txt \
                $t/capstone/LICENSES/LICENSE_LLVM.TXT $t/cereal/LICENSE $t/cxxopts/LICENSE $t/date/LICENSE.txt \
                $t/disruptorplus/LICENSE.txt $t/fmt/LICENSE $t/fxaa/LICENSE $t/glslang/LICENSE.txt $t/half/LICENSE.txt \
                $t/imgui/LICENSE.txt $t/miniaudio/LICENSE $t/pugixml/LICENSE.md $t/rapidjson/license.txt $t/snappy/COPYING \
                $t/tomlplusplus/LICENSE $t/utfcpp/LICENSE $t/xbyak/COPYRIGHT $t/xxhash/LICENSE $t/zarchive/LICENSE \
                $t/zlib-ng/LICENSE.md $t/zstd/LICENSE $t/crypto/des/LICENSE $t/crypto/TinySHA1.hpp $t/crypto/sha256.h \
                $t/crypto/rijndael-alg-fst.h $t/dxbc/DXBCChecksum.h ;;
    *) echo "unknown core $1" >&2; exit 1 ;;
  esac
}

# `--list <core>`: print that core's files, one per line, and stop (windows/installer/licences.sh uses it).
if [ "${1:-}" = --list ]; then files "${2:?core}" | tr ' ' '\n' | grep .; exit 0; fi

# One repo at one commit: shallow, blobless, sparse (only .gitmodules + the given paths).
sparse() { # dir url commit path...
  local d=$1 url=$2 c=$3; shift 3
  if [ ! -e "$d/.git" ]; then
    mkdir -p "$d"
    git -C "$d" init -q
    git -C "$d" remote add origin "$url"
  fi
  git -C "$d" config core.longpaths true # Dolphin's Externals pass Windows' 260-character limit (ignored on Linux)
  git -C "$d" cat-file -e "$c^{commit}" 2>/dev/null || git -C "$d" fetch -q --depth 1 --filter=blob:none origin "$c"
  git -C "$d" config core.sparseCheckout true
  git -C "$d" config core.autocrlf false # the bytes as committed (Git for Windows defaults to CRLF)
  mkdir -p "$d/.git/info"
  printf '/%s\n' .gitmodules "$@" > "$d/.git/info/sparse-checkout"
  git -C "$d" -c advice.detachedHead=false checkout -q --detach "$c"
  git -C "$d" read-tree -mu HEAD # apply the (possibly changed) pattern list to the work tree
  [ "$(git -C "$d" rev-parse HEAD)" = "$c" ] || { echo "$d: HEAD is not $c" >&2; exit 1; }
}

# Every submodule of a sparse repo, recursively, as `git submodule update --init --recursive` would have them.
subs() { # dir wanted-path... (paths relative to dir; those inside a submodule are checked out there)
  local d=$1; shift
  [ -f "$d/.gitmodules" ] || return 0
  git -C "$d" submodule init -q 2>/dev/null || true
  local c p name url want w
  while read -r c p; do
    name=$(git config -f "$d/.gitmodules" --get-regexp '^submodule\..*\.path$' |
           awk -v p="$p" '$2 == p { sub(/^submodule\./, "", $1); sub(/\.path$/, "", $1); print $1; exit }')
    [ -n "$name" ] || continue # a gitlink with no .gitmodules entry: never cloned by submodule update either
    [ "$(git -C "$d" config "submodule.$name.update" || true)" != none ] || continue
    url=$(git -C "$d" config "submodule.$name.url" || true)
    [ -n "$url" ] || continue
    want=()
    for w in "$@"; do case "$w" in "$p"/*) want+=("${w#"$p"/}") ;; esac; done
    echo "  $d/$p @ ${c:0:12}"
    sparse "$d/$p" "$url" "$c" "${want[@]}"
    subs "$d/$p" "${want[@]}"
  done < <(git -C "$d" ls-tree -r HEAD | awk '$2 == "commit" { print $3, $4 }')
}

mkdir -p "$CORE_SRC"
while IFS=$'\t' read -r core target repo commit _; do
  [ "$target" = win ] || continue
  d=$CORE_SRC/$core
  if [ -e "$d/.git" ] && [ "$(git -C "$d" config --bool core.sparseCheckout || true)" != true ]; then
    echo "== $core: $d is a full checkout (build tree), left as it is"
    continue
  fi
  echo "== $core @ ${commit:0:12}"
  read -r -a want <<<"$(files "$core")"
  sparse "$d" "$repo" "$commit" "${want[@]}"
  case "$core" in ppsspp|azahar|dolphin|pcee2|xenia) subs "$d" "${want[@]}" ;; esac
  for f in "${want[@]}"; do
    if [ -f "$d/$f" ]; then echo "  have $f"; else echo "  none $f (not in the tree at this commit)"; fi
  done
done < <(grep -v '^#' "$LOCK")
