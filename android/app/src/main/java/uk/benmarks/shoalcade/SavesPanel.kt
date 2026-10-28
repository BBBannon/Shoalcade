package uk.benmarks.shoalcade

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

internal fun formatTime(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

/** With seconds, for telling apart two saves made in the same minute (newer-save confirmation). */
internal fun formatTimeExact(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(Date(ms))

/** Where a version came from: the browser saves manager's exact words (docs/wording.md). */
private fun sourceLabel(s: String) = when (s) {
    "browser" -> "the browser player"
    "app" -> "the phone app"
    "upload" -> "an uploaded file"
    "restore" -> "an older version"
    "jellyemu" -> "JellyEMU (imported)"
    "moved" -> "another game (moved here)"
    "recovered" -> "a recovered file"
    else -> s.ifEmpty { "unknown" }
}

/** "Copy of version 3" for a "Use this save" copy, else "From the phone app (Example Phone)": same as the browser. */
private fun fromLabel(v: SaveVersion) =
    (if (v.source == "restore" && v.restoredFrom != null) "Copy of version ${v.restoredFrom}" else "From " + sourceLabel(v.source)) +
        (v.device?.let { " ($it)" } ?: "")

private fun sizeLabel(b: Long) = if (b >= 1024 * 1024) "%.1f MB".format(b / 1048576.0) else "${(b + 1023) / 1024} KB"

/** "version 12, 24 Oct 2026 14:02, from the browser player (Example Phone)". */
internal fun describeVersion(v: SaveVersion) = buildString {
    append("version ${v.version}")
    v.createdMillis?.let { append(", ").append(formatTime(it)) }
    append(", ").append(fromLabel(v).replaceFirstChar { it.lowercase() })
    if (v.origin == "browser") append(", was a browser save (merged in)")
    if (v.afterSnapshot) append(", saved after loading a snapshot")
    if (v.cheats) append(", made with cheats on")
}

private const val MAX_FILE = 64L * 1024 * 1024 // biggest save any console allows (PSP / 3DS)

/** The browser saves manager's "In use" badge colour (sc.css .badge = LED red; .badge.pin = ink on paper). */
private val InUseRed = Color(0xFFC8324B)

/** A small square badge like the browser's ("In use", "Pinned"). Read out as [text] alone. */
@Composable
private fun Badge(text: String, bg: Color, fg: Color, marker: String = "") {
    Surface(color = bg, contentColor = fg, shape = RoundedCornerShape(2.dp),
        modifier = Modifier.padding(start = 6.dp).clearAndSetSemantics { contentDescription = text }) {
        Text(marker + text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
    }
}

/**
 * Saves panel (D39): the game's save history on Jellyfin, with "Use this save", save-a-copy, replace-with-a-file, and
 * Pin / Name per version (same call, limits and words as the browser saves manager).
 * Follows the server's rules: "Use this save" and replace keep the old save in history; replace is checked against the
 * version shown (409 → ask). One save list per game, DS too (D91).
 */
@Composable
internal fun SavesPanel(vm: AppViewModel, game: Game, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var history by remember { mutableStateOf<SaveHistory?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusError by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf<SaveVersion?>(null) }
    /** Jellyfin couldn't be reached on the last Pin / Name: they're off until a Retry loads the list again. */
    var offline by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<SaveVersion?>(null) }
    var confirmRestore by remember { mutableStateOf<SaveVersion?>(null) }
    var exportVersion by remember { mutableStateOf<SaveVersion?>(null) }
    var replaceConflict by remember { mutableStateOf<Pair<ByteArray, SaveVersion?>?>(null) }

    LaunchedEffect(reload) {
        history = null
        loadFailed = false
        try {
            history = vm.history(game)
            offline = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loadFailed = true
            statusError = true
            status = "Couldn't load the saves: ${e.message}"
        }
    }

    fun after(r: SyncResult) {
        busy = false
        statusError = r is SyncResult.Failed
        status = r.message
        reload++
    }

    /**
     * Pin / unpin or name a version. On success the list reloads to show what the server now has; on failure nothing
     * changed, so the list stays and the error stays on screen (a reload could hide it behind a load error).
     */
    fun change(v: SaveVersion, pinned: Boolean? = null, label: String? = null, ok: String) {
        busy = true
        vm.updateVersion(game, v.version, pinned, label) { err, unreachable ->
            busy = false
            offline = unreachable
            statusError = err != null
            status = err?.let { "Couldn't change version ${v.version}: $it" } ?: ok
            if (err == null) reload++
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val v = exportVersion ?: return@rememberLauncherForActivityResult
        exportVersion = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            status = try {
                val d = vm.versionBytes(game, v.version)
                withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)!!.use { it.write(d.bytes) } }
                "Saved a copy of version ${v.version}."
            } catch (e: Exception) {
                "Couldn't save the copy: ${e.message}"
            }
        }
    }

    fun upload(bytes: ByteArray, mode: String) {
        busy = true
        vm.replace(game, bytes, history?.current ?: 0, mode, allowBlank = false) { r ->
            if (r is SyncResult.Conflict) { busy = false; replaceConflict = bytes to r.server } else after(r)
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = try {
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)!!.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            if (out.size() > MAX_FILE) throw java.io.IOException("That file is far too big to be a save.")
                        }
                        out.toByteArray()
                    }
                }
            } catch (e: Exception) {
                status = "Couldn't read the file: ${e.message}"
                return@launch
            }
            upload(bytes, "normal")
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Saves: ${game.name}") },
        text = {
            Column {
                vm.saveRules?.let { (recent, days) ->
                    Text("Jellyfin keeps the last $recent versions plus one a day for $days days. The one in use and pinned ones are never removed.",
                        style = MaterialTheme.typography.bodySmall)
                }
                status?.let {
                    Text(it, color = if (statusError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 6.dp))
                }
                if (offline && history != null) {
                    Text("You're offline (Jellyfin can't be reached), so Pin and Name are off. Nothing was changed.",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 4.dp))
                    OutlinedButton(onClick = { status = null; reload++ }, enabled = !busy) { Text("Retry") }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                val h = history
                when {
                    h == null && loadFailed -> OutlinedButton(onClick = { status = null; reload++ }, Modifier.padding(vertical = 8.dp)) { Text("Retry") }
                    h == null -> Text("Loading…", Modifier.padding(vertical = 8.dp))
                    h.versions.isEmpty() -> Text("No saves on Jellyfin yet.", Modifier.padding(vertical = 8.dp))
                    else -> LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(h.versions, key = { it.version }) { v ->
                            val canChange = !busy && !offline
                            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Column(Modifier.fillMaxWidth().clickable(enabled = !busy) { selected = v }.padding(vertical = 2.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("Version ${v.version}", fontWeight = if (v.isCurrent) FontWeight.Bold else FontWeight.Normal)
                                        if (v.isCurrent) Badge("In use", InUseRed, Color.White)
                                        if (v.pinned) Badge("Pinned", MaterialTheme.colorScheme.inverseSurface,
                                            MaterialTheme.colorScheme.inverseOnSurface, marker = "📌 ")
                                    }
                                    // The name is what the person chose, so it stands out (as in the browser).
                                    v.label?.let { Text(it, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }
                                    Text(
                                        listOfNotNull(v.createdMillis?.let { formatTime(it) }, fromLabel(v), sizeLabel(v.size),
                                            if (v.origin == "browser") "Was a browser save (merged in)" else null,
                                            if (v.afterSnapshot) "Saved after loading a snapshot" else null,
                                            if (v.cheats) "Made with cheats on" else null).joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                Row {
                                    TextButton(
                                        enabled = canChange,
                                        onClick = {
                                            change(v, pinned = !v.pinned,
                                                ok = if (v.pinned) "Unpinned version ${v.version}" else "Pinned. Version ${v.version} won't be cleared out.")
                                        },
                                        modifier = Modifier.semantics {
                                            contentDescription = (if (v.pinned) "Unpin version " else "Pin version ") + v.version
                                            stateDescription = if (v.pinned) "Pinned" else "Not pinned"
                                        },
                                    ) { Text(if (v.pinned) "Unpin" else "Pin") }
                                    TextButton(
                                        enabled = canChange,
                                        onClick = { naming = v },
                                        modifier = Modifier.semantics { contentDescription = "Name version ${v.version}" },
                                    ) { Text("Name") }
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && history != null, onClick = { importLauncher.launch(arrayOf("*/*")) }) { Text("Replace with a file…") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )

    selected?.let { v ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text("Version ${v.version}") },
            text = {
                Text((v.label?.let { "“$it”\n" } ?: "") + describeVersion(v) + "\n" + sizeLabel(v.size) +
                    (if (v.isCurrent) "\nThis is the save in use." else "") + (if (v.pinned) "\nPinned: never cleared out." else ""))
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    if (!v.isCurrent) TextButton(onClick = { selected = null; confirmRestore = v }) { Text("Use this save") }
                    TextButton(onClick = {
                        selected = null
                        exportVersion = v
                        val ext = if (game.console?.hasFolderSaves == true) "zip" else "sav"
                        exportLauncher.launch("${game.name} v${v.version}.$ext")
                    }) { Text("Save a copy to a file…") }
                    TextButton(onClick = { selected = null }) { Text("Cancel") }
                }
            },
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
                    Text("Version ${v.version}" + (v.createdMillis?.let { ", " + formatTime(it) } ?: "") +
                        ". Leave it empty to remove the name.")
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it.filterNot { c -> c.isISOControl() }.take(max) },
                        label = { Text("Name") },
                        singleLine = true,
                        supportingText = { Text("${text.length} / $max") },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    naming = null
                    val label = JellyfinClient.cleanLabel(text, max)
                    change(v, label = label, ok = if (label.isEmpty()) "Name removed" else "Named version ${v.version}")
                }) { Text("Save name") }
            },
            dismissButton = { TextButton(onClick = { naming = null }) { Text("Cancel") } },
        )
    }

    confirmRestore?.let { v ->
        AlertDialog(
            onDismissRequest = { confirmRestore = null },
            title = { Text("Use this save?") },
            text = {
                Text("Version ${v.version} becomes the save in use. The save you were using stays in the list." +
                    if (Storage.isDownloaded(game)) " It's then put on this phone; your phone save is backed up first." else "")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestore = null
                    busy = true
                    vm.restore(game, v.version, history?.current ?: 0) { after(it) }
                }) { Text("Use this save") }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = null }) { Text("Cancel") } },
        )
    }

    replaceConflict?.let { (bytes, server) ->
        AlertDialog(
            onDismissRequest = { replaceConflict = null },
            title = { Text("The save changed") },
            text = {
                Text("Another device saved this game since the list was loaded" +
                    (server?.let { " (${describeVersion(it)})" } ?: "") + ". Which should be in use? Both are kept in the list either way.")
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { replaceConflict = null; upload(bytes, "force") }) { Text("Use my file") }
                    TextButton(onClick = { replaceConflict = null; upload(bytes, "history") }) { Text("Keep Jellyfin's") }
                    TextButton(onClick = { replaceConflict = null; reload++ }) { Text("Cancel") }
                }
            },
        )
    }
}

