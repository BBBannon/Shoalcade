#!/usr/bin/env bash
# Shoalcade for Windows: licence texts and SOURCE.txt for the app folder. Part of Shoalcade, GPL-3.0-or-later.
# Called by tools/build_windows_release.sh:
#   licences.sh ROOT IMG MODULES JDK_VER SDL_KIT SDL_TGZ_SHA SRC_COMMIT SRC_DIRTY VERSION SKIKO VPK_VER NAME
#
# Writes IMG/LICENSE.txt (GPL-3.0, the licence of Shoalcade as a whole), IMG/SOURCE.txt (where the Corresponding Source
# of every part is: GPLv3 section 6(d)), and IMG/licences/ (every part's licence text, with a README.txt index).
# Java runtime notices stay where jlink puts them: runtime/legal/.
# Vendored here: licences/Velopack-LICENSE.txt (velopack/velopack tag 1.2.161, MIT) and licences/Skia-LICENSE.txt
# (google/skia LICENSE, BSD-3-Clause; Skia is inside Skiko's DLL).
set -euo pipefail
ROOT=$1 IMG=$2 MODULES=$3 JDK_VER=$4 SDL_KIT=$5 SDL_SHA=$6 SRC_COMMIT=$7 SRC_DIRTY=$8 VERSION=$9
SKIKO=${10} VPK_VER=${11} NAME=${12}
CORE_SRC=${SHOALCADE_CORE_SRC:-$HOME/deps/core-src}
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) WINHOST=1 ;; *) WINHOST=0 ;; esac
[ $WINHOST = 0 ] || CORE_SRC=$(cygpath -m "$CORE_SRC") # native git gets C:/... (path conversion is off)
# Standard licence texts: Debian's on Linux; on Windows the same texts vendored in windows/installer/licences/common.
[ $WINHOST = 1 ] && CL_DEFAULT=$ROOT/windows/installer/licences/common || CL_DEFAULT=/usr/share/common-licenses
CL=${SHOALCADE_COMMON_LICENSES:-$CL_DEFAULT}
L=$IMG/licences
INST=$ROOT/windows/installer
TP=$ROOT/windows/player/third_party
mkdir -p "$L/cores" "$L/mingw"

need() { [ -f "$1" ] || { echo "licences.sh: missing $1" >&2; exit 1; }; }
put() { need "$1"; cp "$1" "$2"; }

# Full licence texts.
put "$ROOT/LICENSE" "$IMG/LICENSE.txt"
put "$ROOT/LICENSE" "$L/GPL-3.0.txt"
put "$CL/GPL-2" "$L/GPL-2.0.txt"
put "$CL/LGPL-2.1" "$L/LGPL-2.1.txt"
put "$CL/LGPL-3" "$L/LGPL-3.0.txt"
put "$CL/MPL-2.0" "$L/MPL-2.0.txt"
put "$CL/Apache-2.0" "$L/Apache-2.0.txt"
put "$ROOT/THIRD_PARTY_NOTICES.md" "$L/THIRD_PARTY_NOTICES.md"

# Each core's own licence file(s), from the exact source it was built from.
put "$CORE_SRC/mgba/LICENSE" "$L/cores/mGBA-LICENSE.txt"
put "$CORE_SRC/gpsp/COPYING" "$L/cores/gpSP-COPYING.txt"
put "$CORE_SRC/melonds/LICENSE" "$L/cores/melonDS-DS-LICENSE.txt"
put "$CORE_SRC/pcsx/COPYING" "$L/cores/PCSX-ReARMed-COPYING.txt"
put "$CORE_SRC/mupen/LICENSE" "$L/cores/Mupen64Plus-Next-LICENSE.txt"
put "$CORE_SRC/ppsspp/LICENSE.TXT" "$L/cores/PPSSPP-LICENSE.txt"
put "$CORE_SRC/azahar/license.txt" "$L/cores/Azahar-license.txt"
for f in deps/libchdr/LICENSE deps/lightrec/COPYING deps/lightning/COPYING deps/libretro-common/LICENSE; do
  [ -f "$CORE_SRC/pcsx/$f" ] && cp "$CORE_SRC/pcsx/$f" "$L/cores/PCSX-ReARMed-$(echo "$f" | cut -d/ -f2)-$(basename "$f").txt"
done
[ -d "$CORE_SRC/ppsspp/ffmpeg" ] && for f in COPYING.LGPLv2.1 LICENSE.md LICENSE; do
  [ -f "$CORE_SRC/ppsspp/ffmpeg/$f" ] && cp "$CORE_SRC/ppsspp/ffmpeg/$f" "$L/cores/PPSSPP-ffmpeg-$f.txt"
done

