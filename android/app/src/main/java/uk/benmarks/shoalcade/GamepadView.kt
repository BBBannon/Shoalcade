package uk.benmarks.shoalcade

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot

/**
 * On-screen controls, drawn and hit-tested from a [PadSpec] that [spec] works out for the view's size (D128): portrait
 * below the game, PSP see-through over the game with an analogue stick, landscape GB/GBC/GBA/DS in columns beside it.
 * Multi-touch: each finger can hold a different button, and sliding a finger across the D-pad changes direction
 * without lifting.
 *
 * In landscape the view covers the whole screen, so it owns every touch. A finger landing in [PadSpec.hole] (the game
 * picture, e.g. the DS touch screen) is passed to [onHoleTouch] as a single-pointer event in the game view's own
 * coordinates (GLRetroView only reads the first pointer anyway).
 *
 * The ☰ Menu handle is not drawn here: GameActivity places a real view at [PadSpec.menu] (TalkBack can reach it).
 */
@SuppressLint("ViewConstructor")
class GamepadView(
    context: Context,
    private val listener: Listener,
    private val spec: (w: Int, h: Int) -> PadSpec,
) : View(context) {

    interface Listener {
        fun onButton(keyCode: Int, pressed: Boolean)
        fun onDpad(x: Float, y: Float)
        /** Analogue stick, -1..1 each way (y -1 = up). Only layouts with a stick (PSP). */
        fun onStick(x: Float, y: Float) {}
    }

    /** Gets each forwarded game-hole touch (see the class note). The event is recycled after the call. */
    var onHoleTouch: ((MotionEvent) -> Unit)? = null

    private var pad: PadSpec? = null
    private val buttons get() = pad?.buttons.orEmpty()
    private val dpadCx get() = pad?.dpadCx ?: 0f
    private val dpadCy get() = pad?.dpadCy ?: 0f
    private val dpadR get() = pad?.dpadR ?: 0f
    private val stickCx get() = pad?.stickCx ?: 0f
    private val stickCy get() = pad?.stickCy ?: 0f
    private val stickR get() = pad?.stickR ?: 0f
    private val hasStick get() = stickR > 0f
    private val armRects = Array(4) { RectF() } // D-pad arms: left, right, up, down
    private val drawRect = RectF()
    private var stick = 0f to 0f
    private var stickPointer = -1 // F2: the one finger that owns the stick until it lifts, however far it drifts

    private val pressed = mutableSetOf<Int>()
    private var dpad = 0 to 0
    private var dpadPointer = -1 // the one finger that owns the D-pad while it's down
    private val dpadDrag = DpadDrag()
    private var thumbX = 0f
    private var thumbY = 0f
    private val holeRouter = HoleRouter()
    private var holeDownTime = 0L

    /** D126: touch buttons hidden (a controller is in use). Nothing is drawn but [hint]; touches are handled by GameActivity. */
    var hidden = false
        private set
    private var hint: String? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 255, 255, 255) }
    private val fillDown = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 120, 200, 255) }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }

    init {
        isHapticFeedbackEnabled = true
    }

    /** F6: every pad buzz goes through the Haptics switch, read each time so it counts at once. */
    private fun buzz(type: Int) { if (Haptics.on(context)) performHapticFeedback(type) }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = refreshSpec()

    /** Works the layout out again for the current size (call it when the game moves without this view resizing). */
    fun refreshSpec() {
        if (width == 0 || height == 0) return
        val s = spec(width, height)
        if (s == pad) return
        pad = s
        setBackgroundColor(if (s.background) Color.rgb(18, 18, 24) else Color.TRANSPARENT)
        fill.alpha = if (s.seeThrough) 45 else 70 // see the game through the buttons
        stroke.alpha = if (s.seeThrough) 110 else 160
        text.textSize = s.textSize
        val cx = s.dpadCx
        val cy = s.dpadCy
        val r = s.dpadR
        val arm = r * 0.36f
        armRects[0].set(cx - r, cy - arm, cx - arm, cy + arm)
        armRects[1].set(cx + arm, cy - arm, cx + r, cy + arm)
        armRects[2].set(cx - arm, cy - r, cx + arm, cy - arm)
        armRects[3].set(cx - arm, cy + arm, cx + arm, cy + r)
        holeRouter.setHole(s.hole)?.let { forward(it) }
        updateGestureExclusion()
        invalidate()
    }

    /** D128: the system Back gesture must not fire on the D-pad or face buttons (nothing to protect while hidden). */
    private fun updateGestureExclusion() {
        val s = pad
        systemGestureExclusionRects = if (s == null || hidden) emptyList() else
            s.gestureExclusion(200f * resources.displayMetrics.density)
                .filter { it.width > 0f && it.height > 0f }
                .map { Rect(it.left.toInt(), it.top.toInt(), ceil(it.right).toInt(), ceil(it.bottom).toInt()) }
    }

    /**
     * Hides or shows the buttons. Hiding lets go of anything held, so no button stays pressed. The view keeps its space
     * (the game never moves or resizes, and the DS touch screen stays exactly where it was).
     */
    fun setHidden(hide: Boolean, hintText: String?) {
        hint = hintText
        if (hide && !hidden) {
            for (k in pressed) listener.onButton(k, false)
            pressed.clear()
            if (dpad != 0 to 0) { dpad = 0 to 0; listener.onDpad(0f, 0f) }
            if (stick != 0f to 0f) { stick = 0f to 0f; listener.onStick(0f, 0f) }
            dpadPointer = -1
            stickPointer = -1
        }
        hidden = hide
        contentDescription = if (hide) hintText else null
        updateGestureExclusion()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val s = pad ?: return
        if (hidden) {
            val h = hint ?: return
            val old = text.textSize
            val oldAlpha = text.alpha
            text.textSize = resources.displayMetrics.scaledDensity * PadLayout.HINT_SP
            text.alpha = 140
            val lines = wrapWords(h, s.hint.maxWidth) { text.measureText(it) }
            val lineH = text.textSize * 1.25f
            var y = s.hint.y - (lines.size - 1) * lineH / 2f
            for (line in lines) { canvas.drawText(line, s.hint.x, y, text); y += lineH }
            text.textSize = old; text.alpha = oldAlpha
            return
        }
        // D-pad: a plus shape, arms lit when pressed. Landscape with no room for buttons has none (dpadR 0).
        if (dpadR > 0f) {
            val arm = dpadR * 0.36f
            val (dx, dy) = dpad
            canvas.drawRect(dpadCx - arm, dpadCy - arm, dpadCx + arm, dpadCy + arm, fill)
            val lit = booleanArrayOf(dx == -1, dx == 1, dy == -1, dy == 1) // left, right, up, down
            for (i in 0 until 4) {
                canvas.drawRoundRect(armRects[i], 12f, 12f, if (lit[i]) fillDown else fill)
                canvas.drawRoundRect(armRects[i], 12f, 12f, stroke)
            }
            if (dpadPointer != -1) canvas.drawCircle(thumbX, thumbY, dpadR * 0.22f, fillDown) // where the thumb is
        }

        for (b in buttons) {
            val p = if (b.keyCode in pressed) fillDown else fill
            if (b.wide) {
                drawRect.set(b.rect)
                canvas.drawRoundRect(drawRect, b.r, b.r, p)
                canvas.drawRoundRect(drawRect, b.r, b.r, stroke)
            } else {
                canvas.drawCircle(b.cx, b.cy, b.r, p)
                canvas.drawCircle(b.cx, b.cy, b.r, stroke)
            }
            val old = text.textSize
            if (b.wide && b.label.length > 1) text.textSize = b.r * 0.75f
            else if (!b.wide && b.label.length > 1) text.textSize = b.r * 0.7f // N64 C-buttons ("C▲") in small circles
            canvas.drawText(b.label, b.cx, b.cy + text.textSize * 0.35f, text)
            text.textSize = old
        }

        if (hasStick) {
            canvas.drawCircle(stickCx, stickCy, stickR, fill)
            canvas.drawCircle(stickCx, stickCy, stickR, stroke)
            val (sx, sy) = stick
            val knob = if (sx != 0f || sy != 0f) fillDown else fill
            canvas.drawCircle(stickCx + sx * stickR * 0.6f, stickCy + sy * stickR * 0.6f, stickR * 0.45f, knob)
        }
    }

    private fun RectF.set(a: Area) = set(a.left, a.top, a.right, a.bottom)

    /** Sends one routed hole touch to [onHoleTouch] as a single-pointer MotionEvent. */
    private fun forward(o: HoleRouter.Out) {
        val now = SystemClock.uptimeMillis()
        if (o.action == MotionEvent.ACTION_DOWN) holeDownTime = now
        val ev = MotionEvent.obtain(holeDownTime, now, o.action, o.x, o.y, 0)
        try { onHoleTouch?.invoke(ev) } finally { ev.recycle() }
    }

    /** Passes the hole finger (if any) on to the game. */
    private fun routeHole(event: MotionEvent) {
        if (holeRouter.hole == null) return
        val n = event.pointerCount
        val ids = IntArray(n) { event.getPointerId(it) }
        val xs = FloatArray(n) { event.getX(it) }
        val ys = FloatArray(n) { event.getY(it) }
        holeRouter.route(event.actionMasked, event.actionIndex, ids, xs, ys)?.let { forward(it) }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        routeHole(event) // the game's finger goes on to the game, even while the buttons are hidden
        if (hidden) return true // GameActivity decides what a touch does while hidden; never a button press
        // Recompute the whole state from every finger still down: simple and never leaves a button stuck.
        val newPressed = mutableSetOf<Int>()
        var ndx = 0
        var ndy = 0
        var nsx = 0f
        var nsy = 0f
        val upIndex = when (event.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> event.actionIndex
            MotionEvent.ACTION_CANCEL -> -2
            else -> -1
        }
        val hadDpad = dpadPointer != -1
        // A finger landing on the D-pad zone claims it until that finger lifts.
        if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            val i = event.actionIndex
            val x = event.getX(i)
            val y = event.getY(i)
            val onStick = hasStick && StickTrack.grabs(x - stickCx, y - stickCy, stickR)
            if (onStick && stickPointer == -1 && !holeRouter.owns(event.getPointerId(i))) stickPointer = event.getPointerId(i)
            if (dpadPointer == -1 && dpadR > 0f && !onStick && !holeRouter.owns(event.getPointerId(i)) &&
                hypot(x - dpadCx, y - dpadCy) <= dpadR * 1.25f) {
                dpadPointer = event.getPointerId(i)
                dpadDrag.start(x - dpadCx, y - dpadCy, dpadR)
            }
        }
        if (upIndex == -2 || (upIndex >= 0 && event.getPointerId(upIndex) == dpadPointer)) dpadPointer = -1
        if (upIndex == -2 || (upIndex >= 0 && event.getPointerId(upIndex) == stickPointer)) stickPointer = -1
        if (upIndex != -2) {
            for (i in 0 until event.pointerCount) {
                if (i == upIndex) continue
                if (holeRouter.owns(event.getPointerId(i))) continue // the game's finger, never a button
                val x = event.getX(i)
                val y = event.getY(i)
                if (event.getPointerId(i) == dpadPointer) {
                    val (mx, my) = dpadDrag.move(x - dpadCx, y - dpadCy)
                    ndx = mx; ndy = my
                    thumbX = x; thumbY = y
                    continue
                }
                if (hasStick && event.getPointerId(i) == stickPointer) {
                    val (sx, sy) = StickTrack.deflect(x - stickCx, y - stickCy, stickR)
                    nsx = sx; nsy = sy
                    continue
                }
                if (hasStick && StickTrack.grabs(x - stickCx, y - stickCy, stickR)) continue // a second finger on the stick does nothing
                if (dpadR > 0f && hypot(x - dpadCx, y - dpadCy) <= dpadR * 1.25f) continue // a second finger on the D-pad does nothing
                buttons.firstOrNull { b ->
                    val pad = b.r * 0.25f
                    val r = b.rect
                    x >= r.left - pad && x <= r.right + pad && y >= r.top - pad && y <= r.bottom + pad
                }?.let { newPressed += it.keyCode }
            }
        }

        var changed = false
        for (k in newPressed - pressed) { listener.onButton(k, true); changed = true }
        for (k in pressed - newPressed) { listener.onButton(k, false); changed = true }
        if (newPressed.any { it !in pressed }) buzz(HapticFeedbackConstants.VIRTUAL_KEY)
        pressed.clear(); pressed += newPressed

        if ((ndx to ndy) != dpad) {
            if (ndx != 0 || ndy != 0) buzz(HapticFeedbackConstants.CLOCK_TICK)
            dpad = ndx to ndy
            listener.onDpad(ndx.toFloat(), ndy.toFloat())
            changed = true
        }
        if (hasStick && (nsx to nsy) != stick) {
            stick = nsx to nsy
            listener.onStick(nsx, nsy)
            changed = true
        }
        if (changed || hadDpad || dpadPointer != -1) invalidate()
        return true
    }
}

