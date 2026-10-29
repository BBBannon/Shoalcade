package uk.benmarks.shoalcade

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.input.InputManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.system.Os
import android.util.Log
import android.util.TypedValue
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.ui.platform.ComposeView
import androidx.activity.addCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import com.swordfish.libretrodroid.LibretroDroid
import com.swordfish.libretrodroid.Variable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.math.ceil

/**
 * Runs one game. Lives in its own Activity because LibretroDroid is a single global native instance,
 * so only one GameActivity may own the emulator at a time (see [owner]).
 */
class GameActivity : ComponentActivity() {

    private lateinit var retroView: GLRetroView
    private lateinit var pad: GamepadView
    private lateinit var game: Game
    private lateinit var console: Console
    private lateinit var userId: String
    @Volatile private var ready = false
    /** Set if the stored save failed to load: we must never overwrite it with a blank one. */
    @Volatile private var saveDisabled = false
    private var clockMode = false // Game Boy cartridge clock saved with the game (GbClock)
    private var quitting = false
    /** Set once the closing save is on the phone: nothing writes the phone save after that (a conflict choice may replace it). */
    @Volatile private var closing = false
    private val writeLock = Any()
    /** Set when this session is a link session (taken from [LinkHolder.pending]). */
    private var link: LinkConnection? = null
    /** The save given to the core at start (null = none). */
    private var loadedSave: ByteArray? = null
    /** D90: after a state load only a save the game itself writes reaches the phone save (see [SnapshotGuard]). */
    private val guard = SnapshotGuard()
    /** True while a state is being loaded: the save baseline is taken when it finishes. */
    @Volatile private var loadingState = false
    /** Launch choice "a snapshot" (D90): load the state as soon as the game is running. */
    private var startFromState = false
    private var startPrior = SnapshotGuard.Prior()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A second launch (e.g. double-tapped Play) must not create a second emulator.
        if (owner != null) { finish(); return }
        owner = this

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        console = Console.valueOf(intent.getStringExtra(EXTRA_CONSOLE)!!)
        game = Game(
            id = intent.getStringExtra(EXTRA_ID)!!,
            name = intent.getStringExtra(EXTRA_NAME) ?: "",
            console = console,
            fileName = intent.getStringExtra(EXTRA_FILE) ?: "",
            canDownload = true,
        )
        userId = intent.getStringExtra(EXTRA_USER)!!
        val rom = Storage.romFile(game)
        if (rom == null || console.core == null) {
            toast("Game file missing. Download it again.")
            finish()
            return
        }
        LibraryPrefs.of(this).recordLaunch(userId, game.id) // "Last played" sort

        if (intent.getBooleanExtra(EXTRA_LINK, false)) {
            link = LinkHolder.pending
            if (link == null) { toast("The link was lost before the game started. Try again."); finish(); return }
        }
        // From boot until the state is in, nothing the game does counts as a save (the person chose the snapshot).
        startFromState = link == null && intent.getBooleanExtra(EXTRA_SNAPSHOT, false) && Storage.stateFile(userId, game).isFile
        if (startFromState) { startPrior = guard.begin(); loadingState = true }

        // Keep a copy of the save as it was before this session, so a bad session can be undone.
        // M2: no backup, no writes: saving stays off this session so the save on the phone is never overwritten.
        runCatching { Storage.backupSave(userId, game) }.onFailure {
            saveDisabled = true
            toast("Saving is off this session: couldn't back up your save first (${it.message}). Your phone save is unchanged.")
        }
        // PS1/N64: a save of the wrong size is never given to the core (it would start fresh without saying so, and a
        // game that saves at boot would hide that). The file stays on the phone (and in backups); saving goes off.
        val (forCore, keptBack) = saveForCore(console, Storage.readSave(userId, game))
        loadedSave = forCore
        if (keptBack) {
            saveDisabled = true
            toast(SAVE_NOT_LOADED)
        }
        if (link == null) loadCheats() // D143: none in a link session

        val libDir = applicationInfo.nativeLibraryDir
        val realCore = File(libDir, if (link != null && console == Console.GBA) LinkRules.GPSP_CORE else console.core!!).absolutePath
        val coreVars = console.coreVariables.toMutableList()
        if (console == Console.NDS) {
            LinkPrefs.dsMac(this)?.let { coreVars += "melonds_mac_address_mode" to it }
            val ds = startDsLayout().let { (l, swap) -> l.variables(swap) }
            coreVars.removeAll { v -> ds.any { it.first == v.first } }
            coreVars += ds
        }
        link?.let { l ->
            if (console == Console.GBA) {
                coreVars += "gpsp_bios" to "builtin"
                // auto = Ruby/Sapphire use the cable, Emerald/FireRed/LeafGreen the wireless adapter.
                coreVars += "gpsp_serial" to if (l.cableMode) "mul_poke" else "auto"
            }
            LinkNative.reset()
            // The shim reads these when the core starts (see cpp/linkshim.c).
            Os.setenv("SC_REAL_CORE", realCore, true)
            Os.setenv("SC_LINK_ROLE", if (l.isHost) "host" else "join", true)
            Os.setenv("SC_LINK_FD", l.detachFdForNative().toString(), true)
        }

        if (console == Console.PSP) {
            // Normally already done straight after the download; this covers an app update.
            try { PspAssets.ensure(this) } catch (e: Exception) {
                toast("Couldn't set up the PSP emulator: ${e.message}"); finish(); return
            }
        }

        // Cartridge clock (Gen 2 Pokémon): load through the clock shim so the clock is saved with the game.
        clockMode = link == null && (console == Console.GB || console == Console.GBC) && GbClock.hasClock(rom)
        if (clockMode) Os.setenv("SC_REAL_CORE", realCore, true)

        val data = GLRetroViewData(this).apply {
            coreFilePath = when {
                link != null -> File(libDir, LinkRules.SHIM).absolutePath
                clockMode -> File(libDir, GbClock.SHIM).absolutePath
                else -> realCore
            }
            gameFilePath = rom.absolutePath
            systemDirectory = PspAssets.systemDir(this@GameActivity).apply { mkdirs() }.absolutePath
            // PSP writes its saves as files here (its memory stick), one folder per game and user.
            savesDirectory = (if (console.hasFolderSaves) Storage.memstickDir(userId, game) else File(filesDir, "coresaves"))
                .apply { mkdirs() }.absolutePath
            variables = coreVars.map { (k, v) -> Variable(k, v) }.toTypedArray()
            saveRAMState = when {
                console.hasFolderSaves -> null
                clockMode -> GbClock.join(loadedSave, Storage.readClock(userId, game))
                else -> loadedSave
            }
            preferLowLatencyAudio = GameSound.LOW_LATENCY // F4: low latency loses sound after a call
        }
        retroView = GLRetroView(this, data).apply { isFocusable = true; isFocusableInTouchMode = true }
        applySound() // F5: a game started with App Mute on never makes a sound
        lifecycle.addObserver(retroView)

        // Orientation (D128/D129): PSP is always landscape; the rest follow the menu's Screen choice (Turn with the phone at first).
        requestedOrientation = screenOrientation(console, display.screen)

