package uk.benmarks.shoalcade

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent

/*
 * Bluetooth / USB controllers (D126): which controller button does what, and when the touch buttons hide.
 * Everything here is plain Kotlin (no Android objects), so the rules are unit-tested; GameActivity feeds it events.
 */

/**
 * A button on the game's own pad (the touch buttons, GamepadView). [keyCode] is what the core is sent; directions go as
 * the D-pad. N64 C-buttons ([cx], [cy]) go as the right analogue stick instead (plan-n64 Step 0): their [keyCode] is a
 * made-up negative code (never a real Android key code) that only names them on the touch pad and is never sent to the core.
 */
enum class GameButton(val label: String, val keyCode: Int, val dx: Int = 0, val dy: Int = 0, val cx: Int = 0, val cy: Int = 0) {
    UP("Up", KeyEvent.KEYCODE_DPAD_UP, dy = -1),
    DOWN("Down", KeyEvent.KEYCODE_DPAD_DOWN, dy = 1),
    LEFT("Left", KeyEvent.KEYCODE_DPAD_LEFT, dx = -1),
    RIGHT("Right", KeyEvent.KEYCODE_DPAD_RIGHT, dx = 1),
    A("A", KeyEvent.KEYCODE_BUTTON_A),
    B("B", KeyEvent.KEYCODE_BUTTON_B),
    X("X", KeyEvent.KEYCODE_BUTTON_X),
    Y("Y", KeyEvent.KEYCODE_BUTTON_Y),
    L("L", KeyEvent.KEYCODE_BUTTON_L1),
    R("R", KeyEvent.KEYCODE_BUTTON_R1),
    /** PS1 only (D135). Added after the others, so stored maps (which name buttons) read as before. */
    L2("L2", KeyEvent.KEYCODE_BUTTON_L2),
    R2("R2", KeyEvent.KEYCODE_BUTTON_R2),
    START("Start", KeyEvent.KEYCODE_BUTTON_START),
    SELECT("Select", KeyEvent.KEYCODE_BUTTON_SELECT),
    /**
     * N64 only (plan-n64), after the others so stored maps read as before. The core's default map (Step 0): N64 A = RetroPad
     * B, N64 B = RetroPad Y, Z = L2; L, R, Start and the D-pad as named. C-buttons = the right stick.
     */
    N64_A("A", KeyEvent.KEYCODE_BUTTON_B),
    N64_B("B", KeyEvent.KEYCODE_BUTTON_Y),
    Z("Z", KeyEvent.KEYCODE_BUTTON_L2),
    C_UP("C▲", -101, cy = -1),
    C_DOWN("C▼", -102, cy = 1),
    C_LEFT("C◀", -103, cx = -1),
    C_RIGHT("C▶", -104, cx = 1),
    ;

    val isDirection get() = dx != 0 || dy != 0

    /** N64 C-button: sent as the right stick pushed fully its way, never as a key. */
    val isCButton get() = cx != 0 || cy != 0

    /**
     * Name on screen for this console: PSP and PS1 face buttons by their symbols, as on the touch buttons
     * (by position on the libretro pad: △ = X, ○ = A, ✕ = B, □ = Y). PS1 shoulders are L1/R1/L2/R2.
     */
    fun labelFor(console: Console): String = if (console == Console.N3DS) when (this) { L2 -> "ZL"; R2 -> "ZR"; else -> label }
    else if (console != Console.PSP && console != Console.PSX) label else when (this) {
        X -> "△"; A -> "○"; B -> "✕"; Y -> "□"
        L -> if (console == Console.PSX) "L1" else label
        R -> if (console == Console.PSX) "R1" else label
        else -> label
    }

