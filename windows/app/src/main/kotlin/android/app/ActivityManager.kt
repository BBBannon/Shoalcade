package android.app

/**
 * DESKTOP STAND-IN, not Android. The shared N3dsCheck.kt (the 3DS check's rules, compiled unchanged) names this in its
 * phone-only pre-check ([N3dsCheck.phone]), which the desktop never runs: a PC's check is advisory (plan Q6) and has
 * no pre-check. Reports nothing.
 */
class ActivityManager {
    class MemoryInfo {
        @JvmField var totalMem: Long = 0
    }

    fun getMemoryInfo(info: MemoryInfo) { info.totalMem = 0 }
}
