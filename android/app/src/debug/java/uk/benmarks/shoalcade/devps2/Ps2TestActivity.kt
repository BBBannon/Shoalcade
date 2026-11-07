package uk.benmarks.shoalcade.devps2

import android.graphics.Color
import android.hardware.input.InputManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import com.swordfish.libretrodroid.Variable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uk.benmarks.shoalcade.Area
import uk.benmarks.shoalcade.GamepadView
import uk.benmarks.shoalcade.HintSpot
import uk.benmarks.shoalcade.PadButton
import uk.benmarks.shoalcade.PadSpec
import uk.benmarks.shoalcade.isGameController
import java.io.File
import java.util.Locale

/**
 * Shoalcade Dev only: the PS2 spike (to-do "PS2 on the phone"). Plays one PS2 disc with the open-source Play! core
 * (BSD-2, no BIOS needed), shows the speed live and writes a result line to [DevPs2Activity.results] on the way out.
 * Not the real game screen: no library, no save sync. Saves stay inside this test app.
 */
class Ps2TestActivity : ComponentActivity() {

    private lateinit var retroView: GLRetroView
    private lateinit var pad: GamepadView
    private lateinit var speed: TextView
    private lateinit var rom: File
    private var res = "1x"

    // Speed: frames the game draws each second while on screen. Play! hands over a frame only when the game draws one,
    // so a game made for 30 fps shows 30 at full speed (60 for 60 fps games).
    private var frames = 0
    private var started = 0L
    private var countedSecs = 0
    private var countedFrames = 0L
    private var worst = Int.MAX_VALUE
    private val lastFive = ArrayDeque<Int>()
    private var worstFive = Int.MAX_VALUE
    private var onScreen = false

