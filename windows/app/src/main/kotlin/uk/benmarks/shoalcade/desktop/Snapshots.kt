package uk.benmarks.shoalcade.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import uk.benmarks.shoalcade.Game
import uk.benmarks.shoalcade.JellyfinClient
import uk.benmarks.shoalcade.Storage
import java.io.File
import java.io.IOException

/**
 * Snapshots on Windows (plan §3.2): the browser's model, slots 1, 2, 3 and Automatic, kept on Jellyfin so they follow
 * the person between PCs. Each is tagged with this computer's core build ([Cores.tag], e.g. `win-mgba-7a12d6d`) and
 * is **never** loaded into another core: ones from another tag are listed as "made on another device" and can't be
 * picked (the browser's `launchChoices` rule, play.js).
 *
 * A local copy of each lives in `library\states\<user>\<itemId>\` (`slot1.state` + `slot1.png` + `slot1.json`): the
 * player writes there, the launch choice works offline from it, and a snapshot that couldn't reach Jellyfin is kept
 * there and sent later ([sendWaiting]). A snapshot only ever holds the game's position: loading one never changes the
 * save (D90; the rule itself is [PlaySession]'s, with the phone's SnapshotGuard).
 */
class SnapshotStore(private val userId: String, private val api: () -> DesktopApi?, private val cores: Cores) {
    /** One slot as the launch choice shows it. [here] = made by this computer's core build, so it can be loaded. */
    data class Entry(val slot: String, val time: Long?, val core: String?, val here: Boolean, val picture: File?,
                     val cheats: List<Pair<String, String>>, val onJellyfin: Boolean, val damaged: Boolean = false,
                     val sha256: String? = null) {
        val label get() = if (slot == AUTO) "Automatic" else "Slot $slot"
    }

    fun dir(game: Game) = File(Storage.root, "states/$userId/${game.id}")
    fun stateFile(game: Game, slot: String) = File(dir(game), "slot$slot.state")
    fun pictureFile(game: Game, slot: String) = File(dir(game), "slot$slot.png")
    private fun metaFile(game: Game, slot: String) = File(dir(game), "slot$slot.json")

    private class Meta(val core: String?, val time: Long, val sha: String?, val cheats: List<Pair<String, String>>, val onJellyfin: Boolean) {
        fun json() = JSONObject().put("core", core).put("time", time).put("sha256", sha).put("onJellyfin", onJellyfin)
            .put("cheats", JSONArray(cheats.map { (n, c) -> JSONObject().put("name", n).put("code", c) }))

        companion object {
            fun read(f: File): Meta? = runCatching {
                val o = JSONObject(f.readText())
                val c = o.optJSONArray("cheats") ?: JSONArray()
                Meta(o.optString("core").takeIf { it.isNotEmpty() && it != "null" }, o.getLong("time"),
                    o.optString("sha256").takeIf { it.isNotEmpty() && it != "null" },
                    (0 until c.length()).mapNotNull { c.optJSONObject(it) }.map { it.optString("name") to it.optString("code") },
                    o.optBoolean("onJellyfin"))
            }.getOrNull()
        }
    }

    private fun writeMeta(game: Game, slot: String, m: Meta) = AtomicFile.write(metaFile(game, slot), m.json().toString().toByteArray())

    /** The local copies (offline launch choice): newest first. */
    fun local(game: Game): List<Entry> {
        val tag = game.console?.let(cores::tag)
        return SLOTS.mapNotNull { s ->
            val m = Meta.read(metaFile(game, s)) ?: return@mapNotNull null
            if (!stateFile(game, s).isFile) return@mapNotNull null
            Entry(s, m.time, m.core, m.core != null && m.core == tag, pictureFile(game, s).takeIf { it.isFile }, m.cheats, m.onJellyfin)
        }.sortedByDescending { it.time ?: 0 }
    }

    /**
     * What the launch choice offers, newest first: Jellyfin's list when it can be reached (its pictures fetched into
     * the local folder), else the local copies. [enabled] false = snapshots are off for this console (the person's
     * server setting, D38): nothing is offered, as in the browser.
     */
    suspend fun forLaunch(game: Game): Pair<List<Entry>, Boolean> {
        val a = api() ?: return local(game) to true
        val (list, enabled) = try { a.snapshots(game.id) } catch (e: IOException) { return local(game) to true }
        if (!enabled) return emptyList<Entry>() to false
        val tag = game.console?.let(cores::tag)
        val entries = list.map { s ->
            val pic = pictureFile(game, s.slot)
            val m = Meta.read(metaFile(game, s.slot))
            // The picture is only fetched again if this slot isn't the one already here.
            val same = m != null && s.sha256 != null && m.sha == s.sha256 && pic.isFile
            if (s.hasImage && !same && s.core == tag) runCatching { a.snapshotImage(game.id, s.slot)?.let { AtomicFile.write(pic, it) } }
            Entry(s.slot, s.createdMillis, s.core, s.core != null && s.core == tag && !s.damaged,
                pic.takeIf { it.isFile && s.hasImage }, s.cheats, onJellyfin = true, damaged = s.damaged, sha256 = s.sha256)
        }
        // A snapshot made here that never reached Jellyfin (offline) is offered too, from the local copy.
        val waiting = local(game).filter { !it.onJellyfin && entries.none { e -> e.slot == it.slot } }
        return (entries + waiting).sortedByDescending { it.time ?: 0 } to true
    }

