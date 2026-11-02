package uk.benmarks.shoalcade.desktop

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import uk.benmarks.shoalcade.CheatBook
import uk.benmarks.shoalcade.CheatRules
import uk.benmarks.shoalcade.CheatStore
import uk.benmarks.shoalcade.CheatSyncCore
import uk.benmarks.shoalcade.CloseSave
import uk.benmarks.shoalcade.Console
import uk.benmarks.shoalcade.Game
import uk.benmarks.shoalcade.GbClock
import uk.benmarks.shoalcade.RomCheck
import uk.benmarks.shoalcade.SAVE_NOT_LOADED
import uk.benmarks.shoalcade.SaveSyncCore
import uk.benmarks.shoalcade.SaveTry
import uk.benmarks.shoalcade.SnapCheat
import uk.benmarks.shoalcade.SnapshotGuard
import uk.benmarks.shoalcade.Storage
import uk.benmarks.shoalcade.SyncResult
import uk.benmarks.shoalcade.SyncStore
import uk.benmarks.shoalcade.WaitingSaves
import uk.benmarks.shoalcade.saveForCore
import uk.benmarks.shoalcade.formatTime
import java.io.File
import java.io.IOException

/**
 * One game, from Play to the library again: the Windows version of the phone's GameActivity save rules
 * (android/.../GameActivity.kt) around the player program (windows/player/PROTOCOL.md).
 *
 * **The player never writes the save the app keeps.** It plays on a working copy (`play\<user>\<id>.sav`, written
 * atomically by the player every 30 s, on pause and on close). Each `saveChanged` puts that copy through the phone's
 * own rule ([persist], GameActivity.persist): only a save the game itself made reaches `library\saves\` (a snapshot
 * load's old copy never does: the phone's [SnapshotGuard]), never a blank one (incl. an empty PS1 card or an untouched
 * N64 block: [Storage.isBlank]), the clock waits with it, and the upload marks ("saved after loading a snapshot",
 * "made with cheats on") are set before the write. So a crash, a half-loaded snapshot or a refused save can never
 * change the save on this PC; the last good one stands.
 *
 * Before: a backup (the phone's last 10, [Storage.backupSave]; none = saving off this session), the phone's size rule
 * for PS1/N64 ([saveForCore]), the cheats (synced with Jellyfin first when it can be reached). While playing: the
 * browser's auto upload (D59, [scheduleAutoUpload]). After: [closeResult] (Close Game / Close Game (and Upload)),
 * or "The game stopped" after a crash, with nothing uploaded.
 */
