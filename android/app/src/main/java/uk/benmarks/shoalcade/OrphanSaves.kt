package uk.benmarks.shoalcade

/**
 * B9: a save on this phone for a game Jellyfin no longer lists ("Not in your library"; the browser saves manager's
 * word). [name] / [console] are null when the phone doesn't know them any more. [onJellyfin] = the same bytes were
 * uploaded before the game left; [blank] = the game never saved anything in it. F9: the entry can also be just a
 * download or a snapshot: [hasSave] = a save is on the phone; [download] = bytes the phone's downloaded copy takes
 * (null = none); [playable] = that download holds a ROM the app can play offline; [snapshotTime] = the snapshot's
 * time (null = none). Plain Kotlin, so the JVM tests run it.
 */
data class OrphanSave(
    val id: String,
    val name: String?,
    val console: Console?,
    val folder: Boolean,
    val time: Long?,
    val size: Long,
    val onJellyfin: Boolean,
    val blank: Boolean,
    val hasSave: Boolean = true,
    val download: Long? = null,
    val playable: Boolean = false,
    val snapshotTime: Long? = null,
) {
    /** A stand-in game for reading this save from the phone (a PSP memory stick needs the PSP console). */
    val game: Game get() = gameFor(id, name, console, folder)

    val title: String get() = name ?: "Unknown game"

    companion object {
        fun gameFor(id: String, name: String?, console: Console?, folder: Boolean) =
            Game(id, name ?: id, console ?: if (folder) Console.PSP else null, "", false)

        /**
         * Games in the library this save can go to: same save kind (a PSP memory stick only to a PSP game, a save file
         * only to a non-PSP game); when the console is known, that console only (GB and GBC count as one: same
         * cartridges, same core). Sorted by name. Jellyfin still checks the save itself.
         */
        fun targets(o: OrphanSave, games: List<Game>): List<Game> {
            fun family(c: Console?) = if (c == Console.GBC) Console.GB else c
            return games.filter { g ->
                g.console != null && g.id != o.id && g.console.hasFolderSaves == o.folder &&
                    (o.console == null || family(g.console) == family(o.console))
            }.sortedBy { it.name.lowercase() }
        }
    }
}

/**
 * What the "Not in your library" list shows (L1, L2). A failed read or a game list that isn't loaded yet is never shown
 * as "None", which would be a false all-clear on a saves screen. Plain Kotlin, so the JVM tests run it.
 */
sealed interface OrphanView {
    /** The game list isn't known yet (no saved copy, not reached Jellyfin): every save would look orphaned. */
    data object ListNotLoaded : OrphanView
    data class Failed(val reason: String) : OrphanView
    data class Ready(val saves: List<OrphanSave>) : OrphanView

    companion object {
        /** [read] null = not logged in. Exceptions from [read] become [Failed]; cancellation is passed on. */
        fun load(listKnown: Boolean, read: (() -> List<OrphanSave>)?): OrphanView = when {
            !listKnown -> ListNotLoaded
            read == null -> Failed("Not logged in.")
            else -> try {
                Ready(read())
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }
}