        val dm = resources.displayMetrics
        pad = GamepadView(this, object : GamepadView.Listener {
            override fun onButton(keyCode: Int, pressed: Boolean) {
                // N64 C-buttons go as the right stick (plan-n64), never as keys.
                val c = GameButton.cButton(keyCode)
                if (c != null) touchC.press(c, pressed)?.let { (x, y) -> retroView.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_RIGHT, x, y) }
                else retroView.sendKeyEvent(if (pressed) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, keyCode)
            }
            override fun onDpad(x: Float, y: Float) =
                retroView.sendMotionEvent(GLRetroView.MOTION_SOURCE_DPAD, x, y)
            override fun onStick(x: Float, y: Float) =
                retroView.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_LEFT, x, y)
        }, spec = { w, h -> padSpec(w, h) })
        // Landscape: the pad covers the game, so a finger on the picture (DS touch screen) is passed on to it.
        pad.onHoleTouch = { retroView.onTouchEvent(it) }

        menuHandle = TextView(this).apply {
            text = MENU_LABEL
            contentDescription = "Menu"
            gravity = Gravity.CENTER
            setTextColor(android.graphics.Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            includeFontPadding = false
            isClickable = true
            isFocusable = false // controllers open the menu with Home / Back, never by moving focus onto it
            setOnClickListener { openDrawer() }
            setOnTouchListener(MenuDrag(16f * dm.density) { openDrawer() })
        }

        // One root for every layout (D128): only sizes and places change when the phone turns, so the game never restarts.
        root = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            addView(retroView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(pad, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(menuHandle, FrameLayout.LayoutParams(0, 0))
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyLayout() }
        }
        if (link != null) {
            val status = TextView(this).apply {
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundColor(0x99000000.toInt())
                textSize = 12f
                setPadding(16, 6, 16, 6)
            }
            root.addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END))
            watchLink(status)
        }
        setContentView(root)
        startWatchers()
        syncCheats() // in the background: the game never waits for the server
    }

    // --- Screen layout (D128) --------------------------------------------------------------------

    private lateinit var root: FrameLayout
    private lateinit var menuHandle: TextView
    /** The layout now on screen (null until the root has a size). */
    private var layout: GameLayout? = null
    /** DS screen layout (and swap) the core is using, and one waiting for the game to be ready. */
    private var dsApplied: Pair<DsLayout, Boolean>? = null
    private var dsPending: Pair<DsLayout, Boolean>? = null
    /** The menu's Display choices, kept on the phone (D131). */
    private val display: DisplayChoices by lazy { DisplayPrefs.of(this, console) }

    /** The pad's buttons for the layout on screen ([w] x [h] = the pad's size, which the layout already holds). */
    private fun padSpec(w: Int, h: Int): PadSpec {
        val dm = resources.displayMetrics
        val l = layout?.takeIf { it.pad.width == w && it.pad.height == h }
            ?: GameLayout.compute(console, w, h, dm.density) // only before the first layout (never drawn)
        return PadLayout.forLayout(console, l, dm.density, dm.scaledDensity)
    }

    /** DS: the layout (and swap) the core starts with, from the way the phone is facing now. */
    private fun startDsLayout(): Pair<DsLayout, Boolean> {
        val land = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        return (display.ds(land) to display.swap(land)).also { dsApplied = it }
    }

    /** Places the game, the touch buttons and the Menu handle for the root's current size. Only changes what moved. */
    private fun applyLayout() {
        val w = root.width
        val h = root.height
        if (w == 0 || h == 0) return
        val dm = resources.displayMetrics
        val land = w > h
        val ds = display.ds(land) // the menu's choice for this way up
        val l = GameLayout.compute(console, w, h, dm.density, cutout(), ds, padHidden = !autoHide.visible)
        if (l != layout) {
            val turned = layout?.landscape != l.landscape
            layout = l
            place(retroView, l.game)
            place(pad, l.pad)
            // The pad already this size: onSizeChanged won't run (e.g. DS buttons hidden, game grows), so ask again.
            if (pad.width == l.pad.width && pad.height == l.pad.height) pad.refreshSpec()
            placeMenu(PadLayout.forLayout(console, l, dm.density, dm.scaledDensity), l)
            if (turned && drawer.open) refreshDrawer() // DS choices are kept per way up
        }
        if (console == Console.NDS) setDsLayout(ds, display.swap(land))
    }

    /** Camera cut-out insets inside the root (zero where the window already keeps clear of it). */
    private fun cutout(): Box {
        val i = androidx.core.view.ViewCompat.getRootWindowInsets(root)?.getInsets(WindowInsetsCompat.Type.displayCutout())
            ?: return Box(0, 0, 0, 0)
        return Box(i.left, i.top, i.right, i.bottom)
    }

    /** Sizes and places [v] at [b] (root pixels), only if it isn't already there. */
    private fun place(v: android.view.View, b: Box) {
        val lp = v.layoutParams as FrameLayout.LayoutParams
        val at = Gravity.TOP or Gravity.START
        if (lp.width == b.width && lp.height == b.height && lp.leftMargin == b.left && lp.topMargin == b.top && lp.gravity == at) return
        lp.width = b.width; lp.height = b.height
        lp.leftMargin = b.left; lp.topMargin = b.top
        lp.gravity = at
        v.layoutParams = lp
    }

    /** The Menu handle at [PadSpec.menu] (pad pixels to root pixels). See-through where it has to sit on the game. */
    private fun placeMenu(spec: PadSpec, l: GameLayout) {
        val m = spec.menu
        val box = Box(l.pad.left + m.left.toInt(), l.pad.top + m.top.toInt(),
            l.pad.left + ceil(m.right).toInt(), l.pad.top + ceil(m.bottom).toInt())
        place(menuHandle, box)
        val overGame = spec.hole?.intersects(m) == true
        menuHandle.text = if (spec.compactMenu) "☰" else MENU_LABEL
        menuHandle.setTextSize(TypedValue.COMPLEX_UNIT_PX, box.height * if (spec.compactMenu) 0.55f else 0.38f)
        menuHandle.background = GradientDrawable().apply {
            cornerRadius = box.height * 0.25f
            setColor(android.graphics.Color.argb(if (overGame) 40 else 70, 255, 255, 255))
        }
        menuHandle.alpha = if (overGame) 0.6f else 1f
    }

    /** DS screen layout: straight into the running core, or held until the game is ready. */
    private fun setDsLayout(ds: DsLayout, swap: Boolean) {
        val want = ds to swap
        if (!ready) { dsPending = want.takeIf { it != dsApplied }; return }
        dsPending = null
        if (want == dsApplied) return
        dsApplied = want
        retroView.updateVariables(*ds.variables(swap).map { (k, v) -> Variable(k, v) }.toTypedArray())
    }

    /** Error watch, save-load check and 30 s autosave, shared by both screen layouts. */
    private fun startWatchers() {
        setUpControllers()
        val loadedSave = this.loadedSave
        lifecycleScope.launch {
            // D143: a cheat the core won't take never ends the game; it's only mentioned.
            retroView.getGLRetroErrors().first { code ->
                if (code == GLRetroView.ERROR_CHEAT) toast(CHEAT_REFUSED)
                code != GLRetroView.ERROR_CHEAT
            }.let { code ->
                toast("The emulator couldn't start this game (error $code).")
                finish()
            }
        }
        lifecycleScope.launch {
            retroView.getGLRetroEvents().filterIsInstance<GLRetroView.GLRetroEvents.FrameRendered>().first()
            // If we gave the core a real save but it reads back blank, the core rejected it
            // (e.g. wrong size). Switch saving off so the good file on disk is never overwritten.
            val now = splitSave(readSramOnEmuThread()).first
            if (saveNotLoaded(console, loadedSave, now)) {
                saveDisabled = true
                toast(SAVE_NOT_LOADED)
            }
            ready = true
            dsPending?.let { (l, swap) -> setDsLayout(l, swap) }
            applyCheats() // D143: the in-game save's cheats (a snapshot start then puts in the snapshot's)
            if (startFromState) {
                delay(500) // let the core settle before the state goes in
                startFromSnapshot()
            }
        }
        // Autosave the in-game save to the phone every 30 s while playing (guards against crashes).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    delay(30_000)
                    saveNow()
                }
            }
        }
        // System Back (and the Back gesture) opens the menu, and closes it again. Never leaves the game by itself.
        onBackPressedDispatcher.addCallback(this) { if (drawer.open) closeDrawer() else openDrawer() }
    }

    /** Shows the link status in the corner, and says once if the link drops. */
    private fun watchLink(status: TextView) {
        val peer = link?.peerName ?: "other phone"
        lifecycleScope.launch {
            var warned = false
            while (true) {
                val st = LinkNative.state()
                status.text = when (st) {
                    LinkNative.ACTIVE -> "🔗 Linked to $peer · ${LinkNative.received()}↓ ${LinkNative.sent()}↑"
                    LinkNative.WAITING -> "🔗 Starting link…"
                    LinkNative.LOST -> "⚠️ Link lost"
                    LinkNative.REFUSED -> "⚠️ Link refused by the game"
                    LinkNative.NO_SUPPORT -> "⚠️ This emulator can't link"
                    else -> "🔗 Link off"
                }
                if (!warned && st >= LinkNative.LOST) {
                    warned = true
                    toast("The link stopped. You can keep playing; save, quit and link again to retry.")
                }
                delay(1_000)
            }
        }
    }

    /**
     * Reads SRAM on the emulator's own thread without blocking: if the view is paused the request
     * simply waits, so it can never hang the app. Null if it doesn't answer within 3 s.
     */
    private suspend fun readSramOnEmuThread(): ByteArray? {
        val result = CompletableDeferred<ByteArray?>()
        retroView.queueEvent { result.complete(runCatching { retroView.serializeSRAM(true) }.getOrNull()) }
        return withTimeoutOrNull(3_000) { result.await() }
    }

    private suspend fun saveNow(): SaveTry {
        if (!ready || saveDisabled) return SaveTry.NOTHING
        val bytes = readSramOnEmuThread()
        // A state load whose baseline couldn't be read yet: take it now (never written as a save).
        if (guard.waiting) {
            if (!loadingState && bytes != null) guard.setBase(splitSave(bytes).first)
            return SaveTry.NOTHING
        }
        // PSP saves are files the emulator writes itself, so its SRAM doesn't matter.
        if (bytes == null) return if (console.hasFolderSaves) SaveTry.NOTHING else SaveTry.NOT_READ
        return withContext(Dispatchers.IO) { persist(bytes) }
    }

    /** (save, cartridge clock). The clock is only there for Game Boy games with one (see GbClock). */
    private fun splitSave(bytes: ByteArray?): Pair<ByteArray?, ByteArray?> =
        if (clockMode) GbClock.split(bytes) else bytes to null

    /**
     * Writes the save (and cartridge clock) to the phone if real and changed. Never throws.
     * D90: after a state load, nothing is written until the game itself saves ([SnapshotGuard]); the clock waits
     * too, so the phone's clock always belongs with the phone's save.
     */
    private fun persist(raw: ByteArray?): SaveTry = synchronized(writeLock) {
        if (closing) return SaveTry.NOTHING
        val (bytes, clock) = splitSave(raw)
        if (!guard.isGameSave(bytes)) return SaveTry.NOTHING
        if (clock != null && !Storage.isBlank(clock)) runCatching {
            if (Storage.readClock(userId, game)?.contentEquals(clock) != true) Storage.writeClock(userId, game, clock)
        }.onFailure { e ->
            Log.w("Shoalcade", "Couldn't write the cartridge clock for ${game.id}", e)
            runOnUiThread { toast("Couldn't write the game's clock to the phone: ${e.message}") }
        }
        if (Storage.isBlank(bytes)) return SaveTry.NOTHING
        try {
            if (Storage.readSave(userId, game)?.contentEquals(bytes) != true) {
                // Marked before the write: if the app dies in between, an unmarked older-progress save can't slip out.
                if (guard.active) SaveSync(this).markSavedAfterSnapshot(userId, game)
                if (cheated) SaveSync(this).markSavedWithCheats(userId, game) // D143: the upload says "made with cheats on"
                Storage.writeSave(userId, game, bytes!!)
            }
            if (guard.active) guard.clear() // the game's own save is on the phone: back to the normal rule
            SaveTry.KEPT
        } catch (e: Exception) {
            runOnUiThread { toast("Couldn't write your save to the phone: ${e.message}") }
            SaveTry.NOT_WRITTEN
        }
    }

    override fun onPause() {
        // The emulator is already paused here (ON_PAUSE is sent before onPause), so read SRAM directly.
        if (owner === this && ::retroView.isInitialized && ready && !saveDisabled) {
            persist(runCatching { retroView.serializeSRAM(false) }.getOrNull())
        }
        super.onPause()
    }

    override fun onDestroy() {
        if (deviceListenerOn) getSystemService(InputManager::class.java)?.unregisterInputDeviceListener(deviceListener)
        if (link != null && owner === this) LinkHolder.end()
        if (owner === this) owner = null
        super.onDestroy()
    }

    // --- Bluetooth / USB controllers (D126) -------------------------------------------------------

    private val controlPrefs by lazy { ControlPrefs.of(this) }
    private val autoHide = TouchAutoHide()
    /**
     * One translator per (player port, controller model); controllers of one model share its button map. Single-player
     * consoles: every controller plays as player 1 (port 0). N64: each controller gets its own port ([ports]).
     */
    private val translators = mutableMapOf<Pair<Int, String>, PadTranslator>()
    /** N64 players 1-4 by physical controller (plan-n64); unused on single-player consoles. */
    private val ports by lazy { PlayerPorts(console.players) }
    /** Device id → the physical-controller id [ports] knows it by (kept, since a removed device can't be looked up). */
    private val padIds = mutableMapOf<Int, String>()
    /** The touch pad's N64 C-buttons (always player 1). */
    private val touchC = CStick()
    /** Each controller's axes: their rest and latest values (G6 M3: a trigger resting at -1 isn't "moved"). */
    private val axisRest = AxisRest()
    /** Device id of the controller that last sent anything. */
    private var lastDevice = -1
    /** The controller that last sent anything (the one the Controls screen changes). */
    private var lastController: ControllerInfo? = null
    /** Rest of a touch gesture that only brought the touch buttons back (never reaches the game or the buttons). */
    private var swallowTouch = false
    private var deviceListenerOn = false

    private val deviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(id: Int) {
            val d = InputDevice.getDevice(id)
            if (isController(d)) { autoHide.controllerConnected(id); portFor(d, id); applyTouchVisibility() }
        }
        override fun onInputDeviceRemoved(id: Int) {
            autoHide.controllerDisconnected(id)
            axisRest.forget(id)
            lastController = null // the Controls screen must not show a controller that has gone
            // A controller vanishing mid-press must not leave a button held in the game.
            releaseControllers()
            padIds.remove(id)?.let { ports.disconnect(it) } // it keeps its player number for when it comes back
            applyTouchVisibility()
        }
        override fun onInputDeviceChanged(id: Int) {}
    }

    private fun setUpControllers() {
        autoHide.mode = controlPrefs.touchMode(console)
        for (id in InputDevice.getDeviceIds()) InputDevice.getDevice(id)?.takeIf { isController(it) }?.let {
            autoHide.controllerConnected(id)
            portFor(it, id)
        }
        getSystemService(InputManager::class.java)?.let {
            it.registerInputDeviceListener(deviceListener, Handler(Looper.getMainLooper()))
            deviceListenerOn = true
        }
        applyTouchVisibility()
    }

    /** A real, plugged-in or paired controller (not the phone's own keys or a virtual device). */
    private fun isController(d: InputDevice?): Boolean = isGameController(d)

    private fun applyTouchVisibility() {
        if (!::pad.isInitialized) return
        val show = autoHide.visible
        pad.setHidden(!show, when {
            show -> null
            autoHide.mode == TouchMode.HIDE -> TOUCH_OFF_HINT
            else -> TOUCH_HIDDEN_HINT
        })
        if (::root.isInitialized) applyLayout() // DS in landscape grows to the full width while the buttons are hidden
    }

    private fun infoOf(d: InputDevice?): ControllerInfo = controllerInfo(d)

    private fun translatorFor(c: ControllerInfo, port: Int) =
        translators.getOrPut(port to c.key) { PadTranslator(controlPrefs.map(console, c.key)) }

    /**
     * The core port (player) controller [d] plays as: always 0 on single-player consoles. N64: its own port, given when it
     * first connects or sends input ([PlayerPorts]); null = all four players taken, so its input is ignored.
     */
    private fun portFor(d: InputDevice?, deviceId: Int): Int? {
        if (console.players == 1) return 0
        val id = padIds.getOrPut(deviceId) {
            // The descriptor stays the same when a controller reconnects. Two identical controllers with no serial can
            // share one, so a second device with the same descriptor gets its own id for this session.
            val desc = d?.descriptor?.takeIf { it.isNotBlank() } ?: "dev:$deviceId"
            if (desc in padIds.values) "$desc#$deviceId" else desc
        }
        return ports.portOf(id) ?: ports.connect(id)
    }

    /** Lets go of everything every controller holds, on every player's port. */
    private fun releaseControllers() {
        for ((k, t) in translators) send(t.releaseAll(), k.first)
    }

    private fun send(outs: List<PadOut>, port: Int) {
        for (o in outs) when (o) {
            is PadOut.Key -> retroView.sendKeyEvent(if (o.down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, o.keyCode, port)
            is PadOut.Dpad -> retroView.sendMotionEvent(GLRetroView.MOTION_SOURCE_DPAD, o.x.toFloat(), o.y.toFloat(), port)
            is PadOut.Stick -> retroView.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_LEFT, o.x, o.y, port)
            is PadOut.CStick -> retroView.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_RIGHT, o.x, o.y, port)
            PadOut.Menu -> openDrawer() // Home / Back on the controller
        }
    }

    /** Auto: any controller input hides the touch buttons. */
    private fun controllerUsed() {
        val was = autoHide.visible
        autoHide.controllerInput()
        if (was != autoHide.visible) applyTouchVisibility()
    }

    private fun isGamepad(source: Int) = isGamepadSource(source)

    // Lint flags super.dispatchKeyEvent on ComponentActivity as restricted; overriding it is the documented way to read keys.
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!::retroView.isInitialized) return super.dispatchKeyEvent(event)
        val down = event.action == KeyEvent.ACTION_DOWN
        // Menu open: controller (and keyboard) keys move around it. The phone's own Back goes to the Back handler,
        // which closes it (taking it here too would close on the press and open again on the lift).
        if (drawer.open && (isGamepad(event.source) || (DrawerNav.handles(event.keyCode) && event.keyCode != KeyEvent.KEYCODE_BACK))) {
            navResult(nav.key(event.keyCode, down, event.repeatCount))
            return true
        }
        // Cheat screen: keys move around it (A presses, the D-pad moves); Home / Back go back.
        if (cheatsOpen) {
            if (isGamepad(event.source) && MenuKeys.isMenu(event.keyCode)) {
                if (down && event.repeatCount == 0) cheatsBackPressed()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        if (!isGamepad(event.source)) return super.dispatchKeyEvent(event)
        val info = infoOf(event.device)
        lastController = info
        lastDevice = event.deviceId
        if (controlsOpen) flow?.let { return controlsKey(it, info, event) }
        if (down && event.repeatCount == 0) controllerUsed() // a held button's repeats aren't new input (F1)
        // Every controller key is handled here (unknown ones do nothing), so an unhandled B can't fall through to
        // the system "Back" action. Home / Back always open the menu (MenuKeys), whatever the button map says.
        val port = portFor(event.device, event.deviceId)
        if (port == null) { // a 5th N64 controller: no player for it, but Home / Back still open the menu
            if (down && event.repeatCount == 0 && MenuKeys.isMenu(event.keyCode)) openDrawer()
            return true
        }
        send(translatorFor(info, port).key(event.keyCode, down, event.repeatCount), port)
        return true
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (!::retroView.isInitialized || cheatsOpen || !isGamepad(event.source) || event.action != MotionEvent.ACTION_MOVE) {
            return super.dispatchGenericMotionEvent(event) // cheat screen: the hat / stick become D-pad keys (Android does this)
        }
        val values = PadTranslator.AXES.associateWith { event.getAxisValue(it) }
        val info = infoOf(event.device)
        lastController = info
        lastDevice = event.deviceId
        val moved = axisRest.update(event.deviceId, values)
        val f = flow
        if (controlsOpen && f != null) {
            f.motion(info, values, moved)
            return true
        }
        if (drawer.open) { navResult(nav.motion(values)); return true }
        if (moved) controllerUsed()
        val port = portFor(event.device, event.deviceId) ?: return true // a 5th N64 controller: no player for it
        send(translatorFor(info, port).motion(values), port)
        return true
    }

    /** Auto: a touch while the buttons are hidden only brings them back. On DS the game's own screen still takes touches. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (swallowEdge) { // the rest of the swipe that opened the drawer: it must not land on the scrim and close it
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) swallowEdge = false
            return true
        }
        if (!::pad.isInitialized || controlsOpen || drawer.open || cheatsOpen) return super.dispatchTouchEvent(ev)
        if (edgeSwipeOpens(ev)) return true
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && !autoHide.visible) {
            val onDsScreen = console == Console.NDS && hits(retroView, ev)
            if (!onDsScreen && !hits(menuHandle, ev) && autoHide.screenTouched()) { swallowTouch = true; applyTouchVisibility() }
        }
        if (swallowTouch) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) swallowTouch = false
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun hits(v: android.view.View, ev: MotionEvent): Boolean {
        val at = IntArray(2).also { v.getLocationOnScreen(it) }
        return ev.rawX >= at[0] && ev.rawX < at[0] + v.width && ev.rawY >= at[1] && ev.rawY < at[1] + v.height
    }

    // --- Controls screen (D126) ------------------------------------------------------------------

    private var controlsView: ComposeView? = null
    /** The Controls screen's steps, shared with Settings (D132). Made on first open. */
    private var flow: ControlsFlow? = null
    private val controlsOpen get() = controlsView?.visibility == android.view.View.VISIBLE
    private val controlsBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = controlsBackPressed()
    }

    private fun openControls() {
        // Nothing stays held in the game while the screen is open.
        releaseControllers()
        val f = flow ?: ControlsFlow(ControlsState(console), controlPrefs,
            later = { ms, block -> val r = Runnable(block); root.postDelayed(r, ms); { root.removeCallbacks(r) } },
            changed = { key, map -> translators.forEach { (k, t) -> if (k.second == key) t.map = map } }).also { flow = it }
        val c = lastController
            ?: InputDevice.getDeviceIds().map { InputDevice.getDevice(it) }.firstOrNull { isController(it) }?.let { infoOf(it) }
        f.open(c)
        val view = controlsView ?: ComposeView(this).also { v ->
            v.setContent { HapticsGate {
                ControlsScreen(f.state, onPick = { f.startListening(it, axisRest.last(lastDevice)) }, onReset = { f.reset() },
                    onDone = { closeControls() })
            } }
            root.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            onBackPressedDispatcher.addCallback(this, controlsBack) // added after the menu's, so it wins while enabled
            controlsView = v
        }
        view.visibility = android.view.View.VISIBLE
        controlsBack.isEnabled = true
        setGameAccessible(false)
    }

    private fun closeControls() {
        flow?.stopListening()
        controlsView?.visibility = android.view.View.GONE
        controlsBack.isEnabled = false
        setGameAccessible(true)
    }

    /** TalkBack mustn't wander onto the game and touch buttons hidden behind the Controls screen. */
    private fun setGameAccessible(on: Boolean) {
        val mode = if (on) android.view.View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            else android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        pad.importantForAccessibility = mode
        menuHandle.importantForAccessibility = mode
        retroView.importantForAccessibility = mode
    }

    /** Back cancels listening first, then closes the screen. */
    private fun controlsBackPressed() {
        if (flow?.back() != true) closeControls()
    }

    /** Controller keys while the Controls screen is open. */
    @android.annotation.SuppressLint("RestrictedApi")
    private fun controlsKey(f: ControlsFlow, info: ControllerInfo, event: KeyEvent): Boolean =
        when (f.key(info, event.keyCode, event.action == KeyEvent.ACTION_DOWN, event.repeatCount)) {
            ControlsFlow.KeyResult.HANDLED -> true
            ControlsFlow.KeyResult.BACK -> { controlsBackPressed(); true }
            ControlsFlow.KeyResult.PASS_ON -> super.dispatchKeyEvent(event)
        }

    // --- Menu: the side drawer (D129) ------------------------------------------------------------

    private val drawer = DrawerState()
    private val nav = DrawerNav()
    private var drawerView: ComposeView? = null
    private val edgeSwipe by lazy { resources.displayMetrics.density.let { EdgeSwipe(20f * it, 40f * it) } }
    /** Rest of a touch gesture that opened the drawer from the edge (never reaches the game or the buttons). */
    private var swallowEdge = false

    private fun menuContext(): MenuContext {
        val land = root.width > root.height
        return MenuContext(
            console = console,
            // States would break a link session (the other phone wouldn't jump back with us).
            // Snapshots on/off per console comes from the server settings, shared with the browser (D38).
            states = link == null && SnapshotSetting.enabled(this, console),
            hasState = Storage.stateFile(userId, game).isFile,
            touch = autoHide.mode, screen = display.screen,
            ds = display.ds(land), dsSwap = display.swap(land), muted = muted,
            canUpload = !intent.getBooleanExtra(EXTRA_OFF_LIST, false),
            cheatsAllowed = link == null,
            cheats = cheatBook.cheats.map { MenuCheat(it.id, it.name, cheatBook.isOn(it.id)) },
        )
    }

    /** Re-reads what the drawer shows (a choice changed, or the phone turned). */
    private fun refreshDrawer(resetFocus: Boolean = false) {
        val sections = GameMenu.sections(menuContext())
        nav.setSections(sections, resetFocus)
        drawer.title = game.name
        drawer.sections = sections
        drawer.focusId = nav.focus
    }

    private fun openDrawer() {
        if (quitting || controlsOpen || cheatsOpen || drawer.open) return // Back / Home on those screens is handled there
        // Nothing stays held in the game while the menu is open.
        releaseControllers()
        cancelTouches()
        refreshDrawer(resetFocus = true)
        nav.reset(axisRest.last(lastDevice))
        val view = drawerView ?: ComposeView(this).also { v ->
            v.setContent { HapticsGate { GameDrawer(drawer, onPick = { menuPick(it) }, onClose = { closeDrawer() }) } }
            root.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            drawerView = v
        }
        view.bringToFront() // over the link status too
        drawer.show()
        view.visibility = android.view.View.VISIBLE
        setGameAccessible(false)
        pauseForMenu(true)
    }

    private fun closeDrawer() {
        if (!drawer.open) return
        drawer.hide()
        drawerView?.visibility = android.view.View.GONE
        setGameAccessible(true)
        pauseForMenu(false)
    }

    /**
     * The game stands still while the menu is open (no frames run, no sound). Saves, snapshots and Close Game still
     * work: they run on the emulator's thread, which keeps going. A link session is never paused (the other phone
     * would lose the link).
     */
    private fun pauseForMenu(on: Boolean) {
        if (link != null) return
        retroView.frameSpeed = if (on) 0 else 1
        applySound()
    }

    private val soundStore by lazy { GameSound.store(this) }
    /** F5 App Mute, the same for every game. */
    private var muted
        get() = GameSound.muted(soundStore)
        set(v) = GameSound.setMuted(soundStore, v)

    /** Sound on or off for now: off while the menu stands the game still (not in a link session), or with App Mute. */
    private fun applySound() {
        retroView.audioEnabled = GameSound.enabled(menuOpen = link == null && (drawer.open || cheatsOpen), muted = muted)
    }

    /** Lets go of any finger on the touch buttons or the game (the menu is taking over). */
    private fun cancelTouches() {
        val now = android.os.SystemClock.uptimeMillis()
        val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        root.dispatchTouchEvent(cancel)
        cancel.recycle()
    }

    private fun navResult(r: DrawerNav.Result) {
        when (r) {
            DrawerNav.Result.None -> {}
            DrawerNav.Result.Moved -> { drawer.focusId = nav.focus; drawer.showFocus = true }
            is DrawerNav.Result.Select -> { drawer.showFocus = true; menuPick(r.id) }
            DrawerNav.Result.Close -> closeDrawer()
        }
    }

    /** A drawer row was picked (touch, TalkBack or controller). Actions close the drawer; choices keep it open. */
    private fun menuPick(id: String) {
        when (id) {
            GameMenu.ID_RESUME -> closeDrawer()
            GameMenu.ID_CLOSE -> { closeDrawer(); closeGame(upload = false) }
            GameMenu.ID_CLOSE_UPLOAD -> { closeDrawer(); closeGame(upload = true) }
            GameMenu.ID_SAVE_SNAPSHOT -> { closeDrawer(); saveState() }
            GameMenu.ID_LOAD_SNAPSHOT -> { closeDrawer(); confirmLoadState() }
            GameMenu.ID_CONTROLS -> { closeDrawer(); openControls() }
            GameMenu.ID_DS_SWAP -> {
                val land = root.width > root.height
                display.setSwap(land, !display.swap(land))
                applyLayout()
            }
            GameMenu.ID_MUTE -> { muted = !muted; applySound() }
            GameMenu.ID_ADD_CHEAT -> { closeDrawer(); openCheats(list = false) }
            GameMenu.ID_EDIT_CHEATS -> { closeDrawer(); openCheats(list = true) }
            else -> {
                GameMenu.cheatOf(id)?.let { toggleCheat(it) }
                GameMenu.touchOf(id)?.let { m ->
                    autoHide.mode = m
                    controlPrefs.setTouchMode(console, m) // per console (D132), the same value Settings shows
                    applyTouchVisibility()
                }
                GameMenu.screenOf(id)?.let { m ->
                    display.screen = m
                    requestedOrientation = screenOrientation(console, m)
                }
                GameMenu.dsOf(id)?.let { l ->
                    display.setDs(root.width > root.height, l)
                    applyLayout()
                }
            }
        }
        if (drawer.open) refreshDrawer()
    }

    /**
     * Edge swipe (D129): a finger landing near the left edge, not on the D-pad or the game, that moves right opens the
     * drawer. Until then the touch goes on as normal; once it opens, the rest of the gesture is eaten.
     */
    private fun edgeSwipeOpens(ev: MotionEvent): Boolean {
        val at = IntArray(2).also { root.getLocationOnScreen(it) }
        val x = ev.rawX - at[0]
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val l = layout
                val onDpad = l != null && pad.visibility == android.view.View.VISIBLE && autoHide.visible &&
                    padSpec(l.pad.width, l.pad.height).dpadZone.let { z ->
                        val px = x - l.pad.left
                        val py = ev.rawY - at[1] - l.pad.top
                        px >= z.left && px <= z.right && py >= z.top && py <= z.bottom
                    }
                edgeSwipe.down(x, blocked = onDpad || hits(retroView, ev))
            }
            MotionEvent.ACTION_MOVE -> if (edgeSwipe.move(x)) {
                openDrawer() // lets go of whatever the finger was pressing first
                swallowEdge = true
                swallowTouch = false // swallowEdge takes over the rest of this gesture
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> edgeSwipe.end()
        }
        return false
    }

    // --- Save states: a snapshot of the whole game, kept on the phone only (see decisions D21) ----

    private fun saveState() {
        if (!ready) { toast("The game is still starting."); return }
        lifecycleScope.launch {
            val result = CompletableDeferred<ByteArray?>()
            retroView.queueEvent { result.complete(runCatching { retroView.serializeState(true) }.getOrNull()) }
            val bytes = withTimeoutOrNull(5_000) { result.await() }
            if (bytes == null || bytes.isEmpty()) { toast("Couldn't make a snapshot of this game."); return@launch }
            val picture = takePicture()
            // D143: the snapshot remembers which cheats were on.
            val cheats = cheatBook.snapshotCheats().takeIf { it.isNotEmpty() }?.let(CheatBook::snapToJson)
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    Storage.writeState(userId, game, bytes, cheats)
                    // The picture is optional; an old one is removed so it never shows beside a newer state.
                    runCatching { Storage.writeStateImage(userId, game, picture) }
                }.isSuccess
            }
            toast(if (ok) "Snapshot saved." else "Couldn't write the snapshot to the phone.")
        }
    }

    /** A small PNG of the game screen for the launch choice (D90), or null if it can't be taken. */
    private suspend fun takePicture(): ByteArray? {
        val w = retroView.width
        val h = retroView.height
        if (w <= 0 || h <= 0) return null
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val done = CompletableDeferred<Boolean>()
        runCatching {
            PixelCopy.request(retroView, bmp, { r -> done.complete(r == PixelCopy.SUCCESS) }, Handler(Looper.getMainLooper()))
        }.onFailure { return null }
        if (withTimeoutOrNull(2_000) { done.await() } != true) return null
        return withContext(Dispatchers.Default) {
            val scale = minOf(1f, 320f / w)
            val small = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), true) else bmp
            java.io.ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        }
    }

    private fun confirmLoadState() {
        val time = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
            .format(java.util.Date(Storage.stateFile(userId, game).lastModified()))
        AlertDialog.Builder(this)
            .setTitle("Load snapshot?")
            .setMessage("Goes back to $time. Play since then is lost unless you saved in the game. " +
                "Your in-game save is backed up first.")
            .setPositiveButton("Load") { _, _ -> loadState() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadState() {
        if (!ready) return
        lifecycleScope.launch {
            // A state also rolls back the in-game save, so keep a copy of it first.
            // No backup, no load: the save is never rolled back without a copy (honesty audit, GameActivity:411).
            // The latest in-game save must be on the phone (and so in the backup) first, or loading would lose it.
            if (saveNow().failed && saveNow().failed) {
                toast("Couldn't get your latest in-game save onto the phone, so the snapshot wasn't loaded. Try again in a moment.")
                return@launch
            }
            val backedUp = withContext(Dispatchers.IO) { runCatching { Storage.backupSave(userId, game) } }
            if (backedUp.isFailure) {
                toast("Couldn't back up your in-game save (${backedUp.exceptionOrNull()?.message}), so the snapshot wasn't loaded. " +
                    "Loading it would roll your save back.")
                return@launch
            }
            val bytes = withContext(Dispatchers.IO) { Storage.readState(userId, game) }
                ?: run { toast("No snapshot found."); return@launch }
            // D90: from here on only a save the game itself writes reaches the phone save.
            val prior = guard.begin()
            loadingState = true
            val ok = applyState(bytes, prior)
            toast(if (ok) "Snapshot loaded. Your save only changes when you save in the game."
                else "That snapshot couldn't be loaded (maybe from a different emulator version).")
            if (ok) snapshotCheats()
        }
    }

    /**
     * Loads [bytes] into the core (the guard is already waiting), then takes the save baseline. If the state didn't
     * load, nothing in memory changed, so the guard goes back to exactly how it was ([prior]).
     */
    private suspend fun applyState(bytes: ByteArray, prior: SnapshotGuard.Prior): Boolean {
        val result = CompletableDeferred<Boolean>()
        retroView.queueEvent { result.complete(runCatching { retroView.unserializeState(bytes, true) }.getOrDefault(false)) }
        val ok = withTimeoutOrNull(5_000) { result.await() } == true
        if (ok) {
            delay(200) // a few frames, so the core has finished the load before its save is read
            val now = readSramOnEmuThread()
            loadingState = false
            if (now != null) guard.setBase(splitSave(now).first) // else saveNow() takes it on its next run
        } else {
            loadingState = false
            guard.cancel(prior)
        }
        return ok
    }

    /** Launch choice "a snapshot" (D90): the game booted on its save; now the state goes in. */
    private suspend fun startFromSnapshot() {
        val bytes = withContext(Dispatchers.IO) { Storage.readState(userId, game) }
        val ok = bytes != null && applyState(bytes, startPrior)
        if (bytes == null) { loadingState = false; guard.cancel(startPrior) } // back to the normal rule
        toast(if (ok) "Started from the snapshot. Your save only changes when you save in the game."
            else "Couldn't load the snapshot, so the game started from your save.")
        if (ok) snapshotCheats()
    }

    // --- Cheats (D143): a list per game, the ticked ones applied to the core, kept in step with Jellyfin ----------

    /** The game's cheats on the phone. Main thread only: every change goes through [setBook]. */
    private var cheatBook = CheatBook()
    /** Cheats were on at some point this session: every save written from then on is marked (switching off doesn't undo them). */
    @Volatile private var cheated = false
    private var cheatSync: CheatSyncCore? = null
    private var cheatSyncing = false
    private var cheatSyncAgain = false
    private val cheatEditor = CheatEditorState()
    private var cheatView: ComposeView? = null
    private val cheatsOpen get() = cheatView?.visibility == android.view.View.VISIBLE
    private val cheatsBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = cheatsBackPressed()
    }
    private val cheatHolder = object : CheatSyncCore.Holder {
        override var book: CheatBook
            get() = cheatBook
            set(v) = setBook(v)
    }

    private fun loadCheats() {
        val (book, damaged) = CheatStore.read(userId, game.id)
        cheatBook = book
        if (damaged) toast("The cheat list on the phone couldn't be read, so it was kept aside and a new list started.")
    }

    /** Keeps [b] (on the phone straight away, so nothing is lost offline) and shows it wherever it's on screen. */
    private fun setBook(b: CheatBook) {
        if (b == cheatBook) return
        cheatBook = b
        runCatching { CheatStore.write(userId, game.id, b) }
            .onFailure { toast("Couldn't keep the cheat list on the phone: ${it.message}") }
        cheatEditor.cheats = b.cheats
        if (drawer.open) refreshDrawer()
    }

    /**
     * Saves are precious: before cheats are first switched on for this game, the save is backed up (the server also
     * pins "Before cheats" on the first upload made with them). False if that couldn't be done.
     */
    private fun ensureCheatBackup(): Boolean {
        if (cheatBook.backedUp || saveDisabled) return true // saving off: the phone save can't be changed this session
        return runCatching { Storage.backupSave(userId, game) }.fold(
            { setBook(cheatBook.copy(backedUp = true)); true },
            { toast("Couldn't back up your save first (${it.message}), so no cheat was switched on."); false })
    }

    /**
     * Puts the ticked cheats into the core: always a reset, then each one from index 0, on the emulator's thread
     * (mGBA ignores switching a single cheat off, so the whole list is sent every time).
     */
    private fun applyCheats() {
        if (link != null) return
        val active = cheatBook.activeCheats()
        if (active.isNotEmpty() && !cheated) {
            cheated = true
            // PSP writes its save files itself, so the mark can't wait for a save: it's set now.
            if (console.hasFolderSaves) SaveSync(this).markSavedWithCheats(userId, game)
        }
        if (!ready) return // applied when the game is ready
        val ops = CheatApply.ops(active)
        retroView.queueEvent {
            var refused = 0
            for (op in ops) runCatching {
                when (op) {
                    CheatApply.Op.Reset -> LibretroDroid.resetCheat()
                    is CheatApply.Op.Set -> LibretroDroid.setCheat(op.index, true, op.code)
                }
            }.onFailure { refused++ }
            if (refused > 0) runOnUiThread { toast(CHEAT_REFUSED) }
        }
    }

    private fun toggleCheat(id: String) {
        val on = !cheatBook.isOn(id)
        if (on && !ensureCheatBackup()) return
        setBook(cheatBook.setOn(id, on))
        applyCheats()
        syncCheats()
    }

    /** After a snapshot loads: exactly the cheats it was made with go on (any missing are added to the list). */
    private suspend fun snapshotCheats() {
        val list = CheatBook.snapFromJson(withContext(Dispatchers.IO) { Storage.readStateCheats(userId, game) })
        val (b, added) = cheatBook.fromSnapshot(list)
        if (list.isNotEmpty()) ensureCheatBackup() // the save was already backed up before the load; this records it
        setBook(b.copy(backedUp = cheatBook.backedUp))
        applyCheats()
        if (cheatBook.pending) syncCheats()
        if (list.isNotEmpty()) toast(GameMenu.cheatsOn(list.size) + " from the snapshot" +
            (if (added > 0) ", added to this game's list." else "."))
    }

    /**
     * Sends phone changes to Jellyfin and takes its list (D143). Never blocks the game; offline or an older server
     * leaves everything on the phone, tried again next time. One at a time; a change during a run starts another.
     */
    private fun syncCheats() {
        if (link != null) return
        if (cheatSyncing) { cheatSyncAgain = true; return }
        cheatSyncing = true
        lifecycleScope.launch {
            try {
                val core = cheatSync ?: withContext(Dispatchers.IO) {
                    val store = SessionStore(this@GameActivity)
                    store.load()?.takeIf { it.userId == userId }?.let { s ->
                        val c = JellyfinClient(store.deviceId, s)
                        CheatSyncCore(c) { Compat.ensure(c, s.serverUrl) }
                    }
                }?.also { cheatSync = it } ?: return@launch
                var rounds = 0
                do {
                    cheatSyncAgain = false
                    val before = cheatBook.activeCheats()
                    val r = core.sync(game.id, cheatHolder)
                    if (r is CheatSyncCore.Result.Failed) Log.i("Shoalcade", "Cheats not synced for now: ${r.error.message}")
                    val now = cheatBook.activeCheats()
                    if (now.map { it.code } != before.map { it.code }) { // a phone cheat getting its server id changes nothing here
                        // Changed on Jellyfin (e.g. in the browser player) for this save.
                        if (now.isNotEmpty()) ensureCheatBackup()
                        applyCheats()
                        toast(GameMenu.cheatsOn(now.size) + " (from Jellyfin).")
                    }
                } while (cheatSyncAgain && r is CheatSyncCore.Result.Done && ++rounds < 5)
            } finally {
                cheatSyncing = false
            }
        }
    }

    private fun openCheats(list: Boolean) {
        if (link != null) return
        releaseControllers()
        cheatEditor.cheats = cheatBook.cheats
        if (list) cheatEditor.openList() else cheatEditor.openAdd()
        val view = cheatView ?: ComposeView(this).also { v ->
            v.setContent { HapticsGate {
                CheatEditor(cheatEditor, console, onSave = { id, name, code -> saveCheat(id, name, code) },
                    onDelete = { deleteCheat(it) }, onClose = { closeCheats() })
            } }
            root.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            onBackPressedDispatcher.addCallback(this, cheatsBack) // added after the menu's, so it wins while enabled
            cheatView = v
        }
        view.bringToFront()
        view.visibility = android.view.View.VISIBLE
        cheatsBack.isEnabled = true
        setGameAccessible(false)
        retroView.frameSpeed = 0 // the game stands still while the screen is open, as with the menu
        applySound()
    }

    private fun closeCheats() {
        val v = cheatView ?: return
        WindowCompat.getInsetsController(window, v).hide(WindowInsetsCompat.Type.ime())
        v.visibility = android.view.View.GONE
        cheatsBack.isEnabled = false
        setGameAccessible(true)
        retroView.frameSpeed = 1
        applySound()
    }

    private fun cheatsBackPressed() {
        if (!cheatEditor.back()) closeCheats()
    }

    /** The cheat screen's Add / Save. A plain-English problem, or null when done. */
    private fun saveCheat(id: String?, name: String, typed: String): String? {
        val code = CheatRules.normalise(console, typed) // N64: a pasted 8XXXXXXXYYYY gets its space (plan-n64)
        CheatRules.problem(name, code, console)?.let { return it }
        if (id == null) {
            val (b, c) = cheatBook.add(name, code) ?: return "That cheat can't be used."
            val known = b == cheatBook
            setBook(b)
            val on = ensureCheatBackup()
            if (on) setBook(cheatBook.setOn(c.id, true))
            toast(when {
                known && on -> "You already have that code as \"${c.name}\". It's switched on."
                known -> "You already have that code as \"${c.name}\"."
                on -> "Cheat added and switched on."
                else -> "Cheat added, but not switched on."
            })
        } else {
            setBook(cheatBook.edit(id, name, code) ?: return "That cheat isn't in the list any more.")
            toast("Cheat saved.")
        }
        applyCheats()
        syncCheats()
        if (!cheatEditor.back()) closeCheats()
        return null
    }

    private fun deleteCheat(id: String) {
        setBook(cheatBook.delete(id))
        applyCheats()
        syncCheats()
        toast("Cheat deleted.")
        if (!cheatEditor.back()) closeCheats()
    }

    /**
     * Item 5. "Close Game": the latest in-game save is written to the phone (as always) and nothing is uploaded.
     * "Close Game (and Upload)": then uploads it under the newer-save rule ([SavePlan]): replacing a newer Jellyfin
     * save asks first with both dates; both changed is a conflict and asks (D35). D90/D95: after a snapshot load with
     * no in-game save since, nothing from the snapshot is uploaded.
     * M1: if the latest save can't be put on the phone (tried twice), the game stays open and the person is told.
     */
    private fun closeGame(upload: Boolean) {
        if (quitting) return
        quitting = true
        lifecycleScope.launch {
            var tried = saveNow()
            if (tried.failed) tried = saveNow() // one more try (e.g. the emulator was slow to answer)
            if (tried.failed) warnNotKept(upload, tried) else finishClose(upload, mayMiss = false)
        }
    }

    /** M1: never close silently over a save that didn't reach the phone. */
    private fun warnNotKept(upload: Boolean, tried: SaveTry) {
        AlertDialog.Builder(this)
            .setTitle(CloseSave.WARNING_TITLE)
            .setMessage(CloseSave.warning(tried))
            .setCancelable(false)
            .setPositiveButton(CloseSave.KEEP_PLAYING) { _, _ -> quitting = false }
            .setNegativeButton(CloseSave.CLOSE_ANYWAY) { _, _ -> lifecycleScope.launch { finishClose(upload, mayMiss = true) } }
            .show()
    }

    /** [mayMiss] = the latest save may not be on the phone (the person chose "Close anyway"). */
    private suspend fun finishClose(upload: Boolean, mayMiss: Boolean) {
        val snapshotNoSave = guard.active // still waiting for the game's own save after a snapshot load
        if (!upload) {
            // After "Close anyway" the pause write still gets one last direct try at the save (nothing replaces it here).
            if (!mayMiss) closing = true
            toast(CloseSave.closedText(saveDisabled, mayMiss)); finish(); return
        }
        closing = true
        val sync = SaveSync(this@GameActivity)
        toast("Uploading your save to Jellyfin…") // F3: never a silent wait; a server that's off fails within seconds
        when (val r = sync.uploadLatest(game, snapshotNoSave)) {
            is SyncResult.NeedsConfirm -> askReplaceNewer(sync, r)
            is SyncResult.Conflict -> askConflict(sync, r)
            else -> { toast(CloseSave.uploadNote(saveDisabled, mayMiss) + r.message); finish() }
        }
    }

    /** Replacing a newer Jellyfin save: both dates shown; Jellyfin's is kept in its history (and the phone's backups) first. */
    private fun askReplaceNewer(sync: SaveSync, r: SyncResult.NeedsConfirm) {
        AlertDialog.Builder(this)
            .setTitle("Replace a newer save?")
            .setMessage(r.message)
            .setCancelable(false)
            .setPositiveButton("Upload anyway") { _, _ -> finishWith { sync.keepPhone(game) } }
            .setNegativeButton("Don't upload") { _, _ -> toast("Not uploaded. Your save is kept on the phone."); finish() }
            .show()
    }

    /** Both changed (D35): same choices as the library's question, never settled silently. The other copy is always kept. */
    private fun askConflict(sync: SaveSync, r: SyncResult.Conflict) {
        val (phone, jf) = SavePlan.dateLines(Storage.saveTime(userId, game), r.server)
        AlertDialog.Builder(this)
            .setTitle("Which save should be in use?")
            .setMessage("${game.name} has a different save on this phone and on Jellyfin (maybe from the browser or another phone).\n\n" +
                "$phone\n$jf\n\nNothing is lost either way: the other save is kept in Jellyfin's history and in the phone's backups.")
            .setCancelable(false)
            .setPositiveButton("Keep the phone's") { _, _ -> finishWith { sync.keepPhone(game) } }
            .setNeutralButton("Decide later") { _, _ ->
                toast("Not uploaded. Your save is kept on the phone; tap ⚠️ in the library to choose."); finish()
            }
            .apply { if (r.server != null) setNegativeButton("Keep Jellyfin's") { _, _ -> finishWith { sync.keepServer(game) } } }
            .show()
    }

    private fun finishWith(op: suspend () -> SyncResult) {
        lifecycleScope.launch { toast(op().message); finish() }
    }

    private fun toast(msg: String) = Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()

    companion object {
        private const val EXTRA_ID = "id"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_CONSOLE = "console"
        private const val EXTRA_FILE = "file"
        private const val EXTRA_USER = "user"
        private const val EXTRA_LINK = "link"
        private const val EXTRA_SNAPSHOT = "snapshot"
        private const val EXTRA_OFF_LIST = "offList"
        private const val TOUCH_HIDDEN_HINT = "Touch buttons hidden. Tap here to show them."
        private const val MENU_LABEL = "☰ Menu"
        private const val TOUCH_OFF_HINT = "Touch buttons off. Change it in the menu."
        private const val CHEAT_REFUSED = "The emulator didn't take a cheat. Check its code for this game."

        /** The one activity currently allowed to own the native emulator. Main-thread only. */
        private var owner: GameActivity? = null

        /** True while a game is open (Push all / Pull all wait for it to close). Main-thread only. */
        val running: Boolean get() = owner != null

        /**
         * [snapshot] = the launch choice (D90): start from the phone's snapshot instead of straight from the save.
         * [offList] (F9): the game is no longer in the library, so the menu has no "Close Game (and Upload)".
         */
        fun intent(ctx: Context, game: Game, userId: String, link: Boolean = false, snapshot: Boolean = false, offList: Boolean = false) =
            Intent(ctx, GameActivity::class.java)
            .putExtra(EXTRA_OFF_LIST, offList)
            .putExtra(EXTRA_LINK, link)
            .putExtra(EXTRA_SNAPSHOT, snapshot)
            .putExtra(EXTRA_ID, game.id)
            .putExtra(EXTRA_NAME, game.name)
            .putExtra(EXTRA_CONSOLE, game.console!!.name)
            .putExtra(EXTRA_FILE, game.fileName)
            .putExtra(EXTRA_USER, userId)
    }
}

