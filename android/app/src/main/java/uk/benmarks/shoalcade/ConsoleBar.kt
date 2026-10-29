package uk.benmarks.shoalcade

/** Makers the library's console bar groups consoles under ([Console.maker]; the plugin's GameConsole.Maker is the same table). */
enum class Maker(val label: String) { NINTENDO("Nintendo"), SONY("Sony") }

/** The library's console filter: every game, one maker's consoles, or one console. */
sealed interface ConsoleFilter {
    fun matches(c: Console?): Boolean

    data object All : ConsoleFilter { override fun matches(c: Console?) = true }
    data class Group(val maker: Maker) : ConsoleFilter { override fun matches(c: Console?) = c?.maker == maker }
    data class One(val console: Console) : ConsoleFilter { override fun matches(c: Console?) = c == console }
}

/**
 * The library's console bar after "All": a menu chip per maker ("Nintendo ▾", "Sony ▾") and a plain chip for a console
 * with no maker (RPG Maker). A maker with only one console that has games is a plain chip for that console.
 * Same rules as the browser library's bar (library.js renderFilters).
 */
object ConsoleBar {
    sealed interface Item {
        data class Menu(val maker: Maker, val consoles: List<Console>) : Item
        data class Chip(val console: Console) : Item
    }

    /** Bar items in platform order ([GameSort.PLATFORM_ORDER]) for the consoles in [have]; a maker's menu sits where its first console is. */
    fun items(have: Collection<Console>): List<Item> {
        val shown = Console.entries.filter { it in have }
            .sortedBy { GameSort.PLATFORM_ORDER.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }
        val out = mutableListOf<Item>()
        val seen = mutableSetOf<Maker>()
        for (c in shown) {
            val m = c.maker
            if (m == null) { out += Item.Chip(c); continue }
            if (!seen.add(m)) continue
            val group = shown.filter { it.maker == m }
            out += if (group.size > 1) Item.Menu(m, group) else Item.Chip(c)
        }
        return out
    }

    /** The menu's console picked now, or null (nothing of this maker, or "All Nintendo"). */
    private fun picked(menu: Item.Menu, filter: ConsoleFilter) = (filter as? ConsoleFilter.One)?.console?.takeIf { it in menu.consoles }

    /** True when the filter is this maker or one of its consoles: the chip shows selected. */
    fun isOn(menu: Item.Menu, filter: ConsoleFilter) = filter == ConsoleFilter.Group(menu.maker) || picked(menu, filter) != null

    /** Chip text: "Nintendo", or "Nintendo: GBA" while one of its consoles is picked, so the filter is always in sight. */
    fun label(menu: Item.Menu, filter: ConsoleFilter) = menu.maker.label + (picked(menu, filter)?.let { ": " + it.shortName } ?: "")

    /** TalkBack: what the chip shows now and that it opens a list. */
    fun description(menu: Item.Menu, filter: ConsoleFilter): String {
        val m = menu.maker.label
        return when {
            picked(menu, filter) != null -> "$m consoles, showing ${picked(menu, filter)!!.shortName}. Tap to choose another."
            filter == ConsoleFilter.Group(menu.maker) -> "$m consoles, showing all $m games. Tap to choose a console."
            else -> "$m consoles. Tap to choose."
        }
    }
}
