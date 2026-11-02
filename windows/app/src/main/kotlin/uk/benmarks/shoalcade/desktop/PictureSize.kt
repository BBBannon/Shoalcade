package uk.benmarks.shoalcade.desktop

import android.content.Context
import uk.benmarks.shoalcade.Console

/**
 * Settings → Display → Picture size (plan-windows "Upscaling (N64, PSP, 3DS) | Core option, per console"): how big
 * the 3D consoles draw their picture, 1× to 4×, kept per console on this PC in the `display` store
 * (`picture.<console>`). Each console uses its own core's option (values read from the cores' own lists, 01/11/2026):
 *  - N64, Mupen64Plus-Next (GLideN64, 4:3, the core's defaults): `mupen64plus-43screensize`; its default 640x480 is 2×.
 *  - PSP, PPSSPP: `ppsspp_internal_resolution`, 480x272 (1×, the default) and its multiples.
 *  - 3DS, Azahar: `citra_resolution_factor` 1–4 (1 = the default and the phone's fixed value).
 *  - GameCube and Wii, Dolphin: `dolphin_efb_scale` "1"–"4" (1 = native 640x528, the default).
 *  - PS2: `pcsx2_upscale_multiplier`, in the chosen emulator's own values: PCEE2 "1"–"4"; LRPS2 lists "1x (Native)",
 *    "2x", "4x", "8x" and reads the number at the front (atoi), so 3× is sent as "3x" (the player warns it isn't one of
 *    the listed values; LRPS2 draws at 3× all the same). 1× = native, the default for both.
 * The phone has no such setting. Nothing is sent while the console is at its default, so a game starts exactly as it
 * did before this setting existed. A change counts from the next game started.
 */
object PictureSize {
    class Choice(val console: Console, val key: String, val values: List<String>, val default: Int)

    val CHOICES = listOf(
        Choice(Console.N64, "mupen64plus-43screensize", listOf("320x240", "640x480", "960x720", "1280x960"), default = 2),
        Choice(Console.PSP, "ppsspp_internal_resolution", listOf("480x272", "960x544", "1440x816", "1920x1088"), default = 1),
        Choice(Console.N3DS, "citra_resolution_factor", listOf("1", "2", "3", "4"), default = 1),
        Choice(Console.GC, "dolphin_efb_scale", listOf("1", "2", "3", "4"), default = 1),
        Choice(Console.WII, "dolphin_efb_scale", listOf("1", "2", "3", "4"), default = 1),
        Choice(Console.PS2, "pcsx2_upscale_multiplier", listOf("1x (Native)", "2x", "3x", "4x"), default = 1),
    )
    /** PCEE2's own values for the same PS2 option (LRPS2's are in [CHOICES]). */
    val PS2_PCEE2 = listOf("1", "2", "3", "4")
    val SIZES = 1..4

    const val TITLE = "Picture size"
    const val HELP = "Bigger looks sharper in a big window, but needs a faster PC. A change counts from the next game you start."
    fun label(size: Int) = "$size×"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("display", Context.MODE_PRIVATE)

    fun choiceFor(console: Console?) = CHOICES.firstOrNull { it.console == console }

    /** 1..4 for a console in [CHOICES] (its default if unset or damaged), else null. */
    fun get(ctx: Context, console: Console): Int? {
        val c = choiceFor(console) ?: return null
        return runCatching { prefs(ctx).getString("picture.${console.key}", null)?.toInt() }.getOrNull()?.takeIf { it in SIZES } ?: c.default
    }

    fun set(ctx: Context, console: Console, size: Int) {
        val c = choiceFor(console) ?: return
        if (size !in SIZES) return
        prefs(ctx).edit().apply { if (size == c.default) remove("picture.${console.key}") else putString("picture.${console.key}", "$size") }.apply()
    }

    /** The core option for [console]'s start, or nothing at the default. */
    fun options(ctx: Context, console: Console): Map<String, String> {
        val c = choiceFor(console) ?: return emptyMap()
        val size = get(ctx, console) ?: return emptyMap()
        val values = if (console == Console.PS2 && Ps2.emulator(ctx) == Ps2.Emulator.PCEE2) PS2_PCEE2 else c.values
        return if (size == c.default) emptyMap() else mapOf(c.key to values[size - 1])
    }
}