    companion object {
        private val DPAD = listOf(UP, DOWN, LEFT, RIGHT)

        /** The C-button with touch-pad code [code], or null if it isn't one. */
        fun cButton(code: Int): GameButton? = entries.firstOrNull { it.isCButton && it.keyCode == code }

        /** The buttons the touch pad has for [console] (same set as GamepadView), in the order the Controls screen lists them. */
        fun forConsole(console: Console): List<GameButton> = DPAD + when (console) {
            Console.GB, Console.GBC -> listOf(A, B, START, SELECT)
            Console.GBA -> listOf(A, B, L, R, START, SELECT)
            Console.PSX -> listOf(X, A, B, Y, L, R, L2, R2, START, SELECT) // △ ○ ✕ □ L1 R1 L2 R2
            Console.N64 -> listOf(N64_A, N64_B, Z, L, R, START, C_UP, C_DOWN, C_LEFT, C_RIGHT) // no Select on an N64
            Console.N3DS -> listOf(A, B, X, Y, L, R, L2, R2, START, SELECT) // L2/R2 = ZL/ZR (plan-3ds step 5)
            else -> listOf(A, B, X, Y, L, R, START, SELECT) // DS, PSP
        }
    }
}

/** One thing on a controller: a key, or one direction of an axis (stick, hat or trigger). */
sealed class PadInput {
    data class Key(val code: Int) : PadInput()
    /** [positive] = the + direction (right / down on sticks and hats). */
    data class Axis(val axis: Int, val positive: Boolean) : PadInput()

    /**
     * This input plus the other way the same D-pad press can arrive: some controllers send the D-pad as keys, others
     * as the hat, so "D-pad Up" is one button whichever it is.
     */
    val twins: List<PadInput> get() {
        val pairs = listOf(
            KeyEvent.KEYCODE_DPAD_UP to Axis(MotionEvent.AXIS_HAT_Y, false), KeyEvent.KEYCODE_DPAD_DOWN to Axis(MotionEvent.AXIS_HAT_Y, true),
            KeyEvent.KEYCODE_DPAD_LEFT to Axis(MotionEvent.AXIS_HAT_X, false), KeyEvent.KEYCODE_DPAD_RIGHT to Axis(MotionEvent.AXIS_HAT_X, true))
        val twin = when (this) {
            is Key -> pairs.firstOrNull { it.first == code }?.second
            is Axis -> pairs.firstOrNull { it.second == this }?.let { Key(it.first) }
        }
        return listOfNotNull(this, twin)
    }

    /** A stick (not the hat, which is the D-pad on most controllers). */
    val isStick get() = this is Axis && axis in STICK_AXES

    /** Short name for the screen, e.g. "Button A", "L1", "D-pad Up", "Left stick ←". */
    val name: String get() = when (this) {
        is Key -> KEY_NAMES[code] ?: if (code in KeyEvent.KEYCODE_BUTTON_1..KeyEvent.KEYCODE_BUTTON_16)
            "Button ${code - KeyEvent.KEYCODE_BUTTON_1 + 1}" else "Key $code"
        is Axis -> AXIS_NAMES[axis]?.let { if (positive) it.second else it.first } ?: "Axis $axis ${if (positive) "+" else "−"}"
    }

    /** Stored form: "k96", "a0-", "a15+". */
    val stored: String get() = when (this) {
        is Key -> "k$code"
        is Axis -> "a$axis${if (positive) "+" else "-"}"
    }

