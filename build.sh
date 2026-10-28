#!/usr/bin/env bash
# Builds the app and copies it to dist/.
#   ./build.sh          debug APK  -> dist/Shoalcade-<version>-debug.apk, plus Shoalcade-debug.apk for tools/test-rig.sh
#   ./build.sh release  release APK, signed with Shoalcade's own key (D125; stops if the key is missing)
#                       -> dist/Shoalcade-<version>.apk + dist/Shoalcade-<version>-mapping.txt (to read crash traces)
set -e
cd "$(dirname "$0")/android"
# Tools: JAVA_HOME (JDK 17) and ANDROID_HOME (Android SDK) from the environment; if unset, the portable copies in
# ~/Android/ (jdk17/, sdk/) are used. ANDROID_TOOLS can point somewhere else.
TOOLS="${ANDROID_TOOLS:-$(cygpath -m "$HOME" 2>/dev/null || echo "$HOME")/Android}"
export JAVA_HOME="${JAVA_HOME:-$TOOLS/jdk17/jdk-17.0.20.1+1}"
export ANDROID_HOME="${ANDROID_HOME:-$TOOLS/sdk}"
[ -d "$JAVA_HOME" ] || { echo "JDK 17 not found: set JAVA_HOME" >&2; exit 1; }
[ -d "$ANDROID_HOME" ] || { echo "Android SDK not found: set ANDROID_HOME" >&2; exit 1; }
VERSION=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)
mkdir -p ../dist
if [ "${1:-}" = "release" ]; then
  shift
  ./gradlew.bat assembleRelease --console=plain "$@"
  cp "app/build/outputs/apk/release/Shoalcade-$VERSION.apk" "../dist/Shoalcade-$VERSION.apk"
  cp app/build/outputs/mapping/release/mapping.txt "../dist/Shoalcade-$VERSION-mapping.txt"
  echo "APK: $(cd ../dist && pwd)/Shoalcade-$VERSION.apk (mapping: Shoalcade-$VERSION-mapping.txt)"
else
  ./gradlew.bat assembleDebug --console=plain "$@"
  cp app/build/outputs/apk/debug/app-debug.apk ../dist/Shoalcade-debug.apk
  cp app/build/outputs/apk/debug/app-debug.apk "../dist/Shoalcade-$VERSION-debug.apk"
  echo "APK: $(cd ../dist && pwd)/Shoalcade-$VERSION-debug.apk"
fi
