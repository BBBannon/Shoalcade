package uk.benmarks.shoalcade.devps2

/** Shoalcade Dev only: see src/debug/cpp/ps2jvm.c. [prime] returns null when done, else why not. */
internal object Ps2Jvm {
    init { System.loadLibrary("scps2jvm") }
    @JvmStatic external fun prime(corePath: String): String?
}
