package android.content.pm

/**
 * DESKTOP STAND-IN, not Android: the Vulkan feature question in N3dsCheck.kt's phone-only pre-check (never run on the
 * desktop; see android.app.ActivityManager here).
 */
class PackageManager {
    fun hasSystemFeature(name: String, version: Int): Boolean = false

    companion object {
        const val FEATURE_VULKAN_HARDWARE_VERSION = "android.hardware.vulkan.version"
    }
}
