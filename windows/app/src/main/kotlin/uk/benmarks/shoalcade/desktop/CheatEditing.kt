package uk.benmarks.shoalcade.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import uk.benmarks.shoalcade.CheatBook
import uk.benmarks.shoalcade.CheatRules
import uk.benmarks.shoalcade.CheatStore
import uk.benmarks.shoalcade.CheatSyncCore
import uk.benmarks.shoalcade.Console
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Where the Windows cheat editor's list lives (D143, D145): the game being played (its [PlaySession], so the game and
 * its pause menu follow at once) or, when the game isn't running, this PC's copy of the list ([CheatStore]). Either
 * way every change is kept on this PC first and sent to Jellyfin when it can be ([CheatSyncCore], the phone's own
 * sync), so the phone, the browser and every PC share one list per game and Jellyfin user. Cheats are never written
 * into a save: the list is its own file and its own server document.
 */
interface CheatHost {
    val book: CheatBook
    /** Keeps [b] on this PC (and in the game, when it is running). Throws [IOException] if it couldn't be kept. */
    fun put(b: CheatBook)
    /** Sends what's waiting to Jellyfin and takes its list, in the background (one run at a time). */
    fun sync()
    /** False = Jellyfin can't be reached (or has no cheats): changes wait on this PC. */
    val online: Boolean
    /** The game is running: changes go into it now (else "they go on when the game starts"). */
    val playing: Boolean
    /** Told about every new list, including changes made elsewhere (the game's menu, a snapshot, Jellyfin). */
    var onChange: ((CheatBook) -> Unit)?
}

/**
 * One sync at a time (the phone's cheatSyncing / cheatSyncAgain): a request while one runs starts one more after it.
 * [scope] runs on one thread (the UI's), as CheatSyncCore needs. [after] is told each result.
 */
class CheatSyncLoop(
    private val scope: CoroutineScope,
    private val core: CheatSyncCore?,
    private val itemId: String,
    private val holder: CheatSyncCore.Holder,
    private val after: (CheatSyncCore.Result) -> Unit = {},
) {
    // running/again change together under the lock: a request that lands just as a round ends is never dropped
    // (it used to be: `again` set after the loop's last check, before `running` went false).
    private val lock = Any()
    private var running = false
    private var again = false

    fun request() {
        val c = core ?: return
        synchronized(lock) {
            if (running) { again = true; return }
            running = true
        }
        scope.launch {
            try {
                var rounds = 0
                while (true) {
                    synchronized(lock) { again = false }
                    val r = c.sync(itemId, holder)
                    if (r is CheatSyncCore.Result.Failed) Log.i("Cheats not synced for now: ${r.error.message}")
                    after(r)
                    synchronized(lock) {
                        if (!(again && r is CheatSyncCore.Result.Done && ++rounds < 5)) { running = false; return@launch }
                    }
                }
            } finally {
                synchronized(lock) { running = false }
            }
        }
    }
}

/**
 * This PC's copy of a game's list ([CheatStore]), for the editor when the game isn't running. One per game and user
 * at a time: a newer one, or the game starting ([handOver]), stops the older one's sync and its writes (review S9), so
 * a sync that finishes late never writes an old list over the game's changes, here or on Jellyfin.
 */
