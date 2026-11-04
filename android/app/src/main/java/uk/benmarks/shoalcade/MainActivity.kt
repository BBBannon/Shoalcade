package uk.benmarks.shoalcade

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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
        Text("Shoalcade", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() })
        Text("Log in with your Jellyfin account", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(server, { server = it }, label = { Text("Server (e.g. jellyfin.example.com)") },
            singleLine = true, enabled = code == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth())

        if (code != null) {
            Spacer(Modifier.height(24.dp))
            Text("Your Quick Connect code", style = MaterialTheme.typography.titleMedium)
            // TalkBack reads the code digit by digit (as the link PINs), not as one big number.
            Text(code, style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold,
                letterSpacing = MaterialTheme.typography.displayMedium.fontSize * 0.15f,
                modifier = Modifier.semantics { contentDescription = code.toList().joinToString(" ") })
            Text("On a device already signed in to Jellyfin: open your profile → Quick Connect → enter this code.",
                style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            Waiting("Waiting for approval…")
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = { vm.cancelQuickConnect() }, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            return@Column
        }

        LoginError(error)
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
            HttpsOnly()
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
        LoginError(error)
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { error = null; vm.login(server, user, pw) { error = it } },
            enabled = !vm.loading && server.isNotBlank() && user.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (vm.loading) "Logging in…" else "Log in") }
        TextButton(onClick = { error = null; usePassword = false }, modifier = Modifier.fillMaxWidth()) {
            Text("Use Quick Connect instead")
        }
        HttpsOnly()
    }
}

/** Live region: TalkBack reads a login error out as soon as it appears (focus stays on the button). */
@Composable
private fun LoginError(error: String?) {
    error?.let { LiveText(it, MaterialTheme.colorScheme.error, Modifier.padding(top = 8.dp)) }
}

