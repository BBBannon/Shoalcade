# Shoalcade release keep rules (D125). R8 shrinks, optimises and renames everything not listed here.
# Libraries that ship their own rules (Compose, AndroidX lifecycle/activity, coroutines, Tink via security-crypto)
# are not repeated. The app's own JSON is hand-parsed with org.json (no reflection), networking is HttpURLConnection
# (no OkHttp/Retrofit), and there are no Parcelable/Serializable classes, WebView JS bridges or ServiceLoader uses.
# Manifest entries (activities, service, application) are kept automatically by AAPT's generated rules.

# --- LibretroDroid (emulator front-end, GPL-3.0) ---
# Its AAR ships NO keep rules, but its native library (liblibretrodroid.so) talks to these classes by name over JNI:
# Java_com_swordfish_libretrodroid_LibretroDroid_* native methods, plus FindClass/GetMethodID/GetFieldID on
# Controller, DetachedVirtualFile, GLRetroShader, RetroException and Variable (constructors, fields and callbacks).
# The package is small (about 40 classes), so keep it whole rather than guess at each member the C++ code touches.
-keep class com.swordfish.libretrodroid.** { *; }

# --- Our link-play status library (libsclinkstate.so, src/main/cpp/linkstate.c) ---
# JNI finds these by exact name: Java_uk_benmarks_shoalcade_LinkNative_state/received/sent/reset.
-keep class uk.benmarks.shoalcade.LinkNative {
    native <methods>;
}

# Readable crash stack traces once mapped back with the kept mapping file (dist/Shoalcade-<version>-mapping.txt).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- Tink (inside androidx.security:security-crypto, holds the login token) ---
# Compile-time-only annotations Tink refers to but never needs at run time (Google's documented fix).
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.Nullable
-dontwarn javax.annotation.concurrent.GuardedBy