class PcCheatHost(
    private val userId: String,
    private val gameId: String,
    scope: CoroutineScope,
    core: CheatSyncCore?,
) : CheatHost {
    private val read = CheatStore.read(userId, gameId)
    override var book: CheatBook = read.first
        private set
    /** The file couldn't be read: it was kept aside and a new list started (the phone's words, shown once). */
    val damaged: Boolean = read.second
    override val online = core != null
    override val playing = false
    override var onChange: ((CheatBook) -> Unit)? = null
    private val lock = Any() // the sync's writes vs [close] (the game starts on another thread)
    @Volatile private var closed = false
    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val holder = object : CheatSyncCore.Holder {
        override var book: CheatBook
            get() = this@PcCheatHost.book
            set(v) {
                synchronized(lock) {
                    if (closed) return // handed over: the game's own sync takes it from here
                    runCatching { put(v) }.getOrElse { Log.w("Couldn't keep the cheat list", it) }
                }
            }
    }
    private val loop = CheatSyncLoop(CoroutineScope(scope.coroutineContext + job), core, gameId, holder)

    init {
        live.put(key(userId, gameId), this)?.close()
    }

    override fun put(b: CheatBook) {
        if (b == book) return
        if (closed) throw IOException(PcWords.of("This game is open now: change its cheats from the playing screen."))
        try {
            CheatStore.write(userId, gameId, b)
        } catch (e: IOException) {
            throw IOException(PcWords.of("Couldn't keep the cheat list on the phone: ${e.message}"), e)
        }
        book = b
        onChange?.invoke(b)
    }

    override fun sync() = loop.request()

    /** Stops for good: no more writes (every change made here is already in [CheatStore]), and the sync is cancelled. */
    private fun close() {
        synchronized(lock) { closed = true }
        job.cancel()
        live.remove(key(userId, gameId), this)
    }

    companion object {
        private val live = ConcurrentHashMap<String, PcCheatHost>()
        private fun key(userId: String, gameId: String) = "$userId|$gameId"

        /**
         * The game is starting (review S9): this PC's editor copy stops, and its sync (if one is running) is waited for,
         * up to [waitMs], so nothing it sends or writes lands after the game reads the list. Anything it hadn't sent
         * yet is still marked in [CheatStore], so the game's own sync sends it (adds, edits and deletes are safe twice).
         */
        suspend fun handOver(userId: String, gameId: String, waitMs: Long = 5_000) {
            val h = live[key(userId, gameId)] ?: return
            h.close()
            withTimeoutOrNull(waitMs) { h.job.join() }
        }
    }
}

/**
 * The editor's actions, the phone's (GameActivity.saveCheat / deleteCheat / toggleCheat) and the same words: a new
 * cheat starts switched on, the same code twice gives the one already there, each console's code rule
 * ([CheatRules.problem]: N64, DS CodeBreaker, GameCube D-4, Wii, PS2) before anything is kept.
 */
class CheatEditor(val console: Console, val host: CheatHost) {
    /** [problem] = plain-English reason nothing was kept (the form stays open); else [said] = what happened. */
    data class Answer(val problem: String? = null, val said: String? = null) {
        val ok get() = problem == null
    }

    /** Add ([id] null) or change a cheat. */
    fun save(id: String?, name: String, typed: String): Answer {
        val code = CheatRules.normalise(console, typed) // N64 spaces, PS2 pnach lines (CheatRules.normalise)
        CheatRules.problem(name, code, console)?.let { return Answer(problem = it) }
        val before = host.book
        val said = if (id == null) {
            val (b, c) = before.add(name, code) ?: return Answer(problem = "That cheat can't be used.")
            val known = b == before
            keep(b.setOn(c.id, true))?.let { return it }
            if (known) "You already have that code as \"${c.name}\". It's switched on." else "Cheat added and switched on."
        } else {
            keep(before.edit(id, name, code) ?: return Answer(problem = "That cheat isn't in the list any more."))?.let { return it }
            "Cheat saved."
        }
        host.sync()
        return Answer(said = said)
    }

    fun delete(id: String): Answer {
        if (host.book.byId(id) == null) return Answer(problem = "That cheat isn't in the list any more.")
        keep(host.book.delete(id))?.let { return it }
        host.sync()
        return Answer(said = "Cheat deleted.")
    }

    /** A switch in the list (the browser's words: "Turned on Max money."). */
    fun setOn(id: String, on: Boolean): Answer {
        val c = host.book.byId(id) ?: return Answer(problem = "That cheat isn't in the list any more.")
        keep(host.book.setOn(id, on))?.let { return it }
        host.sync()
        return Answer(said = (if (on) "Turned on " else "Turned off ") + c.name + ".")
    }

    private fun keep(b: CheatBook): Answer? = try {
        host.put(b); null
    } catch (e: IOException) {
        Answer(problem = e.message ?: "Couldn't keep the cheat list.")
    }

    companion object {
        /** Consoles with cheats on Windows: every one but the 3DS (D210: Azahar's retro_cheat_set is empty) and Xbox 360 (X-5). */
        fun allowed(console: Console?): Boolean = console != null && console != Console.N3DS && console != Console.XBOX360
    }
}
