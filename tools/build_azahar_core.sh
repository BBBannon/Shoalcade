#!/usr/bin/env bash
# Builds Shoalcade's own 3DS core: Azahar's libretro core for Android arm64,
# with the built-in key blob switched OFF (D153: no bundled keys, ever).
# Recipe follows Azahar's own CI (.github/workflows/libretro.yml, job libretro-android).
#
# Usage: bash tools/build_azahar_core.sh
# Source: ~/Android/src/azahar-<tag> (clone with --recurse-submodules).
# Output: android/app/src/main/jniLibs/arm64-v8a/libazahar_libretro_android.so
set -euo pipefail

TAG="${AZAHAR_TAG:-2126.2}"
SRC="${AZAHAR_SRC:-$HOME/Android/src/azahar-$TAG}"
SDK="${ANDROID_SDK_ROOT:-$HOME/Android/sdk}"
NDK="$SDK/ndk/29.0.14206865"
CMAKE_DIR="$SDK/cmake/3.30.3/bin"
BUILD="$SRC/build/android-arm64-v8a"
HERE="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$HERE/android/app/src/main/jniLibs/arm64-v8a/libazahar_libretro_android.so"

# Windows (Git Bash): CMake is a native .exe, so hand it C:/style paths.
if command -v cygpath >/dev/null; then
  SRC="$(cygpath -m "$SRC")"; NDK="$(cygpath -m "$NDK")"; CMAKE_DIR="$(cygpath -m "$CMAKE_DIR")"; BUILD="$SRC/build/android-arm64-v8a"
fi
NINJA="$CMAKE_DIR/ninja"; [ -f "$NINJA.exe" ] && NINJA="$NINJA.exe"

[ -d "$SRC" ] || { echo "No Azahar source at $SRC"; exit 1; }
[ -d "$NDK" ] || { echo "NDK 29.0.14206865 missing"; exit 1; }

# Shoalcade's own changes to Azahar (tools/azahar-patches/*.patch, each explained inside). Applied once.
for P in "$HERE"/tools/azahar-patches/*.patch; do
  [ -e "$P" ] || continue
  if git -C "$SRC" apply --reverse --check "$P" 2>/dev/null; then echo "Patch already in: $(basename "$P")"
  else git -C "$SRC" apply "$P" && echo "Patched: $(basename "$P")"; fi
done

"$CMAKE_DIR/cmake" -G Ninja -DCMAKE_MAKE_PROGRAM="$NINJA" \
  -DENABLE_LIBRETRO=ON -DENABLE_BUILTIN_KEYBLOB=OFF \
  -DCMAKE_BUILD_TYPE=Release \
  -DANDROID_PLATFORM=android-21 -DANDROID_STL=c++_static -DANDROID_ABI=arm64-v8a \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -S "$SRC" -B "$BUILD"
"$CMAKE_DIR/cmake" --build "$BUILD" --target azahar_libretro --config Release

SO="$(find "$BUILD" -name 'azahar_libretro*.so' | head -1)"
[ -n "$SO" ] || { echo "Build finished but no azahar_libretro .so found"; exit 1; }
STRIP="$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/bin | head -1)/llvm-strip"
"$STRIP" -s "$SO" -o "$OUT"

# Proof the key blob is off: the CMake cache must say OFF.
grep -q "ENABLE_BUILTIN_KEYBLOB:BOOL=OFF" "$BUILD/CMakeCache.txt" && echo "Key blob: OFF (checked)"
echo "Built $OUT ($(du -h "$OUT" | cut -f1)) from Azahar $TAG $(git -C "$SRC" rev-parse --short HEAD)"
