package uk.benmarks.shoalcade

import android.content.Context
import android.graphics.Color
import android.hardware.input.InputManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.swordfish.libretrodroid3ds.GLRetroView
import com.swordfish.libretrodroid3ds.GLRetroViewData
import com.swordfish.libretrodroid3ds.Variable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import uk.benmarks.shoalcade.Area
import uk.benmarks.shoalcade.AxisRest
import uk.benmarks.shoalcade.Console
import uk.benmarks.shoalcade.ControlPrefs
import uk.benmarks.shoalcade.ControllerInfo
import uk.benmarks.shoalcade.ControlsFlow
import uk.benmarks.shoalcade.ControlsScreen
import uk.benmarks.shoalcade.ControlsState
import uk.benmarks.shoalcade.DrawerNav
import uk.benmarks.shoalcade.DrawerState
import uk.benmarks.shoalcade.DsLayout
import uk.benmarks.shoalcade.GameDrawer
import uk.benmarks.shoalcade.GameMenu
import uk.benmarks.shoalcade.GameSound
import uk.benmarks.shoalcade.GamepadView
import uk.benmarks.shoalcade.HapticsGate
import uk.benmarks.shoalcade.HintSpot
import uk.benmarks.shoalcade.MenuContext
import uk.benmarks.shoalcade.MenuItem
import uk.benmarks.shoalcade.MenuKeys
import uk.benmarks.shoalcade.MenuSection
import uk.benmarks.shoalcade.PadButton
import uk.benmarks.shoalcade.PadOut
import uk.benmarks.shoalcade.PadSpec
import uk.benmarks.shoalcade.PadTranslator
import uk.benmarks.shoalcade.ScreenMode
import uk.benmarks.shoalcade.TouchAutoHide
import uk.benmarks.shoalcade.TouchMode
import uk.benmarks.shoalcade.controllerInfo
import uk.benmarks.shoalcade.isGameController
import uk.benmarks.shoalcade.isGamepadSource
import uk.benmarks.shoalcade.screenOrientation
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.min

/**
 * How the two 3DS screens are arranged (Azahar's "Screen Layout" core option). [aspect] = the core's frame width ÷ height
 * (src/citra_libretro/emu_window/libretro_window.cpp, ComputeLayoutGeometry), [swappedAspect] with the screens swapped.
 */
enum class N3dsLayout(val key: String, val label: String, val core: String, val aspect: Float, val swappedAspect: Float) {
    STACKED("stacked", "Top and bottom", "default", 400f / 480f, 400f / 480f),
    /** One screen, full size; the Flip button swaps top and bottom (Ben 30/10). Landscape default. */
    SINGLE("single", "One screen (Flip to swap)", "single_screen", 400f / 240f, 320f / 240f),
    LARGE("large", "Big screen, small screen", "large_screen", 480f / 240f, 420f / 240f),
    SIDE("side", "Side by side", "side_by_side", 720f / 240f, 720f / 240f);

    fun aspect(swapped: Boolean) = if (swapped) swappedAspect else aspect

    companion object { fun of(k: String?, d: N3dsLayout) = entries.firstOrNull { it.key == k } ?: d }
}

/**
 * The 3DS game screen (plan-3ds steps 5-6). Our own Azahar core on the 3DS-only Vulkan layer (D153), with the same
 * pieces the other consoles use (menu drawer, controller maps, Controls screen, touch auto-hide, App Mute, save sync) but
 * its own wiring, so GameActivity and every other console stay untouched (lessons 30/10). Opened from the library
 * ([GameActivity.intent] sends 3DS here) with a game and user; Shoalcade Dev's "3DS test" also opens a bare file
 * ([EXTRA_ROM], no Jellyfin). Saves are a folder the core writes itself (like PSP): `Azahar/sdmc/Nintendo 3DS` inside
 * [Storage.memstickDir], zipped to sync (D158).
 */
class Game3dsActivity : ComponentActivity() {

    private val console = Console.N3DS
    private lateinit var root: FrameLayout
    private lateinit var retroView: GLRetroView
    private lateinit var pad: GamepadView
    private lateinit var menuHandle: TextView
    private lateinit var speed: TextView
    private lateinit var rom: File
    /** The library game and user; null for a bare file from Shoalcade Dev's "3DS test". */
    private var game: Game? = null
    private var userId: String? = null
    private var quitting = false
    /** The 3DS check (D151): the bundled test scene, measured; no buttons, menu or saves. */
    private var check = false
    private var checkSecond = 0
    private val checkFps = mutableListOf<Int>()
    /** The loading screen (GameLoading.kt, shared with the other consoles) until the game's first picture. */
    private var loading: LoadingState? = null
    private var loadingView: ComposeView? = null
    private val ticker = Handler(Looper.getMainLooper())
    @Volatile private var frames = 0
    private var land = false
    private var gameArea = Area(0f, 0f, 0f, 0f)
    private var lastSpec: PadSpec? = null
    /** Core options as last sent, so only real changes go to the core. */
    private var sentLayout: Pair<N3dsLayout, Boolean>? = null

    // --- Display choices (kept on the phone) ---
    private val displayPrefs by lazy { getSharedPreferences("n3ds_display", Context.MODE_PRIVATE) }
    /** Turn / Portrait / Landscape: the same saved choice Settings shows for 3DS (DisplayPrefs, like the others). */
    private val display: DisplayChoices by lazy { DisplayPrefs.of(this, console) }
    private var screen: ScreenMode
        get() = display.screen
        set(v) { display.screen = v }
    private fun layout(l: Boolean) =
        N3dsLayout.of(displayPrefs.getString(if (l) "layout.land" else "layout.port", null), if (l) N3dsLayout.SINGLE else N3dsLayout.STACKED)
    private fun setLayout(l: Boolean, v: N3dsLayout) = displayPrefs.edit().putString(if (l) "layout.land" else "layout.port", v.key).apply()
    private fun swap(l: Boolean) = displayPrefs.getBoolean(if (l) "swap.land" else "swap.port", false)
    private fun setSwap(l: Boolean, v: Boolean) = displayPrefs.edit().putBoolean(if (l) "swap.land" else "swap.port", v).apply()

