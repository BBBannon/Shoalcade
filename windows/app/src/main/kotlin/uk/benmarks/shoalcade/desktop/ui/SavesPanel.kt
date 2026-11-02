package uk.benmarks.shoalcade.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import uk.benmarks.shoalcade.Console
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
    /** F7 gap 2: the console picked for a removed game whose console wasn't recorded (the browser's #move-con). */
    var picked by remember { mutableStateOf<Console?>(null) }
    var fresh by remember { mutableStateOf(false) }
    var deleteAll by remember { mutableIntStateOf(0) } // 1 = first question, 2 = type to confirm
    val tools = remember(vm.session) { vm.tools() }

    LaunchedEffect(reload) {
        loadFailed = false
        try {
            history = tools?.history(game) ?: throw java.io.IOException("Not logged in.")
            snaps = if (removed) runCatching { vm.api()?.snapshots(game.id)?.list }.getOrNull().orEmpty() else emptyList()
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

    fun replaceFromFile() {
        val f = FilePick.open("Replace with a file…") ?: return
        if (f.length() > SaveTools.MAX_FILE) { statusError = true; status = "That file is far too big to be a save."; return }
        scope.launch {
            val bytes = try { withContext(Dispatchers.IO) { f.readBytes() } } catch (e: Exception) { statusError = true; status = "Couldn't read the file: ${e.message}"; return@launch }
            game.console?.wrongSaveSize(bytes.size)?.let { statusError = true; status = it; return@launch }
            upload(bytes, "normal")
        }
    }
    fun copyToFile(v: SaveVersion) {
        val ext = if (game.console?.hasFolderSaves == true) "zip" else "sav"
        val f = FilePick.save("Save a copy to a file…", "${game.name} v${v.version}.$ext") ?: return
        act { saveCopy(game, v.version, f) }
    }

    // saves.html's one-game view: the rules, then the box (.sg-box) with the whole-game tools (.sv-top) and the
    // versions (.sv) with their own actions (.sv-acts). A click on a version still opens its details.
    PocketDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(min = 560.dp), maxWidth = 960.dp,
        title = { Text("Saves: ${game.name}") },
        text = {
            Column {
                if (removed) PocketNote("This game is no longer in your library.", Modifier.padding(bottom = 8.dp))
                vm.saveRules?.let { (recent, days) ->
                    Text("Jellyfin keeps the last $recent versions plus one a day for $days days. The one in use and pinned ones are never removed.",
                        style = MaterialTheme.typography.bodySmall, color = Pocket.PMuted)
                }
                val h = history
                PocketBox(Modifier.fillMaxWidth().padding(top = 12.dp), PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                        if (!removed) PocketButton(onClick = { replaceFromFile() }, enabled = !busy && history != null) { Text("Replace with a file…") }
                        if (!removed && h != null && h.current != 0) PocketButton(onClick = { fresh = true }, enabled = !busy) { Text("Start fresh") }
                        if (h != null && (h.versions.isNotEmpty() || snaps.isNotEmpty())) PocketButton(onClick = { moving = true }, enabled = !busy) { Text("Move to another game") }
                        if (removed && h != null && (h.versions.isNotEmpty() || snaps.isNotEmpty())) PocketButton(onClick = { deleteAll = 1 }, enabled = !busy) { Text("Delete all saves for this game") }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Pocket.PLine))
                    status?.let { PocketNote(it, Modifier.padding(top = 12.dp), error = statusError) }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                    when {
                        h == null && loadFailed -> PocketButton(onClick = { status = null; reload++ }, modifier = Modifier.padding(vertical = 12.dp)) { Text("Retry") }
                        h == null -> Text("Loading…", color = Pocket.PMuted, modifier = Modifier.padding(vertical = 24.dp))
                        h.versions.isEmpty() -> Text(if (removed && snaps.isNotEmpty()) "No save versions for this game, only snapshots." else "No saves on Jellyfin yet.",
                            color = Pocket.PMuted, modifier = Modifier.padding(vertical = 24.dp))
                        else -> LazyColumn(Modifier.heightIn(max = 420.dp)) {
                            items(h.versions, key = { it.version }) { v ->
                                PocketSaveRow(
                                    heading = {
                                        Text("Version ${v.version}")
                                        if (v.isCurrent) PocketBadge("In use")
                                        if (v.pinned) PocketBadge("Pinned", pin = true)
                                    },
                                    meta = listOfNotNull(v.createdMillis?.let { formatTime(it) }, fromLabel(v), sizeLabel(v.size),
                                        if (v.origin == "browser") "Was a browser save (merged in)" else null,
                                        if (v.afterSnapshot) "Saved after loading a snapshot" else null,
                                        if (v.cheats) "Made with cheats on" else null).joinToString(" · "),
                                    label = v.label, current = v.isCurrent,
                                    modifier = Modifier.clickable(enabled = !busy) { selected = v },
                                ) {
                                    if (!v.isCurrent && !removed) PocketMini({ confirmUse = v }, enabled = !busy) { Text("Use this save") }
                                    PocketMini({ copyToFile(v) }, enabled = !busy) { Text("Save a copy to a file…") }
                                    PocketMini({ act { pin(game, v) } }, enabled = !busy, pressed = v.pinned) { Text(if (v.pinned) "Unpin" else "Pin") }
                                    PocketMini({ naming = v }, enabled = !busy) { Text("Name") }
                                    if (!v.isCurrent && !v.pinned) PocketMini({ deleting = v }, enabled = !busy, danger = true) { Text("Delete") }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { PocketButton(onClick = onDismiss) { Text("Close") } },
    )

    selected?.let { v ->
        PocketDialog(
            onDismissRequest = { selected = null },
            title = { Text("Version ${v.version}") },
            text = { Text((v.label?.let { "“$it”\n" } ?: "") + describeVersion(v) + "\n" + sizeLabel(v.size) +
                (if (v.isCurrent) "\nThis is the save in use." else "") + (if (v.pinned) "\nPinned: never cleared out." else "")) },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!v.isCurrent && !removed) PocketButton(onClick = { selected = null; confirmUse = v }) { Text("Use this save") }
                    PocketButton(onClick = { selected = null; act { pin(game, v) } }) { Text(if (v.pinned) "Unpin" else "Pin") }
                    PocketButton(onClick = { selected = null; naming = v }) { Text("Name") }
                    PocketButton(onClick = { selected = null; copyToFile(v) }) { Text("Save a copy to a file…") }
                    if (!v.isCurrent && !v.pinned) PocketButton(onClick = { selected = null; deleting = v }, danger = true) { Text("Delete") }
                    PocketButton(onClick = { selected = null }) { Text("Cancel") }
                }
            },
        )
    }

    confirmUse?.let { v ->
        PocketDialog(
            onDismissRequest = { confirmUse = null },
            title = { Text("Use this save?") },
            text = { Text("Version ${v.version} becomes the save in use. The save you were using stays in the list." +
                if (Storage.isDownloaded(game)) " It's then put on this PC; your PC save is backed up first." else "") },
            confirmButton = { PocketButton(onClick = {
                confirmUse = null; busy = true
                scope.launch { tools?.let { after(it.use(game, v.version, history?.current ?: 0)) } }
            }) { Text("Use this save") } },
            dismissButton = { PocketButton(onClick = { confirmUse = null }) { Text("Cancel") } },
        )
    }

    naming?.let { v ->
        val max = vm.maxLabelLength
        var text by remember(v.version) { mutableStateOf((v.label ?: "").take(max)) }
        PocketDialog(
            onDismissRequest = { naming = null },
            title = { Text("Name this save") },
            text = {
                Column {
                    Text("Version ${v.version}" + (v.createdMillis?.let { ", " + formatTime(it) } ?: "") + ". Leave it empty to remove the name.")
                    PocketTextField(text, { text = it.filterNot { c -> c.isISOControl() }.take(max) }, label = { Text("Name") }, singleLine = true,
                        supportingText = { Text("${text.length} / $max") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            },
            confirmButton = { PocketButton(onClick = { naming = null; act { name(game, v, text, max) } }) { Text("Save name") } },
            dismissButton = { PocketButton(onClick = { naming = null }) { Text("Cancel") } },
        )
    }

    deleting?.let { v ->
        PocketDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete this save?") },
            text = { Text("Version ${v.version} from ${v.createdMillis?.let { formatTime(it) } ?: "an unknown time"}" +
                (v.label?.let { " (“$it”)" } ?: "") + " will be deleted for good. This can't be undone.\n\nSave a copy to a file first if you might want it later.") },
            confirmButton = { PocketButton(onClick = { deleting = null; act { deleteVersion(game, v) } }, danger = true) { Text("Delete for good") } },
            dismissButton = { PocketButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }

    replaceConflict?.let { (bytes, server) ->
        PocketDialog(
            onDismissRequest = { replaceConflict = null },
            title = { Text("The save changed") },
            text = { Text("Another device saved this game since the list was loaded" + (server?.let { " (${describeVersion(it)})" } ?: "") +
                ". Which should be in use? Both are kept in the list either way.") },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PocketButton(onClick = { replaceConflict = null; upload(bytes, "force") }) { Text("Use my file") }
                    PocketButton(onClick = { replaceConflict = null; upload(bytes, "history") }) { Text("Keep Jellyfin's") }
                    PocketButton(onClick = { replaceConflict = null; reload++ }) { Text("Cancel") }
                }
            },
        )
    }

    if (moving) {
        val from = game.console ?: picked
        val targets = remember(vm.games, from) { tools?.moveTargets(game, vm.games, picked).orEmpty() }
        PocketDialog(
            onDismissRequest = { moving = false },
            title = { Text("Move to another game") },
            text = {
                Column {
                    if (game.console == null) {
                        // The browser's saves.html words: the console first, then the game (the server checks the save fits it).
                        Text("Which console is this save for?", fontWeight = FontWeight.SemiBold)
                        Text("Shoalcade didn't record it for this game. Pick the console the game was on, then the game to move it to.",
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 6.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(bottom = 10.dp)) {
                            Console.entries.forEach { c -> PocketMini({ picked = c }, pressed = picked == c) { Text(c.tag) } }
                        }
                    }
                    when {
                        from == null -> {}
                        targets.isEmpty() -> Text("There are no other ${SaveTools.formatNames(from)} games in your library to move this save to.")
                        else -> {
                            Text(if (history?.versions.isNullOrEmpty()) "Move this game's snapshots to" else "Move all of this save's versions to",
                                fontWeight = FontWeight.SemiBold)
                            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                                items(targets, key = { it.id }) { g ->
                                    Text(if (g.console == from) g.name else "${g.name} (${g.console?.tag})",
                                        Modifier.fillMaxWidth().clickable { moving = false; moveTo = g }.padding(vertical = 10.dp))
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { PocketButton(onClick = { moving = false }) { Text("Cancel") } },
        )
    }

    moveTo?.let { target ->
        val n = history?.versions?.size ?: 0
        PocketDialog(
            onDismissRequest = { moveTo = null },
            title = { Text(if (n == 0) "Move these snapshots?" else "Move this save?") },
            text = { Text(if (n == 0) "Its snapshots move to “${target.name}”. That game's save isn't changed.\n\n" +
                "A snapshot slot “${target.name}” already uses is never replaced: that snapshot stays here."
                else "All $n ${if (n == 1) "version" else "versions"} move to “${target.name}”. Nothing is deleted, but this game's list will be empty afterwards.\n\n" +
                    "If “${target.name}” already has a save in use, it stays in use; the moved versions go into its list, so you can pick one there with “Use this save”. " +
                    "Otherwise the save in use here becomes the save in use for “${target.name}”.\n\n" +
                    "Its snapshots move too. A snapshot slot “${target.name}” already uses is never replaced: that snapshot stays here.\n\n" +
                    "The saves on this PC aren't changed.") },
            confirmButton = { PocketButton(onClick = { moveTo = null; act { move(game, target, n > 0, picked) } }) { Text(if (n == 0) "Move snapshots" else "Move save") } },
            dismissButton = { PocketButton(onClick = { moveTo = null }) { Text("Cancel") } },
        )
    }

    if (fresh) {
        val cur = history?.versions?.firstOrNull { it.isCurrent }
        PocketDialog(
            onDismissRequest = { fresh = false },
            title = { Text("Start fresh?") },
            text = { Text("Next time you play, the game starts from the beginning.\n\n" +
                "Nothing is deleted: the save in use now (version ${cur?.version}, ${cur?.createdMillis?.let { formatTime(it) } ?: ""}) stays in the list, " +
                "so you can pick it again any time with “Use this save”.\n\nThis save is shared with the phone app and the browser. " +
                "The save on this PC is backed up first, then taken off.") },
            confirmButton = { PocketButton(onClick = { fresh = false; if (cur != null) act { startFresh(game, cur) } }, danger = true) { Text("Start fresh") } },
            dismissButton = { PocketButton(onClick = { fresh = false }) { Text("Cancel") } },
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
        if (deleteAll == 1) PocketDialog(
            onDismissRequest = { deleteAll = 0 },
            title = { Text("Delete all saves for “$name”?") },
            text = { Text("$what will be deleted, including pinned ones and the one in use. You can't undo this here.\n\n" +
                "The server keeps a hidden copy for 7 days, so your server admin can bring them back if this was a mistake.\n\n" +
                "If the game is only missing because a drive or folder is offline, it may come back: its saves would then be gone.\n\n" +
                "Download the save first if you might want it later. Saves on this PC aren't deleted.") },
            confirmButton = { PocketButton(onClick = { deleteAll = 2 }, danger = true) { Text("Continue") } },
            dismissButton = { PocketButton(onClick = { deleteAll = 0 }) { Text("Cancel") } },
        ) else {
            var typed by remember { mutableStateOf("") }
            PocketDialog(
                onDismissRequest = { deleteAll = 0 },
                title = { Text("Type to confirm") },
                text = {
                    Column {
                        Text(if (known) "To delete everything for this game, type its name: “$name”." else "This game's name isn't known. To delete everything, type: delete")
                        PocketTextField(typed, { typed = it.take(200) }, label = { Text(if (known) "Game name" else "Type delete") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    }
                },
                confirmButton = { PocketButton(onClick = {
                    deleteAll = 0
                    act { deleteAll(game, removed, known, typed, vs.size, snaps.size).also { onDismiss() } }
                }, danger = true) { Text("Delete all") } },
                dismissButton = { PocketButton(onClick = { deleteAll = 0 }) { Text("Cancel") } },
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
