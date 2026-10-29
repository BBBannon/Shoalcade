#!/usr/bin/env bash
# Builds the Shoalcade server plugin and its install zip.
#   server/build.sh            -> dist/server/Shoalcade_s<ver>[-dev<n>].zip  (+ unpacked folder next to it)
#   server/build.sh --install  -> also installs it into the test-bed Jellyfin (.testbed/jf), never anywhere else
# Needs: .NET 10 SDK (uses .testbed/dotnet if present), curl, sha256sum, tar, zip (or python).
set -euo pipefail
cd "$(dirname "$0")"
ROOT="$(cd .. && pwd)"
export DOTNET_CLI_TELEMETRY_OPTOUT=1 DOTNET_NOLOGO=1
DOTNET="$ROOT/.testbed/dotnet/dotnet.exe"; [ -x "$DOTNET" ] || DOTNET=dotnet
VER=$(sed -n 's:.*<ShoalcadeServerVersion>\(.*\)</ShoalcadeServerVersion>.*:\1:p' Directory.Build.props)
DEV=$(sed -n 's:.*<ShoalcadeDevBuild>\(.*\)</ShoalcadeDevBuild>.*:\1:p' Directory.Build.props)
DEV="${DEV:-0}"
FULL="$VER.0.$DEV"   # what Jellyfin sees, e.g. 0.2.0.1 (D88: 4th part = dev build, 0 = release)
if [ "$DEV" -gt 0 ]; then LABEL="s$VER dev $DEV"; TAG="s$VER-dev$DEV"; else LABEL="s$VER"; TAG="s$VER"; fi
# What Jellyfin's Catalogue shows for this build (plain text, no double quotes): CHANGELOG="..." server/build.sh
CHANGELOG="${CHANGELOG:-$LABEL. See ChangeLog.txt in the Shoalcade repo.}"
DL="$ROOT/.testbed/dl"; mkdir -p "$DL"

# 1. EmulatorJS (pinned + checked)
while read -r name ver sha; do
  case "$name" in ''|\#*) continue ;; esac
  f="$DL/ejs-$name.tgz"
  [ -f "$f" ] || curl -fsSL -o "$f" "https://registry.npmjs.org/@emulatorjs/$name/-/$name-$ver.tgz"
  echo "$sha  $f" | sha256sum -c --quiet - || { echo "SHA-256 mismatch for $name - refusing to build"; exit 1; }
done < emulatorjs.lock

# 1b. mkxp-z for the RPG Maker browser player (D147; pinned + checked, see mkxpz.lock). GitHub Actions artifacts
#     expire, so the zip is kept in .testbed/dl; it is fetched with the gh CLI only if it isn't there yet.
while read -r name ver sha art; do
  case "$name" in ''|\#*) continue ;; esac
  f="$DL/mkxpz-$name-$ver.zip"
  [ -f "$f" ] || gh api "repos/white-axe/mkxp-z/actions/artifacts/$art/zip" > "$f" || { rm -f "$f"; echo "Couldn't fetch mkxp-z $name ($ver): put the artifact zip at $f - refusing to build"; exit 1; }
  echo "$sha  $f" | sha256sum -c --quiet - || { echo "SHA-256 mismatch for mkxp-z $name - refusing to build"; exit 1; }
done < mkxpz.lock

# 2. Plugin DLL (warnings are errors)
"$DOTNET" build Jellyfin.Plugin.Shoalcade -c Release -warnaserror -nologo -v q

# 3. Assemble
OUT="$ROOT/dist/server/Shoalcade_$FULL"
rm -rf "$OUT" && mkdir -p "$OUT/ejs/cores/reports"
cp Jellyfin.Plugin.Shoalcade/bin/Release/net10.0/Jellyfin.Plugin.Shoalcade.dll "$OUT/"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
while read -r name ver sha; do
  case "$name" in ''|\#*) continue ;; esac
  mkdir -p "$TMP/$name" && tar -xzf "$DL/ejs-$name.tgz" -C "$TMP/$name"
  if [ "$name" = emulatorjs ]; then
    cp -r "$TMP/$name/package/data/." "$OUT/ejs/"
    cp "$TMP/$name/package/LICENSE" "$OUT/ejs/LICENSE.txt"
    rm -f "$OUT/ejs/cores/package.json" "$OUT/ejs/cores/README.md" "$OUT/ejs/compression/README.md" "$OUT/ejs/localization/README.md"
    # unRAR is never used (the player takes zip/7z only) and its licence isn't GPL-compatible: not shipped.
    rm -f "$OUT/ejs/compression/libunrar.js" "$OUT/ejs/compression/libunrar.wasm"
  else
    cp "$TMP/$name/package/"*.data "$OUT/ejs/cores/"
    cp "$TMP/$name/package/reports/"*.json "$OUT/ejs/cores/reports/"
  fi
