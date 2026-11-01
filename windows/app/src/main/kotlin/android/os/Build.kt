package android.os

/**
 * DESKTOP STAND-IN, not Android. The shared JellyfinClient.kt (compiled from android/) reads `android.os.Build.MODEL`
 * for the device name Jellyfin shows in its device list. On the desktop that is this computer's name. N3dsCheck.kt
 * reads FINGERPRINT (below).
 */
object Build {
    @JvmField
    val MODEL: String? = (System.getenv("COMPUTERNAME") ?: System.getenv("HOSTNAME"))
        ?.trim()?.takeIf { it.isNotEmpty() }?.let { "$it (Windows)" } ?: "Windows PC"

    /**
     * N3dsCheck.kt keeps the phone's system build beside a 3DS check result, to suggest a re-run after an update. On
     * the desktop: the operating system and its version (a Windows update can bring a new graphics driver).
     */
    @JvmField
    val FINGERPRINT: String = "${System.getProperty("os.name")} ${System.getProperty("os.version")}"

    /** N3dsCheck.kt's phone-only pre-check reads this; the desktop never runs it. */
    @JvmField
    val SUPPORTED_ABIS: Array<String> = arrayOf(System.getProperty("os.arch") ?: "")

    /** Link.kt's phone-only Wi-Fi lock code reads this; the desktop never runs it. */
    object VERSION {
        @JvmField val SDK_INT: Int = 0
    }
}
