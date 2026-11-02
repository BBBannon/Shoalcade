package uk.benmarks.shoalcade.desktop

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONArray
import uk.benmarks.shoalcade.Console
import uk.benmarks.shoalcade.DiscSets
import uk.benmarks.shoalcade.Game
import uk.benmarks.shoalcade.GameJson
import uk.benmarks.shoalcade.JellyfinClient
import uk.benmarks.shoalcade.RomCheck
import uk.benmarks.shoalcade.Storage
import java.io.File
import java.io.IOException

/**
 * The desktop side of storage. The rules themselves are the phone's Storage.kt, compiled here unchanged (atomic
 * writes with fsync + rename, last-10 backups, zip-slip-safe unpack of downloads and folder saves, the folder-save
 * zip for PSP/3DS); this object points it at [Paths.libraryDir] and adds what the phone keeps in other files:
 * the offline game list (AppViewModel.kt lines 589-602) and the download steps (Downloads.kt lines 110-122).
 */
object DesktopStorage {
    lateinit var paths: Paths
        private set

    /** Once at start, before anything reads or writes a game or save. */
    fun init(p: Paths) {
        paths = p
        p.dataDir.mkdirs()
        Storage.init(context())
        check(Storage.root.canonicalFile == p.libraryDir.canonicalFile) { "Storage root isn't ${p.libraryDir}" }
    }

    /** The stand-in Context the shared phone files take (their filesDir = [Paths.dataDir]). */
    fun context() = Context(paths.dataDir)

    /** Consoles the Windows player runs (PROTOCOL.md `start.console`): all of them since phase 6 (3DS). */
    fun playableHere(c: Console?) = c != null

    // ---- Offline game list (the phone's rule: written whole, then swapped in) ----

    fun writeGameList(list: List<Game>) {
        val arr = JSONArray()
        list.forEach { arr.put(GameJson.toJson(it)) }
        AtomicFile.write(paths.gameListFile, arr.toString().toByteArray(Charsets.UTF_8))
    }

    /** Null when there is no readable saved list (the game list is then not known). */
    fun readGameList(): List<Game>? = runCatching {
        val arr = JSONArray(paths.gameListFile.readText(Charsets.UTF_8))
        (0 until arr.length()).map { i -> GameJson.fromJson(arr.getJSONObject(i)) }
    }.getOrNull()

    fun deleteGameList() { paths.gameListFile.delete() }

    // ---- Downloads ----

    /**
     * PS2: the game file is always `<gameId>.<ext>`, because both PS2 emulators name the memory card after it
     * (`<save folder>\<game file name>.ps2`): the card is then the same on every PC, whatever the file is called on
     * Jellyfin. Other consoles keep the server's name. Returns the file to play.
     */
    fun ps2RomName(game: Game, rom: File): File {
        if (game.console != Console.PS2) return rom
        val want = File(rom.parentFile, "${game.id}.${rom.extension.lowercase()}")
        if (rom.name == want.name) return rom
        if (want.exists() || !rom.renameTo(want)) throw IOException("Couldn't get the game file ready. Download it again.")
        return want
    }

    /**
     * Downloads [game] into `library\roms\<console>\<itemId>\` through Jellyfin's own download route (the shared
     * [JellyfinClient.download]: `.part` file first, Cancel stops it mid-way), unpacks a .zip with the phone's
     * zip-slip-safe [Storage.unpackIfZipped], then checks the file with the shared [RomCheck] so a file that clearly
     * isn't a game never reaches the player. Cancelled or refused: the download folder is removed (never a save).
     * Returns the playable file.
     */
    suspend fun download(client: JellyfinClient, game: Game, onProgress: (Float) -> Unit): File {
        val console = game.console ?: throw IOException("Unknown console.")
        // D-8: a disc set: every disc, then its .m3u (the shared rule, as the phone).
        if (DiscSets.isSet(game)) return DiscSets.download(client, game, onProgress)
        try {
            val target = client.download(game.id, Storage.downloadFolder(game), Storage.fallbackRomName(game), onProgress)
            return withContext(Dispatchers.IO) {
                Storage.unpackIfZipped(game, target)
                val rom = Storage.romFile(game)
                    ?: throw IOException("This file isn't a ${console.tag} game (.${target.extension}). Check the game file on your Jellyfin server, then download it again.")
                RomCheck.problem(console, rom)?.let { throw IOException(RomCheck.message(console, it)) }
                ps2RomName(game, rom)
            }
        } catch (e: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) { Storage.deleteDownload(game) } // the .part file, or the bad file
            throw e
        }
    }
}
