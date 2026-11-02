package uk.benmarks.shoalcade.desktop

import android.content.Context

/**
 * P12 (D-8 kept): for a game on more than one disc, the disc it was on when it was last played, kept per game on this
 * PC (the `discs` store, `d.<gameId>`). The player says `discChanged {index, count}` (menu "Change disc"); at the next
 * start, once `ready.discs` shows the set, the shell sends `disc.set {index}` back. Disc 0 (the first) is not stored.
 */
class DiscMemory(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("discs", Context.MODE_PRIVATE)

    fun get(gameId: String): Int = (prefs.getString("d.$gameId", null)?.toIntOrNull() ?: 0).coerceAtLeast(0)

    fun keep(gameId: String, index: Int?, count: Int?) {
        if (index == null || index < 0 || (count != null && index >= count)) return
        prefs.edit().apply { if (index == 0) remove("d.$gameId") else putString("d.$gameId", "$index") }.apply()
    }
}
