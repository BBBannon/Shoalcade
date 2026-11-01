package uk.benmarks.shoalcade.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uk.benmarks.shoalcade.ConsoleBar
import uk.benmarks.shoalcade.ConsoleFilter
import uk.benmarks.shoalcade.Game
import uk.benmarks.shoalcade.GameSort
import uk.benmarks.shoalcade.LibraryFilter
import uk.benmarks.shoalcade.PlayTime
import uk.benmarks.shoalcade.ServerStatus
import uk.benmarks.shoalcade.SettingsModel
import uk.benmarks.shoalcade.desktop.AppModel
import uk.benmarks.shoalcade.desktop.N3dsCheckPc
import uk.benmarks.shoalcade.desktop.PcWords

/**
 * The library: the phone's LibraryScreen (MainActivity.kt lines 164-368) as a grid of box art for a desktop window.
 * Same filters, words and rules, all from the shared files: console bar ([ConsoleBar]), ★ Favourites only and
 * ⬇ On this PC only and the search ([LibraryFilter]), sort ([GameSort]), server light ([ServerStatus]), play time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(vm: AppModel) {
    val snackbar = remember { SnackbarHostState() }
    var filter by remember { mutableStateOf<ConsoleFilter>(ConsoleFilter.All) }
    var search by remember { mutableStateOf("") }

    LaunchedEffect(vm.message) { vm.message?.let { snackbar.showSnackbar(it); vm.message = null } }
    var savesOf by remember { mutableStateOf<Pair<Game, Boolean>?>(null) }
    var getSaveOf by remember { mutableStateOf<Game?>(null) }
    var savesSettings by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }
    var linkSettings by remember { mutableStateOf(false) }
    if (about) AboutDialog { about = false }
    if (linkSettings) LinkSettingsDialog { linkSettings = false }
    if (vm.checkOpen) N3dsCheckDialog(vm) { vm.checkOpen = false }
    vm.linkGame?.let { g -> LinkDialog(vm, g) { vm.linkGame = null } }
    savesOf?.let { (g, removed) -> SavesPanel(vm, g, removed) { savesOf = null } }
    getSaveOf?.let { g -> GetSaveDialog(vm, g) { getSaveOf = null } }
    vm.conflictPrompt?.let { ConflictDialog(vm, it) }
    vm.playAsk?.let { PlayAskDialog(vm, it) }
    vm.launch?.let { LaunchChoiceDialog(vm, it) }
    vm.batchReport?.let { SyncAllDialog(vm, it) }
    if (savesSettings) SavesSettingsDialog(vm, onOpenSaves = { g, removed -> savesSettings = false; savesOf = g to removed }) { savesSettings = false }
    val actions = remember { CardActions({ g -> savesOf = g to false }, { g -> getSaveOf = g }) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Shoalcade")
                        Text((vm.session?.userName ?: "") + if (vm.offline) " · offline" else "", style = MaterialTheme.typography.bodySmall)
                    }
                },
                actions = {
                    IconButton(onClick = { vm.refresh() }) { Icon(Icons.Default.Refresh, "Refresh") }
                    SettingsCog(vm, connected = vm.session != null && ServerStatus.connected.collectAsState().value, onSaves = { savesSettings = true },
                        onLink = { linkSettings = true }, onAbout = { about = true })
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        vm.playing?.let { p -> Column(Modifier.padding(pad).fillMaxSize()) { PlayingScreen(vm, p) }; return@Scaffold }
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            SyncAllProgress(vm)
            if (vm.exiting) Text("Uploading waiting saves before closing…", modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            vm.serverProblem?.let { p ->
                Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                    Text("⚠️ $p", color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(12.dp))
                }
            }

            val onPc = remember(vm.games, vm.diskVersion) { vm.onPc() }
            // The last download removed: the toggle goes off rather than coming back on with the next download.
            LaunchedEffect(onPc) { if (vm.onPcOnly && vm.games.isNotEmpty() && onPc.isEmpty()) vm.chooseOnPcOnly(false) }

            if (vm.games.isNotEmpty()) {
                OutlinedTextField(
                    value = search, onValueChange = { search = it }, singleLine = true,
                    placeholder = { Text("Find a game") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = if (search.isEmpty()) null else {
                        { IconButton(onClick = { search = "" }) { Icon(Icons.Default.Close, "Clear search") } }
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                        .semantics { contentDescription = "Search games" },
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp)) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(start = 16.dp)) {
                    FilterChip(filter == ConsoleFilter.All, { filter = ConsoleFilter.All }, { Text("All") }, Modifier.padding(end = 6.dp))
                    if (vm.games.any { it.favourite }) {
                        FilterChip(vm.favouritesOnly, { vm.chooseFavouritesOnly(!vm.favouritesOnly) }, { Text(LibraryFilter.FAVOURITES) },
                            Modifier.padding(end = 6.dp).semantics {
                                contentDescription = "Show only Favourites, " + if (vm.favouritesOnly) "on" else "off"
                            })
                    }
                    if (onPc.isNotEmpty() && onPc.size < vm.games.size) {
                        FilterChip(vm.onPcOnly, { vm.chooseOnPcOnly(!vm.onPcOnly) }, { Text(PcWords.of(LibraryFilter.ON_PHONE)) },
                            Modifier.padding(end = 6.dp).semantics {
                                contentDescription = "Show only games on this PC, " + if (vm.onPcOnly) "on" else "off"
                            })
                    }
                    val bar = remember(vm.games) { ConsoleBar.items(vm.games.mapNotNull { it.console }.toSet()) }
                    bar.forEach { item ->
                        when (item) {
                            is ConsoleBar.Item.Chip -> {
                                val f = ConsoleFilter.One(item.console)
                                FilterChip(filter == f, { filter = f }, { Text(item.console.shortName) }, Modifier.padding(end = 6.dp))
                            }
                            is ConsoleBar.Item.Menu -> MakerChip(item, filter, vm.games) { filter = it }
                        }
                    }
                }
                SortButton(vm.sort) { vm.chooseSort(it) }
            }

            val lf = LibraryFilter(filter, vm.favouritesOnly, vm.onPcOnly, search)
            val shown = remember(vm.games, lf, onPc, vm.sort, vm.diskVersion) { vm.sorted(lf.shown(vm.games, onPc)) }
            if (shown.isEmpty() && !vm.loading) {
                Text(
                    if (vm.games.isNotEmpty()) PcWords.of(lf.emptyMessage(vm.games, onPc))
                    else if (vm.offline) "Offline and no saved game list yet." else "No games found on this server.",
                    Modifier.padding(24.dp),
                )
            }
            val gridState = rememberLazyGridState()
            val topKey = lf.topKey(vm.sort, shown)
            var lastTopKey by remember { mutableStateOf(topKey) }
            LaunchedEffect(topKey) { if (topKey != lastTopKey) { lastTopKey = topKey; gridState.scrollToItem(0) } }
            LazyVerticalGrid(
                GridCells.Adaptive(176.dp), Modifier.fillMaxSize(), gridState,
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(shown, key = { it.id }) { g -> GameCard(g, vm, downloaded = g.id in onPc, actions) }
            }
        }
    }
}

/**
 * The settings cog doubles as the server light, as on the phone (F3): green when connected, red with a "!" badge when
 * not (not colour alone), and its name says which. The Settings screen comes later; for now it holds Log out.
 */
