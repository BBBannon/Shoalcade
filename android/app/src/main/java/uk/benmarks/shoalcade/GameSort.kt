package uk.benmarks.shoalcade

import android.content.Context
import java.text.Collator
import java.util.Locale

/**
 * Games list order (remembered per user, see [LibraryPrefs]). Unknowns (no year, never played, unknown console)
 * always go last, then by name. Default is [LAST_PLAYED] so the game being played is at the top.
 */
enum class GameSort(val key: String, val label: String) {
    NAME("name", "Name"),
    YEAR("year", "Year"),
    PLATFORM("platform", "Platform"),
    LAST_PLAYED("played", "Last played"),
    ;

    companion object {
        val DEFAULT = LAST_PLAYED

        fun fromKey(key: String?): GameSort = entries.firstOrNull { it.key == key } ?: DEFAULT

        /** Platform order: the Game Boy family oldest first, then N64, DS, 3DS, PS1, PSP. */
        val PLATFORM_ORDER = listOf(Console.GB, Console.GBC, Console.GBA, Console.N64, Console.NDS, Console.N3DS, Console.PSX, Console.PSP)

        private val collator: Collator = Collator.getInstance(Locale.UK).apply { strength = Collator.PRIMARY }
        private val byName = Comparator<Game> { a, b -> collator.compare(a.name, b.name) }.thenBy { it.id }

        /** [played] = when the game was last played (epoch ms), or null if never. */
        fun sort(games: List<Game>, sort: GameSort, played: (Game) -> Long?): List<Game> = when (sort) {
            NAME -> games.sortedWith(byName)
            // Newest first; no year last.
            YEAR -> games.sortedWith(compareBy<Game> { it.year == null }.thenByDescending { it.year ?: 0 }.then(byName))
            PLATFORM -> games.sortedWith(compareBy<Game> {
                PLATFORM_ORDER.indexOf(it.console).let { i -> if (i < 0) Int.MAX_VALUE else i }
            }.then(byName))
            LAST_PLAYED -> {
                val t = games.associate { it.id to played(it) }
                games.sortedWith(compareBy<Game> { t[it.id] == null }.thenByDescending { t[it.id] ?: 0L }.then(byName))
            }
        }

        /** Most recent of this phone's last launch and the server's last play / current save time. */
        fun lastPlayed(phone: Long?, server: Long?): Long? = listOfNotNull(phone, server).maxOrNull()
    }
}

/** Per-user library settings kept on the phone: the chosen sort and when each game was last started here. */
class LibraryPrefs(private val store: SyncStore) {
    fun sort(userId: String): GameSort = GameSort.fromKey(store.get("sort.$userId"))
    fun setSort(userId: String, sort: GameSort) = store.set("sort.$userId", sort.key)

    fun lastLaunch(userId: String, gameId: String): Long? = store.get("played.$userId.$gameId")?.toLongOrNull()
    fun recordLaunch(userId: String, gameId: String, at: Long = System.currentTimeMillis()) =
        store.set("played.$userId.$gameId", at.toString())

    companion object {
        fun of(ctx: Context) = LibraryPrefs(object : SyncStore {
            private val prefs = ctx.applicationContext.getSharedPreferences("library", Context.MODE_PRIVATE)
            override fun get(key: String): String? = prefs.getString(key, null)
            override fun set(key: String, value: String?) {
                prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
            }
        })
    }
}
