package uk.benmarks.shoalcade

/**
 * The Settings screen outside a game (D132): words, what shows for each console, and what a pick changes.
 * Pure Kotlin. It reads and writes the same [DisplayChoices] and [ControlPrefs] as the in-game menu, and uses the
 * menu's own row types and choice ids where they mean the same thing, so the two can never disagree.
 */
object SettingsModel {
    const val TITLE = "Settings"
    const val BACK = "Back"
    const val EACH_CONSOLE = "Each console"
    const val EACH_CONSOLE_HELP = "The game menu changes the same settings while you play."
    const val PSP_SCREEN = "Always landscape"
    const val UPRIGHT = "Phone upright"
    const val SIDEWAYS = "Phone on its side"
    const val SNAPSHOTS_ON = "Snapshots: on"
    const val SNAPSHOTS_OFF = "Snapshots: off"
    const val SNAPSHOTS_WHERE = "Change this in the browser player's Settings. It counts for the app too."
    const val SAVES = "Saves"
    const val SAVES_HELP = "For every downloaded game at once. If a save is newer, or changed in both places, you're asked first."
    /** I6: nothing is copied by Android's own backup or phone transfer, so the saves must reach Jellyfin first. */
    const val CHANGING_PHONES = "Changing phones? Push all first, then log in on the new phone."
    /** B9 (D135, F9): phone saves, downloads and snapshots for games Jellyfin no longer lists (the browser saves manager's words). */
    const val NOT_IN_LIBRARY = "Not in your library"
    const val NOT_IN_LIBRARY_HELP = "Games no longer in your library that still have a save, download or snapshot on this phone. Play them, save a copy, or upload a save to another game."
    const val APP = "App"
    /** F6: one switch for every buzz the app makes, the touch buttons included. */
    const val HAPTICS = "Haptics (vibrate on touch)"
    const val ID_HAPTICS = "app.haptics"
    const val SERVER = "Server"
    const val ACCOUNT = "Account"
    const val LOG_OUT = "Log out"
    const val STORAGE = "Storage used on this phone"
    const val STORAGE_HELP = "Games, saves, backups and snapshots."
    const val ABOUT = "About and licences"

    /** Consoles the app plays, in the library's platform order (3DS isn't playable yet, so it has no settings). */
    val CONSOLES = listOf(Console.GB, Console.GBC, Console.GBA, Console.NDS, Console.PSX, Console.PSP)

    // Ids. Screen and touch buttons use the in-game menu's own ids; the DS has one set per way up here.
    fun dsId(landscape: Boolean, l: DsLayout) = "ds.${way(landscape)}.${l.key}"
    fun swapId(landscape: Boolean) = "swap.${way(landscape)}"
    private fun way(landscape: Boolean) = if (landscape) "land" else "port"

    /** Line shown instead of the Screen choices (PSP), or null. */
    fun screenNote(console: Console): String? = if (console.isLandscape) "${GameMenu.SCREEN}: $PSP_SCREEN" else null

    /** What the Settings screen lists for [console], with its current values. */
    fun sections(console: Console, display: DisplayChoices, touch: TouchMode): List<ShownSection> = buildList {
        if (!console.isLandscape) {
            add(ShownSection("screen", GameMenu.SCREEN, ScreenMode.entries.map {
                MenuItem.Choice(GameMenu.screenId(it), it.label, "screen", display.screen == it)
            }))
        }
        if (console.isDualScreen) {
            add(ShownSection("ds", GameMenu.DS_SCREENS, listOf(false, true).flatMap { land ->
                listOf(MenuItem.Heading("ds.${way(land)}", if (land) SIDEWAYS else UPRIGHT)) +
                    DsLayout.entries.map { MenuItem.Choice(dsId(land, it), it.label, "ds.${way(land)}", display.ds(land) == it) } +
                    MenuItem.Toggle(swapId(land), GameMenu.DS_SWAP, display.swap(land))
            }))
        }
        // Step 10: the same "Controls" section as the game menu, with the same rows in the same order.
        add(ShownSection("controls", GameMenu.CONTROLS,
            listOf(MenuItem.Action(GameMenu.ID_CONTROLS, GameMenu.CONTROLLER_BUTTONS, GameMenu.CONTROLS_HINT),
                MenuItem.Heading("touch", GameMenu.TOUCH)) +
                TouchMode.entries.map { MenuItem.Choice(GameMenu.touchId(it), it.label, "touch", touch == it) }))
    }

    /** Carries out a pick of [id] for [console]. False = not a setting (e.g. Controls, which opens a screen). */
    fun pick(id: String, console: Console, display: DisplayChoices, controls: ControlPrefs): Boolean {
        GameMenu.screenOf(id)?.let { if (console.isLandscape) return false; display.screen = it; return true }
        GameMenu.touchOf(id)?.let { controls.setTouchMode(console, it); return true }
        if (!console.isDualScreen) return false
        for (land in listOf(false, true)) {
            if (id == swapId(land)) { display.setSwap(land, !display.swap(land)); return true }
            DsLayout.entries.firstOrNull { dsId(land, it) == id }?.let { display.setDs(land, it); return true }
        }
        return false
    }
}
