package uk.benmarks.shoalcade

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** Small key/value store for sync state (which server save version this phone last saw). Not secret. */
interface SyncStore {
    fun get(key: String): String?
    fun set(key: String, value: String?)
}

private class PrefsSyncStore(context: Context) : SyncStore {
    // "scsync" (L18), copied once from dev 2's "jgsync" by SyncRename. v0.4's "sync" prefs (JellyEMU file times)
    // are left untouched.
    private val prefs = SyncRename.open(context)
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun set(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }
}

/** The server save version this phone last uploaded or downloaded, and the SHA-256 of those bytes. */
data class Synced(val version: Int, val sha256: String)

sealed class SyncResult(val message: String) {
    /** [skipped] = why nothing moved (null = a save was sent or brought to the phone). Push all / Pull all count by it. */
    class Done(message: String, val skipped: SavePlan.Why? = null) : SyncResult(message)
    /** The server has a different save: the user must choose ([SaveSyncCore.keepPhone] / [SaveSyncCore.keepServer]). */
    class Conflict(val server: SaveVersion?, message: String) : SyncResult(message)
    /**
     * [tryLater] = the server couldn't be reached or isn't taking saves just now (network, Wi-Fi sign-in page, login,
     * busy, server error). False when the server refused this one game's save (too big, blank, unknown console).
     */
    class Failed(message: String, val tryLater: Boolean = false) : SyncResult(message)
    /**
     * Item 5 rule ([SavePlan]): sending this save would replace a newer one. Nothing was sent; ask with both dates,
     * then [SaveSyncCore.keepPhone] (upload) if the user says yes.
     */
    class NeedsConfirm(val server: SaveVersion?, val phoneTime: Long?, message: String) : SyncResult(message)
}

/**
 * Moves the in-game save between the phone and the Shoalcade plugin (D35). Plain Kotlin, so the JVM tests and
 * the test-bed integration test run exactly this code.
 *
 * Rules (CLAUDE.md "saves are precious"):
 *  - never upload a blank save;
 *  - every upload carries the version this phone last saw; a 409 is never resolved silently — the user picks, and
 *    the losing copy is kept in the server's history (and on the phone) either way;
 *  - before the phone save is replaced it is backed up on the phone, and if it has changes the server hasn't seen
 *    it is also stored in the server's history (mode=history). If either step fails, nothing is replaced.
 */
