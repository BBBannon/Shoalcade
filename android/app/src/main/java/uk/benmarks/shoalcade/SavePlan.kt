package uk.benmarks.shoalcade

/**
 * The "newer save" rule (bundle s0.2 item 5), used everywhere a save moves between the phone and Jellyfin:
 * Close Game (and Upload) now, Push all / Pull all next (item 6).
 *  - a save that would replace a **newer** one (phone or server) is never sent without a confirmation showing both
 *    dates ([Plan.ConfirmNewer]); the replaced save always goes to the server's history / the phone's backups first
 *    (that part is done by [SaveSyncCore.keepPhone] / [SaveSyncCore.download]);
 *  - a change on both sides is a conflict ([Plan.Conflict]): it always asks, never picks a winner (D35);
 *  - nothing is sent when there's nothing new ([Plan.Skip]).
 * Plain Kotlin, so the JVM tests check exactly this.
 */
object SavePlan {
    enum class Way { UPLOAD, DOWNLOAD }

    enum class Why {
        /** There's no save to send (phone save missing or blank for an upload; no server save for a download). */
        NO_SAVE,
        /** The phone and Jellyfin already hold the same bytes. */
        SAME,
        /** The sending side hasn't changed since this phone last synced. */
        NOTHING_NEW,
    }

    sealed class Plan {
        data class Skip(val why: Why) : Plan()
        /** Safe to send with the normal version check. */
        object Go : Plan()
        /** Would replace a save that is newer: ask first, showing both dates (epoch ms; null = unknown). */
        data class ConfirmNewer(val phoneTime: Long?, val serverTime: Long?) : Plan()
        /** Both sides changed since the last sync: the user chooses (D35). */
        object Conflict : Plan()
    }

    /**
     * [phoneSha] = SHA-256 of the phone save, null if there's none or it's blank. [phoneTime] = when the phone save
     * was written. [server] = Jellyfin's current save (null = none). [synced] = what this phone last uploaded/downloaded.
     */
    fun plan(way: Way, phoneSha: String?, phoneTime: Long?, server: SaveVersion?, synced: Synced?): Plan {
        val phoneChanged = phoneSha != null && phoneSha != synced?.sha256
        val serverChanged = server != null && server.version != synced?.version
        val upload = way == Way.UPLOAD
        // Nothing to send from the sending side.
        if (if (upload) phoneSha == null else server == null) return Plan.Skip(Why.NO_SAVE)
        // Nothing on the receiving side to replace.
        if (if (upload) server == null else phoneSha == null) return Plan.Go
        if (phoneSha == server!!.sha256) return Plan.Skip(Why.SAME)
        if (if (upload) !phoneChanged else !serverChanged) return Plan.Skip(Why.NOTHING_NEW)
        if (phoneChanged && serverChanged) return Plan.Conflict
        val serverTime = server.createdMillis
        val (incoming, replaced) = if (upload) phoneTime to serverTime else serverTime to phoneTime
        return if (replacesNewer(incoming, replaced)) Plan.ConfirmNewer(phoneTime, serverTime) else Plan.Go
    }

    /** True if the save being replaced is newer than the one replacing it. An unknown incoming time asks too (safe side). */
    fun replacesNewer(incoming: Long?, replaced: Long?): Boolean = replaced != null && (incoming == null || replaced > incoming)

    /** Which of the two is newer, in words for the dialogs; null if the times can't be compared. */
    fun newer(phoneTime: Long?, serverTime: Long?): String? = when {
        phoneTime == null || serverTime == null || phoneTime == serverTime -> null
        phoneTime > serverTime -> "phone"
        else -> "server"
    }

    /** "Phone save: …" / "Jellyfin save: …" lines with the newer one marked. Times in the phone's own time zone. */
    fun dateLines(phoneTime: Long?, server: SaveVersion?, fmt: (Long) -> String = ::formatTime,
                  exact: (Long) -> String = ::formatTimeExact): Pair<String, String> {
        val serverTime = server?.createdMillis
        // Same minute: show seconds, so the two dates never look identical while one is marked newer.
        val fmt = if (phoneTime != null && serverTime != null && fmt(phoneTime) == fmt(serverTime)) exact else fmt
        val n = newer(phoneTime, serverTime)
        val phone = "Phone save: " + (phoneTime?.let(fmt) ?: "none") + (if (n == "phone") " (newer)" else "")
        val jf = "Jellyfin save: " + when {
            server == null -> "none"
            serverTime == null -> "version ${server.version}, time unknown"
            else -> "${fmt(serverTime)}, version ${server.version}"
        } + (if (n == "server") " (newer)" else "")
        return phone to jf
    }

    /** Body of the "replace a newer save?" confirmation, for either direction. */
    fun confirmText(way: Way, gameName: String, phoneTime: Long?, server: SaveVersion?, fmt: (Long) -> String = ::formatTime,
                    exact: (Long) -> String = ::formatTimeExact): String {
        val (phone, jf) = dateLines(phoneTime, server, fmt, exact)
        return if (way == Way.UPLOAD) {
            "The save on Jellyfin for $gameName is newer than the one on this phone.\n\n$phone\n$jf\n\n" +
                "If you upload, Jellyfin's save is kept in its history first, so it isn't lost."
        } else {
            "The save on this phone for $gameName is newer than the one on Jellyfin.\n\n$phone\n$jf\n\n" +
                "If you continue, the phone's save is kept in the phone's backups and in Jellyfin's history first, so it isn't lost."
        }
    }
}

/** What one try at putting the game's latest in-game save on the phone did (M1). */
enum class SaveTry {
    /** It's on the phone (written now, or the phone already had exactly this). */
    KEPT,
    /** Nothing to keep: no save yet, saving off, or a snapshot load's copy (D90). */
    NOTHING,
    /** The game didn't hand over its save in time. */
    NOT_READ,
    /** The phone couldn't write it. */
    NOT_WRITTEN;

    /** The latest in-game save may not be on the phone. */
    val failed get() = this == NOT_READ || this == NOT_WRITTEN
}

/**
 * M1: Close Game never says the save is kept when it isn't. A failed try is retried once; if it still fails the
 * game stays open with [WARNING_TITLE] until the person chooses [KEEP_PLAYING] or [CLOSE_ANYWAY].
 */
object CloseSave {
    const val WARNING_TITLE = "Your latest save may not be kept"
    const val KEEP_PLAYING = "Keep playing"
    const val CLOSE_ANYWAY = "Close anyway"

    fun warning(t: SaveTry): String = (if (t == SaveTry.NOT_WRITTEN) "Couldn't write the latest save to the phone (tried twice)."
        else "Couldn't read the latest save from the game (tried twice).") +
        " If you close anyway, the phone keeps the last save it got (up to 30 seconds older) and anything since may be lost. " +
        "Keep playing to try again in a moment."

    /** Toast for Close Game (no upload). [saveOff] = saving was off this session; [mayMiss] = the person chose [CLOSE_ANYWAY]. */
    fun closedText(saveOff: Boolean, mayMiss: Boolean): String = when {
        saveOff -> "Game closed. Saving was off this session, so your save on the phone is unchanged. Nothing was uploaded."
        mayMiss -> "Game closed. Your latest save may not be on the phone. Nothing was uploaded."
        else -> "Game closed. Your save is kept on the phone and wasn't uploaded."
    }

    /** Put before the upload result so it's honest about what was uploaded ("" = nothing to add). */
    fun uploadNote(saveOff: Boolean, mayMiss: Boolean): String = when {
        saveOff -> "Saving was off this session. "
        mayMiss -> "Your latest save may not be on the phone. "
        else -> ""
    }
}
