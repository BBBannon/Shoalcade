package uk.benmarks.shoalcade.desktop.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uk.benmarks.shoalcade.BuildConfig
import uk.benmarks.shoalcade.Console
import uk.benmarks.shoalcade.ConsoleBar
import uk.benmarks.shoalcade.ConsoleFilter
import uk.benmarks.shoalcade.Game
import uk.benmarks.shoalcade.GameSort
import uk.benmarks.shoalcade.LibraryFilter
import uk.benmarks.shoalcade.LinkRules
import uk.benmarks.shoalcade.PlayTime
import uk.benmarks.shoalcade.ServerStatus
import uk.benmarks.shoalcade.desktop.AppModel
import uk.benmarks.shoalcade.desktop.CheatEditor
import uk.benmarks.shoalcade.desktop.ContinueCard
import uk.benmarks.shoalcade.desktop.DesktopStorage
import uk.benmarks.shoalcade.desktop.NetworkAdapter
import uk.benmarks.shoalcade.desktop.PcWords
import uk.benmarks.shoalcade.desktop.Ps2

/**
 * The library in the plugin's Pocket layout (index.html, sc.css): the top bar (brand, who, Settings, search), the
 * console strip, "Games" with Sort, the grid of tiles and "Played lately" beside it. A tile opens the game sheet with
 * the game's actions. Filters, words and rules are the phone's shared files, as before: console bar ([ConsoleBar]),
 * ★ Favourites only and ⬇ On this PC only and the search ([LibraryFilter]), sort ([GameSort]), server light
 * ([ServerStatus]), play time.
 */
