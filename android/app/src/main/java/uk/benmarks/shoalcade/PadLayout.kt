package uk.benmarks.shoalcade

import android.view.KeyEvent
import kotlin.math.min

/** A float rectangle in touch-pad pixels. */
data class Area(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    val centreX get() = (left + right) / 2f
    val centreY get() = (top + bottom) / 2f
    fun contains(x: Float, y: Float) = x >= left && x < right && y >= top && y < bottom
    fun intersects(o: Area) = left < o.right && o.left < right && top < o.bottom && o.top < bottom
}

/** One round (or [wide] pill) touch button. */
data class PadButton(val label: String, val keyCode: Int, val cx: Float, val cy: Float, val r: Float, val wide: Boolean = false) {
    /** Drawn shape (pills are 1.9 × as wide as tall). */
    val rect: Area get() = (if (wide) r * 1.9f else r).let { rx -> Area(cx - rx, cy - r, cx + rx, cy + r) }
}

/** Where the "Touch buttons hidden" hint goes: centred on ([x], [y]) (text baseline), wrapped to [maxWidth]. */
data class HintSpot(val x: Float, val y: Float, val maxWidth: Float)

/**
 * Everything GamepadView draws, in its own pixels. [menu] = where the ☰ Menu handle sits (GameActivity places a real
 * view there, so TalkBack can reach it). [stickR] 0 = no analogue stick.
 */
data class PadSpec(
    val buttons: List<PadButton>,
    val dpadCx: Float, val dpadCy: Float, val dpadR: Float,
    val stickCx: Float = 0f, val stickCy: Float = 0f, val stickR: Float = 0f,
    val textSize: Float,
    val menu: Area,
    val hint: HintSpot,
    /** PSP: drawn over the game, so see-through. */
    val seeThrough: Boolean = false,
    /** Portrait: the pad has its own dark background (else the black screen behind shows). */
    val background: Boolean = false,
    /**
     * The game picture inside the pad (landscape GB/GBC/GBA/DS), or null. Touches landing here belong to the game
     * (DS touch screen): GamepadView forwards them to the game view instead of treating them as button presses.
     */
    val hole: Area? = null,
    /** The ☰ Menu handle is the small icon-only one (landscape DS with the buttons hidden: no room for the full one). */
    val compactMenu: Boolean = false,
) {
    /** Area a finger may land in to take the D-pad. */
    val dpadZone get() = Area(dpadCx - dpadR * 1.25f, dpadCy - dpadR * 1.25f, dpadCx + dpadR * 1.25f, dpadCy + dpadR * 1.25f)

    /**
     * Where the system Back gesture must not fire (D128): the D-pad and the face/shoulder buttons near each side.
     * Android honours at most 200 dp of exclusion per screen edge, so each rect is kept to [maxTall] high.
     */
    fun gestureExclusion(maxTall: Float): List<Area> {
        fun clip(a: Area): Area = if (a.height <= maxTall) a else Area(a.left, a.centreY - maxTall / 2, a.right, a.centreY + maxTall / 2)
        val faces = buttons.filter { !it.wide }.map { it.rect }
        val face = if (faces.isEmpty()) null else Area(faces.minOf { it.left }, faces.minOf { it.top }, faces.maxOf { it.right }, faces.maxOf { it.bottom })
        return listOfNotNull(clip(dpadZone), face?.let { clip(it) })
    }
}

/** Touch-button layouts (D128). Portrait and PSP are exactly as before landscape existed; landscape GB/GBC/GBA/DS is new. */
object PadLayout {

