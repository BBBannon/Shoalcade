package uk.benmarks.shoalcade

import android.content.Context
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.UUID

// The phone's AutoUpload.kt is WorkManager and Android notifications around a pure core. The pure parts are copied
// here word for word (each block names its phone lines), so the phone's AutoUploadTest runs against exactly them.
// The Windows scheduler that replaces WorkManager (D184: while Shoalcade is open, plus on exit) is
// desktop/UploadWhenOnline.kt. Plan §5 step 2 (a shared module after the release) removes these copies.

/**
 * AutoUpload.kt lines 35-79 (the switch, the steps and the words), word for word. [store] / [later] are the desktop's
 * wiring in place of the phone's lines 81-149 (Android prefs, WorkManager, notifications): the waiting list goes in
 * the per-PC `autoupload` preferences, and [kick] tells the desktop scheduler.
 */
object AutoUpload {
    private const val KEY_ON = "on"
    const val NEEDS_CHOICE_TITLE = "Shoalcade: a save needs your choice"

    /** On unless switched off (per phone, like Haptics: the waiting saves themselves are per user). */
    fun on(store: SyncStore): Boolean = store.get(KEY_ON) != "0"
    fun setOn(store: SyncStore, on: Boolean) = store.set(KEY_ON, if (on) null else "0")

    /** What one upload try means for a waiting save. */
    enum class Step {
        /** It went up. */
        UPLOADED,
        /** Nothing to send (Jellyfin already has it, nothing new, or no save). */
        NOTHING_TO_DO,
        /** Both changed, or it would replace a newer Jellyfin save: a person chooses in the app. Nothing was sent. */
        NEEDS_CHOICE,
        /** Couldn't reach Jellyfin, or it's busy or erroring: try again later (backoff). */
        TRY_LATER,
        /** The login has expired (401): stop until the person logs in again; the save keeps waiting. */
        LOGIN_GONE,
        /** Jellyfin refused this one save for good (game removed, too big, blank, unknown console). */
        GIVE_UP,
    }

    fun step(r: SyncResult): Step = when (r) {
        is SyncResult.Done -> if (r.skipped == null) Step.UPLOADED else Step.NOTHING_TO_DO
        is SyncResult.NeedsConfirm, is SyncResult.Conflict -> Step.NEEDS_CHOICE
        is SyncResult.Failed -> when {
            r.code == 401 -> Step.LOGIN_GONE
            r.tryLater -> Step.TRY_LATER
            else -> Step.GIVE_UP
        }
    }

    /** Notification text for the games that need a choice. */
    fun choiceText(names: List<String>): String = when (names.size) {
        0 -> ""
        1 -> "${names[0]}: nothing was uploaded. Open Shoalcade and tap ⚠️ to choose which save to keep."
        else -> "${names.size} games: ${names.joinToString(", ")}. Nothing was uploaded for them. " +
            "Open Shoalcade and tap ⚠️ on each to choose which save to keep."
    }

    // --- Desktop wiring (in place of the phone's Android wiring) --------------------------------

    /** Per-PC store: the switch and the waiting saves. Not secret (no token, only game ids and names). */
    fun store(ctx: Context): SyncStore = object : SyncStore {
        private val prefs = ctx.applicationContext.getSharedPreferences("autoupload", Context.MODE_PRIVATE)
        override fun get(key: String): String? = prefs.getString(key, null)
        override fun set(key: String, value: String?) {
            prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
        }
    }

    fun on(ctx: Context) = on(store(ctx))

    /** Told when a save starts waiting, so the desktop scheduler tries it soon (desktop/UploadWhenOnline.kt). */
    @Volatile var kick: () -> Unit = {}

