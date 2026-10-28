package uk.benmarks.shoalcade

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/**
 * D143: what the cheat screen shows. [GameActivity] owns it. [page]: the list ("Edit cheats…") or the form for one
 * cheat ([editing] = its id, null = a new one). [fromList] = the form was opened from the list, so Back goes there.
 */
class CheatEditorState {
    var cheats by mutableStateOf<List<Cheat>>(emptyList())
    var page by mutableStateOf(Page.FORM)
    var editing by mutableStateOf<String?>(null)
    var fromList by mutableStateOf(false)
    /** New each time the form opens, so its fields start again. */
    var formKey by mutableStateOf(0)

    enum class Page { LIST, FORM }

    fun openAdd() { page = Page.FORM; editing = null; fromList = false; formKey++ }
    fun openList() { page = Page.LIST; editing = null; fromList = false }
    fun openEdit(id: String) { page = Page.FORM; editing = id; fromList = true; formKey++ }

    /** Back: from a form opened from the list, back to the list (true); else the screen should close (false). */
    fun back(): Boolean {
        if (page == Page.FORM && fromList) { openList(); return true }
        return false
    }
}

private val INK = Color(0xFF101018)
private val MUTED = Color(0xFFA8A8B4)
private val ERROR = Color(0xFFFF8A80)

/**
 * The cheat screen (D143): a full-screen layer over the paused game. Works by touch, keyboard and controller (the
 * D-pad moves focus, A presses, B / Back goes back). [onSave] returns a plain-English problem, or null when saved.
 */
@Composable
fun CheatEditor(
    state: CheatEditorState, console: Console,
    onSave: (id: String?, name: String, code: String) -> String?,
    onDelete: (id: String) -> Unit,
    onClose: () -> Unit,
) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(Modifier.fillMaxSize().semantics { paneTitle = GameMenu.CHEATS }, color = INK, contentColor = Color.White) {
            Box(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.widthIn(max = 600.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    when (state.page) {
                        CheatEditorState.Page.LIST -> CheatList(state, onClose)
                        CheatEditorState.Page.FORM -> androidx.compose.runtime.key(state.formKey) {
                            CheatForm(state, console, onSave, onDelete, onClose)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CheatList(state: CheatEditorState, onClose: () -> Unit) {
    val first = remember { FocusRequester() }
    Text("Edit cheats", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
    Text("Pick a cheat to rename it, change its code or delete it.", color = MUTED)
    if (state.cheats.isEmpty()) Text("No cheats for this game yet.")
    state.cheats.forEachIndexed { i, c ->
        OutlinedButton(onClick = { state.openEdit(c.id) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).let { if (i == 0) it.focusRequester(first) else it }) {
            Column(Modifier.fillMaxWidth()) {
                Text(c.name, fontWeight = FontWeight.SemiBold)
                Text(c.code.lines().first() + if (c.code.contains('\n')) " …" else "",
                    style = TextStyle(fontFamily = FontFamily.Monospace), color = MUTED, maxLines = 1)
            }
        }
    }
    Button(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .let { if (state.cheats.isEmpty()) it.focusRequester(first) else it }) { Text("Done") }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

@Composable
private fun CheatForm(
    state: CheatEditorState, console: Console,
    onSave: (id: String?, name: String, code: String) -> String?,
    onDelete: (id: String) -> Unit,
    onClose: () -> Unit,
) {
    val editing = state.editing?.let { id -> state.cheats.firstOrNull { it.id == id } }
    var name by remember { mutableStateOf(editing?.name ?: "") }
    var code by remember { mutableStateOf(editing?.code ?: "") }
    var problem by remember { mutableStateOf<String?>(null) }
    var tried by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    val cancel = { if (!state.back()) onClose() }

    Text(if (editing == null) "Add a cheat" else "Edit cheat", style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
    Text(CheatRules.hint(console), color = MUTED)
    OutlinedTextField(
        value = name,
        onValueChange = { v -> name = v.filterNot { it.isISOControl() }.take(CheatRules.MAX_NAME); if (tried) problem = CheatRules.problem(name, code) },
        label = { Text("Name") },
        singleLine = true,
        supportingText = { Text("${name.length} / ${CheatRules.MAX_NAME}") },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = Modifier.fillMaxWidth().focusRequester(first),
    )
    OutlinedTextField(
        value = code,
        // A pasted .cht line can be long; anything far past the limit is cut so the field stays usable.
        onValueChange = { v -> code = v.take(CheatRules.MAX_CODE + 500); if (tried) problem = CheatRules.problem(name, code) },
        label = { Text("Code") },
        minLines = 4,
        textStyle = TextStyle(fontFamily = FontFamily.Monospace),
        supportingText = { Text("One code per line. Letters, numbers, spaces and + - : _ . , only.") },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false,
            keyboardType = KeyboardType.Ascii),
        modifier = Modifier.fillMaxWidth(),
    )
    problem?.let { Text(it, color = ERROR, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
    if (confirmDelete && editing != null) {
        Text("Delete \"${editing.name}\"? This removes it from the game's list everywhere (phone and browser).", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onDelete(editing.id) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC8324B)),
                modifier = Modifier.heightIn(min = 48.dp)) { Text("Delete") }
            OutlinedButton(onClick = { confirmDelete = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Keep it") }
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                tried = true
                problem = CheatRules.problem(name, code) ?: onSave(editing?.id, name, code)
            }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (editing == null) "Add cheat" else "Save cheat") }
            OutlinedButton(onClick = cancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
            if (editing != null) TextButton(onClick = { confirmDelete = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Delete…", color = ERROR)
            }
        }
    }
    if (editing == null) Text("A new cheat starts switched on.", style = MaterialTheme.typography.bodySmall, color = MUTED)
    Text(GameMenu.CHEATS_OFF_NOTE, style = MaterialTheme.typography.bodySmall, color = MUTED)
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}
