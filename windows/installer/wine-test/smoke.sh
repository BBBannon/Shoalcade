#!/usr/bin/env bash
# Shoalcade for Windows: installer smoke test under Wine (TEST ONLY). Part of Shoalcade, GPL-3.0-or-later.
#
#   windows/installer/wine-test/smoke.sh <dev|release> [shots dir]
#
# In a FRESH Wine prefix (~/deps/wine-pkg/smoke-<variant>, deleted and made again each run), under Xvfb:
#  1. puts a pretend save in the app's data folder BEFORE installing (install and uninstall must both leave it alone)
#  2. Setup.exe --silent: checks the files and the Desktop + Start menu shortcuts land
#  3. the INSTALLED ShoalcadePlayer.exe + core DLLs + PPSSPP files run the player's own test cases headless
#     (windows/player/tests/run_tests.py, GB / GBA x2 / DS / PS1 / N64 / PSP)
#  4. the INSTALLED Shoalcade.exe starts Java and opens its window: screenshot; PPSSPP files copied to the data folder
#     + clicks "About Shoalcade" on the login screen: screenshot of the About list
#  5. upgrade: Setup.exe again over itself (same version), then Setup.exe of a test-only NEWER package (the same files
#     repacked as <version+1>-upgradetest plus a marker file; never shipped, the real version is not changed):
#     new files land, the data folder (pretend save + pretend settings) is byte-identical, the upgraded app starts
#  6. Update.exe uninstall --silent: install folder and shortcuts gone, data folder + pretend save untouched (SHA-256),
#     and a sweep of the prefix's user folders for anything else left behind
#
# Test-only fonts: a bare Wine prefix has no Arial/Segoe UI, which Compose asks for, so copies of DejaVu renamed to those
# names (alias_fonts.py, needs fontTools in ~/deps/venv-fonttools) are put in the prefix. Real Windows has the real ones.
set -uo pipefail
V=${1:?usage: smoke.sh <dev|release> [shots dir]}
ROOT=$(cd "$(dirname "$0")/../../.." && pwd)
SHOTS=${2:-$ROOT/docs/windows/phase8-shots}
DEPS=${SHOALCADE_DEPS:-$HOME/deps}
if [ "$V" = dev ]; then PACK=ShoalcadeDevApp; DATA="Shoalcade Dev"; NAME="Shoalcade Dev"
else PACK=ShoalcadeApp; DATA="Shoalcade"; NAME="Shoalcade"; fi
SETUP=$ROOT/dist/windows/$V/$PACK-win-Setup.exe
[ -f "$SETUP" ] || { echo "no $SETUP: run tools/build_windows_release.sh $V first"; exit 2; }
export WINEPREFIX=$DEPS/wine-pkg/smoke-$V WINEDEBUG=-all WINEDLLOVERRIDES="mscoree,mshtml="
WORK=$DEPS/wine-pkg/smoke-$V-work
U=$WINEPREFIX/drive_c/users/$USER
LOCAL=$U/AppData/Local
mkdir -p "$SHOTS"
PASS=0 FAIL=0
ok() { echo "PASS  $*"; PASS=$((PASS + 1)); }
bad() { echo "FAIL  $*"; FAIL=$((FAIL + 1)); }
check() { if eval "$2"; then ok "$1"; else bad "$1"; fi; }
# After Setup/Update: `wine` already waited for the exe; give Velopack's own helper processes up to 2 min to finish.
# (Not `wineserver -w`: once the app has run, the prefix's wineserver can stay up and that waits for ever.)
settle() { sleep 3; for _ in $(seq 1 60); do pgrep -f -i 'Setup\.exe|Update\.exe' >/dev/null || return 0; sleep 2; done; }

wineserver -k 2>/dev/null; sleep 1
rm -rf "$WINEPREFIX" "$WORK"; mkdir -p "$WORK"

# One X server for the whole run, so Wine's desktop process always has a display.
Xvfb :87 -screen 0 1280x960x24 -nolisten tcp >/dev/null 2>&1 &
XPID=$!
export DISPLAY=:87
trap 'wineserver -k 2>/dev/null; kill $XPID 2>/dev/null' EXIT
sleep 2

echo "== prefix"
wineboot -i >/dev/null 2>&1; wineserver -w
FONTS=$DEPS/wine-pkg/fonts
if [ ! -f "$FONTS/Arial-Regular.ttf" ]; then
  mkdir -p "$FONTS"
  "$DEPS/venv-fonttools/bin/python" -I "$ROOT/windows/installer/wine-test/alias_fonts.py" /usr/share/fonts/truetype/dejavu "$FONTS" >/dev/null
