package uk.benmarks.shoalcade

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min

/** What the drawer shows. GameActivity owns it and changes it; the drawer view is GONE while [open] is false. */
class DrawerState {
    var title by mutableStateOf("")
    var sections by mutableStateOf<List<ShownSection>>(emptyList())
    /** The controller's highlight ([DrawerNav.focus]); only drawn once a controller has moved it ([showFocus]). */
    var focusId by mutableStateOf<String?>(null)
    var showFocus by mutableStateOf(false)
    /** New each time the drawer opens, so it slides in every time. */
    var slide by mutableStateOf(MutableTransitionState(false))
    var open = false
        private set

    fun show() { open = true; slide = MutableTransitionState(false).apply { targetState = true } }
    fun hide() { open = false; showFocus = false }
}

/** One line of the drawer's list: a section title or one of its items. */
private sealed class Line {
    data class Title(val text: String) : Line()
    data class Entry(val item: MenuItem) : Line()
}

private val PANEL = Color(0xFF101018)
private val ROW = Color(0xFF181822)
private val FOCUS = Color(0xFF78C8FF)
private val MUTED = Color(0xFFA8A8B4)

/**
 * The side drawer (D129): slides in from the left over the game, a scrim over the rest closes it. Rows are at least
 * 48 dp tall, each with a real label and role for TalkBack (buttons, radio buttons, switches, headings).
 */
@Composable
fun GameDrawer(state: DrawerState, onPick: (String) -> Unit, onClose: () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val panelWidth = min(340.dp, maxWidth * 0.85f)
            // Scrim: a tap anywhere beside the panel closes the menu.
            AnimatedVisibility(visibleState = state.slide, enter = fadeIn(tween(180))) {
                Box(
                    Modifier.fillMaxSize().background(Color(0x99000000))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                            onClickLabel = GameMenu.CLOSE_MENU) { onClose() }
                        .semantics { contentDescription = GameMenu.CLOSE_MENU; role = Role.Button }
                )
            }
            AnimatedVisibility(visibleState = state.slide, enter = slideInHorizontally(tween(200)) { -it }) {
                Surface(
                    Modifier.width(panelWidth).fillMaxHeight().semantics { paneTitle = GameMenu.TITLE },
                    color = PANEL, contentColor = Color.White,
                ) {
                    DrawerPanel(state, onPick, onClose)
                }
            }
        }
    }
}

@Composable
private fun DrawerPanel(state: DrawerState, onPick: (String) -> Unit, onClose: () -> Unit) {
    val lines = state.sections.flatMap { s -> listOf<Line>(Line.Title(s.title)) + s.items.map { Line.Entry(it) } }
    val list = rememberLazyListState()
    // Keep the controller's highlight on screen.
    LaunchedEffect(state.focusId, state.showFocus) {
        if (!state.showFocus) return@LaunchedEffect
        val i = lines.indexOfFirst { it is Line.Entry && it.item.id == state.focusId }
        if (i >= 0) list.animateScrollToItem((i - 1).coerceAtLeast(0))
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(state.title.ifBlank { GameMenu.TITLE }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).semantics { heading() })
            TextButton(onClick = onClose, modifier = Modifier.size(48.dp).semantics { contentDescription = GameMenu.CLOSE_MENU }) {
                // Step 10: TalkBack says only "Close menu", not the ✕ symbol as well.
                Text("✕", style = MaterialTheme.typography.titleLarge, color = Color.White, modifier = Modifier.clearAndSetSemantics {})
            }
        }
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            itemsIndexed(lines, key = { i, l -> if (l is Line.Entry) l.item.id else "t$i" }) { _, line ->
                when (line) {
                    is Line.Title -> Text(line.text.uppercase(), style = MaterialTheme.typography.labelLarge, color = FOCUS,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 2.dp).semantics { heading() })
                    is Line.Entry -> DrawerRow(line.item, state.showFocus && state.focusId == line.item.id) { onPick(line.item.id) }
                }
            }
        }
    }
}

/** One menu row. Also used by the Settings screen (D132), where a controller's D-pad moves Compose's own focus. */
@Composable
internal fun DrawerRow(item: MenuItem, focused: Boolean, onClick: () -> Unit) {
    if (item is MenuItem.Heading) {
        Text(item.label, style = MaterialTheme.typography.titleSmall, color = MUTED,
            modifier = Modifier.padding(start = 4.dp, top = 6.dp).semantics { heading() })
        return
    }
    val interaction = remember { MutableInteractionSource() }
    val lit = focused || interaction.collectIsFocusedAsState().value
    val shape = RoundedCornerShape(10.dp)
    val base = Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .background(if (lit) Color(0xFF203448) else ROW, shape)
        .border(if (lit) 2.dp else 1.dp, if (lit) FOCUS else Color(0xFF2C2C3A), shape)
    val ripple = LocalIndication.current
    val act = when (item) {
        is MenuItem.Choice -> base.selectable(selected = item.selected, interactionSource = interaction, indication = ripple,
            role = Role.RadioButton, onClick = onClick)
        is MenuItem.Toggle -> base.toggleable(value = item.on, interactionSource = interaction, indication = ripple,
            role = Role.Switch, onValueChange = { onClick() })
        else -> base.clickable(interactionSource = interaction, indication = ripple, role = Role.Button, onClick = onClick)
    }
    Row(act.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        when (item) {
            is MenuItem.Choice -> { RadioButton(selected = item.selected, onClick = null); Spacer(Modifier.width(10.dp)) }
            else -> {}
        }
        Column(Modifier.weight(1f)) {
            Text(item.label, style = MaterialTheme.typography.bodyLarge)
            if (item is MenuItem.Action && item.hint != null) {
                Text(item.hint, style = MaterialTheme.typography.bodySmall, color = MUTED)
            }
        }
        if (item is MenuItem.Toggle) Switch(checked = item.on, onCheckedChange = null)
    }
}
