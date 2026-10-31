package uk.benmarks.shoalcade

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * The 3DS check (plan-3ds step 7, D151): 3DS games are listed but can't be downloaded until this phone has run a short
 * speed test. Pass or Borderline unlock 3DS; Fail keeps it locked, with "Unlock anyway" behind a warning (a wrong
 * result must never block anyone). The result is per phone, kept on the phone; a system update (new graphics driver)
 * or an app update (new 3DS engine) asks for a re-run but doesn't lock 3DS again.
 *
 * Plain Kotlin apart from [preCheck] and [store], so the rules are unit-tested.
 */
object N3dsCheck {

    enum class Result(val key: String, val label: String, val detail: String) {
        PASS("pass", "Pass", "3DS games should run at full speed on this phone."),
        BORDERLINE("borderline", "Borderline", "3DS games are unlocked, but some may stutter or slow down on this phone."),
        FAIL("fail", "Too slow", "This phone runs 3DS games too slowly to enjoy them, so 3DS is locked.");

        companion object { fun of(k: String?) = entries.firstOrNull { it.key == k } }
    }

    // --- The run -------------------------------------------------------------------------------

    /** Seconds the scene runs before measuring (graphics set-up stutters at first) and then measured. */
    const val WARM_UP_S = 10
    const val MEASURE_S = 30

    /**
     * The scene's load (tools/n3ds-check: cubes drawn and processor work per frame), written to its SD card before it
     * starts. Calibrated on a PowerVR phone (D160): 300, 1200 and 3000 cubes and 10x processor work all ran at 60 fps,
     * because the emulator runs a fixed slice of 3DS time per frame; past the point where the emulated 3DS is busy
     * flat out, more load only slows the scene's own animation. This level keeps it flat out: the heaviest a 3DS gets.
     */
    const val CUBES = 1200
    const val CPU = 200000

    /** Full speed is 60 frames a second. */
    private const val FULL = 60

    /**
     * Result from the frames counted each measured second. Pass: on average 90% of full speed and no second below 75%.
     * Borderline: on average at least 70%. Anything slower, or nothing measured, fails.
     */
    fun verdict(fps: List<Int>): Result {
        if (fps.isEmpty()) return Result.FAIL
        val avg = fps.average()
        val low = fps.sorted()[fps.size / 10] // 10th percentile: one slow second doesn't decide it
        return when {
            avg >= FULL * 0.9 && low >= FULL * 0.75 -> Result.PASS
            avg >= FULL * 0.7 -> Result.BORDERLINE
            else -> Result.FAIL
        }
    }

    /** "On average 58 frames a second (97% of full speed)." */
    fun speedLine(fps: List<Int>): String {
        if (fps.isEmpty()) return "No speed measured."
        val avg = fps.average()
        return "On average ${avg.toInt()} frames a second (${(avg * 100 / FULL).toInt()}% of full speed)."
    }

    // --- Pre-check: what the phone must have before the run is worth trying ---------------------

    data class Phone(val vulkan11: Boolean, val ramBytes: Long, val arm64: Boolean)

    /** 3.5 GB: phones sold as 4 GB report a little less than 4 GB. */
    private const val MIN_RAM = 3_500_000_000L

    /** Why the phone can't run 3DS at all (empty = it may; the speed run decides). */
    fun missing(p: Phone): List<String> = buildList {
        if (!p.arm64) add("A 64-bit (arm64) phone is needed.")
        if (!p.vulkan11) add("The phone's graphics chip needs Vulkan 1.1 or newer.")
        if (p.ramBytes in 1 until MIN_RAM) add("At least 4 GB of memory is needed.")
    }

