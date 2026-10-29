package uk.benmarks.shoalcade

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shared download state, read by the library screen. Lives outside the ViewModel so downloads
 * carry on (inside [DownloadService]) when the app is closed or the screen is off.
 */
object Downloads {
    /** Game id → 0..1 while downloading. Only written on the main thread. */
    val progress = mutableStateMapOf<String, Float>()
    /** Last finished/failed message for the snackbar. */
    var message by mutableStateOf<String?>(null)
    /** Bumped when a download finishes, so "On phone" badges recompute. */
    var finished by mutableStateOf(0)
        private set

    fun start(ctx: Context, game: Game) {
        if (progress.containsKey(game.id)) return
        progress[game.id] = 0f
        ctx.startForegroundService(Intent(ctx, DownloadService::class.java)
            .putExtra(DownloadService.EXTRA_GAME, GameJson.toJson(game).toString()))
    }

    fun cancel(ctx: Context, gameId: String) {
        ctx.startService(Intent(ctx, DownloadService::class.java)
            .setAction(DownloadService.ACTION_CANCEL).putExtra(DownloadService.EXTRA_ID, gameId))
    }

    internal fun done(gameId: String, msg: String) {
        progress.remove(gameId)
        message = msg
        finished++
    }
}

/** Foreground service: keeps ROM downloads going with the app in the background. */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val jobs = mutableMapOf<String, Job>()
    private val names = mutableMapOf<String, String>()
    private lateinit var wakeLock: PowerManager.WakeLock

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW))
        // Keeps the CPU awake so a screen-off download doesn't stall. Released in onDestroy.
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Shoalcade:download").apply { acquire(6 * 60 * 60_000L) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must call startForeground promptly after startForegroundService, whatever the command.
        startForeground(NOTIF_ID, notification("Starting download…", null),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        if (intent?.action == ACTION_CANCEL) {
            intent.getStringExtra(EXTRA_ID)?.let { jobs[it]?.cancel() }
            stopIfIdle()
            return START_NOT_STICKY
        }
        val game = intent?.getStringExtra(EXTRA_GAME)?.let { runCatching { GameJson.fromJson(it) }.getOrNull() }
        if (game == null || jobs.containsKey(game.id)) { stopIfIdle(); return START_NOT_STICKY }

        names[game.id] = game.name
        jobs[game.id] = scope.launch {
            var msg = "Download stopped. Tap Download to try again."
            try {
                val saveNote = download(game)
                msg = "${game.name} downloaded." + (saveNote?.let { " $it" } ?: "")
            } catch (e: CancellationException) {
                // The message is set first: withContext throws CancellationException again when it returns to this
                // cancelled job, so nothing after it here runs. The bookkeeping is in finally for the same reason.
                if (game.isFolderGame) {
                    // A folder game keeps the files that arrived (it isn't "downloaded" until the list is written),
                    // so a big game carries on where it stopped. Only half-written .part files go.
                    msg = "${game.name} download stopped. Tap Download to carry on from where it stopped."
                    withContext(NonCancellable + Dispatchers.IO) {
                        Storage.downloadFolder(game).walkTopDown().filter { it.isFile && it.name.endsWith(".part") }.forEach { it.delete() }
                    }
                } else {
                    msg = "${game.name} download cancelled."
                    withContext(NonCancellable + Dispatchers.IO) { Storage.deleteDownload(game) } // drop the .part file
                }
            } catch (e: Exception) {
                msg = "Download failed: ${e.message}"
            } finally {
                jobs.remove(game.id)
                names.remove(game.id)
                Downloads.done(game.id, msg)
                stopIfIdle()
            }
        }
        return START_NOT_STICKY // if Android kills us, the .part file is simply left; tap Download again
    }

    /** Returns what happened to the Jellyfin save (shown with the "downloaded" message), or null if nothing to say. */
    private suspend fun download(game: Game): String? {
        val store = SessionStore(this)
        val client = JellyfinClient(store.deviceId, store.load() ?: throw java.io.IOException("Not logged in."))
        var lastPct = -1
        val onProgress = { p: Float ->
            val pct = (p * 100).toInt()
            if (pct != lastPct) { // throttle: at most 100 UI/notification updates per game
                lastPct = pct
                scope.launch {
                    if (jobs.containsKey(game.id)) Downloads.progress[game.id] = p
                    updateNotification()
                }
            }
        }
        if (game.isFolderGame) {
            val list = client.folderFiles(game.id)
            FolderDownload.run(Storage.downloadFolder(game), list, { f, dest -> client.fetchFolderFile(game.id, f, dest) }, onProgress)
            return fetchSave(game)
        }
        val target = client.download(game.id, Storage.downloadFolder(game), Storage.fallbackRomName(game), onProgress)
        withContext(Dispatchers.IO) {
            Storage.unpackIfZipped(game, target)
            if (game.console == Console.PSP) PspAssets.ensure(this@DownloadService) // ready for offline play
        }
        return fetchSave(game)
    }

    /** Pull the Jellyfin save if the phone has none yet. A failure here never fails the download, but is shown. */
    private suspend fun fetchSave(game: Game): String? {
        val r = try {
            SaveSync(this).fetchIfNoLocal(game)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SyncResult.Failed("${e.message}")
        }
        return when (r) {
            null -> null // the phone already has a save: left alone
            is SyncResult.Done -> "The Jellyfin save is on the phone too."
            else -> "The Jellyfin save wasn't fetched: ${r.message}"
        }
    }

    private fun updateNotification() {
        if (jobs.isEmpty()) return
        val first = jobs.keys.first()
        val title = if (jobs.size == 1) "Downloading ${names[first]}" else "Downloading ${jobs.size} games"
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, notification(title, Downloads.progress[first], first))
    }

    private fun notification(title: String, progress: Float?, cancelId: String? = null): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply {
                if (progress != null) setProgress(100, (progress * 100).toInt(), false) else setProgress(0, 0, true)
                if (cancelId != null) {
                    val cancel = PendingIntent.getService(this@DownloadService, cancelId.hashCode(),
                        Intent(this@DownloadService, DownloadService::class.java)
                            .setAction(ACTION_CANCEL).putExtra(EXTRA_ID, cancelId),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                    addAction(Notification.Action.Builder(null, "Cancel", cancel).build())
                }
            }
            .build()
    }

    private fun stopIfIdle() {
        if (jobs.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else updateNotification()
    }

    override fun onDestroy() {
        scope.cancel()
        // Anything still marked as downloading (service killed) is cleared so Download can be tapped again.
        jobs.keys.toList().forEach { Downloads.done(it, "Download stopped. Tap Download to try again.") }
        if (wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_GAME = "game"
        const val EXTRA_ID = "id"
        const val ACTION_CANCEL = "uk.benmarks.shoalcade.CANCEL_DOWNLOAD"
        private const val CHANNEL = "downloads"
        private const val NOTIF_ID = 42
    }
}
