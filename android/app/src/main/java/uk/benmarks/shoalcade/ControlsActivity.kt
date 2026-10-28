package uk.benmarks.shoalcade

import android.content.Context
import android.content.Intent
import android.hardware.input.InputManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent

/** A real, plugged-in or paired controller (not the phone's own keys or a virtual device). */
internal fun isGameController(d: InputDevice?): Boolean = d != null && !d.isVirtual && d.isExternal &&
    (d.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
        d.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK)

/** Which controller model [d] is ([controllerKey]) and its name, for the Controls screen. */
internal fun controllerInfo(d: InputDevice?): ControllerInfo =
    ControllerInfo(controllerKey(d?.vendorId ?: 0, d?.productId ?: 0, d?.descriptor, d?.name), d?.name ?: "Controller")

internal fun isGamepadSource(source: Int) =
    source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
        source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK ||
        source and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD

/**
 * Settings → a console → Controls (D132): the same Controls screen and steps as in a game ([ControlsFlow]), without
 * starting one. Lists the controllers connected now to pick from; with none it says so (the screen's own words) and
 * picks up a controller as soon as one is connected or pressed.
 */
class ControlsActivity : ComponentActivity() {
    private lateinit var flow: ControlsFlow
    private val axisRest = AxisRest()
    private var lastDevice = -1
    private val handler = Handler(Looper.getMainLooper())

    private val deviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(id: Int) = refreshConnected()
        override fun onInputDeviceRemoved(id: Int) { axisRest.forget(id); refreshConnected() }
        override fun onInputDeviceChanged(id: Int) = refreshConnected()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val console = Console.fromKey(intent.getStringExtra(EXTRA_CONSOLE))?.takeIf { it in SettingsModel.CONSOLES }
            ?: run { finish(); return }
        flow = ControlsFlow(ControlsState(console), ControlPrefs.of(this),
            later = { ms, block -> val r = Runnable(block); handler.postDelayed(r, ms); { handler.removeCallbacks(r) } })
        refreshConnected()
        getSystemService(InputManager::class.java)?.registerInputDeviceListener(deviceListener, handler)
        onBackPressedDispatcher.addCallback(this) { if (!flow.back()) finish() }
        setContent { HapticsGate {
            ControlsScreen(flow.state,
                onPick = { flow.startListening(it, axisRest.last(lastDevice)) },
                onReset = { flow.reset() },
                onDone = { finish() },
                title = "${ControlsWords.TITLE}: ${console.shortName}",
                onChoose = { flow.open(it) })
        } }
    }

    override fun onDestroy() {
        if (::flow.isInitialized) {
            flow.stopListening()
            getSystemService(InputManager::class.java)?.unregisterInputDeviceListener(deviceListener)
        }
        super.onDestroy()
    }

    /** Re-reads the connected controllers; the one shown stays unless it has gone. */
    private fun refreshConnected() {
        val list = InputDevice.getDeviceIds().map { InputDevice.getDevice(it) }.filter { isGameController(it) }
            .map { controllerInfo(it) }.distinctBy { it.key }
        val s = flow.state
        s.connected = list
        if (s.device == null || list.none { it.key == s.device?.key }) flow.open(list.firstOrNull())
    }

    // Lint flags super.dispatchKeyEvent on ComponentActivity as restricted; overriding it is the documented way to read keys.
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!::flow.isInitialized || !isGamepadSource(event.source) || !isGameController(event.device)) return super.dispatchKeyEvent(event)
        lastDevice = event.deviceId
        return when (flow.key(controllerInfo(event.device), event.keyCode, event.action == KeyEvent.ACTION_DOWN, event.repeatCount)) {
            ControlsFlow.KeyResult.HANDLED -> true
            ControlsFlow.KeyResult.BACK -> { if (!flow.back()) finish(); true }
            ControlsFlow.KeyResult.PASS_ON -> super.dispatchKeyEvent(event) // D-pad moves around the screen
        }
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (!::flow.isInitialized || !isGamepadSource(event.source) || event.action != MotionEvent.ACTION_MOVE ||
            !isGameController(event.device)) return super.dispatchGenericMotionEvent(event)
        lastDevice = event.deviceId
        val values = PadTranslator.AXES.associateWith { event.getAxisValue(it) }
        val moved = axisRest.update(event.deviceId, values)
        val listening = flow.state.listening != null
        flow.motion(controllerInfo(event.device), values, moved)
        // Not listening: let the stick / hat move around the screen as usual.
        return listening || super.dispatchGenericMotionEvent(event)
    }

    companion object {
        private const val EXTRA_CONSOLE = "console"
        fun intent(ctx: Context, console: Console): Intent =
            Intent(ctx, ControlsActivity::class.java).putExtra(EXTRA_CONSOLE, console.key)
    }
}
