package uk.benmarks.shoalcade.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.benmarks.shoalcade.Game
import uk.benmarks.shoalcade.SaveHistory
import uk.benmarks.shoalcade.SaveVersion
import uk.benmarks.shoalcade.Storage
import uk.benmarks.shoalcade.SyncResult
import uk.benmarks.shoalcade.describeVersion
import uk.benmarks.shoalcade.desktop.AppModel
import uk.benmarks.shoalcade.desktop.DesktopApi
import uk.benmarks.shoalcade.desktop.PcWords
import uk.benmarks.shoalcade.desktop.SaveTools
import uk.benmarks.shoalcade.formatTime
import uk.benmarks.shoalcade.fromLabel
import uk.benmarks.shoalcade.sizeLabel
import java.io.File

/** The browser saves manager's "In use" badge colour (sc.css .badge), as the phone's SavesPanel. */
private val InUseRed = Color(0xFFC8324B)

@Composable
private fun Badge(text: String, bg: Color, fg: Color) {
    Surface(color = bg, contentColor = fg, shape = RoundedCornerShape(2.dp), modifier = Modifier.padding(start = 6.dp)) {
        Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
    }
}

/**
 * "Saves: <game>": the phone's SavesPanel (history, In use / Pinned, Use this save, Pin, Name, Replace with a file…,
 * Save a copy to a file…) plus the browser saves manager's tools: Move to another game, Delete (an old version),
 * Start fresh, and for a game no longer in the library ([removed]) Delete all saves after typing its name. Every
 * destructive action asks first with the browser's words. Files go through the Windows file dialogs.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SavesPanel(vm: AppModel, game: Game, removed: Boolean = false, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var history by remember { mutableStateOf<SaveHistory?>(null) }
    var snaps by remember { mutableStateOf<List<DesktopApi.Snapshot>>(emptyList()) }
    var loadFailed by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusError by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<SaveVersion?>(null) }
    var confirmUse by remember { mutableStateOf<SaveVersion?>(null) }
    var naming by remember { mutableStateOf<SaveVersion?>(null) }
    var deleting by remember { mutableStateOf<SaveVersion?>(null) }
    var replaceConflict by remember { mutableStateOf<Pair<ByteArray, SaveVersion?>?>(null) }
    var moving by remember { mutableStateOf(false) }
    var moveTo by remember { mutableStateOf<Game?>(null) }
    var fresh by remember { mutableStateOf(false) }
    var deleteAll by remember { mutableIntStateOf(0) } // 1 = first question, 2 = type to confirm
    val tools = remember(vm.session) { vm.tools() }

    LaunchedEffect(reload) {
        loadFailed = false
        try {
            history = tools?.history(game) ?: throw java.io.IOException("Not logged in.")
            snaps = if (removed) runCatching { vm.api()?.snapshots(game.id)?.first }.getOrNull().orEmpty() else emptyList()
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            loadFailed = true; statusError = true; status = "Couldn't load the saves: ${e.message}"
        }
    }

    fun done(text: String, error: Boolean = false) { busy = false; statusError = error; status = text; reload++ }
    fun act(block: suspend SaveTools.() -> String) {
        val t = tools ?: return
        busy = true
        scope.launch {
            try { done(PcWords.of(t.block())) } catch (e: CancellationException) { throw e } catch (e: Exception) { done(SaveTools.failText(e), true) }
            vm.refreshMarks()
        }
    }
    fun after(r: SyncResult) { done(PcWords.of(r.message), r is SyncResult.Failed); vm.refreshMarks() }
    fun upload(bytes: ByteArray, mode: String) {
        val t = tools ?: return
        busy = true
        scope.launch {
            val r = t.replace(game, bytes, history?.current ?: 0, mode)
            if (r is SyncResult.Conflict) { busy = false; replaceConflict = bytes to r.server } else after(r)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(min = 560.dp, max = 760.dp),
        title = { Text("Saves: ${game.name}") },
        text = {
            Column {
                if (removed) Text("This game is no longer in your library.", style = MaterialTheme.typography.bodySmall)
                vm.saveRules?.let { (recent, days) ->
                    Text("Jellyfin keeps the last $recent versions plus one a day for $days days. The one in use and pinned ones are never removed.",
                        style = MaterialTheme.typography.bodySmall)
                }
                status?.let { Text(it, color = if (statusError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 6.dp)) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                val h = history
                when {
                    h == null && loadFailed -> OutlinedButton(onClick = { status = null; reload++ }) { Text("Retry") }
                    h == null -> Text("Loading…")
                    h.versions.isEmpty() -> Text(if (removed && snaps.isNotEmpty()) "No save versions for this game, only snapshots." else "No saves on Jellyfin yet.")
                    else -> LazyColumn(Modifier.heightIn(max = 380.dp)) {
                        items(h.versions, key = { it.version }) { v ->
                            Column(Modifier.fillMaxWidth().clickable(enabled = !busy) { selected = v }.padding(vertical = 6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Version ${v.version}", fontWeight = if (v.isCurrent) FontWeight.Bold else FontWeight.Normal)
                                    if (v.isCurrent) Badge("In use", InUseRed, Color.White)
                                    if (v.pinned) Badge("Pinned", MaterialTheme.colorScheme.inverseSurface, MaterialTheme.colorScheme.inverseOnSurface)
                                }
                                v.label?.let { Text(it, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }
                                Text(listOfNotNull(v.createdMillis?.let { formatTime(it) }, fromLabel(v), sizeLabel(v.size),
                                    if (v.origin == "browser") "Was a browser save (merged in)" else null,
                                    if (v.afterSnapshot) "Saved after loading a snapshot" else null,
                                    if (v.cheats) "Made with cheats on" else null).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                            }
                            HorizontalDivider()
                        }
                    }
                }
                // The browser saves manager's tools for the whole game.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    if (h != null && (h.versions.isNotEmpty() || snaps.isNotEmpty())) OutlinedButton(onClick = { moving = true }, enabled = !busy) { Text("Move to another game") }
                    if (!removed && h != null && h.current != 0) OutlinedButton(onClick = { fresh = true }, enabled = !busy) { Text("Start fresh") }
                    if (removed && h != null && (h.versions.isNotEmpty() || snaps.isNotEmpty())) OutlinedButton(onClick = { deleteAll = 1 }, enabled = !busy) { Text("Delete all saves for this game") }
                }
            }
        },
        confirmButton = {
            if (!removed) TextButton(enabled = !busy && history != null, onClick = {
                val f = FilePick.open("Replace with a file…") ?: return@TextButton
                if (f.length() > SaveTools.MAX_FILE) { statusError = true; status = "That file is far too big to be a save."; return@TextButton }
                scope.launch {
                    val bytes = try { withContext(Dispatchers.IO) { f.readBytes() } } catch (e: Exception) { statusError = true; status = "Couldn't read the file: ${e.message}"; return@launch }
                    game.console?.wrongSaveSize(bytes.size)?.let { statusError = true; status = it; return@launch }
                    upload(bytes, "normal")
                }
            }) { Text("Replace with a file…") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )

    selected?.let { v ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text("Version ${v.version}") },
            text = { Text((v.label?.let { "“$it”\n" } ?: "") + describeVersion(v) + "\n" + sizeLabel(v.size) +
                (if (v.isCurrent) "\nThis is the save in use." else "") + (if (v.pinned) "\nPinned: never cleared out." else "")) },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    if (!v.isCurrent && !removed) TextButton(onClick = { selected = null; confirmUse = v }) { Text("Use this save") }
                    TextButton(onClick = { selected = null; act { pin(game, v) } }) { Text(if (v.pinned) "Unpin" else "Pin") }
                    TextButton(onClick = { selected = null; naming = v }) { Text("Name") }
                    TextButton(onClick = {
                        selected = null
                        val ext = if (game.console?.hasFolderSaves == true) "zip" else "sav"
                        val f = FilePick.save("Save a copy to a file…", "${game.name} v${v.version}.$ext") ?: return@TextButton
                        act { saveCopy(game, v.version, f) }
                    }) { Text("Save a copy to a file…") }
                    if (!v.isCurrent && !v.pinned) TextButton(onClick = { selected = null; deleting = v }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { selected = null }) { Text("Cancel") }
                }
            },
        )
    }

    confirmUse?.let { v ->
        AlertDialog(
            onDismissRequest = { confirmUse = null },
            title = { Text("Use this save?") },
            text = { Text("Version ${v.version} becomes the save in use. The save you were using stays in the list." +
                if (Storage.isDownloaded(game)) " It's then put on this PC; your PC save is backed up first." else "") },
            confirmButton = { TextButton(onClick = {
                confirmUse = null; busy = true
                scope.launch { tools?.let { after(it.use(game, v.version, history?.current ?: 0)) } }
            }) { Text("Use this save") } },
            dismissButton = { TextButton(onClick = { confirmUse = null }) { Text("Cancel") } },
        )
    }

    naming?.let { v ->
        val max = vm.maxLabelLength
        var text by remember(v.version) { mutableStateOf((v.label ?: "").take(max)) }
        AlertDialog(
            onDismissRequest = { naming = null },
            title = { Text("Name this save") },
            text = {
                Column {
                    Text("Version ${v.version}" + (v.createdMillis?.let { ", " + formatTime(it) } ?: "") + ". Leave it empty to remove the name.")
                    OutlinedTextField(text, { text = it.filterNot { c -> c.isISOControl() }.take(max) }, label = { Text("Name") }, singleLine = true,
                        supportingText = { Text("${text.length} / $max") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            },
            confirmButton = { TextButton(onClick = { naming = null; act { name(game, v, text, max) } }) { Text("Save name") } },
            dismissButton = { TextButton(onClick = { naming = null }) { Text("Cancel") } },
        )
    }

    deleting?.let { v ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete this save?") },
            text = { Text("Version ${v.version} from ${v.createdMillis?.let { formatTime(it) } ?: "an unknown time"}" +
                (v.label?.let { " (“$it”)" } ?: "") + " will be deleted for good. This can't be undone.\n\nSave a copy to a file first if you might want it later.") },
            confirmButton = { TextButton(onClick = { deleting = null; act { deleteVersion(game, v) } }) { Text("Delete for good", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }

    replaceConflict?.let { (bytes, server) ->
        AlertDialog(
            onDismissRequest = { replaceConflict = null },
            title = { Text("The save changed") },
            text = { Text("Another device saved this game since the list was loaded" + (server?.let { " (${describeVersion(it)})" } ?: "") +
                ". Which should be in use? Both are kept in the list either way.") },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { replaceConflict = null; upload(bytes, "force") }) { Text("Use my file") }
                    TextButton(onClick = { replaceConflict = null; upload(bytes, "history") }) { Text("Keep Jellyfin's") }
                    TextButton(onClick = { replaceConflict = null; reload++ }) { Text("Cancel") }
                }
            },
        )
    }

    if (moving) {
        val targets = remember(vm.games) { tools?.moveTargets(game, vm.games).orEmpty() }
        AlertDialog(
            onDismissRequest = { moving = false },
            title = { Text("Move to another game") },
            text = {
                if (targets.isEmpty()) Text("There are no other ${game.console?.tag ?: ""} games in your library to move this save to.")
                else LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(targets, key = { it.id }) { g ->
                        Text(if (g.console == game.console) g.name else "${g.name} (${g.console?.tag})",
                            Modifier.fillMaxWidth().clickable { moving = false; moveTo = g }.padding(vertical = 10.dp))
                        HorizontalDivider()
                    }
                }
            },
            confirmButton = { TextButton(onClick = { moving = false }) { Text("Cancel") } },
        )
    }

    moveTo?.let { target ->
        val n = history?.versions?.size ?: 0
        AlertDialog(
            onDismissRequest = { moveTo = null },
            title = { Text(if (n == 0) "Move these snapshots?" else "Move this save?") },
            text = { Text(if (n == 0) "Its snapshots move to “${target.name}”. That game's save isn't changed.\n\n" +
                "A snapshot slot “${target.name}” already uses is never replaced: that snapshot stays here."
                else "All $n ${if (n == 1) "version" else "versions"} move to “${target.name}”. Nothing is deleted, but this game's list will be empty afterwards.\n\n" +
                    "If “${target.name}” already has a save in use, it stays in use; the moved versions go into its list, so you can pick one there with “Use this save”. " +
                    "Otherwise the save in use here becomes the save in use for “${target.name}”.\n\n" +
                    "Its snapshots move too. A snapshot slot “${target.name}” already uses is never replaced: that snapshot stays here.\n\n" +
                    "The saves on this PC aren't changed.") },
            confirmButton = { TextButton(onClick = { moveTo = null; act { move(game, target, n > 0) } }) { Text(if (n == 0) "Move snapshots" else "Move save") } },
            dismissButton = { TextButton(onClick = { moveTo = null }) { Text("Cancel") } },
        )
    }

    if (fresh) {
        val cur = history?.versions?.firstOrNull { it.isCurrent }
        AlertDialog(
            onDismissRequest = { fresh = false },
            title = { Text("Start fresh?") },
            text = { Text("Next time you play, the game starts from the beginning.\n\n" +
                "Nothing is deleted: the save in use now (version ${cur?.version}, ${cur?.createdMillis?.let { formatTime(it) } ?: ""}) stays in the list, " +
                "so you can pick it again any time with “Use this save”.\n\nThis save is shared with the phone app and the browser. " +
                "The save on this PC is backed up first, then taken off.") },
            confirmButton = { TextButton(onClick = { fresh = false; if (cur != null) act { startFresh(game, cur) } }) { Text("Start fresh", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { fresh = false }) { Text("Cancel") } },
        )
    }

    if (deleteAll > 0) {
        val vs = history?.versions.orEmpty()
        val known = game.name.isNotBlank() && game.name != game.id
        val name = if (known) game.name else "Unknown game"
        val pinned = vs.count { it.pinned }
        val what = listOfNotNull(
            if (vs.isNotEmpty()) "${vs.size} ${if (vs.size == 1) "save version" else "save versions"}" + (if (pinned > 0) " ($pinned pinned)" else "") else null,
            if (snaps.isNotEmpty()) "${snaps.size} ${if (snaps.size == 1) "snapshot" else "snapshots"}" else null).joinToString(" and ")
        if (deleteAll == 1) AlertDialog(
            onDismissRequest = { deleteAll = 0 },
            title = { Text("Delete all saves for “$name”?") },
            text = { Text("$what will be deleted, including pinned ones and the one in use. You can't undo this here.\n\n" +
                "The server keeps a hidden copy for 7 days, so your server admin can bring them back if this was a mistake.\n\n" +
                "If the game is only missing because a drive or folder is offline, it may come back: its saves would then be gone.\n\n" +
                "Download the save first if you might want it later. Saves on this PC aren't deleted.") },
            confirmButton = { TextButton(onClick = { deleteAll = 2 }) { Text("Continue", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleteAll = 0 }) { Text("Cancel") } },
        ) else {
            var typed by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { deleteAll = 0 },
                title = { Text("Type to confirm") },
                text = {
                    Column {
                        Text(if (known) "To delete everything for this game, type its name: “$name”." else "This game's name isn't known. To delete everything, type: delete")
                        OutlinedTextField(typed, { typed = it.take(200) }, label = { Text(if (known) "Game name" else "Type delete") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    }
                },
                confirmButton = { TextButton(onClick = {
                    deleteAll = 0
                    act { deleteAll(game, removed, known, typed, vs.size, snaps.size).also { onDismiss() } }
                }) { Text("Delete all", color = MaterialTheme.colorScheme.error) } },
                dismissButton = { TextButton(onClick = { deleteAll = 0 }) { Text("Cancel") } },
            )
        }
    }
}

/** The Windows file dialogs (native on Windows). Null = the person cancelled. */
object FilePick {
    fun open(title: String): File? {
        val d = java.awt.FileDialog(null as java.awt.Frame?, title, java.awt.FileDialog.LOAD)
        d.isVisible = true
        return d.file?.let { File(d.directory, it) }
    }

    fun save(title: String, suggested: String): File? {
        val d = java.awt.FileDialog(null as java.awt.Frame?, title, java.awt.FileDialog.SAVE)
        d.file = suggested.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        d.isVisible = true
        return d.file?.let { File(d.directory, it) }
    }
}
