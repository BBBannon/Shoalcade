package uk.benmarks.shoalcade.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import uk.benmarks.shoalcade.ApiException
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

    /**
     * [refused]: why it isn't sent to Jellyfin (409 `old_engine`, 413 the server's limit, or too big even packed, D-2):
     * kept here. [refusedCode] says which ([REFUSED_OLD_ENGINE], [REFUSED_LIMIT], [REFUSED_TOO_BIG]); [refusedBytes]
     * = the packed size the server refused, [refusedLimit] = the server's snapshot limit at that time (review S1). The
     * first two are lifted once their cause has gone ([sendWaiting]); too big for this app, or refused by a web proxy in
     * front of Jellyfin ([REFUSED_PROXY]), is for good. [jfSha]: the SHA-256 of what Jellyfin holds when that differs
     * from the state file (a packed one, D-2).
     */
    private data class Meta(val core: String?, val time: Long, val sha: String?, val cheats: List<Pair<String, String>>, val onJellyfin: Boolean,
                            val refused: String? = null, val jfSha: String? = null, val refusedCode: String? = null, val refusedBytes: Long? = null,
                            val refusedLimit: Long? = null) {
        /** The refusal's kind; snapshots refused before the kind was kept are told apart by their words. */
        val kind: String? get() = refusedCode ?: refused?.let {
            when {
                it == TOO_BIG_SERVER -> REFUSED_LIMIT
                it == TOO_BIG_PROXY -> REFUSED_PROXY
                it.startsWith(TOO_BIG_PREFIX) -> REFUSED_TOO_BIG
                else -> REFUSED_OLD_ENGINE
            }
        }

        fun json() = JSONObject().put("core", core).put("time", time).put("sha256", sha).put("onJellyfin", onJellyfin)
            .put("cheats", JSONArray(cheats.map { (n, c) -> JSONObject().put("name", n).put("code", c) }))
            .apply {
                if (refused != null) put("refused", refused); if (jfSha != null) put("jellyfinSha256", jfSha)
                if (refusedCode != null) put("refusedCode", refusedCode); if (refusedBytes != null) put("refusedBytes", refusedBytes)
                if (refusedLimit != null) put("refusedLimit", refusedLimit)
            }

        companion object {
            fun read(f: File): Meta? = runCatching {
                val o = JSONObject(f.readText())
                val c = o.optJSONArray("cheats") ?: JSONArray()
                Meta(o.optString("core").takeIf { it.isNotEmpty() && it != "null" }, o.getLong("time"),
                    o.optString("sha256").takeIf { it.isNotEmpty() && it != "null" },
                    (0 until c.length()).mapNotNull { c.optJSONObject(it) }.map { it.optString("name") to it.optString("code") },
                    o.optBoolean("onJellyfin"), o.optString("refused").takeIf { it.isNotEmpty() },
                    o.optString("jellyfinSha256").takeIf { it.isNotEmpty() && it != "null" },
                    o.optString("refusedCode").takeIf { it.isNotEmpty() }, o.optLong("refusedBytes", 0L).takeIf { it > 0 },
                    o.optLong("refusedLimit", 0L).takeIf { it > 0 })
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
            if (s.hasImage && !same && s.core == tag) runCatching { a.snapshotImage(game.id, s.slot)?.let { AtomicFile.writeDirect(pic, it) } } // D225
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
        // Already here, byte for byte (checked against Jellyfin's SHA-256, or what went up packed): no second download.
        if (e.sha256 != null && m != null && (m.sha == e.sha256 || m.jfSha == e.sha256) && m.core == tag && f.isFile &&
            JellyfinClient.sha256(f.readBytes()) == m.sha) return f
        val a = api()
        if (a == null) return f.takeIf { it.isFile && m?.core == tag } ?: throw IOException("Couldn't reach your Jellyfin server.")
        val (bytes, core) = a.snapshotBytes(game.id, e.slot)
        if (core != null && core != tag) throw IOException(OTHER_CORE)
        withContext(Dispatchers.IO) {
            val state = if (packs(game)) unpack(bytes) else bytes
            AtomicFile.write(f, state)
            val sha = JellyfinClient.sha256(state)
            writeMeta(game, e.slot, Meta(tag, e.time ?: System.currentTimeMillis(), sha, e.cheats, true,
                jfSha = JellyfinClient.sha256(bytes).takeIf { it != sha }))
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
        return if (send(game, slot)) "Snapshot saved." else refused(game, slot)?.let { "Snapshot saved on this PC only. $it" }
            ?: "Snapshot saved on this PC. It goes up to Jellyfin once it can be reached."
    }

    private fun refused(game: Game, slot: String): String? = Meta.read(metaFile(game, slot))?.refused

    /**
     * Sends one local snapshot to Jellyfin. True if it's there now. Never throws. A 409 `old_engine` (the slot holds an
     * old Game Boy emulator snapshot, which Jellyfin keeps until it's deleted, D221) or a 413 (bigger than the admin's
     * snapshot limit) is kept as a refusal with the local copy: [waiting] leaves it out until [sendWaiting] sees its
     * cause gone (the slot freed, or a limit big enough), review #11.
     */
    private suspend fun send(game: Game, slot: String): Boolean {
        val a = api() ?: return false
        val m = Meta.read(metaFile(game, slot)) ?: return false
        if (m.refused != null) return false
        val f = stateFile(game, slot)
        var sent: Long? = null
        return try {
            val raw = withContext(Dispatchers.IO) { f.readBytes() }
            // A newer one written over it meanwhile is sent by its own call.
            if (m.sha != null && JellyfinClient.sha256(raw) != m.sha) return false
            // D-2: GameCube / Wii / PS2 states are 50-110 MB. Packed first; still too big = kept on this PC for good.
            val bytes = if (packs(game)) withContext(Dispatchers.Default) { pack(raw) } else raw
            if (packs(game) && bytes.size > MAX_UPLOAD) {
                withContext(Dispatchers.IO) { writeMeta(game, slot, Meta(m.core, m.time, m.sha, m.cheats, false, tooBig(bytes.size.toLong()),
                    refusedCode = REFUSED_TOO_BIG, refusedBytes = bytes.size.toLong())) }
                return false
            }
            sent = bytes.size.toLong()
            a.putSnapshot(game.id, slot, bytes, m.core ?: return false)
            pictureFile(game, slot).takeIf { it.isFile }?.let { p -> runCatching { a.putSnapshotImage(game.id, slot, p.readBytes()) } }
            if (m.cheats.isNotEmpty()) runCatching { a.putSnapshotCheats(game.id, slot, m.cheats) }
            val jf = if (bytes === raw) null else JellyfinClient.sha256(bytes)
            withContext(Dispatchers.IO) { writeMeta(game, slot, Meta(m.core, m.time, m.sha, m.cheats, true, jfSha = jf)) }
            true
        } catch (e: ApiException) {
            // Review S1: a 413 for a snapshot within the server's own limit came from a web proxy in front of Jellyfin
            // (nginx takes 1 MB by default). Sending it again would only be refused again, so it is kept here for good.
            val limit = if (e.code == 413 && packs(game)) (try { a.snapshots(game.id).maxBytes } catch (x: IOException) { null }) else null
            val why = when {
                e.code == 409 && e.error == OLD_ENGINE -> (e.message ?: OLD_ENGINE_TEXT) to REFUSED_OLD_ENGINE
                e.code == 413 && packs(game) && limit != null && sent != null && sent <= limit -> TOO_BIG_PROXY to REFUSED_PROXY
                e.code == 413 && packs(game) -> TOO_BIG_SERVER to REFUSED_LIMIT // the admin's snapshot size limit
                else -> null
            }
            if (why != null) {
                Log.w("Snapshot ${game.id} slot $slot refused (${e.code} ${e.error}, ${why.second}, sent $sent, server limit $limit): kept on this PC")
                withContext(Dispatchers.IO) { writeMeta(game, slot, Meta(m.core, m.time, m.sha, m.cheats, false, why.first,
                    refusedCode = why.second, refusedBytes = sent, refusedLimit = limit)) }
            } else Log.w("Snapshot ${game.id} slot $slot not sent yet", e)
            false
        } catch (e: IOException) {
            Log.w("Snapshot ${game.id} slot $slot not sent yet", e)
            false
        }
    }

    /** Local snapshots that never reached Jellyfin (made offline), for [sendWaiting]. Refused ones are never sent again. */
    fun waiting(games: List<Game>): List<Pair<Game, String>> = games.flatMap { g ->
        SLOTS.mapNotNull { s -> Meta.read(metaFile(g, s))?.takeIf { !it.onJellyfin && it.refused == null && stateFile(g, s).isFile }?.let { g to s } }
    }

    /**
     * Sends every waiting snapshot. Returns how many went up. First, a refusal whose cause has gone is lifted
     * (review #11): `old_engine` once Jellyfin's slot is empty (the old snapshot deleted), the server's limit once it
     * has grown past the limit it refused at (review S1) and is at least the size refused (unknown size: the most this
     * app sends). Too big for this app, or refused by a web proxy, stays refused.
     */
    suspend fun sendWaiting(games: List<Game>): Int {
        api()?.let { a -> liftRefusals(a, games) }
        return waiting(games).count { (g, s) -> send(g, s) }
    }

    private suspend fun liftRefusals(a: DesktopApi, games: List<Game>) {
        for (g in games) {
            val held = SLOTS.mapNotNull { s ->
                Meta.read(metaFile(g, s))?.takeIf { !it.onJellyfin && it.kind in LIFTABLE && stateFile(g, s).isFile }?.let { s to it }
            }
            if (held.isEmpty()) continue
            val server = try { a.snapshots(g.id) } catch (e: IOException) { continue }
            for ((s, m) in held) {
                val gone = when (m.kind) {
                    REFUSED_LIMIT -> server.maxBytes != null && server.maxBytes >= (m.refusedBytes ?: MAX_UPLOAD.toLong()) &&
                        (m.refusedLimit == null || server.maxBytes > m.refusedLimit)
                    else -> server.list.none { it.slot == s }
                }
                if (gone) {
                    Log.w("Snapshot ${g.id} slot $s: the server's refusal (${m.kind}) has gone, sending it again")
                    withContext(Dispatchers.IO) { writeMeta(g, s, m.copy(refused = null, refusedCode = null, refusedBytes = null, refusedLimit = null)) }
                }
            }
        }
    }

    /** Deletes a snapshot on Jellyfin and here. Never touches the save. */
    suspend fun delete(game: Game, slot: String) {
        api()?.deleteSnapshot(game.id, slot) ?: throw IOException("Couldn't reach your Jellyfin server.")
        withContext(Dispatchers.IO) { listOf(stateFile(game, slot), pictureFile(game, slot), metaFile(game, slot)).forEach { it.delete() } }
    }

    /** D-2 (plan §14): GameCube, Wii and PS2 snapshots are packed (zip) before they go to Jellyfin, and only if small enough. */
    private fun packs(game: Game) = game.console?.isWindowsOnly == true

    companion object {
        /**
         * The biggest packed GameCube / Wii / PS2 snapshot sent to Jellyfin: the plugin's default snapshot limit
         * (MaxSnapshotMegabytes). A bigger one stays on this PC; a server set lower answers 413, also kept here.
         */
        const val MAX_UPLOAD = 32 * 1024 * 1024
        /** The biggest state unpacked from Jellyfin (Dolphin's are about 110 MB). */
        const val MAX_STATE = 512L * 1024 * 1024
        const val TOO_BIG_SERVER = "It's bigger than your Jellyfin takes for snapshots, so it stays on this PC."
        /** Review S1: a 413 within the server's own limit (a web proxy such as nginx, 1 MB by default). */
        const val TOO_BIG_PROXY = "Your server's web proxy refused it (too big), so it stays on this PC. " +
            "Raising the proxy's upload limit lets your next snapshot go up."
        private const val TOO_BIG_PREFIX = "It's too big for Jellyfin even packed"
        fun tooBig(bytes: Long) = "$TOO_BIG_PREFIX (${bytes / (1024 * 1024)} MB; up to ${MAX_UPLOAD / (1024 * 1024)} MB), " +
            "so it stays on this PC."

        /** Why a snapshot isn't sent (Meta.refusedCode). The first two are lifted once their cause has gone. */
        const val REFUSED_OLD_ENGINE = "old_engine"
        const val REFUSED_LIMIT = "server_limit"
        const val REFUSED_TOO_BIG = "too_big"
        const val REFUSED_PROXY = "proxy_limit"
        private val LIFTABLE = setOf(REFUSED_OLD_ENGINE, REFUSED_LIMIT)

        /** A state packed as a zip with one entry (the phone's fixed-time zip style, so the same state packs the same). */
        fun pack(state: ByteArray): ByteArray {
            val out = java.io.ByteArrayOutputStream(state.size / 4 + 1024)
            java.util.zip.ZipOutputStream(out).use { z ->
                z.setLevel(java.util.zip.Deflater.BEST_SPEED)
                z.putNextEntry(java.util.zip.ZipEntry(PACKED_NAME).apply { time = 315_532_800_000L })
                z.write(state)
                z.closeEntry()
            }
            return out.toByteArray()
        }

        /** [pack] undone; a state that isn't packed (no zip header) comes back as it is. Refuses anything odd. */
        fun unpack(bytes: ByteArray): ByteArray {
            if (bytes.size < 4 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte() || bytes[2] != 3.toByte() || bytes[3] != 4.toByte()) return bytes
            java.util.zip.ZipInputStream(bytes.inputStream()).use { z ->
                val e = z.nextEntry ?: throw IOException("That snapshot is damaged.")
                if (e.name != PACKED_NAME) throw IOException("That snapshot is damaged.")
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = z.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_STATE) throw IOException("That snapshot is far too big.")
                    out.write(buf, 0, n)
                }
                if (z.nextEntry != null) throw IOException("That snapshot is damaged.")
                return out.toByteArray()
            }
        }
        private const val PACKED_NAME = "state"

        const val AUTO = "auto"
        val SLOTS = DesktopApi.SLOTS
        /** The browser's words (play.js fetchSnapshot). */
        const val OTHER_CORE = "This snapshot was made by a different emulator version, so it can't be loaded."
        /** The browser's rule (play.js AUTO_GAP_MS): at most one Automatic snapshot a minute. */
        const val AUTO_GAP_MS = 60_000L
        /** The server's refusal for a slot holding an old Game Boy emulator snapshot (StatesController, D221). */
        const val OLD_ENGINE = "old_engine"
        const val OLD_ENGINE_TEXT = "This slot holds a snapshot from the old Game Boy emulator. It's kept until you delete it, so use another slot."
    }
}