    /**
     * The state file to start from for [slot], fetched from Jellyfin unless the local copy is that exact snapshot.
     * Refused (IOException) if another core build made it: the browser's words.
     */
    suspend fun fetch(game: Game, e: Entry): File {
        val tag = game.console?.let(cores::tag)
        if (!e.here || e.core != tag) throw IOException(OTHER_CORE)
        val f = stateFile(game, e.slot)
        val m = Meta.read(metaFile(game, e.slot))
        if (!e.onJellyfin) return f.takeIf { it.isFile } ?: throw IOException("That snapshot isn't on this PC any more.")
        // Already here, byte for byte (checked against Jellyfin's SHA-256): no second download.
        if (e.sha256 != null && m?.sha == e.sha256 && m.core == tag && f.isFile && JellyfinClient.sha256(f.readBytes()) == e.sha256) return f
        val a = api()
        if (a == null) return f.takeIf { it.isFile && m?.core == tag } ?: throw IOException("Couldn't reach your Jellyfin server.")
        val (bytes, core) = a.snapshotBytes(game.id, e.slot)
        if (core != null && core != tag) throw IOException(OTHER_CORE)
        withContext(Dispatchers.IO) {
            AtomicFile.write(f, bytes)
            writeMeta(game, e.slot, Meta(tag, e.time ?: System.currentTimeMillis(), JellyfinClient.sha256(bytes), e.cheats, true))
        }
        return f
    }

    /**
     * The player wrote a snapshot ([slot], its file and picture). It is sent to Jellyfin with this core's tag, its
     * picture and the cheats that were on (the browser's order: state, then picture, then cheats). If Jellyfin can't
     * be reached it stays here, marked, and goes up with [sendWaiting]. Returns a line for the person.
     */
    suspend fun saved(game: Game, slot: String, cheats: List<Pair<String, String>>): String {
        val tag = game.console?.let(cores::tag) ?: return "Snapshot saved on this PC."
        val f = stateFile(game, slot)
        val bytes = withContext(Dispatchers.IO) { f.readBytes() }
        withContext(Dispatchers.IO) { writeMeta(game, slot, Meta(tag, System.currentTimeMillis(), JellyfinClient.sha256(bytes), cheats, false)) }
        return if (send(game, slot)) "Snapshot saved." else "Snapshot saved on this PC. It goes up to Jellyfin once it can be reached."
    }

    /** Sends one local snapshot to Jellyfin. True if it's there now. Never throws. */
    private suspend fun send(game: Game, slot: String): Boolean {
        val a = api() ?: return false
        val m = Meta.read(metaFile(game, slot)) ?: return false
        val f = stateFile(game, slot)
        return try {
            val bytes = withContext(Dispatchers.IO) { f.readBytes() }
            // A newer one written over it meanwhile is sent by its own call.
            if (m.sha != null && JellyfinClient.sha256(bytes) != m.sha) return false
            a.putSnapshot(game.id, slot, bytes, m.core ?: return false)
            pictureFile(game, slot).takeIf { it.isFile }?.let { p -> runCatching { a.putSnapshotImage(game.id, slot, p.readBytes()) } }
            if (m.cheats.isNotEmpty()) runCatching { a.putSnapshotCheats(game.id, slot, m.cheats) }
            withContext(Dispatchers.IO) { writeMeta(game, slot, Meta(m.core, m.time, m.sha, m.cheats, true)) }
            true
        } catch (e: IOException) {
            Log.w("Snapshot ${game.id} slot $slot not sent yet", e)
            false
        }
    }

    /** Local snapshots that never reached Jellyfin (made offline), for [sendWaiting]. */
    fun waiting(games: List<Game>): List<Pair<Game, String>> = games.flatMap { g ->
        SLOTS.mapNotNull { s -> Meta.read(metaFile(g, s))?.takeIf { !it.onJellyfin && stateFile(g, s).isFile }?.let { g to s } }
    }

    /** Sends every waiting snapshot. Returns how many went up. */
    suspend fun sendWaiting(games: List<Game>): Int = waiting(games).count { (g, s) -> send(g, s) }

    /** Deletes a snapshot on Jellyfin and here. Never touches the save. */
    suspend fun delete(game: Game, slot: String) {
        api()?.deleteSnapshot(game.id, slot) ?: throw IOException("Couldn't reach your Jellyfin server.")
        withContext(Dispatchers.IO) { listOf(stateFile(game, slot), pictureFile(game, slot), metaFile(game, slot)).forEach { it.delete() } }
    }

    companion object {
        const val AUTO = "auto"
        val SLOTS = DesktopApi.SLOTS
        /** The browser's words (play.js fetchSnapshot). */
        const val OTHER_CORE = "This snapshot was made by a different emulator version, so it can't be loaded."
        /** The browser's rule (play.js AUTO_GAP_MS): at most one Automatic snapshot a minute. */
        const val AUTO_GAP_MS = 60_000L
    }
}
