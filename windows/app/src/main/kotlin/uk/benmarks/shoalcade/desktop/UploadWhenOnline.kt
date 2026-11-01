package uk.benmarks.shoalcade.desktop

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import uk.benmarks.shoalcade.AutoUpload
import uk.benmarks.shoalcade.Game
import uk.benmarks.shoalcade.SaveSyncCore
import uk.benmarks.shoalcade.SyncResult
import uk.benmarks.shoalcade.WaitingSaves

/**
 * "Upload saves automatically" on Windows (D174 + D184): saves that couldn't go up when they were made wait in the
 * phone's [WaitingSaves] list and go up by themselves **while Shoalcade is open, and once more as it closes** (no
 * tray app in v1). The app's scheduler (AppModel) runs this every minute, when the server light turns green, and on exit.
 *
 * The phone's AutoUploadRun loop and steps exactly ([AutoUpload.step]; the phone's AutoUploadTest proves them on this
 * build), plus one Windows rule: Jellyfin refusing the upload request itself (400, today's plugin and
 * `source=desktop`, server change S1) keeps the save waiting instead of giving up on it, because the save is fine and
 * goes up once the plugin is updated. Only the safe fast path is ever taken: the save goes up only when Jellyfin's
 * save hasn't changed since this PC last synced, through [SaveSyncCore.uploadLatest]. Anything needing a choice gets
 * ⚠️ and is never settled here. Nothing runs while a game is open ([playing]).
 */
class UploadWhenOnline(
    private val core: SaveSyncCore,
    private val waiting: WaitingSaves,
    private val playing: () -> Boolean,
) {
    enum class Outcome { DONE, RETRY, STOP, PLUGIN_REFUSED }

    class Report(val uploaded: List<Game>, val needChoice: List<Game>, val gaveUp: List<Pair<Game, String>>, val outcome: Outcome) {
        /** One line for the person, or null when there's nothing worth saying. */
        fun summary(): String? = buildList {
            if (uploaded.isNotEmpty()) add(if (uploaded.size == 1) "${uploaded[0].name}: save uploaded to Jellyfin."
                else "${uploaded.size} saves uploaded to Jellyfin.")
            if (needChoice.isNotEmpty()) add(AutoUpload.choiceText(needChoice.map { it.name }).replace("Open Shoalcade and tap", "Tap"))
            gaveUp.forEach { (g, why) -> add("${g.name}: $why") }
        }.joinToString(" ").ifEmpty { null }
    }

    suspend fun run(): Report {
        val uploaded = mutableListOf<Game>(); val needChoice = mutableListOf<Game>(); val gaveUp = mutableListOf<Pair<Game, String>>()
        fun report(o: Outcome) = Report(uploaded, needChoice, gaveUp, o)
        val tried = mutableSetOf<String>()
        while (true) {
            // Read again after each pass: a save that started waiting during this run is picked up too.
            val next = waiting.list(core.userId).filter { it.stamp !in tried }
            if (next.isEmpty()) return report(Outcome.DONE)
            for (e in next) {
                tried += e.stamp
                if (playing()) return report(Outcome.RETRY)
                val g = e.game
                // A save move always finishes once started.
                val r = withContext(NonCancellable) { core.uploadLatest(g) }
                if (SaveSource.refusedByPlugin(r)) return report(Outcome.PLUGIN_REFUSED) // keeps waiting
                when (AutoUpload.step(r)) {
                    AutoUpload.Step.UPLOADED -> { uploaded += g; waiting.remove(e) }
                    AutoUpload.Step.NOTHING_TO_DO -> waiting.remove(e)
                    AutoUpload.Step.NEEDS_CHOICE -> {
                        // A conflict is already marked ⚠️ by uploadLatest; a newer-save question gets the same mark.
                        if (r is SyncResult.NeedsConfirm) core.flagForChoice(g)
                        needChoice += g; waiting.remove(e)
                    }
                    AutoUpload.Step.TRY_LATER -> return report(Outcome.RETRY)
                    AutoUpload.Step.LOGIN_GONE -> return report(Outcome.STOP)
                    AutoUpload.Step.GIVE_UP -> { gaveUp += g to PcWords.of(r.message); waiting.remove(e) }
                }
            }
        }
    }
}