    fun phone(ctx: Context): Phone {
        val vk11 = ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, 0x401000)
        val mem = ActivityManager.MemoryInfo().also { ctx.getSystemService(ActivityManager::class.java)?.getMemoryInfo(it) }
        return Phone(vk11, mem.totalMem, "arm64-v8a" in Build.SUPPORTED_ABIS)
    }

    // --- What this phone has done --------------------------------------------------------------

    /** [build] = the phone's system build (changes with system and graphics driver updates); [app] = the app version. */
    data class Saved(val result: Result?, val unlockedAnyway: Boolean, val build: String?, val app: Long?, val time: Long?)

    fun read(s: SyncStore) = Saved(
        Result.of(s.get(K_RESULT)), s.get(K_ANYWAY) == "1", s.get(K_BUILD), s.get(K_APP)?.toLongOrNull(), s.get(K_TIME)?.toLongOrNull(),
    )

    fun save(s: SyncStore, r: Result, build: String, app: Long, time: Long) {
        s.set(K_RESULT, r.key); s.set(K_BUILD, build); s.set(K_APP, app.toString()); s.set(K_TIME, time.toString())
        if (r != Result.FAIL) s.set(K_ANYWAY, null) // a pass replaces an earlier "Unlock anyway"
    }

    fun unlockAnyway(s: SyncStore) = s.set(K_ANYWAY, "1")

    /** 3DS games can be downloaded and played. */
    fun unlocked(v: Saved) = v.unlockedAnyway || v.result == Result.PASS || v.result == Result.BORDERLINE

    /** The phone or the app changed since the check: worth running it again (3DS stays unlocked). */
    fun stale(v: Saved, build: String, app: Long) = v.result != null && (v.build != build || v.app != app)

    /** One line for the library and Settings. */
    fun status(v: Saved, build: String, app: Long): String = when {
        v.result == null && !v.unlockedAnyway -> "🔒 Run the 3DS check first (Settings → 3DS check)"
        v.result == Result.FAIL && !v.unlockedAnyway -> "🔒 Too slow on this phone (Settings → 3DS check)"
        stale(v, build, app) -> "The phone or app has updated: run the 3DS check again"
        v.unlockedAnyway && v.result == Result.FAIL -> "Unlocked anyway (the 3DS check said too slow)"
        else -> "3DS check: ${v.result?.label}"
    }

    private const val K_RESULT = "result"
    private const val K_ANYWAY = "anyway"
    private const val K_BUILD = "build"
    private const val K_APP = "app"
    private const val K_TIME = "time"

    fun store(ctx: Context): SyncStore = object : SyncStore {
        private val prefs = ctx.getSharedPreferences("n3ds_check", Context.MODE_PRIVATE)
        override fun get(key: String): String? = prefs.getString(key, null)
        override fun set(key: String, value: String?) {
            prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
        }
    }

    fun saved(ctx: Context) = read(store(ctx))
    fun unlocked(ctx: Context) = unlocked(saved(ctx))
    fun status(ctx: Context) = status(saved(ctx), Build.FINGERPRINT, BuildConfig.VERSION_CODE.toLong())

    // --- Words ---------------------------------------------------------------------------------

    const val TITLE = "3DS check"
    const val INTRO = "3DS games need a lot from a phone. This check plays a short 3D test scene for about " +
        "${WARM_UP_S + MEASURE_S} seconds and measures how fast this phone runs it. 3DS games unlock if it's fast enough. " +
        "It stops early if the phone gets too hot."
    const val START = "Start the 3DS check"
    const val AGAIN = "Run the check again"
    const val ANYWAY = "Unlock anyway"
    const val ANYWAY_WARNING = "This phone ran the check too slowly. 3DS games will probably stutter, slow down or " +
        "sound choppy. You can still try them, but it isn't supported. Unlock 3DS anyway?"
    const val TOO_HOT = "The check stopped because the phone got too hot. Let it cool down and try again."
    const val CANCELLED = "The check was stopped before the end. Nothing changed."
    const val LOCKED_TITLE = "Run the 3DS check first"
    const val LOCKED_TEXT = "Before downloading 3DS games, Shoalcade checks this phone is fast enough for them. It takes about a minute."
    const val OPEN = "Open the 3DS check"
}
