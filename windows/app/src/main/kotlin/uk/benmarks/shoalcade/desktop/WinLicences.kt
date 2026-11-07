package uk.benmarks.shoalcade.desktop

import java.io.File

/**
 * Everything the Windows installer ships, its licence and where its exact source lives (the Windows About screen).
 * GPL-3.0 §6 / MPL-2.0 §3.2: people who get the program must be told where the source is. The phone's list is
 * android/…/About.kt; this one adds the Windows-only parts (player, Java runtime, installer). The core commits are
 * those in windows/cores/cores.lock (WinLicencesTest checks every Windows core's commit is linked here).
 * The installed folder also holds every licence text (licences\) and SOURCE.txt, written by
 * windows/installer/licences.sh at build time from the same pins.
 */
object WinLicences {
    const val APP_SOURCE = "https://github.com/BBBannon/Shoalcade"

    data class Part(val name: String, val use: String, val licence: String, val source: String)

    /** The exact Shoalcade commit this build came from (Shoalcade.exe passes it from launcher.cfg); null in development. */
    fun buildCommit(): String? = System.getProperty("shoalcade.source.commit")?.takeIf { it.matches(Regex("[0-9a-f]{40}")) }

    fun appSource(): String = buildCommit()?.let { "$APP_SOURCE/tree/$it" } ?: APP_SOURCE

    /** The install folder (Shoalcade.exe's folder): app\resources is Compose's resources folder, two levels down. */
    fun installDir(): File? = System.getProperty("compose.application.resources.dir")
        ?.let { File(it).absoluteFile.parentFile?.parentFile }?.takeIf { File(it, "licences").isDirectory }

    val cores = listOf(
        Part("mGBA (0.11-212-7a12d6d)", "GB, GBC and GBA", "MPL-2.0", "https://github.com/libretro/mgba/tree/7a12d6d"),
        Part("gpSP (v1.1.0-5819380)", "GBA link play", "GPL-2.0-or-later", "https://github.com/libretro/gpsp/tree/5819380"),
        Part("melonDS DS 1.4.0 (commit f394adb)", "Nintendo DS", "GPL-3.0",
            "https://github.com/JesseTG/melonds-ds/tree/f394adbacb5722ee97c1b37c8064da9a25818310"),
        Part("melonDS (commit 16f127d, inside melonDS DS)", "Nintendo DS emulation", "GPL-3.0",
            "https://github.com/JesseTG/melonDS/tree/16f127dbb587a73371827ce79689c5d237c4b59b"),
        Part("PCSX ReARMed (r26l-119-gc881679)", "PlayStation (PS1)",
            "GPL-2.0-or-later (parts: lightrec LGPL-2.1-or-later, GNU lightning LGPL-3.0-or-later, libchdr and zstd BSD, libretro-common MIT)",
            "https://github.com/libretro/pcsx_rearmed/tree/c8816799b50388e61cfe237fe2cdbb7d8175f20a"),
        Part("Mupen64Plus-Next (3.0-GLES3 15bada8)", "Nintendo 64",
            "GPL-3.0 as combined (parts: mupen64plus-core GPL-2.0-or-later, GLideN64 GPL-2.0, xBRZ GPL-3.0, GNU lightning LGPL-3.0-or-later, cxd4 CC0, parallel-rsp, parallel-rdp, angrylion and libretro-common MIT, xxHash BSD-2, zlib, libpng and minizip zlib)",
            "https://github.com/libretro/mupen64plus-libretro-nx/tree/15bada85906593376e8fd697f1b0b3af3a20f512"),
        Part("PPSSPP (commit 902e5cc) + one-line Windows build fix", "PSP, plus its support files (fonts, compatibility list)",
            "GPL-2.0-or-later (PSPSDK parts BSD; FFmpeg LGPL-2.1-or-later)",
            "https://github.com/hrydgard/ppsspp/tree/902e5ccd8c1400b4387b73942491cded655e5f9c (fix: tools/ppsspp-patches in Shoalcade's source)"),
        Part("Azahar 2126.2 (commit 219d743) + Shoalcade patches", "Nintendo 3DS", "GPL-3.0-or-later (some parts GPL-2.0-or-later)",
            "https://github.com/azahar-emu/azahar/tree/219d743a1e698101fe1ab8bef43e6563126c3c27 (patches: tools/azahar-patches in Shoalcade's source)"),
        Part("Dolphin (commit b93001e)", "GameCube and Wii", "GPL-2.0-or-later",
            "https://github.com/libretro/dolphin/tree/b93001ed50c8b34e6ead4eed713c2005a80386e4"),
        Part("LRPS2 (commit 083de59) + Windows build fixes", "PlayStation 2", "GPL-3.0",
            "https://github.com/libretro/parallel-ps2/tree/083de59f52159ce4dd2f63a63f74fa9f0e936ee3 (fixes: tools/lrps2-patches in Shoalcade's source)"),
        Part("PCEE2 (commit 5719627) + Shoalcade patches", "PlayStation 2 (second choice)",
            "GPL-3.0-or-later (libraries compiled in: see its THIRD_PARTY_LICENSES)",
            "https://github.com/WizzardSK/pcee2-libretro/tree/5719627731beb90e379ff1be1223391b7f563b3b (patches: tools/pcee2-patches in Shoalcade's source)"),
        Part("Xenia Edge libretro (commit 8fd1709)", "Xbox 360", "BSD-3-Clause (bundled libraries under their own licences)",
            "https://github.com/WizzardSK/xenia-libretro/tree/8fd1709218e98767d4468a482656adc64f45a8ef"),
    )

