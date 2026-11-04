package uk.benmarks.shoalcade.desktop.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

/*
 * Pocket's parts (sc.css sections), one composable each. The class each one mirrors is named in its comment, so a
 * change to sc.css can be followed here.
 */

// ---------- focus ring (sc.css :focus-visible { outline: 3px solid var(--focus); outline-offset: 2px }) ----------

/** Every clickable's indication: the 3 dp focus ring, 2 dp outside, while it has keyboard focus. No ripple. */
object PocketIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = Node(interactionSource)
    override fun equals(other: Any?) = other === this
    override fun hashCode() = 7

    /** Like :focus-visible: focus that came with a mouse press shows no ring; focus by keyboard (Tab) does. */
    private class Node(private val source: InteractionSource) : Modifier.Node(), DrawModifierNode {
        private var focused = 0
        private var pressing = 0
        private var byPointer = false
        override fun onAttach() {
            coroutineScope.launch {
                source.interactions.collect {
                    when (it) {
                        is FocusInteraction.Focus -> { focused++; byPointer = pressing > 0 }
                        is FocusInteraction.Unfocus -> focused = (focused - 1).coerceAtLeast(0)
                        is PressInteraction.Press -> { pressing++; if (focused > 0) byPointer = true }
                        is PressInteraction.Release -> pressing = (pressing - 1).coerceAtLeast(0)
                        is PressInteraction.Cancel -> pressing = (pressing - 1).coerceAtLeast(0)
                    }
                    invalidateDraw()
                }
            }
        }
        override fun ContentDrawScope.draw() {
            drawContent()
            if (focused > 0 && !byPointer) focusRing(Pocket.RM)
        }
    }
}

private fun DrawScope.focusRing(radius: Dp) {
    val w = 3.dp.toPx()
    val gap = 2.dp.toPx() + w / 2
    drawRoundRect(Pocket.Focus, topLeft = Offset(-gap, -gap), size = Size(size.width + gap * 2, size.height + gap * 2),
        cornerRadius = CornerRadius(radius.toPx() + gap), style = Stroke(w))
}

/** The same ring for parts that take focus without a click (text fields). */
fun Modifier.pocketFocusRing(focused: Boolean, radius: Dp = Pocket.RM): Modifier =
    drawWithContent { drawContent(); if (focused) focusRing(radius) }

/** .btn:hover { box-shadow: 3px 3px 0 var(--ink) }: a hard offset shadow drawn behind. */
fun Modifier.hardShadow(on: Boolean, by: Dp = 3.dp, radius: Dp = Pocket.RM, colour: Color = Pocket.Ink): Modifier =
    if (!on) this else drawBehind {
        drawRoundRect(colour, topLeft = Offset(by.toPx(), by.toPx()), size = size, cornerRadius = CornerRadius(radius.toPx()))
    }

/** .btn:disabled and the like: 55 % opacity while not [enabled]. */
private fun Modifier.dimmedUnless(enabled: Boolean): Modifier = alpha(if (enabled) 1f else 0.55f)

// ---------- buttons (.btn, .btn.primary, .btn.danger, .mini) ----------

/**
 * .btn: 2 px ink border, raised panel colour, 600 .9rem, at least 40 high; hover adds the hard shadow.
 * [primary] = .btn.primary (accent), [danger] = .btn.danger. Disabled = .btn:disabled (55 % opacity, no shadow).
 */
@Composable
fun PocketButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    danger: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val shape = RoundedCornerShape(Pocket.RM)
    val bg = when { danger -> Pocket.Danger; primary -> Pocket.Accent; else -> Pocket.PRaise }
    val fg = when { danger -> Color.White; primary -> Pocket.AccentInk; else -> Pocket.PInk }
    Row(
        modifier
            .dimmedUnless(enabled)
            .hardShadow(hovered && enabled)
            .defaultMinSize(minHeight = 40.dp)
            .background(bg, shape)
            .border(2.dp, if (danger) Pocket.Danger else Pocket.Ink, shape)
            .hoverable(src, enabled)
            .clickable(src, PocketIndication, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides fg, LocalTextStyle provides MaterialTheme.typography.labelLarge.copy(color = fg)) { content() }
    }
}

