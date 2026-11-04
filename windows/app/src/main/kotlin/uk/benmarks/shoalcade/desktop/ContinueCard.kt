package uk.benmarks.shoalcade.desktop

import android.content.Context
import uk.benmarks.shoalcade.Game

/**
 * The library's Continue card (D248): the game played last that is downloaded on this PC (so it works offline), at the
 * top of the library; one click starts it through Play (the same save check, launch choice and snapshot choice).
 * Settings → Display → "Show Continue", kept on this PC in the `display` store, on by default.
 *
 * Which game: the latest "last played" (this PC's last start or Jellyfin's, the later: the library's Last played sort
 * and Played lately) among the games on this PC (the phone's ContinueGame.pick rule). The console strip and the ★ / ⬇
 * toggles don't change it (it is about carrying on, not browsing); it is hidden while something is typed in the
 * search, so the results come first.
 */
object ContinueCard {
    const val TITLE = "Continue"
    const val SETTING = "Show Continue"
    const val HELP = "At the top of the library: the last game you played that is on this PC, to carry on in one click."

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("display", Context.MODE_PRIVATE)

    fun on(ctx: Context): Boolean = prefs(ctx).getBoolean("continue", true)
    fun set(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("continue", on).apply()

    /** The game for the card and when it was last played; null = no card. */
    fun pick(games: List<Game>, onPc: Set<String>, search: String, lastPlayed: (Game) -> Long?): Pair<Game, Long>? {
        if (search.isNotBlank()) return null
        return games.asSequence().filter { it.id in onPc && it.console != null } // a console this app knows
            .mapNotNull { g -> lastPlayed(g)?.let { g to it } }
            .maxByOrNull { it.second }
    }
}
