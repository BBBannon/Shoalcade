package uk.benmarks.shoalcade.desktop

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import uk.benmarks.shoalcade.BuildConfig
import uk.benmarks.shoalcade.Console
import uk.benmarks.shoalcade.N3dsCheck
import uk.benmarks.shoalcade.SyncStore
import java.io.File
import java.io.IOException

/**
 * The 3DS screen layout chosen on this PC (docs/windows/phase6-3ds.md, D206). The phone keeps one per way up in its
 * `n3ds_display` store (Game3dsActivity.kt: `layout.land` / `layout.port`, `swap.land` / `swap.port`); a PC window
 * has one way up, so it has its own pair (`layout.pc`, `swap.pc`) in a store of the same name. Default: Top and
 * bottom, so the mouse (the stylus) always has the bottom screen to touch.
 */
class N3dsDisplay(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("n3ds_display", Context.MODE_PRIVATE)

    /** The phone's layout keys (N3dsLayout.key). */
    val layout: String get() = prefs.getString(LAYOUT, null)?.takeIf { it in LAYOUTS } ?: DEFAULT
    val flip: Boolean get() = prefs.getBoolean(SWAP, false)

    /** The player's `layoutChanged` (menu, Flip key, or the shell): kept for the next game. Unknown names are ignored. */
    fun keep(layout: String?, flip: Boolean?) {
        val e = prefs.edit()
        layout?.takeIf { it in LAYOUTS }?.let { e.putString(LAYOUT, it) }
        flip?.let { e.putBoolean(SWAP, it) }
        e.apply()
    }

    companion object {
        /** Game3dsActivity.kt N3dsLayout keys, in its order. */
        val LAYOUTS = listOf("stacked", "single", "large", "side")
        const val DEFAULT = "stacked"
        private const val LAYOUT = "layout.pc"
        private const val SWAP = "swap.pc"
    }
}

/**
 * The 3DS check on Windows: **advisory only** (plan Q6): it runs the phone's test scene and shows how fast this PC
 * ran it, but 3DS games always download and play. The rules (what counts as Pass / Borderline / Too slow, the speed
 * line, the saved result and "run it again after an update") are the phone's own N3dsCheck.kt, compiled unchanged;
 * only the words differ, because nothing is locked here.
 */
object N3dsCheckPc {
    const val TITLE = N3dsCheck.TITLE
    const val INTRO = "3DS games need a lot from a computer. This check plays a short 3D test scene for about " +
        "${N3dsCheck.WARM_UP_S + N3dsCheck.MEASURE_S} seconds and measures how fast this PC runs it. It's only " +
        "advice: 3DS games always play on Windows, whatever the result."
    const val START = N3dsCheck.START
    const val AGAIN = N3dsCheck.AGAIN
    const val RUNNING = "The 3DS check is running in its own window. Press Esc there to stop it."
    const val CANCELLED = N3dsCheck.CANCELLED
    const val NO_ENGINE = "The 3DS engine couldn't start on this PC. Nothing changed."

    /** What each result means on a PC (the phone's labels; its details speak of locking, which Windows never does). */
    fun detail(r: N3dsCheck.Result): String = when (r) {
        N3dsCheck.Result.PASS -> "3DS games should run at full speed on this PC."
        N3dsCheck.Result.BORDERLINE -> "3DS games should play, but some may stutter or slow down on this PC."
        N3dsCheck.Result.FAIL -> "This PC ran the check too slowly: 3DS games will probably stutter or slow down. " +
            "You can still play them."
    }

    /** One line for the menu and the check window (never a lock). */
    fun status(v: N3dsCheck.Saved, build: String = android.os.Build.FINGERPRINT, app: Long = BuildConfig.VERSION_CODE.toLong()): String = when {
        v.result == null -> "Not run on this PC yet (optional)"
        N3dsCheck.stale(v, build, app) -> "Windows or Shoalcade has updated since the check: you can run it again"
        else -> "3DS check: ${v.result.label}"
    }

