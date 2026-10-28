package uk.benmarks.shoalcade

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) { HapticsGate {
                Surface(Modifier.fillMaxSize()) {
                    val vm: AppViewModel = viewModel()
                    var settings by rememberSaveable { mutableStateOf(false) } // D132: Settings screen open
                    LaunchedEffect(vm.session == null) { if (vm.session == null) settings = false } // logged out
                    when {
                        vm.session == null -> LoginScreen(vm)
                        settings -> SettingsScreen(vm, onClose = { settings = false })
                        else -> LibraryScreen(vm, onSettings = { settings = true })
                    }
                    // A Push all / Pull all can finish while either screen is showing.
                    if (vm.session != null) vm.batchReport?.let { SyncAllDialog(vm, it) }
                }
            } }
        }
    }
}

@Composable
private fun LoginScreen(vm: AppViewModel) {
    var server by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var usePassword by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }
    if (about) AboutDialog { about = false }
    val code = vm.quickConnectCode

    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Shoalcade", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text("Log in with your Jellyfin account", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(server, { server = it }, label = { Text("Server (e.g. jellyfin.example.com)") },
            singleLine = true, enabled = code == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth())

        if (code != null) {
            Spacer(Modifier.height(24.dp))
            Text("Your Quick Connect code", style = MaterialTheme.typography.titleMedium)
            Text(code, style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold,
                letterSpacing = MaterialTheme.typography.displayMedium.fontSize * 0.15f)
            Text("On a device already signed in to Jellyfin: open your profile → Quick Connect → enter this code.",
                style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("Waiting for approval…", modifier = Modifier.padding(start = 12.dp))
            }
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = { vm.cancelQuickConnect() }, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            return@Column
        }

        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        if (!usePassword) {
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { error = null; vm.startQuickConnect(server) { error = it } },
                enabled = !vm.loading && server.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Log in with Quick Connect") }
            TextButton(onClick = { error = null; usePassword = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Use username and password instead")
            }
            Text("HTTPS only.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            TextButton(onClick = { about = true }) { Text("About and licences") }
            return@Column
        }

        OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Ascii,
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
            ),
            modifier = Modifier.fillMaxWidth())
        OutlinedTextField(pw, { pw = it }, label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { error = null; vm.login(server, user, pw) { error = it } },
            enabled = !vm.loading && server.isNotBlank() && user.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (vm.loading) "Logging in…" else "Log in") }
        TextButton(onClick = { error = null; usePassword = false }, modifier = Modifier.fillMaxWidth()) {
            Text("Use Quick Connect instead")
        }
        Text("HTTPS only.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
    }
}

/**
 * F3: the settings cog doubles as the server light: green when connected, red with a "!" badge when not (so it's not
 * colour alone), and its spoken name says which. Tappable either way.
 */