/**
 * The core was given a real save but reads back blank, so it rejected it (e.g. wrong size; for PS1, a card it couldn't
 * read is swapped for its own empty one, which [Storage.isBlank] counts as blank). Saving then goes off for the session.
 */
internal fun saveNotLoaded(console: Console, loaded: ByteArray?, now: ByteArray?): Boolean =
    !console.hasFolderSaves && !Storage.isBlank(loaded) && Storage.isBlank(now)

internal const val SAVE_NOT_LOADED = "Your save couldn't be loaded, so saving is off this session to protect it."

/**
 * What the core is given at start (plan-n64 final RC fix): (save for the core, a real save was kept back). On consoles
 * with one exact save size ([Console.exactSaveSize]: PS1, N64) a save of any other size is never given to the core;
 * if it held anything (not blank) it counts as kept back, so the caller turns saving off and says so. Every other
 * console, and a right-size save, goes in unchanged.
 */
internal fun saveForCore(console: Console, save: ByteArray?): Pair<ByteArray?, Boolean> {
    val exact = console.exactSaveSize ?: return save to false
    if (save == null || save.size == exact) return save to false
    return null to !Storage.isBlank(save)
}

/**
 * Menu handle touches: dragging right by [slop] px opens the menu straight away (and the lift then does nothing);
 * anything else is left to the view's own click handling (tap = menu, also what TalkBack uses).
 */
internal class MenuDrag(private val slop: Float, private val open: () -> Unit) : android.view.View.OnTouchListener {
    private var downX = 0f
    private var opened = false

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: android.view.View, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.rawX; opened = false }
            MotionEvent.ACTION_MOVE -> if (!opened && e.rawX - downX >= slop) {
                opened = true
                v.isPressed = false
                open()
            }
        }
        return opened // once a drag has opened the menu, the rest of the gesture (and its lift) is eaten: no second menu
    }
}
