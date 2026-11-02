package uk.benmarks.shoalcade.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import uk.benmarks.shoalcade.AutoUpload
import uk.benmarks.shoalcade.BuildConfig
import uk.benmarks.shoalcade.CheatSyncCore
import uk.benmarks.shoalcade.Compat
import uk.benmarks.shoalcade.SaveSyncCore
import uk.benmarks.shoalcade.SaveVersion
import uk.benmarks.shoalcade.SyncAll
import uk.benmarks.shoalcade.SyncRename
import uk.benmarks.shoalcade.SyncResult
import uk.benmarks.shoalcade.SyncStore
import uk.benmarks.shoalcade.WaitingSaves
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.NonCancellable
import uk.benmarks.shoalcade.Game
import uk.benmarks.shoalcade.GameSort
import uk.benmarks.shoalcade.HttpException
import uk.benmarks.shoalcade.JellyfinClient
import uk.benmarks.shoalcade.LibraryPrefs
import uk.benmarks.shoalcade.N3dsCheck
import uk.benmarks.shoalcade.Notices
import uk.benmarks.shoalcade.PlayTimeQueue
import uk.benmarks.shoalcade.ServerStatus
import uk.benmarks.shoalcade.Session
import uk.benmarks.shoalcade.SessionStore
import uk.benmarks.shoalcade.Storage
import java.io.IOException

/**
 * The library window's state and actions: the desktop version of the phone's AppViewModel (android/.../AppViewModel.kt)
 * for login, the game list, downloads, stars and play time. Same calls, same order and same words; what the phone does
 * with Android (ConnectivityManager, a download service) is done here with the window's focus and coroutines.
 * Saves and play (phase 3): the phone's own SaveSyncCore / SyncAll through [core], the play session ([PlaySession]),
 * the saves panel and the browser's save tools ([SaveTools]), server snapshots ([SnapshotStore]) and "upload when back
 * online" ([UploadWhenOnline], D184). Link play comes in a later phase.
 *
 * [dev] = the Dev build's rules (http://localhost allowed). [scope] runs on the UI thread (Dispatchers.Main = Swing).
 */