    val player = listOf(
        Part("SDL 3.2.24", "Player window, sound and controllers", "zlib", "https://github.com/libsdl-org/SDL/tree/release-3.2.24"),
        Part("Dear ImGui 1.92.4", "Player pause menu", "MIT", "https://github.com/ocornut/imgui/tree/v1.92.4"),
        Part("nlohmann/json 3.11.3", "Player messages", "MIT", "https://github.com/nlohmann/json/tree/v3.11.3"),
        Part("stb_image 2.30, stb_image_write 1.16", "Snapshot pictures", "MIT or public domain",
            "https://github.com/nothings/stb/tree/003501f6d7a8668ce8b6d875e34dd97ad0d5d5b5"),
        Part("libretro.h", "Emulator core interface", "MIT",
            "https://github.com/libretro/libretro-common/tree/d9597c359a5ca8399a4e244f816e7e975a92a2aa"),
        // Phase 6: packed in the app for the 3DS check (the phone's About.kt line).
        Part("3DS check scene", "The 3DS check's test scene", "GPL-3.0-or-later; based on devkitPro 3ds-examples (Public Domain); libctru and citro3d (zlib)",
            "https://github.com/devkitPro/3ds-examples (scene: tools/n3ds-check in Shoalcade's source)"),
        // D-7: packed in the app for the GameCube and Wii check.
        Part("GameCube and Wii check scenes", "The GameCube and Wii check's test scenes",
            "GPL-3.0-or-later; built with devkitPro libogc (zlib-style licence)",
            "https://github.com/devkitPro/libogc (scenes: tools/gc-check in Shoalcade's source)"),
        Part("Atkinson Hyperlegible 1.006", "Pause menu font", "SIL Open Font License 1.1",
            "https://github.com/googlefonts/atkinson-hyperlegible/tree/1cb311624b2ddf88e9e37873999d165a8cd28b46"),
        Part("MinGW-w64 runtime (GCC 14.2, mingw-w64 12.0)", "Built into the player, launcher and cores",
            "GPL-3.0 with the GCC Runtime Library Exception; mingw-w64 runtime ZPL/MIT-style",
            "https://sources.debian.org/src/gcc-mingw-w64/ and https://sources.debian.org/src/mingw-w64/"),
    )

    val app = listOf(
        Part("Eclipse Temurin 17.0.20.1+1 (Java runtime)", "Runs the app", "GPL-2.0 with the Classpath Exception",
            "https://github.com/adoptium/jdk17u/tree/jdk-17.0.20.1+1"),
        Part("Compose Multiplatform 1.7.3", "App screens", "Apache-2.0", "https://github.com/JetBrains/compose-multiplatform/tree/v1.7.3"),
        Part("Skiko 0.8.18 (with Skia)", "Drawing the app", "Apache-2.0 (Skia: BSD-3-Clause)", "https://github.com/JetBrains/skiko/tree/v0.8.18"),
        Part("Kotlin 2.0.21, kotlinx.coroutines 1.9.0, kotlinx-datetime 0.6.0, atomicfu 0.23.2", "App language and libraries",
            "Apache-2.0", "https://github.com/JetBrains/kotlin/tree/v2.0.21, https://github.com/Kotlin/kotlinx.coroutines/tree/1.9.0"),
        Part("AndroidX collection, lifecycle, annotation", "App framework", "Apache-2.0",
            "https://android.googlesource.com/platform/frameworks/support"),
        Part("OkHttp 4.12.0 and Okio 3.6.0", "Talking to your Jellyfin", "Apache-2.0", "https://github.com/square/okhttp/tree/parent-4.12.0"),
        Part("JNA 5.15.0", "Windows sign-in protection (DPAPI)", "Apache-2.0 (or LGPL-2.1; Apache-2.0 chosen)",
            "https://github.com/java-native-access/jna/tree/5.15.0"),
        Part("JSON-java 20240303", "Reading server replies", "Public domain", "https://github.com/stleary/JSON-java/tree/20240303"),
        Part("JmDNS 3.5.9", "Link play: finding hosts on your Wi-Fi", "Apache-2.0", "https://github.com/jmdns/jmdns/tree/3.5.9"),
        Part("SLF4J API 2.0.7", "Used by JmDNS", "MIT", "https://github.com/qos-ch/slf4j/tree/v_2.0.7"),
        Part("Velopack 1.2.161", "Installer and uninstaller", "MIT", "https://github.com/velopack/velopack/tree/1.2.161"),
    )

    val all: List<Part> get() = cores + player + app
}
