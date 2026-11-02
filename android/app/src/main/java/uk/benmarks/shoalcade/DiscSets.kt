package uk.benmarks.shoalcade

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * D-8: disc-set games (PS1, PS2, GameCube, Wii on two or more discs): one game, one save, every disc downloaded and a
 * `.m3u` naming them, which the emulator plays (its disk control swaps discs). Shared by the phone and the Windows app.
 */
object DiscSets {

    /** True if [game] is a disc set this app downloads as one (the server lists 2+ discs for a disc-set console). */
    fun isSet(game: Game) = game.discs > 1 && game.console?.takesDiscSets == true

    /**
     * Downloads every disc through the plugin's disc route (`.part` first, sizes checked), checks each with [RomCheck],
     * then writes the `.m3u` last, so a half-downloaded set is never playable. Anything wrong, or Cancel: the game's
     * download folder is removed (never a save). Returns the `.m3u`.
     */
    suspend fun download(client: JellyfinClient, game: Game, onProgress: (Float) -> Unit): File {
        val console = game.console ?: throw IOException("Unknown console.")
        try {
            val discs = client.discs(game.id)
            val dir = Storage.downloadFolder(game)
            val total = discs.sumOf { it.size.coerceAtLeast(0L) }.coerceAtLeast(1L).toFloat()
            var before = 0L
            for (d in discs) {
                val size = d.size.coerceAtLeast(0L)
                client.downloadDisc(game.id, d, dir) { f -> onProgress(((before + f * size) / total).coerceIn(0f, 1f)) }
                before += size
            }
            return withContext(Dispatchers.IO) {
                for (d in discs) RomCheck.problem(console, File(dir, d.name))?.let { throw IOException(RomCheck.message(console, it)) }
                Storage.writeDiscList(game, discs.map { it.name })
            }
        } catch (e: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) { Storage.deleteDownload(game) }
            throw e
        }
    }
}
