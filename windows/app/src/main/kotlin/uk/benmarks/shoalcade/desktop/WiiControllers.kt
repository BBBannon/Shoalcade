package uk.benmarks.shoalcade.desktop

import android.content.Context

/**
 * Wii: each port's controller (PROTOCOL.md `controllers`: `remote`, `sideways`, `nunchuk`, `classic`), remembered per
 * game on this PC (the `wii` store, `c.<gameId>` = "nunchuk,remote,…"). The player's menu changes it in the game
 * (`controllerChanged {port, controller}`) and the next start of that game sends the same list back.
 */
class WiiControllers(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("wii", Context.MODE_PRIVATE)

    /** The game's list, port 0 first (empty = the player's default, a Wii Remote on every port). */
    fun get(gameId: String): List<String> =
        prefs.getString("c.$gameId", null)?.split(',')?.map { it.trim() }?.takeIf { l -> l.all { it in KNOWN } }.orEmpty()

    /** Port [port] now has [controller] (an unknown name or port is ignored). */
    fun changed(gameId: String, port: Int, controller: String?) {
        if (controller !in KNOWN || port !in 0 until PORTS) return
        val l = get(gameId).toMutableList()
        while (l.size <= port) l += DEFAULT
        l[port] = controller!!
        val trimmed = l.dropLastWhile { it == DEFAULT }
        prefs.edit().apply { if (trimmed.isEmpty()) remove("c.$gameId") else putString("c.$gameId", trimmed.joinToString(",")) }.apply()
    }

    companion object {
        const val DEFAULT = "remote"
        val KNOWN = setOf("remote", "sideways", "nunchuk", "classic")
        const val PORTS = 4
    }
}
