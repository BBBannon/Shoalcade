package uk.benmarks.shoalcade.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.loadSvgPainter
import androidx.compose.ui.unit.Density
import uk.benmarks.shoalcade.BuildConfig

/**
 * Shoalcade's look: the phone app's Material 3 dark scheme (MainActivity.kt: `MaterialTheme(darkColorScheme())`)
 * and the logo's colours (docs/assets/logo.svg: purple #AA5CC3 to blue #00A4DC). The Dev build uses the phone Dev
 * build's orange #FF8A00 to red #D7263D (D152), so the two can't be mistaken for each other.
 */
object Brand {
    val Purple = Color(0xFFAA5CC3)
    val Blue = Color(0xFF00A4DC)
    val DevOrange = Color(0xFFFF8A00)
    val DevRed = Color(0xFFD7263D)
    /** The server light (MainActivity.kt SettingsCog). */
    val Connected = Color(0xFF66BB6A)
    val NotConnected = Color(0xFFEF5350)

    fun tile(dev: Boolean = BuildConfig.DEBUG) =
        if (dev) Brush.linearGradient(listOf(DevOrange, DevRed)) else Brush.linearGradient(listOf(Purple, Blue))

    /** The logo (docs/assets/logo.svg, on the classpath by path), recoloured for the Dev build. */
    fun logo(density: Density, dev: Boolean = BuildConfig.DEBUG): Painter {
        val svg = Brand::class.java.getResourceAsStream("/logo.svg")!!.use { it.readBytes().toString(Charsets.UTF_8) }
        val coloured = if (dev) svg.replace("#AA5CC3", "#FF8A00").replace("#00A4DC", "#D7263D") else svg
        return loadSvgPainter(coloured.byteInputStream(), density)
    }
}

@Composable
fun ShoalcadeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(), content = content)
}