class PlaySession(
    val game: Game,
    val userId: String,
    /** The phone's save sync, logged in (works offline: calls then fail as "try later"). */
    private val sync: SaveSyncCore,
    /** The same store [sync] keeps its marks in (the phone's "scsync"). */
    private val syncStore: SyncStore,
    private val waiting: WaitingSaves,
    private val snapshots: SnapshotStore,
    private val setup: PlayerSetup,
    /** `play\<user>`: the player's working copies. */
    private val workDir: File,
    /** Where screenshots go (`Pictures\Shoalcade`). */
    private val screenshotDir: File?,
    /** The browser-style auto upload applies now (Settings switch on, logged in). Asked each time. */
    private val autoUpload: () -> Boolean,
    /** Cheat sync with Jellyfin (null = offline / no cheats on the server). */
    private val cheatSync: CheatSyncCore?,
    private val scope: CoroutineScope,
    private val onNote: (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    /** The browser's timing (play.js POLL_MS / MIN_GAP_MS): a few seconds for the save to settle, 30 s between uploads. */
    private val settleMs: Long = 3_000,
    private val minGapMs: Long = 30_000,
    /** How long a closing game gets to write its save and quit before it is stopped. */
    private val closeGraceMs: Long = 15_000,
    /**
     * Link play: the connected link (from [PcLinkLobby]). The phone's link rules (GameActivity): GBA plays on gpSP, no
     * snapshots, cheats, fast-forward, rewind or pause, and no auto upload while linked. Closed when the game ends.
     */
    val link: PcLink? = null,
    /** The phone's "different DS wireless ID" ([uk.benmarks.shoalcade.LinkPrefs.dsMac]): every DS game, as on the phone. */
    private val dsMac: String? = null,
    /** D-6 folder watcher timing: the folder save must stay unchanged this long before it goes up (tests: shorter). */
    private val folderQuietMs: Long = FolderWatcher.QUIET_MS,
    private val folderPollMs: Long = 1_000,
) {
    enum class Phase { LOADING, PLAYING, CLOSING, ENDED }
    enum class CloseMode { PLAIN, UPLOAD }

    private val console: Console = game.console ?: throw IOException("Unknown console.")
    /** D143: none in a link session; D210: none for the 3DS (Azahar's retro_cheat_set is empty). */
    private val cheatsAllowed = link == null && CheatEditor.allowed(console)
    private val guard = SnapshotGuard()
    private val lock = Any()

    @Volatile var phase = Phase.LOADING; private set
    /** Link play: the player's last `linkStatus` state (`waiting`, `active`, `lost`, …), null before one. */
    @Volatile var linkState: String? = null; private set
    /** False = nothing the game does this session reaches the save on this PC (backup failed, or the save was refused). */
    @Volatile var saving = true; private set
    @Volatile private var player: GamePlayer? = null
    @Volatile private var closeMode: CloseMode? = null
    @Volatile private var exitedCode: Int? = null
    @Volatile private var lastWriteFailed = false
    @Volatile private var cheated = false
    @Volatile private var awaitingBase = false
    @Volatile private var startSnapshot: File? = null
    private var startPrior = SnapshotGuard.Prior()
    private var startLoaded = false
    private var book = CheatBook()
    @Volatile private var snapshotsOn = true
    /** The player was asked to write the Automatic snapshot as it closes (`autoSnapshotOnClose`). */
    @Volatile private var autoOnClose = false
    @Volatile private var playSeconds = 0L
    private var autoSaved: CompletableDeferred<Unit>? = null

    // Auto upload (D59).
    private var uploadJob: Job? = null
    @Volatile private var uploading = false
    @Volatile private var uploadAgain = false
    @Volatile private var lastUpload = 0L
    /** A question came up (both changed / would replace a newer save): nothing more goes up by itself this session. */
    @Volatile var needsChoice = false; private set
    /** Jellyfin refused the upload request itself (today's plugin and `source=desktop`): the save waits; not retried this session. */
    @Volatile private var pluginRefused = false

    val workSave = File(workDir, "${game.id}.sav")
    val workClock = File(workDir, "${game.id}.clock")
    private val hasFolder = console.hasFolderSaves
    /** 3DS: the screen layout kept on this PC (D206). */
    private val n3dsDisplay by lazy { N3dsDisplay(DesktopStorage.context()) }
    /** DS: the screens kept on this PC, the same way. */
    private val dsDisplay by lazy { DsDisplay(DesktopStorage.context()) }
    /** Wii: each port's controller, kept per game on this PC. */
    private val wiiControllers by lazy { WiiControllers(DesktopStorage.context()) }
    /** Games on more than one disc: the disc each was on, kept per game on this PC (P12). */
    private val discs by lazy { DiscMemory(DesktopStorage.context()) }
    private val controlsStore by lazy { ControlsStore(DesktopStorage.context()) }
    private var useClock = false

    /** What [prepare] made ready: the `start` message, and anything the person should be told first. */
    class Prepared(val start: StartRequest, val notes: List<String>)

    /**
     * Gets everything ready, in the phone's order: game file and core present, the game file isn't junk, the backup,
     * the save for the core, cheats, the snapshot (fetched and checked against this core build). [snapshot] = the
     * launch choice (null = Continue from save). [snapshotsEnabled] = the person's server setting for this console.
     */
    suspend fun prepare(snapshot: SnapshotStore.Entry?, snapshotsEnabled: Boolean = true,
                        offered: List<SnapshotStore.Entry> = emptyList()): Prepared = withContext(Dispatchers.IO) {
        // As GameActivity: a link session never starts from a snapshot and has no snapshots or cheats.
        require(link == null || snapshot == null) { "A link session can't start from a snapshot." }
        snapshotsOn = snapshotsEnabled && link == null
        val notes = mutableListOf<String>()
        val found = Storage.romFile(game) ?: throw IOException("Game file missing. Download it again.")
        // D161 (Game3dsActivity): a .cia is an installer package Azahar's core can't open; say so instead of failing.
        if (console == Console.N3DS && found.extension.equals("cia", ignoreCase = true)) throw IOException(CIA_TEXT)
        RomCheck.problem(console, found)?.let { throw IOException(RomCheck.message(console, it)) }
        if (!DesktopStorage.playableHere(console)) throw IOException("${console.shortName} games don't play on Windows yet.")
        val core = setup.core(console, link = link != null)
            ?: throw IOException("The ${console.shortName} ${if (link != null) "link " else ""}emulator isn't installed. Install Shoalcade again.")
        if (!setup.player.isFile) throw IOException("The game player isn't installed. Install Shoalcade again.")
        // PS2 (plan §7): never started without the person's own BIOS (PCEE2 would show a black screen), and the game
        // file is <id>.<ext> so the memory card's name is the same on every PC and in both PS2 emulators.
        if (console == Console.PS2 && Ps2.installed(DesktopStorage.context(), setup.systemDir) == null) throw IOException(Ps2.BIOS_MISSING)
        val rom = DesktopStorage.ps2RomName(game, found)

        // Keep a copy of the save as it was before this session (the phone's rule, last 10). No backup = saving off.
        runCatching { Storage.backupSave(userId, game) }.onFailure {
            saving = false
            notes += PcWords.of("Saving is off this session: couldn't back up your save first (${it.message}). Your phone save is unchanged.")
        }

        workDir.mkdirs()
        var saveFile: File? = null
        var saveFolder: File? = null
        if (!hasFolder) {
            // PS1/N64: a save of the wrong size is never given to the core; it stays here (and in backups), saving off.
            val (forCore, keptBack) = saveForCore(console, Storage.readSave(userId, game))
            if (keptBack) { saving = false; notes += SAVE_NOT_LOADED }
            workSave.delete()
            if (forCore != null) AtomicFile.write(workSave, forCore)
            saveFile = workSave
            useClock = (console == Console.GB || console == Console.GBC) && GbClock.hasClock(rom)
            workClock.delete()
            if (useClock) Storage.readClock(userId, game)?.let { AtomicFile.write(workClock, it) }
        } else {
            // PSP and 3DS: the emulator writes its own save files into the memory stick (as on the phone; 3DS: Azahar
            // keeps everything under Azahar\ in it, and only Azahar\sdmc\Nintendo 3DS is the save, D158). Saving off:
            // a copy, so nothing the game does reaches the kept save.
            val stick = Storage.memstickDir(userId, game)
            saveFolder = if (saving) stick.apply { mkdirs() } else File(workDir, "${game.id}.stick").also { copy ->
                copy.deleteRecursively(); if (stick.isDirectory) stick.copyRecursively(copy) else copy.mkdirs()
            }
        }

        // Cheats (D143): Jellyfin's list first when it can be reached (a few seconds at most), else the one here.
        // D143: none in a link session. 3DS: none at all (Azahar 2126.2's retro_cheat_set is empty; the phone's
        // Game3dsActivity has cheatsAllowed = false), so nothing is synced, sent or marked.
        val noCheats = !cheatsAllowed
        PcCheatHost.handOver(userId, game.id) // review S9: the game sheet's editor stops before the list is read
        book = if (noCheats) CheatBook() else CheatStore.read(userId, game.id).first
        if (!noCheats) cheatSync?.let { cs ->
            val holder = object : CheatSyncCore.Holder { override var book: CheatBook get() = this@PlaySession.book; set(v) = setBook(v) }
            withTimeoutOrNull(5_000) { cs.sync(game.id, holder) }
        }
        var snapFile: File? = null
        if (snapshot != null) {
            snapFile = snapshots.fetch(game, snapshot) // refuses another core build's snapshot
            // From boot until the snapshot is in, nothing the game does counts as a save (the person chose the snapshot).
            startPrior = guard.begin()
            startSnapshot = snapFile
            if (snapshot.cheats.isNotEmpty()) setBook(book.fromSnapshot(snapshot.cheats.map { (n, c) -> SnapCheat(n, c) }).first)
        }
        val active = book.activeCheats()
        if (active.isNotEmpty()) markCheated()
        if (saving && !book.backedUp && active.isNotEmpty()) setBook(book.copy(backedUp = true)) // backed up above

        // The pause menu's snapshot slots: this core build's ones fetched here first, so its Load finds them.
        val slots = if (!snapshotsOn) emptyList() else SnapshotStore.SLOTS.map { slot ->
            val e = offered.firstOrNull { it.slot == slot } ?: snapshots.local(game).firstOrNull { it.slot == slot }
            val file = e?.takeIf { it.here }?.let { runCatching { if (it == snapshot) snapFile else snapshots.fetch(game, it) }.getOrNull() }
            StartRequest.SlotView(slot, e?.time?.let { formatTime(it) }, e?.picture, file, loadable = file != null,
                note = when { e == null -> null; !e.here -> OTHER_DEVICE; file == null -> "Couldn't get it from Jellyfin."; else -> null }, empty = e == null)
        }
        // The browser's rule: the Automatic snapshot as the game closes, at most one a minute.
        autoOnClose = snapshotsOn && autoGapOver()
        val mac = dsMac?.takeIf { console == Console.NDS }
        // The link's local stream: the player connects here first (PROTOCOL.md "Link play").
        val linkStart = link?.let { l ->
            val door = l.openForPlayer()
            StartRequest.LinkStart(door.port, l.isHost, door.token, l.cableMode, mac, l.peerName)
        }

        Prepared(StartRequest(
            console = console.key, core = core, rom = rom, saveFile = saveFile, clockFile = if (useClock) workClock else null,
            saveFolder = saveFolder, systemDir = setup.systemDir, statesDir = snapshots.dir(game).apply { mkdirs() },
            screenshotDir = screenshotDir, title = game.name, snapshot = snapFile,
            cheatItems = cheatItems().also { sentCheats = it },
            snapshotsAllowed = snapshotsOn, autoSnapshotOnClose = autoOnClose, canUpload = true, slots = slots,
            options = (if (mac != null) mapOf("melonds_mac_address_mode" to mac) else emptyMap()) +
                PictureSize.options(DesktopStorage.context(), console) +
                (if (console == Console.PS2) Ps2.options(DesktopStorage.context(), setup.systemDir) else emptyMap()) +
                NetworkAdapter.options(DesktopStorage.context(), console, game.id), link = linkStart,
            cheatsAllowed = if (noCheats) false else null,
            n3dsLayout = if (console == Console.N3DS) n3dsDisplay.let { StartRequest.N3dsLayoutStart(it.layout, it.flip) } else null,
            dsLayout = if (console == Console.NDS) dsDisplay.let { StartRequest.DsLayoutStart(it.layout, it.swap) } else null,
            controllers = if (console == Console.WII) wiiControllers.get(game.id) else emptyList(),
            pressureCross = if (console == Console.PS2 && Ps2.pressureCross(DesktopStorage.context())) true else null,
            controls = controlsStore.start(console.key),
        ), notes)
    }

    /**
     * [strict] (the cheat editor, review S8): a list that couldn't be written throws [IOException] and nothing changes,
     * as [PcCheatHost.put], so the editor never says "Cheat added." for a cheat that wasn't kept. Otherwise (the menu's
     * switches, sync, snapshots) it is used for this session and the person is told.
     */
    private fun setBook(b: CheatBook, strict: Boolean = false) {
        if (b == book) return
        try {
            CheatStore.write(userId, game.id, b)
        } catch (e: Exception) {
            val why = PcWords.of("Couldn't keep the cheat list on the phone: ${e.message}")
            if (strict) throw IOException(why, e)
            onNote(why)
        }
        book = b
        onCheatsChanged?.invoke(b)
    }

    /** Told about every new cheat list (the app's cheat editor, open while the game runs, follows the menu's switches). */
    @Volatile private var onCheatsChanged: ((CheatBook) -> Unit)? = null

    /** D143: cheats were on at some point this session: every save written from then on is marked. */
    private fun markCheated() {
        if (cheated) return
        cheated = true
        // PSP writes its save files itself, so the mark can't wait for a save: it's set now (the phone's rule).
        if (hasFolder) syncStore.set(SaveSyncCore.cheatsKey(userId, game.id), "1")
    }

    /**
     * Runs the game until the player has gone. Every message is handled in order (PROTOCOL.md); a player that dies
     * without `exited`, or exits non-zero, is a crash ([Outcome.crashed]).
     */
    suspend fun run(p: GamePlayer, prepared: Prepared): Outcome {
        player = p
        var ended: Int? = null
        var sentStart = false
        p.events.collect { e ->
            when (e) {
                is PlayerEvent.Ended -> ended = e.code
                is PlayerEvent.BadLine -> Log.w("player sent a line that isn't the protocol")
                is PlayerEvent.Message -> when (e.type) {
                    "hello" -> if (!sentStart) { sentStart = true; if (!p.start(prepared.start)) Log.w("player went before start") }
                    "firstFrame" -> { if (phase == Phase.LOADING) phase = Phase.PLAYING; startCelioLine(p); startFolderWatch() }
                    "linkStatus" -> linkState = e.string("state")
                    "saveRefused" -> {
                        saving = false
                        onNote(e.string("message")?.let { "$it ${PcWords.of(SAVE_NOT_LOADED)}" } ?: SAVE_NOT_LOADED)
                    }
                    "saveChanged" -> onSaveChanged()
                    "snapshotLoaded" -> onSnapshotLoaded(e.string("path"), e.string("slot"))
                    "saveFlushed" -> onSaveFlushed()
                    "snapshotSaved" -> onSnapshotSaved(e.string("slot") ?: "1", e.string("path"))
                    "playSeconds" -> playSeconds += (e.long("n") ?: 0L).coerceAtLeast(0)
                    "closing" -> {
                        if (phase != Phase.ENDED) phase = Phase.CLOSING
                        // The pause menu's Close Game / Close Game (and Upload); anything else is a plain close.
                        if (e.string("reason") == "menu") closeMode = if (e.bool("upload") == true) CloseMode.UPLOAD else CloseMode.PLAIN
                        else if (closeMode == null) closeMode = CloseMode.PLAIN
                    }
                    "exited" -> exitedCode = e.long("code")?.toInt()
                    "error" -> onError(e.string("message") ?: "", e.bool("fatal") == true)
                    "cheatToggled" -> onCheatToggled(e.string("id"), e.bool("on") == true)
                    // 3DS: the layout chosen in the menu or with Flip is kept for the next game (D206).
                    "layoutChanged" -> if (console == Console.N3DS) n3dsDisplay.keep(e.string("n3ds"), e.bool("flip"))
                        else if (console == Console.NDS) dsDisplay.keep(e.string("ds"), e.bool("swap"))
                    // Wii: a controller changed in the game's menu; the next start of this game uses it again.
                    "controllerChanged" -> if (console == Console.WII) wiiControllers.changed(game.id, e.long("port")?.toInt() ?: -1, e.string("controller"))
                    "ready" -> {
                        if (console == Console.WII) e.json.optJSONArray("controllers")?.let { Log.i("player: Wii controllers $it") }
                        // P12: a game on more than one disc goes back in with the disc it was on last time.
                        e.json.optJSONObject("discs")?.let { d ->
                            val count = d.optJSONArray("labels")?.length() ?: 0
                            val want = discs.get(game.id)
                            if (want in 1 until count && want != d.optInt("index")) p.send("disc.set", mapOf("index" to want))
                        }
                    }
                    "discChanged" -> discs.keep(game.id, e.long("index")?.toInt(), e.long("count")?.toInt())
                    // Buttons changed in the game menu's Controls: kept for every game of this console.
                    "controlsChanged" -> if (e.string("console") == console.key) controlsStore.changed(e.json)
                    "warning", "coreMessage" -> e.string("message")?.let { if (e.type == "warning") Log.i("player: $it") }
                }
            }
        }
        celioLine?.cancel()
        folderWatch?.stop()
        link?.problem?.let { onNote("Link play: " + PcWords.link(it)) } // e.g. the internet link was too slow
        link?.close() // the game has gone: the other player sees the link end (the phone's LinkHolder.end)
        val crashed = exitedCode != 0 || (ended ?: 0) != 0
        // A crash: no upload waits for its timer any more (an upload already under way finishes, it's a whole save).
        if (crashed) uploadJob?.cancel()
        // The last whole save the player wrote (atomically) goes through the same rule; never uploaded from here.
        val last = withContext(Dispatchers.IO) { persist() }
        if (last == SaveTry.NOT_WRITTEN) lastWriteFailed = true
        phase = Phase.ENDED
        if (!crashed) withContext(NonCancellable) { uploadJob?.join() } // a running upload finishes before the close upload
        return Outcome(crashed, exitedCode ?: ended, !saving, closeMode ?: CloseMode.PLAIN, guard.active, needsChoice,
            lastWriteFailed, playSeconds, pluginRefused)
    }

    /** What happened. [snapshotNoSave] = a snapshot was loaded and the game hasn't saved since (D90/D95). */
    data class Outcome(
        val crashed: Boolean, val exitCode: Int?, val savingOff: Boolean, val mode: CloseMode, val snapshotNoSave: Boolean,
        val needsChoice: Boolean, val mayMiss: Boolean, val playSeconds: Long, val pluginRefused: Boolean,
    )

    // ---- The phone's persist rule (GameActivity.kt persist) on the player's working copy ----

    /** The working copy through the phone's rule. Never throws. */
    fun persist(): SaveTry = synchronized(lock) {
        if (hasFolder) return SaveTry.NOTHING // PSP writes its own files (the phone's rule too)
        if (!saving) return SaveTry.NOTHING
        val bytes = try { if (workSave.isFile) workSave.readBytes() else null } catch (e: IOException) { return SaveTry.NOT_READ }
        if (guard.waiting) return SaveTry.NOTHING
        if (!guard.isGameSave(bytes)) return SaveTry.NOTHING
        val clock = if (useClock) runCatching { workClock.takeIf { it.isFile }?.readBytes() }.getOrNull() else null
        if (clock != null && !Storage.isBlank(clock)) runCatching {
            if (Storage.readClock(userId, game)?.contentEquals(clock) != true) Storage.writeClock(userId, game, clock)
        }.onFailure { onNote(PcWords.of("Couldn't write the game's clock to the phone: ${it.message}")) }
        if (Storage.isBlank(bytes)) return SaveTry.NOTHING
        try {
            if (Storage.readSave(userId, game)?.contentEquals(bytes) != true) {
                // Marked before the write: if the app dies in between, an unmarked older-progress save can't slip out.
                if (guard.active) syncStore.set(SaveSyncCore.afterSnapshotKey(userId, game.id), "1")
                if (cheated) syncStore.set(SaveSyncCore.cheatsKey(userId, game.id), "1")
                Storage.writeSave(userId, game, bytes!!)
                changedSinceUpload = true
            }
            if (guard.active) guard.clear() // the game's own save is on this PC: back to the normal rule
            SaveTry.KEPT
        } catch (e: Exception) {
            onNote(PcWords.of("Couldn't write your save to the phone: ${e.message}"))
            SaveTry.NOT_WRITTEN
        }
    }

    @Volatile private var changedSinceUpload = false

    // ---- Link play ----

    private var celioLine: Job? = null

    /** Celio Link: the room's own status in the game's corner (GameActivity.watchLink shows celio.line()). */
    private fun startCelioLine(p: GamePlayer) {
        val c = link?.celio ?: return
        if (celioLine != null) return
        celioLine = scope.launch {
            var last: String? = null
            while (phase != Phase.ENDED) {
                // The player's menu font has no emoji: "🔗 Linked to the PC player" → "Linked to the PC player".
                val t = PcWords.link(c.line()).removePrefix("🔗 ").removePrefix("⚠️ ")
                if (t != last) { p.send("link.note", mapOf("text" to t)); last = t }
                delay(500)
            }
        }
    }

    // ---- Folder saves: auto upload once the save folder settles (D-6) ----

    private var folderWatch: FolderWatcher? = null

    /**
     * PSP, 3DS, GameCube, Wii, PS2 write their own save files: the browser-style auto upload ([scheduleAutoUpload], its
     * 30 s gap and its rules) starts ~5 s after those files stop changing. Not while saving is off (a copy is played)
     * or linked. A change after a snapshot load is the game's own save now: marked "saved after loading a snapshot".
     */
    private fun startFolderWatch() {
        if (!hasFolder || !saving || link != null || folderWatch != null) return
        folderWatch = FolderWatcher(scope, { Storage.saveStamp(userId, game) }, onSettled = {
            if (phase == Phase.PLAYING || phase == Phase.LOADING) {
                synchronized(lock) {
                    if (guard.active) { syncStore.set(SaveSyncCore.afterSnapshotKey(userId, game.id), "1"); guard.clear() }
                }
                scheduleAutoUpload()
            }
        }, quietMs = folderQuietMs, pollMs = folderPollMs).also { it.start() }
    }

    private fun onSaveChanged() {
        changedSinceUpload = false
        val t = persist()
        if (t == SaveTry.NOT_WRITTEN) lastWriteFailed = true
        if (t == SaveTry.KEPT && changedSinceUpload && phase != Phase.ENDED) scheduleAutoUpload()
    }

    private fun onError(message: String, fatal: Boolean) {
        Log.w("player error${if (fatal) " (fatal)" else ""}: $message")
        when {
            // The launch choice's snapshot didn't load: nothing in memory changed, back to the normal rule.
            startSnapshot != null && !startLoaded && message.startsWith("Couldn't load the snapshot") -> {
                guard.cancel(startPrior)
                startSnapshot = null
                onNote("$message The game carries on from your save.")
            }
            message.startsWith("Couldn't write your save to disk") -> { lastWriteFailed = true; onNote(message) }
            fatal -> onNote(message)
        }
    }

    // ---- Snapshots (D90) ----

    private fun onSnapshotLoaded(path: String?, slotGiven: String?) {
        if (startSnapshot != null && !startLoaded) startLoaded = true // the launch choice: the guard began before boot
        else guard.begin() // loaded in the game (its menu or the quick key): from now nothing counts until the baseline
        // The save as it is just after the load is the baseline: the player writes it (if it changed) and says so.
        awaitingBase = true
        if (player?.send("save.flush") != true) awaitingBase = false
        // Exactly the cheats the snapshot was made with go on (D143).
        val slot = slotGiven ?: path?.let { Regex("""slot(\w+)\.state$""").find(it)?.groupValues?.get(1) }
        val list = slot?.let { s -> snapshots.local(game).firstOrNull { it.slot == s }?.cheats }
        if (!list.isNullOrEmpty()) {
            setBook(book.fromSnapshot(list.map { (n, c) -> SnapCheat(n, c) }).first)
            markCheated()
            sendCheats()
        }
    }

    private fun onSaveFlushed() {
        if (!awaitingBase) return
        awaitingBase = false
        val base = runCatching { workSave.takeIf { it.isFile }?.readBytes() }.getOrNull()
        synchronized(lock) { guard.setBase(base) }
    }

    private fun onSnapshotSaved(slot: String, path: String?) {
        if (slot == SnapshotStore.AUTO) autoSaved?.complete(Unit)
        if (path == null || File(path).absoluteFile != snapshots.stateFile(game, slot).absoluteFile) return // not ours to keep
        val cheats = book.activeCheats().map { it.name to it.code }
        scope.launch { val msg = withContext(NonCancellable) { snapshots.saved(game, slot, cheats) }; if (slot != SnapshotStore.AUTO) onNote(msg) }
    }

    // ---- Cheats ----

    /** The whole list as the game's menu shows it (`{id, name, code, enabled}`): the start message and `cheats.set`. */
    private fun cheatItems() = book.cheats.map { StartRequest.CheatItem(it.id, it.name, CheatRules.forCore(it.code), book.isOn(it.id)) }
    /** What the game was last given (start or `cheats.set`). */
    @Volatile private var sentCheats: List<StartRequest.CheatItem>? = null

    /**
     * Sends the cheats to the game (always a reset, then each one switched on: PROTOCOL.md `cheats.set`), as the whole
     * list with ids and names, so the pause menu's Cheats section shows exactly this PC's list (new, renamed or deleted
     * cheats, and a cheat's server id once Jellyfin has it: the menu's switches name cheats by id).
     */
    private fun sendCheats() {
        val items = cheatItems()
        sentCheats = items
        player?.send(org.json.JSONObject().put("type", "cheats.set").put("cheats", org.json.JSONArray(items.map {
            org.json.JSONObject().put("id", it.id).put("name", it.name).put("code", it.code).put("enabled", it.enabled)
        })))
    }

    private val cheatHolder = object : CheatSyncCore.Holder {
        override var book: CheatBook get() = this@PlaySession.book; set(v) = setBook(v)
    }

    /** One cheat sync at a time; afterwards, a list Jellyfin changed (or new server ids) goes into the game too. */
    private val cheatLoop by lazy {
        CheatSyncLoop(scope, cheatSync, game.id, cheatHolder) {
            if (book.activeCheats().isNotEmpty()) markCheated()
            if (phase == Phase.PLAYING && cheatItems() != sentCheats) sendCheats()
        }
    }

    /**
     * The pause menu switched a cheat (`cheatToggled`; the player has already applied it). The shell owns the list:
     * it keeps the change here, marks saves from now on "made with cheats on", and sends it to Jellyfin (CheatSync).
     */
    private fun onCheatToggled(id: String?, on: Boolean) {
        if (id == null || book.byId(id) == null) return
        setBook(book.setOn(id, on).let { if (on && saving) it.copy(backedUp = true) else it }) // backed up at the start
        sentCheats = cheatItems() // the player already has this switch
        if (book.activeCheats().isNotEmpty()) markCheated()
        cheatLoop.request()
    }

    /**
     * The app's cheat editor on this game while it runs (null: a link session or the 3DS, D210). A change is kept as
     * the menu's switches are, goes into the game at once (`cheats.set`, which also refreshes its menu) and on to Jellyfin.
     */
    val cheatHost: CheatHost? = if (!cheatsAllowed) null else object : CheatHost {
        override val book: CheatBook get() = this@PlaySession.book
        override fun put(b: CheatBook) {
            // The save was backed up as the game started (prepare); with saving off nothing reaches the save anyway.
            setBook(if (saving && b.activeCheats().isNotEmpty()) b.copy(backedUp = true) else b, strict = true)
            if (book.activeCheats().isNotEmpty()) markCheated()
            sendCheats()
        }
        override fun sync() = cheatLoop.request()
        override val online get() = cheatSync != null
        override val playing get() = phase == Phase.PLAYING
        override var onChange: ((CheatBook) -> Unit)?
            get() = onCheatsChanged
            set(v) { onCheatsChanged = v }
    }

    // ---- Auto upload (D59: the browser's rule) ----

    /**
     * A few seconds after a save the game made reached this PC, it goes up with the version check, through the phone's
     * own SavePlan rule ([SaveSyncCore.uploadLatest]). Never more than one every 30 s; never while a snapshot load is
     * settling (nothing is kept then anyway); stops for the session once a choice is needed (never settled by itself)
     * or the plugin refuses Windows saves. Link play would skip it (not in this phase).
     */
    private fun scheduleAutoUpload() {
        if (link != null) return // linked: the save goes up as the game closes, never mid-trade
        if (!autoUpload() || needsChoice || pluginRefused) return
        if (uploading) { uploadAgain = true; return }
        uploadJob?.cancel() // still waiting: wait for this newer save to settle instead
        uploadJob = scope.launch {
            delay(maxOf(settleMs, lastUpload + minGapMs - clock()))
            uploading = true
            try {
                withContext(NonCancellable) { autoUploadNow() }
            } finally {
                uploading = false
            }
            if (uploadAgain) { uploadAgain = false; if (phase != Phase.ENDED) scheduleAutoUpload() }
        }
    }

    private suspend fun autoUploadNow() {
        if (!autoUpload() || needsChoice || pluginRefused) return
        val r = sync.uploadLatest(game)
        lastUpload = clock()
        when {
            r is SyncResult.Done -> if (r.skipped == null) onNote(PcWords.of(r.message))
            r is SyncResult.Conflict -> { needsChoice = true; onNote("This save was changed somewhere else. Choose which one to keep when the game closes.") }
            r is SyncResult.NeedsConfirm -> { sync.flagForChoice(game); needsChoice = true
                onNote("Jellyfin has a newer save than this one. Choose which one to keep when the game closes.") }
            SaveSource.refusedByPlugin(r) -> { pluginRefused = true; waiting.add(userId, game); onNote(PcWords.of(r.message) + " " + SaveSource.REFUSED_NOTE) }
            r is SyncResult.Failed -> onNote(PcWords.of(r.message))
        }
    }

    // ---- Leaving the game ----

    /**
     * Close Game / Close Game (and Upload) from the shell. The Automatic snapshot is taken first (the browser's rule:
     * at most one a minute, only when snapshots are on), then the player writes the save and quits.
     */
    fun requestClose(upload: Boolean) {
        if (closeMode == null || upload) closeMode = if (upload) CloseMode.UPLOAD else CloseMode.PLAIN
        val p = player ?: return
        scope.launch(Dispatchers.IO) {
            if (!autoOnClose && snapshotsOn && phase == Phase.PLAYING && !guard.waiting && autoGapOver()) {
                val done = CompletableDeferred<Unit>().also { autoSaved = it }
                if (p.send("snapshot.save", mapOf("slot" to SnapshotStore.AUTO))) withTimeoutOrNull(5_000) { done.await() }
            }
            phase = Phase.CLOSING
            p.close(closeGraceMs)
        }
    }

    private fun autoGapOver(): Boolean {
        val t = snapshots.stateFile(game, SnapshotStore.AUTO).takeIf { it.isFile }?.lastModified() ?: return true
        return clock() - t > SnapshotStore.AUTO_GAP_MS
    }

    /** What the shell does once the game has closed: a line to show, or a question that only the person can answer. */
    sealed class Close {
        data class Said(val text: String) : Close()
        /** Would replace a newer Jellyfin save: "Replace a newer save?" with both dates. */
        data class Newer(val result: SyncResult.NeedsConfirm) : Close()
        /** Both changed (D35): "Which save should be in use?". */
        data class Both(val result: SyncResult.Conflict) : Close()
    }

    /**
     * The phone's finishClose (GameActivity.kt), plus the browser's auto upload. Crash: "The game stopped", nothing
     * uploaded. Close Game (and Upload): the SavePlan rule, asking where it must. Close Game: when auto upload is on,
     * only the safe fast path goes up (no question; anything needing a choice gets ⚠️); otherwise nothing.
     */
    suspend fun closeResult(o: Outcome): Close {
        if (o.crashed) return Close.Said(STOPPED)
        val upload = o.mode == CloseMode.UPLOAD
        if (!upload && !(autoUpload() && !o.pluginRefused)) {
            return Close.Said(PcWords.of(CloseSave.closedText(o.savingOff, o.mayMiss)) +
                if (o.needsChoice) " $CHOOSE_LATER" else "")
        }
        if (!upload && o.needsChoice) return Close.Said(PcWords.of(CloseSave.closedText(o.savingOff, o.mayMiss)) + " $CHOOSE_LATER")
        val r = withContext(NonCancellable) { sync.uploadLatest(game, o.snapshotNoSave) }
        val note = PcWords.of(CloseSave.uploadNote(o.savingOff, o.mayMiss))
        return when {
            r is SyncResult.NeedsConfirm && upload -> Close.Newer(r)
            r is SyncResult.Conflict && upload -> Close.Both(r)
            r is SyncResult.NeedsConfirm -> { sync.flagForChoice(game); Close.Said("Game closed. $note${PcWords.of(r.message.substringBefore("\n"))} $CHOOSE_LATER") }
            r is SyncResult.Conflict -> Close.Said("Game closed. $note${PcWords.of(r.message)} $CHOOSE_LATER")
            SaveSource.refusedByPlugin(r) -> { waiting.add(userId, game); Close.Said("Game closed. $note${PcWords.of(r.message)} ${SaveSource.REFUSED_NOTE}") }
            else -> Close.Said((if (upload) "" else "Game closed. ") + note + PcWords.of(r.message))
        }
    }

    companion object {
        /** After a crash (brief, plan §4.2): the last save on disk stands, nothing is uploaded. */
        const val STOPPED = "The game stopped. Your last save on this PC is kept, and nothing was uploaded."
        const val CHOOSE_LATER = "Not uploaded: tap ⚠️ in the library to choose which save to keep."
        /** A slot from another core build, in the pause menu and the launch choice. */
        const val OTHER_DEVICE = "Made on another device (a different emulator version), so it can't be loaded here."
        /** D161, the phone's words (Game3dsActivity.CIA_TEXT). */
        const val CIA_TEXT = "This game is a .cia file (an installer), which Shoalcade can't play. Convert it to .cci or .3ds " +
            "(for example with Azahar on a computer) and put that in your Jellyfin library instead."
    }
}
