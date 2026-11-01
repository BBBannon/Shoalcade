package android.net.wifi

/**
 * DESKTOP STAND-IN, not Android: the names Link.kt's LinkHolder uses for the phone's Wi-Fi locks, so it compiles.
 * A PC needs no such locks; the desktop never calls this (it can't be had: Context.getSystemService throws).
 */
class WifiManager private constructor() {
    open class Lock internal constructor() {
        fun setReferenceCounted(on: Boolean) {}
        fun acquire() {}
        fun release() {}
    }
    class MulticastLock internal constructor() : Lock()
    class WifiLock internal constructor() : Lock()

    fun createMulticastLock(tag: String): MulticastLock = MulticastLock()
    fun createWifiLock(mode: Int, tag: String): WifiLock = WifiLock()

    companion object {
        const val WIFI_MODE_FULL_HIGH_PERF = 3
        const val WIFI_MODE_FULL_LOW_LATENCY = 4
    }
}
