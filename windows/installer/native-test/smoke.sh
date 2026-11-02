#!/usr/bin/env bash
# Shoalcade for Windows: installer smoke test on Windows itself (TEST ONLY; Git Bash). Part of Shoalcade,
# GPL-3.0-or-later. The Wine version is windows/installer/wine-test/smoke.sh; this one runs on the real desktop.
#
#   windows/installer/native-test/smoke.sh <dev|release>
#
# This uses the REAL user profile, so it refuses to start if anything of this variant is already there: the data
# folder (%LOCALAPPDATA%\Shoalcade[ Dev], i.e. real saves), the install folder (%LOCALAPPDATA%\Shoalcade[Dev]App) or
# the uninstall entry, or a running Shoalcade.exe. Then:
#  1. a pretend save in the data folder BEFORE installing (install, reinstall and uninstall must all leave it alone)
#  2. Setup.exe --silent: files, Desktop + Start menu shortcuts, uninstall entry, core DLLs = cores.lock
#  3. the INSTALLED Shoalcade.exe starts (log line, window shown, alive ~20 s, Java in-process), PPSSPP files, Dolphin's
#     Sys and PCEE2's resources copied to the data folder (a pretend user PS2 BIOS + card there untouched); then closed
#  4. Setup.exe again over itself: still installed, the pretend save + settings byte-identical
#  5. Update.exe uninstall --silent (its message about the kept data folder closes itself after 60 s, or click OK):
#     install folder, shortcuts and uninstall entry gone; data folder + pretend save untouched (SHA-256)
#  6. the data folder is removed again, only because this script made it
# The installed player's own test cases (wine-test step 3) are left out: run windows/player/tests/run_tests.sh.
set -uo pipefail
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) ;; *) echo "Windows only (Git Bash); on Linux use wine-test/smoke.sh"; exit 2 ;; esac
V=${1:?usage: smoke.sh <dev|release>}
ROOT=$(cd "$(dirname "$0")/../../.." && pwd)
if [ "$V" = dev ]; then PACK=ShoalcadeDevApp; DATA="Shoalcade Dev"; NAME="Shoalcade Dev"
else PACK=ShoalcadeApp; DATA="Shoalcade"; NAME="Shoalcade"; fi
SETUP=$ROOT/dist/windows/$V/$PACK-win-Setup.exe
[ -f "$SETUP" ] || { echo "no $SETUP: run tools/build_windows_release.sh $V first"; exit 2; }
export MSYS_NO_PATHCONV=1 # reg's /v and friends reach Windows as they are; paths are converted by hand (w)
w() { cygpath -w "$1"; }
psh() { powershell.exe -NoProfile -NonInteractive -Command "$1" | tr -d '\r'; }

LOCAL=$(cygpath -u "$LOCALAPPDATA")
DESKTOP=$(cygpath -u "$(psh "[Environment]::GetFolderPath('Desktop')")")
STARTMENU=$(cygpath -u "$(psh "[Environment]::GetFolderPath('StartMenu')")")
INST=$LOCAL/$PACK
CUR=$INST/current
DATAD=$LOCAL/$DATA
UNKEY="HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\$PACK"
PASS=0 FAIL=0
ok() { echo "PASS  $*"; PASS=$((PASS + 1)); }
bad() { echo "FAIL  $*"; FAIL=$((FAIL + 1)); }
check() { if eval "$2"; then ok "$1"; else bad "$1"; fi; }
running() { tasklist /FO CSV /NH 2>/dev/null | tr -d '\r' | grep -i -q "^\"$1\""; }
# Velopack's Setup/Update return before their helpers finish: wait (up to 2 min) until none is left.
settle() { sleep 3; for _ in $(seq 1 60); do running Setup.exe || running Update.exe || running "$PACK-win-Setup.exe" || return 0; sleep 2; done; }
has_key() { reg query "$UNKEY" >/dev/null 2>&1; }
# Setup.exe may start the app when it is done: close any copy running from the install folder (only ours).
stop_installed() {
  psh "Get-CimInstance Win32_Process -Filter \"Name='Shoalcade.exe'\" | Where-Object { \$_.ExecutablePath -like '$(w "$INST")\\*' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force }" >/dev/null
  sleep 2
}
desk_lnk() { find "$DESKTOP" -maxdepth 1 -iname "$NAME.lnk" 2>/dev/null | head -1; }
start_lnk() { find "$STARTMENU" -iname "$NAME.lnk" 2>/dev/null | head -1; }

echo "== 0. nothing of $NAME may be here already"
refuse=
[ -e "$DATAD" ] && refuse+=" data folder $DATAD (real saves?);"
[ -e "$INST" ] && refuse+=" install folder $INST;"
has_key && refuse+=" uninstall entry $UNKEY;"
running Shoalcade.exe && refuse+=" a running Shoalcade.exe;"
if [ -n "$refuse" ]; then echo "REFUSED:$refuse remove it first (this test installs and uninstalls for real)"; exit 2; fi
echo "   none found; Desktop $DESKTOP, Start menu $STARTMENU"

