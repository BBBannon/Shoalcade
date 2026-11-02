package uk.benmarks.shoalcade.desktop

import android.content.Context
import uk.benmarks.shoalcade.Console

/**
 * P11 (Ben, D-8: keep LAN / online play in the game's own way): a per-game "Network adapter" switch, off by default,
 * kept on this PC (the `network` store, `on.<gameId>`). On = the console's own network adapter is plugged in:
 *  - GameCube (Dolphin): the Broadband Adapter in serial port 1, `dolphin_gc_sp1` = "12" (Ethernet Built-In: Dolphin's
 *    own network, nothing to set up on the PC); off = "255" (None, Dolphin's default).
 *  - PS2 with LRPS2: the network adapter, `pcsx2_dev9_eth` = "enabled" (its Sockets backend: no PC set-up either).
 *    PCEE2 has no such option, so the switch is shown as not available there.
 * Wii games need no switch: the Wii's own Wi-Fi is always there in Dolphin.
 */
object NetworkAdapter {
    const val TITLE = "Network adapter"
    const val HELP = "For games that play over a network (LAN or online) in their own way. Most games don't need it. " +
        "Off unless you switch it on for this game."
    const val PCEE2_NO = "Not available with PCEE2. Choose LRPS2 in Settings → PS2 to use it."

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("network", Context.MODE_PRIVATE)

    /** This game's console has a network adapter Shoalcade can switch (GameCube, PS2). */
    fun applies(c: Console?) = c == Console.GC || c == Console.PS2

    /** Available now: PS2 only with LRPS2. */
    fun available(ctx: Context, c: Console?) = applies(c) && !(c == Console.PS2 && Ps2.emulator(ctx) == Ps2.Emulator.PCEE2)

    fun isOn(ctx: Context, gameId: String) = prefs(ctx).getBoolean("on.$gameId", false)

    fun set(ctx: Context, gameId: String, on: Boolean) {
        prefs(ctx).edit().apply { if (on) putBoolean("on.$gameId", true) else remove("on.$gameId") }.apply()
    }

    /** The core option for this game's start: nothing when off (the cores' defaults are off). */
    fun options(ctx: Context, c: Console?, gameId: String): Map<String, String> {
        if (!available(ctx, c) || !isOn(ctx, gameId)) return emptyMap()
        return when (c) {
            Console.GC -> mapOf("dolphin_gc_sp1" to "12")
            Console.PS2 -> mapOf("pcsx2_dev9_eth" to "enabled")
            else -> emptyMap()
        }
    }
}