    /** Portrait: [w] × [h] = the pad below the game. Same numbers as GamepadView before D128. */
    fun portrait(console: Console, w: Int, h: Int): PadSpec {
        val W = w.toFloat()
        val H = h.toFloat()
        val unit = min(W * 0.09f, H * 0.13f) // face-button radius
        val b = mutableListOf<PadButton>()
        if (console == Console.PSX) {
            // PS1: one shoulder row L1 L2 … R2 R1, smaller pills so four fit beside the ☰ Menu handle.
            val r = unit * 0.55f
            b += PadButton("L1", KeyEvent.KEYCODE_BUTTON_L1, W * 0.10f, H * 0.11f, r, wide = true)
            b += PadButton("L2", KeyEvent.KEYCODE_BUTTON_L2, W * 0.30f, H * 0.11f, r, wide = true)
            b += PadButton("R2", KeyEvent.KEYCODE_BUTTON_R2, W * 0.70f, H * 0.11f, r, wide = true)
            b += PadButton("R1", KeyEvent.KEYCODE_BUTTON_R1, W * 0.90f, H * 0.11f, r, wide = true)
        } else if (console != Console.GB && console != Console.GBC) {
            b += PadButton("L", KeyEvent.KEYCODE_BUTTON_L1, W * 0.13f, H * 0.11f, unit, wide = true)
            b += PadButton("R", KeyEvent.KEYCODE_BUTTON_R1, W * 0.87f, H * 0.11f, unit, wide = true)
        }
        b += faceButtons(console, W * 0.77f, H * 0.52f, unit)
        b += PadButton("SELECT", KeyEvent.KEYCODE_BUTTON_SELECT, W * 0.38f, H * 0.9f, unit * 0.6f, wide = true)
        b += PadButton("START", KeyEvent.KEYCODE_BUTTON_START, W * 0.62f, H * 0.9f, unit * 0.6f, wide = true)
        val m = unit * 0.55f
        return PadSpec(b, dpadCx = W * 0.25f, dpadCy = H * 0.52f, dpadR = min(W * 0.2f, H * 0.3f), textSize = unit * 0.7f,
            menu = Area(W / 2 - m * 1.6f, H * 0.11f - m, W / 2 + m * 1.6f, H * 0.11f + m),
            hint = HintSpot(W / 2, H / 2, W), background = true) // full width: one line wherever dev 2 fitted it
    }

    /** Hint text size, sp. */
    const val HINT_SP = 14f

    /**
     * PSP: drawn over the game, with an analogue stick. Same numbers as before D128 (stick: D138), hint included (1.5 lines of
     * 14 sp text above the bottom edge). [scaledDensity] = pixels per sp.
     */
    fun psp(w: Int, h: Int, scaledDensity: Float): PadSpec {
        val W = w.toFloat()
        val H = h.toFloat()
        val unit = H * 0.075f
        val b = mutableListOf(
            PadButton("L", KeyEvent.KEYCODE_BUTTON_L1, W * 0.08f, H * 0.09f, unit, wide = true),
            PadButton("R", KeyEvent.KEYCODE_BUTTON_R1, W * 0.92f, H * 0.09f, unit, wide = true),
        )
        // PSP buttons map to the libretro pad by position: △ = X, ○ = A, ✕ = B, □ = Y.
        val fx = W * 0.88f
        val fy = H * 0.45f
        val d = unit * 1.45f
        b += PadButton("△", KeyEvent.KEYCODE_BUTTON_X, fx, fy - d, unit)
        b += PadButton("○", KeyEvent.KEYCODE_BUTTON_A, fx + d, fy, unit)
        b += PadButton("✕", KeyEvent.KEYCODE_BUTTON_B, fx, fy + d, unit)
        b += PadButton("□", KeyEvent.KEYCODE_BUTTON_Y, fx - d, fy, unit)
        b += PadButton("SELECT", KeyEvent.KEYCODE_BUTTON_SELECT, W * 0.80f, H * 0.90f, unit * 0.55f, wide = true)
        b += PadButton("START", KeyEvent.KEYCODE_BUTTON_START, W * 0.92f, H * 0.90f, unit * 0.55f, wide = true)
        val m = unit * 0.5f
        // F2 (D138): a bigger stick (as big as the D-pad in landscape, narrower screens capped by width), low in the
        // corner where the thumb rests; its grab zone stays below the D-pad's.
        val sr = min(H * 0.16f, W * 0.12f)
        return PadSpec(b, dpadCx = W * 0.12f, dpadCy = H * 0.40f, dpadR = H * 0.16f,
            stickCx = W * 0.15f, stickCy = H - sr * 1.1f, stickR = sr, textSize = unit * 0.8f,
            menu = Area(W / 2 - m * 1.6f, H * 0.06f - m * 0.8f, W / 2 + m * 1.6f, H * 0.06f + m * 0.8f),
            hint = HintSpot(W / 2, H - HINT_SP * scaledDensity * 1.5f, W), seeThrough = true)
    }