echo "== 1. pretend save before install"
mkdir -p "$DATAD"; MADE_DATA=1
SAVE="$DATAD/library/saves/smoketest/0123456789abcdef/game.sav"
mkdir -p "$(dirname "$SAVE")"
head -c 131072 /dev/urandom > "$SAVE"
SAVE_SHA=$(sha256sum "$SAVE" | cut -d' ' -f1)
printf 'smoke-test settings\n' > "$DATAD/smoketest-settings.txt"
data_sum() { (cd "$DATAD" && find library smoketest-settings.txt -type f -print0 | sort -z | xargs -0 sha256sum | sha256sum | cut -d' ' -f1); }
DATA_SHA=$(data_sum)
# The user's own PS2 BIOS + memory card in the system folder the launcher copies PCEE2's resources into: never touched.
mkdir -p "$DATAD/system/pcsx2/bios" "$DATAD/system/pcsx2/memcards"
echo 'pretend user BIOS' > "$DATAD/system/pcsx2/bios/user-bios.bin"
echo 'pretend user card' > "$DATAD/system/pcsx2/memcards/Mcd001.ps2"
user_sys_sum() { (cd "$DATAD/system/pcsx2" && find bios memcards -type f -print0 | sort -z | xargs -0 sha256sum | sha256sum | cut -d' ' -f1); }
USER_SYS_SHA=$(user_sys_sum)
NCORES=$(grep -v '^#' "$ROOT/windows/cores/cores.lock" | awk -F'\t' '$2=="win"' | wc -l)

echo "== 2. install (Setup.exe --silent)"
t0=$(date +%s)
"$SETUP" --silent; settle; stop_installed
echo "   took $(( $(date +%s) - t0 )) s"
check "installed into %LOCALAPPDATA%\\$PACK (not the data folder)" "[ -f '$CUR/Shoalcade.exe' ] && [ -f '$INST/Update.exe' ]"
check "Java runtime, jars, launcher.cfg" "[ -f '$CUR/runtime/bin/server/jvm.dll' ] && [ -f '$CUR/app/launcher.cfg' ] && [ -f '$CUR/app/skiko-windows-x64.dll' ]"
check "player + SDL3 + $NCORES cores (cores.lock)" "[ -f '$CUR/app/player/ShoalcadePlayer.exe' ] && [ -f '$CUR/app/player/SDL3.dll' ] && [ \$(ls '$CUR/app/cores/'*_libretro.dll | wc -l) = $NCORES ]"
check "PPSSPP files + stamp" "[ -f '$CUR/app/system/PPSSPP/LICENSE.TXT' ] && [ -s '$CUR/app/system/PPSSPP.stamp' ]"
check "Dolphin Sys + stamp (only Sys\)" "[ -f '$CUR/app/system/dolphin-emu/Sys/codehandler.bin' ] && [ -s '$CUR/app/system/dolphin-emu.stamp' ] && [ \"\$(ls -A '$CUR/app/system/dolphin-emu')\" = Sys ]"
check "PCEE2 resources + stamp (only resources\)" "[ -f '$CUR/app/system/pcsx2/resources/GameIndex.yaml' ] && [ -s '$CUR/app/system/pcsx2.stamp' ] && [ \"\$(ls -A '$CUR/app/system/pcsx2')\" = resources ]"
check "no BIOS, memory card or key file installed" "[ -z \"\$(find '$CUR' \( -type d \( -iname bios -o -iname memcards \) \) -o -iname '*.ps2' -o -iname IPL.bin -o -iname 'scph*' -o -iname '*keys*.txt')\" ]"
check "licences + SOURCE.txt" "[ -f '$CUR/LICENSE.txt' ] && [ -f '$CUR/SOURCE.txt' ] && [ -f '$CUR/licences/README.txt' ]"
check "core DLLs identical to cores.lock" "(cd '$CUR/app/cores' && grep -v '^#' '$ROOT/windows/cores/cores.lock' | awk -F'\t' '\$2==\"win\"{print \$8\"  \"\$6}' | sha256sum -c --quiet)"
check "launcher.cfg passes the source commit (About link)" "grep -qE '^opt=-Dshoalcade.source.commit=[0-9a-f]{40}\$' '$CUR/app/launcher.cfg'"
check "Desktop shortcut ($NAME.lnk)" "[ -n \"\$(desk_lnk)\" ]"
check "Start menu shortcut ($NAME.lnk)" "[ -n \"\$(start_lnk)\" ]"
check "uninstall entry in the registry" "has_key"
check "pretend save untouched by install" "[ \"\$(sha256sum '$SAVE' | cut -d' ' -f1)\" = '$SAVE_SHA' ]"

