// Shoalcade for Windows: the desktop shell (Kotlin / Compose Desktop). Plan: docs/plan-windows.md §4-5.
// Its own Gradle build, so the Android app's build is never touched (D179).
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        google()
    }
}
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}
rootProject.name = "ShoalcadeDesktop"
