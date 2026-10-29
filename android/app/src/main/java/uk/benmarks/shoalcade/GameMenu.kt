package uk.benmarks.shoalcade

import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs

/**
 * The in-game menu (dev 3, D129): a side drawer built from a registry of [MenuSection]s, so later sections (Speed,
 * Cheats) slot in without touching the rest. Pure Kotlin: the words, what shows when, and controller navigation.
 * The drawer itself is [GameDrawer]; [GameActivity] carries out the choices.
 */
object GameMenu {
    // Item 5 words: the same as the browser player. No "snapshot on close" option.
    const val RESUME = "Resume"
    const val CLOSE = "Close Game"
    const val CLOSE_UPLOAD = "Close Game (and Upload)"
    const val SAVE_STATE = "Save snapshot"
    const val LOAD_STATE = "Load snapshot"

    /** [states] = snapshots allowed here (not a link session, on in the server settings); [hasState] = one is saved. */
    fun items(states: Boolean, hasState: Boolean): List<String> = buildList {
        add(RESUME); add(CLOSE); add(CLOSE_UPLOAD)
        if (states) { add(SAVE_STATE); if (hasState) add(LOAD_STATE) }
    }

    /** D126: controller and touch-button settings. */
    const val CONTROLS = "Controls"
    const val TOUCH = "Touch buttons"

    /** The old one-list menu (dev 2): [items] then Controls and "Touch buttons: <mode>". Kept for its tests. */
    fun all(states: Boolean, hasState: Boolean, touch: TouchMode): List<Pair<String, String>> =
        items(states, hasState).map { it to it } + listOf(CONTROLS to CONTROLS, TOUCH to "$TOUCH: ${touch.label}")

    // Drawer words.
    const val TITLE = "Game menu"
    const val CLOSE_MENU = "Close menu"
    const val GAME = "Game"
    const val SNAPSHOTS = "Snapshots"
    const val DISPLAY = "Display"
    const val SCREEN = "Screen"
    const val DS_SCREENS = "DS screens"
    const val DS_SWAP = "Swap the two screens"
    /** F5: silence the game so music from another app can play (the app never takes audio focus). */
    const val SOUND = "Sound"
    const val APP_MUTE = "App Mute"
    /** Step 10: the row that opens the Controls screen. Not "Controls" too, as it sits under the Controls title. */
    const val CONTROLLER_BUTTONS = "Controller buttons"
    const val CONTROLS_HINT = "Choose what each button does"

    // Item ids (stable: the activity acts on these, never on the words).
    const val ID_RESUME = "resume"
    const val ID_CLOSE = "close"
    const val ID_CLOSE_UPLOAD = "close_upload"
    const val ID_SAVE_SNAPSHOT = "save_snapshot"
    const val ID_LOAD_SNAPSHOT = "load_snapshot"
    const val ID_CONTROLS = "controls"
    const val ID_DS_SWAP = "ds.swap"
    const val ID_MUTE = "sound.mute"
    fun touchId(m: TouchMode) = "touch.${m.key}"
    fun screenId(m: ScreenMode) = "screen.${m.key}"
    fun dsId(l: DsLayout) = "ds.${l.key}"

    /** What a choice id means (null = not that kind of id). */
    fun touchOf(id: String): TouchMode? = TouchMode.entries.firstOrNull { touchId(it) == id }
    fun screenOf(id: String): ScreenMode? = ScreenMode.entries.firstOrNull { screenId(it) == id }
    fun dsOf(id: String): DsLayout? = DsLayout.entries.firstOrNull { dsId(it) == id }

    /** F9: a game no longer in the library has nowhere on Jellyfin to upload to, so it has no "and Upload". */
    val GAME_SECTION = MenuSection("game", GAME) { c ->
        listOfNotNull(MenuItem.Action(ID_RESUME, RESUME), MenuItem.Action(ID_CLOSE, CLOSE),
            if (c.canUpload) MenuItem.Action(ID_CLOSE_UPLOAD, CLOSE_UPLOAD) else null)
    }