/** .mini: a small well-coloured button for row actions; [pressed] = aria-pressed (accent-soft), [danger] = red text. */
@Composable
fun PocketMini(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, pressed: Boolean = false, danger: Boolean = false,
               content: @Composable RowScope.() -> Unit) {
    val shape = RoundedCornerShape(Pocket.RS)
    val fg = if (danger) Pocket.Danger else Pocket.PInk
    Row(
        modifier.dimmedUnless(enabled).defaultMinSize(minHeight = 32.dp)
            .background(if (pressed) Pocket.AccentSoft else Pocket.PWell, shape)
            .clickable(remember { MutableInteractionSource() }, PocketIndication, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides fg, LocalTextStyle provides MaterialTheme.typography.labelMedium.copy(color = fg)) { content() }
    }
}

/** .sheet-x / .panel-head .x: a round icon-only button. [description] is its spoken name. */
@Composable
fun PocketIconButton(onClick: () -> Unit, description: String?, modifier: Modifier = Modifier, enabled: Boolean = true,
                     tint: Color = Pocket.PInk, content: @Composable () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    Box(
        modifier.size(40.dp).dimmedUnless(enabled)
            .background(if (hovered && enabled) Pocket.AccentSoft else Color.Transparent, CircleShape)
            .hoverable(src, enabled)
            .clickable(src, PocketIndication, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { if (description != null) contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { CompositionLocalProvider(LocalContentColor provides tint) { content() } }
}

/** .badge: a small bold label. Default = the LED red "In use"; [pin] = ink "Pinned". */
@Composable
fun PocketBadge(text: String, modifier: Modifier = Modifier, pin: Boolean = false) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = if (pin) Pocket.Bg else Color.White,
        modifier = modifier.background(if (pin) Pocket.Ink else Pocket.Led, RoundedCornerShape(2.dp)).padding(horizontal = 8.dp, vertical = 2.dp))
}

// ---------- controls: segmented, switch, check box ----------

/** .seg: one bordered bar of choices; the picked one is ink with light text. */
@Composable
fun <T> PocketSegmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(Pocket.RM)
    Row(modifier.border(2.dp, Pocket.Ink, shape).background(Color.Transparent, shape)) {
        options.forEachIndexed { i, (value, label) ->
            val on = value == selected
            Box(
                Modifier.weight(1f).defaultMinSize(minHeight = 36.dp).background(if (on) Pocket.Ink else Color.Transparent)
                    .clickable(remember { MutableInteractionSource() }, PocketIndication, role = Role.RadioButton) { onSelect(value) }
                    .semantics { this.selected = on }
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label, style = MaterialTheme.typography.labelLarge.copy(fontSize = Pocket.rem(0.88f)), color = if (on) Pocket.Bg else Pocket.PMuted) }
            if (i < options.lastIndex) Box(Modifier.width(2.dp).defaultMinSize(minHeight = 36.dp).background(Pocket.Ink))
        }
    }
}

/** .tog-ui: the 40×24 switch, LED red when on. Its own clicks; inside [PocketToggleRow] the whole row toggles. */
@Composable
fun PocketSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val x by animateDpAsState(if (checked) 19.dp else 3.dp, if (Pocket.reducedMotion) snap() else tween(150), label = "tog")
    val m = if (onCheckedChange == null) modifier else
        modifier.toggleable(checked, remember { MutableInteractionSource() }, PocketIndication, enabled, Role.Switch, onCheckedChange)
    Box(m.dimmedUnless(enabled).size(40.dp, 24.dp).background(if (checked) Pocket.Led else Pocket.PLine, RoundedCornerShape(12.dp))) {
        Box(Modifier.offset(x = x, y = 3.dp).size(18.dp).drawBehind {
            drawCircle(Color(0x4D000000), center = center.copy(y = center.y + 1.dp.toPx()))
        }.background(Color.White, CircleShape))
    }
}