class SaveSyncCore(
    private val client: JellyfinClient,
    private val userId: String,
    private val state: SyncStore,
    private val ensureCompatible: suspend () -> Unit = {},
) {
    // "|main" kept from the days of a separate DS browser channel (D52), so saved state still matches (D91).
    private fun baseKey(game: Game) = "b|$userId|${game.id}|main"
    private fun conflictKey(game: Game) = "c|$userId|${game.id}"

    /** D90: the phone save was written by the game after a snapshot was loaded (set by [GameActivity]). */
    fun savedAfterSnapshot(game: Game) = state.get(afterSnapshotKey(userId, game.id)) != null
    private fun clearAfterSnapshot(game: Game) {
        state.set(afterSnapshotKey(userId, game.id), null)
        state.set(cheatsKey(userId, game.id), null) // D143: the same goes for the "made with cheats on" mark
    }

    /** D143: the phone save was written while cheats were on (set by [GameActivity]); the upload says so. */
    fun savedWithCheats(game: Game) = state.get(cheatsKey(userId, game.id)) != null

    fun synced(game: Game): Synced? =
        state.get(baseKey(game))?.split(':')?.takeIf { it.size == 2 }
            ?.let { (v, s) -> v.toIntOrNull()?.let { Synced(it, s) } }

    /** True when an upload hit a conflict the user hasn't settled yet (shown as ⚠️ in the library). */
    fun hasConflict(game: Game) = state.get(conflictKey(game)) != null

    /**
     * N7 + bundle s0.2 item D: never-uploaded phone saves for games Jellyfin no longer lists ([listed] = ids it lists
     * now), minus the ones already mentioned. Each is mentioned once; a game drops off the "told" list when it's no
     * longer an orphan (listed again, or uploaded), so if it goes missing again it's mentioned again.
     * Only reads the saves; never changes or deletes them.
     */
    fun orphansToMention(listed: Set<String>): Set<String> {
        val orphans = Storage.savedGameIds(userId).filter { id -> id !in listed && synced(Game(id, id, null, "", false)) == null }.toSet()
        val told = state.get(orphanKey)?.split('\n')?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
        state.set(orphanKey, orphans.sorted().joinToString("\n").ifEmpty { null })
        return orphans - told
    }
    private val orphanKey get() = "o|$userId"
    private fun goneNameKey(id: String) = "n|$userId|$id"

    /**
     * B9: remembers the name and console of every game with a phone save (F9: or download or snapshot) that has just left the list ([before] = the
     * list last time, [listed] = ids listed now), so the "Not in your library" list can name it later. A game listed
     * again is forgotten. Only this small note changes; save files are never touched.
     */
    fun rememberGone(before: List<Game>, listed: Set<String>) {
        val was = before.associateBy { it.id }
        for (id in localGameIds()) {
            if (id in listed) { if (state.get(goneNameKey(id)) != null) state.set(goneNameKey(id), null); continue }
            val g = was[id] ?: continue
            state.set(goneNameKey(id), (g.console?.key ?: "") + "\n" + g.name)
        }
    }

    /** F9: every game with something on this phone for this user: a save, a download or a snapshot. Reads only. */
    private fun localGameIds(): Set<String> =
        (Storage.savedGameIds(userId) + Storage.downloadedGameIds().keys + Storage.snapshotGameIds(userId))
            .filter { it.isNotBlank() && it != "." && it != ".." }.toSet() // G5 DL2: a stray ".sav" (id "") never deletes roms/<console>/

    /**
     * B9 "Not in your library": every game Jellyfin no longer lists ([listed]) that still has a save, a download (F9)
     * or a snapshot (F9) on this phone, newest first. Named from [rememberGone], else from a ROM still on the phone,
     * else unnamed. The console is the download's folder when there is one. Reads only; never changes or deletes.
     */
    fun orphanSaves(listed: Set<String>): List<OrphanSave> {
        val downloads = Storage.downloadedGameIds()
        val snapshots = Storage.snapshotGameIds(userId)
        return localGameIds().filter { it !in listed }.map { id ->
            val note = state.get(goneNameKey(id))?.split('\n', limit = 2)
            val rom = if (note == null) Storage.findRom(id) else null
            val folder = Storage.hasSaveFolder(userId, id)
            val console = downloads[id] ?: note?.let { Console.fromKey(it[0]) } ?: rom?.first ?: if (folder) Console.PSP else null
            val name = note?.getOrNull(1)?.ifEmpty { null } ?: rom?.second?.nameWithoutExtension
            val game = OrphanSave.gameFor(id, name, console, folder)
            val bytes = Storage.readSave(userId, game)
            val sha = if (Storage.isBlank(bytes)) null else JellyfinClient.sha256(bytes!!)
            val snapshot = if (id in snapshots) Storage.stateFile(userId, game).lastModified() else null
            OrphanSave(id, name, console, folder, Storage.saveTime(userId, game), bytes?.size?.toLong() ?: 0,
                onJellyfin = sha != null && sha == synced(game)?.sha256, blank = sha == null, hasSave = bytes != null,
                download = if (id in downloads) Storage.downloadSize(game) else null,
                playable = Storage.romFile(game) != null, snapshotTime = snapshot)
        }.sortedByDescending { maxOf(it.time ?: 0, it.snapshotTime ?: 0) }
    }

    /** The phone save's bytes for [o] (a zip for a PSP memory stick). Reads only. */
    fun orphanBytes(o: OrphanSave): ByteArray? = Storage.readSave(userId, o.game)

    /**
     * B9 "Upload to another game…": sends the phone save of [o] to [target] on Jellyfin, checked against the target's
     * save in use right now ([mode] "normal"; a conflict comes back to ask: "force" / "history" as in "Replace with a
     * file"). Jellyfin keeps the target's old save in its list; if the target is downloaded, its phone save is backed
     * up before the new one is put on. The orphan save itself stays on this phone, untouched.
     */
    suspend fun uploadOrphan(o: OrphanSave, target: Game, mode: String = "normal"): SyncResult {
        val bytes = withContext(Dispatchers.IO) { orphanBytes(o) }
        if (Storage.isBlank(bytes)) return SyncResult.Failed("That phone save is empty, so it wasn't uploaded.")
        val base = try {
            ensureCompatible()
            client.history(target.id).current
        } catch (e: IOException) {
            return failed(e, "Couldn't upload the save")
        }
        return replace(target, bytes!!, base, mode)
    }

    private fun record(game: Game, v: SaveVersion?) {
        state.set(baseKey(game), v?.let { "${it.version}:${it.sha256}" })
        state.set(conflictKey(game), null)
    }

    private fun record(game: Game, version: Int, sha: String) {
        state.set(baseKey(game), "$version:$sha")
        state.set(conflictKey(game), null)
    }

    private fun markConflict(game: Game) = state.set(conflictKey(game), "1")

    private suspend fun phoneSave(game: Game): ByteArray? = withContext(Dispatchers.IO) { Storage.readSave(userId, game) }

    /** True if the phone save has bytes the server hasn't seen from this phone. */
    suspend fun phoneHasNewChanges(game: Game): Boolean {
        val b = phoneSave(game)
        if (Storage.isBlank(b)) return false
        return JellyfinClient.sha256(b!!) != synced(game)?.sha256
    }

    private fun versionText(v: SaveVersion?) = v?.let { " (version ${it.version})" } ?: ""

    /** "Use this save" makes a copy: " (as version 7)", the browser saves manager's words. */
    private fun asVersion(v: SaveVersion?) = v?.let { " (as version ${it.version})" } ?: ""

    /** " as version 7": "Uploaded as version 7.", the browser saves manager's words. */
    private fun asNew(v: SaveVersion?) = v?.let { " as version ${it.version}" } ?: ""

    private fun withWarnings(msg: String, w: List<String>) = if (w.isEmpty()) msg else "$msg Check this: ${w.joinToString(" ")}"

    private fun failed(e: IOException, what: String) = SyncResult.Failed("$what: ${e.message}", tryLater = tryLater(e))

    /**
     * F3: says plainly whether anything was sent, and that nothing uploads by itself, so the person knows to try again
     * later. After a timeout the server may have stored it (G5 DL1); uploading again is safe (same bytes = unchanged,
     * otherwise the usual conflict question).
     */
    private fun uploadFailed(e: IOException) = SyncResult.Failed("Upload failed: ${e.message} Your save is still safe on the phone." +
        when {
            e !is ServerUnreachableException -> ""
            e.nothingSent -> " Nothing was uploaded: upload it once you're connected (⋮ → Upload save to Jellyfin, or Push all)."
            else -> " The upload may not have finished: upload it again once you're connected (⋮ → Upload save to Jellyfin, or Push all)."
        },
        tryLater = tryLater(e))

    private fun tryLater(e: IOException) = !(e is HttpException && e.code in REFUSED_THIS_GAME)

    /** When the phone save last changed (null = none). */
    fun phoneTime(game: Game): Long? = Storage.saveTime(userId, game)

    /** True if the phone has a save that isn't blank. */
    fun hasPhoneSave(game: Game): Boolean = !Storage.isBlank(Storage.readSave(userId, game))

    /** Uploads the phone save with the version check. A conflict is remembered and returned for the user to settle. */
    suspend fun upload(game: Game): SyncResult {
        val bytes = phoneSave(game)
        if (Storage.isBlank(bytes)) return SyncResult.Failed("No save to upload yet. Save in the game first.")
        return try {
            ensureCompatible()
            when (val r = client.putSave(game.id, bytes!!, base = synced(game)?.version ?: 0, afterSnapshot = savedAfterSnapshot(game), cheats = savedWithCheats(game))) {
                is PutResult.Stored -> {
                    record(game, r.current)
                    clearAfterSnapshot(game)
                    SyncResult.Done(withWarnings(
                        if (r.unchanged) "Jellyfin already has this save${versionText(r.current)}."
                        else "Save uploaded to Jellyfin${versionText(r.current)}.", r.warnings),
                        skipped = if (r.unchanged) SavePlan.Why.SAME else null)
                }
                is PutResult.Conflict -> {
                    markConflict(game)
                    SyncResult.Conflict(r.current, "Not uploaded: Jellyfin has a different save for ${game.name}" +
                        "${versionText(r.current)}, maybe from the browser. Choose which one to keep.")
                }
            }
        } catch (e: IOException) {
            uploadFailed(e)
        }
    }

    /**
     * "Close Game (and Upload)" (item 5; Push all reuses it): uploads the latest in-game save from the phone, under the
     * [SavePlan] rule. Returns [SyncResult.NeedsConfirm] if that would replace a newer Jellyfin save (nothing sent;
     * on yes call [keepPhone], which keeps Jellyfin's in its history), [SyncResult.Conflict] if both changed (marked ⚠️,
     * the user chooses), or Done with "nothing new" when there's nothing to send.
     * [snapshotNoSave] (D90/D95): a snapshot was loaded and the game hasn't saved since, so the snapshot's own save is
     * never on the phone and the words say so.
     */
    suspend fun uploadLatest(game: Game, snapshotNoSave: Boolean = false): SyncResult {
        val bytes = phoneSave(game)
        val sha = if (Storage.isBlank(bytes)) null else JellyfinClient.sha256(bytes!!)
        // Toasts show two lines: "Nothing new to upload" always comes first.
        val snapNote = "Nothing new to upload: you haven't saved in the game since loading the snapshot (a snapshot's save is never uploaded)."
        return try {
            ensureCompatible()
            val server = (client.game(game.id) ?: return SyncResult.Failed("This game isn't on the server any more. Your save is kept on the phone.")).save
            val phoneTime = withContext(Dispatchers.IO) { Storage.saveTime(userId, game) }
            when (val p = SavePlan.plan(SavePlan.Way.UPLOAD, sha, phoneTime, server, synced(game))) {
                is SavePlan.Plan.Skip -> when (p.why) {
                    SavePlan.Why.NO_SAVE -> if (snapshotNoSave) SyncResult.Done(snapNote, p.why)
                        else SyncResult.Failed("No save to upload yet. Save in the game first.")
                    SavePlan.Why.SAME -> {
                        record(game, server)
                        SyncResult.Done(if (snapshotNoSave) snapNote else "Nothing new to upload: Jellyfin already has this save${versionText(server)}.", p.why)
                    }
                    SavePlan.Why.NOTHING_NEW -> SyncResult.Done((if (snapshotNoSave) snapNote else "Nothing new to upload: Jellyfin already has your latest phone save.") +
                        (if (server != null && server.version != synced(game)?.version)
                            " Jellyfin has a newer save${versionText(server)}${server.createdMillis?.let { " from " + formatTime(it) } ?: ""}; to play it here, use ⋮ → Get save from Jellyfin in the library." else ""), p.why)
                }
                SavePlan.Plan.Conflict -> {
                    markConflict(game)
                    SyncResult.Conflict(server, "Not uploaded: Jellyfin has a different save for ${game.name}" +
                        "${versionText(server)}, maybe from the browser. Choose which one to keep.")
                }
                is SavePlan.Plan.ConfirmNewer -> SyncResult.NeedsConfirm(server, phoneTime,
                    SavePlan.confirmText(SavePlan.Way.UPLOAD, game.name, phoneTime, server))
                SavePlan.Plan.Go -> upload(game)
            }
        } catch (e: IOException) {
            uploadFailed(e)
        }
    }

    /**
     * Pull all (item 6): brings Jellyfin's in-use save to the phone under the same [SavePlan] rule, mirrored. Nothing
     * new on Jellyfin → nothing changed; would replace a newer phone save → [SyncResult.NeedsConfirm] (nothing changed;
     * on yes call [keepServer]); both changed → [SyncResult.Conflict] (marked ⚠️). The phone copy is always kept first
     * ([download]).
     */
    suspend fun downloadLatest(game: Game): SyncResult {
        val bytes = phoneSave(game)
        val sha = if (Storage.isBlank(bytes)) null else JellyfinClient.sha256(bytes!!)
        return try {
            ensureCompatible()
            val server = (client.game(game.id) ?: return SyncResult.Failed("This game isn't on the server any more. Your save is kept on the phone.")).save
            val phoneTime = withContext(Dispatchers.IO) { Storage.saveTime(userId, game) }
            when (val p = SavePlan.plan(SavePlan.Way.DOWNLOAD, sha, phoneTime, server, synced(game))) {
                is SavePlan.Plan.Skip -> when (p.why) {
                    SavePlan.Why.NO_SAVE -> SyncResult.Done("Nothing to get: Jellyfin has no save for ${game.name} yet.", p.why)
                    SavePlan.Why.SAME -> { record(game, server); SyncResult.Done("Nothing new on Jellyfin: the phone already has this save${versionText(server)}.", p.why) }
                    SavePlan.Why.NOTHING_NEW -> SyncResult.Done("Nothing new on Jellyfin: the phone already has its latest save." +
                        (if (sha != null && sha != synced(game)?.sha256) " The phone save has changes Jellyfin hasn't got; Push all uploads them." else ""), p.why)
                }
                SavePlan.Plan.Conflict -> {
                    markConflict(game)
                    SyncResult.Conflict(server, "Not changed: ${game.name} has a different save on the phone and on Jellyfin" +
                        "${versionText(server)}. Choose which one to keep.")
                }
                is SavePlan.Plan.ConfirmNewer -> SyncResult.NeedsConfirm(server, phoneTime,
                    SavePlan.confirmText(SavePlan.Way.DOWNLOAD, game.name, phoneTime, server))
                SavePlan.Plan.Go -> download(game, "Jellyfin's save${versionText(server)} is now on the phone. Your old phone save was backed up.")
            }
        } catch (e: IOException) {
            failed(e, "Couldn't get the save")
        }
    }

    /**
     * Conflict choice "Keep the phone's": it becomes current; the server's previous save stays in history (and is copied
     * to the phone's backups). [shown] (L2, Push all's questions): the Jellyfin save the user was shown, null = none; then
     * only that exact save is replaced, and if another arrived since, nothing changes and [SyncResult.Conflict] says to
     * choose again. Without [shown] (in-game and library questions) the save in use now is replaced, as before.
     */
    suspend fun keepPhone(game: Game, shown: Shown? = null): SyncResult {
        val bytes = phoneSave(game)
        if (Storage.isBlank(bytes)) return SyncResult.Failed("There's no phone save to keep.")
        return try {
            ensureCompatible()
            client.getSave(game.id)?.let { old -> withContext(Dispatchers.IO) { Storage.backupServerSave(userId, game, old.bytes) } }
            when (val r = client.putSave(game.id, bytes!!, base = null, mode = "force", afterSnapshot = savedAfterSnapshot(game), cheats = savedWithCheats(game),
                expect = shown?.let { it.save?.version ?: 0 }, expectSha = shown?.save?.sha256)) {
                is PutResult.Stored -> {
                    record(game, r.current)
                    clearAfterSnapshot(game)
                    SyncResult.Done(withWarnings("The phone save is now the one in use${versionText(r.current)}. " +
                        "Jellyfin's previous save is kept in its history.", r.warnings))
                }
                is PutResult.Conflict -> SyncResult.Conflict(r.current, if (shown != null) changedSinceShown(r.current)
                    else "The save changed again on the server. Try again.")
            }
        } catch (e: IOException) {
            failed(e, "Couldn't keep the phone save")
        }
    }

    /**
     * Conflict choice "Keep Jellyfin's": the phone save goes into the server's history first, then the server's comes to
     * the phone. [shown] (L2): only the exact Jellyfin save the user was shown is brought over; if another arrived since,
     * nothing changes and [SyncResult.Conflict] says to choose again.
     */
    suspend fun keepServer(game: Game, shown: Shown? = null): SyncResult = download(game, "Jellyfin's save is now on the phone. " +
        "The phone's save is kept in Jellyfin's history and in the phone's backups.", shown)

    /** The Jellyfin save a question showed the user ([save] null = Jellyfin had none), for [keepPhone] / [keepServer]. */
    class Shown(val save: SaveVersion?)

    private fun isShown(dl: SaveDownload?, shown: Shown): Boolean {
        val want = shown.save ?: return dl == null
        return dl != null && dl.version == want.version && dl.sha256.equals(want.sha256, ignoreCase = true)
    }

    // L3: Push all / Pull all show the same question again with the newer save (SyncAll.askAgain).
    private fun changedSinceShown(now: SaveVersion?) =
        "Not changed: Jellyfin's save changed since you chose${versionText(now)}. Choose again below."

    /** The Jellyfin save in use now (null = none), so a refused choice can be asked again with it (L3). */
    private suspend fun serverNow(game: Game): SaveVersion? =
        client.history(game.id).let { h -> h.versions.firstOrNull { it.version == h.current } }

    /**
     * Replaces the phone save with the server's current one. The phone copy is kept first (phone backup, plus the
     * server's history if the server hasn't seen it); if that fails nothing is replaced.
     */
    suspend fun download(game: Game, doneMessage: String = "Save downloaded from Jellyfin. Your old phone save was backed up.",
                         shown: Shown? = null): SyncResult = try {
        ensureCompatible()
        val dl = client.getSave(game.id)
        when {
            // L2: only the save the user chose; one that arrived since means asking again, and nothing changes.
            shown != null && !isShown(dl, shown) -> serverNow(game).let { SyncResult.Conflict(it, changedSinceShown(it)) }
            dl == null -> SyncResult.Failed(NO_SERVER_SAVE)
            Storage.isBlank(dl.bytes) -> SyncResult.Failed("The Jellyfin save is empty, so it wasn't used.")
            else -> {
                keepPhoneCopy(game)
                withContext(Dispatchers.IO) { Storage.writeSave(userId, game, dl.bytes) }
                record(game, dl.version, dl.sha256)
                clearAfterSnapshot(game) // the phone save is the server's now
                SyncResult.Done(doneMessage)
            }
        }
    } catch (e: IOException) {
        failed(e, "Couldn't get the save")
    }

    /** Phone backup always; server history too if the phone has changes the server hasn't seen. Throws if either fails. */
    private suspend fun keepPhoneCopy(game: Game) {
        val bytes = phoneSave(game) ?: return
        withContext(Dispatchers.IO) { Storage.backupSave(userId, game) }
        if (Storage.isBlank(bytes) || JellyfinClient.sha256(bytes) == synced(game)?.sha256) return
        client.putSave(game.id, bytes, base = null, mode = "history", afterSnapshot = savedAfterSnapshot(game), cheats = savedWithCheats(game)) // never changes the current save
    }

    /**
     * Saves panel "Use this save": makes [version] current on the server (the current one stays in history).
     * That save is then put on the phone if the game is downloaded (phone copy kept first).
     */
    suspend fun restore(game: Game, version: Int, base: Int): SyncResult = try {
        ensureCompatible()
        when (val r = client.restore(game.id, version, base)) {
            is PutResult.Conflict -> SyncResult.Conflict(r.current, "The save changed on the server since the list was loaded. Look again and retry.")
            is PutResult.Stored -> when {
                // Same rule as the confirm dialog and [replace]: only a downloaded game's phone save is replaced (audit N4).
                Storage.isDownloaded(game) -> download(game, "Version $version is now in use${asVersion(r.current)} and is on the phone. " +
                    "Your old phone save was backed up.")
                else -> SyncResult.Done("Version $version is now in use${asVersion(r.current)}. The phone save wasn't changed, " +
                    "because the game isn't downloaded. After you download it, use ⋮ → Get save from Jellyfin.")
            }
        }
    } catch (e: IOException) {
        failed(e, "Couldn't use that save")
    }

    /**
     * Saves panel "Replace with a file": [mode] "normal" (checked against [base]; a conflict comes back to ask),
     * "force" (use my file) or "history" (Keep Jellyfin's; file stored in history only).
     * A new current save is then put on the phone if the game is downloaded.
     */
    suspend fun replace(game: Game, bytes: ByteArray, base: Int, mode: String = "normal", allowBlank: Boolean = false): SyncResult = try {
        ensureCompatible()
        when (val r = client.putSave(game.id, bytes, base, mode, source = "upload", allowBlank = allowBlank)) {
            is PutResult.Conflict -> SyncResult.Conflict(r.current, "Another device saved this game since the list was loaded.")
            is PutResult.Stored -> when {
                r.unchanged -> SyncResult.Done("That file matches${r.version?.let { " version ${it.version}" } ?: " a saved version"}, so nothing changed.")
                mode == "history" -> SyncResult.Done("Your file is kept in the list${asNew(r.version)}. Jellyfin's save stays in use.")
                Storage.isDownloaded(game) ->
                    download(game, withWarnings("Uploaded${asNew(r.current)} and put on the phone. The previous save is kept in the list.", r.warnings))
                else -> SyncResult.Done(withWarnings("Uploaded${asNew(r.current)}. The previous save is kept in the list.", r.warnings))
            }
        }
    } catch (e: IOException) {
        failed(e, "Couldn't upload the file")
    }

    /**
     * a0.5 migration: phone saves from the JellyEMU days have no server version recorded yet. Each one is backed up on
     * the phone, then uploaded with the normal conflict rules (base 0): it's stored if the server has none, matched if
     * the server already has the same bytes (the JellyEMU import), and otherwise flagged for the user to choose.
     * Stops at the first network/server error and tries again next time; a game whose save the server refuses
     * (too big, blank, unknown console) is skipped so it can't hold up the rest (audit N3). Returns the games that need a choice.
     */
    suspend fun migrate(games: List<Game>): List<Game> {
        val needChoice = mutableListOf<Game>()
        for (g in games) {
            if (g.console == null || synced(g) != null) continue
            if (hasConflict(g)) { needChoice += g; continue }
            if (Storage.isBlank(phoneSave(g))) continue
            try {
                withContext(Dispatchers.IO) { Storage.backupSave(userId, g) }
            } catch (e: IOException) {
                continue // no backup, no upload; tried again next time
            }
            when (val r = upload(g)) {
                is SyncResult.Conflict -> needChoice += g
                is SyncResult.Failed -> if (r.tryLater) break // otherwise skip this game and carry on
                is SyncResult.Done, is SyncResult.NeedsConfirm -> {}
            }
        }
        return needChoice
    }

    companion object {
        /** Refusals about this one save, which won't change if migration tries again straight away. */
        private val REFUSED_THIS_GAME = setOf(400, 404, 413, 422)

        fun afterSnapshotKey(userId: String, gameId: String) = "s|$userId|$gameId"
        fun cheatsKey(userId: String, gameId: String) = "x|$userId|$gameId"
    }

    /** After a first download: pull the server save only if the phone has none. */
    suspend fun fetchIfNoLocal(game: Game): SyncResult? {
        if (phoneSave(game) != null) return null
        // Nothing on Jellyfin yet (a new game) is normal after a download, not a failure worth showing.
        return download(game).takeUnless { it is SyncResult.Failed && it.message == NO_SERVER_SAVE }
    }

    /** What the library's save dialog shows: the server's current save and whether it changed since this phone synced. */
    suspend fun check(game: Game): Pair<SaveVersion?, Boolean> {
        ensureCompatible()
        val remote = client.game(game.id) ?: throw IOException("This game isn't on the server any more.")
        val server = remote.save
        return server to (server?.version != synced(game)?.version && !(server == null && synced(game) == null))
    }
}