    /**
     * N64 (plan-n64): landscape only, drawn see-through over the game like PSP, with PSP's D-pad, touch stick (D138),
     * ☰ Menu and hint. Left: L and Z on top. Right: R on top, the C-button diamond, then B and the big A below it (as
     * on the N64 pad: B up and left of A), Start in the corner. C-buttons carry made-up negative codes (GameButton.C_UP.keyCode etc.):
     * GameActivity sends them as the right stick. [scaledDensity] = pixels per sp.
     */
    fun n64(w: Int, h: Int, scaledDensity: Float): PadSpec {
        val W = w.toFloat()
        val H = h.toFloat()
        val unit = H * 0.075f
        val b = mutableListOf(
            // Shoulder pills by unit from the edges (not PSP's 8 % / 92 %), so they stay on narrow screens too.
            PadButton("L", KeyEvent.KEYCODE_BUTTON_L1, unit * 2.1f, H * 0.09f, unit, wide = true),
            PadButton("Z", KeyEvent.KEYCODE_BUTTON_L2, unit * 6.7f, H * 0.09f, unit, wide = true),
            PadButton("R", KeyEvent.KEYCODE_BUTTON_R1, W - unit * 2.1f, H * 0.09f, unit, wide = true),
        )
        val rx = W * 0.88f // right-hand anchor: the C diamond's centre
        val cy = H * 0.40f
        val d = unit * 1.4f
        val cr = unit * 0.75f
        b += PadButton("C▲", GameButton.C_UP.keyCode, rx, cy - d, cr)
        b += PadButton("C◀", GameButton.C_LEFT.keyCode, rx - d, cy, cr)
        b += PadButton("C▶", GameButton.C_RIGHT.keyCode, rx + d, cy, cr)
        b += PadButton("C▼", GameButton.C_DOWN.keyCode, rx, cy + d, cr)
        // The core's default map: N64 A = RetroPad B, N64 B = RetroPad Y (plan-n64 Step 0).
        b += PadButton("A", KeyEvent.KEYCODE_BUTTON_B, rx - unit, H * 0.74f, unit * 1.05f)
        b += PadButton("B", KeyEvent.KEYCODE_BUTTON_Y, rx - unit * 3.1f, H * 0.62f, unit * 1.05f)
        b += PadButton("START", KeyEvent.KEYCODE_BUTTON_START, W - unit * 1.3f, H * 0.90f, unit * 0.55f, wide = true)
        val m = unit * 0.5f
        val sr = min(H * 0.16f, W * 0.12f)
        return PadSpec(b, dpadCx = W * 0.12f, dpadCy = H * 0.40f, dpadR = H * 0.16f,
            stickCx = W * 0.15f, stickCy = H - sr * 1.1f, stickR = sr, textSize = unit * 0.8f,
            menu = Area(W / 2 - m * 1.6f, H * 0.06f - m * 0.8f, W / 2 + m * 1.6f, H * 0.06f + m * 0.8f),
            hint = HintSpot(W / 2, H - HINT_SP * scaledDensity * 1.5f, W), seeThrough = true)
    }

    /**
     * Landscape GB/GBC/GBA/DS: [w] × [h] = the whole screen, [game] = the game picture (pad pixels). D-pad, L and
     * Select in the left column; face buttons, R and Start in the right one; ☰ Menu in the top-left corner.
     * [density] = pixels per dp.
     */
    fun landscape(console: Console, w: Int, h: Int, game: Area, density: Float): PadSpec {
        val W = w.toFloat()
        val H = h.toFloat()
        val leftW = game.left
        val rightW = W - game.right
        val col = min(leftW, rightW)
        val lc = leftW / 2f
        val rc = game.right + rightW / 2f
        val unit = min(col * 0.15f, H * 0.085f)
        val b = mutableListOf<PadButton>()
        if (col >= 48f * density) {
            if (console == Console.PSX) {
                // PS1: L1 and L2 side by side in the left column, R2 and R1 in the right (outer = 1, inner = 2).
                val r = unit * 0.6f
                val off = col * 0.22f
                b += PadButton("L1", KeyEvent.KEYCODE_BUTTON_L1, lc - off, H * 0.22f, r, wide = true)
                b += PadButton("L2", KeyEvent.KEYCODE_BUTTON_L2, lc + off, H * 0.22f, r, wide = true)
                b += PadButton("R2", KeyEvent.KEYCODE_BUTTON_R2, rc - off, H * 0.22f, r, wide = true)
                b += PadButton("R1", KeyEvent.KEYCODE_BUTTON_R1, rc + off, H * 0.22f, r, wide = true)
            } else if (console != Console.GB && console != Console.GBC) {
                // 22 %, not lower: the D-pad's touch zone reaches up to 31 % and claims a finger first.
                b += PadButton("L", KeyEvent.KEYCODE_BUTTON_L1, lc, H * 0.22f, unit * 0.8f, wide = true)
                b += PadButton("R", KeyEvent.KEYCODE_BUTTON_R1, rc, H * 0.22f, unit * 0.8f, wide = true)
            }
            b += faceButtons(console, rc, H * 0.56f, unit)
            b += PadButton("SELECT", KeyEvent.KEYCODE_BUTTON_SELECT, lc, H * 0.88f, unit * 0.6f, wide = true)
            b += PadButton("START", KeyEvent.KEYCODE_BUTTON_START, rc, H * 0.88f, unit * 0.6f, wide = true)
        }
        val full = Area(12f * density, 10f * density, (12f + 96f) * density, (10f + 40f) * density)
        val menu = if (!full.intersects(game)) full else compactMenu(game, density)
        // Hint: in the left column, or (DS grown to the full width) in the strip under the game. A full-height game
        // leaves no strip, so keep the 14 sp text 1.5 lines above the bottom edge (as the old overlay hint), not on it.
        val hint = if (leftW >= 96f * density) HintSpot(lc, H / 2f, leftW * 0.86f)
            else HintSpot(W / 2f, min((game.bottom + H) / 2f, H - 21f * density), W * 0.9f)
        return PadSpec(b, dpadCx = lc, dpadCy = H * 0.56f, dpadR = if (col >= 48f * density) min(col * 0.40f, H * 0.20f) else 0f,
            textSize = unit * 0.7f, menu = menu, hint = hint, hole = game, compactMenu = menu != full)
    }

