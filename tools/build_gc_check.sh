#!/usr/bin/env bash
# Builds the GameCube / Wii check scene (tools/gc-check, D222 D-7) with devkitPro's devkitPPC + libogc: the same
# source as tools/gc-check/gc-check.dol (GameCube) and tools/gc-check/wii-check.dol (Wii). The Windows app hands the
# right one to the player for the advisory GameCube / Wii speed check (as the 3DS check's tools/n3ds-check).
# Windows: devkitPro installer ("GameCube" / "Wii" development) in C:\devkitPro. Linux/macOS: devkitPro pacman with
# gamecube-dev and wii-dev, DEVKITPRO set (default /opt/devkitpro).
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$HERE/tools/gc-check"
MSYS=/c/devkitPro/msys2/usr/bin/bash.exe
for k in gc wii; do
  if [ -x "$MSYS" ]; then
    MSYSTEM=MSYS "$MSYS" -lc "export DEVKITPRO=/opt/devkitpro DEVKITPPC=/opt/devkitpro/devkitPPC; cd '$(cygpath -m "$SRC")' && make -f Makefile.$k"
  else
    export DEVKITPRO="${DEVKITPRO:-/opt/devkitpro}"
    export DEVKITPPC="${DEVKITPPC:-$DEVKITPRO/devkitPPC}"
    [ -d "$DEVKITPPC" ] || { echo "devkitPro not found (C:\\devkitPro on Windows, \$DEVKITPRO elsewhere)"; exit 1; }
    make -C "$SRC" -f Makefile.$k
  fi
  echo "Built $SRC/$k-check.dol ($(du -h "$SRC/$k-check.dol" | cut -f1))"
done
