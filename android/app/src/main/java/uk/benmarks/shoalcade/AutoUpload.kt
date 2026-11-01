package uk.benmarks.shoalcade

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Auto upload: a save that couldn't go up when the game closed (offline, or the server didn't answer) goes up to
 * Jellyfin by itself as soon as the phone is back online, without opening the app. A Settings switch turns it off.
 *
 * Only what is already safe without a person is done, through the app's own [SaveSyncCore.uploadLatest] and its
 * [SavePlan] rule: the save goes up only when Jellyfin's save hasn't changed since this phone last synced (or Jellyfin
 * has none), with the server's version check on the upload itself. Anything that needs a choice (both changed, or it
 * would replace a newer Jellyfin save) is never settled here: nothing is sent, the game gets ⚠️ in the library (the
 * existing "which to keep" question) and a notification says so. Uploading never changes the phone save, and the
 * server keeps the save it replaces in its list.
 */
object AutoUpload {
    private const val KEY_ON = "on"
    const val WORK = "auto-upload"
    const val NEEDS_CHOICE_TITLE = "Shoalcade: a save needs your choice"
    private const val CHANNEL = "saves"
    private const val NOTIF_ID = 43

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

    // --- Android wiring -----------------------------------------------------------------------

    /** Per-phone store: the switch and the waiting saves. Not secret (no token, only game ids and names). */
    fun store(ctx: Context): SyncStore = object : SyncStore {
        private val prefs = ctx.applicationContext.getSharedPreferences("autoupload", Context.MODE_PRIVATE)
        override fun get(key: String): String? = try { prefs.getString(key, null) } catch (_: ClassCastException) { null }
        override fun set(key: String, value: String?) {
            prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
        }
    }

    fun on(ctx: Context) = on(store(ctx))

    /** The Settings switch. On: waiting saves are sent once online. Off: nothing goes up by itself (they keep waiting). */
    fun setOn(ctx: Context, on: Boolean) {
        setOn(store(ctx), on)
        if (on) schedule(ctx) else runCatching { WorkManager.getInstance(ctx).cancelUniqueWork(WORK) }
    }

    /**
     * Runs [AutoUploadWorker] once the phone is online (now, if it is). One at a time: KEEP leaves a run that is already
     * waiting or running alone; a running one reads the waiting list again before it finishes, so nothing is missed.
     * WorkManager keeps it through a reboot or the app being closed. No polling, no foreground service.
     */
    fun schedule(ctx: Context) {
        if (!on(ctx)) return
        val req = OneTimeWorkRequestBuilder<AutoUploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, req)
    }

    /** App start: if saves are still waiting (e.g. the login had expired, now logged in again), try them once online. */
    fun onAppStart(ctx: Context) {
        runCatching { if (on(ctx) && WaitingSaves(store(ctx)).any()) schedule(ctx) }
    }

    /** What [SaveSyncCore] tells about a save that couldn't go up now. */
    fun later(ctx: Context): SaveSyncCore.UploadLater {
        val app = ctx.applicationContext
        return object : SaveSyncCore.UploadLater {
            override val on: Boolean get() = on(app)
            override fun waiting(userId: String, game: Game) {
                WaitingSaves(store(app)).add(userId, game)
                runCatching { schedule(app) }
            }
        }
    }

    /** Android 13+: shown only if the person allowed notifications. Otherwise the ⚠️ in the library is the only sign. */
    fun notifyChoice(ctx: Context, games: List<Game>) {
        if (games.isEmpty()) return
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Saves", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(ctx, NOTIF_ID,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE)
        val text = choiceText(games.map { it.name })
        nm.notify(NOTIF_ID, Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(NEEDS_CHOICE_TITLE)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build())
    }
}

/**
 * Saves waiting to go up by themselves, one line each: user id, a stamp, the game (as [GameJson]). The stamp changes
 * every time a game is added again, so a run only clears the entry it actually tried ([remove]); a newer failure for
 * the same game keeps waiting. Only ids and names: never a token.
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
 * One auto upload run for the logged-in user. Plain Kotlin, so the JVM tests run exactly this against the fake server.
 * [playing] = a game is open right now: nothing is sent while one is (try again later), so a running game's save is
 * never uploaded under it. Each game goes through [SaveSyncCore.uploadLatest], which waits for any other save move of
 * the same game in the app ([SaveSyncCore] lock) and then sees what it did, so nothing is sent twice.
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

/** WorkManager's side of [AutoUploadRun]: switch off or logged out = nothing to do. */
class AutoUploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        if (!AutoUpload.on(app)) return Result.success()
        val core = withContext(Dispatchers.IO) { SaveSync(app).core() } ?: return Result.success() // logged out
        val report = AutoUploadRun(core, WaitingSaves(AutoUpload.store(app)),
            playing = { withContext(Dispatchers.Main) { GameActivity.running } }).run()
        AutoUpload.notifyChoice(app, report.needChoice)
        return if (report.outcome == AutoUploadRun.Outcome.RETRY) Result.retry() else Result.success()
    }
}
