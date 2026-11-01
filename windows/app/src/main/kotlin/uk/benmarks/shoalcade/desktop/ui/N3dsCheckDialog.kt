package uk.benmarks.shoalcade.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import uk.benmarks.shoalcade.desktop.AppModel
import uk.benmarks.shoalcade.desktop.N3dsCheckPc

/**
 * The 3DS check on Windows (the phone's N3dsCheckActivity, advisory only, plan Q6): what it does, the result kept on
 * this PC, Start / Run again, and while it runs the corner words from its window. There is no lock and so no
 * "Unlock anyway": 3DS games always download and play here.
 */
@Composable
fun N3dsCheckDialog(vm: AppModel, onDismiss: () -> Unit) {
    val status = remember(vm.checkVersion) { vm.checkStatus() }
    val saved = remember(vm.checkVersion) { vm.checkSaved() }
    AlertDialog(
        onDismissRequest = { if (!vm.checkRunning) onDismiss() },
        modifier = Modifier.widthIn(max = 560.dp),
        title = { Text(N3dsCheckPc.TITLE) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(N3dsCheckPc.INTRO, style = MaterialTheme.typography.bodyLarge)
                Text(status, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                vm.checkLine?.let { line ->
                    Text(N3dsCheckPc.RUNNING, style = MaterialTheme.typography.bodyMedium)
                    Text(line, style = MaterialTheme.typography.bodyLarge)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                vm.checkNote?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
            }
        },
        confirmButton = {
            Button(onClick = { vm.runCheck() }, enabled = !vm.checkRunning && vm.playing == null) {
                Text(if (saved.result == null) N3dsCheckPc.START else N3dsCheckPc.AGAIN)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !vm.checkRunning) { Text("Close") } },
    )
}