@Composable
fun LibraryScreen(vm: AppModel) {
    val snackbar = remember { SnackbarHostState() }
    var filter by remember { mutableStateOf<ConsoleFilter>(ConsoleFilter.All) }
    var search by remember { mutableStateOf("") }

    LaunchedEffect(vm.message) { vm.message?.let { snackbar.showSnackbar(it); vm.message = null } }
    var savesOf by remember { mutableStateOf<Pair<Game, Boolean>?>(null) }
    var getSaveOf by remember { mutableStateOf<Game?>(null) }
    var cheatsOf by remember { mutableStateOf<Game?>(null) }
    var sheetOf by remember { mutableStateOf<String?>(null) }
    var settingsTab by remember { mutableStateOf<SettingsTab?>(null) } // the Settings panel, open on this tab
    var lastTab by remember { mutableStateOf(SettingsTab.ACCOUNT) }
    val onPc = remember(vm.games, vm.diskVersion) { vm.onPc() }
    val actions = remember { CardActions({ g -> savesOf = g to false }, { g -> getSaveOf = g }, { g -> cheatsOf = g }) }
    // The sheet follows the game by id, so a download finishing or a star changing shows at once.
    sheetOf?.let { id -> vm.games.firstOrNull { it.id == id }?.let { g -> GameSheet(g, vm, g.id in onPc, actions) { sheetOf = null } } ?: run { sheetOf = null } }
    vm.linkGame?.let { g -> LinkDialog(vm, g) { vm.linkGame = null } }
    savesOf?.let { (g, removed) -> SavesPanel(vm, g, removed) { savesOf = null } }
    getSaveOf?.let { g -> GetSaveDialog(vm, g) { getSaveOf = null } }
    cheatsOf?.let { g -> CheatsPanel(vm, g) { cheatsOf = null } }
    vm.conflictPrompt?.let { ConflictDialog(vm, it) }
    vm.playAsk?.let { PlayAskDialog(vm, it) }
    vm.launch?.let { LaunchChoiceDialog(vm, it) }
    vm.batchReport?.let { SyncAllDialog(vm, it) }
    val connected = vm.session != null && ServerStatus.connected.collectAsState().value
    val onSettings = { settingsTab = if (settingsTab == null) lastTab else null }

    // The Settings panel sits at the right edge; the library (or the playing stage) beside it shrinks, as in the plugin.
    Row(Modifier.fillMaxSize().background(Pocket.Bg)) {
        Box(Modifier.weight(1f).fillMaxHeight()) {
            // While a game is open the top bar stays (Settings, Refresh, as before), over the player's dark stage.
            vm.playing?.let { p ->
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, top = 22.dp, bottom = 16.dp)) {
                        TopBar(vm, search, { search = it }, connected, settingsTab != null, onSettings, searchable = false)
                    }
                    PlayingScreen(vm, p)
                }
                PocketToastHost(snackbar, Modifier.align(Alignment.BottomCenter))
                return@Box
            }
            Column(Modifier.fillMaxSize()) {
                if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Pocket.Accent, trackColor = Pocket.PLine)
                Column(Modifier.widthIn(max = 1320.dp).fillMaxWidth().align(Alignment.CenterHorizontally).padding(start = 32.dp, end = 32.dp, top = 22.dp)) {
                    TopBar(vm, search, { search = it }, connected, settingsTab != null, onSettings)
                    SyncAllProgress(vm)
                    if (vm.exiting) PocketNote("Uploading waiting saves before closing…", Modifier.padding(top = 12.dp))
                    vm.serverProblem?.let { p -> PocketNote("⚠️ $p", Modifier.padding(top = 12.dp), error = true) }
                    UpdateBanner(vm)

                    // The last download removed: the toggle goes off rather than coming back on with the next download.
                    LaunchedEffect(onPc) { if (vm.onPcOnly && vm.games.isNotEmpty() && onPc.isEmpty()) vm.chooseOnPcOnly(false) }

                    ConsoleStrip(vm, filter, onPc) { filter = it }

                    val lf = LibraryFilter(filter, vm.favouritesOnly, vm.onPcOnly, search)
                    val shown = remember(vm.games, lf, onPc, vm.sort, vm.diskVersion) { vm.sorted(lf.shown(vm.games, onPc)) }
                    val recent = remember(vm.games, vm.diskVersion, vm.playing) {
                        vm.games.mapNotNull { g -> vm.lastPlayed(g)?.let { g to it } }.sortedByDescending { it.second }.take(6)
                    }
                    val carryOn = remember(vm.games, onPc, vm.diskVersion, vm.playing, search, vm.showContinue) {
                        if (vm.showContinue) ContinueCard.pick(vm.games, onPc, search) { vm.lastPlayed(it) } else null
                    }
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                        Column(Modifier.weight(1f)) {
                            // D248: the Continue card. Play's own path (save check, launch choice); a PS2 game with no
                            // BIOS on this PC opens its sheet instead, which says why it can't start.
                            carryOn?.let { (g, at) ->
                                ContinueTile(g, at, vm) { if (vm.needsBios(g)) sheetOf = g.id else vm.play(g) }
                            }
                            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                PocketSection("Games", "${shown.size} " + if (shown.size == 1) "game" else "games", Modifier.weight(1f))
                                SortButton(vm.sort) { vm.chooseSort(it) }
                            }
                            if (shown.isEmpty() && !vm.loading) {
                                Text(
                                    if (vm.games.isNotEmpty()) PcWords.of(lf.emptyMessage(vm.games, onPc))
                                    else if (vm.offline) "Offline and no saved game list yet." else "No games found on this server.",
                                    color = Pocket.Muted, modifier = Modifier.padding(vertical = 24.dp),
                                )
                            }
                            val gridState = rememberLazyGridState()
                            val topKey = lf.topKey(vm.sort, shown)
                            var lastTopKey by remember { mutableStateOf(topKey) }
                            LaunchedEffect(topKey) { if (topKey != lastTopKey) { lastTopKey = topKey; gridState.scrollToItem(0) } }
                            LazyVerticalGrid(
                                GridCells.Adaptive(140.dp), Modifier.fillMaxSize(), gridState,
                                contentPadding = PaddingValues(top = 4.dp, bottom = 48.dp, end = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp),
                            ) {
                                items(shown, key = { it.id }) { g -> GameCard(g, vm, downloaded = g.id in onPc) { sheetOf = g.id } }
                            }
                        }
                        if (recent.isNotEmpty()) PlayedLately(vm, recent) { sheetOf = it.id }
                    }
                }
            }
            PocketToastHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
        settingsTab?.let { t ->
            SettingsPanel(vm, connected, t, { settingsTab = it; lastTab = it }, { settingsTab = null },
                onOpenSaves = { g, removed -> settingsTab = null; savesOf = g to removed })
        }
    }
}

/**
 * .top: brand (logo, name, the Dev badge), who, the Settings button (also the server light; opens and closes the
 * Settings panel), Refresh, search. Internal for the comparison pictures (PocketShots).
 */