# The player's parts.
put "$SDL_KIT/LICENSE.txt" "$L/SDL3-LICENSE.txt"
put "$TP/imgui/LICENSE.txt" "$L/DearImGui-LICENSE.txt"
put "$TP/nlohmann/LICENSE.MIT" "$L/nlohmann-json-LICENSE.txt"
put "$TP/fonts/OFL.txt" "$L/AtkinsonHyperlegible-OFL.txt"
sed -n '1,/\*\//p' "$TP/libretro/libretro.h" > "$L/libretro-h-LICENSE.txt"
for h in stb_image_write.h stb_image.h; do
  sed -n '/^This software is available under 2 licenses/,$p' "$TP/stb/$h" > "$L/stb-LICENSE.txt"
done
[ -s "$L/stb-LICENSE.txt" ] || { echo "licences.sh: stb licence text not found" >&2; exit 1; }
# MinGW-w64: libstdc++/libgcc (GPL-3.0 + GCC Runtime Library Exception) and winpthreads are linked into the exes/DLLs.
if [ $WINHOST = 1 ]; then
  # Built on Windows: the player and Shoalcade.exe carry MSYS2's MinGW-w64 runtime (the cores, built on Linux, carry
  # Debian's; the same licences).
  MSYS2=$(cygpath -u "${SHOALCADE_MSYS2:-C:/msys64}")
  ML=$MSYS2/mingw64/share/licenses
  for f in libstdc++/COPYING.RUNTIME libstdc++/COPYING3 crt/COPYING.MinGW-w64-runtime.txt winpthreads/COPYING; do
    need "$ML/$f"; done
  MINGW_GCC_TXT=mingw/gcc-msys2-licence.txt MINGW_RT_TXT=mingw/mingw-w64-msys2-licence.txt
  cat "$ML/libstdc++/COPYING.RUNTIME" "$ML/libstdc++/COPYING3" > "$L/$MINGW_GCC_TXT"
  cat "$ML/crt/COPYING.MinGW-w64-runtime.txt" "$ML/winpthreads/COPYING" > "$L/$MINGW_RT_TXT"
  MSYS2_PKGS=$("$MSYS2/usr/bin/pacman" -Q mingw-w64-x86_64-gcc mingw-w64-x86_64-crt mingw-w64-x86_64-winpthreads |
               tr -d '\r' | paste -sd, - | sed 's/,/, /g')
  BUILT_ON=$'on Windows with MSYS2\'s MinGW-w64\n(the cores: on Linux)'
else
  put /usr/share/doc/gcc-mingw-w64-base/copyright "$L/mingw/gcc-mingw-w64-copyright.txt"
  put /usr/share/doc/mingw-w64-common/copyright "$L/mingw/mingw-w64-runtime-copyright.txt"
  MINGW_GCC_TXT=mingw/gcc-mingw-w64-copyright.txt MINGW_RT_TXT=mingw/mingw-w64-runtime-copyright.txt
  BUILT_ON="on Linux with MinGW-w64"
fi

# The app's Java parts.
put "$INST/licences/Skia-LICENSE.txt" "$L/Skia-LICENSE.txt"
put "$INST/licences/Velopack-LICENSE.txt" "$L/Velopack-LICENSE.txt"