class AppModel(
    private val scope: CoroutineScope,
    private val dev: Boolean = BuildConfig.DEBUG,
    private val store: SessionStore = SessionStore(DesktopStorage.context()),
    private val timeouts: JellyfinClient.Timeouts = JellyfinClient.Timeouts(),
    /** Where the player and cores are (tests point at their own). */
    private val setupFor: () -> PlayerSetup = { PlayerSetup.find(DesktopStorage.paths) },
    /** Extra player arguments (tests: `--headless`). Never anything secret. */
    private val playerArgs: List<String> = emptyList(),
    /** How often waiting saves are tried while Shoalcade is open (D184). */
    private val uploadEveryMs: Long = 60_000,
) {
    private val library = LibraryPrefs.of(DesktopStorage.context())
    private val playQueue = PlayTimeQueue(DesktopStorage.context())
    val art = BoxArt(DesktopStorage.paths.artDir)

    var session by mutableStateOf(store.load())
        private set
    var games by mutableStateOf<List<Game>>(emptyList())
        private set
    var gamesKnown by mutableStateOf(false)
        private set
    var loading by mutableStateOf(false)
        private set
    var offline by mutableStateOf(false)
        private set

    private val notices = Notices()
    private var shownMessage by mutableStateOf<String?>(null)
    /** The snackbar message. A new one waits for the one showing (phone L1); set null once shown. */
    var message: String?
        get() = shownMessage
        set(v) { shownMessage = if (v == null) notices.shown() else notices.post(v) }

    /** Bumped whenever files on disk change, so "On this PC" recomputes. */
    var diskVersion by mutableStateOf(0)
        private set
    var artVersion by mutableStateOf(0)
        private set
    /** Plugin missing or versions don't match (D41): shown as a banner; downloaded games still play. */
    var serverProblem by mutableStateOf<String?>(null)
        private set
    /** A newer Windows app than this one, from the user's plugin (S2, the phone's D165 banner). Null = nothing to offer. */
    var appUpdate by mutableStateOf<DesktopApi.WindowsApp?>(null)
        private set
    private var updateHidden: String? = null
    /** "Later": that version isn't offered again until the next start. */
    fun updateLater() { updateHidden = appUpdate?.version; appUpdate = null }
    var sort by mutableStateOf(GameSort.DEFAULT)
        private set
    var favouritesOnly by mutableStateOf(false)
        private set
    /** The phone's "⬇ On phone only" toggle (`dlOnly.<user>`), shown as "⬇ On this PC only". */
    var onPcOnly by mutableStateOf(false)
        private set
    var hasFavourites by mutableStateOf(false)
        private set
    private var serverFeatures: List<String>? = null

    /** Game id → 0..1 while downloading. */
    val progress = mutableStateMapOf<String, Float>()
    private val downloadJobs = mutableMapOf<String, Job>()

    var quickConnectCode by mutableStateOf<String?>(null)
        private set
    private var quickConnectJob: Job? = null
    private var checkJob: Job? = null

    /**
     * Bumped at every log in and log out. Work started under an older login (a game list, play time or star still on
     * its way) checks it before it touches the list, the cache file or box art, so a fetch that lands after log out
     * can never write the old user's list back. [listLock] makes "still the same login?" and the file write one step
     * against log out's delete.
     */
    @Volatile private var epoch = 0
    private val listLock = Any()
    private fun writeListIf(e: Int, list: List<Game>) = synchronized(listLock) { if (e == epoch) DesktopStorage.writeGameList(list) }

    init {
        SaveSource.install()
        AutoUpload.kick = { scope.launch { uploadWaiting() } }
        session?.let { loadPrefs(it.userId) }
        DesktopStorage.readGameList().let { games = it ?: emptyList(); gamesKnown = it != null }
        if (session != null) refresh()
        // D184: waiting saves go up while Shoalcade is open (every minute) and once more on exit ([beforeExit]).
        scope.launch { while (true) { delay(uploadEveryMs); uploadWaiting() } }
    }

    private fun loadPrefs(userId: String) {
        sort = library.sort(userId)
        favouritesOnly = library.favouritesOnly(userId)
        onPcOnly = library.onPhoneOnly(userId)
    }

    private fun client() = JellyfinClient(store.deviceId, session, timeouts = timeouts)

    // ---- Login (AppViewModel.kt lines 136-215) ----

    fun login(server: String, user: String, password: String, onError: (String) -> Unit) {
        val base = try { ServerAddress.check(server, dev) } catch (e: IOException) { onError(e.message!!); return }
        loading = true
        scope.launch {
            try {
                completeLogin(JellyfinClient(store.deviceId, timeouts = timeouts).login(base, user, password))
            } catch (e: CancellationException) {
                throw e
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
        synchronized(listLock) { epoch++ }
        session = s
        loadPrefs(s.userId)
        DesktopStorage.readGameList().let { games = it ?: emptyList(); gamesKnown = it != null }
        refresh()
    }

    fun startQuickConnect(server: String, onError: (String) -> Unit) {
        val base = try { ServerAddress.check(server, dev) } catch (e: IOException) { onError(e.message!!); return }
        quickConnectJob?.cancel()
        quickConnectJob = scope.launch {
            val c = JellyfinClient(store.deviceId, timeouts = timeouts)
            try {
                val qc = c.quickConnectStart(base)
                quickConnectCode = qc.code
                // Jellyfin codes last about 10 minutes; stop asking after that.
                val approved = withTimeoutOrNull(10 * 60_000L) {
                    while (!c.quickConnectApproved(qc)) delay(QUICK_CONNECT_POLL_MS)
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
        // L9: also end the login on the server, best effort, in the background. Local clearing never waits for it.
        val old = session
        if (!keepCache && old != null) {
            val c = JellyfinClient(store.deviceId, old, timeouts = timeouts)
            scope.launch { c.logout() }
        }
        downloadJobs.values.forEach { it.cancel() }
        store.clear()
        ServerStatus.disconnected()
        serverProblem = null
        appUpdate = null
        session = null
        games = emptyList()
        gamesKnown = false
        loading = false // an old login's refresh no longer clears it (see [epoch])
        synchronized(listLock) { epoch++; if (!keepCache) DesktopStorage.deleteGameList() }
    }

    // ---- The game list (AppViewModel.kt lines 217-269) ----

    fun refresh() {
        loading = true
        val mine = epoch
        scope.launch {
            try {
                val c = client()
                val url = session?.serverUrl ?: return@launch
                val info = try { c.info() } catch (e: HttpException) { if (e.code != 404) throw e; null }
                if (mine != epoch) return@launch // logged out (or in again) meanwhile
                val problem = if (info == null) Compat.PLUGIN_MISSING else Compat.problem(info)
                Compat.remember(url, problem)
                serverProblem = problem
                offline = false
                if (info == null || problem != null) return@launch
                hasFavourites = "favourites" in info.features
                val before = games // names for saves on this PC whose game has left the server (N7)
                val fetched = c.listGames(info.canDownload).map { it.game }
                if (mine != epoch) return@launch
                games = fetched
                gamesKnown = true
                val list = games
                withContext(Dispatchers.IO) { writeListIf(mine, list) }
                if (mine != epoch) return@launch
                serverFeatures = info.features
                saveRules = info.keepRecent to info.keepDailyDays
                maxLabelLength = info.maxLabelLength
                hasCheats = "cheats" in info.features
                flushPlayTime()
                launch {
                    // Never in the way (as the phone): an older plugin without it, or any error, just shows nothing.
                    val u = runCatching { api()?.windowsApp() }.getOrNull()?.takeIf { Compat.updateAvailable(it.version) }
                    if (mine == epoch) appUpdate = u?.takeIf { it.version != updateHidden }
                }
                launch { afterListRefresh(mine, before, list) }
                launch { if (art.fetchMissing(c, list) { mine == epoch } && mine == epoch) artVersion++ } // don't hold the spinner
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpException) {
                if (mine != epoch) return@launch // an old login's refusal must never log out the new one
                if (e.code == 401) { message = "Your login has expired. Please log in again."; logout(keepCache = true) }
                else { offline = true; message = e.message }
            } catch (e: IOException) {
                if (mine == epoch) offline = true // no network: the saved list stays, so downloaded games stay playable
            } finally {
                if (mine == epoch) loading = false
            }
        }
    }

    /**
     * One light, signed-in call (the plugin's info), when the window comes back to the front: the desktop's "return
     * to the app" (the phone also checks on a network change; Windows has no such signal here). No polling.
     */
    fun checkConnection() {
        if (session == null) { ServerStatus.disconnected(); return }
        if (loading || checkJob?.isActive == true) return
        if (ServerStatus.recentlyConnected(CHECK_GAP_MS)) return
        checkJob = scope.launch {
            val reached = try { client().info(); true }
                catch (e: CancellationException) { throw e }
                catch (e: HttpException) { JellyfinClient.answered(e.code) }
                catch (e: Exception) { false }
            if (reached && offline) refresh()
        }
    }

    fun chooseSort(s: GameSort) { sort = s; session?.let { library.setSort(it.userId, s) } }
    fun chooseFavouritesOnly(on: Boolean) { favouritesOnly = on; session?.let { library.setFavouritesOnly(it.userId, on) } }
    fun chooseOnPcOnly(on: Boolean) { onPcOnly = on; session?.let { library.setOnPhoneOnly(it.userId, on) } }

    /** [list] in the chosen order. Last played = the later of this PC's last start and Jellyfin's. */
    fun sorted(list: List<Game>): List<Game> {
        val uid = session?.userId
        return GameSort.sort(list, sort) { g -> GameSort.lastPlayed(uid?.let { library.lastLaunch(it, g.id) }, g.serverPlayed) }
    }

    /** When [g] was last played: this PC's last start or Jellyfin's, the later one (the library's "Played lately"). */
    fun lastPlayed(g: Game): Long? = GameSort.lastPlayed(session?.userId?.let { library.lastLaunch(it, g.id) }, g.serverPlayed)

    /** Ids of the games downloaded to this PC (the phone's "On phone" truth: [Storage.isDownloaded]). */
    fun onPc(): Set<String> = games.filter { Storage.isDownloaded(it) }.mapTo(HashSet()) { it.id }

    // ---- Play time (D172): this PC's queued sessions plus the server's total ----

    fun playSeconds(g: Game): Long =
        g.playSeconds + (session?.let { runCatching { playQueue.pendingSeconds(it.userId, g.id) }.getOrDefault(0L) } ?: 0L)

    fun flushPlayTime() {
        val s = session ?: return
        val features = serverFeatures ?: return
        val c = client()
        val mine = epoch
        scope.launch {
            val totals = runCatching { withContext(Dispatchers.IO) { playQueue.flush(c, s.userId, features) } }.getOrDefault(emptyMap())
            if (totals.isEmpty() || mine != epoch) return@launch
            games = games.map { g -> totals[g.id]?.let { g.copy(playSeconds = it) } ?: g }
            val list = games
            withContext(Dispatchers.IO) { writeListIf(mine, list) }
        }
    }

    // ---- Stars ----

    fun setFavourite(game: Game, on: Boolean) {
        val c = client()
        val mine = epoch
        scope.launch {
            try {
                withContext(Dispatchers.IO) { c.setFavourite(game.id, on) }
                if (mine != epoch) return@launch
                games = games.map { if (it.id == game.id) it.copy(favourite = on) else it }
                if (games.none { it.favourite }) chooseFavouritesOnly(false)
                val list = games
                withContext(Dispatchers.IO) { writeListIf(mine, list) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (mine == epoch) message = "Couldn't change the star: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    // ---- Downloads (Downloads.kt: progress, Cancel, the same messages) ----

    /** A PS2 game with no PS2 BIOS on this PC yet: the sheet says so instead of Play (plan §7). */
    fun needsBios(g: Game) = g.console == uk.benmarks.shoalcade.Console.PS2 && Ps2.installed(DesktopStorage.context(), DesktopStorage.paths.systemDir) == null

    fun canDownload(g: Game) = !offline && serverProblem == null && g.canDownload && DesktopStorage.playableHere(g.console)

    fun download(game: Game) {
        if (progress.containsKey(game.id) || session == null) return
        progress[game.id] = 0f
        val c = client()
        downloadJobs[game.id] = scope.launch {
            var lastPct = -1
            val msg = try {
                DesktopStorage.download(c, game) { p ->
                    val pct = (p * 100).toInt()
                    if (pct != lastPct) { lastPct = pct; scope.launch { if (downloadJobs.containsKey(game.id)) progress[game.id] = p } }
                }
                "${game.name} downloaded."
            } catch (e: CancellationException) {
                "${game.name} download cancelled."
            } catch (e: Exception) {
                "Download failed: ${e.message}"
            }
            downloadJobs.remove(game.id)
            progress.remove(game.id)
            diskVersion++
            message = msg
        }
    }

    fun cancelDownload(game: Game) { downloadJobs[game.id]?.cancel() }

    /** "Remove from phone (keeps save)" on the phone. Saves are never touched. */
    fun deleteDownload(game: Game) {
        Storage.deleteDownload(game)
        diskVersion++
        message = PcWords.of("${game.name} removed from the phone. Your save is kept.") // the phone's words
    }

    // ======================================================================================================
    // Saves (phase 3, docs/windows/phase3-saves.md)
    // ======================================================================================================

    private val ctx get() = DesktopStorage.context()

    /** The sync marks, in the phone's "scsync" store (the same keys SaveSync.kt's PrefsSyncStore keeps). */
    val syncStore: SyncStore = object : SyncStore {
        private val prefs = SyncRename.open(DesktopStorage.context())
        override fun get(key: String): String? = prefs.getString(key, null)
        override fun set(key: String, value: String?) { prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply() }
    }
    private val autoStore get() = AutoUpload.store(ctx)
    val waitingSaves get() = WaitingSaves(autoStore)

    var saveRules by mutableStateOf<Pair<Int, Int>?>(null)
        private set
    var maxLabelLength by mutableStateOf(JellyfinClient.DEFAULT_MAX_LABEL)
        private set
    private var hasCheats = false
    /** Games marked ⚠️ (a choice is needed) and games whose save waits to go up. */
    var conflicts by mutableStateOf<Set<String>>(emptySet())
        private set
    var waitingIds by mutableStateOf<Set<String>>(emptySet())
        private set
    var autoUploadOn by mutableStateOf(AutoUpload.on(AutoUpload.store(DesktopStorage.context())))
        private set

    private val cores by lazy { runCatching { Cores.packed { Ps2.emulator(DesktopStorage.context()) } }.getOrElse { Cores(emptyList(), Cores.thisTarget()) } }

    /** The phone's SaveSyncCore, logged in: the same wiring as SaveSync.core() (sync store, auto upload, version check). */
    fun core(): SaveSyncCore? {
        val s = session ?: return null
        val c = client()
        return SaveSyncCore(c, s.userId, syncStore, AutoUpload.later(ctx)) { Compat.ensure(c, s.serverUrl) }
    }

    fun api(): DesktopApi? = session?.let { DesktopApi(it, store.deviceId, timeouts = timeouts) }

    fun tools(): SaveTools? {
        val c = core() ?: return null
        return SaveTools(client(), api()!!, c, syncStore)
    }

    fun snapshots(): SnapshotStore? = session?.let { s -> SnapshotStore(s.userId, { if (offline) null else api() }, cores) }

    fun refreshMarks() {
        val c = core() ?: run { conflicts = emptySet(); waitingIds = emptySet(); return }
        conflicts = games.filter { c.hasConflict(it) }.mapTo(HashSet()) { it.id }
        waitingIds = waitingSaves.list(c.userId).mapTo(HashSet()) { it.game.id }
    }

    /** After each game list: the orphan notice (once per save), ⚠️ marks, waiting saves and snapshots go up. */
    private suspend fun afterListRefresh(mine: Int, before: List<Game>, list: List<Game>) {
        if (mine != epoch) return
        val c = core() ?: return
        val listed = list.map { it.id }.toSet()
        val orphans = withContext(Dispatchers.IO) {
            c.rememberGone(before, listed) // so "Not in your library" can name them later
            c.orphansToMention(listed).toList()
        }
        if (mine != epoch) return
        if (orphans.isNotEmpty()) message = orphanNotice(orphans, before.associate { it.id to it.name })
        refreshMarks()
        uploadWaiting()
        snapshots()?.let { s -> runCatching { s.sendWaiting(list) } }
    }

    /**
     * N7 / item D on the phone: saves on this PC that never went up, for games no longer in the library, are mentioned
     * once each. The phone's words, about this PC.
     */
    fun orphanNotice(ids: List<String>, names: Map<String, String>): String {
        val known = ids.mapNotNull { names[it] }
        val which = if (known.isEmpty()) "" else ": " + known.joinToString(", ") +
            (if (known.size < ids.size) " and ${ids.size - known.size} more" else "")
        return (if (ids.size == 1) "1 save on this PC is for a game no longer in your library, so it wasn't uploaded"
            else "${ids.size} saves on this PC are for games no longer in your library, so they weren't uploaded") +
            "$which. " + (if (ids.size == 1) "It's" else "They're") + " kept on this PC."
    }

    fun setAutoUpload(on: Boolean) {
        AutoUpload.setOn(autoStore, on)
        autoUploadOn = on
        if (on) scope.launch { uploadWaiting() }
    }

    private var uploadJob: Job? = null
    private var refusedUntil = 0L

    /** D174 + D184: waiting saves go up by themselves, only the safe fast path, never while a game is open. */
    suspend fun uploadWaiting() {
        if (uploadJob?.isActive == true) return
        val job = scope.launch {
            val r = uploadWaitingNow() ?: return@launch
            r.summary()?.let { message = it }
            diskVersion++
            refreshMarks()
        }
        uploadJob = job
        job.join()
    }

    /** One run of the waiting list, if it should run now. Null = nothing ran. */
    private suspend fun uploadWaitingNow(): UploadWhenOnline.Report? {
        if (playing != null || batch != null) return null
        val c = core() ?: return null
        if (!AutoUpload.on(autoStore) || offline || serverProblem != null) return null
        if (System.currentTimeMillis() < refusedUntil) return null
        if (waitingSaves.list(c.userId).isEmpty()) return null
        val r = UploadWhenOnline(c, waitingSaves) { playing != null }.run()
        // A plugin older than 0.10.0.0 refuses Windows saves (S1): try again in 5 minutes, not every minute.
        if (r.outcome == UploadWhenOnline.Outcome.PLUGIN_REFUSED) refusedUntil = System.currentTimeMillis() + 5 * 60_000
        return r
    }

    /** Shoalcade is closing (D184): waiting saves get one more go (at most [limitMs]), and the play time queue is sent. */
    suspend fun beforeExit(limitMs: Long = 20_000) {
        exiting = true
        refusedUntil = 0
        withTimeoutOrNull(limitMs) {
            uploadJob?.join()
            runCatching { uploadWaitingNow() }
            flushPlayTimeNow()
        }
    }

    /** Set while [beforeExit] runs: the window says "Uploading waiting saves before closing…". */
    var exiting by mutableStateOf(false)
        private set

    private suspend fun flushPlayTimeNow() {
        val s = session ?: return
        val features = serverFeatures ?: return
        runCatching { withContext(Dispatchers.IO) { playQueue.flush(client(), s.userId, features) } }
    }

    // ---- Library ⋮ menu (MainActivity.kt GameRow): upload, get save, the ⚠️ question ----

    private fun saveOp(g: Game, op: suspend SaveSyncCore.() -> SyncResult) {
        if (batch != null) { message = "Wait for ${batch!!.way.word} to finish first."; return }
        val c = core() ?: run { message = "Not logged in."; return }
        scope.launch {
            val r = c.op()
            val refused = SaveSource.refusedByPlugin(r)
            // A plugin older than 0.10.0.0 refuses Windows saves (S1): the save is fine and waits to go up.
            if (refused) session?.let { waitingSaves.add(it.userId, g) }
            message = PcWords.of(r.message) + if (refused) " " + SaveSource.REFUSED_NOTE else ""
            if (r is SyncResult.Conflict) conflictPrompt = ConflictAsk(g, r.server)
            diskVersion++; refreshMarks()
        }
    }

    /** "Upload save to Jellyfin": the version check; a conflict opens the question. */
    fun uploadSave(g: Game) = saveOp(g) { upload(g) }

    /** "Get save from Jellyfin" (after the dialog with both dates). */
    fun getSave(g: Game) = saveOp(g) { download(g) }

    /** The ⚠️ question: "Which save should be in use?". */
    class ConflictAsk(val game: Game, val server: SaveVersion?)
    var conflictPrompt by mutableStateOf<ConflictAsk?>(null)

    fun askConflict(g: Game) {
        val c = core() ?: return
        scope.launch {
            try { conflictPrompt = ConflictAsk(g, c.check(g).first) }
            catch (e: IOException) { message = "Couldn't check the Jellyfin save: ${e.message}" }
        }
    }

    fun resolveConflict(g: Game, keepPc: Boolean) {
        conflictPrompt = null
        saveOp(g) { if (keepPc) keepPhone(g) else keepServer(g) }
    }

    /** For the Get save dialog: Jellyfin's save in use now (null = none). */
    suspend fun serverSave(g: Game): SaveVersion? = core()?.check(g)?.first

    fun pcSaveTime(g: Game): Long? = session?.let { Storage.saveTime(it.userId, g) }

    // ---- Push all / Pull all (SyncAllPanel.kt) ----

    class BatchProgress(val way: SyncAll.Way, val index: Int, val total: Int, val game: String)
    var batch by mutableStateOf<BatchProgress?>(null)
        private set
    var batchStopping by mutableStateOf(false)
        private set
    var batchReport by mutableStateOf<SyncAll.Report?>(null)
    val batchAsks = mutableStateListOf<SyncAll.Ask>()
    val batchSettled = mutableStateMapOf<String, String>()
    var settling by mutableStateOf<String?>(null)
        private set

    fun syncAll(way: SyncAll.Way) {
        if (batch != null || settling != null) return
        if (playing != null) { message = "Close the game first: ${way.word} doesn't run while a game is open."; return }
        val c = core() ?: run { message = "Not logged in."; return }
        if (offline) { message = "You're offline, so ${way.word} didn't run. Nothing was changed."; return }
        serverProblem?.let { message = "${way.word} didn't run, nothing was changed: $it"; return }
        val list = SyncAll.candidates(games)
        if (list.isEmpty()) { message = "${way.word}: there are no downloaded games."; return }
        batchStopping = false
        batch = BatchProgress(way, 0, list.size, list[0].name)
        scope.launch {
            try {
                val report = SyncAll(c).run(way, list, stop = { batchStopping }) { i, g -> batch = BatchProgress(way, i, list.size, g.name) }
                batchAsks.clear(); batchAsks.addAll(report.asks); batchSettled.clear()
                batchReport = report
                // A failed push of a save Jellyfin hasn't got (e.g. a plugin older than 0.10.0.0 refusing Windows saves, S1) waits
                // to go up: the waiting list only ever takes the safe path, so this can never settle anything.
                for ((g, _) in report.failed) if (c.phoneHasNewChanges(g)) session?.let { waitingSaves.add(it.userId, g) }
            } finally {
                batch = null; batchStopping = false
                diskVersion++; refreshMarks()
            }
        }
    }

    fun stopBatch() { if (batch != null) batchStopping = true }

    fun settleAsk(ask: SyncAll.Ask, choice: SyncAll.Choice) {
        if (settling != null || batch != null) return
        if (choice == SyncAll.Choice.LATER) {
            batchSettled[ask.game.id] = "Left as it is: both saves are unchanged." + if (ask.conflict) " It stays marked ⚠️ in the list." else ""
            return
        }
        val c = core() ?: run { message = "Not logged in."; return }
        settling = ask.game.id
        scope.launch {
            try {
                val all = SyncAll(c)
                val r = all.settle(ask, choice)
                val next = all.askAgain(ask, r)
                val i = batchAsks.indexOfFirst { it.game.id == ask.game.id }
                if (next != null && i >= 0) batchAsks[i] = next
                else r?.let { batchSettled[ask.game.id] = PcWords.of(it.message) }
            } finally {
                settling = null; diskVersion++; refreshMarks()
            }
        }
    }

    // ---- Playing a game ----

    /** The game open now (one at a time), its phase and what it said. */
    class Playing(val game: Game, val session: PlaySession) {
        var phase by mutableStateOf(PlaySession.Phase.LOADING)
        val notes = mutableStateListOf<String>()
    }
    var playing by mutableStateOf<Playing?>(null)
        private set

    /** A question before the game starts (Jellyfin's save changed) or after it closes (the upload needs a choice). */
    sealed class PlayAsk(val game: Game) {
        /** Before: Jellyfin has a different save and this PC's changed too, or bringing it would replace a newer one. */
        class Before(game: Game, val result: SyncResult) : PlayAsk(game)
        class AfterNewer(game: Game, val result: SyncResult.NeedsConfirm) : PlayAsk(game)
        class AfterBoth(game: Game, val result: SyncResult.Conflict) : PlayAsk(game)
    }
    var playAsk by mutableStateOf<PlayAsk?>(null)
    /** The launch choice (D90): the snapshots it offers. */
    class Launch(val game: Game, val snapshots: List<SnapshotStore.Entry>, val enabled: Boolean)
    var launch by mutableStateOf<Launch?>(null)
    var checkingSave by mutableStateOf<String?>(null)
        private set

    /**
     * Play: if Jellyfin can be reached, the phone's Pull rule first (SavePlan, D99): a newer Jellyfin save is brought
     * to this PC (this PC's copy backed up first); one that needs a choice asks with both dates; offline plays what's
     * here. Then the launch choice if there are snapshots, then the game.
     */
    fun play(g: Game) {
        if (playing != null) { message = "A game is already open. Close it first."; return }
        if (checkRunning) { message = "The 3DS check is running. Let it finish first."; return }
        if (batch != null) { message = "Wait for ${batch!!.way.word} to finish first."; return }
        val c = core()
        library.recordLaunch(session?.userId ?: return, g.id)
        scope.launch {
            if (c != null && !offline && serverProblem == null) {
                checkingSave = g.id
                val r = try { withTimeoutOrNull(20_000) { c.downloadLatest(g) } } finally { checkingSave = null }
                when (r) {
                    is SyncResult.NeedsConfirm, is SyncResult.Conflict -> { playAsk = PlayAsk.Before(g, r); return@launch }
                    is SyncResult.Done -> if (r.skipped == null) message = PcWords.of(r.message)
                    else -> {}
                }
                refreshMarks()
            }
            chooseStart(g)
        }
    }

    /** The answer to [PlayAsk.Before]: [choice] KEEP_PHONE = this PC's, KEEP_SERVER = Jellyfin's, LATER = play this PC's, change nothing. */
    fun answerBefore(ask: PlayAsk.Before, choice: SyncAll.Choice) {
        playAsk = null
        val c = core() ?: return
        scope.launch {
            val server = (ask.result as? SyncResult.NeedsConfirm)?.server ?: (ask.result as? SyncResult.Conflict)?.server
            val r = when (choice) {
                SyncAll.Choice.KEEP_PHONE -> if (ask.result is SyncResult.Conflict) c.keepPhone(ask.game, SaveSyncCore.Shown(server)) else null
                SyncAll.Choice.KEEP_SERVER -> c.keepServer(ask.game, SaveSyncCore.Shown(server))
                SyncAll.Choice.LATER -> null
            }
            r?.let { message = PcWords.of(it.message) + if (SaveSource.refusedByPlugin(it)) " " + SaveSource.REFUSED_NOTE else "" }
            refreshMarks(); diskVersion++
            // A refused or failed choice changed nothing: the game still starts from this PC's save, as offline.
            chooseStart(ask.game)
        }
    }

    private suspend fun chooseStart(g: Game) {
        val snaps = snapshots() ?: return
        val (list, enabled) = runCatching { snaps.forLaunch(g) }.getOrDefault(emptyList<SnapshotStore.Entry>() to true)
        if (list.any { it.here }) launch = Launch(g, list, enabled) else start(g, null, enabled, list)
    }

    // ---- The 3DS check (docs/windows/phase6-3ds.md): advisory only, never locks (plan Q6) ----

    /** The check is running (its own player window): the corner words, else null. */
    var checkLine by mutableStateOf<String?>(null)
        private set
    /** What the last run said ("Pass. … On average 59 frames a second (98% of full speed)."). */
    var checkNote by mutableStateOf<String?>(null)
        private set
    /** Bumped when the kept result changes (the window re-reads it). */
    var checkVersion by mutableStateOf(0)
        private set
    private var n3dsJob: Job? = null

    fun checkSaved(): N3dsCheck.Saved = N3dsCheck.read(N3dsCheckPc.store(DesktopStorage.context()))
    fun checkStatus(): String = N3dsCheckPc.status(checkSaved())
    val checkRunning: Boolean get() = checkLine != null

    /** Runs the 3DS check (one player at a time: not while a game is open). The result is kept on this PC. */
    fun runCheck() {
        if (playing != null) { checkNote = "Close the game first."; return }
        if (n3dsJob?.isActive == true) return
        checkNote = null
        checkLine = "3DS check: getting ready…"
        n3dsJob = scope.launch {
            val dir = java.io.File(DesktopStorage.paths.dataDir, "n3ds/check")
            val run = N3dsCheckRun(setupFor(), dir, playerArgs = playerArgs, log = java.io.File(DesktopStorage.paths.logsDir, "player.log"))
            val out = try { run.run { line -> scope.launch { checkLine = line } } } finally { checkLine = null }
            checkNote = when (out) {
                is N3dsCheckRun.Outcome.Measured -> {
                    withContext(Dispatchers.IO) {
                        N3dsCheck.save(N3dsCheckPc.store(DesktopStorage.context()), out.result, android.os.Build.FINGERPRINT,
                            BuildConfig.VERSION_CODE.toLong(), System.currentTimeMillis())
                    }
                    checkVersion++
                    "${out.result.label}. ${N3dsCheckPc.detail(out.result)} ${N3dsCheck.speedLine(out.fps)}"
                }
                N3dsCheckRun.Outcome.Cancelled -> N3dsCheckPc.CANCELLED
                is N3dsCheckRun.Outcome.Failed -> out.why
            }
        }
    }

    // ---- The GameCube / Wii check (D222 D-7): advisory, our own homebrew scene, one player at a time ----

    /** What the last GameCube or Wii check said; bumped [checkVersion] when a result is kept. */
    var gcCheckNote by mutableStateOf<String?>(null)
        private set

    fun gcCheckSaved(c: uk.benmarks.shoalcade.Console): N3dsCheck.Saved = N3dsCheck.read(GcCheckPc.store(DesktopStorage.context(), c))

    fun runGcCheck(c: uk.benmarks.shoalcade.Console) {
        if (playing != null) { gcCheckNote = "Close the game first."; return }
        if (n3dsJob?.isActive == true || checkRunning) return
        gcCheckNote = null
        checkLine = "${GcCheckPc.name(c)} check: getting ready…"
        n3dsJob = scope.launch {
            val dir = java.io.File(DesktopStorage.paths.dataDir, "gc-check/${c.key}")
            val run = GcCheckRun(setupFor(), dir, c, playerArgs = playerArgs, log = java.io.File(DesktopStorage.paths.logsDir, "player.log"))
            val out = try { run.run { line -> scope.launch { checkLine = line } } } finally { checkLine = null }
            gcCheckNote = when (out) {
                is N3dsCheckRun.Outcome.Measured -> {
                    withContext(Dispatchers.IO) {
                        N3dsCheck.save(GcCheckPc.store(DesktopStorage.context(), c), out.result, android.os.Build.FINGERPRINT,
                            BuildConfig.VERSION_CODE.toLong(), System.currentTimeMillis())
                    }
                    checkVersion++
                    "${out.result.label}. ${GcCheckPc.detail(c, out.result)} ${GcCheckPc.speedLine(out.fps, GcCheckPc.targetFps(c))}"
                }
                N3dsCheckRun.Outcome.Cancelled -> N3dsCheckPc.CANCELLED
                is N3dsCheckRun.Outcome.Failed -> out.why
            }
        }
    }

    // ---- Link play (docs/windows/phase5-link.md) ----

    /** The game whose Link play window is open (library ⋮ → Link play). */
    var linkGame by mutableStateOf<Game?>(null)

    /** Internet link (GBA): only when the plugin has `link-relay` and Jellyfin can be reached (the phone's rule). */
    fun linkRelay(): uk.benmarks.shoalcade.RelayHttp? =
        if (serverFeatures?.contains(uk.benmarks.shoalcade.RelayWire.FEATURE) == true && !offline && session != null) client().linkRelay() else null

    /**
     * Why this game can't link now, or null. The phone's LinkDialog rule: GBA link plays on gpSP, which always writes a
     * 128 KB save, so only a save that is already 128 KB (or none yet, for a Gen 3 Pokémon) is allowed.
     */
    fun linkProblem(g: Game): String? {
        val s = session ?: return "Log in first."
        val console = g.console ?: return "Unknown console."
        if (!uk.benmarks.shoalcade.LinkRules.supports(console)) return "Link play is for GBA and DS games."
        val rom = Storage.romFile(g) ?: return "Download the game first."
        if (console != uk.benmarks.shoalcade.Console.GBA) return null
        val code = runCatching { uk.benmarks.shoalcade.LinkRules.gbaGameCode(uk.benmarks.shoalcade.LinkLobby.romHeader(rom)) }.getOrNull()
        return uk.benmarks.shoalcade.LinkRules.gbaSaveProblem(code, Storage.readSave(s.userId, g)?.size)
    }

    /** The link is made (the lobby's [PcLinkLobby.take]): the game starts linked, from its save. */
    fun startLinked(g: Game, link: PcLink) {
        linkGame = null
        if (playing != null) { link.close(); message = "A game is already open. Close it first."; return }
        session?.let { library.recordLaunch(it.userId, g.id) }
        start(g, null, link = link)
    }

    /** The launch choice's answer: null = Continue from save. [link]: a link session (no snapshots, no cheats). */
    fun start(g: Game, snapshot: SnapshotStore.Entry?, snapshotsEnabled: Boolean = true, offered: List<SnapshotStore.Entry> = emptyList(),
              link: PcLink? = null) {
        launch = null
        val s = session
        val c = core()
        val snaps = snapshots()
        if (s == null || c == null || snaps == null) { link?.close(); return }
        val setup = setupFor()
        lateinit var p: Playing
        val ps = PlaySession(g, s.userId, c, syncStore, waitingSaves, snaps, setup,
            java.io.File(DesktopStorage.paths.dataDir, "play/${s.userId}"), screenshotDir(),
            autoUpload = { AutoUpload.on(autoStore) && session != null },
            cheatSync = if (hasCheats && !offline) client().let { cl -> CheatSyncCore(cl) { Compat.ensure(cl, s.serverUrl) } } else null,
            scope = scope, onNote = { n -> scope.launch { p.notes += n; message = n } },
            link = link, dsMac = uk.benmarks.shoalcade.LinkPrefs.dsMac(DesktopStorage.context()))
        p = Playing(g, ps)
        playing = p
        scope.launch {
            val outcome = try {
                val prep = ps.prepare(snapshot, snapshotsEnabled, offered)
                prep.notes.forEach { message = it }
                val player = withContext(Dispatchers.IO) {
                    PlayerClient.launch(setup.player, playerArgs, log = java.io.File(DesktopStorage.paths.logsDir, "player.log"))
                }
                val watch = launch { while (true) { p.phase = ps.phase; delay(100) } }
                try { ps.run(player, prep) } finally { watch.cancel(); p.phase = PlaySession.Phase.ENDED }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                link?.close()
                playing = null
                message = "Couldn't start the game: ${e.message}"
                Log.w("Couldn't start ${g.id}", e)
                return@launch
            }
            session?.let { u -> withContext(Dispatchers.IO) { runCatching { playQueue.record(u.userId, g.id, outcome.playSeconds) } } }
            val close = withContext(NonCancellable) { ps.closeResult(outcome) }
            playing = null
            when (close) {
                is PlaySession.Close.Said -> message = close.text
                is PlaySession.Close.Newer -> playAsk = PlayAsk.AfterNewer(g, close.result)
                is PlaySession.Close.Both -> playAsk = PlayAsk.AfterBoth(g, close.result)
            }
            diskVersion++; refreshMarks(); flushPlayTime()
            uploadWaiting()
        }
    }

    /**
     * The cheat editor for [g] (D143, D145): on the running game when it is this one (the game and its menu follow at
     * once), else this PC's copy of the game's list. Changes go to Jellyfin when it can be reached, so the phone and the
     * browser see them too. Null: no cheats on Windows for this console (3DS, D210), not logged in, or a link session.
     */
    fun cheatEditor(g: Game): CheatEditor? {
        val console = g.console?.takeIf { CheatEditor.allowed(it) } ?: return null
        val s = session ?: return null
        playing?.takeIf { it.game.id == g.id }?.let { p -> return p.session.cheatHost?.let { CheatEditor(console, it) } }
        val sync = if (hasCheats && !offline) client().let { cl -> CheatSyncCore(cl) { Compat.ensure(cl, s.serverUrl) } } else null
        return CheatEditor(console, PcCheatHost(s.userId, g.id, scope, sync))
    }

    /** Close Game / Close Game (and Upload) from the shell's bar while a game is open. */
    fun closeGame(upload: Boolean) { playing?.session?.requestClose(upload) }

    /** The answer after Close Game (and Upload): the phone's askReplaceNewer / askConflict choices. */
    fun answerAfter(ask: PlayAsk, choice: SyncAll.Choice) {
        playAsk = null
        val c = core() ?: return
        scope.launch {
            val r = when (choice) {
                SyncAll.Choice.KEEP_PHONE -> c.keepPhone(ask.game)
                SyncAll.Choice.KEEP_SERVER -> c.keepServer(ask.game)
                SyncAll.Choice.LATER -> null
            }
            message = r?.let { PcWords.of(it.message) + if (SaveSource.refusedByPlugin(it)) " " + SaveSource.REFUSED_NOTE else "" }
                ?: if (ask is PlayAsk.AfterBoth) "Not uploaded. Your save is kept on this PC; tap ⚠️ in the library to choose."
                else "Not uploaded. Your save is kept on this PC."
            if (r != null && SaveSource.refusedByPlugin(r)) session?.let { waitingSaves.add(it.userId, ask.game) }
            diskVersion++; refreshMarks()
        }
    }

    private fun screenshotDir(): java.io.File = java.io.File(System.getProperty("user.home") ?: ".", "Pictures/${uk.benmarks.shoalcade.Screenshot.FOLDER}")

    // ---- Not in your library (B9): saves on this PC for games no longer listed; and Jellyfin's removed games ----

    suspend fun orphanSaves(): uk.benmarks.shoalcade.OrphanView {
        val c = core()
        val listed = games.map { it.id }.toSet()
        return withContext(Dispatchers.IO) { uk.benmarks.shoalcade.OrphanView.load(gamesKnown, c?.let { core -> { core.orphanSaves(listed) } }) }
    }

    fun uploadOrphan(o: uk.benmarks.shoalcade.OrphanSave, target: Game, mode: String, done: (SyncResult) -> Unit) {
        val c = core() ?: return done(SyncResult.Failed("Not logged in."))
        scope.launch { val r = c.uploadOrphan(o, target, mode); diskVersion++; refreshMarks(); done(r) }
    }

    companion object {
        private const val CHECK_GAP_MS = 5_000L
        /** The phone asks every 3 s whether the code has been approved. */
        var QUICK_CONNECT_POLL_MS = 3_000L
    }
}