    companion object {
        fun parse(s: String): PadInput? {
            // L13: toIntOrNull, so a huge number in a damaged saved map is dropped instead of crashing game start.
            Regex("^k(\\d+)$").find(s)?.let { return it.groupValues[1].toIntOrNull()?.let(::Key) }
            Regex("^a(\\d+)([+-])$").find(s)?.let { m ->
                return m.groupValues[1].toIntOrNull()?.let { Axis(it, m.groupValues[2] == "+") }
            }
            return null
        }

        val STICK_AXES = setOf(MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ,
            MotionEvent.AXIS_RX, MotionEvent.AXIS_RY)

        /** Axes a controller button can be set to (Controls screen listening). */
        val BINDABLE_AXES = listOf(MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y, MotionEvent.AXIS_X, MotionEvent.AXIS_Y,
            MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ, MotionEvent.AXIS_RX, MotionEvent.AXIS_RY,
            MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_BRAKE, MotionEvent.AXIS_GAS)

        private val KEY_NAMES = mapOf(
            KeyEvent.KEYCODE_BUTTON_A to "Button A", KeyEvent.KEYCODE_BUTTON_B to "Button B",
            KeyEvent.KEYCODE_BUTTON_C to "Button C", KeyEvent.KEYCODE_BUTTON_X to "Button X",
            KeyEvent.KEYCODE_BUTTON_Y to "Button Y", KeyEvent.KEYCODE_BUTTON_Z to "Button Z",
            KeyEvent.KEYCODE_BUTTON_L1 to "L1", KeyEvent.KEYCODE_BUTTON_R1 to "R1",
            KeyEvent.KEYCODE_BUTTON_L2 to "L2", KeyEvent.KEYCODE_BUTTON_R2 to "R2",
            KeyEvent.KEYCODE_BUTTON_THUMBL to "L3 (left stick press)", KeyEvent.KEYCODE_BUTTON_THUMBR to "R3 (right stick press)",
            KeyEvent.KEYCODE_BUTTON_START to "Start", KeyEvent.KEYCODE_BUTTON_SELECT to "Select",
            KeyEvent.KEYCODE_BUTTON_MODE to "Home",
            KeyEvent.KEYCODE_DPAD_UP to "D-pad Up", KeyEvent.KEYCODE_DPAD_DOWN to "D-pad Down",
            KeyEvent.KEYCODE_DPAD_LEFT to "D-pad Left", KeyEvent.KEYCODE_DPAD_RIGHT to "D-pad Right",
            KeyEvent.KEYCODE_DPAD_CENTER to "D-pad centre",
        )
        private val AXIS_NAMES = mapOf(
            MotionEvent.AXIS_HAT_X to ("D-pad Left" to "D-pad Right"), MotionEvent.AXIS_HAT_Y to ("D-pad Up" to "D-pad Down"),
            MotionEvent.AXIS_X to ("Left stick ←" to "Left stick →"), MotionEvent.AXIS_Y to ("Left stick ↑" to "Left stick ↓"),
            MotionEvent.AXIS_Z to ("Right stick ←" to "Right stick →"), MotionEvent.AXIS_RZ to ("Right stick ↑" to "Right stick ↓"),
            // L14: RX/RY are the right stick on some controllers and the triggers on others, so they get their own names.
            MotionEvent.AXIS_RX to ("Axis RX −" to "Axis RX +"), MotionEvent.AXIS_RY to ("Axis RY −" to "Axis RY +"),
            MotionEvent.AXIS_LTRIGGER to ("L2 (back)" to "L2"), MotionEvent.AXIS_RTRIGGER to ("R2 (back)" to "R2"),
            MotionEvent.AXIS_BRAKE to ("L2 (back)" to "L2"), MotionEvent.AXIS_GAS to ("R2 (back)" to "R2"),
        )
    }
}

/** Keys that always open the in-game menu. They can never be given to a game button, so nobody can lock themselves out. */
object MenuKeys {
    val CODES = setOf(KeyEvent.KEYCODE_BUTTON_MODE, KeyEvent.KEYCODE_BACK)
    fun isMenu(keyCode: Int) = keyCode in CODES
}

/** Same set as Android's KeyEvent.isGamepadButton (A..MODE and BUTTON_1..16), without needing Android. */
internal fun isGamepadButton(code: Int) =
    code in KeyEvent.KEYCODE_BUTTON_A..KeyEvent.KEYCODE_BUTTON_MODE || code in KeyEvent.KEYCODE_BUTTON_1..KeyEvent.KEYCODE_BUTTON_16

/**
 * Which controller inputs press each game button, for one console and one controller model.
 * Defaults are exactly the app's behaviour before D126: buttons by name, the D-pad keys and hat as the D-pad, and the
 * left stick as the D-pad too (except PSP, whose left stick is a real analogue stick).
 */
class ControllerMap(val console: Console, private val binds: Map<GameButton, List<PadInput>>) {

    val buttons: List<GameButton> get() = GameButton.forConsole(console)

    fun inputs(b: GameButton): List<PadInput> = binds[b].orEmpty()

