import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// Shoalcade for Windows: the desktop shell (plan docs/plan-windows.md §4-§5, D179).
//
// Code sharing (plan §5, step 1): the phone app's own pure Kotlin files are compiled here BY PATH from
// android/app/src/main/java. Nothing is copied and the Android app is not changed. Their phone unit tests are
// compiled the same way from android/app/src/test/java and run against this build too.
//
// Build types: a Dev build ("Shoalcade Dev", own data folder, allows http://localhost) by default, like the phone's
// debug build (D152). The real one: -Pshoalcade.release=true.

plugins {
    kotlin("jvm") version "2.0.21" // same Kotlin as the phone app (android/build.gradle.kts)
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.3"
}

val androidApp: File = file("../../android/app")
val phoneMain: File = File(androidApp, "src/main/java")
val phoneTest: File = File(androidApp, "src/test/java")
private val pkg = "uk/benmarks/shoalcade/"

/** The phone files with no Android imports (plan §5), compiled as they are. */
val sharedMain = listOf(
    "Celio", "CheatSync", "Cheats", "Compat", "Console", "ConsoleBar", "FastForward", "GameLayout", "GbClock",
    "JellyfinClient", "LibraryFilter", "LinkRelay", "N64Save", "OrphanSaves", "PsxCard", "RomCheck", "SavePlan",
    "ServerStatus", "SettingsModel", "SnapshotGuard",
    // Not Android-free, but their only Android lines are Context.filesDir / getSharedPreferences, key and axis code
    // numbers, screen orientation numbers or SystemClock. The desktop build supplies stand-ins for exactly those
    // (src/main/kotlin/android/), so these phone files compile unchanged too, and the rules in them exist once:
    // Storage (save files, backups, atomic writes, zip-slip-safe unzip, folder-save zips), GameSort (+ LibraryPrefs),
    // PlayTime (+ the play time queue), and what the shared files above need: PadLayout (FastForward), GameMenu,
    // Controls and DisplayPrefs (SettingsModel).
    "Storage", "GameSort", "PlayTime", "PadLayout", "GameMenu", "Controls", "DisplayPrefs",
    // Saves phase (docs/windows/phase3-saves.md): the phone's save sync itself. SaveSync.kt's Android lines are its
    // wiring class (Context, the phone's SessionStore, SyncRename, AutoUpload.later); the desktop supplies those
    // names (SessionStore.kt, desktop SyncRename/AutoUpload), so SaveSyncCore and SyncAll run here unchanged.
    "SaveSync", "SyncAll",
    // Link play (docs/windows/phase5-link.md): Link.kt for its rules, PIN handshake (D133) and LinkPrefs, so the PC
    // speaks to a phone with the phone's own code. Its Android-only lobby compiles against stand-ins and is never run.
    "Link",
    // 3DS phase (docs/windows/phase6-3ds.md): the 3DS check's rules (verdict, speed line, saved result), unchanged.
    // Its phone-only pre-check names ActivityManager / PackageManager / Build.SUPPORTED_ABIS: stand-ins here, never run.
    "N3dsCheck",
    // Disc sets (D-8): every disc, then the .m3u, the shared rule.
    "DiscSets",
).map { "$pkg$it.kt" }.toSet()

/**
 * Phone unit tests that only exercise the shared files (or the fake server). The rest touch Android-only classes;
 * the list and the reason for each one left out are in docs/windows/phase2-shell.md.
 */
val sharedTests = listOf(
    "AppUpdateTest", "AxisRestTest", "CelioFakes", "CelioTest", "CheatSyncTest", "CheatsTest", "ConsoleBarTest",
    "ControlsTest", "DisplayPrefsTest", "DrawerNavTest", "FakeShoalcade", "FolderSaveTest", "GameLayoutTest",
    "GameSortTest", "GbClockTest", "JellyfinClientTest", "LibraryFilterTest", "LogicTest", "MenuSectionsTest",
    "PlayTimeTest", "RomCheckTest", "SavePlanTest", "SnapshotGuardTest",
    // Saves phase: the phone's own save tests. TestbedIntegrationTest skips itself unless SC_TESTBED points at a
    // running test-bed. BackupRulesTest reads the Android manifest by path (no Android needed).
    "SaveSyncCoreTest", "ServerReachTest", "SyncAllTest", "AutoUploadTest", "BackupRulesTest", "PsxCardTest",
    "ExactSaveSizeTest", "TestbedIntegrationTest",
    // Link play phase: the phone's own link rule and relay tests.
    "LinkRulesTest", "LinkRelayTest",
    // Disc sets (D-8).
    "DiscSetTest",
).map { "$pkg$it.kt" }.toSet()

