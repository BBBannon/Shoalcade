package uk.benmarks.shoalcade

import android.content.Context
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * On-phone layout, inside the app's private storage (<files>/library), so uninstalling the app
 * removes every ROM, save and state (D26). Updating the app keeps them.
 *   Shoalcade/roms/<console>/<itemId>/<rom file>
 *   Shoalcade/saves/<userId>/<itemId>.sav
 *   Shoalcade/saves/<userId>/backups/<itemId>-<time>.sav   (last [MAX_BACKUPS] kept; also -server-<time>.sav, -clock-<time>.clock)
 *   Shoalcade/states/<userId>/<itemId>.state                (save state; phone only, previous kept as .state.bak; picture .state.png;
 *                                                            cheats that were on: .state.cheats.json, previous .state.cheats.json.bak)
 *   Shoalcade/cheats/<userId>/<itemId>.json                 (the game's cheats, D143, see CheatStore)
 *   Shoalcade/memstick/<userId>/<itemId>/PSP/SAVEDATA/...      (PSP: one memory stick per game; its "save" is a zip of SAVEDATA)
 *   Shoalcade/memstick/<userId>/<itemId>/...                   (3DS, and on Windows GameCube / Wii / PS2: the core's own
 *                                                            folder; only its save files are zipped, see [FolderSpec])
 */
object Storage {
    private const val MAX_BACKUPS = 10

    lateinit var root: File
        private set

    /** Called once from [ShoalcadeApp] before anything else runs. */
    fun init(ctx: Context) {
        root = File(ctx.filesDir, "library").apply { mkdirs() }
    }

    /** Unit tests only: point storage at a temporary folder. */
    internal fun useRootForTests(dir: File) { root = dir }

    /** Bytes used by everything the app keeps here: games, saves, backups, snapshots (Settings, D132). Reads only. */
    fun bytesUsed(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun gameDir(game: Game) = File(root, "roms/${game.console?.folder ?: "other"}/${game.id}")

    /** The playable ROM file if this game is downloaded, else null. */
    fun romFile(game: Game): File? {
        val c = game.console ?: return null
        val files = gameDir(game).listFiles()
        // D-8: a disc set is playable only once whole (its .m3u is written last), never as a lone disc 1.
        if (game.discs > 1 && c.takesDiscSets) return files?.firstOrNull { it.isFile && it.name.endsWith(DISC_LIST, ignoreCase = true) }
        return playable(c, files)
    }

    /**
     * The file to play in a download folder: a disc set's .m3u (D-8, only written once every disc is in), else the
     * ROM. Consoles without disc sets never look for a .m3u.
     */
    private fun playable(c: Console, files: Array<File>?): File? {
        if (files == null) return null
        if (c.takesDiscSets) files.firstOrNull { it.isFile && it.name.endsWith(DISC_LIST, ignoreCase = true) }?.let { return it }
        return files.firstOrNull { it.isFile && it.extension.lowercase() in c.romExtensions }
    }

    /** D-8: the .m3u of a disc set, as `<gameId>.m3u` (PS2 names its memory card after the game file). */
    fun discListFile(game: Game) = File(gameDir(game), "${game.id}$DISC_LIST")

    /** D-8: writes the set's .m3u (one disc file name per line, relative) once every disc has downloaded. */
    fun writeDiscList(game: Game, discNames: List<String>): File {
        val f = discListFile(game)
        writeText(f, discNames.joinToString("") { it + "\n" })
        return f
    }

    private const val DISC_LIST = ".m3u"

    fun isDownloaded(game: Game) = romFile(game) != null

    /** The folder the raw download lands in, before any unzip. */
    fun downloadFolder(game: Game) = gameDir(game)

    /** Download file name if the server sends none: the known name, else "<id>.<first ROM extension>". */
    fun fallbackRomName(game: Game) =
        game.fileName.ifEmpty { "${game.id}.${game.console?.romExtensions?.first() ?: "bin"}" }

    /** If the download was a .zip, pull out the ROM and delete the zip. */
    fun unpackIfZipped(game: Game, file: File) {
        when (file.extension.lowercase()) {
            "zip" -> {
                val exts = game.console?.romExtensions ?: emptySet()
                var found = false
                ZipInputStream(file.inputStream().buffered()).use { zip ->
                    while (true) {
                        val e = zip.nextEntry ?: break
                        val name = File(e.name).name // never trust paths inside the zip
                        if (!e.isDirectory && name.substringAfterLast('.').lowercase() in exts) {
                            val part = File(file.parentFile, "$name.part")
                            part.outputStream().use { zip.copyTo(it) }
                            if (!part.renameTo(File(file.parentFile, name))) throw IOException("Couldn't unpack the game.")
                            found = true
                            break
                        }
                    }
                }
                file.delete()
                if (!found) throw IOException("No playable ROM found inside the zip.")
            }
            "7z" -> {
                file.delete()
                throw IOException(".7z files aren't supported yet. Store this game as .zip or unzipped on the server.")
            }
        }
    }

    fun deleteDownload(game: Game) {
        gameDir(game).deleteRecursively()
    }

    private fun saveDir(userId: String) = File(root, "saves/$userId")

    fun saveFile(userId: String, game: Game) = File(saveDir(userId), "${game.id}.sav")

    /** PSP: the folder the emulator uses as this game's memory stick. */
    fun memstickDir(userId: String, game: Game) = stickDir(userId, game.id)

    private fun stickDir(userId: String, gameId: String) = File(root, "memstick/$userId/$gameId")

    /**
     * Which files in a folder-save console's folder are the save (S1, plan-windows-gc-wii-ps2 §5). The emulator keeps
     * its own housekeeping files in the same folder; only these are zipped, backed up, restored or taken off, and a
     * restore never touches the rest. [sub] = the save's folder inside the game's folder; [keep] = the save files
     * (path relative to [sub], `/` separators; hidden `.files` never count).
     *  - PSP: `PSP/SAVEDATA`, all of it. 3DS (D158): the core's pretend SD card, `Nintendo 3DS` (save and extra data),
     *    not its pretend NAND.
     *  - Wii (Dolphin): only `User/Wii/title/<hi>/<lo>/data/…`. An installed WiiWare / Virtual Console game's `content`
     *    folder sits beside `data` and is the game, not the save: never zipped, never deleted, kept by a restore.
     *  - GameCube (Dolphin): all of `User/GC` (every region's GCI card folder: the region follows the disc) except
     *    Dolphin's `SRAM.raw`: the console's own settings (language, sound, clock), not game progress, rewritten every
     *    session. Synced, it made every session a new save version (and "both changed" between two PCs), so each PC
     *    keeps its own: never zipped, and a restore or Start fresh leaves it alone.
     *  - PS2 (LRPS2 or PCEE2: the same card in both): the game's own 8 MB memory card, `<game file name>.ps2`, at the
     *    top of the folder. A blank card (every byte the same: never formatted) is no save: never zipped, and never
     *    replaces a real one ([writeSave]).
     */
    private class FolderSpec(val console: Console, val sub: String, val keep: (String) -> Boolean = { true })

    private const val PSP_SAVES = "PSP/SAVEDATA"
    private const val N3DS_SAVES = "Azahar/sdmc/Nintendo 3DS"
    private const val GC_SETTINGS = "SRAM.raw"
    private val WII_DATA = Regex("[^/]+/[^/]+/data/.+")

    /** In the order a folder whose game has gone is recognised by ([folderSaveConsole]): most specific first. */
    private val FOLDER_SPECS = listOf(
        FolderSpec(Console.N3DS, N3DS_SAVES),
        FolderSpec(Console.PSP, PSP_SAVES),
        FolderSpec(Console.WII, "User/Wii/title", keep = { WII_DATA.matches(it) }),
        FolderSpec(Console.GC, "User/GC", keep = { it != GC_SETTINGS }),
        FolderSpec(Console.PS2, "", keep = { '/' !in it && it.endsWith(".ps2", ignoreCase = true) }),
    )

    private fun spec(c: Console?): FolderSpec? = if (c?.hasFolderSaves == true) FOLDER_SPECS.firstOrNull { it.console == c } else null

    private fun specDir(stick: File, sp: FolderSpec) = if (sp.sub.isEmpty()) stick else File(stick, sp.sub)

    /** The save files of [sp] under [dir] (relative path to file), in name order; a blank PS2 card isn't one. Reads only. */
    private fun saveFiles(dir: File, sp: FolderSpec): List<Pair<String, File>> =
        // An empty [FolderSpec.sub] (PS2) means files at the top only: never walk the emulator's whole folder.
        dir.walkTopDown().let { if (sp.sub.isEmpty()) it.maxDepth(1) else it }.filter { it.isFile && !it.name.startsWith(".") }.map { it.relativeTo(dir).invariantSeparatorsPath to it }
            .filter { (rel, f) -> sp.keep(rel) && !(sp.console == Console.PS2 && isBlankFile(f)) }.sortedBy { it.first }.toList()

    /** True if every byte of [f] is 0x00, or every byte 0xFF (a PS2 card never formatted). Reads only. */
    private fun isBlankFile(f: File): Boolean = runCatching {
        f.inputStream().buffered().use { input ->
            val first = input.read()
            var same = true
            val buf = ByteArray(64 * 1024)
            if (first != 0 && first != 0xFF) same = first < 0 // an empty file is blank too
            else while (same) {
                val n = input.read(buf)
                if (n < 0) break
                for (i in 0 until n) if (buf[i].toInt() and 0xFF != first) { same = false; break }
            }
            same
        }
    }.getOrDefault(false)

    /** True if [stick] holds a save of [sp]. Reads only. */
    private fun hasSave(stick: File, sp: FolderSpec) = saveFiles(specDir(stick, sp), sp).isNotEmpty()

    /** The save as bytes: the save file, or for folder-save consoles a zip of its save files ([FolderSpec]). */
    fun readSave(userId: String, game: Game): ByteArray? {
        val sp = spec(game.console) ?: return saveFile(userId, game).takeIf { it.isFile }?.readBytes()
        val dir = specDir(memstickDir(userId, game), sp)
        // The emulator may still be writing (the Windows folder watcher reads while it runs): the files must look
        // the same before and after the read, or a torn card could go up as the save. Tried a few times, then refused.
        // Review S3: a PS2 card or a GCI is fixed-size and written in place, and its time may not move within one
        // read, so the contents are read twice and must match too.
        var files = saveFiles(dir, sp)
        repeat(STEADY_TRIES) {
            if (files.isEmpty()) return null
            val before = stampOf(files)
            val zip = zipFiles(files)
            val again = zipFiles(files)
            val after = saveFiles(dir, sp)
            if (stampOf(after) == before && zip.contentEquals(again)) return zip
            files = after
            Thread.sleep(STEADY_PAUSE_MS)
        }
        throw IOException(SAVE_STILL_CHANGING)
    }

    /** [readSave] refused: the save files changed every time they were read (the game is still saving). */
    const val SAVE_STILL_CHANGING = "The game is still writing its save, so it wasn't read. Try again in a moment."
    private const val STEADY_TRIES = 5
    private const val STEADY_PAUSE_MS = 200L

    private fun stampOf(files: List<Pair<String, File>>) = files.joinToString("\n") { (rel, f) -> "$rel ${f.length()} ${f.lastModified()}" }

    /** When the save last changed on the phone (newest save file for folder saves). */
    fun saveTime(userId: String, game: Game): Long? {
        val sp = spec(game.console) ?: return saveFile(userId, game).takeIf { it.isFile }?.lastModified()
        return saveFiles(specDir(memstickDir(userId, game), sp), sp).maxOfOrNull { it.second.lastModified() }
    }

    /**
     * What a folder save's files look like now (each save file's name, size and time), to notice the game saving
     * (the Windows app's folder watcher, D-6). Null for one-file saves. Reads only.
     */
    fun saveStamp(userId: String, game: Game): String? {
        val sp = spec(game.console) ?: return null
        return stampOf(saveFiles(specDir(memstickDir(userId, game), sp), sp))
    }

    /** Ids of every game with a save on this phone (save files and memory sticks), whether or not it's still listed. */
    fun savedGameIds(userId: String): Set<String> {
        val files = saveDir(userId).listFiles { f -> f.isFile && f.name.endsWith(".sav") }?.map { it.name.removeSuffix(".sav") } ?: emptyList()
        val sticks = File(root, "memstick/$userId").listFiles { f -> f.isDirectory }
            ?.filter { stick -> FOLDER_SPECS.any { hasSave(stick, it) } }
            ?.map { it.name } ?: emptyList()
        return (files + sticks).toSet()
    }

    /**
     * Which console a folder save on the phone belongs to, from the folder itself: 3DS SD card, PSP memory stick, or
     * (Windows) Dolphin's Wii / GameCube saves or a PS2 card (null = no folder save). For a save whose game has gone,
     * so it is read from the right place (D158). Reads only.
     */
    fun folderSaveConsole(userId: String, gameId: String): Console? =
        FOLDER_SPECS.firstOrNull { hasSave(stickDir(userId, gameId), it) }?.console

    /** True if this game's phone save is a folder (memory stick, SD card, Dolphin's User folder…), not a save file. Reads only. */
    fun hasSaveFolder(userId: String, gameId: String) = folderSaveConsole(userId, gameId) != null

    /**
     * Takes a folder save off (Start fresh, after its backup): only its save files ([FolderSpec]), never the
     * emulator's other files (a Wii game installed in the folder stays). Nothing for one-file saves.
     *
     * Exactly the files [readSave] / [backupSave] take, so nothing is deleted that wasn't backed up (hidden `.files`
     * stay). All or nothing: every file is first moved into a hidden folder beside the save, and only then deleted;
     * if one can't be moved (a file in use), the ones already moved go back and it throws [SAVE_NOT_REMOVED], so a
     * failure never leaves half a save behind.
     *
     * Review S6: if some can't be put back either, it throws [SavePartlyRemovedException] and holds the save's uploads
     * ([uploadHeld]): what is left is part of a save, so it never goes up as the current one. The hold ends when a whole
     * save is written back ([writeSave]: a backup restored, or Jellyfin's save fetched) or a later removal succeeds.
     */
    fun removeFolderSave(userId: String, game: Game) {
        val sp = spec(game.console) ?: return
        val stick = memstickDir(userId, game)
        val files = saveFiles(specDir(stick, sp), sp)
        if (files.isEmpty()) return
        val aside = File(stick, ".removing-${System.nanoTime()}")
        val moved = ArrayList<Pair<File, File>>()
        for ((rel, f) in files) {
            val to = File(aside, rel)
            if ((to.parentFile!!.isDirectory || to.parentFile!!.mkdirs()) && move(f, to)) { moved += f to to; continue }
            var allBack = true
            for ((from, at) in moved.asReversed()) if (!move(at, from)) allBack = false
            if (allBack) { aside.deleteRecursively(); throw IOException(SAVE_NOT_REMOVED) } // only empty folders are left in it
            holdFile(userId, game.id).let { it.parentFile?.mkdirs(); it.writeText("partly removed ${System.currentTimeMillis()}") }
            throw SavePartlyRemovedException(SAVE_PARTLY_REMOVED)
        }
        aside.deleteRecursively() // it is no save any more (outside the save folder), so a file left here is harmless
        holdFile(userId, game.id).delete()
    }

    /** How [removeFolderSave] moves a file. Tests make it fail (a file another program holds) on any system. */
    internal var move: (File, File) -> Boolean = { from, to -> from.renameTo(to) }

    /** Review S6: [removeFolderSave] took part of the save off and couldn't put it all back. */
    class SavePartlyRemovedException(message: String) : IOException(message)

    /** Review S6: what is left on this device is only part of a save, so it isn't uploaded until a whole one is back. */
    const val SAVE_PARTLY_REMOVED = "Only part of the save could be put back (a file is in use), so it won't be uploaded. " +
        "Its backup is kept: put it back, or get the save from Jellyfin, before you play."

    /** Hidden (never a save file), beside the folder save: present = uploads of this game's save are held (review S6). */
    private fun holdFile(userId: String, gameId: String) = File(stickDir(userId, gameId), ".upload-held")

    /** True if this game's save must not be uploaded: only part of it is here ([SavePartlyRemovedException]). Reads only. */
    fun uploadHeld(userId: String, gameId: String) = holdFile(userId, gameId).isFile

    /**
     * Review S11: true if two zips of [game]'s folder save hold the same save files, byte for byte, leaving out what
     * isn't the save ([FolderSpec.keep]: Dolphin's SRAM.raw, stored in GameCube saves synced before it was left out).
     * Any zip that can't be read: false.
     */
    fun sameSave(game: Game, a: ByteArray, b: ByteArray): Boolean {
        val sp = spec(game.console) ?: return a.contentEquals(b)
        fun files(z: ByteArray): Map<String, String>? = runCatching {
            val out = HashMap<String, String>()
            ZipInputStream(z.inputStream()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (!e.isDirectory && sp.keep(e.name)) out[e.name] = String(zip.readBytes(), Charsets.ISO_8859_1)
                }
            }
            out
        }.getOrNull()
        val x = files(a) ?: return false
        return x.isNotEmpty() && x == files(b)
    }

    /** [removeFolderSave] failed and put everything back: the save is exactly as it was. */
    const val SAVE_NOT_REMOVED = "Couldn't take the save off (a file is in use), so it was left as it is."

    /** A ROM still on the phone for [gameId] (any console), e.g. for a game Jellyfin no longer lists. Reads only. */
    fun findRom(gameId: String): Pair<Console, File>? = Console.entries.firstNotNullOfOrNull { c ->
        playable(c, File(root, "roms/${c.folder}/$gameId").listFiles())?.let { c to it }
    }

    /**
     * F9: every game with a download folder on the phone holding any file (finished or not), with the console whose
     * folder it is in. Reads only.
     */
    fun downloadedGameIds(): Map<String, Console> = Console.entries.flatMap { c ->
        File(root, "roms/${c.folder}").listFiles { f -> f.isDirectory && f.walkTopDown().any { it.isFile } }
            ?.map { it.name to c } ?: emptyList()
    }.toMap()

    /** Bytes the phone's download of [game] takes (0 = not downloaded). Reads only. */
    fun downloadSize(game: Game): Long = gameDir(game).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** F9: ids of every game with a snapshot on this phone for [userId]. Reads only. */
    fun snapshotGameIds(userId: String): Set<String> =
        File(root, "states/$userId").listFiles { f -> f.isFile && f.name.endsWith(".state") }
            ?.map { it.name.removeSuffix(".state") }?.toSet() ?: emptySet()

    /** F9: deletes the snapshot (its picture and the previous one kept as .bak too). Never touches the save. */
    fun deleteSnapshot(userId: String, game: Game) {
        val f = stateFile(userId, game)
        val cheats = stateCheatsFile(userId, game)
        listOf(f, bak(f), stateImageFile(userId, game), cheats, bak(cheats)).forEach { it.delete() }
    }

    /** A phone backup of a save: from the phone itself, or the Jellyfin save the phone replaced ([fromJellyfin]). */
    data class Backup(val file: File, val time: Long, val fromJellyfin: Boolean)

    /** F9: this game's save backups on the phone (not cartridge clock ones), newest first. Reads only. */
    fun backups(userId: String, gameId: String): List<Backup> {
        val server = "$gameId-server-"
        return backupDir(userId).listFiles { f -> f.isFile && f.name.startsWith("$gameId-") && f.name.endsWith(".sav") }
            ?.mapNotNull { f ->
                val fromJf = f.name.startsWith(server)
                val stamp = f.name.removePrefix(if (fromJf) server else "$gameId-").removeSuffix(".sav")
                stamp.toLongOrNull()?.let { Backup(f, it, fromJf) } // "<id>-clock-…" and other games' "<id>-x-…" skip here
            }?.sortedByDescending { it.time } ?: emptyList()
    }

    /**
     * F9: puts [backup] back as the phone save. The save now on the phone is backed up first (saves are precious);
     * if that fails, nothing is replaced.
     */
    fun restoreBackup(userId: String, game: Game, backup: Backup) {
        val bytes = backup.file.readBytes() // read first: making the new backup may prune this one
        backupSave(userId, game)
        writeSave(userId, game, bytes)
    }

    private fun backupDir(userId: String) = File(saveDir(userId), "backups")

    /** Wall clock for backup names. Tests set it to put several backups in the same millisecond. */
    internal var clock: () -> Long = System::currentTimeMillis

    /**
     * Copies the current local save into backups/ (skipped if identical to the newest backup), then prunes. A Game Boy
     * cartridge clock beside it is backed up too (L4), so the clock that belonged with a replaced save isn't lost.
     */
    fun backupSave(userId: String, game: Game) {
        readClock(userId, game)?.let { keepBackup(userId, game, "${game.id}-clock-", it, ".clock") }
        val cur = readSave(userId, game) ?: return // file, or zip of the folder save's files
        keepBackup(userId, game, "${game.id}-", cur)
    }

    /** Keeps a copy of the Jellyfin save before the phone replaces it. */
    fun backupServerSave(userId: String, game: Game, bytes: ByteArray) =
        keepBackup(userId, game, "${game.id}-server-", bytes)

    private fun keepBackup(userId: String, game: Game, prefix: String, bytes: ByteArray, ext: String = ".sav") {
        val dir = backupDir(userId).apply { mkdirs() }
        // Phone, server and clock backups are rotated separately ("<id>-" would otherwise also match "<id>-server-").
        val others = listOf("${game.id}-server-", "${game.id}-clock-").filter { it != prefix && it.startsWith(prefix) }
        // Newest first by the time in the name, not the file's own time (two files can share a millisecond).
        fun stamp(f: File) = f.name.removePrefix(prefix).substringBefore('.').toLongOrNull() ?: f.lastModified()
        val existing = dir.listFiles()
            ?.filter { f -> f.name.startsWith(prefix) && others.none { f.name.startsWith(it) } }
            ?.sortedByDescending { stamp(it) } ?: emptyList()
        if (existing.firstOrNull()?.readBytes()?.contentEquals(bytes) == true) return
        // Saves are precious: a second backup in the same millisecond (or after the phone's clock went back) must
        // never replace the first, so the new name is always later than the newest one already there.
        val time = maxOf(clock(), (existing.firstOrNull()?.let { stamp(it) } ?: Long.MIN_VALUE) + 1)
        val file = File(dir, "$prefix$time$ext")
        check(!file.exists()) { "Backup ${file.name} already exists." }
        writeAtomically(file, bytes)
        existing.drop(MAX_BACKUPS - 1).forEach { it.delete() } // new one + 9 older = 10
    }

    fun writeSave(userId: String, game: Game, bytes: ByteArray) {
        val sp = spec(game.console) ?: return writeAtomically(saveFile(userId, game), bytes)
        val dir = specDir(memstickDir(userId, game), sp)
        if (sp.console == Console.PS2 && cardsInZipBlank(bytes)) {
            // Saves are precious: an empty card never replaces a real one; with no real one there is nothing to write.
            if (saveFiles(dir, sp).isNotEmpty()) throw IOException(PS2_BLANK_CARD)
            return
        }
        unzipToFolder(bytes, dir, sp.keep)
        holdFile(userId, game.id).delete() // a whole save is back (review S6)
    }

    /** PS2: why [writeSave] refused a zip whose only card is blank while a real card is here. */
    const val PS2_BLANK_CARD = "That PS2 save is an empty memory card, so it didn't replace the save on this PC."

    /** True if no .ps2 card at the top of the zip has anything on it (none, or all blank). Reads only. */
    private fun cardsInZipBlank(bytes: ByteArray): Boolean = runCatching {
        var real = false
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory || '/' in e.name || '\\' in e.name || !e.name.endsWith(".ps2", true)) continue
                if (!isBlank(zip.readBytes())) real = true
            }
        }
        !real
    }.getOrDefault(false) // a broken zip: unzipToFolder refuses it itself

    /** Fixed entry time, so the same files always give the same zip (lets "unchanged" checks work). */
    private const val ZIP_TIME = 315_532_800_000L // 01/01/1980, the earliest zip time

    /** Zips every file under [dir] in name order, skipping hidden ones (.nomedia). Null if nothing is saved yet. */
    fun zipFolder(dir: File): ByteArray? {
        val files = dir.walkTopDown().filter { it.isFile && !it.name.startsWith(".") }.map { it.relativeTo(dir).invariantSeparatorsPath to it }
            .sortedBy { it.first }.toList()
        if (files.isEmpty()) return null
        return zipFiles(files)
    }

    /** The zip of [files] (relative name to file) in the order given, with the fixed entry time. */
    private fun zipFiles(files: List<Pair<String, File>>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, f) in files) {
                zip.putNextEntry(ZipEntry(name).apply { time = ZIP_TIME })
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /**
     * Replaces [dir] with the zip's contents. Unpacks to a temporary folder first, so a bad zip
     * leaves the old save untouched. Entries trying to escape the folder ("../") are refused.
     * [keep] (S1) = which files are the save: entries that aren't are left out, and the files in [dir] that aren't
     * (the emulator's own, e.g. an installed Wii game) are copied into the new folder first, so the swap keeps them.
     */
    fun unzipToFolder(bytes: ByteArray, dir: File, keep: (String) -> Boolean = { true }) {
        val parent = dir.parentFile!!.apply { mkdirs() }
        val tmp = File(parent, dir.name + ".new").apply { deleteRecursively(); mkdirs() }
        val base = tmp.canonicalPath + File.separator
        try {
            var count = 0
            ZipInputStream(bytes.inputStream()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val target = File(tmp, e.name)
                    if (!target.canonicalPath.startsWith(base)) throw IOException("The save contains a bad file name.")
                    if (e.isDirectory) { target.mkdirs(); continue }
                    if (!keep(target.relativeTo(tmp).invariantSeparatorsPath)) continue // not a save file: never written
                    target.parentFile?.mkdirs()
                    target.outputStream().use { zip.copyTo(it) }
                    count++
                }
            }
            if (count == 0) throw IOException("The save is empty.")
            // The emulator's other files stay: copied (not moved) into the new folder, so a failure here changes nothing.
            if (dir.isDirectory) dir.walkTopDown().filter { it.isFile }.forEach { f ->
                val rel = f.relativeTo(dir).invariantSeparatorsPath
                if (!keep(rel)) f.copyTo(File(tmp, rel), overwrite = true)
            }
            val old = File(parent, dir.name + ".old")
            // L5: an earlier rollback that failed left the save only in .old. Put it back and stop (nothing is
            // deleted), so the caller's backup can see it on the next try.
            if (!dir.exists() && old.exists()) {
                if (!old.renameTo(dir)) throw IOException("Couldn't replace the save. The old save is kept in ${old.name}.")
                throw IOException("Couldn't replace the save. The old save was put back; try again.")
            }
            old.deleteRecursively()
            if (dir.exists() && !dir.renameTo(old)) throw IOException("Couldn't replace the save.")
            if (!tmp.renameTo(dir)) {
                if (!old.renameTo(dir)) throw IOException("Couldn't replace the save. The old save is kept in ${old.name}.")
                throw IOException("Couldn't replace the save.")
            }
            old.deleteRecursively()
        } finally {
            tmp.deleteRecursively() // no-op after a successful rename
        }
    }

    /**
     * Unique temp file + fsync + rename: a crash, power cut or two writers at once can never leave
     * a half-written or empty save. rename(2) replaces the target atomically on Android.
     */
    private fun writeAtomically(f: File, bytes: ByteArray) {
        f.parentFile?.mkdirs()
        val tmp = File.createTempFile(f.name, ".tmp", f.parentFile)
        try {
            java.io.FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
            // renameTo replaces on Android; the atomic move fallback lets the JVM tests (Windows) run this same code.
            // Windows can refuse to replace a file written a moment ago while a scanner still holds it
            // (AccessDeniedException), which made tests flaky, so that fallback tries again briefly.
            if (!tmp.renameTo(f)) runCatching {
                var tries = 0
                while (true) {
                    try {
                        java.nio.file.Files.move(tmp.toPath(), f.toPath(),
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                        break
                    } catch (e: java.nio.file.AccessDeniedException) {
                        if (++tries >= 20) throw e
                        Thread.sleep(25)
                    }
                }
            }.getOrElse { throw IOException("Couldn't write ${f.name}.", it) }
        } finally {
            tmp.delete() // no-op after a successful rename
        }
    }

    /** Game Boy cartridge clock (Gen 2 Pokémon), kept beside the save. Phone only: Jellyfin stores the save alone. */
    fun clockFile(userId: String, game: Game) = File(saveDir(userId), "${game.id}.clock")

    fun readClock(userId: String, game: Game): ByteArray? =
        clockFile(userId, game).takeIf { it.isFile }?.readBytes()

    fun writeClock(userId: String, game: Game, bytes: ByteArray) = writeAtomically(clockFile(userId, game), bytes)

    fun stateFile(userId: String, game: Game) = File(root, "states/$userId/${game.id}.state")

    fun readState(userId: String, game: Game): ByteArray? =
        stateFile(userId, game).takeIf { it.isFile }?.readBytes()

    /** The state's picture (PNG), shown in the launch choice (D90). Written after the state; deleted if none was taken. */
    fun stateImageFile(userId: String, game: Game) = File(root, "states/$userId/${game.id}.state.png")

    fun writeStateImage(userId: String, game: Game, png: ByteArray?) {
        val f = stateImageFile(userId, game)
        if (png == null) f.delete() else writeAtomically(f, png)
    }

    /** D143: the cheats that were on when the snapshot was made (JSON list of {name, code}); no file = none were on. */
    fun stateCheatsFile(userId: String, game: Game) = File(root, "states/$userId/${game.id}.state.cheats.json")

    fun readStateCheats(userId: String, game: Game): String? =
        stateCheatsFile(userId, game).takeIf { it.isFile }?.readText()

    /**
     * Saves a state, keeping the previous one as .state.bak so one bad press can be undone. [cheats] (D143) = the
     * cheats that were on, as JSON (null = none); the previous snapshot's list moves to .bak with it, so the two stay together.
     */
    fun writeState(userId: String, game: Game, bytes: ByteArray, cheats: String? = null) {
        val f = stateFile(userId, game)
        val c = stateCheatsFile(userId, game)
        val cBak = bak(c)
        if (f.isFile) {
            writeAtomically(bak(f), f.readBytes())
            if (c.isFile) writeAtomically(cBak, c.readBytes()) else cBak.delete()
        }
        // Old list off first, new one after the state: if the app dies in between, a snapshot is left with no cheats
        // (the safe side), never with another snapshot's.
        c.delete()
        writeAtomically(f, bytes)
        if (cheats != null) writeAtomically(c, cheats.toByteArray(Charsets.UTF_8))
    }

    /** The previous snapshot (or its cheat list), kept beside it as `<name>.bak`. */
    private fun bak(f: File) = File(f.parentFile, f.name + ".bak")

    /** Small text files (e.g. the cheat list), written whole or not at all. */
    fun writeText(f: File, text: String) = writeAtomically(f, text.toByteArray(Charsets.UTF_8))

    /**
     * All-0x00 or all-0xFF means the game never saved: never upload or keep that. Nor a PS1 memory card the emulator
     * formatted by itself with nothing on it (D135, [PsxCard.isEmptyCard]), nor an N64 save block the core made fresh
     * with its four empty Controller Paks (plan-n64, [N64Save.isUntouched]).
     */
    fun isBlank(bytes: ByteArray?): Boolean =
        bytes == null || bytes.isEmpty() || bytes.all { it == 0.toByte() } || bytes.all { it == 0xFF.toByte() } ||
            PsxCard.isEmptyCard(bytes) || N64Save.isUntouched(bytes)
}
