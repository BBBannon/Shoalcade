package uk.benmarks.shoalcade

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Every dialog's title (TalkBack pass, 02/11/2026): the same words as before, marked as a heading, so TalkBack says
 * "heading" and jumping by headings lands on it, as the Settings and menu headings already do.
 */
@Composable
internal fun DialogTitle(text: String) = Text(text, Modifier.semantics { heading() })

/** The game row's ⋮ button, spoken with the game's name, so it is clear when TalkBack jumps straight to it. */
internal fun moreLabel(game: Game) = "More for ${game.name}"

/** A yes / no ask: [confirm] does it, [dismiss] (default "Cancel") and tapping outside close it doing nothing. */
@Composable
internal fun ConfirmDialog(
    title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit,
    dismiss: String = "Cancel", confirmEnabled: Boolean = true,
) = AlertDialog(
    onDismissRequest = onDismiss,
    title = { DialogTitle(title) },
    text = { Text(text) },
    confirmButton = { TextButton(enabled = confirmEnabled, onClick = onConfirm) { Text(confirm) } },
    dismissButton = { TextButton(onClick = onDismiss) { Text(dismiss) } },
)

/** A small spinner with what it is waiting for ("Waiting for approval…", "Connecting to …"). */
@Composable
internal fun Waiting(text: String) = Row(verticalAlignment = Alignment.CenterVertically) {
    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    Text(text, Modifier.padding(start = 12.dp))
}

/** A status or error line TalkBack reads out as soon as it appears or changes (focus stays where it is). */
@Composable
internal fun LiveText(text: String, color: Color, modifier: Modifier = Modifier) =
    Text(text, color = color, modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite })
