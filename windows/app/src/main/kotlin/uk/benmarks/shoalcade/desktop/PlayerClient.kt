package uk.benmarks.shoalcade.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runInterruptible
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Drives one ShoalcadePlayer(.exe) process over its control protocol (windows/player/PROTOCOL.md, version 1): one
 * JSON object per line, shell → player on stdin, player → shell on stdout. Logs (stderr) go to [log].
 *
 * Security (plan §7): the player never gets the Jellyfin token. This class has no way to pass one: the command line
 * is the player path plus [extraArgs] (test flags), and every message is built from local paths and settings.
 */
class PlayerClient private constructor(
    private val process: Process,
    private val scope: CoroutineScope,
) : Closeable, GamePlayer {
    private val writer: BufferedWriter = process.outputStream.bufferedWriter(Charsets.UTF_8)
    private val channel = Channel<PlayerEvent>(Channel.UNLIMITED)

    /** Every message from the player, in order, ending after `exited` (or when the process ends). One collector. */
    override val events: Flow<PlayerEvent> = channel.receiveAsFlow()

    private fun readEvents() = scope.launch {
        try {
            process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (raw in lines) {
                    val line = raw.removeSuffix("\r")
                    if (line.isBlank()) continue
                    channel.send(PlayerEvent.parse(line))
                }
            }
        } catch (_: IOException) {
            // stdout closed under us (process killed): the end of the stream below says so
        } finally {
            // Always delivered, even while this client is being closed: whoever collects [events] must see the end.
            withContext(NonCancellable) {
                val code = runInterruptible { process.waitFor() }
                channel.send(PlayerEvent.Ended(code))
                channel.close()
            }
        }
    }.also { reader -> reader.invokeOnCompletion { scope.cancel() } } // the client's own work ends with the reader

    /** Sends one message. False if the player has gone (its stdin is closed). */
    @Synchronized
    override fun send(message: JSONObject): Boolean = try {
        require(message.has("type")) { "A player message needs a type." }
        writer.write(message.toString())
        writer.write("\n")
        writer.flush()
        true
    } catch (_: IOException) {
        false
    }

    override fun start(s: StartRequest) = send(s.toJson())
    fun pause() = send("pause")
    fun resume() = send("resume")
    /** Asks the player to write the save and quit (`closing {reason:"shell"}` … `exited`). */
    fun closeGame() = send("close")

    val isAlive get() = process.isAlive

    /** Waits up to [timeoutMs] for the process to end; its exit code, or null if still running. */
    fun waitFor(timeoutMs: Long): Int? = if (process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) process.exitValue() else null

    /**
     * Ends this client. A player still running is asked to close (so it writes the save) and given [graceMs]; only
     * then is it stopped. Closing stdin alone also makes it save and close (`shellGone`).
     */
    override fun close() = close(graceMs = 10_000)

    override fun close(graceMs: Long) {
        if (process.isAlive) {
            closeGame()
            // Normally it quits within a second (`closing {reason:"shell"}`). If not, closing stdin asks again
            // (`shellGone`), and only then is it stopped. The reader keeps going until the player's last line.
            if (!process.waitFor(graceMs, TimeUnit.MILLISECONDS)) {
                runCatching { writer.close() }
                if (!process.waitFor(2_000, TimeUnit.MILLISECONDS)) process.destroyForcibly()
            }
        }
        runCatching { writer.close() }
    }

    companion object {
        /**
         * Starts [player] with [extraArgs] (e.g. `--headless` in tests; never anything secret). Its working folder is
         * the player's own folder, so it finds the core DLLs beside it. [env] adds to the environment (tests).
         */
        fun launch(player: File, extraArgs: List<String> = emptyList(), log: File? = null, env: Map<String, String> = emptyMap()): PlayerClient {
            require(player.isFile) { "Player not found: $player" }
            val pb = ProcessBuilder(listOf(player.absolutePath) + extraArgs).directory(player.absoluteFile.parentFile)
            pb.environment().putAll(env)
            if (log != null) { log.parentFile?.mkdirs(); pb.redirectError(ProcessBuilder.Redirect.appendTo(log)) }
            else pb.redirectError(ProcessBuilder.Redirect.DISCARD)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            return PlayerClient(pb.start(), scope).also { it.readEvents() }
        }
    }
}

/**
 * One running game as the shell sees it ([PlayerClient] for the real player; tests script it). [events] ends with
 * [PlayerEvent.Ended]. Only local paths and settings ever go through it: there is no way to hand it the token.
 */