@Composable
internal fun TopBar(vm: AppModel, search: String, onSearch: (String) -> Unit, connected: Boolean, settingsOpen: Boolean, onSettings: () -> Unit,
                    searchable: Boolean = true) {
    val density = LocalDensity.current
    val logo = remember { Brand.logo(density) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.semantics(mergeDescendants = true) { heading() }) {
            Image(logo, contentDescription = null, modifier = Modifier.size(32.dp))
            Text("Shoalcade", style = MaterialTheme.typography.headlineLarge.copy(fontFamily = Pocket.condensed))
            if (BuildConfig.DEBUG) DevBadge()
        }
        Text((vm.session?.userName ?: "") + if (vm.offline) " · offline" else "", color = Pocket.Muted,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = Pocket.rem(0.85f)))
        SettingsButton(connected, settingsOpen, onSettings)
        PocketIconButton({ vm.refresh() }, "Refresh", tint = Pocket.Ink) { Icon(Icons.Default.Refresh, null, Modifier.size(20.dp)) }
        Spacer(Modifier.weight(1f))
        if (searchable && vm.games.isNotEmpty()) SearchBox(search, onSearch)
    }
}

/** The Dev build's marker (D152): the Dev red with the orange edge, beside the recoloured logo. */
@Composable
private fun DevBadge() {
    Text("Dev", color = Color.White, style = MaterialTheme.typography.labelSmall.copy(fontSize = Pocket.rem(0.8f)),
        modifier = Modifier.background(Brand.DevRed, RoundedCornerShape(2.dp)).leftEdge(Brand.DevOrange, 4.dp)
            .padding(start = 10.dp, end = 8.dp, top = 2.dp, bottom = 2.dp))
}

/** .search: an underlined box with the magnifier; the line turns LED red while typing. */
@Composable
private fun SearchBox(value: String, onChange: (String) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.width(280.dp).pocketFocusRing(focused, 2.dp)
            .drawUnderline(if (focused) Pocket.Led else Pocket.Ink).padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Default.Search, null, tint = Pocket.Ink, modifier = Modifier.size(20.dp))
        Box(Modifier.weight(1f).padding(vertical = 8.dp)) {
            if (value.isEmpty()) Text("Find a game", color = Pocket.Muted)
            BasicTextField(value, onChange, singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(color = Pocket.Ink),
                cursorBrush = SolidColor(Pocket.Ink),
                modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }.semantics { contentDescription = "Search games" })
        }
        if (value.isNotEmpty()) PocketIconButton({ onChange("") }, "Clear search", Modifier.size(28.dp), tint = Pocket.Ink) {
            Icon(Icons.Default.Close, null, Modifier.size(16.dp))
        }
    }
}

/** border-bottom: 2px solid [c]. */
private fun Modifier.drawUnderline(c: Color) = drawBehind {
    drawRect(c, topLeft = Offset(0f, size.height - 2.dp.toPx()), size = Size(size.width, 2.dp.toPx()))
}

/** .strip: All, ★ Favourites only, ⬇ On this PC only, then each console (or maker menu), with counts (.chip-k). */
@Composable
private fun ConsoleStrip(vm: AppModel, filter: ConsoleFilter, onPc: Set<String>, onPick: (ConsoleFilter) -> Unit) {
    val bar = remember(vm.games) { ConsoleBar.items(vm.games.mapNotNull { it.console }.toSet()) }
    val showFav = vm.games.any { it.favourite }
    val showPc = onPc.isNotEmpty() && onPc.size < vm.games.size
    Box(Modifier.padding(vertical = 22.dp)) {
        PocketStrip {
            val lastIsAll = !showFav && !showPc && bar.isEmpty()
            PocketChip("All", filter == ConsoleFilter.All, { onPick(ConsoleFilter.All) }, count = vm.games.size, last = lastIsAll)
            if (showFav) PocketChip(LibraryFilter.FAVOURITES, vm.favouritesOnly, { vm.chooseFavouritesOnly(!vm.favouritesOnly) },
                Modifier.semantics { contentDescription = "Show only Favourites, " + if (vm.favouritesOnly) "on" else "off" },
                count = vm.games.count { it.favourite }, last = !showPc && bar.isEmpty())
            if (showPc) PocketChip(PcWords.of(LibraryFilter.ON_PHONE), vm.onPcOnly, { vm.chooseOnPcOnly(!vm.onPcOnly) },
                Modifier.semantics { contentDescription = "Show only games on this PC, " + if (vm.onPcOnly) "on" else "off" },
                count = onPc.size, last = bar.isEmpty())
            bar.forEachIndexed { i, item ->
                val last = i == bar.lastIndex
                when (item) {
                    is ConsoleBar.Item.Chip -> {
                        val f = ConsoleFilter.One(item.console)
                        PocketChip(item.console.shortName, filter == f, { onPick(f) }, count = vm.games.count { it.console == item.console }, last = last)
                    }
                    is ConsoleBar.Item.Menu -> MakerChip(item, filter, vm.games, last, onPick)
                }
            }
        }
    }
}

/** A DropdownMenu drawn as .con-menu (surface, 2 px ink edge). */
@Composable
private fun PocketDropdown(open: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    DropdownMenu(open, onDismiss, containerColor = Pocket.Surface, shape = RoundedCornerShape(Pocket.RM),
        border = BorderStroke(2.dp, Pocket.Ink), tonalElevation = 0.dp, shadowElevation = 0.dp, content = content)
}

/** "Nintendo ▾" (or "Nintendo: GBA ▾"): a menu of All Nintendo and each console, with counts (MainActivity.kt MakerChip). */
@Composable
private fun MakerChip(menu: ConsoleBar.Item.Menu, filter: ConsoleFilter, games: List<Game>, last: Boolean, onPick: (ConsoleFilter) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(propagateMinConstraints = true) {
        val shownCount = games.count { g -> (if (ConsoleBar.isOn(menu, filter)) filter else ConsoleFilter.Group(menu.maker)).matches(g.console) }
        PocketChip(ConsoleBar.label(menu, filter), ConsoleBar.isOn(menu, filter), { open = true },
            Modifier.semantics { contentDescription = ConsoleBar.description(menu, filter) }, count = shownCount, menu = true, last = last)
        PocketDropdown(open, { open = false }) {
            val choices = listOf(ConsoleFilter.Group(menu.maker) to "All ${menu.maker.label}") +
                menu.consoles.map { ConsoleFilter.One(it) to it.shortName }
            choices.forEach { (f, name) ->
                PocketMenuItem(name, { open = false; onPick(f) }, checked = f == filter, trailing = "${games.count { f.matches(it.console) }}")
            }
        }
    }
}

/** .sort: "Sort" and the order picked; remembered per user (MainActivity.kt SortButton). */
@Composable
private fun SortButton(sort: GameSort, onChoose: (GameSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Sort", color = Pocket.Muted, style = MaterialTheme.typography.labelLarge.copy(fontSize = Pocket.rem(0.85f)), modifier = Modifier.clearAndSetSemantics { })
        Box {
            val shape = RoundedCornerShape(Pocket.RM)
            Row(
                Modifier.defaultMinSize(minHeight = 36.dp).background(Pocket.PRaise, shape).border(2.dp, Pocket.Ink, shape)
                    .clickable(remember { MutableInteractionSource() }, PocketIndication, role = Role.Button) { open = true }
                    .semantics { contentDescription = "Sort games by ${sort.label}. Click to change." }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) { Text("${sort.label} ▾", style = MaterialTheme.typography.labelLarge, maxLines = 1) }
            PocketDropdown(open, { open = false }) {
                GameSort.entries.forEach { s -> PocketMenuItem(s.label, { open = false; onChoose(s) }, checked = s == sort) }
            }
        }
    }
}

/**
 * The settings button doubles as the server light, as on the phone (F3): the cog is green when connected, red with a
 * "!" badge when not (not colour alone), and its name says which. It opens and closes the Settings panel (the
 * plugin's toolbar settings button, aria-expanded).
 */
@Composable
private fun SettingsButton(connected: Boolean, open: Boolean, onClick: () -> Unit) {
    val colour = if (connected) Brand.Connected else Brand.NotConnected
    val label = ServerStatus.label(connected)
    PocketButton(onClick, Modifier.semantics(mergeDescendants = true) {
        contentDescription = label
        stateDescription = if (open) "open" else "closed"
    }) {
        Box {
            Icon(Icons.Default.Settings, contentDescription = null, tint = colour, modifier = Modifier.size(20.dp))
            if (!connected) Text("!", color = Color.White, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.TopEnd).padding(start = 12.dp).background(Pocket.Led, CircleShape).padding(horizontal = 4.dp))
        }
        Text("Settings")
    }
}