@Composable
private fun SettingsCog(vm: AppModel, connected: Boolean, onSaves: () -> Unit, onLink: () -> Unit, onAbout: () -> Unit) {
    val colour = if (connected) Brand.Connected else Brand.NotConnected
    val label = ServerStatus.label(connected)
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.semantics { contentDescription = label }) {
            BadgedBox(modifier = Modifier.clearAndSetSemantics { }, badge = {
                if (!connected) Badge(containerColor = colour, contentColor = Color.Black) { Text("!") }
            }) { Icon(Icons.Default.Settings, contentDescription = null, tint = colour) }
        }
        DropdownMenu(open, { open = false }) {
            Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            vm.session?.let { s ->
                Text("${SettingsModel.SERVER}: ${s.serverUrl}", style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                Text("${SettingsModel.ACCOUNT}: ${s.userName}", style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            HorizontalDivider()
            DropdownMenuItem({ Text("Saves") }, { open = false; onSaves() })
            DropdownMenuItem({ Text(uk.benmarks.shoalcade.CelioText.HEADING) }, { open = false; onLink() })
            DropdownMenuItem({ Text(N3dsCheckPc.TITLE) }, { open = false; vm.checkOpen = true })
            DropdownMenuItem({ Text("About Shoalcade") }, { open = false; onAbout() })
            DropdownMenuItem({ Text(SettingsModel.LOG_OUT) }, { open = false; vm.logout() })
        }
    }
}

/** "Nintendo ▾" (or "Nintendo: GBA ▾"): a menu of All Nintendo and each console, with counts (MainActivity.kt MakerChip). */
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
                    leadingIcon = { if (f == filter) Icon(Icons.Default.Check, null) else Spacer(Modifier.size(24.dp)) },
                    trailingIcon = { Text("$n", style = MaterialTheme.typography.bodySmall) },
                )
            }
        }
    }
}