    /** "D-pad Up / Left stick ↑", or "Not set". Names that read the same (D-pad key and hat) are shown once. */
    fun describe(b: GameButton): String = inputs(b).map { it.name }.distinct().joinToString(" / ").ifEmpty { NOT_SET }

    val isDefault: Boolean get() = binds == defaults(console).binds

    /**
     * Gives [input] to [button]. One input does one thing: it is taken off any other button first ([Assigned.takenFrom]).
     * A direction keeps its stick when it gets a new button, and keeps its buttons when it gets a stick (as the browser does).
     * The menu keys are refused.
     */
    fun assign(button: GameButton, input: PadInput): Assigned {
        if (input is PadInput.Key && MenuKeys.isMenu(input.code)) return Assigned(this, refused = true)
        val same = input.twins
        val out = binds.toMutableMap()
        val taken = mutableListOf<GameButton>()
        for ((b, list) in binds) {
            if (b != button && list.any { it in same }) { out[b] = list - same.toSet(); taken += b }
        }
        val keep = if (button.isDirection) inputs(button).filter { it.isStick != input.isStick && it !in same } else emptyList()
        out[button] = same + keep
        return Assigned(ControllerMap(console, out), takenFrom = taken)
    }

    data class Assigned(val map: ControllerMap, val takenFrom: List<GameButton> = emptyList(), val refused: Boolean = false)

    /** Stored form: "A=k96;B=k97;UP=k19,a16-,a1-". Only this console's buttons. */
    fun encode(): String = buttons.joinToString(";") { b -> b.name + "=" + inputs(b).joinToString(",") { it.stored } }

    companion object {
        const val NOT_SET = "Not set"

        fun defaults(console: Console): ControllerMap {
            val stick = !console.hasAnalogueStick // PSP, N64: the left stick is the analogue stick, not the D-pad
            fun dir(key: Int, hat: Int, hatPlus: Boolean, axis: Int) = listOfNotNull(
                PadInput.Key(key), PadInput.Axis(hat, hatPlus), if (stick) PadInput.Axis(axis, hatPlus) else null)
            val m = GameButton.forConsole(console).associateWith { b ->
                when (b) {
                    GameButton.UP -> dir(KeyEvent.KEYCODE_DPAD_UP, MotionEvent.AXIS_HAT_Y, false, MotionEvent.AXIS_Y)
                    GameButton.DOWN -> dir(KeyEvent.KEYCODE_DPAD_DOWN, MotionEvent.AXIS_HAT_Y, true, MotionEvent.AXIS_Y)
                    GameButton.LEFT -> dir(KeyEvent.KEYCODE_DPAD_LEFT, MotionEvent.AXIS_HAT_X, false, MotionEvent.AXIS_X)
                    GameButton.RIGHT -> dir(KeyEvent.KEYCODE_DPAD_RIGHT, MotionEvent.AXIS_HAT_X, true, MotionEvent.AXIS_X)
                    // PS1 (D135): L2/R2 are real game buttons; many controllers send them as triggers (axes), not keys.
                    GameButton.L2 -> listOf(PadInput.Key(b.keyCode), PadInput.Axis(MotionEvent.AXIS_LTRIGGER, true))
                    GameButton.R2 -> listOf(PadInput.Key(b.keyCode), PadInput.Axis(MotionEvent.AXIS_RTRIGGER, true))
                    // N64 (plan-n64): face buttons by position (south = A, west = B), either trigger = Z (as keys and as
                    // axes), the right stick = the C-buttons. The left stick is the N64 stick ([PadTranslator]).
                    GameButton.N64_A -> listOf(PadInput.Key(KeyEvent.KEYCODE_BUTTON_A))
                    GameButton.N64_B -> listOf(PadInput.Key(KeyEvent.KEYCODE_BUTTON_X))
                    GameButton.Z -> listOf(PadInput.Key(KeyEvent.KEYCODE_BUTTON_L2), PadInput.Axis(MotionEvent.AXIS_LTRIGGER, true),
                        PadInput.Key(KeyEvent.KEYCODE_BUTTON_R2), PadInput.Axis(MotionEvent.AXIS_RTRIGGER, true))
                    GameButton.C_UP -> listOf(PadInput.Axis(MotionEvent.AXIS_RZ, false))
                    GameButton.C_DOWN -> listOf(PadInput.Axis(MotionEvent.AXIS_RZ, true))
                    GameButton.C_LEFT -> listOf(PadInput.Axis(MotionEvent.AXIS_Z, false))
                    GameButton.C_RIGHT -> listOf(PadInput.Axis(MotionEvent.AXIS_Z, true))
                    else -> listOf(PadInput.Key(b.keyCode))
                }
            }
            return ControllerMap(console, m)
        }

        /** Reads [encode]d text; anything missing or unreadable falls back to the default for that button. */
        fun decode(console: Console, text: String?): ControllerMap {
            val def = defaults(console)
            if (text.isNullOrBlank()) return def
            val saved = text.split(';').mapNotNull { part ->
                val eq = part.indexOf('=')
                if (eq < 0) return@mapNotNull null
                val b = runCatching { GameButton.valueOf(part.substring(0, eq)) }.getOrNull() ?: return@mapNotNull null
                b to part.substring(eq + 1).split(',').filter { it.isNotEmpty() }.mapNotNull { PadInput.parse(it) }
                    .filterNot { it is PadInput.Key && MenuKeys.isMenu(it.code) }
            }.toMap()
            return ControllerMap(console, def.buttons.associateWith { saved[it] ?: def.inputs(it) })
        }
    }
}