/**
 * Picks the one finger that belongs to the game picture ([hole], pad pixels) and turns it into a single-pointer
 * stream (DOWN, MOVE…, UP or CANCEL) in the game view's coordinates (hole top-left = 0, 0). Only the first finger to
 * land in the hole counts, until it lifts: the game reads one pointer. It may slide out of the hole and stays the game's.
 */
internal class HoleRouter {
    data class Out(val action: Int, val x: Float, val y: Float)

    var hole: Area? = null
        private set
    private var pointer = -1
    private var lastX = 0f
    private var lastY = 0f

    /** True while finger [id] is the game's. */
    fun owns(id: Int) = pointer != -1 && id == pointer

    /** New hole (layout changed). A finger already passed on is cancelled, since its coordinates no longer fit. */
    fun setHole(a: Area?): Out? {
        if (a == hole) return null
        val out = cancel()
        hole = a
        return out
    }

    fun cancel(): Out? {
        if (pointer == -1) return null
        pointer = -1
        return Out(MotionEvent.ACTION_CANCEL, lastX, lastY)
    }

    /** One touch event: [ids], [xs], [ys] = every pointer in it, in index order. Returns what to pass on, if anything. */
    fun route(actionMasked: Int, actionIndex: Int, ids: IntArray, xs: FloatArray, ys: FloatArray): Out? {
        val h = hole
        fun out(action: Int, i: Int): Out {
            val o = Out(action, xs[i] - (h?.left ?: 0f), ys[i] - (h?.top ?: 0f))
            lastX = o.x; lastY = o.y
            return o
        }
        when (actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (actionMasked == MotionEvent.ACTION_DOWN) pointer = -1 // a new gesture: forget a lost finger
                if (pointer == -1 && h != null && h.contains(xs[actionIndex], ys[actionIndex])) {
                    pointer = ids[actionIndex]
                    return out(MotionEvent.ACTION_DOWN, actionIndex)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val i = ids.indexOf(pointer)
                if (pointer != -1 && i >= 0) return out(MotionEvent.ACTION_MOVE, i)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (pointer != -1 && ids[actionIndex] == pointer) {
                    pointer = -1
                    return out(MotionEvent.ACTION_UP, actionIndex)
                }
            }
            MotionEvent.ACTION_CANCEL -> return cancel()
        }
        return null
    }
}

