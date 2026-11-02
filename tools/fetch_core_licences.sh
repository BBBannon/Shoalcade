#!/usr/bin/env bash
# Shoalcade: the core source files windows/installer/licences.sh reads, without full clones. Part of Shoalcade,
# GPL-3.0-or-later.
#
#   tools/fetch_core_licences.sh        (into SHOALCADE_CORE_SRC, default ~/deps/core-src; Linux or Windows Git Bash)
#
# For each Windows core in windows/cores/cores.lock: a shallow, blobless fetch of the pinned commit with a sparse
# checkout of just its licence files (+ .gitmodules), so the index is the whole tree and `git submodule status` reads
# as it does in a full checkout. PPSSPP, Azahar, Dolphin and PCEE2, whose submodules tools/build_windows_cores.sh initialises
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
    *) echo "unknown core $1" >&2; exit 1 ;;
  esac
}

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
  case "$core" in ppsspp|azahar|dolphin|pcee2) subs "$d" "${want[@]}" ;; esac
  for f in "${want[@]}"; do
    if [ -f "$d/$f" ]; then echo "  have $f"; else echo "  none $f (not in the tree at this commit)"; fi
  done
done < <(grep -v '^#' "$LOCK")
