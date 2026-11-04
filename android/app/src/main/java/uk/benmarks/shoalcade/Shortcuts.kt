package uk.benmarks.shoalcade

import android.view.KeyEvent
import android.view.MotionEvent

/**
 * D247 Controller shortcuts, the same four as the Windows app: hold **Select** on the controller, then
 * R1 = quick snapshot (the phone's one snapshot, the Windows app's F2 slot 1), L1 = load it (through the usual
 * "Load snapshot?" question and save backup), R2 = fast-forward on / off, L2 = screenshot.
 * They are the controller's own buttons, whatever the Controls screen maps them to. The switch is kept on the phone in
 * the plain SharedPreferences file `display` (key `shortcuts`, no secrets), like Haptics; read when a game starts.
 */
internal object ControllerShortcuts {
    private const val KEY = "shortcuts"

    /** On unless switched off; anything but "0" in the file reads as on. */
    fun on(store: SyncStore): Boolean = store.get(KEY) != "0"
    fun setOn(store: SyncStore, on: Boolean) = store.set(KEY, if (on) null else "0")

    // What each shortcut says when it can't be done (the done messages come from the snapshot and screenshot code).
    const val NO_SNAPSHOTS = "Snapshots are off for this game."
    const val NO_SNAPSHOT = "No snapshot to load yet."
    const val NO_FAST_FORWARD = "Fast-forward isn't available for this game."
    fun fastForward(on: Boolean) = if (on) "Fast-forward on" else "Fast-forward off"
}

/**
 * One shortcut: the controller key that fires it while Select is held, and for L2 / R2 the trigger axes too (many
 * controllers send their triggers as axes, some as keys, some both: either way it is one press).
 */
enum class PadShortcut(val keyCode: Int, val axes: Set<Int> = emptySet()) {
    QUICK_SAVE(KeyEvent.KEYCODE_BUTTON_R1),
    QUICK_LOAD(KeyEvent.KEYCODE_BUTTON_L1),
    FAST_FORWARD(KeyEvent.KEYCODE_BUTTON_R2, setOf(MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS)),
    SCREENSHOT(KeyEvent.KEYCODE_BUTTON_L2, setOf(MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE)),
    ;

    companion object {
        fun ofKey(code: Int): PadShortcut? = entries.firstOrNull { it.keyCode == code }
        private val AXES = entries.flatMap { it.axes }.toSet()
        fun isTriggerAxis(axis: Int) = axis in AXES
    }
}

/**
 * Spots the shortcuts on one controller, in front of its [PadTranslator]. Pure Kotlin, so it is unit tested.
 *
 * - A shortcut fires when its button goes down while Select is held (pressing Select after the button does nothing).
 * - That button's press never reaches the game: its key events and trigger axes are kept back until it is let go,
 *   even if Select is let go first. Everything else, Select included, goes to the game as normal.
 * - [reset] forgets everything (the menu opened, a dialog took the keys, the controller went away).
 */
class ShortcutPad {
    private var select = false
    private val keys = mutableSetOf<Int>()
    private val axes = mutableMapOf<Int, Float>()
    /** Shortcut buttons held now. */
    private var held = setOf<PadShortcut>()
    /** Buttons that fired a shortcut and are still held: kept from the game. */
    private val eaten = mutableSetOf<PadShortcut>()

    /** [pass] = the key still goes to the game; [fired] = the shortcut it set off, if any. */
    data class KeyResult(val pass: Boolean, val fired: PadShortcut? = null)

    /** [values] = the axis values to give the game (a held shortcut trigger reads as let go); [fired] = shortcuts set off. */
    data class MotionResult(val values: Map<Int, Float>, val fired: List<PadShortcut>)

    fun key(code: Int, down: Boolean): KeyResult {
        if (code == KeyEvent.KEYCODE_BUTTON_SELECT) { select = down; return KeyResult(true) }
        val s = PadShortcut.ofKey(code) ?: return KeyResult(true)
        val before = eaten.toSet()
        if (down) keys += code else keys -= code
        val fired = update()
        return KeyResult(pass = s !in before && s !in eaten, fired = fired.firstOrNull())
    }

    fun motion(values: Map<Int, Float>): MotionResult {
        val before = eaten.toSet()
        values.forEach { (a, v) -> if (PadShortcut.isTriggerAxis(a)) axes[a] = v }
        val fired = update()
        val hidden = (before + eaten).flatMap { it.axes }.toSet()
        return MotionResult(if (hidden.isEmpty()) values else values.mapValues { (a, v) -> if (a in hidden) 0f else v }, fired)
    }

    fun reset() { select = false; keys.clear(); axes.clear(); held = emptySet(); eaten.clear() }

    private fun pressed(s: PadShortcut) = s.keyCode in keys || s.axes.any { (axes[it] ?: 0f) > PadTranslator.THRESHOLD }

    private fun update(): List<PadShortcut> {
        val now = PadShortcut.entries.filter { pressed(it) }.toSet()
        val fired = if (select) (now - held).toList() else emptyList()
        eaten += fired
        eaten.retainAll(now)
        held = now
        return fired
    }
}
