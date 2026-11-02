package uk.benmarks.shoalcade.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.Canvas as GCanvas
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * .art: a square tile with the 2 px ink edge. With a cover: the cover, cropped to fill. Without: sc.css's little
 * dithered "screen" in the game's own colour ([hue], the same number library.js works out from the id) with the name
 * across the bottom (.art-t). [console] = .art-c, the console label in the corner. Decoration only for screen readers:
 * the card around it carries the name.
 */
@Composable
fun PocketArt(id: String, name: String, console: String?, cover: ImageBitmap?, modifier: Modifier = Modifier, small: Boolean = false) {
    val shape = RoundedCornerShape(Pocket.RM)
    val h = remember(id) { hue(id) }
    BoxWithConstraints(modifier.aspectRatio(1f).clip(shape).border(2.dp, Pocket.Ink, shape).clearAndSetSemantics { }) {
        val w = maxWidth
        if (cover != null) {
            Image(cover, null, Modifier.fillMaxSize().background(Pocket.Surface), contentScale = ContentScale.Crop)
        } else {
            NoCover(h)
            if (!small) Text(name, color = Pocket.Bg, maxLines = 4,
                style = TextStyle(fontFamily = Pocket.cond, fontWeight = FontWeight.Bold, fontSize = with(LocalDensity.current) { (w * 0.10f).toSp() }, lineHeight = 1.05.em),
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Pocket.Ink).padding(horizontal = w * 0.08f, vertical = w * 0.06f))
        }
        if (!small && console != null) Text(console, color = Pocket.Ink,
            style = TextStyle(fontFamily = Pocket.cond, fontWeight = FontWeight.Bold, fontSize = Pocket.rem(0.7f), lineHeight = 1.4.em),
            modifier = Modifier.padding(start = w * 0.07f, top = w * 0.06f).background(Pocket.Surface, RoundedCornerShape(2.dp))
                .border(1.5.dp, Pocket.Ink, RoundedCornerShape(2.dp)).padding(horizontal = 5.dp))
    }
}

/** library.js hue(): (n * 31 + char) mod 360 over the id, so a game has the same colour in the browser and here. */
fun hue(id: String): Int {
    var n = 0
    for (c in id) n = (n * 31 + c.code) % 360
    return n
}

/** CSS hsl() → Color. */
fun hsl(h: Int, s: Float, l: Float, alpha: Float = 1f): Color {
    val c = (1 - kotlin.math.abs(2 * l - 1)) * s
    val hp = (h % 360) / 60f
    val x = c * (1 - kotlin.math.abs(hp % 2 - 1))
    val (r, g, b) = when {
        hp < 1 -> Triple(c, x, 0f); hp < 2 -> Triple(x, c, 0f); hp < 3 -> Triple(0f, c, x)
        hp < 4 -> Triple(0f, x, c); hp < 5 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
    }
    val m = l - c / 2
    return Color(r + m, g + m, b + m, alpha)
}

/**
 * The no-cover picture: three bands (light 0-48 %, mid 48-66 %, dark 66-100 %), a 3 px checker dither over them
 * (repeating-conic-gradient … 6px 6px) and the round "screen" with its soft ring (.art:not(.has-cover)::before).
 */
@Composable
private fun NoCover(h: Int) {
    val density = LocalDensity.current
    val dither = remember(h, density.density) {
        val cell = with(density) { 3.dp.roundToPx() }.coerceAtLeast(1)
        val bmp = ImageBitmap(cell * 2, cell * 2)
        val p = Paint().apply { color = hsl(h, 0.30f, 0.30f, 0.18f) }
        GCanvas(bmp).apply {
            drawRect(cell.toFloat(), 0f, cell * 2f, cell.toFloat(), p) // top right
            drawRect(0f, cell.toFloat(), cell.toFloat(), cell * 2f, p) // bottom left
        }
        ShaderBrush(ImageShader(bmp, TileMode.Repeated, TileMode.Repeated))
    }
    Canvas(Modifier.fillMaxSize()) {
        val W = size.width; val H = size.height
        drawRect(hsl(h, 0.35f, 0.78f), size = Size(W, H * 0.48f))
        drawRect(hsl(h, 0.30f, 0.58f), topLeft = Offset(0f, H * 0.48f), size = Size(W, H * 0.18f))
        drawRect(hsl(h, 0.28f, 0.40f), topLeft = Offset(0f, H * 0.66f), size = Size(W, H * 0.34f))
        drawRect(dither)
        val r = W * 0.12f
        val centre = Offset(W * 0.38f + r, H * 0.30f + r)
        drawCircle(hsl(h, 0.35f, 0.78f, 0.5f), radius = r + W * 0.06f, center = centre)
        drawCircle(hsl(h, 0.30f, 0.22f), radius = r, center = centre)
    }
}
