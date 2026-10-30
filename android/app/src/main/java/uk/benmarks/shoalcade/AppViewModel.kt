package uk.benmarks.shoalcade

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import java.io.File
import java.io.IOException

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SessionStore(app)
    private val cacheFile = File(app.filesDir, "games.json")
    val saves = SaveSync(app)
    private val library = LibraryPrefs.of(app)

    var session by mutableStateOf(store.load())
        private set
    var games by mutableStateOf<List<Game>>(emptyList())
        private set
    /** L2: [games] is a real list (from Jellyfin or the saved copy), not just "nothing loaded yet". */
    var gamesKnown by mutableStateOf(false)
        private set
    var loading by mutableStateOf(false)
        private set
    var offline by mutableStateOf(false)
        private set
    private val notices = Notices()
    private var shownMessage by mutableStateOf<String?>(null)
    /** The snackbar message. A new one waits for the one showing (L1); set null once shown to move on to the next. */
    var message: String?
        get() = shownMessage
        set(v) { shownMessage = if (v == null) notices.shown() else notices.post(v) }
    /** Bumped whenever files on disk change, so "downloaded" badges recompute (downloads finish in the service). */
    private var localDiskVersion by mutableStateOf(0)
    val diskVersion: Int get() = localDiskVersion + Downloads.finished
    val progress get() = Downloads.progress
    /** Bumped when new box art arrives. */
    var artVersion by mutableStateOf(0)
        private set
    /** Set when the server plugin is missing or the versions don't match (D41): shown as a banner; offline play still works. */
    var serverProblem by mutableStateOf<String?>(null)
        private set
    /** Games whose save upload hit a conflict the user hasn't settled (D35: always ask). */
    var conflicts by mutableStateOf<Set<String>>(emptySet())
        private set
    /** The conflict to ask about now (game + the server's current save). */
    var conflictPrompt by mutableStateOf<Pair<Game, SaveVersion?>?>(null)
    /** Server save keep rule, for the saves panel ("last 20 + one a day for 30 days"). */
    var saveRules by mutableStateOf<Pair<Int, Int>?>(null)
        private set
    /** Longest save name the server keeps (saveRules.maxLabelLength), for the saves panel's Name box. */
    var maxLabelLength by mutableStateOf(JellyfinClient.DEFAULT_MAX_LABEL)
        private set

    /** The games list order, remembered per user. */
    var sort by mutableStateOf(GameSort.DEFAULT)
        private set

    init {
        session?.let { sort = library.sort(it.userId) }
        readCache().let { games = it ?: emptyList(); gamesKnown = it != null }
        refreshConflicts()
        if (session != null) refresh()
    }

    // ---- F3: connected to the server? (see [ServerStatus]) ----

    /** Network change: lost = not connected straight away; a new network = check the server once. */
    private val netWatch = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { viewModelScope.launch(Dispatchers.Main) { checkConnection() } }
        override fun onLost(network: Network) { ServerStatus.disconnected() }
    }
    private var checkJob: Job? = null

    init { app.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(netWatch) } // after netWatch exists

    /**
     * One light, signed-in call to the server (the plugin's info, as on start), on return to the app and on a network
     * change. Skipped while the list is loading or another call answered in the last few seconds: no polling.
     * Back online after being offline: reloads the list too.
     */
    fun checkConnection() {
        if (session == null) { ServerStatus.disconnected(); return }
        if (loading || checkJob?.isActive == true) return
        if (ServerStatus.recentlyConnected(CHECK_GAP_MS)) return
        checkJob = viewModelScope.launch {
            val reached = try { client().info(); true }
                catch (e: CancellationException) { throw e }
                catch (e: HttpException) { JellyfinClient.answered(e.code) } // ServerStatus already updated by the client
                catch (e: Exception) { false }
            if (reached && offline) refresh()
        }
    }

    override fun onCleared() {
        runCatching { getApplication<Application>().getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(netWatch) }
        super.onCleared()
    }

    private fun client() = JellyfinClient(store.deviceId, session)

    fun login(server: String, user: String, password: String, onError: (String) -> Unit) {
        loading = true
        viewModelScope.launch {
            try {
                completeLogin(JellyfinClient(store.deviceId).login(server, user, password))
            } catch (e: IOException) {
                onError(e.message ?: "Couldn't reach the server.")
            } catch (e: Exception) {
                onError("Login failed: ${e.message}")
            } finally {
                loading = false
            }
        }
    }

    private fun completeLogin(s: Session) {
        store.save(s)
        session = s
        sort = library.sort(s.userId)
        readCache().let { games = it ?: emptyList(); gamesKnown = it != null }
        refresh()
    }

    /** The code to show while waiting for Quick Connect approval; null when not waiting. */
    var quickConnectCode by mutableStateOf<String?>(null)
        private set
    private var quickConnectJob: Job? = null

    fun startQuickConnect(server: String, onError: (String) -> Unit) {
        quickConnectJob?.cancel()
        quickConnectJob = viewModelScope.launch {
            val c = JellyfinClient(store.deviceId)
            try {
                val qc = c.quickConnectStart(server)
                quickConnectCode = qc.code
                // Jellyfin codes last about 10 minutes; stop asking after that.
                val approved = withTimeoutOrNull(10 * 60_000L) {
                    while (!c.quickConnectApproved(qc)) delay(3_000)
                    true
                }
                if (approved == null) { onError("The Quick Connect code expired. Please try again."); return@launch }
                completeLogin(c.quickConnectFinish(qc))
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                onError(e.message ?: "Couldn't reach the server.")
            } catch (e: Exception) {
                onError("Quick Connect failed: ${e.message}")
            } finally {
                quickConnectCode = null
            }
        }
    }

    fun cancelQuickConnect() {
        quickConnectJob?.cancel()
        quickConnectJob = null
        quickConnectCode = null
    }

    /** [keepCache] = true when the token expired: the game list stays so nothing vanishes offline. */
    fun logout(keepCache: Boolean = false) {
        // L9: also end the login on the server, best effort and in the background. Skipped when the
        // token has already expired (keepCache). Local clearing below never waits for it.
        val old = session
        if (!keepCache && old != null) {
            val c = JellyfinClient(store.deviceId, old)
            viewModelScope.launch { c.logout() }
        }
        store.clear()
        ServerStatus.disconnected()
        serverProblem = null
        conflicts = emptySet()
        session = null
        games = emptyList()
        gamesKnown = false
        if (!keepCache) cacheFile.delete()
    }

    fun refresh() {
        loading = true
        viewModelScope.launch {
            try {
                val c = client()
                val url = session?.serverUrl ?: return@launch
                // Two-way version check first (D41). On a mismatch the cached list stays, so downloaded games still play.
                val info = try {
                    c.info()
                } catch (e: HttpException) {
                    if (e.code != 404) throw e
                    null
                }
                val problem = if (info == null) Compat.PLUGIN_MISSING else Compat.problem(info)
                Compat.remember(url, problem)
                serverProblem = problem
                offline = false
                if (info == null || problem != null) return@launch
                saveRules = info.keepRecent to info.keepDailyDays
                maxLabelLength = info.maxLabelLength
                val before = games // names for phone saves whose game has left the server (N7)
                games = c.listGames(info.canDownload).map { it.game }
                gamesKnown = true
                writeCache(games)
                val list = games
                launch { if (BoxArt.fetchMissing(getApplication(), c, list)) artVersion++ } // don't hold the spinner
                launch {
                    try {
                        SnapshotSetting.store(getApplication(), c.snapshotSettings())
                    } catch (e: IOException) {
                        message = "Snapshot setting not refreshed, so the last one is used: ${e.message}"
                    }
                }
                launch { migrateSaves(list, before) }
            } catch (e: HttpException) {
                if (e.code == 401) { message = "Your login has expired. Please log in again."; logout(keepCache = true) }
                else { offline = true; message = e.message }
            } catch (e: IOException) {
                offline = true // No network: keep showing the cached list so downloaded games stay playable.
            } finally {
                loading = false
            }
        }
    }

    fun chooseSort(s: GameSort) {
        sort = s
        session?.let { library.setSort(it.userId, s) }
    }

    /** [list] in the chosen order. Last played = the later of this phone's last start and Jellyfin's (browser play). */
    fun sorted(list: List<Game>): List<Game> {
        val uid = session?.userId
        return GameSort.sort(list, sort) { g -> GameSort.lastPlayed(uid?.let { library.lastLaunch(it, g.id) }, g.serverPlayed) }
    }

    /** Runs in [DownloadService], so it carries on with the app closed. */
    fun download(game: Game) = Downloads.start(getApplication(), game)

    fun cancelDownload(game: Game) = Downloads.cancel(getApplication(), game.id)

    /** On return to the library (e.g. from a game whose upload hit a conflict): recheck badges and ⚠️ marks. */
    fun diskVersionBump() { localDiskVersion++; refreshConflicts() }

    fun deleteDownload(game: Game) {
        Storage.deleteDownload(game) // saves are kept
        localDiskVersion++
        message = "${game.name} removed from the phone. Your save is kept."
    }

    /**
     * a0.5: saves made on this phone in the JellyEMU days reach the plugin with the normal conflict rules
     * (phone backup first; see [SaveSyncCore.migrate]). Conflicts are flagged for the user, never settled here.
     */
    private suspend fun migrateSaves(list: List<Game>, before: List<Game>) {
        val core = saves.core() ?: return
        val uid = session?.userId ?: return
        val local = list.filter { Storage.saveTime(uid, it) != null }
        val need = core.migrate(local)
        refreshConflicts()
        val notes = mutableListOf<String>()
        if (need.isNotEmpty()) {
            notes += if (need.size == 1) "${need[0].name}: the phone save and the Jellyfin save differ. Tap ⚠️ to choose which to keep."
            else "${need.size} games have different saves on the phone and Jellyfin. Tap ⚠️ on each to choose which to keep."
        }
        // N7: never-uploaded phone saves for games Jellyfin no longer lists can't be migrated. Say so once each (item D);
        // they stay on the phone.
        val names = before.associate { it.id to it.name }
        core.rememberGone(before, list.map { it.id }.toSet()) // B9: so "Not in your library" can name them later
        val orphans = core.orphansToMention(list.map { it.id }.toSet()).toList()
        if (orphans.isNotEmpty()) {
            val known = orphans.mapNotNull { names[it] }
            val which = if (known.isEmpty()) "" else ": " + known.joinToString(", ") +
                (if (known.size < orphans.size) " and ${orphans.size - known.size} more" else "")
            notes += (if (orphans.size == 1) "1 phone save is for a game no longer in your library, so it wasn't uploaded"
                else "${orphans.size} phone saves are for games no longer in your library, so they weren't uploaded") +
                "$which. " + (if (orphans.size == 1) "It's" else "They're") + " kept on the phone."
        }
        if (notes.isNotEmpty()) message = notes.joinToString(" ")
    }

    fun refreshConflicts() {
        val core = saves.core() ?: return
        conflicts = games.filter { core.hasConflict(it) }.map { it.id }.toSet()
    }

    private fun show(r: SyncResult, game: Game) {
        message = r.message
        if (r is SyncResult.Conflict) conflictPrompt = game to r.server
        localDiskVersion++
        refreshConflicts()
    }

    private fun syncOp(game: Game, op: suspend SaveSyncCore.() -> SyncResult) = viewModelScope.launch {
        batch?.let { message = "Wait for ${it.way.word} to finish first."; return@launch }
        val core = saves.core()
        if (core == null) { message = "Not logged in."; return@launch }
        show(core.op(), game)
    }

    /** Normal upload with the version check; a conflict opens the "which to keep" question. */
    fun uploadSave(game: Game) = syncOp(game) { upload(game) }

    fun downloadSave(game: Game) = syncOp(game) { download(game) }

    /** Opens the conflict question for a game marked ⚠️ (looks up the server's current save first). */
    fun askConflict(game: Game) = viewModelScope.launch {
        val core = saves.core() ?: return@launch
        try {
            conflictPrompt = game to core.check(game).first
        } catch (e: IOException) {
            message = "Couldn't check the Jellyfin save: ${e.message}"
        }
    }

    fun resolveConflict(game: Game, keepPhone: Boolean) {
        conflictPrompt = null
        syncOp(game) { if (keepPhone) keepPhone(game) else keepServer(game) }
    }

    // ---- Push all / Pull all (item 6) ----

    /** A run in progress: which way, how far ([index] of [total], 0-based), and the game being done now. */
    class BatchProgress(val way: SyncAll.Way, val index: Int, val total: Int, val game: String)

    var batch by mutableStateOf<BatchProgress?>(null)
        private set
    /** Stop pressed: the run stops before the next game (the current one always finishes). */
    var batchStopping by mutableStateOf(false)
        private set
    /** The finished run's summary, shown until closed. */
    var batchReport by mutableStateOf<SyncAll.Report?>(null)
    /** Games needing a decision, and what happened once one was chosen (by game id). */
    val batchAsks = mutableStateListOf<SyncAll.Ask>()
    val batchSettled = mutableStateMapOf<String, String>()
    /** The game whose choice is being carried out now (buttons wait). */
    var settling by mutableStateOf<String?>(null)
        private set

    /** Safe ones are done straight away; the rest come back as a list to decide ([settleAsk]). Never runs during a game. */
    fun syncAll(way: SyncAll.Way) {
        if (batch != null || settling != null) return
        if (GameActivity.running) { message = "Close the game first: ${way.word} doesn't run while a game is open."; return }
        val core = saves.core() ?: run { message = "Not logged in."; return }
        if (offline) { message = "You're offline, so ${way.word} didn't run. Nothing was changed."; return }
        serverProblem?.let { message = "${way.word} didn't run, nothing was changed: $it"; return }
        val list = SyncAll.candidates(games)
        if (list.isEmpty()) { message = "${way.word}: there are no downloaded games."; return }
        batchStopping = false
        batch = BatchProgress(way, 0, list.size, list[0].name)
        viewModelScope.launch {
            try {
                val report = SyncAll(core).run(way, list, stop = { batchStopping }) { i, g -> batch = BatchProgress(way, i, list.size, g.name) }
                batchAsks.clear(); batchAsks.addAll(report.asks); batchSettled.clear()
                batchReport = report
            } finally {
                batch = null; batchStopping = false
                localDiskVersion++; refreshConflicts()
            }
        }
    }

    fun stopBatch() { if (batch != null) batchStopping = true }

    fun settleAsk(ask: SyncAll.Ask, choice: SyncAll.Choice) {
        if (settling != null || batch != null) return
        if (choice == SyncAll.Choice.LATER) {
            batchSettled[ask.game.id] = "Left as it is: both saves are unchanged." +
                if (ask.conflict) " It stays marked ⚠️ in the list." else ""
            return
        }
        val core = saves.core() ?: run { message = "Not logged in."; return }
        settling = ask.game.id
        viewModelScope.launch {
            try {
                val all = SyncAll(core)
                val r = all.settle(ask, choice)
                // L3: Jellyfin's save changed since the question: show the same question again with the newer save.
                val next = all.askAgain(ask, r)
                val i = batchAsks.indexOfFirst { it.game.id == ask.game.id }
                if (next != null && i >= 0) batchAsks[i] = next
                else r?.let { batchSettled[ask.game.id] = it.message }
            } finally {
                settling = null; localDiskVersion++; refreshConflicts()
            }
        }
    }

    // ---- Saves panel ----

    suspend fun history(game: Game): SaveHistory {
        val s = session ?: throw IOException("Not logged in.")
        val c = client()
        Compat.ensure(c, s.serverUrl)
        return c.history(game.id)
    }

    suspend fun versionBytes(game: Game, version: Int): SaveDownload =
        client().getSave(game.id, version) ?: throw IOException("That version has gone.")

    /**
     * Pin / unpin and/or name a version on Jellyfin (same call as the browser saves manager). Only the version's
     * pin and name change; no save bytes move, so nothing on the phone is touched. [done] gets null or the error
     * text, and whether Jellyfin couldn't be reached at all (no network, or a Wi-Fi sign-in page) rather than refused.
     */
    fun updateVersion(game: Game, version: Int, pinned: Boolean? = null, label: String? = null,
                      done: (error: String?, unreachable: Boolean) -> Unit) =
        viewModelScope.launch {
            if (session == null) return@launch done("Not logged in.", false)
            try {
                client().updateVersion(game.id, version, pinned, label, maxLabelLength)
                done(null, false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpException) {
                done(if (e.code == 401) "Your login has expired. Please log in again." else e.message ?: "Refused by the server.", false)
            } catch (e: IOException) {
                done(e.message ?: "Couldn't reach Jellyfin.", true)
            } catch (e: Exception) {
                done(e.message ?: "Something went wrong.", false)
            }
        }

    fun restore(game: Game, version: Int, base: Int, done: (SyncResult) -> Unit) = viewModelScope.launch {
        val r = saves.core()?.restore(game, version, base) ?: SyncResult.Failed("Not logged in.")
        message = r.message; localDiskVersion++; refreshConflicts(); done(r)
    }

    fun replace(game: Game, bytes: ByteArray, base: Int, mode: String, allowBlank: Boolean, done: (SyncResult) -> Unit) =
        viewModelScope.launch {
            val r = saves.core()?.replace(game, bytes, base, mode, allowBlank) ?: SyncResult.Failed("Not logged in.")
            if (r !is SyncResult.Conflict) message = r.message
            localDiskVersion++; refreshConflicts(); done(r)
        }

    // ---- Not in your library (B9) ----

    /**
     * Phone saves for games the library no longer lists. Reads only; works offline (against the cached list). A failed
     * read (L1) or a game list not loaded yet (L2) is its own answer, never an empty list.
     */
    suspend fun orphanSaves(): OrphanView {
        val core = saves.core()
        val listed = games.map { it.id }.toSet()
        val known = gamesKnown
        return withContext(Dispatchers.IO) { OrphanView.load(known, core?.let { c -> { c.orphanSaves(listed) } }) }
    }

    suspend fun orphanBytes(o: OrphanSave): ByteArray? = withContext(Dispatchers.IO) { saves.core()?.orphanBytes(o) }

    /** "Upload to another game…": see [SaveSyncCore.uploadOrphan]. The phone save stays where it is. */
    fun uploadOrphan(o: OrphanSave, target: Game, mode: String, done: (SyncResult) -> Unit) = viewModelScope.launch {
        val r = saves.core()?.uploadOrphan(o, target, mode) ?: SyncResult.Failed("Not logged in.")
        if (r !is SyncResult.Conflict) message = r.message
        localDiskVersion++; refreshConflicts(); done(r)
    }

    /** F9 "Remove download": deletes only the phone's downloaded copy of a game no longer listed. Saves, backups and snapshots stay. */
    fun removeOrphanDownload(o: OrphanSave, done: () -> Unit) = viewModelScope.launch {
        if (GameActivity.running) { message = "Close the game first."; done(); return@launch }
        val ok = withContext(Dispatchers.IO) { runCatching { Storage.deleteDownload(o.game) }.isSuccess }
        localDiskVersion++
        message = if (ok) "${o.title} removed from the phone. Its save, backups and snapshot are kept." else "Couldn't remove ${o.title}."
        done()
    }

    /** F9: deletes the phone snapshot of a game no longer listed (after the person confirmed). The save is never touched. */
    fun deleteOrphanSnapshot(o: OrphanSave, done: () -> Unit) = viewModelScope.launch {
        val uid = session?.userId ?: run { message = "Not logged in."; done(); return@launch }
        if (GameActivity.running) { message = "Close the game first."; done(); return@launch }
        withContext(Dispatchers.IO) { Storage.deleteSnapshot(uid, o.game) }
        localDiskVersion++
        message = "Snapshot of ${o.title} deleted. Its save is kept."
        done()
    }

    /** F9: the save's older copies on this phone (newest first). Reads only. */
    suspend fun orphanBackups(o: OrphanSave): List<Storage.Backup> {
        val uid = session?.userId ?: return emptyList()
        return withContext(Dispatchers.IO) { Storage.backups(uid, o.id) }
    }

    /** F9: puts an older copy back as the phone save; the save there now is backed up first ([Storage.restoreBackup]). */
    fun restoreOrphanBackup(o: OrphanSave, b: Storage.Backup, done: (String?) -> Unit) = viewModelScope.launch {
        val uid = session?.userId ?: return@launch done("Not logged in.")
        if (GameActivity.running) return@launch done("Close the game first.")
        val err = withContext(Dispatchers.IO) { runCatching { Storage.restoreBackup(uid, o.game, b) }.exceptionOrNull() }
        localDiskVersion++
        done(err?.let { it.message ?: it.javaClass.simpleName })
    }

    private fun writeCache(list: List<Game>) {
        val arr = JSONArray()
        list.forEach { arr.put(GameJson.toJson(it)) }
        cacheFile.writeText(arr.toString())
    }

    /** Null when there is no readable saved list (L2: the game list is then not known). */
    private fun readCache(): List<Game>? = runCatching {
        val arr = JSONArray(cacheFile.readText())
        (0 until arr.length()).map { i -> GameJson.fromJson(arr.getJSONObject(i)) }
    }.getOrNull()
}

/**
 * L1: snackbar messages take turns instead of replacing each other, so a once-only notice (the orphan saves, item D)
 * can't be overwritten by another message before it shows. The same text already showing or waiting isn't added twice.
 * Plain Kotlin, so the JVM tests run exactly this code.
 */
private const val CHECK_GAP_MS = 5_000L

class Notices {
    private val waiting = ArrayDeque<String>()
    private var current: String? = null

    /** Adds [m]; returns the message to show now. */
    @Synchronized fun post(m: String): String? {
        if (current == null) current = m else if (m != current && m !in waiting) waiting.addLast(m)
        return current
    }

    /** The message showing has been seen; returns the next one (null = none). */
    @Synchronized fun shown(): String? {
        current = waiting.removeFirstOrNull()
        return current
    }
}
