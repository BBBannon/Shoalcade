package uk.benmarks.shoalcade.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uk.benmarks.shoalcade.Game
import uk.benmarks.shoalcade.JellyfinClient
import java.io.File

/**
 * Box art, cached as art\<itemId>-<tag>.jpg so it shows offline. The phone's BoxArt rules (android/.../BoxArt.kt
 * lines 34-62): a new tag (picture changed on Jellyfin) replaces the old file; failures are skipped quietly.
 */
class BoxArt(private val dir: File) {
    private fun safe(tag: String) = tag.filter { it.isLetterOrDigit() }.take(64)

    fun file(game: Game): File? = game.imageTag?.let { File(dir, "${game.id}-${safe(it)}.jpg") }

    /** The cached cover as a picture, or null (none yet, or not a picture: the console tile shows instead). */
    fun load(game: Game): ImageBitmap? = file(game)?.takeIf { it.isFile }?.let {
        runCatching { org.jetbrains.skia.Image.makeFromEncoded(it.readBytes()).toComposeImageBitmap() }.getOrNull()
    }

    /** Downloads any missing art. True if anything new arrived. Stops (writing nothing more) once [keepGoing] is false. */
    suspend fun fetchMissing(client: JellyfinClient, games: List<Game>, keepGoing: () -> Boolean = { true }): Boolean = withContext(Dispatchers.IO) {
        if (!keepGoing()) return@withContext false
        dir.mkdirs()
        val existing = dir.listFiles()?.toList().orEmpty()
        var got = false
        for (g in games) {
            val f = file(g) ?: continue
            if (f.isFile) continue
            if (!JellyfinClient.isSafeId(g.id)) continue
            val bytes = runCatching { client.boxArt(g.id, g.imageTag!!) }.getOrNull() ?: continue
            if (!keepGoing()) break // logged out meanwhile: no more of the old user's art
            if (runCatching { AtomicFile.writeDirect(f, bytes) }.isSuccess) { // a picture: no .tmp rename (antivirus, D225)
                got = true
                existing.filter { it.name.startsWith("${g.id}-") && it != f }.forEach { it.delete() } // the old picture
            }
        }
        got
    }
}
