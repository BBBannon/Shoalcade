import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val signingPropertiesFile: File = file(
    System.getenv("SHOALCADE_SIGNING_PROPERTIES")
        ?: (findProperty("shoalcade.signing.properties") as String?)
        ?: "${System.getProperty("user.home")}/.android-keys/shoalcade-release.properties"
)

android {
    namespace = "uk.benmarks.shoalcade"
    compileSdk = 35

    defaultConfig {
        applicationId = "uk.benmarks.shoalcade"
        minSdk = 30 // Android 11+
        targetSdk = 35
        versionCode = 14 // +1 for every build Ben installs
        // a0.6 dev 1 (D41, D88): sent to the server in every request. Dev builds are "0.6.0-devN"; launch is "1.0.0".
        versionName = "0.8.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // D125: release builds are signed with Shoalcade's own key, kept OUTSIDE the repo (default
    // ~/.android-keys/shoalcade-release.properties: storeFile, storePassword, keyAlias, keyPassword). Another path:
    // env SHOALCADE_SIGNING_PROPERTIES or -Pshoalcade.signing.properties=... Without it, debug builds work as normal
    // and release builds stop with a clear message (never a silently debug-signed or unsigned release).
    val releaseSigning = signingPropertiesFile.takeIf { it.isFile }?.let { f ->
        val p = Properties().apply { f.inputStream().use { load(it) } }
        signingConfigs.create("release") {
            storeFile = file(p.getProperty("storeFile"))
            storePassword = p.getProperty("storePassword")
            keyAlias = p.getProperty("keyAlias")
            keyPassword = p.getProperty("keyPassword")
            enableV2Signing = false // D125: v3 only (L19); v2 is skipped at minSdk 30 anyway
            enableV3Signing = true // v3 lets a future key rotation keep updates installing
        }
    }

    // Link-play shim (src/main/cpp): stands in for the core so it can offer netpacket support.
    ndkVersion = "27.2.12479018"
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" }
    }

    buildTypes {
        debug {
            // x86_64 too, so the debug APK runs at full speed in the PC test emulators
            // (x86_64 cores live in src/debug/jniLibs). Phones only use arm64.
            ndk { abiFilters += listOf("x86_64") }
        }
        release {
            // D125: R8 shrinks and optimises the code and drops unused resources. Keep rules: proguard-rules.pro.
            // The mapping file (build/outputs/mapping/release/mapping.txt) is needed to read crash stack traces.
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            releaseSigning?.let { signingConfig = it }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true } // BuildConfig.VERSION_NAME for the D41 version check
    testOptions {
        unitTests.isReturnDefaultValues = true
        // The desktop JVM's HttpURLConnection refuses PATCH (Android's allows it); the client sets it directly in tests.
        unitTests.all {
            it.jvmArgs("--add-opens=java.base/java.net=ALL-UNNAMED")
            // The desktop JVM's disconnect() hands a half-read keep-alive body to its Keep-Alive-SocketCleaner, which holds
            // the stream's lock while it drains it, so a trickling body blocked the F3 time limit for ~60 s (stage 7 gates,
            // jstack proof). Android's HttpURLConnection (OkHttp) closes the socket instead; this matches it for the JVM.
            it.systemProperty("http.keepAlive", "false")
        }
    }
    packaging {
        // Cores are loaded by file name from nativeLibraryDir, so they must be extracted.
        jniLibs { useLegacyPackaging = true }
    }
}

// Release APK name: Shoalcade-<versionName>.apk (debug keeps app-debug.apk; build.sh copies it to dist/).
android.applicationVariants.all {
    if (buildType.name == "release") outputs.all {
        (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName = "Shoalcade-$versionName.apk"
    }
}

// D125: stop a release build without the signing key, rather than produce an unsigned APK. Hooked to packaging only,
// so `./gradlew test` (which also compiles the release variant) still works on any machine.
val checkReleaseSigning by tasks.registering {
    val f = signingPropertiesFile
    doLast {
        if (!f.isFile) throw GradleException(
            "Release signing key not found: $f. " +
            "Release builds must be signed with Shoalcade's own key (D125). Point SHOALCADE_SIGNING_PROPERTIES " +
            "(or -Pshoalcade.signing.properties) at the properties file, or build the debug APK instead (assembleDebug)."
        )
    }
}
tasks.matching { it.name == "packageRelease" || it.name == "packageReleaseBundle" }
    .configureEach { dependsOn(checkReleaseSigning) }

// GPL-3.0: the licence text must travel with the APK. Copied from the repo root into the app's assets.
val licenceAssets = layout.buildDirectory.dir("generated/licence")
val copyLicence by tasks.registering(Copy::class) {
    from(rootProject.file("../LICENSE")) { rename { "LICENSE.txt" } }
    into(licenceAssets)
}
android.sourceSets["main"].assets.srcDir(licenceAssets)
tasks.named("preBuild") { dependsOn(copyLicence) }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    // Emulator front-end (GPL-3.0). Cores are bundled in src/main/jniLibs.
    implementation("com.github.Swordfish90:LibretroDroid:0.14.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.json:json:20240303") // real org.json on the JVM (Android's is a stub in unit tests)
}
