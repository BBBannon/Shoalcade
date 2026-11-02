package uk.benmarks.shoalcade.desktop.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.Typography
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.loadSvgPainter
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.SystemFont
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import uk.benmarks.shoalcade.BuildConfig

/**
 * The plugin's "Pocket" look (D55, D217): every token of sc.css `:root`, by the same name, so the Windows app and the
 * plugin pages are one design. sc.css stays the source of truth; PocketTokensTest fails if these drift from it.
 * Sizes: 1 CSS px = 1 dp, 1rem = 16 sp (the browser default).
 */
object Pocket {
    val Bg = Color(0xFFD7DDBE)
    val Surface = Color(0xFFECEFDB)
    val Ink = Color(0xFF222A1C)
    val Muted = Color(0xFF56614A)
    val Line = Color(0xFFB2BB95)
    val Accent = Color(0xFF3B5230)
    val AccentInk = Color(0xFFEEF2DC)
    val AccentSoft = Color(0xFFC3CEA1)
    val Led = Color(0xFFC8324B)
    val Danger = Color(0xFFA3283C)
    val Focus = Color(0xFFC8324B)
    // The panel tokens (dialogs, sheets, settings).
    val PBg = Color(0xFFECEFDB)
    val PInk = Color(0xFF222A1C)
    val PMuted = Color(0xFF56614A)
    val PLine = Color(0xFFCBD2B0)
    val PWell = Color(0xFFDCE2C3)
    val PRaise = Color(0xFFF7F8EE)
    val PNote = Color(0xFFE2D9A8)
    val RS = 3.dp
    val RM = 4.dp
    val RL = 6.dp
    val Rail = 64.dp
    val PanelW = 440.dp

    // Fixed colours sc.css writes in place (not tokens).
    /** .sg-err / .pt-warn: an error note. */
    val ErrorBg = Color(0xFFF1CBD1)
    /** .sheet::backdrop, .ask::backdrop. */
    val Backdrop = Color(0x8C0A0E18)
    /** .player: the stage behind the game; .toolbar uses Ink, Bg and Accent. */
    val Stage = Color(0xFF151A12)

    /** sc.css `:root` names → values, for the drift test. */
    val cssColours: Map<String, Color> = mapOf(
        "bg" to Bg, "surface" to Surface, "ink" to Ink, "muted" to Muted, "line" to Line, "accent" to Accent,
        "accent-ink" to AccentInk, "accent-soft" to AccentSoft, "led" to Led, "danger" to Danger, "focus" to Focus,
        "p-bg" to PBg, "p-ink" to PInk, "p-muted" to PMuted, "p-line" to PLine, "p-well" to PWell, "p-raise" to PRaise, "p-note" to PNote,
    )
    val cssSizes = mapOf("r-s" to RS, "r-m" to RM, "r-l" to RL, "rail" to Rail, "panel-w" to PanelW)

    /** rem → sp (1rem = 16px in the browser). */
    fun rem(r: Float): TextUnit = (16f * r).sp

    /**
     * sc.css `--cond`: Bahnschrift (every Windows 10/11 PC has it), then the condensed fonts other systems have,
     * then the default. A missing font is skipped rather than silently swapped for something else.
     */
    val cond: FontFamily by lazy {
        family(listOf("Bahnschrift", "DIN Alternate", "Roboto Condensed", "DejaVu Sans Condensed", "Arial Narrow")) ?: FontFamily.SansSerif
    }
    /** `.brand` adds font-stretch: condensed: Bahnschrift's own condensed width. */
    val condensed: FontFamily by lazy { family(listOf("Bahnschrift Condensed", "Roboto Condensed", "DejaVu Sans Condensed")) ?: cond }

    private fun installed(name: String) = runCatching {
        org.jetbrains.skia.FontMgr.default.matchFamilyStyle(name, org.jetbrains.skia.FontStyle.NORMAL)?.familyName?.equals(name, ignoreCase = true) == true
    }.getOrDefault(false)

    @OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
    private fun family(names: List<String>): FontFamily? {
        val name = names.firstOrNull { installed(it) } ?: return null
        return FontFamily(listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map { SystemFont(name, it) })
    }

    /**
     * Windows' "Show animations in Windows" off (Settings → Accessibility → Visual effects) = the browser's
     * prefers-reduced-motion: Pocket's slides and fades are then instant. -Dshoalcade.reducedMotion=true forces it.
     */
    val reducedMotion: Boolean by lazy {
        System.getProperty("shoalcade.reducedMotion")?.let { return@lazy it == "true" }
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return@lazy false
        runCatching {
            val on = com.sun.jna.ptr.IntByReference()
            User32Spi.LIB.SystemParametersInfoW(0x1042 /* SPI_GETCLIENTAREAANIMATION */, 0, on, 0) && on.value == 0
        }.getOrDefault(false)
    }

    private interface User32Spi : com.sun.jna.win32.StdCallLibrary {
        fun SystemParametersInfoW(action: Int, param: Int, out: com.sun.jna.ptr.IntByReference, winIni: Int): Boolean
        companion object { val LIB: User32Spi by lazy { com.sun.jna.Native.load("user32", User32Spi::class.java) } }
    }
}

/**
 * Shoalcade's logo colours (docs/assets/logo.svg: purple #AA5CC3 to blue #00A4DC). The Dev build uses the phone Dev
 * build's orange #FF8A00 to red #D7263D (D152) for its logo and "Dev" badge, so the two can't be mistaken for each other.
 */