/** .tog: a label with the switch at its end; the whole row is the control. */
@Composable
fun PocketToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Row(
        modifier.fillMaxWidth().toggleable(checked, remember { MutableInteractionSource() }, PocketIndication, enabled, Role.Switch, onCheckedChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge.copy(fontSize = Pocket.rem(0.92f)), modifier = Modifier.weight(1f))
        PocketSwitch(checked, null, enabled = enabled)
    }
}

/** A check box in Pocket's drawing: 2 px ink square, accent fill and a tick when on. */
@Composable
fun PocketCheckbox(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val m = if (onCheckedChange == null) modifier else
        modifier.toggleable(checked, remember { MutableInteractionSource() }, PocketIndication, enabled, Role.Checkbox, onCheckedChange)
    Box(m.padding(10.dp).dimmedUnless(enabled).size(20.dp)
        .background(if (checked) Pocket.Accent else Pocket.PRaise, RoundedCornerShape(Pocket.RS))
        .border(2.dp, Pocket.Ink, RoundedCornerShape(Pocket.RS)), contentAlignment = Alignment.Center) {
        if (checked) Icon(Icons.Default.Check, null, tint = Pocket.AccentInk, modifier = Modifier.size(16.dp))
    }
}

// ---------- text fields (.ask-field input, .ed-line input) ----------

/**
 * A labelled text box: the label above in 650 .9rem (.ask-field), then a 2 px ink box on the raised colour that turns
 * LED red with the focus ring while typing. [supportingText] sits under it (a count, a hint).
 */
@Composable
fun PocketTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    singleLine: Boolean = true,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    /** A taller box for several lines (a cheat code). */
    minLines: Int = 1,
    /** Codes: a fixed-width face, so 8-character blocks line up. */
    monospace: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Pocket.RM)
    Column(modifier) {
        if (label != null) CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = Pocket.rem(0.9f))) {
            Box(Modifier.padding(bottom = 4.dp)) { label() }
        }
        BasicTextField(
            value, onValueChange, enabled = enabled, singleLine = singleLine, minLines = if (singleLine) 1 else minLines,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Pocket.PInk,
                fontFamily = if (monospace) androidx.compose.ui.text.font.FontFamily.Monospace else MaterialTheme.typography.bodyLarge.fontFamily),
            cursorBrush = SolidColor(Pocket.Ink), keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
            visualTransformation = visualTransformation,
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            decorationBox = { inner ->
                Row(
                    Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.6f).pocketFocusRing(focused)
                        .defaultMinSize(minHeight = 40.dp).background(Pocket.PRaise, shape)
                        .border(2.dp, if (isError) Pocket.Danger else if (focused) Pocket.Led else Pocket.Ink, shape).padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty() && placeholder != null) CompositionLocalProvider(LocalContentColor provides Pocket.PMuted) { placeholder() }
                        inner()
                    }
                    trailing?.invoke()
                }
            },
        )
        if (supportingText != null) CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.bodySmall.copy(color = Pocket.PMuted)) {
            Box(Modifier.padding(top = 4.dp)) { supportingText() }
        }
    }
}

// ---------- notes, boxes ----------

/** .note (the yellow note); [error] = .note.sg-err (pink with a red edge). */
@Composable
fun PocketNote(text: String, modifier: Modifier = Modifier, error: Boolean = false) {
    val shape = RoundedCornerShape(Pocket.RS)
    Text(text, style = MaterialTheme.typography.bodyMedium.copy(fontSize = Pocket.rem(0.85f)), color = Pocket.Ink,
        modifier = modifier.fillMaxWidth().background(if (error) Pocket.ErrorBg else Pocket.PNote, shape).leftEdge(if (error) Pocket.Danger else null)
            .padding(start = if (error) 18.dp else 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp))
}