fi
cp "$FONTS"/*.ttf "$WINEPREFIX/drive_c/windows/Fonts/"
{ echo REGEDIT4; echo; echo '[HKEY_LOCAL_MACHINE\Software\Microsoft\Windows NT\CurrentVersion\Fonts]'
  for f in "$FONTS"/*.ttf; do b=$(basename "$f" .ttf); echo "\"$b (TrueType)\"=\"$b.ttf\""; done; } > "$WORK/fonts.reg"
wine regedit "$WORK/fonts.reg"; wineserver -w

echo "== 1. pretend save before install"
SAVE="$LOCAL/$DATA/library/saves/smoketest/0123456789abcdef/game.sav"
mkdir -p "$(dirname "$SAVE")"
head -c 131072 /dev/urandom > "$SAVE"
SAVE_SHA=$(sha256sum "$SAVE" | cut -d' ' -f1)

echo "== 2. install (Setup.exe --silent)"
t0=$(date +%s)
wine "$SETUP" --silent --log 'C:\setup.log' >/dev/null 2>&1; settle
echo "   took $(( $(date +%s) - t0 )) s"
INST=$LOCAL/$PACK
CUR=$INST/current
check "installed into %LOCALAPPDATA%\\$PACK (not the data folder)" "[ -f '$CUR/Shoalcade.exe' ] && [ -f '$INST/Update.exe' ]"
check "Java runtime, jars, launcher.cfg" "[ -f '$CUR/runtime/bin/server/jvm.dll' ] && [ -f '$CUR/app/launcher.cfg' ] && [ -f '$CUR/app/skiko-windows-x64.dll' ]"
check "player + SDL3 + 7 cores" "[ -f '$CUR/app/player/ShoalcadePlayer.exe' ] && [ -f '$CUR/app/player/SDL3.dll' ] && [ \$(ls '$CUR/app/cores/'*_libretro.dll | wc -l) = 7 ]"
check "PPSSPP files + stamp" "[ -f '$CUR/app/system/PPSSPP/LICENSE.TXT' ] && [ -s '$CUR/app/system/PPSSPP.stamp' ]"
check "licences + SOURCE.txt" "[ -f '$CUR/LICENSE.txt' ] && [ -f '$CUR/SOURCE.txt' ] && [ -f '$CUR/licences/README.txt' ]"
check "core DLLs identical to cores.lock" "(cd '$CUR/app/cores' && grep -v '^#' '$ROOT/windows/cores/cores.lock' | awk -F'\t' '\$2==\"win\"{print \$8\"  \"\$6}' | sha256sum -c --quiet)"
DESK=$(find "$U" -maxdepth 2 -iname "$NAME.lnk" -path '*Desktop*' | head -1)
START=$(find "$U/AppData/Roaming/Microsoft/Windows/Start Menu" -iname "$NAME.lnk" 2>/dev/null | head -1)
check "Desktop shortcut ($NAME.lnk)" "[ -n '$DESK' ]"
check "Start menu shortcut ($NAME.lnk)" "[ -n '$START' ]"
check "uninstall entry in the registry" "wine reg query 'HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\$PACK' >/dev/null 2>&1"
check "pretend save untouched by install" "[ \"\$(sha256sum '$SAVE' | cut -d' ' -f1)\" = '$SAVE_SHA' ]"

echo "== 3. installed player, headless, the player's own test cases"
WINEPREFIX=$WINEPREFIX python3 "$ROOT/windows/player/tests/run_tests.py" --platform windows \
  --player "$CUR/app/player/ShoalcadePlayer.exe" --cores "$CUR/app/cores" --ppsspp-assets "$CUR/app/system/PPSSPP" \
  --work "$WORK/player" --only gb,gba-mgba,gba-gpsp,nds,psx,n64,psp --shots "$WORK/player-shots" > "$WORK/player.log" 2>&1
prc=$?
tail -1 "$WORK/player.log"
check "installed player test cases (run_tests.py)" "[ $prc = 0 ]"

echo "== 4. installed Shoalcade.exe: Java starts, window opens"
start_app() { # log file
  (cd "$CUR" && wine "$CUR/Shoalcade.exe" > "$1" 2>&1 &)
  for _ in $(seq 1 30); do sleep 5; grep -q ' starting; data in' "$1" && break; done
  sleep 45 # Java + Compose first frame under Wine's software OpenGL
}
stop_app() { wine taskkill /f /im Shoalcade.exe >/dev/null 2>&1; sleep 3; }
start_app "$WORK/app.log"
"$HOME/sdk/jdk17/bin/java" "$DEPS/tools/Grab.java" "$SHOTS/$V-login-wine.png" >/dev/null
grep -m1 ' starting; data in' "$WORK/app.log"
check "app started (its log line)" "grep -q '$NAME .* starting; data in C:.*$DATA' '$WORK/app.log'"
check "no exception from the app" "! grep -q 'Exception in thread' '$WORK/app.log'"
check "Shoalcade.exe is the running process (Java in-process)" "pgrep -f 'Shoalcade.exe' >/dev/null && ! pgrep -f 'javaw.exe' >/dev/null"
check "PPSSPP files copied to the data folder" "[ -f '$LOCAL/$DATA/system/PPSSPP/LICENSE.TXT' ] && cmp -s '$LOCAL/$DATA/system/PPSSPP.stamp' '$CUR/app/system/PPSSPP.stamp'"
check "launcher.cfg passes the source commit (About link)" "grep -qE '^opt=-Dshoalcade.source.commit=[0-9a-f]{40}$' '$CUR/app/launcher.cfg'"
grep -E 'RenderException|Skiko|DirectX|OpenGL|software' "$WORK/app.log" | head -3 | sed 's/^/   app log: /'
# The About screen: "About Shoalcade" sits at the login window's bottom-right corner.
GEO=$(xwininfo -root -tree | grep -F "\"$NAME\": (" | grep -oE '[0-9]+x[0-9]+\+-?[0-9]+\+-?[0-9]+' | head -1)
if [ -n "$GEO" ]; then
  IFS='x+' read -r GW GH GX GY <<<"$GEO"
  "$HOME/sdk/jdk17/bin/java" "$DEPS/tools/Click.java" $((GX + GW - 80)) $((GY + GH - 30)); sleep 10
  "$HOME/sdk/jdk17/bin/java" "$DEPS/tools/Grab.java" "$SHOTS/$V-about-wine.png" >/dev/null
  echo "   window $GEO; About screenshot: $SHOTS/$V-about-wine.png"
else echo "   (window not found by name: no About screenshot)"; fi
check "no exception after opening About" "! grep -q 'Exception in thread' '$WORK/app.log'"

echo "== 5. upgrade over itself, keeping user data"
# Pretend settings next to the pretend save: the library + settings must come through byte for byte.
printf 'smoke-test settings\n' > "$LOCAL/$DATA/smoketest-settings.txt"
data_sum() { (cd "$LOCAL/$DATA" && find library smoketest-settings.txt -type f -print0 | sort -z | xargs -0 sha256sum | sha256sum | cut -d' ' -f1); }
DATA_SHA=$(data_sum)
stop_app
VER=$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' "$CUR/sq.version" | head -1)
echo "   installed version $VER"
wine "$SETUP" --silent >/dev/null 2>&1; settle
check "same-version Setup over itself: app still installed" "[ -f '$CUR/Shoalcade.exe' ] && [ -f '$CUR/app/launcher.cfg' ]"
check "same-version Setup: data folder byte-identical" "[ \"\$(data_sum)\" = '$DATA_SHA' ]"
stop_app
# Test-only newer package: the same files + a marker, version <major>.<minor>.<patch+1>-upgradetest.
IFS=. read -r A B C <<<"${VER%%-*}"
UPV="$A.$B.$((C + 1))-upgradetest"
rm -rf "$WORK/image-up" "$WORK/up"; cp -al "$ROOT/build/windows-release/$V/image" "$WORK/image-up"
echo "upgrade test marker ($UPV)" > "$WORK/image-up/UPGRADE-TEST.txt"
DOTNET_ROOT=${DOTNET_ROOT:-$HOME/sdk/dotnet}
DOTNET_ROOT=$DOTNET_ROOT PATH=$DOTNET_ROOT:$PATH DOTNET_CLI_TELEMETRY_OPTOUT=1 \
  "$DEPS/vpk-tool/vpk" "[win]" --skip-updates pack --packId "$PACK" --packVersion "$UPV" --packDir "$WORK/image-up" \
  --packTitle "$NAME" --packAuthors Shoalcade --runtime win-x64 --mainExe Shoalcade.exe --shortcuts "Desktop,StartMenuRoot" \
  --delta None --outputDir "$WORK/up" > "$WORK/up-pack.log" 2>&1
check "test-only upgrade package built ($UPV)" "[ -f '$WORK/up/$PACK-win-Setup.exe' ]"
wine "$WORK/up/$PACK-win-Setup.exe" --silent >/dev/null 2>&1; settle
check "upgrade: new version's files in place" "[ -f '$CUR/UPGRADE-TEST.txt' ] && grep -q '<version>$UPV</version>' '$CUR/sq.version'"
# One app copy: exactly one <pack>*/current/Shoalcade.exe (no rollback folder left). Velopack's own launcher stub in the
# install root is named after the title ("Shoalcade.exe" for release), so it isn't counted.
one_install() { find "$LOCAL" -maxdepth 3 -ipath "$LOCAL/$PACK*/current/Shoalcade.exe" | wc -l; }
check "upgrade: one install only" "[ \$(one_install) = 1 ]"
[ "$(one_install)" = 1 ] || find "$LOCAL" -maxdepth 3 -ipath "$LOCAL/$PACK*/current/Shoalcade.exe" -printf '   found: %p\n'
check "upgrade: data folder byte-identical (pretend save + settings)" "[ \"\$(data_sum)\" = '$DATA_SHA' ]"
check "upgrade: shortcuts still there" "[ -n \"\$(find '$U' -maxdepth 2 -iname '$NAME.lnk' -path '*Desktop*')\" ]"
# Velopack writes DisplayVersion without the pre-release part (0.12.1-upgradetest -> 0.12.1).
check "upgrade: Windows' uninstall list shows ${UPV%%-*}" "wine reg query 'HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\$PACK' /v DisplayVersion 2>/dev/null | grep -q 'REG_SZ *${UPV%%-*}'"
stop_app
start_app "$WORK/app2.log"
check "upgraded app starts (its log line, no exception)" "grep -q '$NAME .* starting; data in C:.*$DATA' '$WORK/app2.log' && ! grep -q 'Exception in thread' '$WORK/app2.log'"
"$HOME/sdk/jdk17/bin/java" "$DEPS/tools/Grab.java" "$SHOTS/$V-after-upgrade-wine.png" >/dev/null

