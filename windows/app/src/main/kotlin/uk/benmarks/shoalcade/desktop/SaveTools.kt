package uk.benmarks.shoalcade.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uk.benmarks.shoalcade.ApiException
import uk.benmarks.shoalcade.Console
import uk.benmarks.shoalcade.Game
import uk.benmarks.shoalcade.HttpException
import uk.benmarks.shoalcade.JellyfinClient
import uk.benmarks.shoalcade.SaveHistory
import uk.benmarks.shoalcade.SaveSyncCore
import uk.benmarks.shoalcade.SaveVersion
import uk.benmarks.shoalcade.Storage
import uk.benmarks.shoalcade.SyncResult
import uk.benmarks.shoalcade.SyncStore
import uk.benmarks.shoalcade.sizeLabel
import java.io.File
import java.io.IOException

/**
 * The saves panel's actions ("Saves: <game>"), with no screen in them so the tests run exactly these. The phone's
 * own ones go through the shared [SaveSyncCore] (Use this save, Replace with a file…, the file copy); Pin / Name and
 * the browser saves manager's tools go through [DesktopApi] with the browser's rules:
 *  - **Move to another game**: only to a game with the same save format (same console, or Game Boy and Game Boy Color:
 *    the server's `SaveFormat`), never to itself. The target's save in use stays in use if it has one.
 *  - **Delete a version**: never the one in use, never a pinned one (the server refuses them too).
 *  - **Start fresh**: no save in use on Jellyfin afterwards; every version stays in the list.
 *  - **Delete all saves of a removed game**: only for a game no longer in the library, after typing its name
 *    ([sameName], the browser's rule), with the counts the person was shown (the server refuses if they changed).
 *  - **Download all my saves**: the server's zip.
 * None of them ever deletes or changes a save on this PC, except Start fresh, which backs it up and takes it off so
 * this PC starts from the beginning too (otherwise the next upload would quietly bring it back).
 */
