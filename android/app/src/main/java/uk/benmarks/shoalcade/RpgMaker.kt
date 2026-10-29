package uk.benmarks.shoalcade

import android.content.Context
import android.view.KeyEvent
import java.io.File

/**
 * RPG Maker XP / VX / VX Ace (D147): the mkxp-z core's set-up that isn't per game.
 * The compatibility script (assets/rpgmaker/shoalcade_compat.rb, the same file the browser uses) runs before every
 * game; mkxp-z reads it from `<system>/mkxp-z/Scripts/Preload` once the matching core option says "enabled".
 */
object RpgMaker {
    const val COMPAT = "shoalcade_compat.rb"

    /** mkxp-z's option name for a preload script: "mkxp-z_preload-" + each character of the file name in hex. */
    fun preloadOption(script: String) = "mkxp-z_preload-" + script.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }

    /** Options the core is always given (on top of its own defaults, which the shim supplies). */
    val coreVariables: List<Pair<String, String>> = listOf(preloadOption(COMPAT) to "enabled")

    /**
     * Options for one game, from its file list as the server sent it (kept next to the game, so this works offline):
     * games made for Ruby 1.8 / 1.9 (`oldRuby`, the server checks their DLLs) need mkxp-z's syntax transform ("2" =
     * compatibility mode), which mkxp-z otherwise turns off for any game with an mkxp.json.
     */
    fun gameVariables(fileListJson: String?): List<Pair<String, String>> {
        val old = fileListJson?.let { runCatching { org.json.JSONObject(it).optBoolean("oldRuby") }.getOrNull() } ?: false
        return if (old) listOf(SYNTAX_TRANSFORM to "2") else emptyList()
    }
    const val SYNTAX_TRANSFORM = "mkxp-z_syntaxTransform"

    /** Copies the compatibility script into the core's system folder (every start, so an app update replaces it). */
    fun installCompat(ctx: Context, systemDir: File) {
        val dir = File(systemDir, "mkxp-z/Scripts/Preload").apply { mkdirs() }
        val out = File(dir, COMPAT)
        val bytes = ctx.assets.open("rpgmaker/$COMPAT").use { it.readBytes() }
        if (!out.isFile || !out.readBytes().contentEquals(bytes)) out.writeBytes(bytes)
    }

    /**
     * RPG Maker's buttons and the pad keys that reach them through mkxp-z's default libretro map (C = RetroPad A,
     * B = B, A = X, X = Y, Y = L3, Z = R3, L, R). [DEFAULT_LABELS] is what the touch buttons say when a game doesn't name them.
     */
    val RGSS_KEYS: Map<Char, Int> = mapOf(
        'c' to KeyEvent.KEYCODE_BUTTON_A, 'b' to KeyEvent.KEYCODE_BUTTON_B, 'a' to KeyEvent.KEYCODE_BUTTON_X,
        'x' to KeyEvent.KEYCODE_BUTTON_Y, 'y' to KeyEvent.KEYCODE_BUTTON_THUMBL, 'z' to KeyEvent.KEYCODE_BUTTON_THUMBR,
        'l' to KeyEvent.KEYCODE_BUTTON_L1, 'r' to KeyEvent.KEYCODE_BUTTON_R1,
    )
    private val DEFAULT_LABELS: Map<Int, String> = mapOf(
        KeyEvent.KEYCODE_BUTTON_A to "OK", KeyEvent.KEYCODE_BUTTON_B to "Back", KeyEvent.KEYCODE_BUTTON_X to "A",
        KeyEvent.KEYCODE_BUTTON_Y to "X", KeyEvent.KEYCODE_BUTTON_THUMBL to "Y", KeyEvent.KEYCODE_BUTTON_THUMBR to "Z",
    )

    /** The running game's own names for its buttons (from its mkxp.json), by pad key. Set when a game starts. */
    @Volatile var gameNames: Map<Int, String> = emptyMap()

    /** Longest game name that fits on a touch button; longer ones keep the plain name (the full name is in Controls). */
    private const val FITS = 6

    /** What a touch button says: the game's short name if it has one that fits, else OK / Back / A / X / Y / Z. */
    fun label(keyCode: Int): String? {
        val game = gameNames[keyCode]?.substringBefore('/')?.trim()
        return if (!game.isNullOrEmpty() && game.length <= FITS) game else DEFAULT_LABELS[keyCode]
    }

    /**
     * Reads `bindingNames` from a game's mkxp.json (mkxp-z's own settings file, JSON with comments and trailing commas,
     * so it's read leniently). Unknown keys and empty names are ignored. Returns pad key → name.
     */
    fun bindingNames(mkxpJson: String): Map<Int, String> {
        val noComments = mkxpJson.lines().joinToString("\n") { line ->
            val i = line.indexOf("//")
            if (i >= 0 && line.substring(0, i).count { it == '"' } % 2 == 0) line.substring(0, i) else line
        }
        val block = Regex(""""bindingNames"\s*:\s*\{([^}]*)\}""").find(noComments)?.groupValues?.get(1) ?: return emptyMap()
        return Regex(""""([a-zA-Z])"\s*:\s*"((?:[^"\\]|\\.)*)"""").findAll(block).mapNotNull { m ->
            val key = RGSS_KEYS[m.groupValues[1].lowercase()[0]] ?: return@mapNotNull null
            val name = m.groupValues[2].replace("\\\"", "\"").trim().take(40)
            if (name.isEmpty()) null else key to name
        }.toMap()
    }

    /** Loads the game's button names (no mkxp.json, or an unreadable one = none). */
    fun loadNames(gameDir: File) {
        gameNames = runCatching { bindingNames(File(gameDir, "mkxp.json").readText()) }.getOrDefault(emptyMap())
    }
}