echo "== 6. uninstall (Update.exe uninstall --silent)"
t0=$(date +%s)
wine "$INST/Update.exe" uninstall --silent >/dev/null 2>&1; settle
echo "   took $(( $(date +%s) - t0 )) s (includes the hook's message, which waits up to 60 s for OK)"
# Update.exe removes everything, then schedules `cmd /C choice /T 3 & rmdir` for its own folder and exits. On Windows it
# exits at once; under Wine 10 its process can linger ~2 min, so the rmdir can meet a locked Update.exe. So: hard checks
# that everything but Update.exe is gone and that Velopack scheduled the removal; a leftover lone Update.exe is reported
# as a WINE NOTE (real-Windows check on Ben's PC, docs/windows/phase8-installer.md), never counted as a pass.
for _ in $(seq 1 30); do [ -e "$INST" ] || break; sleep 2; done
VLOG=$(find "$LOCAL/velopack" -iname "velopack_$PACK.log" 2>/dev/null | head -1)
check "install folder emptied (all but Update.exe)" "[ ! -e '$INST' ] || [ \"\$(ls -A '$INST')\" = Update.exe ]"
check "Velopack finished and scheduled the folder's removal" "grep -q 'Finished successfully' '$VLOG' && grep -q 'Scheduling removal of install directory' '$VLOG'"
if [ -e "$INST" ]; then echo "   WINE NOTE: $INST still holds Update.exe (Wine kept the process alive past Velopack's 3 s rmdir)"; WINE_NOTE=1
else ok "install folder gone"; fi
check "shortcuts removed" "[ ! -e '$DESK' ] && [ ! -e '$START' ]"
check "data folder kept" "[ -d '$LOCAL/$DATA/library' ] && [ -f '$LOCAL/$DATA/smoketest-settings.txt' ]"
check "pretend save kept, same bytes" "[ \"\$(sha256sum '$SAVE' | cut -d' ' -f1)\" = '$SAVE_SHA' ]"
check "uninstall entry removed" "! wine reg query 'HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\$PACK' >/dev/null 2>&1"
# (*.desktop files are Wine's own Linux menu copies of the shortcuts, made by winemenubuilder: not on Windows.)
LEFT=$(cd "$U" && find . -newer "$WORK/fonts.reg" \( -iname '*shoalcade*' -o -iname '*velopack*' \) ! -path "./AppData/Local/$DATA" ! -path "./AppData/Local/$DATA/*" ! -name '*.desktop' | sort)
echo "   left behind in the user's folders, besides the data folder:"
echo "${LEFT:-(nothing)}" | sed 's/^/     /'
OTHER=$(echo "$LEFT" | grep -v -x -E "\./AppData/Local/velopack(/velopack(_$PACK)?\.log)?" | grep . || true)
[ -n "${WINE_NOTE:-}" ] && OTHER=$(echo "$OTHER" | grep -v -x -E "\./AppData/Local/$PACK(/Update\.exe)?" | grep . || true)
check "nothing else left (Velopack's own log folder is allowed)" "[ -z \"\$OTHER\" ]"
find "$WINEPREFIX/drive_c" -iname '*velopack*.log' -newer "$WORK/fonts.reg" 2>/dev/null | while read -r f; do
  grep -i -E 'hook|Removing directory' "$f" | tail -4 | sed 's/^/   velopack: /'; done

echo
echo "$V: $PASS passed, $FAIL failed${WINE_NOTE:+, 1 Wine note (lone Update.exe left)} (prefix $WINEPREFIX, logs $WORK)"
[ "$FAIL" = 0 ]
