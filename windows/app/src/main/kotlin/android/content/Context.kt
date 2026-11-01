package android.content

import uk.benmarks.shoalcade.desktop.FilePrefs
import java.io.File

/**
 * DESKTOP STAND-IN, not Android. The shared phone files compiled here (Storage, GameSort's LibraryPrefs, PlayTime,
 * DisplayPrefs, Controls) touch Context only for `filesDir` and `getSharedPreferences(name, MODE_PRIVATE)`. This gives
 * them the desktop data folder ([uk.benmarks.shoalcade.desktop.Paths]) and a small file-backed store, so their rules
 * run unchanged. Link.kt (compiled for its PIN handshake and rules) also names `getSystemService` for the phone's own
 * Wi-Fi and NSD services; on the desktop that throws (the desktop lobby, PcLink.kt, never asks). N3dsCheck.kt (the
 * 3DS check's rules) names the package manager and the memory service in its phone-only pre-check: both report nothing. Nothing else of
 * Android's Context exists, so a new Android use in a shared file fails the desktop build at once instead of drifting.
 */
open class Context(val filesDir: File) {
    open val applicationContext: Context get() = this

    /** One store per name, kept in `<filesDir>/prefs/<name>.json` (not secret: the token never goes here). */
    fun getSharedPreferences(name: String, mode: Int): SharedPreferences = FilePrefs.open(File(filesDir, "prefs/$name.json"))

    /** Phone only (Link.kt's LinkLobby / LinkHolder, which the desktop never runs). */
    fun getSystemService(name: String): Any = throw UnsupportedOperationException("Android service $name: phone only")

    /** Phone only (N3dsCheck.kt's pre-check, which the desktop never runs): no Android services here. */
    fun <T> getSystemService(type: Class<T>): T? = null

    /** Phone only (N3dsCheck.kt's pre-check): reports no Android features. */
    val packageManager: android.content.pm.PackageManager get() = android.content.pm.PackageManager()

    companion object {
        const val MODE_PRIVATE = 0
        const val NSD_SERVICE = "servicediscovery"
        const val WIFI_SERVICE = "wifi"
    }
}
