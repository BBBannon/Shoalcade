package uk.benmarks.shoalcade

import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Small pure pieces the shared phone files need that live inside Android-bound phone files. Each is the phone's own
// code, word for word, with the line it comes from. Plan §5 step 2 (a shared module after the release) removes these.

/** SavesPanel.kt line 31 (needed by SavePlan.kt). */
internal fun formatTime(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

/** SavesPanel.kt line 34: with seconds, for telling apart two saves made in the same minute. */
internal fun formatTimeExact(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(Date(ms))

/**
 * Screenshot.kt lines 21-35, the pure part (needed by GameMenu.kt). The phone's gallery code (MediaStore, PixelCopy)
 * is not here; the Windows player writes its own PNGs (PROTOCOL.md `screenshot`).
 */
internal object Screenshot {
    const val ACTION = "Screenshot"
    /** The Windows folder name under Pictures, as the phone's gallery folder (Pictures/Shoalcade, plan §3.2). */
    const val FOLDER = "Shoalcade"

    /** Characters a file name can't hold (the browser's list), plus control characters. */
    private val BAD = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]+")

    /** "<name> <yyyy-MM-dd HH-mm-ss>.png", the name cleaned and cut to 80 characters ("game" if nothing is left). */
    fun fileName(game: String, at: Date): String {
        val name = game.replace(BAD, "").trim().take(80).trim().ifEmpty { "game" }
        return "$name ${SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.ROOT).format(at)}.png"
    }
}

/**
 * SavesPanel.kt lines 36-46: where a version came from, the browser saves manager's exact words (docs/wording.md),
 * plus Windows saves (`desktop`, D187), "the Windows app" beside "the phone app". The plugin's history labels get the
 * same words with server change S1.
 */
internal fun sourceLabel(s: String) = when (s) {
    "browser" -> "the browser player"
    "app" -> "the phone app"
    "desktop" -> "the Windows app"
    "upload" -> "an uploaded file"
    "restore" -> "an older version"
    "jellyemu" -> "JellyEMU (imported)"
    "moved" -> "another game (moved here)"
    "recovered" -> "a recovered file"
    else -> s.ifEmpty { "unknown" }
}

/** SavesPanel.kt lines 48-51. */
internal fun fromLabel(v: SaveVersion) =
    (if (v.source == "restore" && v.restoredFrom != null) "Copy of version ${v.restoredFrom}" else "From " + sourceLabel(v.source)) +
        (v.device?.let { " ($it)" } ?: "")

/** SavesPanel.kt line 53. */
internal fun sizeLabel(b: Long) = if (b >= 1024 * 1024) "%.1f MB".format(b / 1048576.0) else "${(b + 1023) / 1024} KB"

/** SavesPanel.kt lines 55-63: "version 12, 24 Oct 2026 14:02, from the browser player (Example Phone)". */
internal fun describeVersion(v: SaveVersion) = buildString {
    append("version ${v.version}")
    v.createdMillis?.let { append(", ").append(formatTime(it)) }
    append(", ").append(fromLabel(v).replaceFirstChar { it.lowercase() })
    if (v.origin == "browser") append(", was a browser save (merged in)")
    if (v.afterSnapshot) append(", saved after loading a snapshot")
    if (v.cheats) append(", made with cheats on")
}

/**
 * AppViewModel.kt lines 606-627 (L1): snackbar messages take turns instead of replacing each other, so a once-only
 * notice can't be overwritten by another message before it shows. The same text already showing or waiting isn't
 * added twice.
 */
class Notices {
    private val waiting = ArrayDeque<String>()
    private var current: String? = null

    /** Adds [m]; returns the message to show now. */
    @Synchronized fun post(m: String): String? {
        if (current == null) current = m else if (m != current && m !in waiting) waiting.addLast(m)
        return current
    }

    /** The message showing has been seen; returns the next one (null = none). */
    @Synchronized fun shown(): String? {
        current = waiting.removeFirstOrNull()
        return current
    }
}

/** GameActivity.kt lines 1506-1507: a real save went in but the core now holds a blank one (the save didn't load). */
internal fun saveNotLoaded(console: Console, loaded: ByteArray?, now: ByteArray?): Boolean =
    !console.hasFolderSaves && !Storage.isBlank(loaded) && Storage.isBlank(now)

/** GameActivity.kt line 1509. */
internal const val SAVE_NOT_LOADED = "Your save couldn't be loaded, so saving is off this session to protect it."

/**
 * GameActivity.kt lines 1511-1521: what the core is given at start (save for the core, a real save was kept back). On
 * consoles with one exact save size (PS1, N64) a save of any other size is never given to the core; if it held
 * anything it counts as kept back, so the caller turns saving off and says so. The Windows shell checks this before it
 * starts the player (the player refuses a wrong size too: PROTOCOL.md `saveRefused`), so the rule exists once.
 */
internal fun saveForCore(console: Console, save: ByteArray?): Pair<ByteArray?, Boolean> {
    val exact = console.exactSaveSize ?: return save to false
    if (save == null || save.size == exact) return save to false
    return null to !Storage.isBlank(save)
}
