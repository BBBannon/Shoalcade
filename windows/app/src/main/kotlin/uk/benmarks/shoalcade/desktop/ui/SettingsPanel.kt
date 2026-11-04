package uk.benmarks.shoalcade.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import uk.benmarks.shoalcade.CelioText
import uk.benmarks.shoalcade.Game
import uk.benmarks.shoalcade.ServerStatus
import uk.benmarks.shoalcade.SettingsModel
import uk.benmarks.shoalcade.desktop.AppModel
import uk.benmarks.shoalcade.desktop.ContinueCard
import uk.benmarks.shoalcade.desktop.DesktopStorage
import uk.benmarks.shoalcade.desktop.N3dsCheckPc
import uk.benmarks.shoalcade.desktop.PadSettings
import uk.benmarks.shoalcade.desktop.PictureSize
import uk.benmarks.shoalcade.desktop.Ps2

/**
 * The tabs of the Settings panel, in rail order. The words are the old Settings menu's (D217 follow-up), shortened
 * only where a rail label must fit: "About Shoalcade" is "About" on the rail and its page keeps the full title.
 */
enum class SettingsTab(val label: String, val icon: ImageVector) {
    ACCOUNT(SettingsModel.ACCOUNT, Icons.Outlined.AccountCircle),
    SAVES("Saves", PocketIcons.Save),
    DISPLAY("Display", PocketIcons.Monitor),
    PS2("PS2", PocketIcons.SportsEsports),
    LINK(CelioText.HEADING, PocketIcons.Link),
    CHECK(N3dsCheckPc.TITLE, PocketIcons.Speed),
    ABOUT("About", Icons.Outlined.Info),
}

/**
 * Settings as the plugin's settings panel (sc.css "settings panel": right side, 440 wide, the dark icon rail of tabs,
 * the head with the title and ✕, the body that scrolls; play.html `#panel`). The library beside it shrinks, as the
 * plugin's stage does (`body.panel-open .stage`). Same items, words and behaviour as the old Settings menu and its
 * dialogs; Esc or ✕ closes it (not while the 3DS check runs, as its dialog couldn't be closed then).
 */
