package uk.benmarks.shoalcade.desktop

import android.content.Context

/**
 * Settings → Display → Controller: two switches, kept on this PC in the `controller` store, both on by default, read
 * as each game starts and sent in `start.controls` only when off (PROTOCOL.md), so a game starts as before otherwise.
 *  - Controller rumble (D246): off = the player tells the core no and never asks a controller to shake. The N64 never
 *    rumbles either way (Controller Pak 1 holds the save; the player's forced options).
 *  - Controller shortcuts (D247, the phone's): hold Select, then R1 / L1 / R2 / L2 (physical buttons).
 */
object PadSettings {
    const val TITLE = "Controller"
    const val RUMBLE = "Controller rumble"
    const val RUMBLE_HELP = "The controller shakes when the game asks, in games that have rumble. A change counts from the next game you start."
    const val SHORTCUTS = "Controller shortcuts"
    const val SHORTCUTS_LIST = "Hold Select, then: R1 quick snapshot · L1 load it · R2 fast-forward on / off · L2 screenshot."

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("controller", Context.MODE_PRIVATE)

    fun rumble(ctx: Context): Boolean = prefs(ctx).getBoolean("rumble", true)
    fun setRumble(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("rumble", on).apply()

    fun shortcuts(ctx: Context): Boolean = prefs(ctx).getBoolean("shortcuts", true)
    fun setShortcuts(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("shortcuts", on).apply()
}