/** border-left: 6px solid [colour] (.sg-err, .toast). */
fun Modifier.leftEdge(colour: Color?, width: Dp = 6.dp): Modifier =
    if (colour == null) this else drawBehind { drawRect(colour, size = Size(width.toPx(), size.height)) }

/** .sg-box / .set-box / aside.recent: a surface box with the 2 px ink edge. */
@Composable
fun PocketBox(modifier: Modifier = Modifier, padding: PaddingValues = PaddingValues(16.dp), raised: Boolean = false,
              content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(if (raised) Pocket.RM else Pocket.RL)
    Column(modifier.background(if (raised) Pocket.PRaise else Pocket.Surface, shape).border(2.dp, Pocket.Ink, shape).padding(padding), content = content)
}

/** h2.sec: a section heading with an optional muted count after it. */
@Composable
fun PocketSection(title: String, count: String? = null, modifier: Modifier = Modifier) {
    Row(modifier.semantics(mergeDescendants = true) { heading() }, verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (count != null) Text(count, style = MaterialTheme.typography.bodySmall.copy(fontSize = Pocket.rem(0.85f)), color = Pocket.Muted,
            modifier = Modifier.padding(bottom = 1.dp))
    }
}

// ---------- toast (.toast) ----------

/** The app's messages as Pocket's toast: ink, light text, the LED edge, bottom centre. */
@Composable
fun PocketToastHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(state, modifier.padding(bottom = 24.dp)) { data ->
        val shape = RoundedCornerShape(Pocket.RM)
        Text(data.visuals.message, color = Pocket.Bg, style = MaterialTheme.typography.labelLarge.copy(fontSize = Pocket.rem(0.95f)),
            modifier = Modifier.widthIn(max = 720.dp).clip(shape).background(Pocket.Ink).leftEdge(Pocket.Led)
                .padding(start = 22.dp, end = 16.dp, top = 9.dp, bottom = 9.dp))
    }
}

// ---------- dialogs: .ask (questions) and .sheet (the game sheet) ----------

/** Full width under our control, and sc.css's backdrop (rgba(10, 14, 24, .55)). Esc and a click outside close, as before. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
private fun pocketDialogProperties() = DialogProperties(usePlatformDefaultWidth = false, scrimColor = Pocket.Backdrop)

/**
 * .ask: Pocket's in-page dialog. The same slots as Material's AlertDialog, so every question keeps its words and
 * buttons: the title (h2), the text, then the buttons at the end of the row (dismiss first, as .ask-b).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PocketDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    /** Material's AlertDialog never went wider than 560; the saves list and About ask for more. */
    maxWidth: Dp = 560.dp,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    Dialog(onDismissRequest, pocketDialogProperties()) {
        val shape = RoundedCornerShape(Pocket.RL)
        Column(
            Modifier.padding(16.dp).widthIn(min = 280.dp, max = maxWidth).then(modifier).background(Pocket.PBg, shape).border(2.dp, Pocket.Ink, shape)
                .padding(horizontal = 22.dp, vertical = 20.dp),
        ) {
            CompositionLocalProvider(LocalContentColor provides Pocket.PInk) {
                if (title != null) CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.headlineSmall) {
                    Box(Modifier.padding(bottom = 8.dp).semantics { heading() }) { title() }
                }
                if (text != null) CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.bodyLarge) {
                    Box(Modifier.weight(1f, fill = false)) { text() }
                }
                FlowRow(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    dismissButton?.invoke()
                    confirmButton()
                }
            }
        }
    }
}