# Licence/notice files the Java libraries carry inside their own jars (e.g. SLF4J's MIT text, JNA's dual licence).
mkdir -p "$L/java"
for j in "$IMG"/app/*.jar; do
  for f in $(unzip -Z1 "$j" 2>/dev/null | grep -i -E '^META-INF/(LICENSE|NOTICE|COPYING)[^/]*$' || true); do
    unzip -p "$j" "$f" > "$L/java/$(basename "$j" .jar)-$(basename "$f" .txt).txt"
  done
done

PPSSPP_DIRTY=$(git -C "$CORE_SRC/ppsspp" status --porcelain --untracked-files=no | wc -l)
# A licence-only tree (tools/fetch_core_licences.sh: sparse, never patched): the build fix is then known from cores.lock,
# and the files it changes from the patches themselves.
if [ "$(git -C "$CORE_SRC/ppsspp" config --bool core.sparseCheckout || true)" = true ] &&
   grep -v '^#' "$ROOT/windows/cores/cores.lock" | awk -F'\t' '$1=="ppsspp" && $2=="win"' | grep -q 'ppsspp-patches'; then
  PPSSPP_DIRTY=$(cat "$ROOT"/tools/ppsspp-patches/*.patch | grep -c '^--- a/')
fi

cat > "$L/README.txt" <<EOF
$NAME $VERSION for Windows: licences
====================================

Shoalcade is free software under the GNU General Public License version 3 (LICENSE.txt, also GPL-3.0.txt).
Where to get the source code of every part: SOURCE.txt (next to Shoalcade.exe).
No BIOS, firmware, keys or games are included.

Part                                       Licence                                  Text here
----                                       -------                                  ---------
Shoalcade (app, player, launcher)          GPL-3.0-or-later                         GPL-3.0.txt
mGBA (GB, GBC, GBA)                        MPL-2.0                                  MPL-2.0.txt, cores/mGBA-LICENSE.txt
gpSP (GBA link play)                       GPL-2.0-or-later                         cores/gpSP-COPYING.txt
melonDS DS + melonDS (DS)                  GPL-3.0                                  cores/melonDS-DS-LICENSE.txt
PCSX ReARMed (PS1)                         GPL-2.0-or-later; parts: lightrec        cores/PCSX-ReARMed-*.txt, LGPL-2.1.txt,
                                           LGPL-2.1+, GNU lightning LGPL-3.0+,      LGPL-3.0.txt
                                           libchdr BSD-3, libretro-common MIT
Mupen64Plus-Next (N64)                     GPL-3.0 as combined                      cores/Mupen64Plus-Next-LICENSE.txt
PPSSPP (PSP) + its system files            GPL-2.0-or-later (PSPSDK parts BSD;      cores/PPSSPP-LICENSE.txt,
  (app/system/PPSSPP)                      FFmpeg LGPL-2.1+; fonts: see             app/system/PPSSPP/LICENSE.TXT
                                           THIRD_PARTY_NOTICES.md)
Azahar (3DS) + Shoalcade patches           GPL-3.0-or-later (parts GPL-2.0+)        cores/Azahar-license.txt
SDL 3.2.24                                 zlib                                     SDL3-LICENSE.txt
Dear ImGui 1.92.4                          MIT                                      DearImGui-LICENSE.txt
nlohmann/json 3.11.3                       MIT                                      nlohmann-json-LICENSE.txt
stb_image, stb_image_write                 MIT or public domain                     stb-LICENSE.txt
libretro.h                                 MIT                                      libretro-h-LICENSE.txt
Atkinson Hyperlegible font                 SIL OFL 1.1                              AtkinsonHyperlegible-OFL.txt
MinGW-w64 GCC runtime (libstdc++, libgcc)  GPL-3.0 + GCC Runtime Library Exception  $MINGW_GCC_TXT
MinGW-w64 runtime, winpthreads             ZPL / MIT-style (see file)               $MINGW_RT_TXT
Java runtime: Eclipse Temurin $JDK_VER     GPL-2.0 with Classpath Exception         runtime/legal/ (one folder per module)
Compose Multiplatform, Kotlin, kotlinx,    Apache-2.0                               Apache-2.0.txt
  AndroidX, OkHttp/Okio, JetBrains
  annotations, Skiko $SKIKO
Skia (inside Skiko's DLL)                  BSD-3-Clause                             Skia-LICENSE.txt
JNA 5.15.0                                 Apache-2.0 (or LGPL-2.1, our choice:     Apache-2.0.txt
                                           Apache-2.0)
JSON-java (org.json) 20240303              Public domain                            -
JmDNS 3.5.9 (link play: find hosts)        Apache-2.0                               Apache-2.0.txt
SLF4J API 2.0.7 (used by JmDNS)            MIT                                      java/slf4j-api-2.0.7-LICENSE.txt
Velopack $VPK_VER (installer, Update.exe)  MIT                                      Velopack-LICENSE.txt
Licence files found inside the Java jars:  (as each says)                          java/

Java libraries in app/ (Maven coordinates):
$(sed 's/^/  /' "$MODULES")
EOF

{
cat <<EOF
$NAME $VERSION for Windows: where the source code is
$(printf '%*s' 60 '' | tr ' ' '=')

This program is free software: GNU General Public License version 3 (LICENSE.txt). The files in this folder are object
code. As GPLv3 section 6(d) allows, the Corresponding Source is offered for download, at no charge, from the places
below, each pinned to the exact commit these files were built from. If any of them stops offering it while this
release is offered, ask at https://github.com/BBBannon/Shoalcade/issues and the Shoalcade project will provide it.

How it was built: tools/build_windows_cores.sh (the cores) and tools/build_windows_release.sh (everything else) in the
Shoalcade source, $BUILT_ON. No part needs a key or signature to be rebuilt or replaced: $(
[ "${SC_SIGNED:-0}" = 1 ] && echo "the files are
code-signed only so Windows knows who published them; your own unsigned build of any part runs the same." ||
echo "Shoalcade for
Windows is not signed, and any part can be swapped for your own build.")

Shoalcade (the app, ShoalcadePlayer.exe, Shoalcade.exe, the build scripts and the core patches)
  https://github.com/BBBannon/Shoalcade  commit $SRC_COMMIT
EOF
if [ "$SRC_DIRTY" != 0 ]; then
  echo "  !! TEST BUILD: built with $SRC_DIRTY changed file(s) not yet committed. Do not publish this build."
fi
echo
echo "Emulator cores (app/cores), from windows/cores/cores.lock"
grep -v '^#' "$ROOT/windows/cores/cores.lock" | awk -F'\t' '$2=="win"{printf "  %-30s %s  commit %s\n    licence: %s   SHA-256 %s\n", $6, $3, $4, $5, $8}'
for c in mgba gpsp melonds pcsx mupen ppsspp azahar; do
  # Submodule commits; if git can't walk them (Mupen64Plus-Next has a gitlink with no .gitmodules entry), the
  # commits recorded in the tree itself.
  sub=$( { git -C "$CORE_SRC/$c" submodule status --recursive 2>/dev/null ||
           git -C "$CORE_SRC/$c" ls-tree -r HEAD | awk '$2=="commit"{print " " $3 " " $4}'; } |
         awk '{sub(/^[-+ U]/,"",$1); print "      " $2 "  " $1}')
  [ -n "$sub" ] && { echo "    $c submodules (path  commit):"; echo "$sub"; }
done
cat <<EOF
    melonDS DS fetches melonDS itself at JesseTG/melonDS commit 16f127dbb587a73371827ce79689c5d237c4b59b; its other
      dependencies are pinned by commit/tag inside melonDS DS at the commit above.
    Patches applied (in the Shoalcade source): tools/azahar-patches/*.patch (Azahar),
      tools/ppsspp-patches/*.patch (PPSSPP, MinGW build fix). SHA-256:
$(cd "$ROOT" && sha256sum tools/azahar-patches/*.patch tools/ppsspp-patches/*.patch | sed 's/^/      /')

3DS check scene (n3ds/check.3dsx inside the app, used by the 3DS check): tools/n3ds-check in the Shoalcade source at
  the commit above (built with devkitARM; based on devkitPro 3ds-examples, Public Domain; libctru and citro3d, zlib:
  https://github.com/devkitPro/libctru and https://github.com/devkitPro/citro3d).

PPSSPP system files (app/system/PPSSPP): the assets/ folder of hrydgard/ppsspp at the PPSSPP commit above
  (debugger/ left empty), as tools/build_windows_cores.sh copies it.

SDL 3.2.24 (app/player/SDL3.dll): https://github.com/libsdl-org/SDL/releases/tag/release-3.2.24
  (SDL3-devel-3.2.24-mingw.tar.gz, SHA-256 $SDL_SHA; source in the same release)
Dear ImGui 1.92.4, nlohmann/json 3.11.3, stb, libretro.h, Atkinson Hyperlegible: copied unchanged into the Shoalcade
  source (windows/player/third_party/README.md lists each one's origin).
MinGW-w64 runtime (GCC 14, linked into the .exe/.dll files): https://www.mingw-w64.org and https://gcc.gnu.org
  (Debian packages gcc-mingw-w64 and mingw-w64; source: https://sources.debian.org/src/gcc-mingw-w64/ and
  https://sources.debian.org/src/mingw-w64/)$([ $WINHOST = 1 ] && printf '%s\n' "; for ShoalcadePlayer.exe and Shoalcade.exe,
  built on Windows: MSYS2 packages $MSYS2_PKGS (source: https://github.com/msys2/MINGW-packages)")

Java runtime (runtime/): Eclipse Temurin $JDK_VER, reduced with jlink (modules: see runtime/release).
  Source: https://github.com/adoptium/jdk17u/tree/jdk-$JDK_VER
Java libraries (app/*.jar): each from Maven Central, where every one has a -sources.jar next to it:
$(sed 's/^/  /' "$MODULES")
  org.jetbrains.skiko:skiko-awt-runtime-windows-x64:$SKIKO (its DLL and ICU data are unpacked into app/)
  Skiko: https://github.com/JetBrains/skiko/tree/v$SKIKO
Velopack $VPK_VER (Update.exe, Setup.exe): https://github.com/velopack/velopack/tree/$VPK_VER
EOF
if [ "$PPSSPP_DIRTY" != 0 ]; then echo; echo "(The PPSSPP tree carries the MinGW build fix above: $PPSSPP_DIRTY changed file(s), as expected.)"; fi
} > "$IMG/SOURCE.txt"
echo "  licences: $(find "$L" -type f | wc -l) files; SOURCE.txt $(wc -l < "$IMG/SOURCE.txt") lines"