    /** What [SaveSyncCore] tells about a save that couldn't go up now (the phone's lines 119-129, with [kick]). */
    fun later(ctx: Context): SaveSyncCore.UploadLater {
        val app = ctx.applicationContext
        return object : SaveSyncCore.UploadLater {
            override val on: Boolean get() = on(app)
            override fun waiting(userId: String, game: Game) {
                WaitingSaves(store(app)).add(userId, game)
                runCatching { kick() }
            }
        }
    }
}

/**
 * AutoUpload.kt lines 152-187, word for word. Saves waiting to go up by themselves, one line each: user id, a stamp,
 * the game (as [GameJson]). The stamp changes every time a game is added again, so a run only clears the entry it
 * actually tried ([remove]); a newer failure for the same game keeps waiting. Only ids and names: never a token.
 */
class WaitingSaves(private val store: SyncStore) {
    class Entry(val userId: String, val stamp: String, val game: Game)

    private fun all(): List<Entry> = store.get(KEY)?.split('\n')?.mapNotNull { line ->
        val parts = line.split('\t', limit = 3)
        if (parts.size != 3) null else runCatching { Entry(parts[0], parts[1], GameJson.fromJson(parts[2])) }.getOrNull()
    }.orEmpty()

    private fun write(list: List<Entry>) = store.set(KEY, list.joinToString("\n") {
        "${it.userId}\t${it.stamp}\t${GameJson.toJson(it.game)}"
    }.ifEmpty { null })

    fun any(): Boolean = synchronized(LOCK) { all().isNotEmpty() }

    fun list(userId: String): List<Entry> = synchronized(LOCK) { all().filter { it.userId == userId } }

    fun add(userId: String, game: Game) = synchronized(LOCK) {
        write(all().filterNot { it.userId == userId && it.game.id == game.id } +
            Entry(userId, UUID.randomUUID().toString().take(8), game))
    }

    /** Clears [e] only if it hasn't been added again since it was read. */
    fun remove(e: Entry) = synchronized(LOCK) {
        write(all().filterNot { it.userId == e.userId && it.game.id == e.game.id && it.stamp == e.stamp })
    }

    private companion object {
        const val KEY = "waiting"
        val LOCK = Any()
    }
}

/**
 * AutoUpload.kt lines 189-233, word for word. One auto upload run for the logged-in user. [playing] = a game is open
 * right now: nothing is sent while one is (try again later), so a running game's save is never uploaded under it. Each
 * game goes through [SaveSyncCore.uploadLatest], which waits for any other save move of the same game in the app
 * ([SaveSyncCore] lock) and then sees what it did, so nothing is sent twice.
 *
 * The Windows app runs [uk.benmarks.shoalcade.desktop.UploadWhenOnline] instead (same loop and steps, plus one rule
 * for a plugin that doesn't take Windows saves yet, server change S1). This copy stays so the phone's AutoUploadTest
 * proves the steps both share.
 */
class AutoUploadRun(
    private val core: SaveSyncCore,
    private val waiting: WaitingSaves,
    private val playing: suspend () -> Boolean,
) {
    enum class Outcome { DONE, RETRY, STOP }

    class Report(val uploaded: List<Game>, val needChoice: List<Game>, val gaveUp: List<Game>, val outcome: Outcome)

    suspend fun run(): Report {
        val uploaded = mutableListOf<Game>(); val needChoice = mutableListOf<Game>(); val gaveUp = mutableListOf<Game>()
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
                // A save move always finishes once started, even if Android stops the run.
                val r = withContext(NonCancellable) { core.uploadLatest(g) }
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
                    AutoUpload.Step.GIVE_UP -> { gaveUp += g; waiting.remove(e) }
                }
            }
        }
    }
}

/**
 * SyncRename.kt line 51 (`open`): the sync-state preferences. The phone first copies dev 2's "jgsync" into "scsync"
 * once; Windows never had the old name, so it opens "scsync" straight away.
 */
object SyncRename {
    const val NEW = "scsync"
    fun open(ctx: Context) = ctx.applicationContext.getSharedPreferences(NEW, Context.MODE_PRIVATE)
}