    /** The result kept on this PC, in the phone's store and keys (`prefs/n3ds_check.json`). */
    fun store(ctx: Context): SyncStore = N3dsCheck.store(ctx)
}

/**
 * One run of the 3DS check: the phone's scene (packed in the app, the same file as android/.../assets/n3ds/check.3dsx)
 * on its own pretend SD card in [dir], the load written to it first as Game3dsActivity.prepareCheck does, then the
 * player in check mode (PROTOCOL.md `start.check`). No save, snapshot or cheat is involved.
 */
class N3dsCheckRun(private val setup: PlayerSetup, private val dir: File, private val warmUp: Int = N3dsCheck.WARM_UP_S,
                   private val measure: Int = N3dsCheck.MEASURE_S, private val playerArgs: List<String> = emptyList(),
                   private val log: File? = null) {
    sealed class Outcome {
        data class Measured(val fps: List<Int>, val result: N3dsCheck.Result) : Outcome()
        data object Cancelled : Outcome()
        data class Failed(val why: String) : Outcome()
    }

    /** Writes the scene and its load. Returns the scene file. */
    fun prepare(): File {
        dir.mkdirs()
        val scene = File(dir, "check.3dsx")
        val bytes = N3dsCheckRun::class.java.getResourceAsStream("/n3ds/check.3dsx")?.use { it.readBytes() }
            ?: throw IOException("The 3DS check scene is missing from this install. Install Shoalcade again.")
        AtomicFile.write(scene, bytes)
        File(dir, "Azahar/sdmc/shoalcade").mkdirs()
        AtomicFile.write(File(dir, "Azahar/sdmc/shoalcade/check.txt"), "${N3dsCheck.CUBES} ${N3dsCheck.CPU}".toByteArray())
        return scene
    }

    /** Runs it to the end (or until the person presses Esc in its window). [onTick] gets the player's corner words. */
    suspend fun run(onTick: (String) -> Unit = {}): Outcome {
        val scene = try { withContext(Dispatchers.IO) { prepare() } } catch (e: IOException) { return Outcome.Failed(e.message ?: NO) }
        val core = setup.core(Console.N3DS) ?: return Outcome.Failed("The 3DS emulator isn't installed. Install Shoalcade again.")
        if (!setup.player.isFile) return Outcome.Failed("The game player isn't installed. Install Shoalcade again.")
        val start = StartRequest(console = Console.N3DS.key, core = core, rom = scene, saveFolder = dir,
            systemDir = setup.systemDir, title = N3dsCheck.TITLE, check = StartRequest.Check(warmUp, measure),
            snapshotsAllowed = false, cheatsAllowed = false, canUpload = false)
        val p = withContext(Dispatchers.IO) { PlayerClient.launch(setup.player, playerArgs, log = log) }
        var result: PlayerEvent.Message? = null
        var fatal: String? = null
        try {
            p.events.collect { e ->
                if (e !is PlayerEvent.Message) return@collect
                when (e.type) {
                    "hello" -> p.start(start)
                    "checkTick" -> onTick(tickWords(e))
                    "checkResult" -> result = e
                    "error" -> if (e.bool("fatal") == true) fatal = e.string("message")
                }
            }
        } finally {
            withContext(NonCancellable) { withTimeoutOrNull(10_000) { p.close() } }
        }
        val r = result ?: return Outcome.Failed(fatal?.let { "${N3dsCheckPc.NO_ENGINE} ($it)" } ?: N3dsCheckPc.NO_ENGINE)
        if (r.bool("done") != true) return Outcome.Cancelled
        val fps = r.ints("fps")
        return Outcome.Measured(fps, N3dsCheck.verdict(fps))
    }

    /** The phone's corner words (Game3dsActivity.checkTick). */
    private fun tickWords(e: PlayerEvent.Message): String {
        val left = e.long("left") ?: 0
        return if (e.string("phase") == "warmUp") "3DS check: warming up… $left s"
        else "3DS check: measuring… $left s · ${e.long("fps") ?: 0} fps"
    }

    private companion object { const val NO = "The 3DS check couldn't start." }
}
