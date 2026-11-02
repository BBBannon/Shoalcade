package uk.benmarks.shoalcade.desktop

import android.content.Context

/**
 * Settings → Saves → "Automatic snapshot when I close a game" (D240): the browser's Automatic snapshot as a game
 * closes (PlaySession, at most one a minute), now a switch, kept on this PC in the `snapshots` store. On by default,
 * as before. Off: closing a game never takes one; snapshots saved by hand, and any Automatic one already made, stay.
 */
object AutoSnapshot {
    const val TITLE = "Automatic snapshot when I close a game"
    const val HELP = "As you close a game, it is kept as the Automatic snapshot, so you can carry on from that exact moment. " +
        "Your save is never changed by it. Off: only the snapshots you save yourself."

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("snapshots", Context.MODE_PRIVATE)

    fun on(ctx: Context): Boolean = prefs(ctx).getBoolean("autoOnClose", true)

    fun set(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("autoOnClose", on).apply()
}
