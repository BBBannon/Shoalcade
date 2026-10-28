package uk.benmarks.shoalcade

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * "Push all" / "Pull all" (bundle s0.2 item 6): every downloaded game, one at a time, under the newer-save rule
 * ([SavePlan], D99) through [SaveSyncCore.uploadLatest] / [SaveSyncCore.downloadLatest], so the rule can't drift.
 *  - safe ones (nothing new, or nothing newer replaced) are done straight away;
 *  - ones needing a decision (would replace a newer save, or both changed) change nothing and come back as [Ask]s,
 *    settled one by one with [settle] ("Decide later" leaves both saves untouched);
 *  - the first "can't reach Jellyfin" stops the run (nothing more is tried);
 *  - [run]'s `stop` is checked only between games, and each game runs to the end, so a save is never half-written.
 * Plain Kotlin, so the JVM tests check exactly this.
 */
class SyncAll(private val core: SaveSyncCore) {
    enum class Way(val plan: SavePlan.Way, val word: String) {
        PUSH(SavePlan.Way.UPLOAD, "Push all"), PULL(SavePlan.Way.DOWNLOAD, "Pull all")
    }

    enum class Choice { KEEP_PHONE, KEEP_SERVER, LATER }

    /**
     * A game that needs the user: [conflict] = both changed; otherwise it would replace a newer save. [again] (L3) = an
     * earlier choice was refused because Jellyfin's save changed since; [message] then says so and [server] is the newer one.
     */
    class Ask(val game: Game, val conflict: Boolean, val phoneTime: Long?, val server: SaveVersion?, val message: String,
              val again: Boolean = false)

    class Report(
        val way: Way,
        val done: List<Game>,
        val upToDate: List<Game>,
        val noSave: List<Game>,
        val failed: List<Pair<Game, String>>,
        val asks: List<Ask>,
        /** Why the run stopped early (couldn't reach Jellyfin, or the user pressed Stop); null = every game was checked. */
        val stopped: String?,
        val notChecked: Int,
    ) {
        /** e.g. "Push all: 3 uploaded, 5 already up to date, 1 needs you." Key words first (toasts/snackbars are short). */
        fun summary(): String {
            val parts = buildList {
                if (done.isNotEmpty()) add("${done.size} " + if (way == Way.PUSH) "uploaded" else "brought to the phone")
                if (upToDate.isNotEmpty()) add("${upToDate.size} already up to date")
                if (noSave.isNotEmpty()) add("${noSave.size} with no save " + if (way == Way.PUSH) "on the phone" else "on Jellyfin")
                if (asks.isNotEmpty()) add("${asks.size} need${if (asks.size == 1) "s" else ""} you")
                if (failed.isNotEmpty()) add("${failed.size} failed")
                if (notChecked > 0) add("$notChecked not checked")
            }
            val head = "${way.word}: " + (parts.joinToString(", ").ifEmpty { "no downloaded games with saves" }) + "."
            return if (stopped == null) head else "$head $stopped"
        }
    }

    /**
     * [games] = the downloaded games ([candidates]). [stop] is asked before each game; [progress] gets (index, game)
     * before it starts. Nothing is ever asked here: decisions come back in [Report.asks].
     */
    suspend fun run(way: Way, games: List<Game>, stop: () -> Boolean = { false }, progress: (Int, Game) -> Unit = { _, _ -> }): Report {
        val done = mutableListOf<Game>(); val upToDate = mutableListOf<Game>(); val noSave = mutableListOf<Game>()
        val failed = mutableListOf<Pair<Game, String>>(); val asks = mutableListOf<Ask>()
        var stopped: String? = null
        var i = 0
        while (i < games.size) {
            if (stop()) { stopped = "Stopped by you. Nothing was half-done."; break }
            val g = games[i]
            progress(i, g)
            // Never cancelled mid-game: a save write always finishes (or never starts).
            val r = withContext(NonCancellable) {
                if (way == Way.PUSH) core.uploadLatest(g) else core.downloadLatest(g)
            }
            when (r) {
                is SyncResult.Done -> when (r.skipped) {
                    null -> done += g
                    SavePlan.Why.NO_SAVE -> noSave += g
                    else -> upToDate += g
                }
                is SyncResult.NeedsConfirm -> asks += Ask(g, conflict = false, r.phoneTime, r.server, r.message)
                is SyncResult.Conflict -> asks += Ask(g, conflict = true, core.phoneTime(g), r.server, r.message)
                is SyncResult.Failed -> {
                    // "No save to upload yet" on a push is not a failure, just nothing to send.
                    if (way == Way.PUSH && !r.tryLater && !core.hasPhoneSave(g)) noSave += g
                    else if (r.tryLater) {
                        stopped = "Couldn't reach Jellyfin, so it stopped there and nothing else changed. ${r.message}"
                        break
                    } else failed += g to r.message
                }
            }
            i++
        }
        return Report(way, done, upToDate, noSave, failed, asks, stopped, games.size - i)
    }

    /**
     * The user's choice for one [Ask]. "Keep the phone's" makes the phone save the one in use (Jellyfin's goes to its
     * history and the phone's backups first); "Keep Jellyfin's" brings Jellyfin's to the phone (the phone's goes to
     * the phone's backups and Jellyfin's history first). [Choice.LATER] changes nothing and returns null.
     */
    suspend fun settle(ask: Ask, choice: Choice): SyncResult? = withContext(NonCancellable) {
        when (choice) {
            // L2: settle against exactly the Jellyfin save the question showed; if it changed since, nothing changes.
            Choice.KEEP_PHONE -> core.keepPhone(ask.game, SaveSyncCore.Shown(ask.server))
            Choice.KEEP_SERVER -> if (ask.server == null) SyncResult.Failed("There's no Jellyfin save to keep.") else core.keepServer(ask.game, SaveSyncCore.Shown(ask.server))
            Choice.LATER -> null
        }
    }

    /**
     * L3: a choice [settle] refused because Jellyfin's save changed since the question ([SyncResult.Conflict]) becomes
     * the same question again, showing the newer Jellyfin save. Null for any other result: nothing to ask again.
     */
    fun askAgain(ask: Ask, r: SyncResult?): Ask? = (r as? SyncResult.Conflict)?.let {
        Ask(ask.game, ask.conflict, core.phoneTime(ask.game), it.server, it.message, again = true)
    }

    companion object {
        /** Push all / Pull all cover every game that is on the phone (and has a console the app can play). */
        fun candidates(games: List<Game>): List<Game> = games.filter { it.console != null && Storage.isDownloaded(it) }
    }
}