@Composable
fun SettingsPanel(vm: AppModel, connected: Boolean, tab: SettingsTab, onTab: (SettingsTab) -> Unit, onClose: () -> Unit,
                  onOpenSaves: (Game, Boolean) -> Unit, modifier: Modifier = Modifier) {
    val close = { if (!vm.checkRunning) onClose() }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } } // keyboard users land in the panel, Esc works at once
    Row(
        modifier.width(Pocket.PanelW).fillMaxHeight().background(Pocket.PBg)
            .drawBehind { drawRect(Pocket.Ink, size = Size(2.dp.toPx(), size.height)) } // border-left: 2px solid var(--ink)
            .padding(start = 2.dp)
            .onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) { close(); true } else false }
            .focusGroup(),
    ) {
        // .panel-tabs: the rail, full height, ink, 10/6 padding, 4 apart.
        Column(Modifier.width(72.dp).fillMaxHeight().background(Pocket.Ink).padding(horizontal = 6.dp, vertical = 10.dp)
            .semantics { contentDescription = "Settings" }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SettingsTab.entries.forEach { t ->
                PanelTab(t, t == tab, { onTab(t) }, if (t == tab) Modifier.focusRequester(first) else Modifier)
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            // .panel-head: title + sub, ✕ at the end, 2 px ink line under.
            Row(Modifier.fillMaxWidth().drawBehind {
                drawRect(Pocket.Ink, topLeft = Offset(0f, size.height - 2.dp.toPx()), size = Size(size.width, 2.dp.toPx()))
            }.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(SettingsModel.TITLE, color = Pocket.PInk, modifier = Modifier.semantics { heading() },
                        style = TextStyle(fontFamily = Pocket.cond, fontWeight = FontWeight.Bold, fontSize = Pocket.rem(1.2f), lineHeight = Pocket.rem(1.44f)))
                    Text(ServerStatus.label(connected).substringAfter(" — ").replaceFirstChar { it.uppercase() }, color = Pocket.PMuted,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = Pocket.rem(0.85f)), modifier = Modifier.padding(top = 2.dp))
                }
                PocketIconButton(close, "Close", enabled = !vm.checkRunning, tint = Pocket.PInk) { Icon(Icons.Default.Close, null, Modifier.size(20.dp)) }
            }
            // .panel-body
            CompositionLocalProvider(LocalContentColor provides Pocket.PInk) {
                Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp)) {
                    when (tab) {
                        SettingsTab.ACCOUNT -> PanelSection(SettingsModel.ACCOUNT) {
                            Text(ServerStatus.label(connected), style = MaterialTheme.typography.labelLarge)
                            vm.session?.let { s ->
                                Text("${SettingsModel.SERVER}: ${s.serverUrl}", style = MaterialTheme.typography.bodySmall, color = Pocket.PMuted)
                                Text("${SettingsModel.ACCOUNT}: ${s.userName}", style = MaterialTheme.typography.bodySmall, color = Pocket.PMuted)
                            }
                            PocketButton({ onClose(); vm.logout() }, Modifier.padding(top = 8.dp)) { Text(SettingsModel.LOG_OUT) }
                        }
                        SettingsTab.SAVES -> PanelSection("Saves") { SavesSettingsContent(vm, onOpenSaves, onClose) }
                        SettingsTab.DISPLAY -> DisplaySettings(vm)
                        SettingsTab.PS2 -> Ps2Settings()
                        SettingsTab.LINK -> PanelSection(CelioText.HEADING) { LinkSettingsContent() }
                        SettingsTab.CHECK -> PanelSection(N3dsCheckPc.TITLE) { N3dsCheckContent(vm) }
                        SettingsTab.ABOUT -> PanelSection(null) { AboutContent() }
                    }
                }
            }
        }
    }
}

/** .panel-tabs button: icon over a .7rem label; hover = full strength; selected = accent with white (aria-selected). */
@Composable
private fun PanelTab(t: SettingsTab, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val fg = if (selected) Color.White else Pocket.Bg
    Column(
        modifier.fillMaxWidth().alpha(if (selected || hovered) 1f else 0.7f)
            .background(if (selected) Pocket.Accent else Color.Transparent, RoundedCornerShape(Pocket.RM))
            .hoverable(src).clickable(src, PocketIndication, role = Role.Tab, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 2.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(t.icon, null, tint = fg, modifier = Modifier.size(22.dp))
        Text(t.label, color = fg, textAlign = TextAlign.Center,
            style = TextStyle(fontFamily = Pocket.cond, fontWeight = FontWeight.SemiBold, fontSize = Pocket.rem(0.7f), lineHeight = Pocket.rem(0.82f)))
    }
}

/** .ps: one section, 14 above and below, a p-line rule under; h4 = .88rem 650. [title] null = no heading. */
@Composable
private fun PanelSection(title: String?, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (title != null) Text(title, modifier = Modifier.padding(bottom = 4.dp).semantics { heading() },
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = Pocket.rem(0.88f), fontWeight = FontWeight.SemiBold))
        content()
    }
}

/**
 * Display: Picture size; the library's Continue card (D248); the controller's rumble (D246) and shortcuts (D247).
 * All kept on this PC; the controller switches count from the next game started.
 */