/**
 * B9 "Not in your library" (D135): saves on this phone for games Jellyfin no longer lists, opened from Settings →
 * Saves. Each one can be saved to a file or uploaded to a game that is in the library (checked against that game's
 * save in use; a conflict asks). F9 (D140): downloads and snapshots of such games are listed too: Play (offline, as for
 * a library game, with the snapshot choice), Remove download, Delete snapshot (each after a confirm), and the save's
 * older copies on the phone (put one back, or save it to a file). Nothing here deletes the phone save.
 */
@Composable
internal fun OrphanSavesPanel(vm: AppViewModel, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var list by remember { mutableStateOf<OrphanView?>(null) }
    LaunchedEffect(reload, vm.games, vm.gamesKnown, vm.diskVersion) { list = vm.orphanSaves() }
    var status by remember { mutableStateOf<String?>(null) }
    var statusError by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<OrphanSave?>(null) }
    var picking by remember { mutableStateOf<OrphanSave?>(null) }
    var confirm by remember { mutableStateOf<Pair<OrphanSave, Game>?>(null) }
    var conflict by remember { mutableStateOf<Triple<OrphanSave, Game, SaveVersion?>?>(null) }
    var exporting by remember { mutableStateOf<OrphanSave?>(null) }
    var removing by remember { mutableStateOf<OrphanSave?>(null) }
    var deletingSnapshot by remember { mutableStateOf<OrphanSave?>(null) }
    var launching by remember { mutableStateOf<OrphanSave?>(null) }
    var backupsOf by remember { mutableStateOf<OrphanSave?>(null) }
    var backupList by remember { mutableStateOf<List<Storage.Backup>?>(null) }
    var backupPicked by remember { mutableStateOf<Storage.Backup?>(null) }
    var confirmBackup by remember { mutableStateOf<Storage.Backup?>(null) }
    var exportingBackup by remember { mutableStateOf<Storage.Backup?>(null) }
    val userId = vm.session?.userId

    /** F9: offline, exactly as from the library; the game menu just has no "and Upload" (nowhere on Jellyfin to send it). */
    fun play(o: OrphanSave, snapshot: Boolean) {
        val uid = userId ?: return
        if (GameActivity.running) return
        ctx.startActivity(GameActivity.intent(ctx, o.game, uid, snapshot = snapshot, offList = true))
    }

    LaunchedEffect(backupsOf, reload) { backupList = backupsOf?.let { vm.orphanBackups(it) } }

    fun send(o: OrphanSave, target: Game, mode: String) {
        busy = true
        vm.uploadOrphan(o, target, mode) { r ->
            busy = false
            if (r is SyncResult.Conflict) conflict = Triple(o, target, r.server)
            else { statusError = r is SyncResult.Failed; status = "${target.name}: ${r.message}"; reload++ }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val o = exporting ?: return@rememberLauncherForActivityResult
        exporting = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val bytes = vm.orphanBytes(o) ?: throw java.io.IOException("the save has gone from the phone.")
                withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) } }
                statusError = false; status = "Saved a copy of the ${o.title} save."
            } catch (e: Exception) {
                statusError = true; status = "Couldn't save the copy: ${e.message}"
            }
        }
    }

    val backupExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val b = exportingBackup ?: return@rememberLauncherForActivityResult
        exportingBackup = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)!!.use { it.write(b.file.readBytes()) } }
                statusError = false; status = "Saved a copy of the older copy from ${formatTime(b.time)}."
            } catch (e: Exception) {
                statusError = true; status = "Couldn't save the copy: ${e.message}"
            }
        }
    }

    fun details(o: OrphanSave) = listOfNotNull(o.console?.shortName, o.time?.let { formatTime(it) },
        if (o.hasSave) sizeLabel(o.size) else null,
        when {
            !o.hasSave -> "No save"; o.blank -> "Empty (the game never saved)"
            o.onJellyfin -> "A copy is on Jellyfin too"; else -> "Only on this phone"
        },
        o.download?.let { if (o.playable) "Downloaded (${sizeLabel(it)})" else "Part download (${sizeLabel(it)})" },
        o.snapshotTime?.let { "Snapshot ${formatTime(it)}" })
        .joinToString(" · ")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Not in your library") },
        text = {
            Column {
                Text("Games no longer in your library that still have a save, download or snapshot on this phone. " +
                    "Saves stay on this phone: nothing here deletes them.", style = MaterialTheme.typography.bodySmall)
                status?.let {
                    Text(it, color = if (statusError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 6.dp))
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                val l = list
                when {
                    l == null -> Text("Loading…", Modifier.padding(vertical = 8.dp))
                    l is OrphanView.ListNotLoaded -> Text("Game list not loaded yet. Connect to Jellyfin once so the app knows " +
                        "which games are in your library.", Modifier.padding(vertical = 8.dp))
                    l is OrphanView.Failed -> Text("Couldn't read the saves on this phone: ${l.reason}",
                        color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp))
                    l is OrphanView.Ready && l.saves.isEmpty() ->
                        Text("None. Every save, download and snapshot on this phone is for a game in your library.",
                            Modifier.padding(vertical = 8.dp))
                    l is OrphanView.Ready -> LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(l.saves, key = { it.id }) { o ->
                            Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = !busy) { selected = o }
                                .padding(vertical = 6.dp)) {
                                Text(o.title, fontWeight = FontWeight.SemiBold)
                                Text(details(o), style = MaterialTheme.typography.bodySmall)
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )

    selected?.let { o ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(o.title) },
            text = {
                Text(details(o) + "\nThe game is no longer in your library." +
                    (if (o.hasSave) " This save stays on the phone whatever you pick." else "") +
                    (if (o.snapshotTime != null && o.playable) " Play to load the snapshot." else ""))
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    if (o.playable && userId != null) TextButton(onClick = {
                        selected = null
                        if (hasLaunchSnapshot(ctx, o.game, userId)) launching = o else play(o, snapshot = false)
                    }) { Text("Play") }
                    if (o.hasSave) {
                        TextButton(onClick = {
                            selected = null
                            exporting = o
                            exportLauncher.launch("${o.title}.${if (o.folder) "zip" else "sav"}")
                        }) { Text("Save a copy to a file…") }
                        TextButton(enabled = !o.blank, onClick = { selected = null; picking = o }) { Text("Upload to another game…") }
                    }
                    TextButton(onClick = { selected = null; backupsOf = o }) { Text("Older copies of the save…") }
                    if (o.download != null) TextButton(onClick = { selected = null; removing = o }) { Text("Remove download…") }
                    if (o.snapshotTime != null) TextButton(onClick = { selected = null; deletingSnapshot = o }) { Text("Delete snapshot…") }
                    TextButton(onClick = { selected = null }) { Text("Cancel") }
                }
            },
        )
    }

    picking?.let { o ->
        val targets = remember(o, vm.games) { OrphanSave.targets(o, vm.games) }
        AlertDialog(
            onDismissRequest = { picking = null },
            title = { Text("Upload to which game?") },
            text = {
                if (targets.isEmpty()) Text("No game in your library can take this save.")
                else LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(targets, key = { it.id }) { g ->
                        Text(g.name + (g.console?.let { "  (${it.shortName})" } ?: ""),
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { picking = null; confirm = o to g }
                                .padding(vertical = 12.dp))
                        HorizontalDivider()
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picking = null }) { Text("Cancel") } },
        )
    }

    confirm?.let { (o, g) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Upload to ${g.name}?") },
            text = {
                Text("The ${o.title} save becomes the save in use for ${g.name} on Jellyfin. The save it was using stays in the list." +
                    (if (Storage.isDownloaded(g)) " It's then put on this phone; your phone save for ${g.name} is backed up first." else "") +
                    " The copy here is kept.")
            },
            confirmButton = { TextButton(onClick = { confirm = null; send(o, g, "normal") }) { Text("Upload") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }

    conflict?.let { (o, g, server) ->
        AlertDialog(
            onDismissRequest = { conflict = null },
            title = { Text("The save changed") },
            text = {
                Text("Another device saved ${g.name} just now" + (server?.let { " (${describeVersion(it)})" } ?: "") +
                    ". Which should be in use? Both are kept in the list either way.")
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { conflict = null; send(o, g, "force") }) { Text("Use the phone save") }
                    TextButton(onClick = { conflict = null; send(o, g, "history") }) { Text("Keep Jellyfin's") }
                    TextButton(onClick = { conflict = null }) { Text("Cancel") }
                }
            },
        )
    }

    launching?.let { o ->
        if (userId != null) LaunchChoiceDialog(o.game, userId, onStart = { snapshot -> launching = null; play(o, snapshot) },
            onDismiss = { launching = null })
    }

    removing?.let { o ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${o.title}?") },
            text = {
                Text("This deletes only the game's downloaded copy on this phone" + (o.download?.let { " (${sizeLabel(it)})" } ?: "") +
                    ". It's no longer in your library, so it can't be downloaded again unless it comes back. " +
                    "Its save, older copies and snapshot are kept.")
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    removing = null; busy = true
                    vm.removeOrphanDownload(o) { busy = false; reload++ }
                }) { Text("Remove download") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }

    deletingSnapshot?.let { o ->
        AlertDialog(
            onDismissRequest = { deletingSnapshot = null },
            title = { Text("Delete the snapshot?") },
            text = {
                Text("The snapshot of ${o.title}" + (o.snapshotTime?.let { " from ${formatTime(it)}" } ?: "") +
                    " is deleted from this phone. This can't be undone. The save is kept.")
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    deletingSnapshot = null; busy = true
                    vm.deleteOrphanSnapshot(o) { busy = false; reload++ }
                }) { Text("Delete snapshot") }
            },
            dismissButton = { TextButton(onClick = { deletingSnapshot = null }) { Text("Cancel") } },
        )
    }

    backupsOf?.let { o ->
        val list = backupList
        AlertDialog(
            onDismissRequest = { backupsOf = null },
            title = { Text("Older copies: ${o.title}") },
            text = {
                Column {
                    Text("Copies of the save this phone kept before it was replaced (the last 10).",
                        style = MaterialTheme.typography.bodySmall)
                    when {
                        list == null -> Text("Loading…", Modifier.padding(vertical = 8.dp))
                        list.isEmpty() -> Text("No older copies on this phone.", Modifier.padding(vertical = 8.dp))
                        else -> LazyColumn(Modifier.heightIn(max = 360.dp)) {
                            items(list, key = { it.file.name }) { b ->
                                Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = !busy) { backupPicked = b }
                                    .padding(vertical = 6.dp)) {
                                    Text(formatTime(b.time), fontWeight = FontWeight.SemiBold)
                                    Text((if (b.fromJellyfin) "Jellyfin's save, kept when the phone's replaced it" else "From this phone") +
                                        " · " + sizeLabel(b.file.length()), style = MaterialTheme.typography.bodySmall)
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { backupsOf = null }) { Text("Close") } },
        )
    }

    backupPicked?.let { b ->
        val o = backupsOf
        AlertDialog(
            onDismissRequest = { backupPicked = null },
            title = { Text("Copy from ${formatTime(b.time)}") },
            text = { Text((if (b.fromJellyfin) "Jellyfin's save, kept when the phone's replaced it" else "From this phone") +
                " · " + sizeLabel(b.file.length())) },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    if (o != null) TextButton(onClick = { backupPicked = null; confirmBackup = b }) { Text("Put back on this phone") }
                    TextButton(onClick = {
                        backupPicked = null
                        exportingBackup = b
                        val day = java.text.SimpleDateFormat("yyyy-MM-dd HH-mm", java.util.Locale.UK).format(Date(b.time))
                        backupExportLauncher.launch("${o?.title ?: "Save"} $day.${if (o?.folder == true) "zip" else "sav"}")
                    }) { Text("Save a copy to a file…") }
                    TextButton(onClick = { backupPicked = null }) { Text("Cancel") }
                }
            },
        )
    }

    confirmBackup?.let { b ->
        val o = backupsOf
        AlertDialog(
            onDismissRequest = { confirmBackup = null },
            title = { Text("Put this copy back?") },
            text = {
                Text("The copy from ${formatTime(b.time)} becomes the save on this phone. " +
                    "The save there now is kept as an older copy first.")
            },
            confirmButton = {
                TextButton(enabled = !busy && o != null, onClick = {
                    confirmBackup = null
                    if (o != null) {
                        busy = true
                        vm.restoreOrphanBackup(o, b) { err ->
                            busy = false
                            statusError = err != null
                            status = err?.let { "Couldn't put the copy back: $it" }
                                ?: "The copy from ${formatTime(b.time)} is now the ${o.title} save on this phone."
                            backupsOf = null
                            reload++
                        }
                    }
                }) { Text("Put back") }
            },
            dismissButton = { TextButton(onClick = { confirmBackup = null }) { Text("Cancel") } },
        )
    }
}