interface GamePlayer {
    val events: Flow<PlayerEvent>
    fun send(message: JSONObject): Boolean
    fun send(type: String, fields: Map<String, Any?> = emptyMap()): Boolean = send(JSONObject().put("type", type).apply {
        fields.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) }
    })
    fun start(s: StartRequest): Boolean = send(s.toJson())
    /** Asks the player to save and close, gives it [graceMs], then stops it. */
    fun close(graceMs: Long)
}

/** One message from the player. [type] is its `"type"`; [json] the whole object (fields as PROTOCOL.md). */
sealed class PlayerEvent {
    abstract val type: String

    data class Message(override val type: String, val json: JSONObject) : PlayerEvent() {
        fun string(k: String): String? = json.optString(k).takeIf { json.has(k) && !json.isNull(k) }
        fun long(k: String): Long? = if (json.has(k) && !json.isNull(k)) json.optLong(k) else null
        fun bool(k: String): Boolean? = if (json.has(k) && !json.isNull(k)) json.optBoolean(k) else null
        /** A list of whole numbers (e.g. `checkResult.fps`); empty if missing. */
        fun ints(k: String): List<Int> = json.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optInt(it) } } ?: emptyList()
        override fun toString() = "PlayerEvent($type)"
    }

    /** A stdout line that isn't a protocol message (should never happen: the player keeps stdout for the protocol). */
    data class BadLine(val line: String) : PlayerEvent() { override val type = "badLine" }

    /** The process ended (after `exited`, or a crash with no `exited`). Always the last event. */
    data class Ended(val code: Int) : PlayerEvent() { override val type = "ended" }

    companion object {
        fun parse(line: String): PlayerEvent = try {
            val o = JSONObject(line)
            val t = o.optString("type")
            if (t.isEmpty()) BadLine(line) else Message(t, o)
        } catch (_: JSONException) {
            BadLine(line)
        }
    }
}