/** The phone app's versionName, read from its own build file, so both apps always carry the same number (plan §6). */
val phoneVersion: String = Regex("""versionName\s*=\s*"([^"]+)"""")
    .find(File(androidApp, "build.gradle.kts").readText())?.groupValues?.get(1)
    ?: throw GradleException("versionName not found in android/app/build.gradle.kts")
/** The phone app's versionCode: N3dsCheck.kt keeps it beside a 3DS check result (re-run after an app update). */
val phoneVersionCode: String = Regex("""versionCode\s*=\s*(\d+)""")
    .find(File(androidApp, "build.gradle.kts").readText())?.groupValues?.get(1)
    ?: throw GradleException("versionCode not found in android/app/build.gradle.kts")
val release: Boolean = (findProperty("shoalcade.release") as String?) == "true"

val buildConfigDir = layout.buildDirectory.dir("generated/buildconfig")
val makeBuildConfig by tasks.registering {
    val out = buildConfigDir
    val version = phoneVersion
    val debug = !release
    val code = phoneVersionCode
    inputs.property("version", version)
    inputs.property("code", code)
    inputs.property("debug", debug)
    outputs.dir(out)
    doLast {
        val f = out.get().file("uk/benmarks/shoalcade/BuildConfig.kt").asFile
        f.parentFile.mkdirs()
        f.writeText(
            """
            |package uk.benmarks.shoalcade
            |
            |/** Generated by windows/app/build.gradle.kts: the desktop stand-in for the phone's BuildConfig. */
            |object BuildConfig {
            |    /** The phone app's versionName (android/app/build.gradle.kts): both apps share one version number. */
            |    const val VERSION_NAME: String = "$version"
            |    /** The phone app's versionCode (the 3DS check's "app updated" rule). */
            |    const val VERSION_CODE: Int = $code
            |    /** True for the Dev build ("Shoalcade Dev"), false for -Pshoalcade.release=true. */
            |    const val DEBUG: Boolean = $debug
            |}
            |""".trimMargin()
        )
    }
}

kotlin { jvmToolchain(17) }

sourceSets {
    main {
        kotlin {
            srcDir("src/main/kotlin")
            srcDir(phoneMain)
            srcDir(buildConfigDir)
            // Only the listed phone files: every other file under android/ is left out.
            exclude { e -> !e.isDirectory && e.file.startsWith(phoneMain) && e.relativePath.pathString !in sharedMain }
        }
    }
    main {
        // The logo, by path (docs/assets/logo.svg): window icon and login screen. The cores lock (windows/cores):
        // which core file plays each console and the snapshot tag (desktop/Cores.kt).
        // The 3DS check scene (tools/n3ds-check, the same file the phone ships) is packed in too (D208).
        // The GameCube / Wii check scenes (tools/gc-check, our own libogc homebrew, D-7).
        resources { srcDir("../../docs/assets"); srcDir("../cores"); srcDir("../../android/app/src/main/assets"); srcDir("../../tools")
            include("logo.svg", "cores.lock", "n3ds/check.3dsx", "gc-check/gc-check.dol", "gc-check/wii-check.dol") }
    }
    test {
        kotlin {
            srcDir("src/test/kotlin")
            srcDir(phoneTest)
            exclude { e -> !e.isDirectory && e.file.startsWith(phoneTest) && e.relativePath.pathString !in sharedTests }
        }
    }
}
tasks.named("compileKotlin") { dependsOn(makeBuildConfig) }

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    // No materialIconsExtended (37.7 MB): the few icons beyond the core set are in ui/PocketIcons.kt.
    // Same versions as the phone app (android/app/build.gradle.kts).
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0") // Dispatchers.Main for Compose Desktop
    implementation("org.json:json:20240303") // Android has org.json built in; the desktop needs the real one
    implementation("com.squareup.okhttp3:okhttp:4.12.0") // Celio Link's WebSocket, as on the phone
    implementation("org.jmdns:jmdns:3.5.9") // Link play: find and show hosts on the Wi-Fi (the phone's NsdManager; docs/windows/phase5-link.md)
    // DPAPI (CryptProtectData) for the saved login on Windows.
    implementation("net.java.dev.jna:jna:5.15.0")
    implementation("net.java.dev.jna:jna-platform:5.15.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

tasks.test {
    // Same as the phone's unit tests (android/app/build.gradle.kts testOptions): PATCH on the JVM's
    // HttpURLConnection, and no keep-alive cleaner holding a half-read body.
    jvmArgs("--add-opens=java.base/java.net=ALL-UNNAMED", "-Djava.net.preferIPv4Stack=true") // as the app (mDNS)
    systemProperty("http.keepAlive", "false")
    maxHeapSize = "512m"
    // The player test needs the Linux player and cores; it skips itself (with a message) when they're missing.
    listOf("shoalcade.player", "shoalcade.cores", "shoalcade.cores.linux", "shoalcade.testroms", "shoalcade.testbed").forEach { k ->
        findProperty(k)?.let { systemProperty(k, it) }
    }
    testLogging { events("failed"); showStandardStreams = false; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

val appName = if (release) "Shoalcade" else "Shoalcade Dev"

compose.desktop {
    application {
        mainClass = "uk.benmarks.shoalcade.desktop.MainKt"
        // java.net: the shared JellyfinClient sets PATCH by reflection (as in the tests), and SaveSource reads the
        // JDK's own URL handlers once.
        // preferIPv4Stack: with IPv6 off on the network adapter, Windows' dual-stack multicast socket refuses
        // setNetworkInterface, so link play's mDNS finds nothing (cost: an IPv6-only Jellyfin address won't work).
        jvmArgs += listOf("-Xmx512m", "--add-opens=java.base/java.net=ALL-UNNAMED", "-Djava.net.preferIPv4Stack=true")
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Deb)
            packageName = appName
            // Installer versions must be numeric: "0.12.0" stays, "0.6.0-dev2" becomes "0.6.0".
            packageVersion = phoneVersion.substringBefore('-')
            description = "Play your own games from your Jellyfin server"
            vendor = "Shoalcade"
            copyright = "GPL-3.0-or-later"
            modules("java.net.http", "java.naming", "jdk.crypto.ec")
            windows {
                menuGroup = appName
                // A Dev build installs next to the real one (D152): its own upgrade id.
                upgradeUuid = if (release) "6d0c5a3e-6f4b-4f1e-9a37-1c7c3b6a2f01" else "b1f2e4a8-3c5d-4e7f-8a9b-0c1d2e3f4a5b"
            }
        }
    }
}

// Test tool: the fake server for looking at the real window (see src/test/.../DemoServer.kt). Not part of the app.
tasks.register<JavaExec>("runDemoServer") {
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "uk.benmarks.shoalcade.desktop.DemoServerKt"
    maxHeapSize = "256m"
    (findProperty("demo.data") as String?)?.let { args(it) }
}

// Test tool: the fake server with saves to look at (src/test/.../DemoSaves.kt). Not part of the app.
tasks.register<JavaExec>("runDemoSaves") {
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "uk.benmarks.shoalcade.desktop.DemoSavesKt"
    maxHeapSize = "256m"
    jvmArgs("--add-opens=java.base/java.net=ALL-UNNAMED")
    args(listOfNotNull(findProperty("demo.data") as String?, findProperty("demo.rom") as String?, findProperty("demo.png") as String?))
}

// Test tool: writes the test run's classpath, so the window and the demo server can be started with plain java.
tasks.register("writeTestClasspath") {
    dependsOn("testClasses")
    val cp = sourceSets.test.get().runtimeClasspath
    doLast { layout.buildDirectory.file("test-classpath.txt").get().asFile.writeText(cp.asPath) }
}