    /** Side of the small ☰ handle, dp. */
    const val COMPACT_MENU_DP = 36f

    /**
     * DS grown to the full width (buttons hidden): the full handle would sit on the picture. A small ☰ goes in the
     * strip left of the game if there is one ("One big, one small" on a 20:9 phone), else in the strip above it
     * (side by side), else over the game's top-left corner (GameActivity draws it see-through there).
     */
    private fun compactMenu(game: Area, density: Float): Area {
        val s = COMPACT_MENU_DP * density
        val gap = 4f * density
        return when {
            game.left >= s + gap -> Area((game.left - s) / 2f, 10f * density, (game.left + s) / 2f, 10f * density + s)
            game.top >= s + gap -> Area(12f * density, (game.top - s) / 2f, 12f * density + s, (game.top + s) / 2f)
            else -> Area(gap, gap, gap + s, gap + s)
        }
    }

    /**
     * GB/GBC/GBA: A and B on a slant. DS: the X/A/B/Y diamond. PS1: the same diamond with the PlayStation symbols
     * (by position, as PSP: △ = X, ○ = A, ✕ = B, □ = Y). Same shapes in every layout.
     */
    private fun faceButtons(console: Console, fx: Float, fy: Float, unit: Float): List<PadButton> =
        if (console == Console.NDS || console == Console.PSX) {
            val d = unit * 1.45f
            val ps = console == Console.PSX
            listOf(
                PadButton(if (ps) "△" else "X", KeyEvent.KEYCODE_BUTTON_X, fx, fy - d, unit),
                PadButton(if (ps) "○" else "A", KeyEvent.KEYCODE_BUTTON_A, fx + d, fy, unit),
                PadButton(if (ps) "✕" else "B", KeyEvent.KEYCODE_BUTTON_B, fx, fy + d, unit),
                PadButton(if (ps) "□" else "Y", KeyEvent.KEYCODE_BUTTON_Y, fx - d, fy, unit),
            )
        } else listOf(
            PadButton("A", KeyEvent.KEYCODE_BUTTON_A, fx + unit * 0.9f, fy - unit * 0.6f, unit * 1.1f),
            PadButton("B", KeyEvent.KEYCODE_BUTTON_B, fx - unit * 1.1f, fy + unit * 0.6f, unit * 1.1f),
        )

    /** The layout for [console] in [layout] (pad pixels). [scaledDensity] = pixels per sp (only the PSP hint uses it). */
    fun forLayout(console: Console, layout: GameLayout, density: Float, scaledDensity: Float = density): PadSpec = when {
        console == Console.N64 -> n64(layout.pad.width, layout.pad.height, scaledDensity)
        console.isLandscape -> psp(layout.pad.width, layout.pad.height, scaledDensity)
        layout.hole == null -> portrait(console, layout.pad.width, layout.pad.height) // portrait buttons hidden: as landscape
        else -> {
            val h = layout.hole!!
            landscape(console, layout.pad.width, layout.pad.height,
                Area(h.left.toFloat(), h.top.toFloat(), h.right.toFloat(), h.bottom.toFloat()), density)
        }
    }
}