/** What the game should be sent. */
sealed class PadOut {
    data class Key(val keyCode: Int, val down: Boolean) : PadOut()
    data class Dpad(val x: Int, val y: Int) : PadOut()
    /** PSP / N64 analogue stick, -1..1. */
    data class Stick(val x: Float, val y: Float) : PadOut()
    /** N64 C-buttons as the right stick: -1, 0 or 1 each way (y -1 = C-up). */
    data class CStick(val x: Float, val y: Float) : PadOut()
    object Menu : PadOut()
}

/**
 * Turns one controller's keys and axes into game input through its [ControllerMap]. Keeps what is held and sends only
 * changes. A controller key that no game button uses, and that isn't one of this console's default keys, goes to the
 * game unchanged (e.g. L2/R2/L3/R3 on DS), exactly as before D126. On PS1, L2/R2 are game buttons (default keys), so
 * they are never passed through: they go wherever the map sends them. N64 passes nothing through: its core reads raw
 * RetroPad buttons as other N64 buttons (and R2 held turns A/B/X/Y into C-buttons), so only mapped buttons count.
 */
class PadTranslator(map: ControllerMap) {
    var map: ControllerMap = map
        set(v) { field = v; rebuild() }

    private val heldKeys = mutableSetOf<Int>()
    private val axes = mutableMapOf<Int, Float>()
    private var sentButtons = setOf<GameButton>()
    private var sentRaw = setOf<Int>()
    private var sentDpad = 0 to 0
    private var sentStick = 0f to 0f
    private var sentC = 0f to 0f
    private lateinit var bound: Set<PadInput>
    private lateinit var defaultKeys: Set<Int>
    private var analogue = false

    init { rebuild() }

    private fun rebuild() {
        bound = map.buttons.flatMap { map.inputs(it) }.toSet()
        defaultKeys = ControllerMap.defaults(map.console).let { d -> d.buttons.flatMap { d.inputs(it) } }
            .filterIsInstance<PadInput.Key>().map { it.code }.toSet()
        // PSP, N64: the left stick is the analogue stick, unless a button has been given to it.
        analogue = map.console.hasAnalogueStick && bound.none { it is PadInput.Axis && (it.axis == MotionEvent.AXIS_X || it.axis == MotionEvent.AXIS_Y) }
    }

    /** A key went down or up ([repeat] = held-key repeat, ignored). */
    fun key(code: Int, down: Boolean, repeat: Int = 0): List<PadOut> {
        if (MenuKeys.isMenu(code)) return if (down && repeat == 0) listOf(PadOut.Menu) else emptyList()
        if (down && repeat > 0) return emptyList()
        if (down) heldKeys += code else heldKeys -= code
        return resolve()
    }

