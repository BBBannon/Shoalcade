pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
rootProject.name = "Shoalcade"
include(":app")
// D153: renamed copy of Argosy's Vulkan LibretroDroid, used by 3DS only (tools/vendor_lr3ds.py).
include(":lr3ds")
