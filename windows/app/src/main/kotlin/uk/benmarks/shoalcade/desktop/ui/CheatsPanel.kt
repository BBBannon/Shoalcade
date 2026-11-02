package uk.benmarks.shoalcade.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import uk.benmarks.shoalcade.CheatRules
import uk.benmarks.shoalcade.Console
import uk.benmarks.shoalcade.Game
import uk.benmarks.shoalcade.GameMenu
import uk.benmarks.shoalcade.desktop.AppModel
import uk.benmarks.shoalcade.desktop.CheatEditor
import uk.benmarks.shoalcade.desktop.PcCheatHost

/**
 * "Cheats: <game>" (D143, D145): the phone's cheat screen and the browser player's Cheats tab in one Pocket dialog.
 * The game's list with a switch per cheat, Edit and Delete…; under it the form to add a cheat or change one, with the
 * console's code hint (GameCube D-4: Gecko, encrypted Action Replay, AR: in front). Every console but the 3DS
 * (D210). Open from the game sheet, or from the playing screen while the game runs (the game follows at once).
 */
@Composable
fun CheatsPanel(vm: AppModel, game: Game, onDismiss: () -> Unit) {
    val editor = remember(game.id) { vm.cheatEditor(game) }
    if (editor == null) { LaunchedEffect(Unit) { onDismiss() }; return }
    PocketDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(min = 560.dp), maxWidth = 760.dp,
        title = { Text("${GameMenu.CHEATS}: ${game.name}") },
        text = { CheatsView(editor, damaged = (editor.host as? PcCheatHost)?.damaged == true) },
        confirmButton = { PocketButton(onDismiss, primary = true) { Text("Done") } },
    )
}

/** [CheatsPanel]'s inside, on its own so tests can draw and drive it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CheatsView(editor: CheatEditor, damaged: Boolean = false) {
    val host = editor.host
    var book by remember { mutableStateOf(host.book) }
    var said by remember { mutableStateOf(if (damaged) DAMAGED else null) }
    var saidError by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) } // the cheat in the form; null = a new one
    var deleting by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    var tried by remember { mutableStateOf(false) }
    val nameField = remember { FocusRequester() }
    val focus = LocalFocusManager.current

    // Changes made elsewhere (the game's menu, a snapshot, Jellyfin) show at once; Jellyfin's list is asked for on opening.
    DisposableEffect(host) {
        host.onChange = { b -> book = b }
        book = host.book
        host.sync()
        onDispose { host.onChange = null }
    }
    fun answer(a: CheatEditor.Answer) {
        book = host.book
        saidError = !a.ok
        said = a.problem ?: a.said
    }
    fun clearForm() { editing = null; name = ""; code = ""; problem = null; tried = false }
    fun recheck() { if (tried) problem = CheatRules.problem(name, CheatRules.normalise(editor.console, code), editor.console) }

    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val on = book.activeCheats().size
        Text(
            when {
                book.cheats.isEmpty() -> "No cheats for this game yet. Add one below."
                host.playing -> GameMenu.cheatsOn(on) + "."
                else -> GameMenu.cheatsOn(on) + ". They go on when the game starts."
            },
            style = MaterialTheme.typography.bodyMedium, color = Pocket.PMuted,
        )
        if (!host.online) PocketNote("Jellyfin can't be reached, so changes are kept on this PC and sent next time.")
        said?.let { PocketNote(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, error = saidError) }

        if (book.cheats.isNotEmpty()) PocketBox(Modifier.fillMaxWidth(), PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
            book.cheats.forEachIndexed { i, c ->
                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Pocket.PLine))
                Column(Modifier.padding(vertical = 4.dp)) {
                    PocketToggleRow(c.name, book.isOn(c.id), { v -> answer(editor.setOn(c.id, v)) })
                    Text(c.code.lines().first() + if (c.code.contains('\n')) " …" else "", style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace, color = Pocket.PMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (deleting == c.id) {
                        Text("Delete \"${c.name}\"? This removes it from the game's list everywhere (phones, browser and PCs).",
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PocketButton({ deleting = null; if (editing == c.id) clearForm(); answer(editor.delete(c.id)) }, danger = true) { Text("Delete") }
                            PocketButton({ deleting = null }) { Text("Keep it") }
                        }
                    } else {
                        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PocketMini({ editing = c.id; name = c.name; code = c.code; problem = null; tried = false; deleting = null
                                runCatching { nameField.requestFocus() } }, pressed = editing == c.id) { Text("Edit") }
                            PocketMini({ deleting = c.id }, danger = true) { Text("Delete…") }
                        }
                    }
                }
            }
        }

        // The form (the phone's CheatForm; the browser's #cheat-form).
        val editingCheat = editing?.let { book.byId(it) }
        PocketBox(Modifier.fillMaxWidth().padding(top = 4.dp), PaddingValues(14.dp)) {
            PocketSection(if (editingCheat == null) GameMenu.ADD_CHEAT.removeSuffix("…") else "Edit cheat", modifier = Modifier.padding(bottom = 6.dp))
            Text(CheatRules.hint(editor.console), style = MaterialTheme.typography.bodySmall, color = Pocket.PMuted)
            PocketTextField(name, { v -> name = v.filterNot { it.isISOControl() }.take(CheatRules.MAX_NAME); recheck() },
                Modifier.fillMaxWidth().padding(top = 10.dp).focusRequester(nameField),
                label = { Text("Name") }, supportingText = { Text("${name.length} / ${CheatRules.MAX_NAME}") })
            PocketTextField(code, { v -> code = v.take(CheatRules.MAX_CODE + 500); recheck() },
                // Tab and Shift+Tab move on (a code never needs a tab; the keyboard must be able to leave the box).
                Modifier.fillMaxWidth().padding(top = 6.dp).onPreviewKeyEvent { e ->
                    if (e.key != Key.Tab) false
                    else { if (e.type == KeyEventType.KeyDown) focus.moveFocus(if (e.isShiftPressed) FocusDirection.Previous else FocusDirection.Next); true }
                },
                label = { Text("Code") }, singleLine = false, minLines = 4, monospace = true,
                supportingText = { Text(codeNote(editor.console)) })
            problem?.let { PocketNote(it, Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite }, error = true) }
            FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PocketButton({
                    tried = true
                    val a = editor.save(editingCheat?.id, name, code)
                    if (a.ok) { clearForm(); answer(a); runCatching { nameField.requestFocus() } } else problem = a.problem
                }, primary = true) { Text(if (editingCheat == null) "Add cheat" else "Save cheat") }
                if (editingCheat != null) PocketButton({ clearForm() }) { Text("Cancel") }
            }
            if (editingCheat == null) Text("A new cheat starts switched on.", style = MaterialTheme.typography.bodySmall, color = Pocket.PMuted,
                modifier = Modifier.padding(top = 8.dp))
            Text(GameMenu.CHEATS_OFF_NOTE, style = MaterialTheme.typography.bodySmall, color = Pocket.PMuted, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** Under the Code box: the phone's line; PS2 says pnach lines are fine (they're kept as their two blocks). */
internal fun codeNote(console: Console) = if (console == Console.PS2)
    "One code per line. pnach lines (patch=1,EE,…) are kept as their two blocks of 8."
else "One code per line. Letters, numbers, spaces and + - : _ . , only."

/** The phone's words for a cheat file that couldn't be read (GameActivity.loadCheats). */
private const val DAMAGED = "The cheat list on this PC couldn't be read, so it was kept aside and a new list started."
