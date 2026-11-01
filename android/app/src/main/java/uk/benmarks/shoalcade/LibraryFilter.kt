package uk.benmarks.shoalcade

import java.text.Normalizer
import java.util.Locale

/**
 * What the games list shows: one console (or maker, or All), the ★ Favourites only and ⬇ On phone only toggles,
 * and the search box. All of them combine; the sort is applied afterwards ([AppViewModel.sorted]).
 */
data class LibraryFilter(
    val console: ConsoleFilter = ConsoleFilter.All,
    val favouritesOnly: Boolean = false,
    val onPhoneOnly: Boolean = false,
    val search: String = "",
) {
    /**
     * The games to show. [onPhone] = ids of the games downloaded to this phone ([Storage.isDownloaded]).
     * A toggle with nothing to keep (no starred games, nothing on the phone) is hidden, so it is ignored
     * rather than showing an empty list.
     */
    fun shown(games: List<Game>, onPhone: Set<String>): List<Game> {
        val favs = favs(games)
        val phone = phone(games, onPhone)
        val q = fold(search)
        return games.filter {
            console.matches(it.console) && (!favs || it.favourite) && (!phone || it.id in onPhone) &&
                (q.isEmpty() || fold(it.name).contains(q))
        }
    }

    /** Says which filter is hiding everything (the list is empty but the library isn't). */
    fun emptyMessage(games: List<Game>, onPhone: Set<String>): String {
        val favs = favs(games)
        val phone = phone(games, onPhone)
        val toggles = listOfNotNull(FAVOURITES.takeIf { favs }, ON_PHONE.takeIf { phone })
        val off = toggles.joinToString(" and ").ifEmpty { null }
        val q = search.trim()
        if (q.isNotEmpty()) {
            val also = listOfNotNull(off?.let { "turn off $it" }, "pick another console".takeIf { console != ConsoleFilter.All })
            return "No games match “$q”." + if (also.isEmpty()) "" else " Clear the search or ${also.joinToString(" or ")}."
        }
        val what = when {
            favs && phone -> "None of your Favourites here are on this phone."
            favs -> "No Favourites here."
            phone -> "None of the games here are on this phone."
            else -> "No games for this console."
        }
        return if (off == null) what else "$what Turn off $off to see more games."
    }

    /**
     * When this changes, the games list goes back to the top: a new filter, search or sort, or a different first game
     * (the game just played moves up under "Last played"). Otherwise the list keeps its place on the old first row,
     * and the game you want sits out of sight above it. Same key = no jump (turning the phone keeps the place).
     */
    fun topKey(sort: Any, shown: List<Game>): String =
        listOf(console, favouritesOnly, onPhoneOnly, fold(search), sort, shown.firstOrNull()?.id).joinToString("|")

    private fun favs(games: List<Game>) = favouritesOnly && games.any { it.favourite }
    private fun phone(games: List<Game>, onPhone: Set<String>) = onPhoneOnly && games.any { it.id in onPhone }

    companion object {
        const val FAVOURITES = "★ Favourites only"
        /** "On phone" is the row badge for a downloaded game; the chip uses the same words. */
        const val ON_PHONE = "⬇ On phone only"

        private val marks = Regex("\\p{M}+")
        private val notLetters = Regex("[^\\p{L}\\p{N}]+")

        /**
         * Search key: no accents, no case, no spaces or punctuation, so "pokemon" finds "Pokémon: Emerald",
         * "pokemonemerald" and "pokémon - emerald" find it too, and "marios" finds "Mario's".
         * Same idea as the browser library's search (library.js fold), which also drops accents and case.
         */
        fun fold(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFKD)
            .replace(marks, "").lowercase(Locale.UK).replace(notLetters, "")
    }
}
