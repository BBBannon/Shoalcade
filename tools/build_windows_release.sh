#!/usr/bin/env bash
# Shoalcade for Windows: build the Windows app folder and its Velopack installer, from Linux.
# Part of Shoalcade, GPL-3.0-or-later. Report: docs/windows/phase8-installer.md.
#
#   tools/build_windows_release.sh [dev|release|both]      (default: both)
#
# Needs (all on this build machine): MinGW-w64 (x86_64-w64-mingw32-gcc/-posix, windres, strip, objdump), CMake + Ninja,
# the SDL3 MinGW kit (~/deps/SDL3-3.2.24), the Windows cores from tools/build_windows_cores.sh (~/deps/cores-win),
# the .NET SDK (~/sdk/dotnet), JDK 17 for Gradle (~/sdk/jdk17), python3, curl, zip. Downloads (SHA-256 pinned):
# Temurin 17.0.20.1+1 for Windows (its jmods) and for Linux (its jlink, which must be the same version), and the
# Velopack CLI (vpk) from NuGet.
#
# Steps:
#  1. Check every Windows core against windows/cores/cores.lock (bytes + SHA-256); stop on any difference.
#  2. Build ShoalcadePlayer.exe (Release, stripped) + SDL3.dll; check every exe/DLL only needs Windows' own DLLs.
#  3. jlink a Windows x64 Java runtime from the Windows jmods (Compose's module list, from the Gradle build).
#  4. Per variant (dev = "Shoalcade Dev", release = "Shoalcade"):
#     - Gradle (init script windows/installer/windows-jars.init.gradle; the app's build file is unchanged): the app
#       jar + runtime classpath with the Windows Skiko native; Skiko's DLL is unpacked beside the jars as Compose does
#     - Shoalcade.exe: windows/installer/launcher (MinGW C, Java in-process, Velopack hooks)
#     - icons from docs/assets/logo.svg (Dev: orange, as the phone's Dev icon)
#     - licences\ + SOURCE.txt (GPLv3 section 6(d) directions to the Corresponding Source)
#     - vpk [win] pack: Setup.exe, a portable zip and the full .nupkg, in dist/windows/<variant>/
#
# Velopack package ids are ShoalcadeApp / ShoalcadeDevApp, NOT "Shoalcade": Velopack installs to
# %LOCALAPPDATA%\<packId>\ and empties that folder on uninstall, and %LOCALAPPDATA%\Shoalcade\ is the app's data folder
# (saves). The build stops if the two could ever be the same folder.
#
# Memory: Gradle runs with --no-daemon and a 1 GB heap, never beside another Gradle build, and only with 1.2 GB free.
#
# On Windows itself (Git Bash): MSYS2 in C:\msys64 (SHOALCADE_MSYS2) with mingw-w64-x86_64-gcc, -binutils, -cmake,
# -ninja, zip and unzip; the player is built natively (no toolchain file), jlink is the Windows Temurin's own, vpk packs
# natively. Paths are C:/... (pwd -W, cygpath -m) and MSYS path conversion is off, so native programs get them as they are.
# Defaults there: JAVA_HOME ~/Android/jdk17/jdk-17.0.20.1+1, DOTNET_ROOT .testbed/dotnet or C:/Program Files/dotnet.
set -euo pipefail

VARIANTS=${1:-both}
case "$VARIANTS" in dev) VARIANTS=(dev) ;; release) VARIANTS=(release) ;; both) VARIANTS=(dev release) ;;
  *) echo "usage: $0 [dev|release|both]" >&2; exit 2 ;; esac

case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) WINHOST=1 ;; *) WINHOST=0 ;; esac
if [ $WINHOST = 1 ]; then
  MSYS2=$(cygpath -u "${SHOALCADE_MSYS2:-C:/msys64}")
  # MSYS2's MinGW-w64 first (not devkitPro's MSYS2 or Git's own tools); its usr/bin last, for zip.
  export PATH="$MSYS2/mingw64/bin:$PATH:$MSYS2/usr/bin" MSYS_NO_PATHCONV=1
  ROOT=$(cd "$(dirname "$0")/.." && pwd -W)
  DEPS=$(cygpath -m "${SHOALCADE_DEPS:-$HOME/deps}")
  JAVA_HOME=$(cygpath -m "${JAVA_HOME:-$HOME/Android/jdk17/jdk-17.0.20.1+1}")
  [ -d "$ROOT/.testbed/dotnet" ] && DOTNET_DEFAULT=$ROOT/.testbed/dotnet || DOTNET_DEFAULT="C:/Program Files/dotnet"
  DOTNET_ROOT=$(cygpath -m "${DOTNET_ROOT:-$DOTNET_DEFAULT}")