    // --- Controllers ---
    private val controlPrefs by lazy { ControlPrefs.of(this) }
    private val autoHide = TouchAutoHide()
    private val translators = mutableMapOf<String, PadTranslator>()
    private val axisRest = AxisRest()
    private var lastDevice = -1
    private var lastController: ControllerInfo? = null
    private var sentC = 0f to 0f
    private var swallowTouch = false
    /** Rest of a touch gesture that opened the menu from the screen edge (as GameActivity). */
    private var swallowEdge = false
    private val edgeSwipe by lazy { resources.displayMetrics.density.let { EdgeSwipe(20f * it, 40f * it) } }
    private val deviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(id: Int) {
            if (isGameController(InputDevice.getDevice(id))) { autoHide.controllerConnected(id); applyTouchVisibility() }
        }
        override fun onInputDeviceRemoved(id: Int) {
            autoHide.controllerDisconnected(id); axisRest.forget(id); lastController = null
            releaseControllers(); applyTouchVisibility()
        }
        override fun onInputDeviceChanged(id: Int) {}
    }

    // --- Menu ---
    private val drawer = DrawerState()
    private val nav = DrawerNav()
    private var drawerView: ComposeView? = null
    private var controlsView: ComposeView? = null
    private var flow: ControlsFlow? = null
    private val controlsOpen get() = controlsView?.visibility == View.VISIBLE
    private val soundStore by lazy { GameSound.store(this) }
    private var muted
        get() = GameSound.muted(soundStore)
        set(v) = GameSound.setMuted(soundStore, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_ID)
        if (id != null) {
            val g = Game(id = id, name = intent.getStringExtra(EXTRA_NAME) ?: "", console = console,
                fileName = intent.getStringExtra(EXTRA_FILE) ?: "", canDownload = true)
            val uid = intent.getStringExtra(EXTRA_USER)!!
            val f = Storage.romFile(g)
            if (f == null) { toast("Game file missing. Download it again."); finish(); return }
            // D161: a .cia is an installer package; the 3DS engine can't open it (Azahar's libretro core plays
            // .3ds/.cci/.cxi/.3dsx and their compressed .z forms). Say so instead of failing to start.
            if (f.extension.equals("cia", ignoreCase = true)) { toast(CIA_TEXT); finish(); return }
            RomCheck.problem(console, f)?.let { toast(RomCheck.message(console, it)); finish(); return }
            game = g; userId = uid; rom = f
            LibraryPrefs.of(this).recordLaunch(uid, id) // "Last played" sort
            // Saves are precious: a copy of the save as it was before this session, so a bad session can be undone.
            runCatching { Storage.backupSave(uid, g) }.onFailure {
                toast("Couldn't back up your save first (${it.message}). Close the game if you don't want to risk it.")
            }
        } else if (intent.getBooleanExtra(EXTRA_CHECK, false)) {
            check = true
            rom = runCatching { prepareCheck() }.getOrElse { endCheck(REASON_ERROR); return }
        } else {
            rom = File(intent.getStringExtra(EXTRA_ROM) ?: run { finish(); return })
            if (!rom.isFile) { finish(); return }
        }
        running = true
        requestedOrientation = screenOrientation(console, screen)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        land = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        sentLayout = layout(land) to swap(land)

        val base = File(filesDir, "dev3ds")
        val g = game
        val data = GLRetroViewData(this).apply {
            coreFilePath = File(applicationInfo.nativeLibraryDir, CORE).absolutePath
            gameFilePath = rom.absolutePath
            systemDirectory = File(filesDir, "n3ds/system").apply { mkdirs() }.absolutePath
            // The core keeps everything under <saves>/Azahar/ (the game's save in sdmc/Nintendo 3DS, its own files in nand).
            savesDirectory = when {
                g != null -> Storage.memstickDir(userId!!, g)
                check -> checkDir()
                else -> File(base, "saves")
            }.apply { mkdirs() }.absolutePath
            variables = options().map { (k, v) -> Variable(k, v) }.toTypedArray()
            preferVulkan = on(VULKAN) // PowerVR GPUs: OpenGL glitches, Vulkan works (D151)
            preferLowLatencyAudio = false // F4 (as the other consoles): low latency loses sound after a call
        }
        retroView = GLRetroView(this, data).apply { isFocusable = true; isFocusableInTouchMode = true }
        lifecycle.addObserver(retroView)

        pad = GamepadView(this, object : GamepadView.Listener {
            override fun onButton(keyCode: Int, pressed: Boolean) {
                if (keyCode == FLIP) { if (pressed) flip(); return }
                retroView.sendKeyEvent(if (pressed) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, keyCode)
            }
            override fun onDpad(x: Float, y: Float) = retroView.sendMotionEvent(GLRetroView.MOTION_SOURCE_DPAD, x, y)
            override fun onStick(x: Float, y: Float) = retroView.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_LEFT, x, y)
        }, spec = { w, h -> padSpec(w, h) })
        // Landscape: the pad lies over the game, so a finger on the picture (the touch screen) goes to the game.
        pad.onHoleTouch = { retroView.onTouchEvent(it) }

        menuHandle = TextView(this).apply {
            text = MENU_LABEL; contentDescription = "Menu"; gravity = Gravity.CENTER
            setTextColor(Color.WHITE); typeface = android.graphics.Typeface.DEFAULT_BOLD; includeFontPadding = false
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 999f; setColor(Color.argb(170, 40, 40, 52))
            }
            isClickable = true; isFocusable = false
            setOnClickListener { openDrawer() }
        }
        speed = TextView(this).apply {
            setTextColor(Color.WHITE); setBackgroundColor(Color.argb(150, 0, 0, 0)); textSize = 11f; setPadding(10, 3, 10, 3)
            visibility = if (on(SHOW_SPEED) || check) View.VISIBLE else View.GONE
            if (check) { textSize = 16f; text = "3DS check: getting ready…" }
        }

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(retroView)
        root.addView(pad)
        root.addView(menuHandle)
        root.addView(speed, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) place(r - l, b - t)
        }
        setContentView(root)
        if (check) { pad.visibility = View.GONE; menuHandle.visibility = View.GONE }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    check -> endCheck(REASON_CANCELLED)
                    controlsOpen -> controlsBackPressed()
                    drawer.open -> closeDrawer()
                    else -> openDrawer()
                }
            }
        })

        lifecycleScope.launch {
            retroView.getGLRetroEvents().collect { if (it is GLRetroView.GLRetroEvents.FrameRendered) frames++ }
        }
        lifecycleScope.launch {
            retroView.getGLRetroErrors().collect {
                if (check) endCheck(REASON_ERROR)
                // A library game that can't start says so and closes, as on the other consoles (not hidden under loading).
                if (!check && game != null && it in START_ERRORS) {
                    toast("The emulator couldn't start this game (error $it).")
                    finish()
                }
                speed.visibility = View.VISIBLE; speed.text = "Error $it (${errorName(it)})"
            }
        }
        setUpControllers()
        applySound()
        // App Mute (F5, as GameActivity): the emulator only exists once it runs and resets the sound when it starts,
        // so the sound is set again on the first frame and each time the game comes back to the front.
        lifecycleScope.launch {
            retroView.getGLRetroEvents().first { it is GLRetroView.GLRetroEvents.FrameRendered }
            applySound()
            lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
                override fun onResume(owner: androidx.lifecycle.LifecycleOwner) { applySound() }
            })
        }
        if (!check) startLoading()
        // D90: the launch choice "start from the snapshot": loaded once the game is running.
        if (intent.getBooleanExtra(EXTRA_SNAPSHOT, false) && stateFile().isFile) lifecycleScope.launch {
            retroView.getGLRetroEvents().first { it is GLRetroView.GLRetroEvents.FrameRendered }
            loadState()
        }
    }

    override fun onDestroy() {
        getSystemService(InputManager::class.java)?.unregisterInputDeviceListener(deviceListener)
        running = false // set again by onCreate if Android rebuilds the screen (otherwise Push all would wait for good)
        super.onDestroy()
    }

    // --- Layout ---------------------------------------------------------------------------------

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        land = newConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        applyCoreLayout()
        if (drawer.open) refreshDrawer() // the 3DS screens choice is kept per way up
    }

    /** Flip: the other screen (one-screen layout) or the screens swapped (the others). Kept per way up, like Swap. */
    private fun flip() {
        setSwap(land, !swap(land))
        applyCoreLayout()
    }

    /** Sends the screen layout for the way the phone faces now, if it changed. */
    private fun applyCoreLayout() {
        val want = layout(land) to swap(land)
        if (want == sentLayout) return
        sentLayout = want
        retroView.updateVariables(
            Variable("citra_layout_option", want.first.core),
            Variable("citra_swap_screen", if (want.second) "Bottom" else "Top"),
        )
        if (root.width > 0) place(root.width, root.height)
    }

    /**
     * Portrait: the game just below the camera notch, as wide as fits; the pad below, no taller than 0.85 × the width
     * (Ben 30/10: "too much empty space"). Landscape: the game centred, as big as fits; the pad lies over it, see-through.
     */
    private fun place(w: Int, h: Int) {
        val (lay, sw) = layout(land) to swap(land)
        val aspect = lay.aspect(sw)
        if (!land) {
            val top = window.decorView.rootWindowInsets?.displayCutout?.safeInsetTop ?: 0
            val room = h - top
            var gw = w
            var gh = (w / aspect).toInt()
            if (gh > room * 62 / 100) { gh = room * 62 / 100; gw = (gh * aspect).toInt() }
            val ph = min(room - gh, (w * 0.85f).toInt())
            gameArea = Area(((w - gw) / 2).toFloat(), top.toFloat(), ((w + gw) / 2).toFloat(), (top + gh).toFloat())
            setBox(retroView, (w - gw) / 2, top, gw, gh)
            setBox(pad, 0, h - ph, w, ph)
            setBox(speed, 0, top + gh, -2, -2)
        } else {
            var gh = h
            var gw = (h * aspect).toInt()
            if (gw > w) { gw = w; gh = (w / aspect).toInt() }
            gameArea = Area(((w - gw) / 2).toFloat(), ((h - gh) / 2).toFloat(), ((w + gw) / 2).toFloat(), ((h + gh) / 2).toFloat())
            setBox(retroView, (w - gw) / 2, (h - gh) / 2, gw, gh)
            setBox(pad, 0, 0, w, h)
            setBox(speed, 0, 0, -2, -2)
        }
        pad.refreshSpec()
        pad.post { placeMenu() }
    }

    private fun setBox(v: View, left: Int, top: Int, width: Int, height: Int) {
        v.layoutParams = FrameLayout.LayoutParams(width, height).apply { leftMargin = left; topMargin = top }
    }

    /** The ☰ Menu handle sits where the pad left room for it. */
    private fun placeMenu() {
        val s = lastSpec ?: return
        val m = s.menu
        setBox(menuHandle, (pad.left + m.left).toInt(), (pad.top + m.top).toInt(), (m.right - m.left).toInt(), (m.bottom - m.top).toInt())
        menuHandle.textSize = (s.textSize * 0.6f) / resources.displayMetrics.scaledDensity
        menuHandle.bringToFront()
    }

    /** By the way the phone faces, not the pad's shape: the portrait pad is wider than tall too. */
    private fun padSpec(w: Int, h: Int): PadSpec = (if (land) landscapeSpec(w, h) else portraitSpec(w, h)).also { lastSpec = it }

    /** Portrait 3DS pad (step 5): full-size buttons, circle pad above the D-pad, ABXY, L/ZL/ZR/R, Start/Select. */
    private fun portraitSpec(w: Int, h: Int): PadSpec {
        val W = w.toFloat()
        val H = h.toFloat()
        val unit = min(W * 0.085f, H * 0.11f)
        val pill = unit * 0.45f
        // A clear gap between L and ZL, ZR and R (Ben 30/10: "they r literally touching").
        val b = shoulders(W, H * 0.08f, pill, 0.085f, 0.255f) + faces(W * 0.75f, H * 0.45f, unit) + listOf(
            PadButton("SELECT", KeyEvent.KEYCODE_BUTTON_SELECT, W * 0.60f, H * 0.90f, pill, wide = true),
            PadButton("START", KeyEvent.KEYCODE_BUTTON_START, W * 0.82f, H * 0.90f, pill, wide = true),
        )
        val m = unit * 0.5f
        return PadSpec(
            b,
            dpadCx = W * 0.24f, dpadCy = H * 0.73f, dpadR = min(W * 0.14f, H * 0.15f),
            stickCx = W * 0.24f, stickCy = H * 0.34f, stickR = min(W * 0.15f, H * 0.16f),
            textSize = unit * 0.6f,
            menu = Area(W / 2 - m * 2.2f, H * 0.08f - m, W / 2 + m * 2.2f, H * 0.08f + m),
            hint = HintSpot(W / 2, H / 2, W),
            background = true,
        )
    }

    /**
     * Landscape 3DS pad: over the game, see-through; touches on the picture go to the game (the touch screen). Buttons
     * keep clear of the camera notch (Ben 30/10: "in landscape the A is on the camera notch"), whichever side it is on.
     */
    private fun landscapeSpec(w: Int, h: Int): PadSpec {
        val cut = window.decorView.rootWindowInsets?.displayCutout
        val a = (cut?.safeInsetLeft ?: 0).toFloat()
        val W = w - a - (cut?.safeInsetRight ?: 0)
        fun x(f: Float) = a + W * f
        val H = h.toFloat()
        val unit = H * 0.075f
        val pill = unit * 0.55f // with a gap between L and ZL, ZR and R
        val d = unit * FACE_SPREAD
        val b = listOf(
            PadButton("L", KeyEvent.KEYCODE_BUTTON_L1, x(0.05f), H * 0.08f, pill, wide = true),
            PadButton("ZL", KeyEvent.KEYCODE_BUTTON_L2, x(0.14f), H * 0.08f, pill, wide = true),
            PadButton("ZR", KeyEvent.KEYCODE_BUTTON_R2, x(0.86f), H * 0.08f, pill, wide = true),
            PadButton("R", KeyEvent.KEYCODE_BUTTON_R1, x(0.95f), H * 0.08f, pill, wide = true),
        ) + faces(x(1f) - unit - d, H * 0.50f, unit) + listOf(
            PadButton("SELECT", KeyEvent.KEYCODE_BUTTON_SELECT, x(0.83f), H * 0.91f, unit * 0.55f, wide = true),
            PadButton("START", KeyEvent.KEYCODE_BUTTON_START, x(0.94f), H * 0.91f, unit * 0.55f, wide = true),
            // Flip between the top and bottom screens (Ben 30/10): just the symbol, under R, off the game picture
            // (touches on the picture go to the game, so a button there never gets them).
            PadButton("⇅", FLIP, x(0.95f), H * 0.22f, unit * 0.62f),
        )
        val m = unit * 0.5f
        val stickR = H * 0.15f
        return PadSpec(
            b,
            dpadCx = a + stickR * 1.1f, dpadCy = H * 0.74f, dpadR = H * 0.14f,
            stickCx = a + stickR * 1.1f, stickCy = H * 0.38f, stickR = stickR,
            textSize = unit * 0.7f,
            menu = Area(x(0.5f) - m * 2.2f, H - m * 2.4f, x(0.5f) + m * 2.2f, H - m * 0.4f),
            hint = HintSpot(x(0.5f), H * 0.5f, W * 0.5f),
            seeThrough = true,
            hole = gameArea,
        )
    }

    private fun shoulders(W: Float, y: Float, r: Float, outer: Float, inner: Float) = listOf(
        PadButton("L", KeyEvent.KEYCODE_BUTTON_L1, W * outer, y, r, wide = true),
        PadButton("ZL", KeyEvent.KEYCODE_BUTTON_L2, W * inner, y, r, wide = true),
        PadButton("ZR", KeyEvent.KEYCODE_BUTTON_R2, W * (1 - inner), y, r, wide = true),
        PadButton("R", KeyEvent.KEYCODE_BUTTON_R1, W * (1 - outer), y, r, wide = true),
    )

    /** Nintendo layout: X top, A right, B bottom, Y left (same positions as the libretro pad). */
    private fun faces(fx: Float, fy: Float, unit: Float): List<PadButton> {
        val d = unit * FACE_SPREAD // a small gap between X, Y, B and A (Ben 30/10)
        return listOf(
            PadButton("X", KeyEvent.KEYCODE_BUTTON_X, fx, fy - d, unit),
            PadButton("A", KeyEvent.KEYCODE_BUTTON_A, fx + d, fy, unit),
            PadButton("B", KeyEvent.KEYCODE_BUTTON_B, fx, fy + d, unit),
            PadButton("Y", KeyEvent.KEYCODE_BUTTON_Y, fx - d, fy, unit),
        )
    }

    // --- Menu -----------------------------------------------------------------------------------

    private val displaySection = MenuSection("display", GameMenu.DISPLAY) { _ ->
        buildList {
            add(MenuItem.Heading("screen", GameMenu.SCREEN))
            ScreenMode.entries.forEach { add(MenuItem.Choice(GameMenu.screenId(it), it.label, "screen", screen == it)) }
            add(MenuItem.Heading("n3ds", SCREENS))
            N3dsLayout.entries.forEach { add(MenuItem.Choice(layoutId(it), it.label, "n3ds", layout(land) == it)) }
            add(MenuItem.Toggle(ID_SWAP, GameMenu.DS_SWAP, swap(land)))
        }
    }
    private val sections by lazy {
        listOf(GameMenu.GAME_SECTION, GameMenu.SNAPSHOT_SECTION, GameMenu.SOUND_SECTION, displaySection, GameMenu.CONTROLS_SECTION)
    }

    /** The library game's snapshot (per user, like the other consoles); a bare dev file keeps its own. */
    private fun stateFile() = game?.let { Storage.stateFile(userId!!, it) } ?: File(filesDir, "dev3ds/states/${rom.name}.state")

    private fun menuContext() = MenuContext(
        console = console, states = game == null || SnapshotSetting.enabled(this, console), hasState = stateFile().isFile,
        touch = autoHide.mode, screen = screen, ds = DsLayout.entries.first(), dsSwap = false, muted = muted,
        canUpload = game != null && !intent.getBooleanExtra(EXTRA_OFF_LIST, false), // F9 + bare dev files: nowhere to upload
        cheatsAllowed = false,
    )

    private fun refreshDrawer(resetFocus: Boolean = false) {
        val shown = GameMenu.sections(menuContext(), sections)
        nav.setSections(shown, resetFocus)
        drawer.title = game?.name ?: rom.nameWithoutExtension
        drawer.sections = shown
        drawer.focusId = nav.focus
    }

    private fun openDrawer() {
        if (check) { endCheck(REASON_CANCELLED); return } // no menu during the 3DS check: Home / Back stop it
        if (controlsOpen || drawer.open) return
        releaseControllers()
        cancelTouches()
        refreshDrawer(resetFocus = true)
        nav.reset(axisRest.last(lastDevice))
        val view = drawerView ?: ComposeView(this).also { v ->
            v.setContent { HapticsGate { GameDrawer(drawer, onPick = { menuPick(it) }, onClose = { closeDrawer() }) } }
            root.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            drawerView = v
        }
        view.bringToFront()
        drawer.show()
        view.visibility = View.VISIBLE
        setGameAccessible(false)
        pauseForMenu(true)
    }

    private fun closeDrawer() {
        if (!drawer.open) return
        drawer.hide()
        drawerView?.visibility = View.GONE
        setGameAccessible(true)
        pauseForMenu(false)
    }

    /** The game stands still while the menu is open (no frames, no sound). */
    private fun pauseForMenu(on: Boolean) {
        retroView.frameSpeed = if (on) 0 else 1
        applySound()
    }

    private fun applySound() {
        retroView.audioEnabled = GameSound.enabled(menuOpen = drawer.open || controlsOpen, muted = muted)
    }

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

    private fun menuPick(id: String) {
        when (id) {
            GameMenu.ID_RESUME -> closeDrawer()
            GameMenu.ID_CLOSE -> { closeDrawer(); closeGame(upload = false) }
            GameMenu.ID_CLOSE_UPLOAD -> { closeDrawer(); closeGame(upload = true) }
            GameMenu.ID_SAVE_SNAPSHOT -> { closeDrawer(); saveState() }
            GameMenu.ID_LOAD_SNAPSHOT -> { closeDrawer(); loadState() }
            GameMenu.ID_CONTROLS -> { closeDrawer(); openControls() }
            GameMenu.ID_MUTE -> { muted = !muted; applySound(); refreshDrawer() }
            ID_SWAP -> { setSwap(land, !swap(land)); applyCoreLayout(); refreshDrawer() }
            else -> {
                GameMenu.touchOf(id)?.let { m ->
                    autoHide.mode = m; controlPrefs.setTouchMode(console, m); applyTouchVisibility(); refreshDrawer()
                }
                GameMenu.screenOf(id)?.let { m -> screen = m; requestedOrientation = screenOrientation(console, m); refreshDrawer() }
                N3dsLayout.entries.firstOrNull { layoutId(it) == id }?.let { l -> setLayout(land, l); applyCoreLayout(); refreshDrawer() }
            }
        }
    }

    /** Snapshots stay on the phone for now (Jellyfin sync is step 6). */
    private fun saveState() {
        val f = stateFile()
        thread(name = "n3ds-state") {
            val ok = runCatching { f.parentFile?.mkdirs(); f.writeBytes(retroView.serializeState()) }.isSuccess
            runOnUiThread { toast(if (ok) "Snapshot saved" else "Couldn't save the snapshot") }
        }
    }

    private fun loadState() {
        val f = stateFile()
        if (!f.isFile) return
        thread(name = "n3ds-state") {
            // Azahar snapshots vary in size as the game runs, so the layer's strict same-size check always refuses
            // them (Ben 30/10: "couldnt load snapshot"). The persisted loader lets the core check the snapshot itself.
            val ok = runCatching { retroView.unserializePersistedState(f.readBytes()) }.getOrDefault(false)
            runOnUiThread { toast(if (ok) "Snapshot loaded" else "Couldn't load the snapshot") }
        }
    }

    private fun toast(s: String) = Toast.makeText(applicationContext, s, Toast.LENGTH_LONG).show()

    // --- Close Game (and Upload): the same questions as the other consoles (GameActivity) ---------

    /**
     * The core writes the 3DS save into its folder itself as soon as the game saves (like PSP), so there is nothing to
     * pull out of the emulator first. The game stops before the upload so nothing changes the folder while it is zipped.
     */
    private fun closeGame(upload: Boolean) {
        if (quitting) return
        quitting = true
        retroView.frameSpeed = 0
        val g = game
        if (!upload || g == null) { toast(CloseSave.closedText(false, false)); finish(); return }
        lifecycleScope.launch {
            val sync = SaveSync(this@Game3dsActivity)
            toast("Uploading your save to Jellyfin…")
            when (val r = sync.uploadLatest(g, snapshotNoSave = false)) {
                is SyncResult.NeedsConfirm -> askReplaceNewer(sync, g, r)
                is SyncResult.Conflict -> askConflict(sync, g, r)
                else -> { toast(CloseSave.uploadNote(false, false) + r.message); finish() }
            }
        }
    }

    private fun askReplaceNewer(sync: SaveSync, g: Game, r: SyncResult.NeedsConfirm) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Replace a newer save?")
            .setMessage(r.message)
            .setCancelable(false)
            .setPositiveButton("Upload anyway") { _, _ -> finishWith { sync.keepPhone(g) } }
            .setNegativeButton("Don't upload") { _, _ -> toast("Not uploaded. Your save is kept on the phone."); finish() }
            .show()
    }

    /** Both changed (D35): never settled silently; the other copy is always kept. */
    private fun askConflict(sync: SaveSync, g: Game, r: SyncResult.Conflict) {
        val (phone, jf) = SavePlan.dateLines(Storage.saveTime(userId!!, g), r.server)
        android.app.AlertDialog.Builder(this)
            .setTitle("Which save should be in use?")
            .setMessage("${g.name} has a different save on this phone and on Jellyfin (maybe from another phone).\n\n" +
                "$phone\n$jf\n\nNothing is lost either way: the other save is kept in Jellyfin's history and in the phone's backups.")
            .setCancelable(false)
            .setPositiveButton("Keep the phone's") { _, _ -> finishWith { sync.keepPhone(g) } }
            .setNeutralButton("Decide later") { _, _ ->
                toast("Not uploaded. Your save is kept on the phone; tap ⚠️ in the library to choose."); finish()
            }
            .apply { if (r.server != null) setNegativeButton("Keep Jellyfin's") { _, _ -> finishWith { sync.keepServer(g) } } }
            .show()
    }

    private fun finishWith(op: suspend () -> SyncResult) {
        lifecycleScope.launch { toast(op().message); finish() }
    }

    // --- Controls screen (shared with the other consoles, 3DS buttons) --------------------------

    private fun openControls() {
        releaseControllers()
        val f = flow ?: ControlsFlow(ControlsState(console), controlPrefs,
            later = { ms, block -> val r = Runnable(block); root.postDelayed(r, ms); { root.removeCallbacks(r) } },
            changed = { key, map -> translators[key]?.map = map }).also { flow = it }
        val c = lastController
            ?: InputDevice.getDeviceIds().map { InputDevice.getDevice(it) }.firstOrNull { isGameController(it) }?.let { controllerInfo(it) }
        f.open(c)
        val view = controlsView ?: ComposeView(this).also { v ->
            v.setContent { HapticsGate {
                ControlsScreen(f.state, onPick = { f.startListening(it, axisRest.last(lastDevice)) }, onReset = { f.reset() },
                    onDone = { closeControls() })
            } }
            root.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            controlsView = v
        }
        view.bringToFront()
        view.visibility = View.VISIBLE
        setGameAccessible(false)
        retroView.frameSpeed = 0
        applySound()
    }

    private fun closeControls() {
        flow?.stopListening()
        controlsView?.visibility = View.GONE
        setGameAccessible(true)
        retroView.frameSpeed = 1
        applySound()
    }

    private fun controlsBackPressed() { if (flow?.back() != true) closeControls() }

    // --- Controllers ----------------------------------------------------------------------------

    private fun setUpControllers() {
        autoHide.mode = controlPrefs.touchMode(console)
        for (id in InputDevice.getDeviceIds()) if (isGameController(InputDevice.getDevice(id))) autoHide.controllerConnected(id)
        getSystemService(InputManager::class.java)?.registerInputDeviceListener(deviceListener, Handler(Looper.getMainLooper()))
        applyTouchVisibility()
    }

    private fun applyTouchVisibility() {
        val show = autoHide.visible
        pad.setHidden(!show, when {
            show -> null
            autoHide.mode == TouchMode.HIDE -> TOUCH_OFF_HINT
            else -> TOUCH_HIDDEN_HINT
        })
    }

    private fun translatorFor(c: ControllerInfo) = translators.getOrPut(c.key) { PadTranslator(controlPrefs.map(console, c.key)) }

    private fun releaseControllers() {
        for (t in translators.values) send(t.releaseAll())
        if (sentC != 0f to 0f) { sentC = 0f to 0f; retroView.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_RIGHT, 0f, 0f) }
    }

    private fun send(outs: List<PadOut>) {
        for (o in outs) when (o) {
            is PadOut.Key -> retroView.sendKeyEvent(if (o.down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, o.keyCode)
            is PadOut.Dpad -> retroView.sendMotionEvent(GLRetroView.MOTION_SOURCE_DPAD, o.x.toFloat(), o.y.toFloat())
            is PadOut.Stick -> retroView.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_LEFT, o.x, o.y)
            is PadOut.CStick -> {}
            PadOut.Menu -> openDrawer()
        }
    }

    private fun controllerUsed() {
        val was = autoHide.visible
        autoHide.controllerInput()
        if (was != autoHide.visible) applyTouchVisibility()
    }

    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!::retroView.isInitialized) return super.dispatchKeyEvent(event)
        val down = event.action == KeyEvent.ACTION_DOWN
        if (drawer.open && (isGamepadSource(event.source) || (DrawerNav.handles(event.keyCode) && event.keyCode != KeyEvent.KEYCODE_BACK))) {
            navResult(nav.key(event.keyCode, down, event.repeatCount))
            return true
        }
        if (!isGamepadSource(event.source)) return super.dispatchKeyEvent(event)
        val info = controllerInfo(event.device)
        lastController = info
        lastDevice = event.deviceId
        flow?.takeIf { controlsOpen }?.let { f ->
            return when (f.key(info, event.keyCode, down, event.repeatCount)) {
                ControlsFlow.KeyResult.HANDLED -> true
                ControlsFlow.KeyResult.BACK -> { controlsBackPressed(); true }
                ControlsFlow.KeyResult.PASS_ON -> super.dispatchKeyEvent(event)
            }
        }
        if (down && event.repeatCount == 0) controllerUsed()
        if (down && event.repeatCount == 0 && MenuKeys.isMenu(event.keyCode)) { openDrawer(); return true }
        send(translatorFor(info).key(event.keyCode, down, event.repeatCount))
        return true
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (!::retroView.isInitialized || !isGamepadSource(event.source) || event.action != MotionEvent.ACTION_MOVE) {
            return super.dispatchGenericMotionEvent(event)
        }
        val values = PadTranslator.AXES.associateWith { event.getAxisValue(it) }
        val info = controllerInfo(event.device)
        lastController = info
        lastDevice = event.deviceId
        val moved = axisRest.update(event.deviceId, values)
        val f = flow
        if (controlsOpen && f != null) { f.motion(info, values, moved); return true }
        if (drawer.open) { navResult(nav.motion(values)); return true }
        if (moved) controllerUsed()
        send(translatorFor(info).motion(values))
        // New 3DS C-stick = the controller's right stick, straight through.
        fun dz(v: Float) = if (kotlin.math.abs(v) < PadTranslator.DEAD_ZONE) 0f else v
        val c = dz(event.getAxisValue(MotionEvent.AXIS_Z)) to dz(event.getAxisValue(MotionEvent.AXIS_RZ))
        if (c != sentC) { sentC = c; retroView.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_RIGHT, c.first, c.second) }
        return true
    }

    /** Auto: a touch while the buttons are hidden only brings them back (the touch screen still takes touches). */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (swallowEdge) { // the rest of the swipe that opened the menu: it must not land on the menu and close it
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) swallowEdge = false
            return true
        }
        if (!::pad.isInitialized || controlsOpen || drawer.open || check) return super.dispatchTouchEvent(ev)
        if (edgeSwipeOpens(ev)) return true
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && !autoHide.visible) {
            val onGame = gameArea.contains(ev.x, ev.y)
            if (!onGame && !hits(menuHandle, ev) && autoHide.screenTouched()) { swallowTouch = true; applyTouchVisibility() }
        }
        if (swallowTouch) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) swallowTouch = false
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    /** A swipe in from the left edge opens the menu, unless it starts on the D-pad, circle pad or the game picture. */
    private fun edgeSwipeOpens(ev: MotionEvent): Boolean {
        val at = IntArray(2).also { root.getLocationOnScreen(it) }
        val x = ev.rawX - at[0]
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val s = lastSpec
                val px = x - pad.left
                val py = ev.rawY - at[1] - pad.top
                val onControls = s != null && pad.visibility == View.VISIBLE && autoHide.visible &&
                    (s.dpadZone.contains(px, py) ||
                        (s.stickR > 0 && kotlin.math.hypot(px - s.stickCx, py - s.stickCy) <= s.stickR * 1.25f))
                edgeSwipe.down(x, blocked = onControls || hits(retroView, ev))
            }
            MotionEvent.ACTION_MOVE -> if (edgeSwipe.move(x)) {
                openDrawer()
                swallowEdge = true
                swallowTouch = false
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> edgeSwipe.end()
        }
        return false
    }

    /** TalkBack mustn't wander onto the game and touch buttons hidden behind the menu or the Controls screen. */
    private fun setGameAccessible(on: Boolean) {
        val mode = if (on) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        pad.importantForAccessibility = mode
        menuHandle.importantForAccessibility = mode
        retroView.importantForAccessibility = mode
    }

    private fun hits(v: View, ev: MotionEvent): Boolean {
        val at = IntArray(2).also { v.getLocationOnScreen(it) }
        return ev.rawX >= at[0] && ev.rawX < at[0] + v.width && ev.rawY >= at[1] && ev.rawY < at[1] + v.height
    }

    // --- Speed readout (dev) --------------------------------------------------------------------

    override fun onResume() { super.onResume(); ticker.post(tick) }
    override fun onPause() { ticker.removeCallbacks(tick); super.onPause() }

    private val tick = object : Runnable {
        override fun run() {
            val f = frames
            frames = 0
            if (check) { checkTick(f); ticker.postDelayed(this, 1000); return }
            if (!speed.text.startsWith("Error")) speed.text = "$f fps · ${f * 100 / 60}% · ${if (on(VULKAN)) "Vulkan" else "OpenGL"} 1×"
            ticker.postDelayed(this, 1000)
        }
    }

    // --- Loading screen (as GameActivity) ----------------------------------------------------------

    /** Shows the loading screen if the game hasn't shown a picture within a second; drops it at the first picture. */
    private fun startLoading() {
        val state = LoadingState(game?.name?.ifBlank { null } ?: rom.nameWithoutExtension).also { loading = it }
        root.postDelayed({
            if (loading !== state || isFinishing) return@postDelayed
            val v = ComposeView(this).apply { setContent { GameLoading(state) } }
            root.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            menuHandle.bringToFront() // the Menu (and Back) still work while it loads
            loadingView = v
        }, LoadingWords.SHOW_AFTER_MS)
        lifecycleScope.launch {
            retroView.getGLRetroEvents().first { it is GLRetroView.GLRetroEvents.FrameRendered }
            state.stage = LoadingWords.GAME
            watchFirstPicture()
        }
    }

    /** Looks at a small copy of the game until it shows something that isn't black (FirstPicture), then drops the screen. */
    private suspend fun watchFirstPicture() {
        val bmp = android.graphics.Bitmap.createBitmap(64, 48, android.graphics.Bitmap.Config.ARGB_8888)
        val px = IntArray(64 * 48)
        val t0 = System.currentTimeMillis()
        while (loading != null) {
            val done = kotlinx.coroutines.CompletableDeferred<Boolean>()
            val asked = runCatching {
                android.view.PixelCopy.request(retroView, bmp, { r -> done.complete(r == android.view.PixelCopy.SUCCESS) },
                    Handler(Looper.getMainLooper()))
            }.isSuccess
            val got = asked && kotlinx.coroutines.withTimeoutOrNull(2_000) { done.await() } == true
            if (got) bmp.getPixels(px, 0, 64, 0, 0, 64, 48)
            if ((got && FirstPicture.lit(px)) || System.currentTimeMillis() - t0 > LOADING_SAFETY_MS) break
            kotlinx.coroutines.delay(250)
        }
        loading = null
        loadingView?.let { root.removeView(it) }
        loadingView = null
    }

    // --- The 3DS check (D151) ---------------------------------------------------------------------

    private fun checkDir() = File(filesDir, "n3ds/check")

    /** The bundled scene (assets/n3ds/check.3dsx, built from tools/n3ds-check) and its load, on its own SD card. */
    private fun prepareCheck(): File {
        val dir = checkDir().apply { mkdirs() }
        val scene = File(dir, "check.3dsx")
        assets.open(CHECK_SCENE).use { i -> scene.outputStream().use { i.copyTo(it) } }
        File(dir, "Azahar/sdmc/shoalcade").apply { mkdirs() }.let { File(it, "check.txt") }
            .writeText("${checkLoad("check_cubes", N3dsCheck.CUBES)} ${checkLoad("check_cpu", N3dsCheck.CPU)}")
        return scene
    }

    /** Shoalcade Dev can try other loads while the pass mark is calibrated; the real app always uses [N3dsCheck]. */
    private fun checkLoad(key: String, d: Int) = if (BuildConfig.DEBUG) prefs(this).getInt(key, d) else d

    /** Once a second while checking: count down, keep the measured seconds, stop if the phone is too hot. */
    private fun checkTick(f: Int) {
        if (quitting) return
        checkSecond++
        if (checkSecond > N3dsCheck.WARM_UP_S) checkFps += f
        val hot = getSystemService(android.os.PowerManager::class.java)?.currentThermalStatus ?: 0
        if (hot >= android.os.PowerManager.THERMAL_STATUS_SEVERE) { endCheck(REASON_HOT); return }
        val left = N3dsCheck.WARM_UP_S + N3dsCheck.MEASURE_S - checkSecond
        speed.text = if (checkSecond <= N3dsCheck.WARM_UP_S) "3DS check: warming up… $left s" else "3DS check: measuring… $left s · $f fps"
        if (left <= 0) endCheck(null)
    }

    /** Ends the check: the measured seconds go back to the 3DS check screen, or why it stopped. */
    private fun endCheck(reason: String?) {
        if (quitting) return
        quitting = true
        val data = android.content.Intent().putExtra(RESULT_FPS, checkFps.toIntArray())
        if (reason != null) data.putExtra(RESULT_REASON, reason)
        setResult(if (reason == null) RESULT_OK else RESULT_CANCELED, data)
        finish()
    }

    private fun errorName(code: Int) = when (code) {
        GLRetroView.ERROR_LOAD_LIBRARY -> "core didn't load"
        GLRetroView.ERROR_LOAD_GAME -> "game didn't load"
        GLRetroView.ERROR_GL_NOT_COMPATIBLE -> "graphics not supported"
        else -> "other"
    }

    // --- Core options ---------------------------------------------------------------------------

    private fun on(key: String) = prefs(this).getBoolean(key, DEFAULTS.getValue(key))
    private fun flag(key: String) = if (on(key)) "enabled" else "disabled"

    /** Azahar core options (src/citra_libretro/core_settings.cpp, 2126.2). D151 defaults, plus the dev switches. */
    private fun options() = listOf(
        "citra_graphics_api" to if (on(VULKAN)) "Vulkan" else "OpenGL",
        "citra_resolution_factor" to "1",
        "citra_use_hw_shader" to flag(HW_SHADER),
        "citra_use_shader_jit" to flag(SHADER_JIT),
        "citra_shaders_accurate_mul" to flag(ACCURATE_MUL),
        "citra_use_disk_shader_cache" to flag(DISK_CACHE),
        "citra_use_cpu_jit" to "enabled",
        "citra_layout_option" to layout(land).core,
        "citra_swap_screen" to if (swap(land)) "Bottom" else "Top",
        // Touch: the game view's touches, as a pointer, land on the bottom screen wherever it is drawn.
        "citra_enable_touch_touchscreen" to "enabled",
    )

    companion object {
        /** Errors that mean the game never started (a refused snapshot or cheat doesn't end the game). */
        private val START_ERRORS = setOf(GLRetroView.ERROR_LOAD_LIBRARY, GLRetroView.ERROR_LOAD_GAME, GLRetroView.ERROR_GL_NOT_COMPATIBLE)
        const val EXTRA_ROM = "rom"
        private const val EXTRA_ID = "id"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_FILE = "file"
        private const val EXTRA_USER = "user"
        private const val EXTRA_SNAPSHOT = "snapshot"
        private const val EXTRA_OFF_LIST = "offList"
        private const val EXTRA_CHECK = "check"
        private const val CHECK_SCENE = "n3ds/check.3dsx"
        const val RESULT_FPS = "fps"
        const val RESULT_REASON = "reason"
        const val REASON_HOT = "hot"
        const val REASON_CANCELLED = "cancelled"
        const val REASON_ERROR = "error"

        /** The 3DS check run (D151): returns [RESULT_FPS] (frames each measured second) or [RESULT_REASON]. */
        fun checkIntent(ctx: Context) = android.content.Intent(ctx, Game3dsActivity::class.java).putExtra(EXTRA_CHECK, true)

        /** True while a 3DS game is open (Push all / Pull all and the saves panel wait for it, as for the others). */
        var running = false
            private set

        /** A library 3DS game. [snapshot] = start from the phone's snapshot (D90); [offList] = no longer in the library (F9). */
        fun intent(ctx: Context, game: Game, userId: String, snapshot: Boolean = false, offList: Boolean = false) =
            android.content.Intent(ctx, Game3dsActivity::class.java)
                .putExtra(EXTRA_ID, game.id).putExtra(EXTRA_NAME, game.name).putExtra(EXTRA_FILE, game.fileName)
                .putExtra(EXTRA_USER, userId).putExtra(EXTRA_SNAPSHOT, snapshot).putExtra(EXTRA_OFF_LIST, offList)
        private const val CORE = "libazahar_libretro_android.so"
        private const val MENU_LABEL = "☰ Menu"
        /** Never leave the loading screen over a running game for good. */
        private const val LOADING_SAFETY_MS = 60_000L
        /** D161. */
        const val CIA_TEXT = "This game is a .cia file (an installer), which Shoalcade can't play. Convert it to .cci or .3ds " +
            "(for example with Azahar on a computer) and put that in your Jellyfin library instead."
        private const val SCREENS = "3DS screens"
        /** Face-button centre to the middle, in button radii (other consoles: 1.45, touching on the diagonal). */
        private const val FACE_SPREAD = 1.6f
        private const val ID_SWAP = "n3ds.swap"
        /** Touch-pad code for the landscape Flip button (never a real Android key code, never sent to the core). */
        private const val FLIP = -301
        private fun layoutId(l: N3dsLayout) = "n3ds.${l.key}"
        private const val TOUCH_OFF_HINT = "Touch buttons off. Change this in the menu: Controls."
        private const val TOUCH_HIDDEN_HINT = "Touch buttons hidden. Tap here to show them."
        private const val VULKAN = "vulkan"
        private const val HW_SHADER = "hw_shader"
        private const val SHADER_JIT = "shader_jit"
        private const val ACCURATE_MUL = "accurate_mul"
        private const val DISK_CACHE = "disk_cache"
        private const val SHOW_SPEED = "show_speed"
        /** Dev switches, in screen order, and their starting values (Azahar's own defaults). */
        val SWITCHES = listOf(
            VULKAN to "Vulkan (off = OpenGL)",
            HW_SHADER to "Hardware shaders",
            SHADER_JIT to "Shader JIT",
            ACCURATE_MUL to "Accurate multiplication",
            DISK_CACHE to "Shader cache on disk",
            SHOW_SPEED to "Show speed in the corner",
        )
        val DEFAULTS = mapOf(VULKAN to true, HW_SHADER to true, SHADER_JIT to true, ACCURATE_MUL to true, DISK_CACHE to true,
            SHOW_SPEED to BuildConfig.DEBUG) // the speed readout is a Shoalcade Dev aid
        fun prefs(c: Context): android.content.SharedPreferences = c.getSharedPreferences("dev3ds", Context.MODE_PRIVATE)
    }
}