    /** Snapshots would break a link session; on/off per console comes from the server settings (D38). */
    val SNAPSHOT_SECTION = MenuSection("snapshots", SNAPSHOTS, visible = { it.states }) { c ->
        buildList {
            add(MenuItem.Action(ID_SAVE_SNAPSHOT, SAVE_STATE))
            if (c.hasState) add(MenuItem.Action(ID_LOAD_SNAPSHOT, LOAD_STATE))
        }
    }

    // D143: Cheats. Words agreed with the browser player.
    const val CHEATS = "Cheats"
    const val ADD_CHEAT = "Add a cheat…"
    const val EDIT_CHEATS = "Edit cheats…"
    const val EDIT_CHEATS_HINT = "Rename or delete"
    const val CHEATS_OFF_NOTE = "Switching a cheat off doesn't undo what it already changed."
    const val ID_ADD_CHEAT = "cheats.add"
    const val ID_EDIT_CHEATS = "cheats.edit"
    private const val CHEAT_PREFIX = "cheat."
    fun cheatId(id: String) = CHEAT_PREFIX + id
    /** The cheat a toggle id is for (null = not a cheat toggle). */
    fun cheatOf(id: String): String? = id.takeIf { it.startsWith(CHEAT_PREFIX) }?.removePrefix(CHEAT_PREFIX)

    /** "No cheats on", "1 cheat on", "3 cheats on". */
    fun cheatsOn(n: Int) = when (n) { 0 -> "No cheats on"; 1 -> "1 cheat on"; else -> "$n cheats on" }

    /**
     * D143: one switch per cheat in the game's list, then Add and Edit. Hidden in a link session (cheats on one phone
     * would make the two games drift apart).
     */
    val CHEATS_SECTION = MenuSection("cheats", CHEATS, visible = { it.cheatsAllowed }) { c ->
        buildList {
            add(MenuItem.Heading("cheats.count", cheatsOn(c.cheats.count { it.on })))
            c.cheats.forEach { add(MenuItem.Toggle(cheatId(it.id), it.name, it.on)) }
            if (c.cheats.isNotEmpty()) add(MenuItem.Heading("cheats.note", CHEATS_OFF_NOTE))
            add(MenuItem.Action(ID_ADD_CHEAT, ADD_CHEAT))
            if (c.cheats.isNotEmpty()) add(MenuItem.Action(ID_EDIT_CHEATS, EDIT_CHEATS, EDIT_CHEATS_HINT))
        }
    }

    /** F5: App Mute, kept for every game (see [GameSound]). */
    val SOUND_SECTION = MenuSection("sound", SOUND) { c -> listOf(MenuItem.Toggle(ID_MUTE, APP_MUTE, c.muted)) }

    val CONTROLS_SECTION = MenuSection("controls", CONTROLS) { c ->
        listOf(MenuItem.Action(ID_CONTROLS, CONTROLLER_BUTTONS, CONTROLS_HINT), MenuItem.Heading("touch", TOUCH)) +
            TouchMode.entries.map { MenuItem.Choice(touchId(it), it.label, "touch", c.touch == it) }
    }

    /** PSP and N64 are always landscape with one screen, so they have nothing to choose here. */
    val DISPLAY_SECTION = MenuSection("display", DISPLAY, visible = { !it.console.isLandscape }) { c ->
        buildList {
            add(MenuItem.Heading("screen", SCREEN))
            ScreenMode.entries.forEach { add(MenuItem.Choice(screenId(it), it.label, "screen", c.screen == it)) }
            if (c.console.isDualScreen) {
                add(MenuItem.Heading("ds", DS_SCREENS))
                DsLayout.entries.forEach { add(MenuItem.Choice(dsId(it), it.label, "ds", c.ds == it)) }
                add(MenuItem.Toggle(ID_DS_SWAP, DS_SWAP, c.dsSwap))
            }
        }
    }

