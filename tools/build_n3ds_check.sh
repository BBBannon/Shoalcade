#!/usr/bin/env bash
# Builds the 3DS check scene (tools/n3ds-check, D151) with devkitPro's devkitARM and puts it in the app's assets.
# Windows: devkitPro installer ("3DS Development") in C:\devkitPro. Linux/macOS: devkitPro pacman with 3ds-dev,
# DEVKITPRO set (default /opt/devkitpro).
# N3DS_CHECK_OUT=<file> writes the scene there instead of the app's assets (to measure a new scene before it ships).
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$HERE/tools/n3ds-check"
OUT="${N3DS_CHECK_OUT:-$HERE/android/app/src/main/assets/n3ds/check.3dsx}"
MSYS=/c/devkitPro/msys2/usr/bin/bash.exe
if [ -x "$MSYS" ]; then
  MSYS2_PATH_TYPE=inherit "$MSYS" -lc "export DEVKITPRO=/opt/devkitpro DEVKITARM=/opt/devkitpro/devkitARM; cd '$(cygpath -m "$SRC")' && make"
else
  export DEVKITPRO="${DEVKITPRO:-/opt/devkitpro}"
  export DEVKITARM="${DEVKITARM:-$DEVKITPRO/devkitARM}"
  [ -d "$DEVKITARM" ] || { echo "devkitPro not found (C:\\devkitPro on Windows, \$DEVKITPRO elsewhere)"; exit 1; }
  make -C "$SRC"
fi
mkdir -p "$(dirname "$OUT")"
cp "$SRC/shoalcade-3ds-check.3dsx" "$OUT"
echo "Built $OUT ($(du -h "$OUT" | cut -f1))"
