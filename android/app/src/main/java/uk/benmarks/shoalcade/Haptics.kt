package uk.benmarks.shoalcade

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * F6 Haptics: the one switch for every buzz the app makes. The touch pad ([GamepadView]) asks [on] before each one,
 * and every Compose screen goes through [HapticsGate]. The app has no Vibrator calls and no rumble. Read at the moment
 * of each buzz, so a change takes effect at once, in a running game too.
 * Kept on the phone in the plain SharedPreferences file `display` (key `haptics`, no secrets), like App Mute.
 */
internal object Haptics {
    private const val KEY = "haptics"

    /** On unless switched off; anything but "0" in the file reads as on (the behaviour before F6). */
    fun on(store: SyncStore): Boolean = store.get(KEY) != "0"
    fun setOn(store: SyncStore, on: Boolean) = store.set(KEY, if (on) null else "0")

    fun on(ctx: Context): Boolean = on(GameSound.store(ctx))
}

/** Gives [content] haptic feedback that does nothing while Haptics is off (text selection, long presses and the like). */
@Composable
internal fun HapticsGate(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val real = LocalHapticFeedback.current
    val gated = remember(real, ctx) {
        object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                if (Haptics.on(ctx)) real.performHapticFeedback(hapticFeedbackType)
            }
        }
    }
    CompositionLocalProvider(LocalHapticFeedback provides gated, content = content)
}
