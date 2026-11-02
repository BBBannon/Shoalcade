package uk.benmarks.shoalcade

import android.content.Context

/**
 * D-8 / P12 on the phone: for a disc set (a game on two or more discs), the disc that was in when the person last
 * changed disc, kept per user and game in the plain SharedPreferences file `discs` (key `d.<userId>.<gameId>`, no
 * secrets). The next start of that game puts the same disc back in, as the Windows app does (`DiscMemory`).
 * Disc 1 (index 0) is not stored. Anything damaged reads as disc 1, so a bad value can never stop a game starting.
 */
class DiscPrefs(private val store: SyncStore) {
    private fun key(userId: String, gameId: String) = "d.$userId.$gameId"

    /** The disc (from 0) to put in at start, or 0. */
    fun get(userId: String, gameId: String): Int =
        (store.get(key(userId, gameId))?.toIntOrNull() ?: 0).coerceAtLeast(0)

    /** Keeps [index] (from 0) for next time; ignored if it isn't one of the [count] discs. */
    fun keep(userId: String, gameId: String, index: Int, count: Int) {
        if (index < 0 || index >= count) return
        store.set(key(userId, gameId), if (index == 0) null else "$index")
    }

    /** The disc to put in at start of a set with [count] discs, or null to leave disc 1 in (nothing kept, or gone). */
    fun startDisc(userId: String, gameId: String, count: Int): Int? = get(userId, gameId).takeIf { it in 1 until count }

    companion object {
        fun of(ctx: Context) = DiscPrefs(object : SyncStore {
            private val prefs = ctx.applicationContext.getSharedPreferences("discs", Context.MODE_PRIVATE)
            override fun get(key: String): String? = try { prefs.getString(key, null) } catch (_: ClassCastException) { null }
            override fun set(key: String, value: String?) {
                prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
            }
        })
    }
}