@Composable
private fun HttpsOnly() = Text("HTTPS only.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))

/** Android 13+: the download and "a save needs your choice" notices need asking for. Downloads work either way. */
internal fun needsNotificationPermission(ctx: Context) = android.os.Build.VERSION.SDK_INT >= 33 &&
    ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED

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
    var filter by remember { mutableStateOf<ConsoleFilter>(ConsoleFilter.All) }
    var search by rememberSaveable { mutableStateOf("") } // not kept between launches, only across rotation
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
    // D151: 3DS games download only after the 3DS check (or "Unlock anyway").
    var locked3ds by remember { mutableStateOf(false) }
    fun download(g: Game) {
        if (g.console == Console.N3DS && !N3dsCheck.unlocked(ctx)) { locked3ds = true; return }
        if (needsNotificationPermission(ctx)) notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        vm.download(g)
    }
    /** Play (the row's button and the Continue card, D248): one path, so the launch choice and save checks always run. */
    fun play(g: Game) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (vm.batch != null) vm.message = "Wait for ${vm.batch!!.way.word} to finish first."
        else if (now - lastPlay > 1500) {
            lastPlay = now
            // D90: a game with a snapshot asks where to start; otherwise straight from the save.
            if (hasLaunchSnapshot(ctx, g, vm.session!!.userId)) launchGame = g
            else ctx.startActivity(GameActivity.intent(ctx, g, vm.session!!.userId))
        }
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
            AppUpdateBanner(vm)

            SyncAllProgress(vm) // a running Push all / Pull all stays in sight and stoppable (buttons in Settings, D132)

            // Games downloaded to this phone (same truth as the "On phone" badge); recomputed when files change.
            val onPhone = remember(vm.games, vm.diskVersion) { vm.games.filter { Storage.isDownloaded(it) }.mapTo(HashSet()) { it.id } }
            // The last download removed: the toggle goes off rather than coming back on with the next download (as Favourites).
            LaunchedEffect(onPhone) { if (vm.onPhoneOnly && vm.games.isNotEmpty() && onPhone.isEmpty()) vm.chooseOnPhoneOnly(false) }

            if (vm.games.isNotEmpty()) SearchBox(search) { search = it }
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChips(vm, filter, onPhone, Modifier.weight(1f)) { filter = it }
                SortButton(vm.sort) { vm.chooseSort(it) }
            }

            // diskVersion: bumped on return from a game, so a game just played moves up under "Last played".
            val lf = LibraryFilter(filter, vm.favouritesOnly, vm.onPhoneOnly, search)
            val shown = remember(vm.games, lf, onPhone, vm.sort, vm.diskVersion) { vm.sorted(lf.shown(vm.games, onPhone)) }
            if (shown.isEmpty() && !vm.loading) {
                Text(
                    if (vm.games.isNotEmpty()) lf.emptyMessage(vm.games, onPhone)
                    else if (vm.offline) "Offline and no saved game list yet." else "No games found on this server.",
                    Modifier.padding(24.dp),
                )
            }
            // Back to the top when the filter, search, sort or first game changes (LibraryFilter.topKey).
            val listState = rememberLazyListState()
            val topKey = lf.topKey(vm.sort, shown)
            var lastTopKey by rememberSaveable { mutableStateOf(topKey) }
            LaunchedEffect(topKey) { if (topKey != lastTopKey) { lastTopKey = topKey; listState.scrollToItem(0) } }
            // D248: the Continue card (Settings → Show Continue); re-read on return from Settings or a game.
            val showContinue = remember(vm.diskVersion) { ContinueGame.on(GameSound.store(ctx)) }
            val continueGame = remember(vm.games, onPhone, search, showContinue, vm.diskVersion) {
                if (showContinue) ContinueGame.pick(vm.games, onPhone, search) { vm.lastPlayed(it) } else null
            }
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                continueGame?.let { g -> item(key = "continue") { ContinueCard(g, vm.artVersion) { play(g) } } }
                items(shown, key = { it.id }) { g ->
                    GameRow(g, vm, onDownload = { download(g) },
                        onPlay = { play(g) },
                        onUpload = { vm.uploadSave(g) },
                        onGetSave = { getSaveGame = g },
                        onSavesPanel = { savesPanelGame = g },
                        onLink = { linkGame = g })
                }
            }
        }
    }

    if (locked3ds) AlertDialog(
        onDismissRequest = { locked3ds = false },
        title = { DialogTitle(N3dsCheck.LOCKED_TITLE) },
        text = { Text(N3dsCheck.LOCKED_TEXT) },
        confirmButton = { TextButton(onClick = {
            locked3ds = false; ctx.startActivity(android.content.Intent(ctx, N3dsCheckActivity::class.java))
        }) { Text(N3dsCheck.OPEN) } },
        dismissButton = { TextButton(onClick = { locked3ds = false }) { Text("Not now") } },
    )
    getSaveGame?.let { game -> GetSaveDialog(vm, game) { getSaveGame = null } }
    savesPanelGame?.let { game -> SavesPanel(vm, game) { savesPanelGame = null } }
    vm.conflictPrompt?.let { (game, server) -> ConflictDialog(vm, game, server) }
    linkGame?.let { game -> LinkDialog(game, vm.session!!.userId, relay = vm.linkRelay()) { linkGame = null } }
    launchGame?.let { game ->
        LaunchChoiceDialog(game, vm.session!!.userId, onStart = { snapshot ->
            launchGame = null
            ctx.startActivity(GameActivity.intent(ctx, game, vm.session!!.userId, snapshot = snapshot))
        }, onDismiss = { launchGame = null }, onDelete = { launchGame = null; vm.deleteSnapshot(game) })
    }
}

/** A newer app inside the user's own plugin: the browser's Settings → Phone app page downloads it. */
@Composable
private fun AppUpdateBanner(vm: AppViewModel) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var updateHidden by rememberSaveable { mutableStateOf<String?>(null) }
    val v = vm.appUpdate?.takeIf { it != updateHidden } ?: return
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Shoalcade $v is ready on your Jellyfin.", Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSecondaryContainer)
            TextButton(onClick = {
                vm.session?.serverUrl?.let { base ->
                    runCatching {
                        ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(base.trimEnd('/') + "/shoalcade/settings#app-s")))
                    }.onFailure { vm.message = "No web browser to open the download page." }
                }
            }) { Text("Get it") }
            TextButton(onClick = { updateHidden = v }) { Text("Later") }
        }
    }
}

