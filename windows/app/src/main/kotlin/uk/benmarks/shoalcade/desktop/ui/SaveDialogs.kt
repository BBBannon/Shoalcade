package uk.benmarks.shoalcade.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.benmarks.shoalcade.Console
import uk.benmarks.shoalcade.Game
import uk.benmarks.shoalcade.OrphanSave
import uk.benmarks.shoalcade.OrphanView
import uk.benmarks.shoalcade.SavePlan
import uk.benmarks.shoalcade.SaveVersion
import uk.benmarks.shoalcade.SyncAll
import uk.benmarks.shoalcade.SyncResult
import uk.benmarks.shoalcade.describeVersion
import uk.benmarks.shoalcade.desktop.AppModel
import uk.benmarks.shoalcade.desktop.DesktopApi
import uk.benmarks.shoalcade.desktop.PcWords
import uk.benmarks.shoalcade.desktop.SnapshotStore
import uk.benmarks.shoalcade.formatTime
import uk.benmarks.shoalcade.sizeLabel

// The phone's save dialogs (MainActivity.kt ConflictDialog / GetSaveDialog, SyncAllPanel.kt, GameActivity.kt's
// askReplaceNewer / askConflict, LaunchChoice.kt) with the same words; "phone" becomes "this PC" through PcWords.

/** D35: a conflict is never settled silently. Either way the other copy is kept (Jellyfin history + PC backups). */
@Composable
fun ConflictDialog(vm: AppModel, ask: AppModel.ConflictAsk) {
    val g = ask.game
    val pc = remember(g.id) { vm.pcSaveTime(g) }
    AlertDialog(
        onDismissRequest = { vm.conflictPrompt = null },
        title = { Text("Which save should be in use?") },
        text = {
            Column {
                Text(PcWords.of("${g.name} has a different save on this phone and on Jellyfin (maybe from the browser or another phone)."))
                Spacer(Modifier.height(8.dp))
                Text("This PC: ${pc?.let { formatTime(it) } ?: "none"}", fontWeight = FontWeight.Bold)
                Text("Jellyfin: ${ask.server?.let { describeVersion(it) } ?: "no save in use"}", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(PcWords.of("Nothing is lost either way: the other save is kept in Jellyfin's history and in the phone's backups."))
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = { vm.resolveConflict(g, keepPc = true) }) { Text(PcWords.of("Keep the phone's")) }
                TextButton(enabled = ask.server != null, onClick = { vm.resolveConflict(g, keepPc = false) }) { Text("Keep Jellyfin's") }
                TextButton(onClick = { vm.conflictPrompt = null }) { Text("Decide later") }
            }
        },
    )
}

