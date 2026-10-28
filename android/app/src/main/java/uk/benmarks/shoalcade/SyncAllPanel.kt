package uk.benmarks.shoalcade

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Item 6: "Push all" / "Pull all", or the progress of a run with a Stop button. In Settings since D132 (they were at
 * the top of the games list); the library still shows a running one ([SyncAllProgress]) so it can always be stopped.
 */
@Composable
internal fun SyncAllBar(vm: AppViewModel) {
    if (vm.batch == null) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val enabled = vm.settling == null && !vm.loading
            OutlinedButton(onClick = { vm.syncAll(SyncAll.Way.PUSH) }, enabled = enabled, modifier = Modifier.weight(1f)) { Text(SyncAll.Way.PUSH.word) }
            OutlinedButton(onClick = { vm.syncAll(SyncAll.Way.PULL) }, enabled = enabled, modifier = Modifier.weight(1f)) { Text(SyncAll.Way.PULL.word) }
        }
        return
    }
    SyncAllProgress(vm)
}

/** A running Push all / Pull all: how far it has got, and Stop. Shows nothing when none is running. */
@Composable
internal fun SyncAllProgress(vm: AppViewModel) {
    val b = vm.batch ?: return
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        LinearProgressIndicator(progress = { (b.index + 1f) / b.total }, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (vm.batchStopping) "Stopping after ${b.game}…" else "${b.way.word}: ${b.index + 1} of ${b.total}, ${b.game}",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { vm.stopBatch() }, enabled = !vm.batchStopping) { Text("Stop") }
        }
    }
}

/**
 * The end of a run: the summary, anything that failed, and every game needing a decision with both dates and
 * "Keep the phone's" / "Keep Jellyfin's" / "Decide later". Closing leaves undecided ones untouched.
 */
@Composable
internal fun SyncAllDialog(vm: AppViewModel, report: SyncAll.Report) {
    val close = { vm.batchReport = null; vm.batchAsks.clear(); vm.batchSettled.clear() }
    AlertDialog(
        onDismissRequest = { if (vm.settling == null) close() },
        title = { Text("${report.way.word} finished") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(report.summary(), fontWeight = FontWeight.Bold)
                report.failed.forEach { (g, why) -> Text("${g.name}: $why", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
                if (vm.batchAsks.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("These need you. Nothing has changed for them yet, and whichever you keep, the other save is kept " +
                        "in Jellyfin's history and the phone's backups.", style = MaterialTheme.typography.bodySmall)
                }
                vm.batchAsks.forEach { ask -> AskCard(vm, ask) }
            }
        },
        confirmButton = { TextButton(onClick = close, enabled = vm.settling == null) { Text("Done") } },
    )
}

@Composable
private fun AskCard(vm: AppViewModel, ask: SyncAll.Ask) {
    val (phone, jf) = SavePlan.dateLines(ask.phoneTime, ask.server)
    val result = vm.batchSettled[ask.game.id]
    OutlinedCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(10.dp)) {
            Text(ask.game.name, fontWeight = FontWeight.Bold)
            Text(if (ask.conflict) "⚠️ Changed on the phone and on Jellyfin" else "This would replace a newer save",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Text(phone, style = MaterialTheme.typography.bodySmall)
            Text(jf, style = MaterialTheme.typography.bodySmall)
            // L3: an earlier choice was refused because Jellyfin's save changed; the lines above now show the newer one.
            if (ask.again && result == null) Text(ask.message, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            when {
                result != null -> Text(result, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                vm.settling == ask.game.id -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                else -> Column(horizontalAlignment = Alignment.End, modifier = Modifier.fillMaxWidth()) {
                    val busy = vm.settling != null
                    TextButton(onClick = { vm.settleAsk(ask, SyncAll.Choice.KEEP_PHONE) }, enabled = !busy) { Text("Keep the phone's") }
                    TextButton(onClick = { vm.settleAsk(ask, SyncAll.Choice.KEEP_SERVER) }, enabled = !busy && ask.server != null) { Text("Keep Jellyfin's") }
                    TextButton(onClick = { vm.settleAsk(ask, SyncAll.Choice.LATER) }, enabled = !busy) { Text("Decide later") }
                }
            }
        }
    }
}