/** "Sort: Last played ▾"; remembered per user (MainActivity.kt SortButton). */
@Composable
private fun SortButton(sort: GameSort, onChoose: (GameSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, contentPadding = PaddingValues(horizontal = 8.dp),
            modifier = Modifier.semantics { contentDescription = "Sort games by ${sort.label}. Click to change." },
        ) { Text("Sort: ${sort.label} ▾", maxLines = 1) }
        DropdownMenu(open, { open = false }) {
            GameSort.entries.forEach { s ->
                DropdownMenuItem(
                    text = { Text(s.label) },
                    onClick = { open = false; onChoose(s) },
                    leadingIcon = { if (s == sort) Icon(Icons.Default.Check, null) else Spacer(Modifier.size(24.dp)) },
                )
            }
        }
    }
}

/** What a card's ⋮ menu opens (dialogs live on the library screen). */
class CardActions(val savesPanel: (Game) -> Unit, val getSave: (Game) -> Unit)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GameCard(g: Game, vm: AppModel, downloaded: Boolean, actions: CardActions) {
    val prog = vm.progress[g.id]
    var menu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column {
            BoxArtImage(g, vm)
            Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp)) {
                Text(if (g.favourite) "★ ${g.name}" else g.name, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.semantics { if (g.favourite) contentDescription = "${g.name}, starred" })
                Text(
                    listOfNotNull(
                        g.console?.shortName ?: "Unknown console",
                        if (downloaded) PcWords.of("On phone") else null,
                        PlayTime.label(vm.playSeconds(g)),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                if (g.id in vm.conflicts) {
                    TextButton(onClick = { vm.askConflict(g) }, contentPadding = PaddingValues(0.dp)) {
                        Text("⚠️ PC and Jellyfin saves differ: choose", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                } else if (g.id in vm.waitingIds) {
                    Text("Save waiting to go up to Jellyfin", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                }
            }
            Row(Modifier.padding(start = 12.dp, end = 4.dp, bottom = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    when {
                        // Click the progress ring to cancel.
                        prog != null -> IconButton(onClick = { vm.cancelDownload(g) }) {
                            CircularProgressIndicator(progress = { prog }, modifier = Modifier.size(32.dp))
                            Icon(Icons.Default.Close, "Cancel download", Modifier.size(16.dp))
                        }
                        vm.checkingSave == g.id -> CircularProgressIndicator(Modifier.size(28.dp))
                        downloaded -> Button(onClick = { vm.play(g) }, enabled = vm.playing == null && vm.batch == null) { Text("Play") }
                        else -> OutlinedButton(onClick = { vm.download(g) }, enabled = vm.canDownload(g),
                            contentPadding = PaddingValues(horizontal = 14.dp)) { Text("Download", maxLines = 1, softWrap = false) }
                    }
                }
                val canStar = vm.hasFavourites && !vm.offline
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Saves on Jellyfin…") }, { menu = false; actions.savesPanel(g) })
                        if (canStar) {
                            DropdownMenuItem({ Text(if (g.favourite) "Unstar" else "Star") }, { menu = false; vm.setFavourite(g, !g.favourite) })
                        }
                        if (downloaded && uk.benmarks.shoalcade.LinkRules.supports(g.console)) {
                            // GBA's Link play also offers the internet and Celio Link (D176, D177); DS is Wi-Fi only.
                            val linkLabel = if (g.console == uk.benmarks.shoalcade.Console.GBA) "Link play" else "Link play (same Wi-Fi)"
                            DropdownMenuItem({ Text(linkLabel) }, { menu = false; vm.linkGame = g }, enabled = vm.playing == null)
                        }
                        if (downloaded) {
                            DropdownMenuItem({ Text("Upload save to Jellyfin") }, { menu = false; vm.uploadSave(g) })
                            DropdownMenuItem({ Text("Get save from Jellyfin") }, { menu = false; actions.getSave(g) })
                            DropdownMenuItem({ Text(PcWords.of("Remove from phone (keeps save)")) }, { menu = false; vm.deleteDownload(g) })
                        }
                    }
                }
            }
        }
    }
}

/** The game's cover (cached under art\), or a coloured tile with the console name when there isn't one. */
@Composable
private fun BoxArtImage(game: Game, vm: AppModel) {
    var bmp by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(game.id, game.imageTag, vm.artVersion) {
        bmp = withContext(Dispatchers.IO) { vm.art.load(game) }
    }
    val m = Modifier.fillMaxWidth().aspectRatio(0.8f).clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
    val b = bmp
    if (b != null) {
        Image(b, contentDescription = null, contentScale = ContentScale.Crop, modifier = m)
    } else {
        Box(m.background(Brand.tile(false)), contentAlignment = Alignment.Center) {
            Text(game.console?.shortName ?: "?", style = MaterialTheme.typography.headlineSmall, color = Color.White)
        }
    }
}