echo "== 3. installed Shoalcade.exe: Java starts, window opens"
LOG=$DATAD/logs/shoalcade.log
APID=$(psh "(Start-Process -FilePath '$(w "$CUR/Shoalcade.exe")' -WorkingDirectory '$(w "$CUR")' -PassThru).Id")
echo "   pid $APID"
for _ in $(seq 1 60); do [ -f "$LOG" ] && grep -q ' starting; data in' "$LOG" && break; sleep 1; done
TITLE=
for _ in $(seq 1 60); do
  TITLE=$(psh "(Get-Process -Id $APID -ErrorAction SilentlyContinue).MainWindowTitle")
  [ -n "$TITLE" ] && break; sleep 1
done
echo "   window title: ${TITLE:-(none)}"
sleep 20
check "app started (its log line)" "grep -q '$NAME .* starting; data in C:.*$DATA' '$LOG'"
check "its window is shown" "[ -n '$TITLE' ]"
check "still running ~20 s after the window" "psh 'Get-Process -Id $APID -ErrorAction SilentlyContinue' | grep -q ."
KIDS=$(psh "Get-CimInstance Win32_Process -Filter 'ParentProcessId=$APID' | ForEach-Object { \$_.Name }")
check "Java in-process (no java/javaw child of Shoalcade.exe)" "! grep -qiE '^javaw?[.]exe\$' <<<\"\$KIDS\""
check "no exception from the app" "! grep -q 'Exception in thread' '$LOG'"
check "PPSSPP files copied to the data folder" "[ -f '$DATAD/system/PPSSPP/LICENSE.TXT' ] && cmp -s '$DATAD/system/PPSSPP.stamp' '$CUR/app/system/PPSSPP.stamp'"
check "Dolphin Sys copied to the data folder" "[ -f '$DATAD/system/dolphin-emu/Sys/codehandler.bin' ] && cmp -s '$DATAD/system/dolphin-emu.stamp' '$CUR/app/system/dolphin-emu.stamp' && diff -rq '$CUR/app/system/dolphin-emu/Sys' '$DATAD/system/dolphin-emu/Sys' >/dev/null"
check "PCEE2 resources copied to the data folder" "[ -f '$DATAD/system/pcsx2/resources/GameIndex.yaml' ] && cmp -s '$DATAD/system/pcsx2.stamp' '$CUR/app/system/pcsx2.stamp' && diff -rq '$CUR/app/system/pcsx2/resources' '$DATAD/system/pcsx2/resources' >/dev/null"
check "user's PS2 BIOS + memory card untouched by the copy" "[ \"\$(user_sys_sum)\" = '$USER_SYS_SHA' ] && [ \$(find '$DATAD/system/pcsx2/bios' '$DATAD/system/pcsx2/memcards' -type f | wc -l) = 2 ]"
psh "\$p = Get-Process -Id $APID -ErrorAction SilentlyContinue; if (\$p) { [void]\$p.CloseMainWindow(); if (-not \$p.WaitForExit(15000)) { Stop-Process -Id $APID -Force } }" >/dev/null
check "app closed" "! psh 'Get-Process -Id $APID -ErrorAction SilentlyContinue' | grep -q ."

echo "== 4. Setup.exe again over itself, keeping user data"
"$SETUP" --silent; settle; stop_installed
check "same-version Setup over itself: app still installed" "[ -f '$CUR/Shoalcade.exe' ] && [ -f '$CUR/app/launcher.cfg' ]"
check "same-version Setup: pretend save + settings byte-identical" "[ \"\$(data_sum)\" = '$DATA_SHA' ]"
check "shortcuts still there" "[ -n \"\$(desk_lnk)\" ] && [ -n \"\$(start_lnk)\" ]"
stop_installed

echo "== 5. uninstall (Update.exe uninstall --silent; its message closes itself after 60 s)"
t0=$(date +%s)
"$INST/Update.exe" uninstall --silent; settle
for _ in $(seq 1 30); do [ -e "$INST" ] || break; sleep 2; done
echo "   took $(( $(date +%s) - t0 )) s"
check "install folder gone" "[ ! -e '$INST' ]"
[ -e "$INST" ] && ls -A "$INST" | sed 's/^/   left: /'
check "shortcuts removed" "[ -z \"\$(desk_lnk)\" ] && [ -z \"\$(start_lnk)\" ]"
check "uninstall entry removed" "! has_key"
check "data folder kept" "[ -d '$DATAD/library' ] && [ -f '$DATAD/smoketest-settings.txt' ]"
check "pretend save kept, same bytes" "[ \"\$(sha256sum '$SAVE' | cut -d' ' -f1)\" = '$SAVE_SHA' ]"
check "pretend save + settings byte-identical" "[ \"\$(data_sum)\" = '$DATA_SHA' ]"
[ -d "$LOCAL/velopack" ] && echo "   (Velopack's own log folder stays: $LOCAL/velopack)"

echo "== 6. clean up"
if [ "${MADE_DATA:-0}" = 1 ] && [ -d "$DATAD" ]; then rm -rf "$DATAD" && echo "   removed the data folder this test made: $DATAD"; fi

echo
echo "$V: $PASS passed, $FAIL failed"
[ "$FAIL" = 0 ]
