package uk.benmarks.shoalcade

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Settings outside a game (D132), opened from the library's ⚙ button. Per console: Screen, DS screens, Touch buttons
 * and Controls, through the same prefs as the in-game menu ([SettingsModel]). Then Push all / Pull all, and the app's
 * server, account, storage and About. Rows are at least 48 dp, with headings and roles for TalkBack, and are
 * focusable, so a controller's D-pad moves through them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(vm: AppViewModel, onClose: () -> Unit) {
    val ctx = LocalContext.current
    BackHandler(onBack = onClose)
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) { vm.message?.let { snackbar.showSnackbar(it); vm.message = null } }
    var about by remember { mutableStateOf(false) }
    if (about) AboutDialog { about = false }
    var orphans by remember { mutableStateOf(false) }
    if (orphans) OrphanSavesPanel(vm) { orphans = false }

    var consoleKey by rememberSaveable { mutableStateOf(SettingsModel.CONSOLES.first().key) }
    val console = Console.fromKey(consoleKey) ?: SettingsModel.CONSOLES.first()
    val controls = remember { ControlPrefs.of(ctx) }
    val display = remember(console) { DisplayPrefs.of(ctx, console) }
    var changes by remember { mutableIntStateOf(0) } // bumped after each pick, so the rows re-read the prefs
    val sections = remember(console, changes) { SettingsModel.sections(console, display, controls.touchMode(console)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(SettingsModel.TITLE) },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, SettingsModel.BACK) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Heading(SettingsModel.EACH_CONSOLE)
            Help(SettingsModel.EACH_CONSOLE_HELP)
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                SettingsModel.CONSOLES.forEach { c ->
                    FilterChip(c == console, { consoleKey = c.key }, { Text(c.shortName) },
                        Modifier.padding(end = 6.dp).heightIn(min = 48.dp))
                }
            }

            SettingsModel.screenNote(console)?.let { SubHeading(GameMenu.SCREEN); Text(it, style = MaterialTheme.typography.bodyLarge) }
            for (s in sections) {
                SubHeading(s.title)
                for (item in s.items) {
                    DrawerRow(item, focused = false) {
                        if (item.id == GameMenu.ID_CONTROLS) ctx.startActivity(ControlsActivity.intent(ctx, console))
                        else if (SettingsModel.pick(item.id, console, display, controls)) changes++
                    }
                }
            }
            // D38: the server decides, shared with the browser, so it is shown here but not changed here.
            SubHeading(GameMenu.SNAPSHOTS)
            val snapshots = remember(console) { SnapshotSetting.enabled(ctx, console) }
            Text(if (snapshots) SettingsModel.SNAPSHOTS_ON else SettingsModel.SNAPSHOTS_OFF, style = MaterialTheme.typography.bodyLarge)
            Help(SettingsModel.SNAPSHOTS_WHERE)

            Heading(SettingsModel.SAVES)
            Help(SettingsModel.SAVES_HELP)
            SyncAllBar(vm)
            Text(SettingsModel.CHANGING_PHONES, style = MaterialTheme.typography.bodyMedium)
            // Auto upload: on by default. Turning it on asks (Android 13+) to show "a save needs your choice" notices.
            val autoStore = remember { AutoUpload.store(ctx) }
            var autoUpload by remember { mutableStateOf(AutoUpload.on(autoStore)) }
            val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
            DrawerRow(SettingsModel.autoUploadRow(autoStore).copy(on = autoUpload), focused = false) {
                autoUpload = !autoUpload
                runCatching { AutoUpload.setOn(ctx, autoUpload) } // on: waiting saves go up once online; off: the run is cancelled
                if (autoUpload && needsNotificationPermission(ctx)) notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
            Help(SettingsModel.AUTO_UPLOAD_HELP)
            SubHeading(SettingsModel.NOT_IN_LIBRARY)
            Help(SettingsModel.NOT_IN_LIBRARY_HELP)
            WideButton(SettingsModel.NOT_IN_LIBRARY + "…") { orphans = true }

            // D151: the 3DS check, run once per phone before 3DS games can be downloaded.
            Heading(N3dsCheck.TITLE)
            Text(N3dsCheck.status(ctx), style = MaterialTheme.typography.bodyLarge)
            WideButton(N3dsCheck.OPEN) { ctx.startActivity(android.content.Intent(ctx, N3dsCheckActivity::class.java)) }

            // Celio Link (trade with PC players): off by default, per phone. A third-party server, so it says so.
            Heading(CelioText.HEADING)
            var celioOn by remember { mutableStateOf(LinkPrefs.celioOn(ctx)) }
            DrawerRow(MenuItem.Toggle(ID_CELIO, CelioText.SWITCH, celioOn), focused = false) {
                celioOn = !celioOn; LinkPrefs.setCelioOn(ctx, celioOn)
            }
            Help(CelioText.SWITCH_HINT)
            if (celioOn) {
                var server by remember { mutableStateOf(LinkPrefs.celioServer(ctx)) }
                val problem = CelioWire.serverProblem(server)
                OutlinedTextField(server, { server = it.trim(); LinkPrefs.setCelioServer(ctx, server) },
                    label = { Text(CelioText.SERVER) }, singleLine = true, isError = problem != null,
                    supportingText = problem?.let { { Text(it) } },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp))
                if (server != CelioWire.DEFAULT_SERVER) {
                    TextButton(onClick = { server = CelioWire.DEFAULT_SERVER; LinkPrefs.setCelioServer(ctx, server) },
                        modifier = Modifier.heightIn(min = 48.dp)) { Text(CelioText.USE_PUBLIC) }
                }
            }

            Heading(SettingsModel.APP)
            var haptics by remember { mutableStateOf(Haptics.on(ctx)) }
            DrawerRow(MenuItem.Toggle(SettingsModel.ID_HAPTICS, SettingsModel.HAPTICS, haptics), focused = false) {
                haptics = !haptics; Haptics.setOn(GameSound.store(ctx), haptics)
            }
            // D248, D246, D247: kept on this phone, on by default.
            val appStore = remember { GameSound.store(ctx) }
            var showContinue by remember { mutableStateOf(ContinueGame.on(appStore)) }
            DrawerRow(MenuItem.Toggle(SettingsModel.ID_SHOW_CONTINUE, SettingsModel.SHOW_CONTINUE, showContinue), focused = false) {
                showContinue = !showContinue; ContinueGame.setOn(appStore, showContinue)
            }
            Help(SettingsModel.SHOW_CONTINUE_HELP)
            var rumble by remember { mutableStateOf(ControllerRumble.on(appStore)) }
            DrawerRow(MenuItem.Toggle(SettingsModel.ID_RUMBLE, SettingsModel.RUMBLE, rumble), focused = false) {
                rumble = !rumble; ControllerRumble.setOn(appStore, rumble)
            }
            Help(SettingsModel.RUMBLE_HELP)
            var shortcuts by remember { mutableStateOf(ControllerShortcuts.on(appStore)) }
            DrawerRow(MenuItem.Toggle(SettingsModel.ID_SHORTCUTS, SettingsModel.SHORTCUTS, shortcuts), focused = false) {
                shortcuts = !shortcuts; ControllerShortcuts.setOn(appStore, shortcuts)
            }
            Help(SettingsModel.SHORTCUTS_HELP)
            val session = vm.session
            Labelled(SettingsModel.SERVER, session?.serverUrl.orEmpty())
            Labelled(SettingsModel.ACCOUNT, session?.userName.orEmpty() + if (vm.offline) " · offline" else "")
            WideButton(SettingsModel.LOG_OUT) { vm.logout() }
            // F9: counted again when files change (e.g. a download removed under Not in your library).
            var used by remember { mutableStateOf<Long?>(null) }
            LaunchedEffect(vm.diskVersion) { used = withContext(Dispatchers.IO) { runCatching { Storage.bytesUsed() }.getOrNull() } }
            Labelled(SettingsModel.STORAGE,
                used?.let { android.text.format.Formatter.formatShortFileSize(ctx, it) } ?: "…", SettingsModel.STORAGE_HELP)
            WideButton(SettingsModel.ABOUT) { about = true }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Heading(text: String) = Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
    modifier = Modifier.padding(top = 16.dp).semantics { heading() })

@Composable
private fun SubHeading(text: String) = Text(text, style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 10.dp).semantics { heading() })

@Composable
private fun Help(text: String) = Text(text, style = MaterialTheme.typography.bodySmall)

/** A full-width button, at least 48 dp tall. */
@Composable
private fun WideButton(text: String, onClick: () -> Unit) =
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(text) }

@Composable
private fun Labelled(label: String, value: String, help: String? = null) {
    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics(mergeDescendants = true) {}) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(value, style = MaterialTheme.typography.bodyLarge)
        help?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

private const val ID_CELIO = "link.celio"
