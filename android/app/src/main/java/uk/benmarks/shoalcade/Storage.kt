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
        val exts = game.console?.romExtensions ?: return null
        return gameDir(game).listFiles()?.firstOrNull { it.isFile && it.extension.lowercase() in exts }
    }

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
    fun memstickDir(userId: String, game: Game) = File(root, "memstick/$userId/${game.id}")

    /** Only the game saves on the memory stick; the emulator keeps its own housekeeping files elsewhere on it. */
    private fun savedataDir(userId: String, game: Game) = File(memstickDir(userId, game), "PSP/SAVEDATA")

    /** The save as bytes: the save file, or for folder-save consoles a zip of the memory stick. */
    fun readSave(userId: String, game: Game): ByteArray? =
        if (game.console?.hasFolderSaves == true) zipFolder(savedataDir(userId, game))
        else saveFile(userId, game).takeIf { it.isFile }?.readBytes()

    /** When the save last changed on the phone (newest file for folder saves). */
    fun saveTime(userId: String, game: Game): Long? =
        if (game.console?.hasFolderSaves == true)
            savedataDir(userId, game).walkTopDown().filter { it.isFile && !it.name.startsWith(".") }.maxOfOrNull { it.lastModified() }
        else saveFile(userId, game).takeIf { it.isFile }?.lastModified()

    /** Ids of every game with a save on this phone (save files and PSP memory sticks), whether or not it's still listed. */
    fun savedGameIds(userId: String): Set<String> {
        val files = saveDir(userId).listFiles { f -> f.isFile && f.name.endsWith(".sav") }?.map { it.name.removeSuffix(".sav") } ?: emptyList()
        val sticks = File(root, "memstick/$userId").listFiles { f -> f.isDirectory }
            ?.filter { File(it, "PSP/SAVEDATA").walkTopDown().any { f -> f.isFile && !f.name.startsWith(".") } }?.map { it.name } ?: emptyList()
        return (files + sticks).toSet()
    }

    /** True if this game's phone save is a PSP memory stick (a folder), not a save file. Reads only. */
    fun hasSaveFolder(userId: String, gameId: String) =
        File(root, "memstick/$userId/$gameId/PSP/SAVEDATA").walkTopDown().any { it.isFile && !it.name.startsWith(".") }

    /** A ROM still on the phone for [gameId] (any console), e.g. for a game Jellyfin no longer lists. Reads only. */
    fun findRom(gameId: String): Pair<Console, File>? = Console.entries.firstNotNullOfOrNull { c ->
        File(root, "roms/${c.folder}/$gameId").listFiles()
            ?.firstOrNull { it.isFile && it.extension.lowercase() in c.romExtensions }?.let { c to it }
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
        listOf(f, File(f.parentFile, f.name + ".bak"), stateImageFile(userId, game), cheats, File(cheats.parentFile, cheats.name + ".bak"))
            .forEach { it.delete() }
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
        val cur = readSave(userId, game) ?: return // file, or zip of the PSP memory stick
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

    fun writeSave(userId: String, game: Game, bytes: ByteArray) =
        if (game.console?.hasFolderSaves == true) unzipToFolder(bytes, savedataDir(userId, game))
        else writeAtomically(saveFile(userId, game), bytes)

    /** Fixed entry time, so the same files always give the same zip (lets "unchanged" checks work). */
    private const val ZIP_TIME = 315_532_800_000L // 01/01/1980, the earliest zip time

    /** Zips every file under [dir] in name order, skipping hidden ones (.nomedia). Null if nothing is saved yet. */
    fun zipFolder(dir: File): ByteArray? {
        val files = dir.walkTopDown().filter { it.isFile && !it.name.startsWith(".") }.map { it.relativeTo(dir).invariantSeparatorsPath to it }
            .sortedBy { it.first }.toList()
        if (files.isEmpty()) return null
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
     */
    fun unzipToFolder(bytes: ByteArray, dir: File) {
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
                    target.parentFile?.mkdirs()
                    target.outputStream().use { zip.copyTo(it) }
                    count++
                }
            }
            if (count == 0) throw IOException("The save is empty.")
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
        val cBak = File(c.parentFile, c.name + ".bak")
        if (f.isFile) {
            writeAtomically(File(f.parentFile, f.name + ".bak"), f.readBytes())
            if (c.isFile) writeAtomically(cBak, c.readBytes()) else cBak.delete()
        }
        // Old list off first, new one after the state: if the app dies in between, a snapshot is left with no cheats
        // (the safe side), never with another snapshot's.
        c.delete()
        writeAtomically(f, bytes)
        if (cheats != null) writeAtomically(c, cheats.toByteArray(Charsets.UTF_8))
    }

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