    private val devices = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(id: Int) = showPad()
        override fun onInputDeviceRemoved(id: Int) = showPad()
        override fun onInputDeviceChanged(id: Int) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rom = File(intent.getStringExtra(EXTRA_ROM) ?: run { finish(); return })
        if (!rom.isFile) { finish(); return }
        res = intent.getStringExtra(EXTRA_RES) ?: "1x"

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val base = File(filesDir, "devps2").apply { mkdirs() }
        // Play! keeps its own data ("Play Data Files") under $EXTERNAL_STORAGE, which an app can't write: crash in
        // retro_init. Point it at this test's folder. Safe: this screen runs in its own process (":ps2", manifest).
        android.system.Os.setenv("EXTERNAL_STORAGE", File(base, "data").apply { mkdirs() }.absolutePath, true)
        val core = File(applicationInfo.nativeLibraryDir, CORE).absolutePath
        Ps2Jvm.prime(core)?.let { Log.w(TAG, "prime: $it"); toast("Couldn't set up the Play! core: $it"); finish(); return }
        val data = GLRetroViewData(this).apply {
            coreFilePath = core
            gameFilePath = rom.absolutePath
            systemDirectory = File(base, "system").apply { mkdirs() }.absolutePath
            savesDirectory = File(base, "saves").apply { mkdirs() }.absolutePath
            variables = arrayOf(Variable("play_res_multi", res))
            preferLowLatencyAudio = false
        }
        retroView = GLRetroView(this, data).apply { isFocusable = true; isFocusableInTouchMode = true }
        lifecycle.addObserver(retroView)
        lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onResume(owner: androidx.lifecycle.LifecycleOwner) { onScreen = true }
            override fun onPause(owner: androidx.lifecycle.LifecycleOwner) { onScreen = false }
        })

        pad = GamepadView(this, object : GamepadView.Listener {
            override fun onButton(keyCode: Int, pressed: Boolean) =
                retroView.sendKeyEvent(if (pressed) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, keyCode)
            override fun onDpad(x: Float, y: Float) = retroView.sendMotionEvent(GLRetroView.MOTION_SOURCE_DPAD, x, y)
            override fun onStick(x: Float, y: Float) = retroView.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_LEFT, x, y)
        }, spec = { w, h -> padSpec(w, h) })

        speed = TextView(this).apply {
            setTextColor(Color.WHITE); setBackgroundColor(Color.argb(160, 0, 0, 0)); textSize = 12f
            setPadding(12, 4, 12, 4); text = "PS2 test ($res): starting…"
        }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(retroView, FrameLayout.LayoutParams(0, 0, Gravity.CENTER))
        root.addView(pad, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(speed, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        // The picture: 4:3, as big as fits, centred.
        root.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val w = r - l; val h = b - t
            val pw = minOf(w, h * 4 / 3); val ph = pw * 3 / 4
            val lp = retroView.layoutParams as FrameLayout.LayoutParams
            if (lp.width != pw || lp.height != ph) root.post { retroView.layoutParams = lp.apply { width = pw; height = ph } }
        }
        setContentView(root)

        getSystemService(InputManager::class.java)?.registerInputDeviceListener(devices, Handler(Looper.getMainLooper()))
        showPad()

        lifecycleScope.launch {
            retroView.getGLRetroEvents().collect { if (it is GLRetroView.GLRetroEvents.FrameRendered) frames++ }
        }
        lifecycleScope.launch {
            retroView.getGLRetroErrors().collect { speed.text = "Error $it: the core couldn't start this game"; Log.w(TAG, "error $it") }
        }
        lifecycleScope.launch { tick() }
    }

    /** Once a second: show the speed, keep the session's average and lowest (ignoring the first 15 s of loading). */
    private suspend fun tick() {
        while (true) {
            delay(1000)
            val n = frames; frames = 0
            if (!onScreen || n == 0) continue
            if (started == 0L) started = SystemClock.elapsedRealtime()
            val secs = (SystemClock.elapsedRealtime() - started) / 1000
            if (secs >= WARM_UP) {
                countedSecs++; countedFrames += n; worst = minOf(worst, n)
                lastFive.addLast(n); if (lastFive.size > 5) lastFive.removeFirst()
                if (lastFive.size == 5) worstFive = minOf(worstFive, lastFive.sum() / 5)
            }
            val avg = if (countedSecs > 0) countedFrames / countedSecs else 0
            speed.text = if (countedSecs == 0) "PS2 test ($res): $n fps (loading, counting from ${WARM_UP}s)"
                else "PS2 test ($res): $n fps · average $avg (full speed = 60, or 30 for 30 fps games)"
            Log.i(TAG, "fps=$n avg=$avg secs=$countedSecs")
        }
    }

    private fun showPad() {
        val controller = InputDevice.getDeviceIds().any { isGameController(InputDevice.getDevice(it)) }
        pad.setHidden(controller, null)
    }

    // --- Controller: buttons straight through, sticks/hat as motion, analogue triggers as L2/R2. ---

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
        val pad = event.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            event.source and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD
        if (!pad || event.repeatCount > 0) return super.dispatchKeyEvent(event)
        retroView.sendKeyEvent(event.action, event.keyCode)
        return true
    }

    private var l2 = false
    private var r2 = false

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK || event.action != MotionEvent.ACTION_MOVE) {
            return super.dispatchGenericMotionEvent(event)
        }
        retroView.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_LEFT, event.getAxisValue(MotionEvent.AXIS_X), event.getAxisValue(MotionEvent.AXIS_Y))
        retroView.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_RIGHT, event.getAxisValue(MotionEvent.AXIS_Z), event.getAxisValue(MotionEvent.AXIS_RZ))
        retroView.sendMotionEvent(GLRetroView.MOTION_SOURCE_DPAD, event.getAxisValue(MotionEvent.AXIS_HAT_X), event.getAxisValue(MotionEvent.AXIS_HAT_Y))
        val lt = maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE)) > 0.5f
        val rt = maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS)) > 0.5f
        if (lt != l2) { l2 = lt; retroView.sendKeyEvent(if (lt) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_L2) }
        if (rt != r2) { r2 = rt; retroView.sendKeyEvent(if (rt) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_R2) }
        return true
    }

    /** Landscape touch pad, PS2 shapes: the libretro pad's A/B/X/Y sit where ○/✕/△/□ do. */
    private fun padSpec(w: Int, h: Int): PadSpec {
        val cut = window.decorView.rootWindowInsets?.displayCutout
        val a = (cut?.safeInsetLeft ?: 0).toFloat()
        val W = w - a - (cut?.safeInsetRight ?: 0)
        fun x(f: Float) = a + W * f
        val H = h.toFloat()
        val unit = H * 0.075f
        val pill = unit * 0.55f
        val d = unit * 1.6f
        val fx = x(1f) - unit - d; val fy = H * 0.50f
        val buttons = listOf(
            PadButton("L1", KeyEvent.KEYCODE_BUTTON_L1, x(0.05f), H * 0.08f, pill, wide = true),
            PadButton("L2", KeyEvent.KEYCODE_BUTTON_L2, x(0.14f), H * 0.08f, pill, wide = true),
            PadButton("R2", KeyEvent.KEYCODE_BUTTON_R2, x(0.86f), H * 0.08f, pill, wide = true),
            PadButton("R1", KeyEvent.KEYCODE_BUTTON_R1, x(0.95f), H * 0.08f, pill, wide = true),
            PadButton("△", KeyEvent.KEYCODE_BUTTON_X, fx, fy - d, unit),
            PadButton("○", KeyEvent.KEYCODE_BUTTON_A, fx + d, fy, unit),
            PadButton("✕", KeyEvent.KEYCODE_BUTTON_B, fx, fy + d, unit),
            PadButton("□", KeyEvent.KEYCODE_BUTTON_Y, fx - d, fy, unit),
            PadButton("SELECT", KeyEvent.KEYCODE_BUTTON_SELECT, x(0.83f), H * 0.91f, unit * 0.55f, wide = true),
            PadButton("START", KeyEvent.KEYCODE_BUTTON_START, x(0.94f), H * 0.91f, unit * 0.55f, wide = true),
        )
        val stickR = H * 0.15f
        val m = unit * 0.5f
        return PadSpec(
            buttons,
            dpadCx = a + stickR * 1.1f, dpadCy = H * 0.74f, dpadR = H * 0.14f,
            stickCx = a + stickR * 1.1f, stickCy = H * 0.38f, stickR = stickR,
            textSize = unit * 0.7f,
            menu = Area(x(0.5f) - m, H - m, x(0.5f) + m, H),
            hint = HintSpot(x(0.5f), H * 0.5f, W * 0.5f),
            seeThrough = true,
        )
    }

    override fun onDestroy() {
        getSystemService(InputManager::class.java)?.unregisterInputDeviceListener(devices)
        if (::rom.isInitialized && countedSecs > 0) saveResult()
        super.onDestroy()
    }

    /** One line per session, newest first, shown on the PS2 test screen (and easy to share). */
    private fun saveResult() {
        val avg = countedFrames / countedSecs
        val line = String.format(Locale.UK, "%s · %s · %d s · average %d fps · worst 5 s %d fps · worst second %d fps",
            rom.nameWithoutExtension, res, countedSecs, avg,
            if (worstFive == Int.MAX_VALUE) avg else worstFive, worst)
        runCatching {
            val f = DevPs2Activity.results(this)
            f.writeText(line + "\n" + (if (f.isFile) f.readText() else ""))
        }
        Log.i(TAG, "result: $line")
    }

    private fun toast(t: String) = android.widget.Toast.makeText(this, t, android.widget.Toast.LENGTH_LONG).show()

    companion object {
        const val EXTRA_ROM = "rom"
        const val EXTRA_RES = "res"
        const val CORE = "libplay_libretro_android.so"
        private const val TAG = "ShoalcadePs2Test"
        private const val WARM_UP = 15L
    }
}