/** .sheet: the game sheet. A close button in the corner (.sheet-x) and the body under it. */
@Composable
fun PocketSheet(onDismissRequest: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest, pocketDialogProperties()) {
        val shape = RoundedCornerShape(Pocket.RL)
        Box(Modifier.padding(16.dp).widthIn(max = 640.dp).fillMaxWidth().then(modifier).background(Pocket.PBg, shape).border(2.dp, Pocket.Ink, shape)) {
            CompositionLocalProvider(LocalContentColor provides Pocket.PInk) {
                Column(Modifier.fillMaxWidth(), content = content)
                PocketIconButton(onDismissRequest, "Close", Modifier.align(Alignment.TopEnd).padding(6.dp)) {
                    Icon(Icons.Default.Close, null, Modifier.size(20.dp))
                }
            }
        }
    }
}

// ---------- console strip (.strip, .chip, .con-menu) ----------

/**
 * .strip: one bordered bar, chips split by 2 px ink lines. Chips share any spare width (flex: 1 0 auto) and the bar
 * scrolls sideways when they don't fit.
 */
@Composable
fun PocketStrip(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(Pocket.RM)
    BoxWithConstraints(modifier.fillMaxWidth().border(2.dp, Pocket.Ink, shape).padding(2.dp)) {
        val min = constraints.maxWidth
        Box(Modifier.clip(RoundedCornerShape(2.dp)).horizontalScroll(rememberScrollState())) {
            Layout(content) { measurables, c ->
                val natural = measurables.map { it.maxIntrinsicWidth(c.maxHeight) }
                val d = 2.dp.roundToPx()
                val dividers = d * (measurables.size - 1).coerceAtLeast(0)
                val spare = (min - natural.sum() - dividers).coerceAtLeast(0)
                val extra = if (measurables.isEmpty()) 0 else spare / measurables.size
                val widths = natural.map { it + extra }
                val h = measurables.mapIndexed { i, m -> m.minIntrinsicHeight(widths[i]) }.maxOrNull() ?: 0
                val placeables = measurables.mapIndexed { i, m -> m.measure(androidx.compose.ui.unit.Constraints.fixed(widths[i], h)) }
                val total = widths.sum() + dividers
                layout(maxOf(total, min), h) {
                    var x = 0
                    placeables.forEach { p -> p.place(x, 0); x += p.width + d }
                }
            }
        }
    }
}

/** .chip: one console button in the strip; [pressed] = aria-pressed (ink, light text). [count] = .chip-k. */
@Composable
fun PocketChip(label: String, pressed: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, count: Int? = null, menu: Boolean = false,
               last: Boolean = false) {
    val fg = if (pressed) Pocket.Bg else Pocket.Ink
    Row(
        modifier.background(if (pressed) Pocket.Ink else Color.Transparent)
            .drawBehind { if (!last) drawRect(Pocket.Ink, topLeft = Offset(size.width, 0f), size = Size(2.dp.toPx(), size.height)) }
            .clickable(remember { MutableInteractionSource() }, PocketIndication, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.Bottom,
    ) {
        Text(label, color = fg, style = MaterialTheme.typography.labelLarge.copy(fontSize = Pocket.rem(1f)), maxLines = 1, softWrap = false)
        if (count != null) Text("$count", color = fg.copy(alpha = 0.7f).compositeOver(if (pressed) Pocket.Ink else Pocket.Bg),
            style = MaterialTheme.typography.labelLarge.copy(fontSize = Pocket.rem(0.8f)), maxLines = 1, softWrap = false)
        if (menu) Text("▾", color = fg, style = MaterialTheme.typography.labelLarge.copy(fontSize = Pocket.rem(0.8f)))
    }
}

private fun Color.compositeOver(bg: Color) = Color(
    red = red * alpha + bg.red * (1 - alpha), green = green * alpha + bg.green * (1 - alpha), blue = blue * alpha + bg.blue * (1 - alpha))

/** .con-menu: a menu box (surface, 2 px ink edge, 4 px hard shadow). Used inside a DropdownMenu. */
@Composable
fun PocketMenuItem(text: String, onClick: () -> Unit, checked: Boolean = false, trailing: String? = null, enabled: Boolean = true) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val fg = if (checked) Pocket.Bg else Pocket.Ink
    Row(
        Modifier.widthIn(min = 210.dp).fillMaxWidth().padding(horizontal = 4.dp).dimmedUnless(enabled)
            .background(if (checked) Pocket.Ink else if (hovered) Pocket.Line else Color.Transparent, RoundedCornerShape(4.dp))
            .hoverable(src, enabled)
            .clickable(src, PocketIndication, enabled = enabled, role = Role.Button, onClick = onClick)
            .defaultMinSize(minHeight = 44.dp).padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = fg, style = MaterialTheme.typography.labelLarge.copy(fontSize = Pocket.rem(1f)), modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, color = fg, style = MaterialTheme.typography.labelLarge.copy(fontSize = Pocket.rem(0.8f)))
    }
}