    /** New axis values from one motion event (axes not given keep their last value). */
    fun motion(values: Map<Int, Float>): List<PadOut> {
        axes.putAll(values)
        return resolve()
    }

    /** Lets go of everything (e.g. the Controls screen opened, or the controller went away). */
    fun releaseAll(): List<PadOut> { heldKeys.clear(); axes.clear(); return resolve() }

    private fun active(i: PadInput): Boolean = when (i) {
        is PadInput.Key -> i.code in heldKeys
        is PadInput.Axis -> (axes[i.axis] ?: 0f).let { if (i.positive) it > THRESHOLD else it < -THRESHOLD }
    }

    private fun resolve(): List<PadOut> {
        val out = mutableListOf<PadOut>()
        val pressed = map.buttons.filter { b -> map.inputs(b).any { active(it) } }.toSet()
        val raw = if (map.console == Console.N64) emptySet() else
            heldKeys.filter { isGamepadButton(it) && PadInput.Key(it) !in bound && it !in defaultKeys }.toSet()

        for (b in pressed - sentButtons) if (!b.isDirection && !b.isCButton) out += PadOut.Key(b.keyCode, true)
        for (b in sentButtons - pressed) if (!b.isDirection && !b.isCButton) out += PadOut.Key(b.keyCode, false)
        for (k in raw - sentRaw) out += PadOut.Key(k, true)
        for (k in sentRaw - raw) out += PadOut.Key(k, false)
        sentButtons = pressed; sentRaw = raw

        val x = (if (GameButton.RIGHT in pressed) 1 else 0) - (if (GameButton.LEFT in pressed) 1 else 0)
        val y = (if (GameButton.DOWN in pressed) 1 else 0) - (if (GameButton.UP in pressed) 1 else 0)
        if ((x to y) != sentDpad) { sentDpad = x to y; out += PadOut.Dpad(x, y) }

        val c = CStick.vector(pressed)
        if (c != sentC) { sentC = c; out += PadOut.CStick(c.first, c.second) }

        if (analogue) {
            fun dz(v: Float) = if (kotlin.math.abs(v) < DEAD_ZONE) 0f else v
            val s = dz(axes[MotionEvent.AXIS_X] ?: 0f) to dz(axes[MotionEvent.AXIS_Y] ?: 0f)
            if (s != sentStick) { sentStick = s; out += PadOut.Stick(s.first, s.second) }
        }
        return out
    }

    companion object {
        /** How far a stick, hat or trigger must go to count as pressed (same as before D126). */
        const val THRESHOLD = 0.5f
        const val DEAD_ZONE = 0.15f
        /** Axes [motion] needs: everything a button could be set to. */
        val AXES = PadInput.BINDABLE_AXES
    }
}

/**
 * N64 C-buttons as the right stick (plan-n64 Step 0: the core's default C-button mode, which also matches the browser's
 * EmulatorJS ids 20-23). Each held C-button pushes the stick fully its way; opposite ones cancel. The touch pad uses
 * [press]; [PadTranslator] uses [vector] on the buttons a controller holds.
 */
class CStick {
    private val held = mutableSetOf<GameButton>()
    private var sent = 0f to 0f

    /** A touch C-button went down or up. The new stick position if it changed, else null. */
    fun press(b: GameButton, down: Boolean): Pair<Float, Float>? {
        if (!b.isCButton) return null
        if (down) held += b else held -= b
        val v = vector(held)
        if (v == sent) return null
        sent = v
        return v
    }

    companion object {
        fun vector(pressed: Collection<GameButton>): Pair<Float, Float> {
            val c = pressed.filter { it.isCButton }
            return c.sumOf { it.cx }.coerceIn(-1, 1).toFloat() to c.sumOf { it.cy }.coerceIn(-1, 1).toFloat()
        }
    }
}

/**
 * Which player (core port 0-3) each controller plays as, for consoles with more than one pad (N64). The first
 * controller is player 1 (port 0, shared with the touch buttons), then 2, 3, 4 in the order they connect. A controller
 * that goes away keeps its port, so reconnecting it gives the same one back; a new controller takes a never-used port,
 * else the one freed longest ago. When every port has a connected controller, more get none (their input is ignored).
 * An id is one physical controller (its input-device descriptor), not its model. Single-player consoles don't use this.
 */
