#!/usr/bin/env bash
# Builds the 3DS check scene (tools/n3ds-check, D151) with devkitPro's devkitARM and puts it in the app's assets.
# Needs devkitPro (Windows installer, "3DS Development") in C:\devkitPro.
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$HERE/tools/n3ds-check"
OUT="$HERE/android/app/src/main/assets/n3ds/check.3dsx"
MSYS=/c/devkitPro/msys2/usr/bin/bash.exe
[ -x "$MSYS" ] || { echo "devkitPro not found in C:\devkitPro"; exit 1; }
MSYS2_PATH_TYPE=inherit "$MSYS" -lc "export DEVKITPRO=/opt/devkitpro DEVKITARM=/opt/devkitpro/devkitARM; cd '$(cygpath -m "$SRC")' && make"
mkdir -p "$(dirname "$OUT")"
cp "$SRC/shoalcade-3ds-check.3dsx" "$OUT"
echo "Built $OUT ($(du -h "$OUT" | cut -f1))"