@Composable
private fun DisplaySettings(vm: AppModel) {
    val ctx = remember { DesktopStorage.context() }
    var version by remember { mutableIntStateOf(0) }
    PanelSection(PictureSize.TITLE) { PictureSizeRows() }
    PanelSection("Library") {
        PocketToggleRow(ContinueCard.SETTING, vm.showContinue, { vm.chooseShowContinue(it) })
        Text(ContinueCard.HELP, style = MaterialTheme.typography.bodySmall, color = Pocket.PMuted)
    }
    PanelSection(PadSettings.TITLE) {
        val rumble = remember(version) { PadSettings.rumble(ctx) }
        val shortcuts = remember(version) { PadSettings.shortcuts(ctx) }
        PocketToggleRow(PadSettings.RUMBLE, rumble, { PadSettings.setRumble(ctx, it); version++ })
        Text(PadSettings.RUMBLE_HELP, style = MaterialTheme.typography.bodySmall, color = Pocket.PMuted)
        PocketToggleRow(PadSettings.SHORTCUTS, shortcuts, { PadSettings.setShortcuts(ctx, it); version++ })
        Text(PadSettings.SHORTCUTS_LIST, style = MaterialTheme.typography.bodySmall, color = Pocket.PMuted)
    }
}

/** Display → Picture size: one 1×–4× row per console that has it ([PictureSize]), kept on this PC. */
@Composable
private fun PictureSizeRows() {
    val ctx = remember { DesktopStorage.context() }
    var version by remember { mutableIntStateOf(0) }
    PictureSize.CHOICES.forEach { c ->
        val now = remember(version) { PictureSize.get(ctx, c.console) ?: c.default }
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(c.console.shortName, Modifier.width(80.dp), style = MaterialTheme.typography.labelLarge)
            PocketSegmented(PictureSize.SIZES.map { it to PictureSize.label(it) }, now, { PictureSize.set(ctx, c.console, it); version++ },
                Modifier.weight(1f).semantics { contentDescription = "${PictureSize.TITLE}, ${c.console.shortName}" })
        }
    }
    Text(PictureSize.HELP, style = MaterialTheme.typography.bodySmall, color = Pocket.PMuted, modifier = Modifier.padding(top = 4.dp))
}

/**
 * PS2 (plan §7 and §14.1): the PS2 emulator radio (LRPS2, the default, or PCEE2) and the PS2 BIOS file, both kept on
 * this PC only. The BIOS is checked, then copied into the player's system folder; the person's own file is untouched.
 */
@Composable
private fun Ps2Settings() {
    val ctx = remember { DesktopStorage.context() }
    val system = remember { DesktopStorage.paths.systemDir }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    var result by remember { androidx.compose.runtime.mutableStateOf<Pair<String, Boolean>?>(null) } // text, is an error
    PanelSection(Ps2.EMULATOR_TITLE) {
        val now = remember(version) { Ps2.emulator(ctx) }
        PocketSegmented(Ps2.Emulator.entries.map { it to it.label }, now, { Ps2.setEmulator(ctx, it); version++ },
            Modifier.fillMaxWidth().semantics { contentDescription = Ps2.EMULATOR_TITLE })
        Text(Ps2.EMULATOR_HELP, style = MaterialTheme.typography.bodySmall, color = Pocket.PMuted)
    }
    PanelSection("PS2 controls") {
        val on = remember(version) { Ps2.pressureCross(ctx) }
        PocketToggleRow(Ps2.PRESSURE_TITLE, on, { Ps2.setPressureCross(ctx, it); version++ })
        Text(Ps2.PRESSURE_HELP, style = MaterialTheme.typography.bodySmall, color = Pocket.PMuted)
    }
    PanelSection("PS2 BIOS") {
        val status = remember(version) { Ps2.status(ctx, system) }
        Text(status, style = MaterialTheme.typography.labelLarge)
        PocketButton({
            val f = FilePick.open(Ps2.BIOS_BUTTON) ?: return@PocketButton
            scope.launch {
                result = try {
                    val b = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Ps2.install(ctx, system, f) }
                    b.label to false
                } catch (e: java.io.IOException) { (e.message ?: Ps2.BIOS_BAD) to true }
                version++
            }
        }) { Text(Ps2.BIOS_BUTTON) }
        result?.let { (t, bad) -> PocketNote(t, error = bad) }
        Text(Ps2.BIOS_HELP, style = MaterialTheme.typography.bodySmall, color = Pocket.PMuted)
    }
}
