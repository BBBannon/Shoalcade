package android.os

/** DESKTOP STAND-IN, not Android: the shared PlayTime.kt's PlayClock reads this. Monotonic, like Android's. */
object SystemClock {
    @JvmStatic
    fun elapsedRealtime(): Long = System.nanoTime() / 1_000_000
}