// ---------- toolbar rail (.toolbar, .tb-btn) ----------

/** .toolbar: the dark rail down the left edge (64 dp, accent edge). */
@Composable
fun PocketRail(modifier: Modifier = Modifier, top: @Composable ColumnScope.() -> Unit, bottom: @Composable ColumnScope.() -> Unit = {}) {
    Column(
        modifier.width(Pocket.Rail).background(Pocket.Ink).drawBehind {
            drawRect(Pocket.Accent, topLeft = Offset(size.width - 2.dp.toPx(), 0f), size = Size(2.dp.toPx(), size.height))
        }.padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp), content = top)
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp), content = bottom)
    }
}

/** .tb-btn: icon over a short label; hover / [pressed] = accent. [set] = .tb-set (the light settings button). */
@Composable
fun PocketRailButton(icon: ImageVector, label: String, onClick: () -> Unit, enabled: Boolean = true, pressed: Boolean = false, set: Boolean = false) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val bg = when { set -> Pocket.AccentSoft; (hovered && enabled) || pressed -> Pocket.Accent; else -> Color.Transparent }
    val fg = if (set) Pocket.Ink else Pocket.Bg
    Column(
        Modifier.width(56.dp).dimmedUnless(enabled).background(bg, RoundedCornerShape(Pocket.RM))
            .hoverable(src, enabled).clickable(src, PocketIndication, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 7.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp))
        Text(label, color = fg, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            style = TextStyle(fontFamily = Pocket.cond, fontWeight = FontWeight.SemiBold, fontSize = Pocket.rem(0.7f), lineHeight = Pocket.rem(0.8f)))
    }
}

// ---------- save rows (.svs, .sv) ----------

/**
 * .sv: one save version. [heading] = .sv-when (the version and its badges), [label] = .sv-label, [meta] = .sv-meta,
 * [actions] = .sv-acts (mini buttons). [current] = .sv.cur (the accent-soft wash from the left).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PocketSaveRow(heading: @Composable RowScope.() -> Unit, meta: String, modifier: Modifier = Modifier, label: String? = null, current: Boolean = false,
                  actions: @Composable RowScope.() -> Unit = {}) {
    Column(modifier.fillMaxWidth()) {
        FlowRow(
            Modifier.fillMaxWidth()
                .then(if (current) Modifier.background(Brush.horizontalGradient(listOf(Pocket.AccentSoft, Pocket.AccentSoft.copy(alpha = 0f)))) else Modifier)
                .padding(horizontal = if (current) 16.dp else 0.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Column(Modifier.widthIn(min = 160.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = Pocket.rem(0.93f))) { heading() }
                }
                if (label != null) Text(label, style = MaterialTheme.typography.bodyMedium)
                // Ink, not p-muted, on the current row's wash: p-muted on accent-soft is under 4.5:1.
                Text(meta, style = MaterialTheme.typography.bodySmall, color = if (current) Pocket.PInk else Pocket.PMuted)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Pocket.PLine))
    }
}