/** The `start` message (PROTOCOL.md "start"). Only local paths and settings: there is no field for a token. */
data class StartRequest(
    val console: String,
    val core: File,
    val rom: File,
    val saveFile: File? = null,
    val clockFile: File? = null,
    val saveFolder: File? = null,
    val systemDir: File? = null,
    val statesDir: File? = null,
    val screenshotDir: File? = null,
    val title: String? = null,
    val options: Map<String, String> = emptyMap(),
    val snapshot: File? = null,
    val cheats: List<String> = emptyList(),
    val ffSpeed: Int? = null,
    val volume: Double? = null,
    val muted: Boolean? = null,
    val saveIntervalSeconds: Int? = null,
    /** The game's whole cheat list for the pause menu (`{code, enabled, id, name}`); used instead of [cheats] when set. */
    val cheatItems: List<CheatItem> = emptyList(),
    /** False = no Snapshots section in the menu (off in the person's server settings). */
    val snapshotsAllowed: Boolean? = null,
    /** True = the player writes the Automatic snapshot as the game closes (the browser's rule). */
    val autoSnapshotOnClose: Boolean? = null,
    /** False = no "Close Game (and Upload)" in the menu. */
    val canUpload: Boolean? = null,
    /** What the menu shows for each snapshot slot (PROTOCOL.md `slots`). */
    val slots: List<SlotView> = emptyList(),
    /** Link play (PROTOCOL.md "Link play"): where the player connects for its link stream, and as what. */
    val link: LinkStart? = null,
    /** False = no Cheats section in the menu (3DS: Azahar has no cheats; the phone's cheatsAllowed = false). */
    val cheatsAllowed: Boolean? = null,
    /** 3DS screen layout (PROTOCOL.md `layout`): the phone's N3dsLayout key and Flip. */
    val n3dsLayout: N3dsLayoutStart? = null,
    /** DS screen layout (PROTOCOL.md `layout`): the phone's DsLayout key and Swap. */
    val dsLayout: DsLayoutStart? = null,
    /** The 3DS check (PROTOCOL.md `start.check`): seconds of warm-up, then seconds measured. */
    val check: Check? = null,
    /** Wii (PROTOCOL.md `controllers`): each port's controller, port 0 first; empty = the player's default. */
    val controllers: List<String> = emptyList(),
    /** PS2 (P4b, PROTOCOL.md `controls.pressureCross`): the right trigger also presses Cross with its real level. */
    val pressureCross: Boolean? = null,
    /** D246 / D247 (PROTOCOL.md `controls.rumble`, `controls.padShortcuts`): false = switched off; null = on (the default). */
    val rumble: Boolean? = null,
    val padShortcuts: Boolean? = null,
    /** PROTOCOL.md `controls` `{keyboard, hotkeys, gamepads}`: the buttons changed in the game menu ([ControlsStore]). */
    val controls: JSONObject? = null,
) {
    data class N3dsLayoutStart(val layout: String, val flip: Boolean)
    data class DsLayoutStart(val layout: String, val swap: Boolean)
    /** [targetFps]: the scene's own frame rate (GameCube / Wii check), echoed in `checkResult`; null = the core's. */
    data class Check(val warmUp: Int, val measure: Int, val targetFps: Int? = null)
    /** [token] is a one-time word for the local link stream only (never the Jellyfin token). */
    data class LinkStart(val port: Int, val host: Boolean, val token: String, val cable: Boolean, val dsMac: String?, val peer: String)
    data class CheatItem(val id: String, val name: String, val code: String, val enabled: Boolean) {
        /** As `start.cheats` and `cheats.set` carry it. */
        fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("code", code).put("enabled", enabled)
    }
    /** [path] = the state file the menu's Load uses (null = none here); [loadable] false = another core build's. */
    data class SlotView(val slot: String, val `when`: String?, val png: File?, val path: File?, val loadable: Boolean, val note: String?, val empty: Boolean)

    fun toJson(): JSONObject = JSONObject().apply {
        put("type", "start")
        put("console", console)
        put("core", core.absolutePath)
        put("rom", rom.absolutePath)
        saveFile?.let { put("saveFile", it.absolutePath) }
        clockFile?.let { put("clockFile", it.absolutePath) }
        saveFolder?.let { put("saveFolder", it.absolutePath) }
        systemDir?.let { put("systemDir", it.absolutePath) }
        statesDir?.let { put("statesDir", it.absolutePath) }
        screenshotDir?.let { put("screenshotDir", it.absolutePath) }
        title?.let { put("title", it) }
        if (options.isNotEmpty()) put("options", JSONObject(options))
        snapshot?.let { put("snapshot", it.absolutePath) }
        if (cheatItems.isNotEmpty()) put("cheats", JSONArray(cheatItems.map { it.toJson() }))
        else if (cheats.isNotEmpty()) put("cheats", JSONArray(cheats))
        snapshotsAllowed?.let { put("snapshotsAllowed", it) }
        autoSnapshotOnClose?.let { put("autoSnapshotOnClose", it) }
        canUpload?.let { put("canUpload", it) }
        if (slots.isNotEmpty()) put("slots", JSONArray(slots.map { v ->
            JSONObject().put("slot", v.slot).put("loadable", v.loadable).put("empty", v.empty).apply {
                v.`when`?.let { put("when", it) }; v.png?.let { put("png", it.absolutePath) }
                v.path?.let { put("path", it.absolutePath) }; v.note?.let { put("note", it) }
            }
        }))
        ffSpeed?.let { put("ff", JSONObject().put("speed", it)) }
        volume?.let { put("volume", it) }
        muted?.let { put("muted", it) }
        saveIntervalSeconds?.let { put("saveIntervalSeconds", it) }
        cheatsAllowed?.let { put("cheatsAllowed", it) }
        n3dsLayout?.let { put("layout", JSONObject().put("n3ds", it.layout).put("flip", it.flip)) }
        dsLayout?.let { put("layout", JSONObject().put("ds", it.layout).put("swap", it.swap)) }
        check?.let { put("check", JSONObject().put("warmUp", it.warmUp).put("measure", it.measure).apply { it.targetFps?.let { t -> put("targetFps", t) } }) }
        if (controllers.isNotEmpty()) put("controllers", JSONArray(controllers))
        if (controls != null || pressureCross != null || rumble != null || padShortcuts != null) put("controls", JSONObject(controls?.toString() ?: "{}").apply {
            pressureCross?.let { put("pressureCross", it) }
            rumble?.let { put("rumble", it) }
            padShortcuts?.let { put("padShortcuts", it) }
        })
        link?.let { l ->
            put("link", JSONObject().put("port", l.port).put("role", if (l.host) "host" else "join").put("token", l.token)
                .put("cable", l.cable).put("peer", l.peer).apply { l.dsMac?.let { put("dsMac", it) } })
        }
    }
}
