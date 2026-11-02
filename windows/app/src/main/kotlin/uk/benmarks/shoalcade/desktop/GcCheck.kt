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
 * The GameCube / Wii check (D222 D-7): **advisory only**, like the 3DS check on Windows. It plays our own test scene
 * (tools/gc-check: `gc-check.dol` / `wii-check.dol`, built from devkitPro's libogc, packed in the app) in Dolphin and
 * says how fast this PC ran it; GameCube and Wii games always download and play, whatever the result. The rules are
 * the phone's N3dsCheck (verdict, speed line, saved result, "run it again after an update"), with full speed being
 * the scene's own frame rate: 50 for the GameCube scene (Dolphin runs it as PAL) and 60 for the Wii scene.
 */
object GcCheckPc {
    const val TITLE = "GameCube and Wii check"
    const val INTRO = "GameCube and Wii games need a fast computer. Each check plays a short 3D test scene for about " +
        "${N3dsCheck.WARM_UP_S + N3dsCheck.MEASURE_S} seconds and measures how fast this PC runs it. It's only " +
        "advice: GameCube and Wii games always play, whatever the result."
    const val RUNNING = "The check is running in its own window. Press Esc there to stop it."

    /** The scene's own frame rate (PROTOCOL.md `check.targetFps`). */
    fun targetFps(c: Console) = if (c == Console.GC) 50 else 60

    fun name(c: Console) = if (c == Console.GC) "GameCube" else "Wii"
    fun start(c: Console) = "Run the ${name(c)} check"

    /** Each measured second as a share of 60 (the phone's rule counts against 60 frames a second). */
    private fun scaled(fps: List<Int>, target: Int) = fps.map { it * 60 / target }

    fun verdict(fps: List<Int>, target: Int): N3dsCheck.Result = N3dsCheck.verdict(scaled(fps, target))

    fun speedLine(fps: List<Int>, target: Int): String {
        if (fps.isEmpty()) return "No speed measured."
        val avg = fps.average()
        return "On average ${avg.toInt()} frames a second (${(avg * 100 / target).toInt()}% of full speed)."
    }

    fun detail(c: Console, r: N3dsCheck.Result): String = when (r) {
        N3dsCheck.Result.PASS -> "${name(c)} games should run at full speed on this PC."
        N3dsCheck.Result.BORDERLINE -> "${name(c)} games should play, but some may stutter or slow down on this PC."
        N3dsCheck.Result.FAIL -> "This PC ran the check too slowly: ${name(c)} games will probably stutter or slow down. You can still play them."
    }

    fun status(c: Console, v: N3dsCheck.Saved, build: String = android.os.Build.FINGERPRINT, app: Long = BuildConfig.VERSION_CODE.toLong()): String = when {
        v.result == null -> "${name(c)}: not run on this PC yet (optional)"
        N3dsCheck.stale(v, build, app) -> "${name(c)}: Windows or Shoalcade has updated since the check: you can run it again"
        else -> "${name(c)} check: ${v.result.label}"
    }

    /** The result kept on this PC, one store per console (`prefs/gc_check.json`, `prefs/wii_check.json`). */
    fun store(ctx: Context, c: Console): SyncStore = object : SyncStore {
        private val prefs = ctx.getSharedPreferences(if (c == Console.GC) "gc_check" else "wii_check", Context.MODE_PRIVATE)
        override fun get(key: String): String? = prefs.getString(key, null)
        override fun set(key: String, value: String?) {
            prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
        }
    }
}

/** One run of the GameCube or Wii check: the packed scene as `rom`, a throwaway save folder in [dir]. */
class GcCheckRun(private val setup: PlayerSetup, private val dir: File, private val console: Console,
                 private val warmUp: Int = N3dsCheck.WARM_UP_S, private val measure: Int = N3dsCheck.MEASURE_S,
                 private val playerArgs: List<String> = emptyList(), private val log: File? = null) {
    init { require(console == Console.GC || console == Console.WII) }

    private val file = if (console == Console.GC) "gc-check.dol" else "wii-check.dol"

    /** Writes the scene into [dir]. */
    fun prepare(): File {
        dir.mkdirs()
        val bytes = GcCheckRun::class.java.getResourceAsStream("/gc-check/$file")?.use { it.readBytes() }
            ?: throw IOException("The ${GcCheckPc.name(console)} check scene is missing from this install. Install Shoalcade again.")
        val scene = File(dir, file)
        AtomicFile.write(scene, bytes)
        File(dir, "save").mkdirs()
        return scene
    }

    suspend fun run(onTick: (String) -> Unit = {}): N3dsCheckRun.Outcome {
        val name = GcCheckPc.name(console)
        val scene = try { withContext(Dispatchers.IO) { prepare() } } catch (e: IOException) {
            return N3dsCheckRun.Outcome.Failed(e.message ?: "The $name check couldn't start.")
        }
        val core = setup.core(console) ?: return N3dsCheckRun.Outcome.Failed("The $name emulator isn't installed. Install Shoalcade again.")
        if (!setup.player.isFile) return N3dsCheckRun.Outcome.Failed("The game player isn't installed. Install Shoalcade again.")
        val target = GcCheckPc.targetFps(console)
        val start = StartRequest(console = console.key, core = core, rom = scene, saveFolder = File(dir, "save"),
            systemDir = setup.systemDir, title = "$name check", check = StartRequest.Check(warmUp, measure, target),
            snapshotsAllowed = false, cheatsAllowed = false, canUpload = false)
        val p = withContext(Dispatchers.IO) { PlayerClient.launch(setup.player, playerArgs, log = log) }
        var result: PlayerEvent.Message? = null
        var fatal: String? = null
        try {
            p.events.collect { e ->
                if (e !is PlayerEvent.Message) return@collect
                when (e.type) {
                    "hello" -> p.start(start)
                    "checkTick" -> onTick(if (e.string("phase") == "warmUp") "$name check: warming up… ${e.long("left") ?: 0} s"
                        else "$name check: measuring… ${e.long("left") ?: 0} s · ${e.long("fps") ?: 0} fps")
                    "checkResult" -> result = e
                    "error" -> if (e.bool("fatal") == true) fatal = e.string("message")
                }
            }
        } finally {
            withContext(NonCancellable) { withTimeoutOrNull(10_000) { p.close() } }
        }
        val r = result ?: return N3dsCheckRun.Outcome.Failed("The $name engine couldn't start on this PC. Nothing changed." +
            (fatal?.let { " ($it)" } ?: ""))
        if (r.bool("done") != true) return N3dsCheckRun.Outcome.Cancelled
        val fps = r.ints("fps")
        return N3dsCheckRun.Outcome.Measured(fps, GcCheckPc.verdict(fps, target))
    }
}