done < emulatorjs.lock
# mkxp-z core -> ejs/mkxp-z/ (served at /shoalcade/ejs/mkxp-z/ like the EmulatorJS cores)
while read -r name ver sha art; do
  case "$name" in ''|\#*) continue ;; esac
  mkdir -p "$OUT/ejs/mkxp-z"
  python - "$DL/mkxpz-$name-$ver.zip" "$OUT/ejs/mkxp-z" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as z:
    for n in ("mkxp-z_libretro.js", "mkxp-z_libretro.wasm"):
        with z.open(n) as src, open(sys.argv[2] + "/" + n, "wb") as dst:
            dst.write(src.read())
PY
done < mkxpz.lock
cp "$ROOT/LICENSE" "$OUT/LICENSE.txt"
cp "$ROOT/THIRD_PARTY_NOTICES.md" "$OUT/THIRD_PARTY_NOTICES.md"   # incl. the JellyEMU MIT notice for the ported scanner code
cp "$ROOT/server/assets/thumb.png" "$OUT/thumb.png"               # plugin picture in Jellyfin's dashboard (imagePath)
cat > "$OUT/meta.json" <<JSON
{
  "category": "General",
  "changelog": "$CHANGELOG",
  "description": "Shoalcade for Jellyfin: play your own games on your Android phone (offline) and in the browser, with every save kept safe on your Jellyfin server.",
  "guid": "5a8b0f1e-7c3d-4e2a-9b6f-1d4c8e9a2f70",
  "name": "Shoalcade",
  "overview": "Your own game library in Jellyfin: phone app, browser player, safe saves",
  "owner": "BBBannon",
  "targetAbi": "12.3.0.0",
  "imagePath": "thumb.png",
  "timestamp": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "version": "$FULL",
  "status": "Active",
  "autoUpdate": false,
  "assemblies": [ "Jellyfin.Plugin.Shoalcade.dll" ]
}
JSON
# Phone app (D134): the release APK dist/Shoalcade-<versionName>.apk (versionName from android/app/build.gradle.kts)
# goes in as app/Shoalcade.apk plus app/app.json, so people can install the app from their own Jellyfin.
# SHOALCADE_APK=<path> picks another file; SHOALCADE_APK=none leaves it out. Either way an APK is only bundled if its
# own version (aapt) equals versionName and it is signed with the release key (apksigner), so the plugin can never
# hand out an old or debug app (security review M2). No APK of that name: the plugin still builds without it.
RELEASE_CERT_SHA256=e99bdb1a58797371a5f72b30112abdfd60e2ec59d42fe2c386c17cdf5bf3c672   # CN=Shoalcade, O=BBBannon
APP_ID=uk.benmarks.shoalcade
WANT=$(sed -n 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"\(.*\)".*/\1/p' "$ROOT/android/app/build.gradle.kts" | head -1)
[ -n "$WANT" ] || { echo "Couldn't read versionName from android/app/build.gradle.kts - refusing to build"; exit 1; }
APK="${SHOALCADE_APK:-}"
[ "$APK" = none ] && APK="" && NOAPK=1
if [ -z "$APK" ] && [ -z "${NOAPK:-}" ]; then
  APK="$ROOT/dist/Shoalcade-$WANT.apk"
  if [ ! -f "$APK" ]; then
    echo "WARNING: no dist/Shoalcade-$WANT.apk (the app's versionName is $WANT). Build the release APK first if this plugin is for people to install."
    APK=""
  fi
fi
if [ -n "$APK" ]; then
  [ -f "$APK" ] || { echo "SHOALCADE_APK not found: $APK"; exit 1; }
  TOOLS="${ANDROID_TOOLS:-$(cygpath -m "$HOME" 2>/dev/null || echo "$HOME")/Android}"   # same defaults as ../build.sh
  SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$TOOLS/sdk}}"
  BT=$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -1)
  AAPT="${BT}aapt"; [ -f "$AAPT.exe" ] && AAPT="$AAPT.exe"
  APKSIGNER="${BT}apksigner"; [ -f "$APKSIGNER.bat" ] && APKSIGNER="$APKSIGNER.bat"
  [ -n "$BT" ] && [ -f "$AAPT" ] && [ -f "$APKSIGNER" ] || {
    echo "Can't check $(basename "$APK"): no aapt/apksigner in $SDK/build-tools - refusing to bundle it (SHOALCADE_APK=none builds without the app)"; exit 1; }
  if [ -z "${JAVA_HOME:-}" ] && [ -d "$TOOLS/jdk17/jdk-17.0.20.1+1" ]; then export JAVA_HOME="$TOOLS/jdk17/jdk-17.0.20.1+1"; fi
  [ -n "${JAVA_HOME:-}" ] && export PATH="$JAVA_HOME/bin:$PATH"
  BADGE=$("$AAPT" dump badging "$APK" 2>/dev/null | head -1 || true)
  APKID=$(printf '%s' "$BADGE" | sed -n "s/.*package: name='\([^']*\)'.*/\1/p")
  APKVER=$(printf '%s' "$BADGE" | sed -n "s/.* versionName='\([^']*\)'.*/\1/p")
  [ "$APKID" = "$APP_ID" ] || { echo "REFUSED: $(basename "$APK") is not the Shoalcade app (package '${APKID:-unreadable}', expected $APP_ID)"; exit 1; }
  [ "$APKVER" = "$WANT" ] || {
    echo "REFUSED: $(basename "$APK") is version ${APKVER:-unknown}, but the app is now $WANT (android/app/build.gradle.kts)."
    echo "  Build the $WANT release APK first, or SHOALCADE_APK=none to build the plugin without the app."; exit 1; }
  CERTS=$("$APKSIGNER" verify --print-certs "$APK" 2>&1) || { echo "REFUSED: $(basename "$APK") failed signature checks:"; echo "$CERTS"; exit 1; }
  SIGNERS=$(printf '%s\n' "$CERTS" | sed -n 's/^Signer #[0-9]* certificate SHA-256 digest: \([0-9a-f]*\).*/\1/p' | tr -d '\r' | sort -u)
  [ "$SIGNERS" = "$RELEASE_CERT_SHA256" ] || {
    echo "REFUSED: $(basename "$APK") is not signed with the Shoalcade release key (signer SHA-256: ${SIGNERS:-none})."
    echo "  Only the signed release APK may go in the plugin, never a debug build."; exit 1; }
  mkdir -p "$OUT/app" && cp "$APK" "$OUT/app/Shoalcade.apk"
  python - "$OUT/app" "$APKVER" <<'PY'