else
  ROOT=$(cd "$(dirname "$0")/.." && pwd)
  DEPS=${SHOALCADE_DEPS:-$HOME/deps}
  JAVA_HOME=${JAVA_HOME:-$HOME/sdk/jdk17}
  DOTNET_ROOT=${DOTNET_ROOT:-$HOME/sdk/dotnet}
fi
WORK=$ROOT/build/windows-release
OUT=$ROOT/dist/windows
INST=$ROOT/windows/installer
export JAVA_HOME DOTNET_ROOT DOTNET_CLI_TELEMETRY_OPTOUT=1 DOTNET_NOLOGO=1
MINGW=x86_64-w64-mingw32
# Tool names: prefixed on Linux (cross tools); MSYS2's own on Windows (its binutils have no prefixed names).
[ $WINHOST = 1 ] && TP= || TP=$MINGW-
SDL_KIT=$DEPS/SDL3-3.2.24
SDL_KIT_TGZ_SHA=07fba9958c3ac50438b3fc9fac22468ebfc64726de8496cb3795acaeebd376f0 # SDL3-devel-3.2.24-mingw.tar.gz
CORES=$DEPS/cores-win

# --- pinned downloads ---------------------------------------------------------------------------------------------
JDK_VER=17.0.20.1+1 # newest Temurin 17 for Windows (31/10/2026); the Linux jlink must be the very same version
JDK_TAG=jdk-17.0.20.1%2B1
JDK_WIN_ZIP=OpenJDK17U-jdk_x64_windows_hotspot_17.0.20.1_1.zip
JDK_WIN_SHA=e53a79c3c3d86865bd7e787903884331068e71321714ffd44f145785affc7cb0
JDK_LNX_TGZ=OpenJDK17U-jdk_x64_linux_hotspot_17.0.20.1_1.tar.gz
JDK_LNX_SHA=3808d1d15e3ec6bd5b84057fb5d84c33d8a1536a258146bcea2e603fc726e08e
JDK_URL=https://github.com/adoptium/temurin17-binaries/releases/download/$JDK_TAG
VPK_VER=1.2.161 # Velopack CLI (NuGet "vpk"); Update.exe/Setup.exe come inside it

# --- signing hook (plan section 8, D202) ------------------------------------------------------------------------------
# Unsigned by default (Ben's own use: SmartScreen shows "More info -> Run anyway" once). To sign, set
# SHOALCADE_SIGN_TEMPLATE to a command that signs one file in place, with {{file}} where the path goes, e.g. with
# osslsigncode and a certificate (SignPath / Certum, plan Q3):
#   SHOALCADE_SIGN_TEMPLATE='sign-one.sh {{file}}' tools/build_windows_release.sh release
# vpk then signs Shoalcade.exe, ShoalcadePlayer.exe, every DLL, Update.exe and Setup.exe with it.
SIGN_TEMPLATE=${SHOALCADE_SIGN_TEMPLATE:-}
export SC_SIGNED=$([ -n "$SIGN_TEMPLATE" ] && echo 1 || echo 0)

