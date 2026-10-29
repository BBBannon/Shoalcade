package uk.benmarks.shoalcade

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Loading screen over the game while it starts (every console). GameActivity shows it once starting has taken more
 * than [SHOW_AFTER_MS] and removes it when the game draws its first picture that isn't black ([FirstPicture]).
 * The phone has the game on it already, so there is no download to count: a moving bar, what is happening and a clock.
 */
class LoadingState(val title: String) {
    var stage by mutableStateOf(LoadingWords.EMULATOR)
    val startedAt = System.currentTimeMillis()
}

object LoadingWords {
    const val SHOW_AFTER_MS = 1_000L
    const val SLOW_MS = 10_000L
    const val EMULATOR = "Starting the emulator…"
    const val GAME = "Starting the game…"
    const val SLOW = "Still going. Big games can take up to a minute the first time."

    /** "0:07", or after [SLOW_MS] "0:23. Still going. …". */
    fun clock(elapsedMs: Long): String {
        val s = (elapsedMs.coerceAtLeast(0) / 1000).toInt()
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}" + if (elapsedMs >= SLOW_MS) ". $SLOW" else ""
    }
}

/** Is this small copy of the screen (ARGB pixels) showing anything but black? At least two pixels must be lit. */
object FirstPicture {
    const val LIT = 16
    fun lit(pixels: IntArray): Boolean {
        var n = 0
        for (p in pixels) {
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            if (maxOf(r, g, b) > LIT && ++n >= 2) return true
        }
        return false
    }
}

private val CARD = Color(0xFF101018)
private val EDGE = Color(0xFF2C2C3A)
private val MUTED = Color(0xFFA8A8B4)
private val BAR = Color(0xFF78C8FF)

@Composable
fun GameLoading(state: LoadingState) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    MaterialTheme(colorScheme = darkColorScheme()) {
        Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
            Column(
                Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(24.dp)
                    .background(CARD, RoundedCornerShape(12.dp)).border(1.dp, EDGE, RoundedCornerShape(12.dp))
                    .padding(20.dp),
            ) {
                Text(state.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2)
                Spacer(Modifier.height(10.dp))
                // TalkBack reads the step when it changes (not the clock every second).
                Text(state.stage, style = MaterialTheme.typography.bodyLarge, color = Color.White,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                Spacer(Modifier.height(12.dp))
                // Indeterminate: TalkBack says "in progress". It stops moving when the phone's animations are off.
                LinearProgressIndicator(Modifier.fillMaxWidth().height(8.dp), color = BAR, trackColor = EDGE)
                Spacer(Modifier.height(10.dp))
                Text(LoadingWords.clock(now - state.startedAt), style = MaterialTheme.typography.bodyMedium, color = MUTED)
            }
        }
    }
}
