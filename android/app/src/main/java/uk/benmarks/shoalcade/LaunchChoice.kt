package uk.benmarks.shoalcade

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** D90: true when Play should ask "save or snapshot?": snapshots on for this console and a snapshot on the phone. */
fun hasLaunchSnapshot(ctx: Context, game: Game, userId: String): Boolean {
    val console = game.console ?: return false
    return SnapshotSetting.enabled(ctx, console) && Storage.stateFile(userId, game).isFile
}

/**
 * The launch choice (D90), same words as the browser player: "Continue from save" (the default, focused first) or
 * the phone's snapshot, with its picture and time. Loading a snapshot never changes the save; only saving in the game does.
 * [onDelete] (if given) adds "Delete snapshot…", which asks first and never touches the save.
 */
@Composable
fun LaunchChoiceDialog(game: Game, userId: String, onStart: (snapshot: Boolean) -> Unit, onDismiss: () -> Unit,
                       onDelete: (() -> Unit)? = null) {
    var confirmDelete by remember { mutableStateOf(false) }
    if (confirmDelete && onDelete != null) {
        val t = remember(game.id) { formatTime(Storage.stateFile(userId, game).lastModified()) }
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { DialogTitle("Delete the snapshot?") },
            text = { Text("The snapshot of ${game.name} from $t is deleted from this phone. This can't be undone. The save is kept.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete snapshot") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
        return
    }
    val time = remember(game.id) { formatTime(Storage.stateFile(userId, game).lastModified()) }
    val picture = remember(game.id) {
        runCatching { BitmapFactory.decodeFile(Storage.stateImageFile(userId, game).path)?.asImageBitmap() }.getOrNull()
    }
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { DialogTitle(game.name) },
        text = {
            Column {
                Button(onClick = { onStart(false) }, modifier = Modifier.fillMaxWidth().focusRequester(focus)) {
                    Text("Continue from save")
                }
                Spacer(Modifier.height(16.dp))
                Text("Or load a snapshot", style = MaterialTheme.typography.labelLarge, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { onStart(true) }, modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium, contentPadding = PaddingValues(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        val box = Modifier.size(width = 84.dp, height = 56.dp).background(Color.Black)
                        if (picture != null) {
                            Image(picture, contentDescription = null, modifier = box,
                                contentScale = ContentScale.Fit, filterQuality = FilterQuality.None)
                        } else {
                            Box(box, contentAlignment = Alignment.Center) {
                                Text("No picture", style = MaterialTheme.typography.labelSmall, color = Color.White)
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("Snapshot", fontWeight = FontWeight.Bold)
                            Text(time, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (onDelete != null) {
                    TextButton(onClick = { confirmDelete = true }, modifier = Modifier.align(Alignment.End)) {
                        Text("Delete snapshot…")
                    }
                } else Spacer(Modifier.height(8.dp))
                Text("Loading a snapshot never changes your save. Only saving in the game does.",
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}
