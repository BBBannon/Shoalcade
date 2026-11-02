package uk.benmarks.shoalcade.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import uk.benmarks.shoalcade.desktop.AppModel
import uk.benmarks.shoalcade.desktop.N3dsCheckPc
import uk.benmarks.shoalcade.desktop.GcCheckPc
import uk.benmarks.shoalcade.Console
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding

/**
 * The 3DS check on Windows (the phone's N3dsCheckActivity, advisory only, plan Q6): what it does, the result kept on
 * this PC, Start / Run again, and while it runs the corner words from its window. There is no lock and so no
 * "Unlock anyway": 3DS games always download and play here. The Settings panel's 3DS check tab (it can't be closed
 * while the check runs, as the dialog couldn't).
 */
@Composable
fun N3dsCheckContent(vm: AppModel) {
    val status = remember(vm.checkVersion) { vm.checkStatus() }
    val saved = remember(vm.checkVersion) { vm.checkSaved() }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(N3dsCheckPc.INTRO, style = MaterialTheme.typography.bodyLarge)
        Text(status, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        vm.checkLine?.let { line ->
            Text(if (line.startsWith("3DS")) N3dsCheckPc.RUNNING else GcCheckPc.RUNNING, style = MaterialTheme.typography.bodyMedium)
            Text(line, style = MaterialTheme.typography.bodyLarge)
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = Pocket.Accent, trackColor = Pocket.PLine)
        }
        vm.checkNote?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
        PocketButton(primary = true, onClick = { vm.runCheck() }, enabled = !vm.checkRunning && vm.playing == null) {
            Text(if (saved.result == null) N3dsCheckPc.START else N3dsCheckPc.AGAIN)
        }
        GcCheckContent(vm)
    }
}

/** The GameCube and Wii check (D-7) under the 3DS one: the same advisory run, one button per console. */
@Composable
private fun GcCheckContent(vm: AppModel) {
    Text(GcCheckPc.TITLE, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp).semantics { heading() })
    Text(GcCheckPc.INTRO, style = MaterialTheme.typography.bodyLarge)
    for (c in listOf(Console.GC, Console.WII)) {
        val status = remember(vm.checkVersion) { GcCheckPc.status(c, vm.gcCheckSaved(c)) }
        Text(status, style = MaterialTheme.typography.labelLarge)
    }
    vm.gcCheckNote?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (c in listOf(Console.GC, Console.WII)) PocketButton(onClick = { vm.runGcCheck(c) }, enabled = !vm.checkRunning && vm.playing == null) {
            Text(GcCheckPc.start(c))
        }
    }
}