/** Greedy word wrap of [text] to [maxWidth] ([measure] = drawn width). A word wider than a line gets a line of its own. */
internal fun wrapWords(text: String, maxWidth: Float, measure: (String) -> Float): List<String> {
    if (measure(text) <= maxWidth) return listOf(text)
    val lines = mutableListOf<String>()
    var line = ""
    for (word in text.split(' ').filter { it.isNotEmpty() }) {
        val tryLine = if (line.isEmpty()) word else "$line $word"
        if (line.isNotEmpty() && measure(tryLine) > maxWidth) { lines += line; line = word } else line = tryLine
    }
    if (line.isNotEmpty()) lines += line
    return lines
}

/**
 * PSP analogue stick maths (F2, D138), relative to the stick centre. A finger landing within [GRAB] × the radius takes
 * the stick; from then on it steers wherever it goes until it lifts, tilt clamped to full (length 1) past the edge.
 */
internal object StickTrack {
    const val GRAB = 1.35f

    fun grabs(dx: Float, dy: Float, r: Float) = hypot(dx, dy) <= r * GRAB

    /** -1..1 each way (y -1 = up); never longer than 1, however far the finger is from the centre. */
    fun deflect(dx: Float, dy: Float, r: Float): Pair<Float, Float> {
        val dist = hypot(dx, dy)
        val k = if (dist > r) r / dist else 1f
        return (dx * k / r).coerceIn(-1f, 1f) to (dy * k / r).coerceIn(-1f, 1f)
    }
}

