package uk.benmarks.shoalcade

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Box art, cached in app storage as art/<itemId>-<tag>.jpg so it shows offline.
 * A new tag (picture changed on Jellyfin) replaces the old file.
 */
object BoxArt {
    private fun dir(ctx: Context) = File(ctx.filesDir, "art")

    private fun safe(tag: String) = tag.filter { it.isLetterOrDigit() }.take(64)

    fun file(ctx: Context, game: Game): File? =
        game.imageTag?.let { File(dir(ctx), "${game.id}-${safe(it)}.jpg") }

    /** Downloads any missing art. Failures are skipped quietly (art is nice-to-have). True if anything new arrived. */
    suspend fun fetchMissing(ctx: Context, client: JellyfinClient, games: List<Game>): Boolean = withContext(Dispatchers.IO) {
        val d = dir(ctx).apply { mkdirs() }
        val existing = d.listFiles()?.toList().orEmpty() // listed once: a first sync of many games stays quick
        var got = false
        for (g in games) {
            val f = file(ctx, g) ?: continue
            if (f.isFile) continue
            val bytes = runCatching { client.boxArt(g.id, g.imageTag!!) }.getOrNull() ?: continue
            val tmp = File(d, f.name + ".part")
            tmp.writeBytes(bytes)
            if (tmp.renameTo(f)) {
                got = true
                existing.filter { it.name.startsWith("${g.id}-") && it != f }.forEach { it.delete() } // the old picture
            }
        }
        got
    }
}

/** The game's cover, or a coloured tile with the console name when there isn't one. */
@Composable
fun BoxArtImage(game: Game, version: Int) {
    val ctx = LocalContext.current
    // What produceState does inside, written out: lint's ProduceStateDoesNotAssignValue can't see the assignment
    // in produceState's keyed forms and fails the build (false positive). Same behaviour: the old cover stays until
    // the new one has loaded.
    var bmp by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(game.id, game.imageTag, version) {
        bmp = withContext(Dispatchers.IO) {
            BoxArt.file(ctx, game)?.takeIf { it.isFile }?.let { runCatching { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }.getOrNull() }
        }
    }
    val shape = RoundedCornerShape(6.dp)
    val m = Modifier.size(48.dp, 56.dp).clip(shape)
    val b = bmp
    if (b != null) {
        Image(b, contentDescription = null, contentScale = ContentScale.Crop, modifier = m)
    } else {
        Box(m.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Text(game.console?.shortName ?: "?", style = MaterialTheme.typography.labelSmall)
        }
    }
}