/** What the game sheet's buttons open (dialogs live on the library screen). */
class CardActions(val savesPanel: (Game) -> Unit, val getSave: (Game) -> Unit, val cheats: (Game) -> Unit)

private fun consoleName(g: Game) = g.console?.shortName ?: "Unknown console"

/** The card's second line: console, On this PC, play time (the phone's words). */
private fun cardMeta(g: Game, vm: AppModel, downloaded: Boolean) = listOfNotNull(
    consoleName(g),
    if (downloaded) PcWords.of("On phone") else null,
    PlayTime.label(vm.playSeconds(g)),
).joinToString(" · ")

/** .card: the tile, the name (★ when starred) and the meta line; a click or Enter opens the game sheet. */
@Composable
private fun GameCard(g: Game, vm: AppModel, downloaded: Boolean, onOpen: () -> Unit) {
    val prog = vm.progress[g.id]
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val focused by src.collectIsFocusedAsState()
    Column(
        Modifier.fillMaxWidth().hoverable(src)
            .clickable(src, PocketIndication, role = Role.Button, onClick = onOpen)
            .semantics(mergeDescendants = true) {
                contentDescription = g.name + ", " + consoleName(g) + (if (g.favourite) ", starred" else "")
            },
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Cover(g, vm, Modifier.fillMaxWidth().hardShadow(hovered || focused, 4.dp).padding(bottom = 0.dp))
        Spacer(Modifier.height(6.dp))
        Text(if (g.favourite) "★ ${g.name}" else g.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
        Text(cardMeta(g, vm, downloaded), style = MaterialTheme.typography.bodySmall, color = Pocket.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (prog != null) LinearProgressIndicator({ prog }, Modifier.fillMaxWidth().padding(top = 4.dp), color = Pocket.Accent, trackColor = Pocket.PLine)
        if (g.id in vm.conflicts) {
            PocketMini({ vm.askConflict(g) }, Modifier.padding(top = 4.dp), danger = true) { Text("⚠️ PC and Jellyfin saves differ: choose") }
        } else if (g.id in vm.waitingIds) {
            Text("Save waiting to go up to Jellyfin", style = MaterialTheme.typography.bodySmall, color = Pocket.Accent)
        }
    }
}

/**
 * The Continue card (D248, [ContinueCard]): box art, "Continue", the name, console and when it was played, and a Play
 * mark. One click, or Enter / Space when focused, starts the game through Play ([AppModel.play]); a screen reader
 * hears it as one button.
 */
@Composable
private fun ContinueTile(g: Game, at: Long, vm: AppModel, onPlay: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val focused by src.collectIsFocusedAsState()
    val shape = RoundedCornerShape(Pocket.RL)
    val played = consoleName(g) + ", played " + Ago.of(at)
    Row(
        Modifier.padding(bottom = 18.dp).widthIn(max = 560.dp).fillMaxWidth()
            .hardShadow(hovered || focused, 4.dp, Pocket.RL)
            .background(Pocket.Surface, shape).border(2.dp, Pocket.Ink, shape)
            .hoverable(src)
            .clickable(src, PocketIndication, role = Role.Button, onClick = onPlay)
            .semantics(mergeDescendants = true) { contentDescription = "${ContinueCard.TITLE}: ${g.name}, $played. Starts the game." }
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(g, vm, Modifier.width(72.dp), small = true)
        Column(Modifier.weight(1f)) {
            Text(ContinueCard.TITLE, color = Pocket.Muted, style = MaterialTheme.typography.labelLarge)
            Text(g.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(played, style = MaterialTheme.typography.bodySmall, color = Pocket.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.size(44.dp).background(Pocket.Accent, CircleShape).border(2.dp, Pocket.Ink, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.PlayArrow, null, tint = Pocket.AccentInk, modifier = Modifier.size(26.dp))
        }
    }
}

/** aside.recent: "Played lately", the newest first (library.js renderRecent, six at most). */
@Composable
private fun PlayedLately(vm: AppModel, recent: List<Pair<Game, Long>>, onOpen: (Game) -> Unit) {
    PocketBox(Modifier.width(290.dp), PaddingValues(14.dp)) {
        PocketSection("Played lately", modifier = Modifier.padding(bottom = 12.dp))
        Column(Modifier.verticalScroll(rememberScrollState())) {
            recent.forEachIndexed { i, (g, at) ->
                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Pocket.Line))
                Row(
                    Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, PocketIndication, role = Role.Button) { onOpen(g) }
                        .semantics(mergeDescendants = true) { }
                        .padding(top = if (i == 0) 0.dp else 10.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Cover(g, vm, Modifier.width(52.dp), small = true)
                    Column(Modifier.weight(1f)) {
                        Text(g.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(consoleName(g) + ", played " + Ago.of(at), style = MaterialTheme.typography.bodySmall, color = Pocket.Muted)
                    }
                }
            }
        }
    }
}

/** library.js ago(): "just now", "5 minutes ago", "yesterday", "3 days ago", then "on 4 Oct". */
object Ago {
    fun of(ms: Long, now: Long = System.currentTimeMillis()): String {
        val s = (now - ms) / 1000
        fun n(v: Long, unit: String) = if (v == 1L) "1 $unit ago" else "$v ${unit}s ago"
        return when {
            s < 60 -> "just now"
            s < 3600 -> n(Math.round(s / 60.0), "minute")
            s < 86400 -> n(Math.round(s / 3600.0), "hour")
            s < 7 * 86400 -> Math.round(s / 86400.0).let { if (it == 1L) "yesterday" else n(it, "day") }
            else -> {
                val d = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault())
                val sameYear = d.year == java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).year
                "on " + d.format(java.time.format.DateTimeFormatter.ofPattern(if (sameYear) "d MMM" else "d MMM yyyy", java.util.Locale.UK))
            }
        }
    }
}

/**
 * .sheet: the game sheet. The tile, the name, its meta line, then every action the card's menu had: Play or Download
 * (with the download's progress and Cancel), Saves on Jellyfin…, Star, Link play, upload / get the save, remove.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GameSheet(g: Game, vm: AppModel, downloaded: Boolean, actions: CardActions, onDismiss: () -> Unit) {
    val prog = vm.progress[g.id]
    PocketSheet(onDismiss) {
        Row(Modifier.padding(22.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            Cover(g, vm, Modifier.width(200.dp))
            Column(Modifier.weight(1f)) {
                Text(g.name, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(end = 34.dp, bottom = 4.dp)
                    .semantics { heading() })
                Text(cardMeta(g, vm, downloaded), style = MaterialTheme.typography.bodySmall, color = Pocket.PMuted)
                FlowRow(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        prog != null -> {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CircularProgressIndicator({ prog }, Modifier.size(28.dp), color = Pocket.Accent, trackColor = Pocket.PLine)
                                PocketButton({ vm.cancelDownload(g) }) { Text("Cancel download") }
                            }
                        }
                        vm.checkingSave == g.id -> CircularProgressIndicator(Modifier.size(28.dp), color = Pocket.Accent)
                        // PS2 (plan §7): no Play until this PC has the person's own BIOS (the game would never start).
                        downloaded && vm.needsBios(g) -> PocketNote(Ps2.BIOS_MISSING, Modifier.widthIn(max = 420.dp))
                        downloaded -> PocketButton({ onDismiss(); vm.play(g) }, enabled = vm.playing == null && vm.batch == null, primary = true) {
                            Icon(Icons.Outlined.PlayArrow, null, Modifier.size(20.dp)); Text("Play")
                        }
                        else -> PocketButton({ vm.download(g) }, enabled = vm.canDownload(g), primary = true) {
                            Icon(PocketIcons.Download, null, Modifier.size(20.dp)); Text("Download", maxLines = 1, softWrap = false)
                        }
                    }
                    PocketButton({ onDismiss(); actions.savesPanel(g) }) { Icon(PocketIcons.Save, null, Modifier.size(20.dp)); Text("Saves on Jellyfin…") }
                    // D143: the game's cheats, every console but the 3DS (D210); the same list as the phone and browser.
                    if (CheatEditor.allowed(g.console) && vm.session != null) {
                        PocketButton({ onDismiss(); actions.cheats(g) }) { Icon(PocketIcons.Code, null, Modifier.size(20.dp)); Text("Cheats…") }
                    }
                    if (vm.hasFavourites && !vm.offline) {
                        PocketButton({ vm.setFavourite(g, !g.favourite) }) {
                            Icon(if (g.favourite) Icons.Filled.Star else PocketIcons.StarOutline, null, Modifier.size(20.dp))
                            Text(if (g.favourite) "Unstar" else "Star")
                        }
                    }
                    if (downloaded && LinkRules.supports(g.console)) {
                        // GBA's Link play also offers the internet and Celio Link (D176, D177); DS is Wi-Fi only.
                        val linkLabel = if (g.console == Console.GBA) "Link play" else "Link play (same Wi-Fi)"
                        PocketButton({ onDismiss(); vm.linkGame = g }, enabled = vm.playing == null) { Text(linkLabel) }
                    }
                    if (downloaded) {
                        PocketButton({ vm.uploadSave(g) }) { Text("Upload save to Jellyfin") }
                        PocketButton({ onDismiss(); actions.getSave(g) }) { Text("Get save from Jellyfin") }
                        PocketButton({ vm.deleteDownload(g) }) { Text(PcWords.of("Remove from phone (keeps save)")) }
                    }
                }
                if (g.id in vm.conflicts) {
                    PocketMini({ onDismiss(); vm.askConflict(g) }, Modifier.padding(top = 12.dp), danger = true) { Text("⚠️ PC and Jellyfin saves differ: choose") }
                } else if (g.id in vm.waitingIds) {
                    PocketNote("Save waiting to go up to Jellyfin", Modifier.padding(top = 12.dp))
                }
                if (NetworkAdapter.applies(g.console)) NetworkAdapterRow(g)
                // .facts: the plugin's labels for what the app knows about the game.
                Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    listOfNotNull(
                        "Console" to consoleName(g),
                        g.year?.let { "Year" to "$it" },
                        vm.lastPlayed(g)?.let { "Last played" to Ago.of(it) },
                    ).forEach { (k, v) ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(k, style = MaterialTheme.typography.bodyMedium, color = Pocket.PMuted, modifier = Modifier.width(84.dp))
                            Text(v, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

/** The game's cover (cached under art\), or Pocket's no-cover tile in the game's colour. */
@Composable
private fun Cover(game: Game, vm: AppModel, modifier: Modifier = Modifier, small: Boolean = false) {
    var bmp by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(game.id, game.imageTag, vm.artVersion) {
        bmp = withContext(Dispatchers.IO) { vm.art.load(game) }
    }
    PocketArt(game.id, game.name, game.console?.shortName, bmp, modifier, small)
}

/**
 * A newer Windows app (S2): the phone's D165 banner, with the plugin's release page for the installer (D181/D199:
 * run the newer Setup.exe over the top; saves and settings stay). **Get it** opens the page in the browser, **Later**
 * hides it until the next start.
 */
@Composable
private fun UpdateBanner(vm: AppModel) {
    val u = vm.appUpdate ?: return
    Row(Modifier.padding(top = 12.dp).fillMaxWidth().background(Pocket.PNote, RoundedCornerShape(Pocket.RS))
        .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Shoalcade ${u.version} for Windows is ready.", Modifier.weight(1f), color = Pocket.Ink,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = Pocket.rem(0.85f)))
        PocketButton(onClick = {
            runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(u.url)) }
                .onFailure { vm.message = "No web browser to open the download page." }
        }, primary = true) { Text("Get it") }
        PocketButton(onClick = { vm.updateLater() }) { Text("Later") }
    }
}

/** P11: GameCube / PS2 "Network adapter" for this game, off by default (D-8), with the plain explanation. */
@Composable
private fun NetworkAdapterRow(g: Game) {
    val ctx = remember { DesktopStorage.context() }
    val na = NetworkAdapter
    var on by remember(g.id) { mutableStateOf(na.isOn(ctx, g.id)) }
    val available = remember(g.id) { na.available(ctx, g.console) }
    Column(Modifier.padding(top = 12.dp).widthIn(max = 460.dp)) {
        PocketToggleRow(na.TITLE, on && available, { v -> na.set(ctx, g.id, v); on = v }, enabled = available)
        Text(if (available) na.HELP else na.PCEE2_NO, style = MaterialTheme.typography.bodySmall, color = Pocket.PMuted)
    }
}
