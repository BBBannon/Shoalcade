package uk.benmarks.shoalcade

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** One finished play session. [id] is made on the phone, so a resend is never counted twice by the server. */
data class PlaySession(val id: String, val gameId: String, val seconds: Long, val endedAt: Long)

/**
 * Play time per game, added up on Jellyfin from every device (this phone and the browser player). Sessions are
 * queued on the phone ([PlayTimeQueue]) and sent when there's a connection, so offline play counts too.
 */
object PlayTime {
    /** Shortest session worth counting (shorter ones are not recorded). */
    const val MIN_SECONDS = 10L
    /** Longest session the server accepts (one day); longer ones are cut to this. */
    const val MAX_SECONDS = 24L * 3600
    /** Most sessions per server call. */
    const val MAX_BATCH = 100
    /** Server feature that says the play time route is there. */
    const val FEATURE = "playtime"

    /**
     * Play time in words, the same rule as the browser (api.js formatPlayTime): under a minute "" (show nothing),
     * "45 min", "3 h 05 min", and from 100 hours just "120 h".
     */
    fun format(seconds: Long): String {
        val mins = seconds.coerceAtLeast(0) / 60
        if (mins < 1) return ""
        if (mins < 60) return "$mins min"
        val h = mins / 60
        if (h >= 100) return "$h h"
        return "$h h " + "%02d".format(mins % 60) + " min"
    }

    /** "Played 12 h 40 min" for the library row, or null when there's nothing to show (under a minute). */
    fun label(seconds: Long): String? = format(seconds).takeIf { it.isNotEmpty() }?.let { "Played $it" }
}

/** Where the queue is kept: SharedPreferences on the phone, a map in tests. */
interface PlayTimeStorage {
    fun read(key: String): String?
    fun write(key: String, value: String?)

    class Prefs(ctx: Context) : PlayTimeStorage {
        private val prefs = ctx.applicationContext.getSharedPreferences("playtime", Context.MODE_PRIVATE)
        override fun read(key: String): String? = prefs.getString(key, null)
        // commit, not apply: a session must be on disk before the game screen closes.
        override fun write(key: String, value: String?) {
            prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.commit()
        }
    }

    class Memory : PlayTimeStorage {
        val map = mutableMapOf<String, String>()
        override fun read(key: String) = map[key]
        override fun write(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
    }
}

/**
 * Counts time actually played in one game screen: [set] true while the game runs on screen (menu closed), false
 * otherwise. [take] hands over whole seconds once there are at least [PlayTime.MIN_SECONDS], keeping the rest, so
 * short stretches (a quick look at the menu, a phone call) still add up instead of being lost. Fast-forward counts as
 * real time, as in the browser.
 */
class PlayClock(private val now: () -> Long = { android.os.SystemClock.elapsedRealtime() }) {
    private var since: Long? = null
    private var ms = 0L

    fun set(on: Boolean) {
        val s = since
        if (on && s == null) since = now()
        else if (!on && s != null) { ms += (now() - s).coerceAtLeast(0); since = null }
    }

    /** Seconds counted so far if at least [PlayTime.MIN_SECONDS], else null (kept for later). Keeps counting if it was. */
    fun take(): Long? {
        val running = since != null
        set(false)
        val secs = ms / 1000
        val out = if (secs >= PlayTime.MIN_SECONDS) { ms -= secs * 1000; secs } else null
        if (running) set(true)
        return out
    }
}

/**
 * Finished play sessions not yet confirmed by the server, per user. Nothing leaves the queue until the server has
 * answered for it ([markSent]), and the server counts a session id once only, so a call that fails half way (or whose
 * answer is lost) is simply sent again: no time is lost and none is counted twice.
 */
class PlayTimeQueue(private val storage: PlayTimeStorage) {
    constructor(ctx: Context) : this(PlayTimeStorage.Prefs(ctx))

    private val lock = Any()

    private fun key(userId: String) = "q_" + userId.lowercase()

    /**
     * Queues a finished session (time under [PlayTime.MIN_SECONDS] is ignored; over a day is cut to a day).
     * Returns it, or null if it was too short.
     */
    fun record(userId: String, gameId: String, seconds: Long, endedAt: Long = System.currentTimeMillis()): PlaySession? {
        if (seconds < PlayTime.MIN_SECONDS || !JellyfinClient.isSafeId(gameId)) return null
        val s = PlaySession(UUID.randomUUID().toString(), gameId, seconds.coerceAtMost(PlayTime.MAX_SECONDS), endedAt)
        synchronized(lock) { save(userId, load(userId) + s) }
        return s
    }

    /** Sessions waiting to be sent, oldest first. */
    fun pending(userId: String): List<PlaySession> = synchronized(lock) { load(userId) }

    /** Seconds waiting to be sent for one game (to add to the server's total on screen while offline). */
    fun pendingSeconds(userId: String, gameId: String): Long = pending(userId).filter { it.gameId == gameId }.sumOf { it.seconds }

    /** Takes sessions the server has confirmed off the queue. */
    fun markSent(userId: String, ids: Collection<String>) {
        if (ids.isEmpty()) return
        val gone = ids.toSet()
        synchronized(lock) { save(userId, load(userId).filterNot { it.id in gone }) }
    }

    /**
     * Sends every queued session, one game and up to [PlayTime.MAX_BATCH] sessions per call. A call that fails keeps
     * its sessions for next time and the rest still go. A refusal that will never change (422: the server won't
     * accept them) drops them. Returns each sent game's new total (seconds) from the server.
     */
    suspend fun flush(userId: String, send: suspend (gameId: String, sessions: List<PlaySession>) -> Long): Map<String, Long> {
        val totals = mutableMapOf<String, Long>()
        for ((game, list) in pending(userId).groupBy { it.gameId }) {
            for (batch in list.chunked(PlayTime.MAX_BATCH)) {
                try {
                    totals[game] = send(game, batch)
                    markSent(userId, batch.map { it.id })
                } catch (e: ApiException) {
                    if (e.code == 422) markSent(userId, batch.map { it.id }) // bad data never gets better
                } catch (e: java.io.IOException) {
                    // offline or the server failed: kept for next time
                }
            }
        }
        return totals
    }

    /**
     * Sends the queue for the client's user. Does nothing on a server without the "playtime" feature ([features] from
     * /shoalcade/info): the sessions wait until the plugin is updated.
     */
    suspend fun flush(client: JellyfinClient, userId: String, features: List<String>): Map<String, Long> =
        if (PlayTime.FEATURE !in features) emptyMap() else flush(userId) { game, batch -> client.addPlayTime(game, batch) }

    private fun load(userId: String): List<PlaySession> {
        val raw = storage.read(key(userId)) ?: return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id")
                val game = o.optString("game")
                val sec = o.optLong("s", 0)
                if (id.isEmpty() || !JellyfinClient.isSafeId(game) || sec < 1) null
                else PlaySession(id, game, sec.coerceAtMost(PlayTime.MAX_SECONDS), o.optLong("end", 0))
            }
        }.getOrDefault(emptyList())
    }

    private fun save(userId: String, list: List<PlaySession>) {
        if (list.isEmpty()) { storage.write(key(userId), null); return }
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.id).put("game", it.gameId).put("s", it.seconds).put("end", it.endedAt)) }
        storage.write(key(userId), a.toString())
    }
}
