package uk.benmarks.shoalcade

/**
 * Fast-forward on the phone (every console; the browser player has had it since s0.2). Pure Kotlin, so it is unit tested.
 *
 * - The emulator's `frameSpeed` is frames run per screen refresh: 0 stands the game still (menu, Cheats, Controls),
 *   1 is normal, 2-4 is fast-forward. [frameSpeed] is the one place that is worked out, so closing the menu goes back
 *   to the speed the game had, not always to normal.
 * - The speed (2×, 3× or 4×, 2× at first as in the browser) is kept on the phone for every game, in the plain
 *   SharedPreferences file `display` (key `ff_speed`, no secrets) that also holds App Mute. Fast-forward itself always
 *   starts off.
 * - Never in a link session: the two phones would drift apart (the same reason cheats are hidden there).
 * - Sound is off while fast ([GameSound.enabled]): LibretroDroid plays the sped-up sound higher and faster.
 */
internal object FastForward {
    /** The speeds offered (browser: 2-4×). */
    val SPEEDS = listOf(2, 3, 4)
    const val DEFAULT_SPEED = 2
    private const val KEY = "ff_speed"

    /** Frames per screen refresh: 0 while [paused], else the fast-forward speed when [on], else 1. */
    fun frameSpeed(paused: Boolean, on: Boolean, speed: Int): Int = when {
        paused -> 0
        on -> speed.coerceIn(SPEEDS.first(), SPEEDS.last())
        else -> 1
    }

    /**
     * The speeds offered for [console]; empty = no fast-forward there (no ⏩, no Speed section).
     * LibretroDroid 0.14's sound callback keeps reading at the game's speed even with the sound off, into a buffer
     * 10 callbacks long: it overflows (and the app crashes, seen on the test emulator) once
     * 0.2 × core sound rate ÷ phone sound rate × speed passes 1. mGBA plays GB/GBC sound at about 130 kHz (over at 2×)
     * and GBA at about 65 kHz (over at 4×); the other cores run at 32-48 kHz and are safe at 4×. 3DS has its own
     * LibretroDroid copy, which caps the read, so it keeps 4×.
     */
    fun speeds(console: Console?): List<Int> = SPEEDS.filter { it <= maxSpeed(console) }

    private fun maxSpeed(console: Console?): Int = when (console) {
        Console.GB, Console.GBC -> 1
        Console.GBA -> 3
        else -> SPEEDS.last()
    }

    /** Fast-forward can be used on [console] at all. */
    fun allowed(console: Console?) = speeds(console).isNotEmpty()

    /** The speed the game really runs at: the chosen one, held to what [console] offers (4× chosen = 3× on GBA). */
    fun speedFor(console: Console?, chosen: Int): Int = speeds(console).lastOrNull()?.let { chosen.coerceAtMost(it) } ?: 1

    /** The chosen speed; anything not offered reads as the default, so a damaged value can't make it odd. */
    fun speed(store: SyncStore): Int = store.get(KEY)?.toIntOrNull()?.takeIf { it in SPEEDS } ?: DEFAULT_SPEED
    fun setSpeed(store: SyncStore, n: Int) { if (n in SPEEDS) store.set(KEY, n.toString()) }

    /** "2×" (the browser's words). */
    fun speedLabel(n: Int) = "$n×"

    /** The touch button: just the symbol when off; the speed too when on. */
    fun buttonText(on: Boolean, speed: Int) = if (on) "⏩ ${speedLabel(speed)}" else "⏩"

    /** What TalkBack says for the touch button. */
    fun description(on: Boolean) = if (on) "Fast-forward, on" else "Fast-forward, off"

    /**
     * Where the ⏩ touch button goes (pad pixels), or null if there is no room (the menu's Speed section still works).
     * The button is a square as tall as the ☰ Menu handle. Tried in order: right of the handle, left of it, below it
     * (centred, else just right or left of its middle), then the same corner on the other side of the pad (the handle's
     * mirror image: landscape GB/GBC/GBA/DS, where the handle sits in the top-left corner beside the game and the L
     * button is right under it). The first spot that is inside the pad ([w] × [h]) and clear of every touch button,
     * the D-pad and stick zones and the game picture ([PadSpec.hole]) wins. [density] = pixels per dp.
     */
    fun spot(spec: PadSpec, w: Float, h: Float, density: Float): Area? {
        val m = spec.menu
        val s = m.height
        val gap = GAP_DP * density
        val cy = m.top
        val candidates = listOf(
            Area(m.right + gap, cy, m.right + gap + s, cy + s),
            Area(m.left - gap - s, cy, m.left - gap, cy + s),
            Area(m.centreX - s / 2, m.bottom + gap, m.centreX + s / 2, m.bottom + gap + s),
            // Still under the handle, just to one side of its middle: PS1 portrait, where the D-pad's slide zone is the
            // left half of the pad and reaches up to a few pixels under a centred spot on most phones.
            Area(m.centreX + gap / 2, m.bottom + gap, m.centreX + gap / 2 + s, m.bottom + gap + s),
            Area(m.centreX - gap / 2 - s, m.bottom + gap, m.centreX - gap / 2, m.bottom + gap + s),
            Area(w - m.left - s, cy, w - m.left, cy + s),
        )
        val pad = Area(0f, 0f, w, h)
        val blocked = obstacles(spec)
        return candidates.firstOrNull { c ->
            c.left >= pad.left && c.top >= pad.top && c.right <= pad.right && c.bottom <= pad.bottom &&
                !c.intersects(m) && blocked.none { it.intersects(c) }
        }
    }

    /** Everything the ⏩ button must keep clear of: touch buttons, D-pad zone, stick zone, game picture. */
    fun obstacles(spec: PadSpec): List<Area> = buildList {
        spec.buttons.forEach { add(it.rect) }
        if (spec.dpadR > 0f) add(spec.dpadZone)
        if (spec.stickR > 0f) {
            val r = spec.stickR * 1.25f // the stick's grab zone (as the edge swipe uses)
            add(Area(spec.stickCx - r, spec.stickCy - r, spec.stickCx + r, spec.stickCy + r))
        }
        spec.hole?.let { add(it) }
    }

    /** Space between the ☰ Menu handle and the ⏩ button, dp. */
    const val GAP_DP = 4f
}