class PlayerPorts(val ports: Int) {
    private val owner = arrayOfNulls<String>(ports)
    private val connected = mutableSetOf<String>()
    /** Ports freed by a disconnect, oldest first. */
    private val freed = ArrayDeque<Int>()

    /** The port of connected controller [id], or null. */
    fun portOf(id: String): Int? = owner.indexOf(id).takeIf { it >= 0 && id in connected }

    /** [id] connected (or sent input before we heard it connect): its port, or null if every port is in use. */
    fun connect(id: String): Int? {
        val had = owner.indexOf(id)
        if (had >= 0) { connected += id; freed.remove(had); return had }
        val port = owner.indexOfFirst { it == null }.takeIf { it >= 0 } ?: freed.removeFirstOrNull() ?: return null
        owner[port] = id
        connected += id
        return port
    }

    fun disconnect(id: String) {
        val port = owner.indexOf(id)
        if (port < 0 || !connected.remove(id)) return
        freed.addLast(port)
    }
}

/**
 * Controls screen "press a button" step. Takes the first new key, or an axis that moves well away from where it was
 * resting when listening began (some triggers rest at -1). Menu keys cancel, never bind.
 */
class BindListener(private val restingAxes: Map<Int, Float>) {
    sealed class Result {
        data class Bound(val input: PadInput) : Result()
        object Cancelled : Result()
        object Waiting : Result()
    }

    fun key(code: Int, down: Boolean, repeat: Int = 0): Result = when {
        !down || repeat > 0 -> Result.Waiting
        MenuKeys.isMenu(code) -> Result.Cancelled
        else -> Result.Bound(PadInput.Key(code))
    }

    fun motion(values: Map<Int, Float>): Result {
        for (axis in PadInput.BINDABLE_AXES) {
            val v = values[axis] ?: continue
            val rest = restingAxes[axis] ?: 0f
            if (kotlin.math.abs(v) < PRESS) continue
            val positive = v > 0
            // Already pushed that way when listening began (e.g. a trigger resting at -1): not a new press.
            if (kotlin.math.abs(rest) >= 0.3f && (rest > 0) == positive) continue
            return Result.Bound(PadInput.Axis(axis, positive))
        }
        return Result.Waiting
    }

    companion object { const val PRESS = 0.6f; const val TIMEOUT_MS = 10_000L }
}

/**
 * G6 M3: tells real stick / trigger movement from an axis sitting where it rests (some triggers rest at -1, which
 * used to count as "moved" on every event and kept re-hiding the touch buttons). The first value seen for each
 * (controller, axis) is its rest. An axis has moved when it goes from within [threshold] of its rest to beyond it.
 * Only that step counts, not every event while it stays away: a stick or trigger held when first seen (rest taken
 * wrongly) then let go sits away from that "rest", and its jitter used to fire on every event (F1: the touch
 * buttons flashed back and hid again straight away).
 */
class AxisRest(private val threshold: Float = PadTranslator.THRESHOLD) {
    private val rest = mutableMapOf<Int, MutableMap<Int, Float>>()
    private val last = mutableMapOf<Int, MutableMap<Int, Float>>()

    /** New axis values from [device]. True = a real movement (not an axis sitting at its rest). */
    fun update(device: Int, values: Map<Int, Float>): Boolean {
        val r = rest.getOrPut(device) { mutableMapOf() }
        val seen = last.getOrPut(device) { mutableMapOf() }
        var moved = false
        for ((axis, v) in values) {
            val restAt = r.getOrPut(axis) { v }
            val before = seen.put(axis, v)
            if (before != null && kotlin.math.abs(before - restAt) <= threshold && kotlin.math.abs(v - restAt) > threshold) moved = true
        }
        return moved
    }

    /** The latest value of each axis on [device] (where they rest now, for the Controls screen's listener). */
    fun last(device: Int): Map<Int, Float> = last[device]?.toMap() ?: emptyMap()

