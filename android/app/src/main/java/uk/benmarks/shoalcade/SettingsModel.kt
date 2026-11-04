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
    /** Auto upload ([AutoUpload]): on by default, remembered on this phone. */
    const val AUTO_UPLOAD = "Upload saves automatically"
    const val AUTO_UPLOAD_HELP = "When the phone is back online, saves made offline go up to your Jellyfin by themselves."
    const val ID_AUTO_UPLOAD = "saves.autoupload"

    /** The auto upload switch as a row, with its current value. */
    fun autoUploadRow(store: SyncStore) = MenuItem.Toggle(ID_AUTO_UPLOAD, AUTO_UPLOAD, AutoUpload.on(store))
    /** B9 (D135, F9): phone saves, downloads and snapshots for games Jellyfin no longer lists (the browser saves manager's words). */
    const val NOT_IN_LIBRARY = "Not in your library"
    const val NOT_IN_LIBRARY_HELP = "Games no longer in your library that still have a save, download or snapshot on this phone. Play them, save a copy, or upload a save to another game."
    const val APP = "App"
    /** F6: one switch for every buzz the app makes, the touch buttons included. */
    const val HAPTICS = "Haptics (vibrate on touch)"
    const val ID_HAPTICS = "app.haptics"
    /** D246: games that rumble shake the controller, never the phone. On by default, kept on this phone. */
    const val RUMBLE = "Controller rumble"
    const val RUMBLE_HELP = "Games with rumble (Game Boy family, DS) shake your controller, never the phone."
    const val ID_RUMBLE = "app.rumble"
    /** D247: Select + a shoulder button on a controller. On by default, kept on this phone. */
    const val SHORTCUTS = "Controller shortcuts"
    const val SHORTCUTS_HELP = "Hold Select, then: R1 quick snapshot · L1 load snapshot · R2 fast-forward · L2 screenshot."
    const val ID_SHORTCUTS = "app.shortcuts"
    /** D248: the Continue card at the top of the games list. On by default, kept on this phone. */
    const val SHOW_CONTINUE = "Show Continue"
    const val SHOW_CONTINUE_HELP = "The last game you played on this phone, at the top of your games."
    const val ID_SHOW_CONTINUE = "app.continue"
    const val SERVER = "Server"
    const val ACCOUNT = "Account"
    const val LOG_OUT = "Log out"
    const val STORAGE = "Storage used on this phone"
    const val STORAGE_HELP = "Games, saves, backups and snapshots."
    const val ABOUT = "About and licences"

    /** Consoles the app plays, in the library's platform order. 3DS (D155): Screen and Controls here; its 3DS screens choice is in the game menu. */
    val CONSOLES = listOf(Console.GB, Console.GBC, Console.GBA, Console.N64, Console.NDS, Console.N3DS, Console.PSX, Console.PSP)

    // Ids. Screen and touch buttons use the in-game menu's own ids; the DS has one set per way up here.
    fun dsId(landscape: Boolean, l: DsLayout) = "ds.${way(landscape)}.${l.key}"
    fun swapId(landscape: Boolean) = "swap.${way(landscape)}"
    private fun way(landscape: Boolean) = if (landscape) "land" else "port"

    /** Line shown instead of the Screen choices (PSP, N64: always landscape), or null. */
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
        // D245: the game menu's own Smooth picture switch, for every console.
        add(ShownSection("picture", GameMenu.DISPLAY, listOf(MenuItem.Toggle(GameMenu.ID_SMOOTH, GameMenu.SMOOTH, display.smooth))))
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
        if (id == GameMenu.ID_SMOOTH) { display.smooth = !display.smooth; return true }
        if (!console.isDualScreen) return false
        for (land in listOf(false, true)) {
            if (id == swapId(land)) { display.setSwap(land, !display.swap(land)); return true }
            DsLayout.entries.firstOrNull { dsId(land, it) == id }?.let { display.setDs(land, it); return true }
        }
        return false
    }
}
