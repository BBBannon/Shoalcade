package uk.benmarks.shoalcade

import androidx.activity.compose.BackHandler
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
            Text(SettingsModel.EACH_CONSOLE_HELP, style = MaterialTheme.typography.bodySmall)
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
            Text(SettingsModel.SNAPSHOTS_WHERE, style = MaterialTheme.typography.bodySmall)

            Heading(SettingsModel.SAVES)
            Text(SettingsModel.SAVES_HELP, style = MaterialTheme.typography.bodySmall)
            SyncAllBar(vm)
            Text(SettingsModel.CHANGING_PHONES, style = MaterialTheme.typography.bodyMedium)
            SubHeading(SettingsModel.NOT_IN_LIBRARY)
            Text(SettingsModel.NOT_IN_LIBRARY_HELP, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { orphans = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(SettingsModel.NOT_IN_LIBRARY + "…")
            }

            // D151: the 3DS check, run once per phone before 3DS games can be downloaded.
            Heading(N3dsCheck.TITLE)
            Text(N3dsCheck.status(ctx), style = MaterialTheme.typography.bodyLarge)
            OutlinedButton(onClick = { ctx.startActivity(android.content.Intent(ctx, N3dsCheckActivity::class.java)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(N3dsCheck.OPEN) }

            Heading(SettingsModel.APP)
            var haptics by remember { mutableStateOf(Haptics.on(ctx)) }
            DrawerRow(MenuItem.Toggle(SettingsModel.ID_HAPTICS, SettingsModel.HAPTICS, haptics), focused = false) {
                haptics = !haptics; Haptics.setOn(GameSound.store(ctx), haptics)
            }
            val session = vm.session
            Labelled(SettingsModel.SERVER, session?.serverUrl.orEmpty())
            Labelled(SettingsModel.ACCOUNT, session?.userName.orEmpty() + if (vm.offline) " · offline" else "")
            OutlinedButton(onClick = { vm.logout() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(SettingsModel.LOG_OUT)
            }
            // F9: counted again when files change (e.g. a download removed under Not in your library).
            var used by remember { mutableStateOf<Long?>(null) }
            LaunchedEffect(vm.diskVersion) { used = withContext(Dispatchers.IO) { runCatching { Storage.bytesUsed() }.getOrNull() } }
            Labelled(SettingsModel.STORAGE,
                used?.let { android.text.format.Formatter.formatShortFileSize(ctx, it) } ?: "…", SettingsModel.STORAGE_HELP)
            OutlinedButton(onClick = { about = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(SettingsModel.ABOUT)
            }
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
private fun Labelled(label: String, value: String, help: String? = null) {
    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics(mergeDescendants = true) {}) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(value, style = MaterialTheme.typography.bodyLarge)
        help?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