@Composable
private fun SettingsCog(connected: Boolean, onClick: () -> Unit) {
    val colour = if (connected) Color(0xFF66BB6A) else Color(0xFFEF5350)
    val label = ServerStatus.label(connected)
    IconButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = label }) {
        BadgedBox(modifier = Modifier.clearAndSetSemantics { }, badge = { // the button's name says it all
            if (!connected) Badge(containerColor = colour, contentColor = Color.Black) { Text("!") }
        }) { Icon(Icons.Default.Settings, contentDescription = null, tint = colour) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreen(vm: AppViewModel, onSettings: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var filter by remember { mutableStateOf<Console?>(null) }
    var lastPlay by remember { mutableStateOf(0L) } // ignore double-taps on Play
    var getSaveGame by remember { mutableStateOf<Game?>(null) }
    var savesPanelGame by remember { mutableStateOf<Game?>(null) }
    var linkGame by remember { mutableStateOf<Game?>(null) }
    var launchGame by remember { mutableStateOf<Game?>(null) } // D90: choose save or snapshot

    // Re-check file access when returning from the Settings screen.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) { vm.diskVersionBump(); vm.checkConnection() } // F3
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(vm.message) {
        vm.message?.let { snackbar.showSnackbar(it); vm.message = null }
    }
    LaunchedEffect(Downloads.message) {
        Downloads.message?.let { snackbar.showSnackbar(it); Downloads.message = null }
    }
    // Android 13+: ask once so the download progress notification can show. Downloads work either way.
    val notifPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }
    fun download(g: Game) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        vm.download(g)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Shoalcade")
                        Text(
                            (vm.session?.userName ?: "") + if (vm.offline) " · offline" else "",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { vm.refresh() }) { Icon(Icons.Default.Refresh, "Refresh") }
                    // D132: Push all / Pull all, About and Log out moved into Settings.
                    SettingsCog(connected = vm.session != null && ServerStatus.connected.collectAsState().value, onSettings)
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            vm.serverProblem?.let { p ->
                // D41 version check: say clearly what to update. Games already on the phone still play.
                Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                    Text("⚠️ $p", color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(12.dp))
                }
            }

            SyncAllProgress(vm) // a running Push all / Pull all stays in sight and stoppable (buttons in Settings, D132)

            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(start = 12.dp)) {
                    FilterChip(filter == null, { filter = null }, { Text("All") }, Modifier.padding(end = 6.dp))
                    Console.entries.filter { c -> vm.games.any { it.console == c } }.forEach { c ->
                        FilterChip(filter == c, { filter = c }, { Text(c.shortName) }, Modifier.padding(end = 6.dp))
                    }
                }
                SortButton(vm.sort) { vm.chooseSort(it) }
            }

            // diskVersion: bumped on return from a game, so a game just played moves up under "Last played".
            val shown = remember(vm.games, filter, vm.sort, vm.diskVersion) {
                vm.sorted(vm.games.filter { filter == null || it.console == filter })
            }
            if (shown.isEmpty() && !vm.loading) {
                Text(
                    if (vm.offline) "Offline and no saved game list yet." else "No games found on this server.",
                    Modifier.padding(24.dp),
                )
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(shown, key = { it.id }) { g ->
                    GameRow(g, vm, onDownload = { download(g) },
                        onPlay = {
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (vm.batch != null) vm.message = "Wait for ${vm.batch!!.way.word} to finish first."
                            else if (now - lastPlay > 1500) {
                                lastPlay = now
                                // D90: a game with a snapshot asks where to start; otherwise straight from the save.
                                if (hasLaunchSnapshot(ctx, g, vm.session!!.userId)) launchGame = g
                                else ctx.startActivity(GameActivity.intent(ctx, g, vm.session!!.userId))
                            }
                        },
                        onUpload = { vm.uploadSave(g) },
                        onGetSave = { getSaveGame = g },
                        onSavesPanel = { savesPanelGame = g },
                        onLink = { linkGame = g })
                }
            }
        }
    }

    getSaveGame?.let { game -> GetSaveDialog(vm, game) { getSaveGame = null } }
    savesPanelGame?.let { game -> SavesPanel(vm, game) { savesPanelGame = null } }
    vm.conflictPrompt?.let { (game, server) -> ConflictDialog(vm, game, server) }
    linkGame?.let { game -> LinkDialog(game, vm.session!!.userId) { linkGame = null } }
    launchGame?.let { game ->
        LaunchChoiceDialog(game, vm.session!!.userId, onStart = { snapshot ->
            launchGame = null
            ctx.startActivity(GameActivity.intent(ctx, game, vm.session!!.userId, snapshot = snapshot))
        }, onDismiss = { launchGame = null })
    }
}

/** "Sort: Last played ▾" next to the console chips; the choice is remembered (per user) by the view model. */
@Composable
private fun SortButton(sort: GameSort, onChoose: (GameSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = { open = true },
            contentPadding = PaddingValues(horizontal = 8.dp),
            modifier = Modifier.semantics { contentDescription = "Sort games by ${sort.label}. Tap to change." },
        ) { Text("Sort: ${sort.label} ▾", maxLines = 1) }
        DropdownMenu(open, { open = false }) {
            GameSort.entries.forEach { s ->
                DropdownMenuItem(
                    text = { Text(s.label) },
                    onClick = { open = false; onChoose(s) },
                    leadingIcon = { if (s == sort) Icon(Icons.Default.Check, null) else Spacer(Modifier.size(24.dp)) },
                    modifier = Modifier.semantics { selected = s == sort },
                )
            }
        }
    }
}