import hashlib, json, os, sys
d, ver = sys.argv[1], sys.argv[2]
apk = os.path.join(d, "Shoalcade.apk")
h = hashlib.sha256()
with open(apk, "rb") as f:
    for block in iter(lambda: f.read(1 << 20), b""):
        h.update(block)
with open(os.path.join(d, "app.json"), "w", encoding="utf-8", newline="") as f:
    json.dump({"version": ver, "sha256": h.hexdigest(), "size": os.path.getsize(apk)}, f)
PY
  echo "Phone app included: $(basename "$APK") as app/Shoalcade.apk (version $APKVER, release-signed)"
else
  echo "Phone app not included (no dist/Shoalcade-$WANT.apk, or SHOALCADE_APK=none): the download page will say so."
fi

ZIP="$ROOT/dist/server/Shoalcade_$TAG.zip"   # Shoalcade_s0.2-dev2.zip; a release is Shoalcade_s1.0.zip
rm -f "$ZIP"
python - "$OUT" "$ZIP" <<'PY'
import os, sys, zipfile
src, dst = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as z:
    for d, _, files in os.walk(src):
        for f in files:
            p = os.path.join(d, f)
            z.write(p, os.path.relpath(p, src))
PY
echo "Built $ZIP ($(du -h "$ZIP" | cut -f1)): $LABEL = $FULL"

# 4. Optional: install into the test-bed only
if [ "${1:-}" = "--install" ]; then
  PLUG="$ROOT/.testbed/jf/data/plugins"
  "$ROOT/tools/testbed/jf.sh" stop
  rm -rf "$PLUG"/Shoalcade_* "$PLUG"/JellyGames_*   # test-bed only; JellyGames_ = the same plugin before the rename (D124)
  mkdir -p "$PLUG/Shoalcade_$FULL" && cp -r "$OUT/." "$PLUG/Shoalcade_$FULL/"
  "$ROOT/tools/testbed/jf.sh" start
fi