/** Android wiring for [SaveSyncCore]: session from the encrypted store, sync state in shared prefs. */
class SaveSync(context: Context) {
    private val app = context.applicationContext
    private val store = SessionStore(app)
    private val syncStore: SyncStore = PrefsSyncStore(app)

    /** Null when logged out. */
    fun core(): SaveSyncCore? {
        val s = store.load() ?: return null
        val client = JellyfinClient(store.deviceId, s)
        return SaveSyncCore(client, s.userId, syncStore) { Compat.ensure(client, s.serverUrl) }
    }

    suspend fun upload(game: Game): SyncResult = core()?.upload(game) ?: SyncResult.Failed("Not logged in.")

    private val loggedOut get() = SyncResult.Failed("Not logged in, so nothing was uploaded. Your save is kept on the phone.")

    /** "Close Game (and Upload)": see [SaveSyncCore.uploadLatest]. */
    suspend fun uploadLatest(game: Game, snapshotNoSave: Boolean) = core()?.uploadLatest(game, snapshotNoSave) ?: loggedOut

    /** After the user confirms a newer-save replace, or picks "Keep the phone's" in a conflict. */
    suspend fun keepPhone(game: Game) = core()?.keepPhone(game) ?: loggedOut

    /** Conflict choice "Keep Jellyfin's" (the phone copy goes to Jellyfin's history and the phone's backups first). */
    suspend fun keepServer(game: Game) = core()?.keepServer(game) ?: SyncResult.Failed("Not logged in. Your save is kept on the phone.")

    /** D90: the game wrote this phone save after a snapshot was loaded; the next upload is marked so. Works logged out. */
    fun markSavedAfterSnapshot(userId: String, game: Game) = syncStore.set(SaveSyncCore.afterSnapshotKey(userId, game.id), "1")

    /** D143: this phone save was written while cheats were on; the next upload is marked so. Works logged out. */
    fun markSavedWithCheats(userId: String, game: Game) = syncStore.set(SaveSyncCore.cheatsKey(userId, game.id), "1")

    fun localSaveTime(game: Game): Long? {
        val s = store.load() ?: return null
        return Storage.saveTime(s.userId, game)
    }

    suspend fun fetchIfNoLocal(game: Game) = core()?.fetchIfNoLocal(game)
}

private const val NO_SERVER_SAVE = "There's no save on Jellyfin for this game."