    /**
     * Every section, in drawer order. Speed goes here later. Step 10: things done mid-game first (Game, Snapshots,
     * Cheats (D143), then Sound for App Mute), then Display before Controls, so in landscape the Screen choices are the first thing below the fold.
     * The Settings screen lists the same settings in the same order.
     */
    val SECTIONS = listOf(GAME_SECTION, SNAPSHOT_SECTION, CHEATS_SECTION, SOUND_SECTION, DISPLAY_SECTION, CONTROLS_SECTION)

    /** The sections shown for [ctx]: visible ones with at least one item. */
    fun sections(ctx: MenuContext, registry: List<MenuSection> = SECTIONS): List<ShownSection> =
        registry.filter { it.visible(ctx) }
            .map { ShownSection(it.id, it.title, it.items(ctx)) }
            .filter { s -> s.items.any { it.selectable } }
}

/** Everything the menu needs to know to decide what it shows. */
data class MenuContext(
    val console: Console,
    /** Snapshots allowed here (not a link session, on in the server settings). */
    val states: Boolean,
    /** A snapshot is saved for this game. */
    val hasState: Boolean,
    val touch: TouchMode,
    val screen: ScreenMode,
    /** DS layout and swap for the way the phone is facing now. */
    val ds: DsLayout,
    val dsSwap: Boolean,
    /** F5: App Mute is on. */
    val muted: Boolean = false,
    /** F9: false for a game no longer in the library (played from Settings → Saves → Not in your library). */
    val canUpload: Boolean = true,
    /** D143: cheats can be used here (not a link session). */
    val cheatsAllowed: Boolean = true,
    /** D143: the game's cheats, in list order, and which are on. */
    val cheats: List<MenuCheat> = emptyList(),
)

/** One cheat as the menu shows it. */
data class MenuCheat(val id: String, val name: String, val on: Boolean)

/** One part of the drawer. [items] are worked out each time the drawer opens or a choice changes. */
class MenuSection(
    val id: String,
    val title: String,
    val visible: (MenuContext) -> Boolean = { true },
    val items: (MenuContext) -> List<MenuItem>,
)

data class ShownSection(val id: String, val title: String, val items: List<MenuItem>)

sealed class MenuItem {
    abstract val id: String
    abstract val label: String
    /** Can be picked (everything but a heading). */
    val selectable get() = this !is Heading

    /** Does something, then the drawer closes. */
    data class Action(override val id: String, override val label: String, val hint: String? = null) : MenuItem()
    /** One of a [group] of options (radio button); the drawer stays open. */
    data class Choice(override val id: String, override val label: String, val group: String, val selected: Boolean) : MenuItem()
    /** On / off; the drawer stays open. */
    data class Toggle(override val id: String, override val label: String, val on: Boolean) : MenuItem()
    /** A label over a group of choices; can't be picked. */
    data class Heading(override val id: String, override val label: String) : MenuItem()
}

/**
 * Controller navigation in the drawer: Up / Down move a row, L1 / R1 jump to the next / previous section, A / Centre /
 * Enter pick, B / Back / Home / Escape close. The hat and the left stick move one row per push (back to the middle
 * before the next). Headings are skipped. Pure, so it is unit tested.
 */
class DrawerNav {
    sealed class Result {
        object None : Result()
        object Moved : Result()
        data class Select(val id: String) : Result()
        object Close : Result()
    }

    private data class Row(val section: Int, val id: String)
    private var rows = listOf<Row>()
    private var at = 0
    /** Vertical direction the hat / stick is held in now (-1 up, 0 middle, 1 down). */
    private var held = 0

    /** The row with the controller's highlight, or null if there are none. */
    val focus: String? get() = rows.getOrNull(at)?.id