class SaveTools(
    private val client: JellyfinClient,
    private val api: DesktopApi,
    private val core: SaveSyncCore,
    private val syncStore: SyncStore,
) {
    suspend fun history(game: Game): SaveHistory = client.history(game.id)

    /** "Use this save" (phone words; the phone's own SaveSyncCore.restore, which then puts it on this PC if downloaded). */
    suspend fun use(game: Game, version: Int, base: Int): SyncResult = withRefusalNote(core.restore(game, version, base))

    /**
     * "Replace with a file…": PS1/N64 files of any other size are refused before anything is sent ([Console.wrongSaveSize]),
     * then the phone's SaveSyncCore.replace (checked against [base]; a conflict comes back to ask: "Use my file" =
     * force, "Keep Jellyfin's" = history).
     */
    suspend fun replace(game: Game, bytes: ByteArray, base: Int, mode: String, allowBlank: Boolean = false): SyncResult {
        game.console?.wrongSaveSize(bytes.size)?.let { return SyncResult.Failed(it) }
        if (bytes.size > MAX_FILE) return SyncResult.Failed("That file is far too big to be a save.")
        return withRefusalNote(core.replace(game, bytes, base, mode, allowBlank))
    }

    /** "Save a copy to a file…": one version, written whole (a temporary file first). */
    suspend fun saveCopy(game: Game, version: Int, dest: File): String {
        val d = client.getSave(game.id, version) ?: throw IOException("That version has gone.")
        withContext(Dispatchers.IO) { AtomicFile.write(dest, d.bytes) }
        return "Saved a copy of version $version."
    }

    /** Pin / Unpin (the browser's words for the result). */
    suspend fun pin(game: Game, v: SaveVersion): String {
        api.updateVersion(game.id, v.version, pinned = !v.pinned)
        return if (v.pinned) "Unpinned version ${v.version}" else "Pinned. Version ${v.version} won't be cleared out."
    }

    suspend fun name(game: Game, v: SaveVersion, label: String, max: Int): String {
        val clean = JellyfinClient.cleanLabel(label, max)
        api.updateVersion(game.id, v.version, label = clean, maxLabel = max)
        return if (clean.isEmpty()) "Name removed" else "Named version ${v.version}"
    }

    // ---- The browser saves manager's tools ----

    /**
     * Games [game]'s save can move to (the browser's moveTargets): same save format, not itself, by name. [picked] = the
     * console the person chose for a removed game whose console wasn't recorded (F7 gap 2); a recorded one always wins.
     */
    fun moveTargets(game: Game, games: List<Game>, picked: Console? = null): List<Game> {
        val f = saveFormat(game.console ?: picked) ?: return emptyList()
        return games.filter { it.id != game.id && saveFormat(it.console) == f }.sortedBy { it.name.lowercase() }
    }

    /**
     * Moves every version (and Jellyfin's snapshots and cheats of the game) to [to]. Returns the browser's message.
     * [picked] is sent as `fromConsole` only when the save's console wasn't recorded (the browser's saves.js rule).
     */
    suspend fun move(game: Game, to: Game, hadVersions: Boolean, picked: Console? = null): String {
        val from = game.console ?: picked
        require(saveFormat(from) != null && saveFormat(from) == saveFormat(to.console) && to.id != game.id) {
            "Saves only move to a game with the same save format."
        }
        val notes = api.move(game.id, to.id, if (game.console == null) picked?.key else null)
        val what = if (hadVersions) "Save" else "Snapshots"
        return (listOf("$what moved to “${to.name}” from “${game.name}”.") + notes).joinToString(" ")
    }

    /** "Delete" on an old version: never the one in use or a pinned one. */
    suspend fun deleteVersion(game: Game, v: SaveVersion): String {
        if (v.isCurrent) throw IOException("The save in use can't be deleted.")
        if (v.pinned) throw IOException("A pinned save can't be deleted. Unpin it first.")
        api.deleteVersion(game.id, v.version)
        return "Version ${v.version} deleted"
    }

    /**
     * "Start fresh" ([base] = the version shown in use). Jellyfin's versions all stay in the list. The save on this PC
     * (if any) is backed up first and then taken off, and this PC forgets which version it last synced, so the game
     * starts from the beginning here too and its next save goes up as the new one. If the backup fails, the PC save
     * stays and the person is told.
     */
    suspend fun startFresh(game: Game, base: SaveVersion): String {
        api.startFresh(game.id, base.version)
        // Review #6: this PC forgets its last sync only once its save is really off (or there was none). If the backup
        // or the removal fails, the save stays whole (removal is all or nothing) and the PC keeps its sync mark.
        val (note, off) = withContext(Dispatchers.IO) {
            try {
                if (Storage.readSave(core.userId, game) == null) return@withContext "" to true
                Storage.backupSave(core.userId, game)
            } catch (e: IOException) {
                return@withContext " The save on this PC couldn't be backed up (${e.message}), so it was left as it is." to false
            }
            try {
                removePcSave(game)
                " The save on this PC was backed up and taken off, so this PC starts from the beginning too." to true
            } catch (e: Storage.SavePartlyRemovedException) {
                // Review S6: the rollback failed too. Said as it is; its uploads are held until a whole save is back.
                Log.w("Start fresh: the PC save was only partly put back", e)
                " The save on this PC was backed up, but taking it off failed part way. ${PcWords.of(e.message ?: "")}" to false
            } catch (e: IOException) {
                Log.w("Start fresh: the PC save couldn't be taken off", e)
                " The save on this PC was backed up, but a file of it is in use, so it was left as it is (all of it)." to false
            }
        }
        if (off) SyncMarks.forget(syncStore, core.userId, game.id)
        return "Started fresh. Version ${base.version} is kept in the list.$note"
    }

    private fun removePcSave(game: Game) {
        if (game.console?.hasFolderSaves == true) {
            // Only the save files the phone's Storage reads (PSP/SAVEDATA, the 3DS SD card's Nintendo 3DS folder, the
            // Wii data folders, the GameCube cards, the PS2 card), never the rest of the folder (an installed Wii game).
            Storage.removeFolderSave(core.userId, game)
        } else {
            val f = Storage.saveFile(core.userId, game)
            if (f.exists() && !f.delete()) throw IOException("Couldn't take the save off.")
            Storage.clockFile(core.userId, game).takeIf { it.isFile }?.delete() // the clock belonged with that save (backed up too)
        }
    }

    /**
     * "Delete all saves for “<name>”?": only a game no longer in the library ([removed]), only after [typed] matches
     * its name (or "delete" when the name isn't known), with the counts shown ([versions], [snapshots]).
     */
    suspend fun deleteAll(game: Game, removed: Boolean, nameKnown: Boolean, typed: String, versions: Int, snapshots: Int): String {
        if (!removed) throw IOException("This game is in your library. Delete old versions one at a time instead.")
        if (!sameName(typed, if (nameKnown) game.name else "delete")) throw IOException("That didn't match, so nothing was deleted.")
        api.deleteAll(game.id, versions, snapshots)
        return "Deleted all saves for “${if (nameKnown) game.name else "Unknown game"}”."
    }

    /** "Download all my saves": the zip goes to [dest]. */
    suspend fun downloadAll(dest: File): String {
        api.exportAll(dest)
        return "Downloaded all your saves (${sizeLabel(dest.length())})."
    }

    private fun withRefusalNote(r: SyncResult): SyncResult =
        if (SaveSource.refusedByPlugin(r)) SyncResult.Failed(PcWords.of(r.message) + " " + SaveSource.REFUSED_NOTE, (r as SyncResult.Failed).tryLater, 400)
        else r

    companion object {
        /** Biggest save any console allows (PSP / 3DS), as the phone's SavesPanel. */
        const val MAX_FILE = 64L * 1024 * 1024

        /** The server's save format family (GameConsole.SaveFormat): Game Boy and Game Boy Color share "gb". */
        fun saveFormat(c: Console?): String? = when (c) {
            null -> null
            Console.GBC -> Console.GB.key
            else -> c.key
        }

        /** The browser's formatNames (saves.js): every console sharing [c]'s save format, "Game Boy or Game Boy Color". */
        fun formatNames(c: Console): String = Console.entries.filter { saveFormat(it) == saveFormat(c) }.joinToString(" or ") { it.tag }

        /** The browser's sameName (saves.js): same text, ignoring case and extra spaces; never empty. */
        fun sameName(a: String?, b: String?): Boolean {
            fun f(s: String?) = (s ?: "").trim().replace(Regex("\\s+"), " ").lowercase()
            return f(a).isNotEmpty() && f(a) == f(b)
        }

        /** "Jellyfin refused" text for a failed browser tool: the server's own words, or that it couldn't be reached. */
        fun failText(e: Exception): String = when (e) {
            is HttpException -> e.message ?: "Refused by the server."
            is IOException -> e.message ?: "Couldn't reach Jellyfin."
            else -> e.message ?: e.javaClass.simpleName
        }

        fun conflict(e: Exception) = e is ApiException && e.code == 409
    }
}

/**
 * The one place the desktop touches SaveSyncCore's sync marks directly: after Start fresh, this PC forgets which
 * Jellyfin version it last saw (key `b|<user>|<id>|main`, SaveSync.kt `baseKey`) and any ⚠️ (`c|<user>|<id>`), so the
 * next save goes up against "no save in use". SyncMarksTest checks this against [SaveSyncCore.synced] /
 * [SaveSyncCore.hasConflict], so a change to the phone's key format fails the tests instead of drifting.
 */
object SyncMarks {
    fun forget(store: SyncStore, userId: String, gameId: String) {
        store.set("b|$userId|$gameId|main", null)
        store.set("c|$userId|$gameId", null)
    }
}
