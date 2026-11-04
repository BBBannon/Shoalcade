package uk.benmarks.shoalcade

import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.InputDevice
import kotlin.math.roundToInt

/**
 * D246 Controller rumble: a game that rumbles shakes the **controller**, never the phone body (the phone only ever
 * buzzes for touch, through [Haptics]). LibretroDroid hands over the core's rumble as a level per player port that
 * holds until the game changes it ([amplitude]); [PadRumble] drives the controller's own motor with it.
 * The switch is kept on the phone in the plain SharedPreferences file `display` (key `rumble`, no secrets), like
 * Haptics; it is read when a game starts.
 */
internal object ControllerRumble {
    private const val KEY = "rumble"

    /** On unless switched off; anything but "0" in the file reads as on. */
    fun on(store: SyncStore): Boolean = store.get(KEY) != "0"
    fun setOn(store: SyncStore, on: Boolean) = store.set(KEY, if (on) null else "0")

    /**
     * Consoles whose games can rumble here: mGBA (Game Boy rumble cartridges, Drill Dozer on GBA) and melonDS DS
     * (the Slot-2 Rumble Pak, left at the core's own setting). Not N64: Controller Pak 1 holds the saves, so a pak is
     * never switched to a Rumble Pak (Console.coreVariables). Not PS1: pcsx_rearmed only rumbles with the DualShock pad
     * type, and changing the pad type changes how games behave. PSP and 3DS have no rumble.
     */
    val CONSOLES = setOf(Console.GB, Console.GBC, Console.GBA, Console.NDS)

    fun allowed(console: Console?) = console in CONSOLES

    /** Motor strength 1-255 for one rumble level (the stronger of the core's two motors), or 0 = stop. */
    fun amplitude(weak: Float, strong: Float): Int = (maxOf(weak, strong).coerceIn(0f, 1f) * 255f).roundToInt()

    /**
     * How long one level is held at most. The core says when the rumble stops; this is only a safety net so a lost
     * "stop" can never leave a controller shaking.
     */
    const val MAX_MS = 5_000L
}

/** Drives one controller's motor at a time (Android 12+: the device's vibrator manager; Android 11: its vibrator). */
internal class PadRumble {
    private var buzzing: Vibrator? = null

    /** Rumbles [device] at [amplitude] (0 = stop). A device with no motor does nothing; the phone never buzzes. */
    fun set(device: InputDevice?, amplitude: Int) {
        val v = device?.let { motor(it) }?.takeIf { it.hasVibrator() }
        if (amplitude <= 0 || v == null) { stop(); return }
        if (buzzing !== v) stop()
        val amp = if (v.hasAmplitudeControl()) amplitude.coerceIn(1, 255) else VibrationEffect.DEFAULT_AMPLITUDE
        runCatching { v.vibrate(VibrationEffect.createOneShot(ControllerRumble.MAX_MS, amp)) }
        buzzing = v
    }

    fun stop() {
        buzzing?.let { runCatching { it.cancel() } }
        buzzing = null
    }

    private fun motor(d: InputDevice): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) d.vibratorManager.defaultVibrator
        else @Suppress("DEPRECATION") d.vibrator
}