@Composable
private fun GameRow(
    g: Game, vm: AppViewModel, onDownload: () -> Unit, onPlay: () -> Unit,
    onUpload: () -> Unit, onGetSave: () -> Unit, onSavesPanel: () -> Unit, onLink: () -> Unit,
) {
    val downloaded = remember(g.id, vm.diskVersion) { Storage.isDownloaded(g) }
    val prog = vm.progress[g.id]
    val conflict = g.id in vm.conflicts
    var menu by remember { mutableStateOf(false) }

    ListItem(
        leadingContent = { BoxArtImage(g, vm.artVersion) },
        headlineContent = { Text(g.name) },
        supportingContent = {
            Column {
                Text(
                    listOfNotNull(
                        g.console?.shortName ?: "Unknown console",
                        if (downloaded) "On phone" else null,
                        if (g.console == Console.N3DS) "3DS support coming later" else null,
                    ).joinToString(" · ")
                )
                if (conflict) {
                    TextButton(onClick = { vm.askConflict(g) }, contentPadding = PaddingValues(0.dp)) {
                        Text("⚠️ Phone and Jellyfin saves differ: choose", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    // Tap the progress ring to cancel.
                    prog != null -> IconButton(onClick = { vm.cancelDownload(g) }) {
                        CircularProgressIndicator(progress = { prog }, modifier = Modifier.size(32.dp))
                        Icon(Icons.Default.Close, "Cancel download", Modifier.size(16.dp))
                    }
                    downloaded -> Button(onClick = onPlay) { Text("Play") }
                    else -> OutlinedButton(
                        onClick = onDownload,
                        enabled = !vm.offline && vm.serverProblem == null && g.canDownload && g.console?.core != null,
                    ) { Text("Download") }
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Saves on Jellyfin…") }, { menu = false; onSavesPanel() })
                        if (downloaded) {
                            if (LinkRules.supports(g.console)) {
                                DropdownMenuItem({ Text("Link play (same Wi-Fi)") }, { menu = false; onLink() })
                            }
                            DropdownMenuItem({ Text("Upload save to Jellyfin") }, { menu = false; onUpload() })
                            DropdownMenuItem({ Text("Get save from Jellyfin") }, { menu = false; onGetSave() })
                            DropdownMenuItem({ Text("Remove from phone (keeps save)") }, { menu = false; vm.deleteDownload(g) })
                        }
                    }
                }
            }
        },
    )
    HorizontalDivider()
}

/** Shows phone vs Jellyfin save before the phone save is replaced. Confirm is disabled until the check finishes. */
@Composable
private fun GetSaveDialog(vm: AppViewModel, game: Game, onDismiss: () -> Unit) {
    val phone = remember { vm.saves.localSaveTime(game) }
    var checking by remember { mutableStateOf(true) }
    var serverText by remember { mutableStateOf("Checking…") }
    var hasServer by remember { mutableStateOf(false) }
    var newer by remember { mutableStateOf<String?>(null) } // L3: the newer one is marked, as in SavePlan.dateLines

    LaunchedEffect(game.id) {
        try {
            val server = vm.saves.core()?.check(game)?.first
            hasServer = server != null
            serverText = server?.let { describeVersion(it) } ?: "none"
            newer = SavePlan.newer(phone, server?.createdMillis)
        } catch (e: Exception) {
            serverText = "couldn't check (${e.message})"
        }
        checking = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Get save from Jellyfin?") },
        text = {
            Column {
                Text("Phone save: ${phone?.let { formatTime(it) } ?: "none"}" + (if (newer == "phone") " (newer)" else ""))
                Text("Jellyfin save: $serverText" + (if (newer == "server") " (newer)" else ""))
                Spacer(Modifier.height(8.dp))
                Text("This replaces the phone save. The phone copy is backed up first, and if Jellyfin hasn't seen it, " +
                    "it's also kept in Jellyfin's history.")
            }
        },
        confirmButton = {
            TextButton(enabled = !checking && hasServer, onClick = { vm.downloadSave(game); onDismiss() }) {
                Text(if (checking) "Checking…" else "Get save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** D35: a conflict is never settled silently. Either way the other copy is kept (Jellyfin history + phone backups). */
@Composable
private fun ConflictDialog(vm: AppViewModel, game: Game, server: SaveVersion?) {
    val phone = remember(game.id) { vm.saves.localSaveTime(game) }
    AlertDialog(
        onDismissRequest = { vm.conflictPrompt = null },
        title = { Text("Which save should be in use?") },
        text = {
            Column {
                Text("${game.name} has a different save on this phone and on Jellyfin (maybe from the browser or another phone).")
                Spacer(Modifier.height(8.dp))
                Text("Phone: ${phone?.let { formatTime(it) } ?: "none"}", fontWeight = FontWeight.Bold)
                Text("Jellyfin: ${server?.let { describeVersion(it) } ?: "no save in use"}", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("Nothing is lost either way: the other save is kept in Jellyfin's history and in the phone's backups.")
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = { vm.resolveConflict(game, keepPhone = true) }) { Text("Keep the phone's") }
                TextButton(enabled = server != null, onClick = { vm.resolveConflict(game, keepPhone = false) }) { Text("Keep Jellyfin's") }
                TextButton(onClick = { vm.conflictPrompt = null }) { Text("Decide later") }
            }
        },
    )
}