say() { printf '\n== %s\n' "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
sha() { sha256sum "$1" | cut -d' ' -f1; }

fetch() { # url dir file sha
  local f=$2/$3
  mkdir -p "$2"
  if [ ! -f "$f" ] || [ "$(sha "$f")" != "$4" ]; then
    echo "downloading $3"
    curl -fsSL -o "$f.part" "$1" && mv "$f.part" "$f"
  fi
  [ "$(sha "$f")" = "$4" ] || die "$3: SHA-256 $(sha "$f") is not the pinned $4"
}

gradle_busy() { # another Gradle build running, or less than 1.2 GB free
  if [ $WINHOST = 1 ]; then
    powershell.exe -NoProfile -NonInteractive -Command \
      '$g = Get-CimInstance Win32_Process | Where-Object { $_.Name -eq "java.exe" -and $_.CommandLine -match "GradleWrapperMain" };
       $free = (Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory / 1024;
       if ($g -or $free -lt 1200) { exit 0 } else { exit 1 }'
  else
    pgrep -f 'java.*GradleWrapperMain' >/dev/null || [ "$(free -m | awk '/^Mem:/{print $7}')" -lt 1200 ]
  fi
}

wait_for_gradle_slot() {
  local waited=0
  while gradle_busy; do
    [ $((waited % 120)) -eq 0 ] && echo "waiting: another Gradle build is running or less than 1.2 GB is free"
    sleep 20; waited=$((waited + 20))
  done
}

PY=python3; python3 -c '' 2>/dev/null || PY=python # on Windows python3 may be the Store stub
if [ $WINHOST = 1 ]; then TOOLS="gcc g++ windres strip objdump"
else TOOLS="$MINGW-gcc $MINGW-gcc-posix $MINGW-windres $MINGW-strip $MINGW-objdump"; fi
for t in $TOOLS cmake ninja $PY curl unzip zip; do
  command -v "$t" >/dev/null || die "missing tool: $t"
done
[ $WINHOST = 0 ] || [ "$(command -v gcc)" = "$MSYS2/mingw64/bin/gcc" ] || die "gcc is not MSYS2's: $(command -v gcc)"
[ -x "$JAVA_HOME/bin/java" ] || die "JDK 17 for Gradle not found at $JAVA_HOME"
[ -x "$DOTNET_ROOT/dotnet" ] || die ".NET SDK not found at $DOTNET_ROOT"
if [ ! -d "$SDL_KIT/$MINGW" ]; then
  fetch https://github.com/libsdl-org/SDL/releases/download/release-3.2.24/SDL3-devel-3.2.24-mingw.tar.gz \
    "$DEPS/sdl-dl" SDL3-devel-3.2.24-mingw.tar.gz "$SDL_KIT_TGZ_SHA"
  tar xzf "$DEPS/sdl-dl/SDL3-devel-3.2.24-mingw.tar.gz" --force-local -C "$DEPS"
fi
[ -d "$SDL_KIT/$MINGW" ] || die "SDL3 MinGW kit not found at $SDL_KIT"

VERSION=$(sed -nE 's/^\s*versionName\s*=\s*"([^"]+)".*/\1/p' "$ROOT/android/app/build.gradle.kts" | head -1)
[ -n "$VERSION" ] || die "versionName not found in android/app/build.gradle.kts"
# Windows file versions are four numbers: 0.12.0 -> 0,12,0,0 ("0.6.0-dev2" -> 0,6,0,0).
IFS=. read -r V1 V2 V3 _ <<<"${VERSION%%-*}"
VER_COMMA="${V1:-0},${V2:-0},${V3:-0},0"
SRC_COMMIT=$(git -C "$ROOT" rev-parse HEAD)
SRC_DIRTY=$(git -C "$ROOT" status --porcelain --untracked-files=no | wc -l)
echo "Shoalcade for Windows $VERSION from commit $SRC_COMMIT ($SRC_DIRTY changed files not committed)"

mkdir -p "$WORK" "$OUT"

# --- 1. cores --------------------------------------------------------------------------------------------------------
say "1. Windows cores vs windows/cores/cores.lock"
LOCK=$ROOT/windows/cores/cores.lock
n=0
while IFS=$'\t' read -r core target repo commit licence file bytes hash; do
  [ "$target" = win ] || continue
  f=$CORES/$file
  [ -f "$f" ] || die "core missing: $f (run tools/build_windows_cores.sh)"
  [ "$(stat -c %s "$f")" = "$bytes" ] || die "$file: $(stat -c %s "$f") bytes, lock says $bytes"
  [ "$(sha "$f")" = "$hash" ] || die "$file: SHA-256 differs from cores.lock"
  echo "  ok  $file  $bytes bytes  ${hash:0:16}…"
  n=$((n + 1))
done < <(grep -v '^#' "$LOCK")
[ "$n" -ge 7 ] || die "expected 7 Windows cores in cores.lock, found $n"
[ -f "$CORES/PPSSPP/LICENSE.TXT" ] || die "PPSSPP system folder missing: $CORES/PPSSPP"
if grep -q "^dolphin$(printf '\t')win$(printf '\t')" "$LOCK"; then # Dolphin's Sys, written beside the core by build_windows_cores.sh
  [ -f "$CORES/dolphin-emu/Sys/codehandler.bin" ] && [ -d "$CORES/dolphin-emu/Sys/GameSettings" ] ||
    die "Dolphin system folder missing: $CORES/dolphin-emu/Sys (run tools/build_windows_cores.sh dolphin)"
fi
if grep -q "^pcee2$(printf '\t')win$(printf '\t')" "$LOCK"; then # PCEE2's resources, written beside the core
  [ -f "$CORES/pcsx2/resources/GameIndex.yaml" ] ||
    die "PCEE2 resources missing: $CORES/pcsx2/resources (run tools/build_windows_cores.sh pcee2)"
fi

# --- 2. player -------------------------------------------------------------------------------------------------------
say "2. ShoalcadePlayer.exe (Release, MinGW)"
# Built from the COMMITTED player source (git archive HEAD), never the working tree: the installer's player must be
# exactly the commit SOURCE.txt names (GPLv3 Corresponding Source), and work in progress in windows/player is left out.
PSRC=$WORK/player-src
rm -rf "$PSRC"; mkdir -p "$PSRC"
git -C "$ROOT" -c core.autocrlf=false archive HEAD windows/player | tar -x -C "$PSRC" # bytes as committed
# The player's CMakeLists also declares its unit-test exe, whose source (windows/player/tests/) is kept out of git.
# An empty stand-in lets CMake configure; that target is never built here.
[ -f "$PSRC/windows/player/tests/unit_tests.cpp" ] || { mkdir -p "$PSRC/windows/player/tests"; : > "$PSRC/windows/player/tests/unit_tests.cpp"; }
if [ -n "$(git -C "$ROOT" status --porcelain -- windows/player)" ]; then
  echo "  note: windows/player has uncommitted changes; they are NOT in this build (built from commit ${SRC_COMMIT:0:12})"
fi
PB=$WORK/player-build
rm -rf "$PB"
TOOLCHAIN=() # natively MSYS2's gcc is the Windows compiler; the toolchain file is for the Linux cross-build
[ $WINHOST = 1 ] || TOOLCHAIN=(-DCMAKE_TOOLCHAIN_FILE="$PSRC/windows/player/cmake/mingw64.cmake")
cmake -S "$PSRC/windows/player" -B "$PB" -G Ninja -DCMAKE_BUILD_TYPE=Release "${TOOLCHAIN[@]}" \
  -DCMAKE_PREFIX_PATH="$SDL_KIT/$MINGW" >/dev/null
nice cmake --build "$PB" -j2 --target ShoalcadePlayer
PLAYER=$WORK/player
rm -rf "$PLAYER"; mkdir -p "$PLAYER"
${TP}strip -o "$PLAYER/ShoalcadePlayer.exe" "$PB/ShoalcadePlayer.exe"
${TP}strip --strip-unneeded -o "$PLAYER/SDL3.dll" "$PB/SDL3.dll"

# Only Windows' own DLLs (and SDL3.dll for the player) may be needed: a MinGW runtime DLL here would mean a
# "works on the build machine, fails on Ben's PC" install.
SYSTEM_DLLS='^(kernel32|user32|gdi32|shell32|advapi32|ole32|oleaut32|ws2_32|winmm|opengl32|iphlpapi|bcrypt|winhttp|version|shlwapi|crypt32|msvcrt|imm32|setupapi|cfgmgr32|hid|dinput8|dxgi|d3dcompiler_47|d3d11|d3d12|dwmapi|uxtheme|comdlg32|comctl32|secur32|userenv|ntdll|dbghelp|powrprof|avrt|mfplat|mfreadwrite|mf|propsys|winusb|xinput1_4|api-ms-win-.*)\.dll$'
check_imports() { # file allowed-extra-regex
  local bad
  bad=$(${TP}objdump -p "$1" | tr -d '\r' | sed -n 's/^\s*DLL Name: //p' | tr 'A-Z' 'a-z' |
        grep -v -E "$SYSTEM_DLLS" | grep -v -E "${2:-^$}" || true)
  [ -z "$bad" ] || die "$(basename "$1") needs non-Windows DLLs: $bad"
}
# No BIOS, firmware, keys or memory card is ever shipped (and the launcher would copy it over the user's own): the build
# stops if the installer image holds a bios\ or memcards\ folder (any *.bin in them included), a PS2/PS1/GameCube card,
# a GameCube IPL.bin, a Sony BIOS (scph*) or a 3DS/Wii key file.
refuse_firmware() { # image folder
  local bad
  bad=$(find "$1" \( -type d \( -iname bios -o -iname memcards \) -print \) -o \( -type f \( -iname '*.ps2' \
        -o -iname 'IPL.bin' -o -iname 'scph*' -o -iname '*.mcd' -o -iname '*.mcr' -o -iname '*.gci' \
        -o -iname 'MemoryCard*.raw' -o -iname 'SRAM.raw' -o -iname '*.keys' -o -iname 'keys.txt' -o -iname 'aes_keys.txt' \
        -o -iname 'keys.bin' -o -iname 'otp.bin' -o -iname 'nand.bin' -o -iname 'seeddb.bin' -o -iname 'boot9.bin' \
        -o -iname 'boot11.bin' -o -iname 'movable.sed' \) -print \))
  [ -z "$bad" ] || die "the installer image would hold a BIOS, key or memory card (never shipped): ${bad:0:800}"
  # A PS2 BIOS by content, whatever it is called (review S12): the app's own BIOS rule (Ps2.check / PCSX2 IsBIOS).
  bad=$("$PY" -I "$INST/find_ps2_bios.py" "$1") || die "the PS2 BIOS check couldn't run on $1"
  [ -z "$bad" ] || die "the installer image would hold a PS2 BIOS (never shipped): ${bad:0:800}"
}
check_imports "$PLAYER/ShoalcadePlayer.exe" '^sdl3\.dll$'
check_imports "$PLAYER/SDL3.dll"
for f in "$CORES"/*_libretro.dll; do check_imports "$f"; done
echo "  imports ok (player + SDL3 + $(ls "$CORES"/*_libretro.dll | wc -l) cores need only Windows' own DLLs)"

# --- 3. Java runtime -------------------------------------------------------------------------------------------------
say "3. Windows Java runtime (Temurin $JDK_VER, jlink)"
fetch "$JDK_URL/$JDK_WIN_ZIP" "$DEPS/jdk-win-dl" "$JDK_WIN_ZIP" "$JDK_WIN_SHA"
JDK_WIN=$DEPS/jdk17-win/jdk-$JDK_VER
[ -d "$JDK_WIN/jmods" ] || { mkdir -p "$DEPS/jdk17-win"; unzip -q -o "$DEPS/jdk-win-dl/$JDK_WIN_ZIP" -d "$DEPS/jdk17-win"; }
if [ $WINHOST = 1 ]; then
  JLINK=$JDK_WIN/bin/jlink # the Windows JDK's own jlink: the same version as its jmods by construction
else
  fetch "$JDK_URL/$JDK_LNX_TGZ" "$DEPS/jdk-linux-dl" "$JDK_LNX_TGZ" "$JDK_LNX_SHA"
  JDK_LNX=$DEPS/jdk17-linux-jlink/jdk-$JDK_VER
  [ -x "$JDK_LNX/bin/jlink" ] || { mkdir -p "$DEPS/jdk17-linux-jlink"; tar xzf "$DEPS/jdk-linux-dl/$JDK_LNX_TGZ" -C "$DEPS/jdk17-linux-jlink"; }
  JLINK=$JDK_LNX/bin/jlink
fi
[ -x "$JLINK" ] || die "jlink not found: $JLINK"

# --- vpk -------------------------------------------------------------------------------------------------------------
VPK=$DEPS/vpk-tool/vpk
if [ ! -x "$VPK" ] || ! "$VPK" --help 2>/dev/null | grep -q "Velopack CLI $VPK_VER"; then
  "$DOTNET_ROOT/dotnet" tool uninstall vpk --tool-path "$DEPS/vpk-tool" >/dev/null 2>&1 || true
  "$DOTNET_ROOT/dotnet" tool install vpk --version "$VPK_VER" --tool-path "$DEPS/vpk-tool" >/dev/null
fi
export PATH=$DOTNET_ROOT:$PATH

# --- per variant -----------------------------------------------------------------------------------------------------
build_variant() {
  local v=$1 dev name data packid gradle_rel=()
  if [ "$v" = dev ]; then dev=1; name="Shoalcade Dev"; data="Shoalcade Dev"; packid=ShoalcadeDevApp
  else dev=0; name="Shoalcade"; data="Shoalcade"; packid=ShoalcadeApp; gradle_rel=(-Pshoalcade.release=true); fi
  # Uninstall empties %LOCALAPPDATA%\<packId>: it must never be the data folder (Windows names ignore case).
  [ "$(tr 'A-Z' 'a-z' <<<"$packid")" != "$(tr 'A-Z' 'a-z' <<<"$data")" ] || die "packId $packid = data folder name"
  local VW=$WORK/$v IMG=$WORK/$v/image
  rm -rf "$VW"; mkdir -p "$VW"

  say "4.$v-a App jars (Gradle, Windows Skiko)"
  wait_for_gradle_slot
  (cd "$ROOT/windows/app" && ./gradlew --no-daemon -q -Dorg.gradle.jvmargs=-Xmx1g \
      -I "$INST/windows-jars.init.gradle" shoalcadeWindowsJars "${gradle_rel[@]}" -Pshoalcade.winjars.out="$VW/jars") \
    2>&1 | grep -v '^w: ' || true
  [ -s "$VW/jars/classpath.txt" ] || die "Gradle did not produce the jar list"

  say "4.$v-b Java runtime (jlink)"
  "$JLINK" --module-path "$JDK_WIN/jmods" --add-modules "$(cat "$VW/jars/jdk-modules.txt")" \
    --strip-debug --no-header-files --no-man-pages --strip-native-commands --compress=2 --output "$VW/runtime"
  grep -q 'JAVA_VERSION="17.0.20.1"' "$VW/runtime/release" || die "jlink output is not $JDK_VER"
  [ -f "$VW/runtime/bin/server/jvm.dll" ] || die "jlink output has no Windows jvm.dll"

  say "4.$v-c App folder"
  mkdir -p "$IMG/app" "$IMG/licences"
  local cp_lines=() j skiko
  skiko=$(cat "$VW/jars/skiko.txt")
  while read -r j; do
    case "$j" in
      skiko-awt-runtime-windows-x64-*.jar)
        # As Compose's own packaging: Skiko's DLL (+ hash) and ICU data beside the jars, loaded via
        # -Dskiko.library.path. The jar holds nothing else, so it is left off the class path (17 MB less).
        unzip -q -o "$VW/jars/app/$j" skiko-windows-x64.dll skiko-windows-x64.dll.sha256 icudtl.dat -d "$IMG/app"
        [ "$(unzip -Z1 "$VW/jars/app/$j" | grep -v -E '^(META-INF/|META-INF/MANIFEST.MF|skiko-windows-x64.dll(.sha256)?|icudtl.dat)$' || true)" = "" ] \
          || die "Skiko's Windows jar holds more than the DLL and ICU data; keep it on the class path"
        ;;
      *) cp "$VW/jars/app/$j" "$IMG/app/"; cp_lines+=("cp=$j") ;;
    esac
  done < "$VW/jars/classpath.txt"
  {
    echo "# Shoalcade.exe settings, written by tools/build_windows_release.sh. \$APPDIR = this folder, \$ROOT = the install folder."
    cat "$VW/jars/launch.txt"
    # The same system properties Compose's own Windows launcher sets (createDistributable's .cfg).
    echo "opt=-Djpackage.app-version=$VERSION"
    echo 'opt=-Dcompose.application.resources.dir=$APPDIR\resources'
    echo 'opt=-Dcompose.application.configure.swing.globals=true'
    echo 'opt=-Dskiko.library.path=$APPDIR'
    # The exact Shoalcade commit, for the About screen's source link (WinLicences.appSource).
    echo "opt=-Dshoalcade.source.commit=$SRC_COMMIT"
    printf '%s\n' "${cp_lines[@]}"
  } > "$IMG/app/launcher.cfg"
  cp -r "$VW/runtime" "$IMG/runtime"
  mkdir -p "$IMG/app/player" "$IMG/app/cores" "$IMG/app/system"
  cp "$PLAYER/ShoalcadePlayer.exe" "$PLAYER/SDL3.dll" "$IMG/app/player/"
  cp "$CORES"/*_libretro.dll "$IMG/app/cores/"
  cp -r "$CORES/PPSSPP" "$IMG/app/system/PPSSPP"
  # Only the folders the cores' builds write (a test player run against $CORES may have added bios\, memcards\, a
  # User\ folder...): Dolphin's Sys\ (GameCube + Wii) and PCEE2's resources\ (PS2). Never a BIOS.
  if [ -d "$CORES/dolphin-emu/Sys" ]; then mkdir -p "$IMG/app/system/dolphin-emu"; cp -r "$CORES/dolphin-emu/Sys" "$IMG/app/system/dolphin-emu/Sys"; fi
  if [ -d "$CORES/pcsx2/resources" ]; then mkdir -p "$IMG/app/system/pcsx2"; cp -r "$CORES/pcsx2/resources" "$IMG/app/system/pcsx2/resources"; fi
  refuse_firmware "$IMG"
  # The stamps the launcher compares before copying each folder to the user's system folder.
  local sysdir
  for sysdir in PPSSPP dolphin-emu pcsx2; do
    [ -d "$IMG/app/system/$sysdir" ] || continue
    (cd "$IMG/app/system" && find "$sysdir" -type f -print0 | sort -z | xargs -0 sha256sum | sha256sum | cut -d' ' -f1) \
      > "$IMG/app/system/$sysdir.stamp"
  done

  say "4.$v-d Icons + Shoalcade.exe"
  mkdir -p "$VW/icons"
  "$PY" -I "$INST/make_icons.py" "$ROOT/docs/assets/logo.svg" "$VW/icons" $([ $dev = 1 ] && echo --dev)
  # The space in "Shoalcade Dev" goes to the resource compiler as the escape \040: Windows' windres passes the -D
  # values to its preprocessor through a command line that splits them at spaces.
  local sp='\040'
  (cd "$INST/launcher" && ${TP}windres -I "$INST/launcher" \
      -DSC_ICON="\\\"$VW/icons/shoalcade.ico\\\"" -DSC_VER_COMMA="$VER_COMMA" -DSC_VER_STR="\\\"$VERSION\\\"" \
      -DSC_APP_NAME_A="\\\"${name// /$sp}\\\"" launcher.rc -O coff -o "$VW/launcher-res.o")
  ${TP}gcc -O2 -Wall -Wextra -Werror -municode -mwindows -I"$JDK_WIN/include" -I"$JDK_WIN/include/win32" \
    "-DSC_DATA_FOLDER=L\"$data\"" "-DSC_APP_NAME=L\"$name\"" \
    "$INST/launcher/launcher.c" "$VW/launcher-res.o" -o "$IMG/Shoalcade.exe" -static -s
  check_imports "$IMG/Shoalcade.exe"

  say "4.$v-e Licences + SOURCE.txt"
  bash "$INST/licences.sh" "$ROOT" "$IMG" "$VW/jars/modules.txt" "$JDK_VER" "$SDL_KIT" "$SDL_KIT_TGZ_SHA" \
    "$SRC_COMMIT" "$SRC_DIRTY" "$VERSION" "$skiko" "$VPK_VER" "$name"

  say "4.$v-f Velopack (vpk pack; cross-packed with [win] on Linux)"
  rm -rf "$OUT/$v"; mkdir -p "$OUT/$v"
  local sign=()
  if [ -n "$SIGN_TEMPLATE" ]; then sign=(--signTemplate "$SIGN_TEMPLATE"); echo "  signing with SHOALCADE_SIGN_TEMPLATE"
  else echo "  UNSIGNED (set SHOALCADE_SIGN_TEMPLATE to sign)"; fi
  local os_dir=("[win]"); [ $WINHOST = 0 ] || os_dir=()
  "$VPK" "${os_dir[@]}" --skip-updates pack --packId "$packid" --packVersion "$VERSION" --packDir "$IMG" "${sign[@]}" \
    --packTitle "$name" --packAuthors "Shoalcade" --runtime win-x64 --mainExe Shoalcade.exe --icon "$VW/icons/shoalcade.ico" \
    --splashImage "$VW/icons/shoalcade-256.png" --shortcuts "Desktop,StartMenuRoot" \
    --delta None --outputDir "$OUT/$v" 2>&1 | sed 's/^/  /'
  ls "$OUT/$v"/*Setup.exe >/dev/null || die "vpk made no Setup.exe"
  du -sh "$IMG" | sed 's/^/  installed size: /'
}

for v in "${VARIANTS[@]}"; do build_variant "$v"; done

say "Done: $OUT"
(cd "$OUT" && find . -type f -printf '%10s  %p\n' | sort -k2 | awk '{printf "  %8.1f MB  %s\n", $1/1048576, $2}')
