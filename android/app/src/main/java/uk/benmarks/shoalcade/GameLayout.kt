package uk.benmarks.shoalcade

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Where the game picture, the touch buttons and the ☰ Menu handle go (D128). Plain Kotlin, so every console ×
 * orientation × screen size is unit-tested (GameLayoutTest). GameActivity builds the screen once and only changes
 * sizes from this, so turning the phone never restarts the game.
 */

/** A pixel rectangle inside the game screen (the root view). */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    fun contains(x: Float, y: Float) = x >= left && x < right && y >= top && y < bottom
}

/** Menu option "Screen": which way the game screen faces. PSP is always landscape. */
enum class ScreenMode(val key: String, val label: String) {
    TURN("turn", "Turn with the phone"), PORTRAIT("portrait", "Portrait"), LANDSCAPE("landscape", "Landscape");

    companion object { fun fromKey(k: String?) = entries.firstOrNull { it.key == k } ?: TURN }
}

/** Menu option "DS screens" (the browser's words). Values are melonDS DS core options (lower case, or they silently fall back). */
enum class DsLayout(val key: String, val label: String, private val normal: String, private val swapped: String,
                    /** Width ÷ height of the picture the core draws (two 256×192 screens; hybrid ratio 2). */
                    val aspect: Float) {
    SIDE("side", "Side by side", "left-right", "right-left", 512f / 192f),
    STACK("stack", "Stacked", "top-bottom", "bottom-top", 256f / 384f),
    BIG("big", "One big, one small", "hybrid-top", "hybrid-bottom", 768f / 384f);

    fun coreValue(swap: Boolean) = if (swap) swapped else normal

    /** Core options for this layout. "One big, one small": the small screen shows whichever one isn't big. */
    fun variables(swap: Boolean): List<Pair<String, String>> = listOf(
        "melonds_number_of_screen_layouts" to "1",
        "melonds_screen_layout1" to coreValue(swap),
        "melonds_hybrid_small_screen" to if (swap) "top" else "bottom",
    )

    companion object {
        fun fromKey(k: String?): DsLayout? = entries.firstOrNull { it.key == k }
        /** Default: stacked when the phone is upright, side by side when it is on its side (as the browser). */
        fun default(landscape: Boolean) = if (landscape) SIDE else STACK
    }
}

/**
 * One finished layout. [game] = the game view, [pad] = the touch-button view, both in root pixels.
 * [hole] = the part of [pad] over the game ([pad]-relative): touches there belong to the game (DS touch screen).
 */
data class GameLayout(
    val landscape: Boolean,
    val game: Box,
    val pad: Box,
    /** The pad is drawn over other things (landscape): transparent background. */
    val padOverlay: Boolean,
    val hole: Box?,
) {
    companion object {
        /** Side column for the touch buttons in landscape: 17 % of the width, at least 160 dp, at most 30 %. */
        fun column(width: Int, density: Float): Int = min(max(width * 0.17f, 160f * density), width * 0.30f).roundToInt()

        /** Portrait game height ÷ width, as before landscape existed (GameActivity before D128). */
        fun portraitAspectHW(console: Console, ds: DsLayout): Float = when {
            console.isDualScreen -> if (ds == DsLayout.STACK) 1.5f else 1f / ds.aspect
            console == Console.GBA -> 2f / 3f
            console == Console.PSX || console == Console.N64 -> 0.75f
            else -> 0.9f
        }

        /** Width ÷ height of the game picture (GB 160×144, GBA 240×160, PS1 and N64 4:3 TV, PSP 480×272, DS by layout). */
        fun pictureAspect(console: Console, ds: DsLayout): Float = when (console) {
            Console.NDS -> ds.aspect
            Console.GBA -> 1.5f
            Console.PSP -> 480f / 272f
            Console.PSX, Console.N64 -> 4f / 3f
            else -> 160f / 144f
        }

        /** The largest [aspect] rectangle centred in [area]. */
        fun fit(area: Box, aspect: Float): Box {
            var w = area.width.toFloat()
            var h = w / aspect
            if (h > area.height) { h = area.height.toFloat(); w = h * aspect }
            val l = area.left + ((area.width - w) / 2f).roundToInt()
            val t = area.top + ((area.height - h) / 2f).roundToInt()
            return Box(l, t, l + w.roundToInt(), t + h.roundToInt())
        }

        /**
         * [width] × [height] = the root view; [cutout] = the camera cut-out insets (left, top, right, bottom) inside it.
         * [padHidden] = touch buttons hidden (D126). Their space is given back (D130, F1): no side columns in landscape,
         * no pad strip in portrait; the game is as big as the screen allows and the pad view only holds the hint and Menu.
         */
        fun compute(console: Console, width: Int, height: Int, density: Float, cutout: Box = Box(0, 0, 0, 0),
                    ds: DsLayout = DsLayout.default(width > height), padHidden: Boolean = false): GameLayout {
            val area = Box(cutout.left, cutout.top, width - cutout.right, height - cutout.bottom)
            val landscape = width > height
            if (console.isLandscape) return GameLayout(landscape, area, area, padOverlay = true, hole = null)
            if (!landscape && !padHidden) {
                // Exactly the old portrait split: game on top (as big as its aspect allows), touch buttons below.
                val wantedPad = area.height - area.width * portraitAspectHW(console, ds)
                val padHeight = wantedPad.coerceIn(area.height * 0.30f, area.height * 0.45f).toInt()
                val game = Box(area.left, area.top, area.right, area.bottom - padHeight)
                val pad = Box(area.left, area.bottom - padHeight, area.right, area.bottom)
                return GameLayout(false, game, pad, padOverlay = false, hole = null)
            }
            // Landscape: game full height in the middle, touch buttons in a column each side (none while hidden).
            // Portrait with the buttons hidden: the same, so the game is centred on the whole screen.
            val col = if (padHidden) 0 else column(area.width, density)
            val game = fit(Box(area.left + col, area.top, area.right - col, area.bottom), pictureAspect(console, ds))
            val hole = Box(game.left - area.left, game.top - area.top, game.right - area.left, game.bottom - area.top)
            return GameLayout(landscape, game, area, padOverlay = true, hole = hole)
        }
    }
}

/** The Activity orientation for a [ScreenMode] (D129). PSP is always landscape, either way up. */
fun screenOrientation(console: Console, mode: ScreenMode): Int = when {
    console.isLandscape -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    mode == ScreenMode.PORTRAIT -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
    mode == ScreenMode.LANDSCAPE -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
    else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER
}

/**
 * The person's Display choices: Screen, plus DS layout and swap for each way the phone can face. [DisplayPrefs]
 * keeps them on the phone (D131); [InMemoryDisplay] only keeps them in memory (tests).
 */
interface DisplayChoices {
    var screen: ScreenMode
    fun ds(landscape: Boolean): DsLayout
    fun setDs(landscape: Boolean, layout: DsLayout)
    fun swap(landscape: Boolean): Boolean
    fun setSwap(landscape: Boolean, on: Boolean)
}

class InMemoryDisplay : DisplayChoices {
    override var screen = ScreenMode.TURN
    private val ds = mutableMapOf<Boolean, DsLayout>()
    private val swap = mutableMapOf<Boolean, Boolean>()
    override fun ds(landscape: Boolean) = ds[landscape] ?: DsLayout.default(landscape)
    override fun setDs(landscape: Boolean, layout: DsLayout) { ds[landscape] = layout }
    override fun swap(landscape: Boolean) = swap[landscape] ?: false
    override fun setSwap(landscape: Boolean, on: Boolean) { swap[landscape] = on }
}