/**
 * D-pad that works as buttons AND a joystick. Landing on an arm gives that direction straight away.
 * Sliding the thumb then steers by how far it has moved from where it landed, so the exact
 * position of the arms stops mattering. All positions are relative to the D-pad centre.
 */
internal class DpadDrag {
    private var r = 1f
    private var ax = 0f // anchor: where the thumb landed, dragged along behind it on long slides
    private var ay = 0f
    private var tap = 0 to 0 // direction of the arm first touched

    fun start(x: Float, y: Float, radius: Float) {
        r = radius; ax = x; ay = y
        tap = direction(x, y, r * 0.2f)
    }

    fun move(x: Float, y: Float): Pair<Int, Int> {
        var vx = x - ax
        var vy = y - ay
        val len = hypot(vx, vy)
        val follow = r * 0.5f // anchor trails the thumb so reversing direction needs only a short slide
        if (len > follow) {
            ax = x - vx / len * follow; ay = y - vy / len * follow
            vx = x - ax; vy = y - ay
        }
        return if (hypot(vx, vy) > r * 0.3f) direction(vx, vy, 0f) else tap
    }

    companion object {
        /** 8-way: a diagonal only counts once the smaller axis is at least half the larger one. */
        fun direction(dx: Float, dy: Float, dead: Float): Pair<Int, Int> {
            var x = 0
            var y = 0
            if (abs(dx) > dead && abs(dx) >= abs(dy) * 0.5f) x = if (dx < 0) -1 else 1
            if (abs(dy) > dead && abs(dy) >= abs(dx) * 0.5f) y = if (dy < 0) -1 else 1
            return x to y
        }
    }
}