/** Starts unfocused, so no keyboard on app start; the keyboard's Search key just hides it. */
@Composable
private fun SearchBox(search: String, onChange: (String) -> Unit) {
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = search, onValueChange = onChange, singleLine = true,
        placeholder = { Text("Find a game") },
        leadingIcon = { Icon(Icons.Default.Search, null) },
        trailingIcon = if (search.isEmpty()) null else {
            { IconButton(onClick = { onChange("") }) { Icon(Icons.Default.Close, "Clear search") } }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .semantics { contentDescription = "Search games" },
    )
}

/** All, the ★ Favourites and ⬇ On phone toggles, then the console bar; scrolls sideways. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterChips(
    vm: AppViewModel, filter: ConsoleFilter, onPhone: Set<String>, modifier: Modifier, onFilter: (ConsoleFilter) -> Unit,
) = Row(modifier.horizontalScroll(rememberScrollState()).padding(start = 12.dp)) {
    FilterChip(filter == ConsoleFilter.All, { onFilter(ConsoleFilter.All) }, { Text("All") }, Modifier.padding(end = 6.dp))
    // On/off, works with the console chips, remembered per user. Hidden with no starred games.
    if (vm.games.any { it.favourite }) {
        FilterChip(vm.favouritesOnly, { vm.chooseFavouritesOnly(!vm.favouritesOnly) }, { Text(LibraryFilter.FAVOURITES) },
            Modifier.padding(end = 6.dp).semantics {
                contentDescription = "Show only Favourites, " + if (vm.favouritesOnly) "on" else "off"
            })
    }
    // Same, for games playable offline. Hidden unless some games are on the phone and some aren't.
    if (onPhone.isNotEmpty() && onPhone.size < vm.games.size) {
        FilterChip(vm.onPhoneOnly, { vm.chooseOnPhoneOnly(!vm.onPhoneOnly) }, { Text(LibraryFilter.ON_PHONE) },
            Modifier.padding(end = 6.dp).semantics {
                contentDescription = "Show only games on this phone, " + if (vm.onPhoneOnly) "on" else "off"
            })
    }
    // Nintendo ▾ / Sony ▾ menus and plain chips, only consoles that have games.
    val bar = remember(vm.games) { ConsoleBar.items(vm.games.mapNotNull { it.console }.toSet()) }
    bar.forEach { item ->
        when (item) {
            is ConsoleBar.Item.Chip -> {
                val f = ConsoleFilter.One(item.console)
                FilterChip(filter == f, { onFilter(f) }, { Text(item.console.shortName) }, Modifier.padding(end = 6.dp))
            }
            is ConsoleBar.Item.Menu -> MakerChip(item, filter, vm.games, onFilter)
        }
    }
}

/** A menu row's ✓ when it is the one chosen, else the same space, so the names line up. */
@Composable
private fun CheckSlot(on: Boolean) = if (on) Icon(Icons.Default.Check, null) else Spacer(Modifier.size(24.dp))