    /** New rows (drawer opened, or a choice changed what shows). Keeps the highlight on the same item if it is still there. */
    fun setSections(sections: List<ShownSection>, resetFocus: Boolean = false) {
        val was = focus
        rows = sections.flatMapIndexed { i, s -> s.items.filter { it.selectable }.map { Row(i, it.id) } }
        at = if (resetFocus) 0 else rows.indexOfFirst { it.id == was }.coerceAtLeast(0)
    }

    /** The drawer just opened: a stick or hat already held must not move the highlight until it goes back to the middle. */
    fun reset(axes: Map<Int, Float> = emptyMap()) { held = direction(axes, 0) }

    fun key(code: Int, down: Boolean, repeat: Int = 0): Result {
        if (!down) return Result.None
        return when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> move(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> move(1)
            KeyEvent.KEYCODE_BUTTON_R1 -> if (repeat == 0) section(1) else Result.None
            KeyEvent.KEYCODE_BUTTON_L1 -> if (repeat == 0) section(-1) else Result.None
            in SELECT -> if (repeat == 0) focus?.let { Result.Select(it) } ?: Result.None else Result.None
            in CLOSE -> if (repeat == 0) Result.Close else Result.None
            else -> Result.None
        }
    }

    /** One controller motion event: hat Y, else left stick Y. */
    fun motion(values: Map<Int, Float>): Result {
        val d = direction(values, held)
        if (d == held) return Result.None
        held = d
        return if (d == 0) Result.None else move(d)
    }

    private fun direction(values: Map<Int, Float>, now: Int): Int {
        val hat = values[MotionEvent.AXIS_HAT_Y] ?: 0f
        val v = if (abs(hat) >= PUSH) hat else values[MotionEvent.AXIS_Y] ?: 0f
        return when {
            v <= -PUSH -> -1
            v >= PUSH -> 1
            abs(v) < BACK -> 0
            else -> now // between the two: keep what it was, so a wobbling stick can't move twice
        }
    }

    private fun move(by: Int): Result {
        if (rows.isEmpty()) return Result.None
        val next = (at + by).coerceIn(0, rows.lastIndex)
        if (next == at) return Result.None
        at = next
        return Result.Moved
    }

    /** First row of the next (or previous) section, going round at the ends. */
    private fun section(by: Int): Result {
        if (rows.isEmpty()) return Result.None
        val sections = rows.map { it.section }.distinct()
        val i = sections.indexOf(rows[at].section)
        val target = sections[Math.floorMod(i + by, sections.size)]
        val next = rows.indexOfFirst { it.section == target }
        if (next == at) return Result.None
        at = next
        return Result.Moved
    }

    companion object {
        const val PUSH = 0.5f
        const val BACK = 0.3f
        val SELECT = setOf(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)
        val CLOSE = setOf(KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_MODE, KeyEvent.KEYCODE_ESCAPE)
        /** Keys the drawer uses (a keyboard's are taken too, not just a controller's). */
        fun handles(code: Int) = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN ||
            code == KeyEvent.KEYCODE_BUTTON_L1 || code == KeyEvent.KEYCODE_BUTTON_R1 || code in SELECT || code in CLOSE
    }
}

/**
 * Edge swipe (D129): a finger landing within [edge] px of the left edge (not on the D-pad or the game: the caller
 * says so with `blocked`) that moves [travel] px to the right opens the drawer. Pure, so it is unit tested.
 */
class EdgeSwipe(private val edge: Float, private val travel: Float) {
    private var startX = 0f
    private var tracking = false

    fun down(x: Float, blocked: Boolean) { tracking = !blocked && x >= 0f && x <= edge; startX = x }

    /** True once, when the finger has gone far enough right: open the drawer. */
    fun move(x: Float): Boolean {
        if (!tracking || x - startX < travel) return false
        tracking = false
        return true
    }

    fun end() { tracking = false }
}