object Brand {
    val Purple = Color(0xFFAA5CC3)
    val Blue = Color(0xFF00A4DC)
    val DevOrange = Color(0xFFFF8A00)
    val DevRed = Color(0xFFD7263D)
    /** The server light (the phone's SettingsCog), in Pocket colours that read on the light background. */
    val Connected = Pocket.Accent
    val NotConnected = Pocket.Led

    fun tile(dev: Boolean = BuildConfig.DEBUG) =
        if (dev) Brush.linearGradient(listOf(DevOrange, DevRed)) else Brush.linearGradient(listOf(Purple, Blue))

    /** The logo (docs/assets/logo.svg, on the classpath by path), recoloured for the Dev build. */
    fun logo(density: Density, dev: Boolean = BuildConfig.DEBUG): Painter {
        val svg = Brand::class.java.getResourceAsStream("/logo.svg")!!.use { it.readBytes().toString(Charsets.UTF_8) }
        val coloured = if (dev) svg.replace("#AA5CC3", "#FF8A00").replace("#00A4DC", "#D7263D") else svg
        return loadSvgPainter(coloured.byteInputStream(), density)
    }
}

/** sc.css's type scale on Material's names, so plain Text and every Material part speak Pocket. */
private fun pocketType(): Typography {
    val f = Pocket.cond
    fun s(rem: Float, w: FontWeight = FontWeight.Normal, lh: Float = 1.3f) =
        TextStyle(fontFamily = f, fontWeight = w, fontSize = Pocket.rem(rem), lineHeight = (lh).em)
    return Typography(
        displayLarge = s(3f, FontWeight.Bold, 1.1f), displayMedium = s(2.6f, FontWeight.Bold, 1.1f), displaySmall = s(2.2f, FontWeight.Bold, 1.1f),
        headlineLarge = s(1.9f, FontWeight.Bold, 1f),      // .brand
        headlineMedium = s(1.5f, FontWeight.Bold, 1.15f),  // .sheet-txt h2, .set-title
        headlineSmall = s(1.3f, FontWeight.Bold, 1.2f),    // .notice h2, .ask h2
        titleLarge = s(1.2f, FontWeight.Bold, 1.2f),       // .panel-head h2, .editor h3
        titleMedium = s(1.05f, FontWeight.Bold),           // h2.sec
        titleSmall = s(0.98f, FontWeight.Bold, 1.2f),      // .card-t
        bodyLarge = s(0.9375f, lh = 1.5f),                 // body: 15px/1.5
        bodyMedium = s(0.88f, lh = 1.45f),                 // .facts, .sv-label
        bodySmall = s(0.8f, lh = 1.4f),                    // .meta, .card-m, .sv-meta
        labelLarge = s(0.9f, FontWeight.SemiBold, 1.2f),   // .btn
        labelMedium = s(0.78f, FontWeight.SemiBold, 1.2f), // .mini
        labelSmall = s(0.72f, FontWeight.Bold, 1.2f),      // .badge
    )
}

private val pocketColours = lightColorScheme(
    primary = Pocket.Accent, onPrimary = Pocket.AccentInk,
    primaryContainer = Pocket.AccentSoft, onPrimaryContainer = Pocket.Ink,
    secondary = Pocket.Muted, onSecondary = Pocket.Surface,
    secondaryContainer = Pocket.AccentSoft, onSecondaryContainer = Pocket.Ink,
    tertiary = Pocket.Accent, onTertiary = Pocket.AccentInk,
    background = Pocket.Bg, onBackground = Pocket.Ink,
    surface = Pocket.Bg, onSurface = Pocket.Ink,
    surfaceVariant = Pocket.PWell, onSurfaceVariant = Pocket.Muted,
    surfaceContainerLowest = Pocket.PRaise, surfaceContainerLow = Pocket.Surface, surfaceContainer = Pocket.Surface,
    surfaceContainerHigh = Pocket.PBg, surfaceContainerHighest = Pocket.PWell, surfaceBright = Pocket.PRaise, surfaceDim = Pocket.Bg,
    inverseSurface = Pocket.Ink, inverseOnSurface = Pocket.Bg, inversePrimary = Pocket.AccentSoft,
    outline = Pocket.Ink, outlineVariant = Pocket.Line,
    // Danger, not the LED red, for text: --led on --surface is under 4.5:1.
    error = Pocket.Danger, onError = Color.White, errorContainer = Pocket.ErrorBg, onErrorContainer = Pocket.Ink,
    scrim = Pocket.Backdrop,
)

/** Material's few remaining parts (menus, progress bars) get Pocket's square-ish corners. */
private val pocketShapes = Shapes(
    extraSmall = RoundedCornerShape(Pocket.RS), small = RoundedCornerShape(Pocket.RM), medium = RoundedCornerShape(Pocket.RL),
    large = RoundedCornerShape(Pocket.RL), extraLarge = RoundedCornerShape(Pocket.RL),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShoalcadeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = pocketColours, typography = pocketType(), shapes = pocketShapes) {
        // No ripples (the plugin has none, and they are motion); every clickable shows Pocket's focus ring instead.
        CompositionLocalProvider(
            LocalIndication provides PocketIndication,
            LocalRippleConfiguration provides if (Pocket.reducedMotion) null else RippleConfiguration(color = Pocket.Ink),
            content = content,
        )
    }
}