/** "Nintendo ▾" (or "Nintendo: GBA ▾" while one of its consoles is picked): a menu of All Nintendo and each console, with counts. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MakerChip(menu: ConsoleBar.Item.Menu, filter: ConsoleFilter, games: List<Game>, onPick: (ConsoleFilter) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.padding(end = 6.dp)) {
        FilterChip(
            ConsoleBar.isOn(menu, filter), { open = true }, { Text(ConsoleBar.label(menu, filter) + " ▾", maxLines = 1) },
            Modifier.semantics { contentDescription = ConsoleBar.description(menu, filter) },
        )
        DropdownMenu(open, { open = false }) {
            val choices = listOf(ConsoleFilter.Group(menu.maker) to "All ${menu.maker.label}") +
                menu.consoles.map { ConsoleFilter.One(it) to it.shortName }
            choices.forEach { (f, name) ->
                val n = games.count { f.matches(it.console) }
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = { open = false; onPick(f) },
                    leadingIcon = { CheckSlot(f == filter) },
                    trailingIcon = { Text("$n", style = MaterialTheme.typography.bodySmall) },
                    modifier = Modifier.semantics { selected = f == filter; contentDescription = "$name, $n ${if (n == 1) "game" else "games"}" },
                )
            }
        }
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
                    leadingIcon = { CheckSlot(s == sort) },
                    modifier = Modifier.semantics { selected = s == sort },
                )
            }
        }
    }
}

/** GameCube, Wii and PS2 games (D218): the Windows app plays them; the phone lists them and keeps their saves. */
internal const val PLAYS_ON_WINDOWS = "Plays on Windows"

@Composable
private fun GameRow(
    g: Game, vm: AppViewModel, onDownload: () -> Unit, onPlay: () -> Unit,
    onUpload: () -> Unit, onGetSave: () -> Unit, onSavesPanel: () -> Unit, onLink: () -> Unit,
) {
    val downloaded = remember(g.id, vm.diskVersion) { Storage.isDownloaded(g) }
    val played = remember(g, vm.diskVersion) { PlayTime.label(vm.playSeconds(g)) }
    val prog = vm.progress[g.id]
    val conflict = g.id in vm.conflicts
    var menu by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // Read once per change (returning from the 3DS check bumps diskVersion), not on every redraw during a download.
    val lock3ds = remember(g.console, vm.diskVersion) { if (g.console == Console.N3DS) N3dsCheck.status(ctx).takeIf { it.startsWith("🔒") } else null }

    ListItem(
        leadingContent = { BoxArtImage(g, vm.artVersion) },
        headlineContent = {
            Text(if (g.favourite) "★ ${g.name}" else g.name,
                Modifier.semantics { if (g.favourite) contentDescription = "${g.name}, starred" })
        },
        supportingContent = {
            Column {
                Text(
                    listOfNotNull(
                        g.console?.shortName ?: "Unknown console",
                        if (g.console?.isWindowsOnly == true) PLAYS_ON_WINDOWS else null,
                        if (downloaded) "On phone" else null,
                        if (!downloaded) lock3ds else null,
                        played,
                    ).joinToString(" · ")
                )
                if (conflict) {
                    // Square, not the usual pill: with no padding the pill's round ends cut into the wrapped second line.
                    TextButton(onClick = { vm.askConflict(g) }, contentPadding = PaddingValues(0.dp), shape = RectangleShape) {
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
                    // GameCube, Wii, PS2 (D218): the Windows app plays them; here only their saves (menu).
                    g.console?.isWindowsOnly == true -> Text(
                        PLAYS_ON_WINDOWS, style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> OutlinedButton(
                        onClick = onDownload,
                        enabled = !vm.offline && vm.serverProblem == null && g.canDownload && g.console?.core != null,
                    ) { Text("Download") }
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, moreLabel(g)) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Saves on Jellyfin…") }, { menu = false; onSavesPanel() })
                        if (vm.hasFavourites && !vm.offline) {
                            DropdownMenuItem({ Text(if (g.favourite) "Unstar" else "Star") }, { menu = false; vm.setFavourite(g, !g.favourite) })
                        }
                        if (downloaded) {
                            if (LinkRules.supports(g.console)) {
                                // GBA's Link play also offers the internet and Celio Link (D176, D177); DS is Wi-Fi only.
                                val linkLabel = if (g.console == Console.GBA) "Link play" else "Link play (same Wi-Fi)"
                                DropdownMenuItem({ Text(linkLabel) }, { menu = false; onLink() })
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
        title = { DialogTitle("Get save from Jellyfin?") },
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
        title = { DialogTitle("Which save should be in use?") },
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