    /** [device] went away: a new one with the same id starts fresh. */
    fun forget(device: Int) { rest.remove(device); last.remove(device) }
}

/** Menu option "Touch buttons". Stored by [key]. */
enum class TouchMode(val key: String, val label: String) {
    AUTO("auto", "Auto"), SHOW("show", "Always show"), HIDE("hide", "Always hide");

    companion object { fun fromKey(k: String?) = entries.firstOrNull { it.key == k } ?: AUTO }
}

/**
 * Whether the touch buttons are showing. Auto: hidden while a controller is connected or after any controller input;
 * shown again when the last controller disconnects or the player touches the screen (that touch never presses anything).
 */
class TouchAutoHide(mode: TouchMode = TouchMode.AUTO) {
    var mode: TouchMode = mode
        set(v) { field = v; if (v == TouchMode.AUTO) hiddenByController = controllers.isNotEmpty() }
    private val controllers = mutableSetOf<Int>()
    private var hiddenByController = false

    val visible: Boolean get() = when (mode) {
        TouchMode.SHOW -> true
        TouchMode.HIDE -> false
        TouchMode.AUTO -> !hiddenByController
    }

    /** Controllers already connected when the game starts, or one just connected. */
    fun controllerConnected(id: Int) { if (controllers.add(id)) hiddenByController = true }

    fun controllerDisconnected(id: Int) {
        if (controllers.remove(id) && controllers.isEmpty()) hiddenByController = false
    }

    fun controllerInput() { hiddenByController = true }

    /**
     * A finger went down on the screen. True = this touch only brings the buttons back and must not reach the game
     * or the buttons (Auto, while hidden).
     */
    fun screenTouched(): Boolean {
        if (mode != TouchMode.AUTO || !hiddenByController) return false
        hiddenByController = false
        return true
    }
}

/** Key for "this controller model": vendor:product when the controller reports them, else its descriptor, else its name. */
fun controllerKey(vendorId: Int, productId: Int, descriptor: String?, name: String?): String = when {
    vendorId != 0 || productId != 0 -> "%04x:%04x".format(vendorId, productId)
    !descriptor.isNullOrBlank() -> "d:$descriptor"
    else -> "n:${name.orEmpty()}"
}

/**
 * Controls settings kept on the phone (offline first, D126): touch buttons mode and maps, both per console
 * (maps also per controller). The Settings screen and the in-game menu both read and write through this, so they agree.
 */
class ControlPrefs(private val store: SyncStore) {
    /**
     * Touch buttons for [console] (D132: per console; D126 had one app-wide value under "touch"). A console never set
     * on its own starts from that old app-wide value, which is only ever read, never changed or removed, so the
     * migration needs no copying step: it can't half-finish, run twice wrongly, or lose the old choice.
     */
    fun touchMode(console: Console): TouchMode =
        TouchMode.fromKey(store.get(touchKey(console)) ?: store.get(LEGACY_TOUCH))

    fun setTouchMode(console: Console, mode: TouchMode) = store.set(touchKey(console), mode.key)

    private fun touchKey(console: Console) = "touch.${console.key}"

    fun map(console: Console, controller: String): ControllerMap = ControllerMap.decode(console, store.get(mapKey(console, controller)))

    /** Saves [map]; a map equal to the defaults is removed rather than stored. */
    fun setMap(controller: String, map: ControllerMap) =
        store.set(mapKey(map.console, controller), if (map.isDefault) null else map.encode())

    fun reset(console: Console, controller: String) = store.set(mapKey(console, controller), null)

    private fun mapKey(console: Console, controller: String) = "map.${console.key}.$controller"

    companion object {
        /** D126's app-wide touch buttons mode: read as every console's starting value, never written (D132). */
        const val LEGACY_TOUCH = "touch"

        fun of(ctx: Context) = ControlPrefs(object : SyncStore {
            private val prefs = ctx.applicationContext.getSharedPreferences("controls", Context.MODE_PRIVATE)
            override fun get(key: String): String? = prefs.getString(key, null)
            override fun set(key: String, value: String?) {
                prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
            }
        })
    }
}