/** "Get save from Jellyfin?": both dates first (the newer one marked); confirm waits for the check. */
@Composable
fun GetSaveDialog(vm: AppModel, game: Game, onDismiss: () -> Unit) {
    val pc = remember { vm.pcSaveTime(game) }
    var checking by remember { mutableStateOf(true) }
    var server by remember { mutableStateOf<SaveVersion?>(null) }
    var serverText by remember { mutableStateOf("Checking…") }
    LaunchedEffect(game.id) {
        try { server = vm.serverSave(game); serverText = server?.let { describeVersion(it) } ?: "none" }
        catch (e: CancellationException) { throw e } catch (e: Exception) { serverText = "couldn't check (${e.message})" }
        checking = false
    }
    val newer = SavePlan.newer(pc, server?.createdMillis)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Get save from Jellyfin?") },
        text = {
            Column {
                Text("PC save: ${pc?.let { formatTime(it) } ?: "none"}" + (if (newer == "phone") " (newer)" else ""))
                Text("Jellyfin save: $serverText" + (if (newer == "server") " (newer)" else ""))
                Spacer(Modifier.height(8.dp))
                Text("This replaces the save on this PC. This PC's copy is backed up first, and if Jellyfin hasn't seen it, it's also kept in Jellyfin's history.")
            }
        },
        confirmButton = { TextButton(enabled = !checking && server != null, onClick = { vm.getSave(game); onDismiss() }) { Text(if (checking) "Checking…" else "Get save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * The questions around a game: before it starts (Jellyfin's save changed: the Pull rule), and after Close Game (and
 * Upload) (the phone's "Replace a newer save?" and "Which save should be in use?"). Nothing changes until a choice.
 */
@Composable
fun PlayAskDialog(vm: AppModel, ask: AppModel.PlayAsk) {
    val g = ask.game
    when (ask) {
        is AppModel.PlayAsk.Before -> {
            val r = ask.result
            val conflict = r is SyncResult.Conflict
            val server = (r as? SyncResult.NeedsConfirm)?.server ?: (r as? SyncResult.Conflict)?.server
            val (pcLine, jfLine) = SavePlan.dateLines(vm.pcSaveTime(g), server)
            AlertDialog(
                onDismissRequest = {},
                title = { Text(if (conflict) "Which save should be in use?" else "Jellyfin's save changed") },
                text = {
                    Column {
                        Text(if (conflict) PcWords.of("${g.name} has a different save on this phone and on Jellyfin (maybe from the browser or another phone).")
                            else PcWords.of(r.message))
                        if (conflict) { Spacer(Modifier.height(8.dp)); Text(PcWords.of(pcLine)); Text(jfLine) }
                        Spacer(Modifier.height(8.dp))
                        Text(PcWords.of("Nothing is lost either way: the other save is kept in Jellyfin's history and in the phone's backups.") +
                            " Decide later plays the save on this PC and changes nothing.", style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = {
                    Column(horizontalAlignment = Alignment.End) {
                        if (conflict) TextButton(onClick = { vm.answerBefore(ask, SyncAll.Choice.KEEP_PHONE) }) { Text(PcWords.of("Keep the phone's")) }
                        TextButton(enabled = server != null, onClick = { vm.answerBefore(ask, SyncAll.Choice.KEEP_SERVER) }) { Text("Keep Jellyfin's") }
                        TextButton(onClick = { vm.answerBefore(ask, SyncAll.Choice.LATER) }) { Text("Decide later") }
                    }
                },
            )
        }
        is AppModel.PlayAsk.AfterNewer -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Replace a newer save?") },
            text = { Text(PcWords.of(ask.result.message)) },
            confirmButton = { TextButton(onClick = { vm.answerAfter(ask, SyncAll.Choice.KEEP_PHONE) }) { Text("Upload anyway") } },
            dismissButton = { TextButton(onClick = { vm.answerAfter(ask, SyncAll.Choice.LATER) }) { Text("Don't upload") } },
        )
        is AppModel.PlayAsk.AfterBoth -> {
            val (pcLine, jfLine) = SavePlan.dateLines(vm.pcSaveTime(g), ask.result.server)
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Which save should be in use?") },
                text = { Text(PcWords.of("${g.name} has a different save on this phone and on Jellyfin (maybe from the browser or another phone).\n\n" +
                    "$pcLine\n$jfLine\n\nNothing is lost either way: the other save is kept in Jellyfin's history and in the phone's backups.")) },
                confirmButton = {
                    Column(horizontalAlignment = Alignment.End) {
                        TextButton(onClick = { vm.answerAfter(ask, SyncAll.Choice.KEEP_PHONE) }) { Text(PcWords.of("Keep the phone's")) }
                        if (ask.result.server != null) TextButton(onClick = { vm.answerAfter(ask, SyncAll.Choice.KEEP_SERVER) }) { Text("Keep Jellyfin's") }
                        TextButton(onClick = { vm.answerAfter(ask, SyncAll.Choice.LATER) }) { Text("Decide later") }
                    }
                },
            )
        }
    }
}

/**
 * The launch choice (D90), the browser's words: "Continue from save" first, then each snapshot with its picture and
 * time. Snapshots from another core build are shown as made on another device and can't be picked.
 */
@Composable
fun LaunchChoiceDialog(vm: AppModel, l: AppModel.Launch) {
    AlertDialog(
        onDismissRequest = { vm.launch = null },
        modifier = Modifier.widthIn(min = 460.dp),
        title = { Text(l.game.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Button(onClick = { vm.start(l.game, null, l.enabled, l.snapshots) }, modifier = Modifier.fillMaxWidth()) { Text("Continue from save") }
                Spacer(Modifier.height(16.dp))
                Text("Or load a snapshot", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                l.snapshots.forEach { e -> SnapshotButton(e) { vm.start(l.game, e, l.enabled, l.snapshots) }; Spacer(Modifier.height(6.dp)) }
                Spacer(Modifier.height(4.dp))
                Text("Loading a snapshot never changes your save. Only saving in the game does.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { vm.launch = null }) { Text("Cancel") } },
    )
}

@Composable
private fun SnapshotButton(e: SnapshotStore.Entry, onPick: () -> Unit) {
    val picture: ImageBitmap? = remember(e.picture, e.time) {
        runCatching { e.picture?.readBytes()?.let { org.jetbrains.skia.Image.makeFromEncoded(it).toComposeImageBitmap() } }.getOrNull()
    }
    OutlinedButton(onClick = onPick, enabled = e.here, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val box = Modifier.size(width = 96.dp, height = 64.dp).background(Color.Black)
            if (picture != null) Image(picture, null, box, contentScale = ContentScale.Fit, filterQuality = FilterQuality.None)
            else Box(box, contentAlignment = Alignment.Center) { Text("No picture", style = MaterialTheme.typography.labelSmall, color = Color.White) }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Snapshot: ${e.label}", fontWeight = FontWeight.Bold)
                Text(e.time?.let { formatTime(it) } ?: "", style = MaterialTheme.typography.bodySmall)
                if (!e.here) Text(if (e.damaged) "Damaged, so it can't be loaded." else "Made on another device (a different emulator version), so it can't be loaded here.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                else if (!e.onJellyfin) Text("Only on this PC so far.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** A running Push all / Pull all: how far it has got, and Stop (SyncAllPanel.kt SyncAllProgress). */
@Composable
fun SyncAllProgress(vm: AppModel) {
    val b = vm.batch ?: return
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        LinearProgressIndicator(progress = { (b.index + 1f) / b.total }, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (vm.batchStopping) "Stopping after ${b.game}…" else "${b.way.word}: ${b.index + 1} of ${b.total}, ${b.game}",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.stopBatch() }, enabled = !vm.batchStopping) { Text("Stop") }
        }
    }
}

/** The end of a run: the summary, anything that failed, and every game needing a decision (SyncAllPanel.kt). */
@Composable
fun SyncAllDialog(vm: AppModel, report: SyncAll.Report) {
    val close = { vm.batchReport = null; vm.batchAsks.clear(); vm.batchSettled.clear() }
    AlertDialog(
        onDismissRequest = { if (vm.settling == null) close() },
        title = { Text("${report.way.word} finished") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(PcWords.of(report.summary()), fontWeight = FontWeight.Bold)
                report.failed.forEach { (g, why) -> Text("${g.name}: ${PcWords.of(why)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
                if (vm.batchAsks.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(PcWords.of("These need you. Nothing has changed for them yet, and whichever you keep, the other save is kept " +
                        "in Jellyfin's history and the phone's backups."), style = MaterialTheme.typography.bodySmall)
                }
                vm.batchAsks.forEach { ask -> AskCard(vm, ask) }
            }
        },
        confirmButton = { TextButton(onClick = close, enabled = vm.settling == null) { Text("Done") } },
    )
}

@Composable
private fun AskCard(vm: AppModel, ask: SyncAll.Ask) {
    val (pc, jf) = SavePlan.dateLines(ask.phoneTime, ask.server)
    val result = vm.batchSettled[ask.game.id]
    OutlinedCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(10.dp)) {
            Text(ask.game.name, fontWeight = FontWeight.Bold)
            Text(if (ask.conflict) PcWords.of("⚠️ Changed on the phone and on Jellyfin") else "This would replace a newer save",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Text(PcWords.of(pc), style = MaterialTheme.typography.bodySmall)
            Text(jf, style = MaterialTheme.typography.bodySmall)
            if (ask.again && result == null) Text(ask.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            when {
                result != null -> Text(result, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                vm.settling == ask.game.id -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                else -> Column(horizontalAlignment = Alignment.End, modifier = Modifier.fillMaxWidth()) {
                    val busy = vm.settling != null
                    TextButton(onClick = { vm.settleAsk(ask, SyncAll.Choice.KEEP_PHONE) }, enabled = !busy) { Text(PcWords.of("Keep the phone's")) }
                    TextButton(onClick = { vm.settleAsk(ask, SyncAll.Choice.KEEP_SERVER) }, enabled = !busy && ask.server != null) { Text("Keep Jellyfin's") }
                    TextButton(onClick = { vm.settleAsk(ask, SyncAll.Choice.LATER) }, enabled = !busy) { Text("Decide later") }
                }
            }
        }
    }
}

/**
 * Settings → Saves (the phone's section, D132): Push all / Pull all, "Upload saves automatically", Not in your
 * library, and the browser's "Download all my saves".
 */
@Composable
fun SavesSettingsDialog(vm: AppModel, onOpenSaves: (Game, Boolean) -> Unit, onDismiss: () -> Unit) {
    var orphans by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(min = 520.dp),
        title = { Text("Saves") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val enabled = vm.settling == null && !vm.loading && vm.batch == null
                    OutlinedButton(onClick = { vm.syncAll(SyncAll.Way.PUSH); onDismiss() }, enabled = enabled, modifier = Modifier.weight(1f)) { Text(SyncAll.Way.PUSH.word) }
                    OutlinedButton(onClick = { vm.syncAll(SyncAll.Way.PULL); onDismiss() }, enabled = enabled, modifier = Modifier.weight(1f)) { Text(SyncAll.Way.PULL.word) }
                }
                Text("Changing PCs? Push all first, then log in on the new PC.", style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Upload saves automatically")
                        Text(PcWords.of("When the phone is back online, saves made offline go up to your Jellyfin by themselves.") +
                            " While Shoalcade is open, a save made in a game also goes up a few seconds later. Anything that needs a choice is never settled by itself.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(vm.autoUploadOn, { vm.setAutoUpload(it) })
                }
                HorizontalDivider()
                OutlinedButton(onClick = { orphans = true }, modifier = Modifier.fillMaxWidth()) { Text("Not in your library") }
                OutlinedButton(onClick = {
                    val f = FilePick.save("Download all my saves", "Shoalcade saves ${java.time.LocalDate.now()}.zip") ?: return@OutlinedButton
                    status = "Downloading all your saves…"
                    scope.launch {
                        status = try { vm.tools()?.downloadAll(f) ?: "Not logged in." }
                            catch (e: CancellationException) { throw e } catch (e: Exception) { "Couldn't download your saves: ${e.message}" }
                    }
                }, enabled = !vm.offline, modifier = Modifier.fillMaxWidth()) { Text("Download all my saves") }
                Text("“Download all my saves” gives one zip with the save in use for every game: a copy to keep somewhere safe.", style = MaterialTheme.typography.bodySmall)
                status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
    if (orphans) NotInLibraryDialog(vm, onOpenSaves) { orphans = false }
}

/**
 * "Not in your library": games no longer in the library that still have a save on this PC (the phone's B9 list: Save a
 * copy to a file…, Upload to another game…; nothing here deletes them), and those that still have saves on Jellyfin
 * (the browser's list: open their saves to Move or Delete all).
 */
@Composable
fun NotInLibraryDialog(vm: AppModel, onOpenSaves: (Game, Boolean) -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var local by remember { mutableStateOf<OrphanView?>(null) }
    var server by remember { mutableStateOf<List<DesktopApi.SaveEntry>?>(null) }
    var serverError by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf<OrphanSave?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(reload, vm.games) {
        local = vm.orphanSaves()
        try { server = vm.api()?.savesList()?.filter { it.status == "removed" }; serverError = null }
        catch (e: CancellationException) { throw e } catch (e: Exception) { serverError = e.message }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(min = 560.dp),
        title = { Text("Not in your library") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                status?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 6.dp)) }
                Text("On this PC", style = MaterialTheme.typography.titleSmall)
                Text("Games no longer in your library that still have a save on this PC. Saves stay on this PC: nothing here deletes them.",
                    style = MaterialTheme.typography.bodySmall)
                when (val l = local) {
                    null -> Text("Loading…")
                    is OrphanView.ListNotLoaded -> Text("Game list not loaded yet. Connect to Jellyfin once so the app knows which games are in your library.")
                    is OrphanView.Failed -> Text("Couldn't read the saves on this PC: ${l.reason}", color = MaterialTheme.colorScheme.error)
                    is OrphanView.Ready -> if (l.saves.isEmpty()) Text("None.") else l.saves.forEach { o ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Text(o.title, fontWeight = FontWeight.SemiBold)
                            Text(listOfNotNull(o.console?.shortName, o.time?.let { formatTime(it) }, if (o.hasSave) sizeLabel(o.size) else null,
                                when { !o.hasSave -> "No save"; o.blank -> "Empty (the game never saved)"; o.onJellyfin -> "A copy is on Jellyfin too"; else -> "Only on this PC" })
                                .joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                            Row {
                                if (o.hasSave) TextButton(onClick = {
                                    val f = FilePick.save("Save a copy to a file…", "${o.title}.${if (o.folder) "zip" else "sav"}") ?: return@TextButton
                                    scope.launch {
                                        status = try {
                                            val bytes = withContext(Dispatchers.IO) { vm.core()?.orphanBytes(o) } ?: throw java.io.IOException("the save has gone from this PC.")
                                            withContext(Dispatchers.IO) { uk.benmarks.shoalcade.desktop.AtomicFile.write(f, bytes) }
                                            "Saved a copy of the ${o.title} save."
                                        } catch (e: Exception) { "Couldn't save the copy: ${e.message}" }
                                    }
                                }) { Text("Save a copy to a file…") }
                                if (o.hasSave && !o.blank) TextButton(onClick = { picking = o }) { Text("Upload to another game…") }
                            }
                        }
                        HorizontalDivider()
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("On Jellyfin", style = MaterialTheme.typography.titleSmall)
                when {
                    serverError != null -> Text("Couldn't load the list from Jellyfin: $serverError", color = MaterialTheme.colorScheme.error)
                    server == null -> Text("Loading…")
                    server!!.isEmpty() -> Text("None.")
                    else -> server!!.forEach { e ->
                        val g = Game(e.itemId, e.gameName ?: e.itemId, Console.fromKey(e.console), "", false)
                        Column(Modifier.fillMaxWidth().clickable { onOpenSaves(g, true) }.padding(vertical = 6.dp)) {
                            Text(e.gameName ?: "Unknown game", fontWeight = FontWeight.SemiBold)
                            Text(listOfNotNull(Console.fromKey(e.console)?.shortName, "${e.versionCount} save versions",
                                if (e.snapshots > 0) "${e.snapshots} snapshots" else null, sizeLabel(e.totalBytes)).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall)
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
    picking?.let { o ->
        val targets = remember(o, vm.games) { OrphanSave.targets(o, vm.games) }
        AlertDialog(
            onDismissRequest = { picking = null },
            title = { Text("Upload to which game?") },
            text = {
                if (targets.isEmpty()) Text("No game in your library can take this save.")
                else LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(targets, key = { it.id }) { g ->
                        Text(g.name + (g.console?.let { "  (${it.shortName})" } ?: ""), Modifier.fillMaxWidth().clickable {
                            picking = null
                            vm.uploadOrphan(o, g, "normal") { r -> status = "${g.name}: ${PcWords.of(r.message)}"; reload++ }
                        }.padding(vertical = 10.dp))
                        HorizontalDivider()
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picking = null }) { Text("Cancel") } },
        )
    }
}
