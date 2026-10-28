package uk.benmarks.shoalcade

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** The controller the Controls screen is changing: [key] = [controllerKey] (one map per controller model). */
data class ControllerInfo(val key: String, val name: String)

/** What the Controls screen shows. GameActivity owns it and changes it as controller events arrive. */
class ControlsState(val console: Console) {
    var device by mutableStateOf<ControllerInfo?>(null)
    var map by mutableStateOf(ControllerMap.defaults(console))
    /** The game button waiting for a controller press, if any. */
    var listening by mutableStateOf<GameButton?>(null)
    /** Last result line, e.g. "A is now Button B on this controller." */
    var note by mutableStateOf<String?>(null)
    /** Controllers connected now, to pick from (Settings, D132). Empty in a game: the last one used is shown there. */
    var connected by mutableStateOf<List<ControllerInfo>>(emptyList())
}

/**
 * The Controls screen's steps (D126), shared by the in-game screen ([GameActivity]) and Settings ([ControlsActivity],
 * D132), so both change the same saved maps in the same way. Plain Kotlin: timers come from [later] (runs a block
 * after a delay and returns a function that cancels it); [changed] tells the game a saved map changed.
 */
class ControlsFlow(
    val state: ControlsState,
    private val prefs: ControlPrefs,
    private val later: (Long, () -> Unit) -> () -> Unit,
    private val changed: (String, ControllerMap) -> Unit = { _, _ -> },
) {
    private val console get() = state.console
    private var listener: BindListener? = null
    private var cancelTimeout: (() -> Unit)? = null

    /** What the activity should do with a controller key. */
    enum class KeyResult { HANDLED, BACK, PASS_ON }

    /** The screen opens on [c] (or "No controller found" if null). */
    fun open(c: ControllerInfo?) {
        stopListening()
        state.note = null
        if (c != null) show(c) else { state.device = null; state.map = ControllerMap.defaults(console) }
    }

    fun show(c: ControllerInfo) {
        state.device = c
        state.map = prefs.map(console, c.key)
    }

    /** Waits for a press for [b]; [resting] = where the controller's axes sit now. */
    fun startListening(b: GameButton, resting: Map<Int, Float>) {
        if (state.device == null) return
        stopListening()
        state.note = null
        state.listening = b
        listener = BindListener(resting)
        cancelTimeout = later(BindListener.TIMEOUT_MS) { stopListening(); state.note = ControlsWords.NOTHING_PRESSED }
    }

    fun stopListening() {
        cancelTimeout?.invoke()
        cancelTimeout = null
        listener = null
        state.listening = null
    }

    /** Back: true = it only cancelled listening; false = the screen should close. */
    fun back(): Boolean {
        if (state.listening == null) return false
        stopListening(); state.note = null
        return true
    }

    /** A key from controller [info]. Not listening: Home / Back go back, other keys move around the screen. */
    fun key(info: ControllerInfo, code: Int, down: Boolean, repeat: Int): KeyResult {
        val l = listener
        if (l == null || state.listening == null) {
            if (state.device == null && down) show(info)
            if (MenuKeys.isMenu(code)) return if (down && repeat == 0) KeyResult.BACK else KeyResult.HANDLED
            return KeyResult.PASS_ON
        }
        result(info, l.key(code, down, repeat))
        return KeyResult.HANDLED
    }

    /** Axis values from controller [info]; [moved] = a real movement (see [AxisRest]). */
    fun motion(info: ControllerInfo, values: Map<Int, Float>, moved: Boolean) {
        val l = listener
        if (state.listening != null && l != null) result(info, l.motion(values))
        else if (state.device == null && moved) show(info)
    }

    private fun result(info: ControllerInfo, r: BindListener.Result) {
        val b = state.listening ?: return
        when (r) {
            BindListener.Result.Waiting -> return
            BindListener.Result.Cancelled -> { stopListening(); state.note = null }
            is BindListener.Result.Bound -> {
                stopListening()
                // The press decides which controller is being changed (it may not be the one shown).
                if (state.device?.key != info.key) show(info)
                val res = state.map.assign(b, r.input)
                if (res.refused) return
                state.map = res.map
                prefs.setMap(info.key, res.map)
                changed(info.key, res.map)
                state.note = ControlsWords.isNow(b.labelFor(console), r.input.name, res.takenFrom.map { it.labelFor(console) })
            }
        }
    }

    fun reset() {
        val c = state.device ?: return
        stopListening()
        prefs.reset(console, c.key)
        state.map = ControllerMap.defaults(console)
        changed(c.key, state.map)
        state.note = ControlsWords.RESET_DONE
    }
}

object ControlsWords {
    /** Step 10: the same words as the menu row that opens this screen (it sits under a "Controls" title). */
    const val TITLE = GameMenu.CONTROLLER_BUTTONS
    const val DONE = "Done"
    const val NO_CONTROLLER = "No controller found"
    const val NO_CONTROLLER_HELP = "Connect a controller and press any button on it. Each controller model gets its own buttons."
    const val LISTEN = "Press a button on your controller…"
    const val LISTEN_HELP = "Back cancels."
    const val RESET = "Reset this controller to defaults"
    const val FINE = "Player 1 only. Tap a game button, then press the controller button you want for it. " +
        "The Home button and Back always open the game menu, so they can't be changed."
    const val NOTHING_PRESSED = "Nothing pressed, so nothing changed."
    const val RESET_DONE = "This controller is back to the default buttons."
    const val WHICH = "Which controller?"
    fun forGames(console: Console) = "Buttons for ${console.tag} games, saved on this phone just for this controller model."
    fun isNow(button: String, input: String, taken: List<String>) = "$button is now $input on this controller." +
        if (taken.isEmpty()) "" else " Taken from ${taken.joinToString(", ")}."
}

@Composable
fun ControlsScreen(
    state: ControlsState, onPick: (GameButton) -> Unit, onReset: () -> Unit, onDone: () -> Unit,
    title: String = ControlsWords.TITLE,
    /** Settings (D132): pick one of [ControlsState.connected]. Null in a game. */
    onChoose: ((ControllerInfo) -> Unit)? = null,
) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF101018), contentColor = Color.White) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f).semantics { heading() })
                    Button(onClick = onDone, modifier = Modifier.heightIn(min = 48.dp)) { Text(ControlsWords.DONE) }
                }

                val device = state.device
                Column(
                    Modifier.fillMaxWidth().background(Color(0xFF1E1E2A), RoundedCornerShape(12.dp)).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("🎮 " + (device?.name ?: ControlsWords.NO_CONTROLLER), fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium)
                    Text(if (device == null) ControlsWords.NO_CONTROLLER_HELP else ControlsWords.forGames(state.console),
                        style = MaterialTheme.typography.bodyMedium, color = Color(0xFFC8C8D4))
                }

                // Settings: more than one controller connected, so say which one is being changed.
                if (onChoose != null && state.connected.size > 1) {
                    Text(ControlsWords.WHICH, style = MaterialTheme.typography.titleSmall, color = Color(0xFFC8C8D4),
                        modifier = Modifier.semantics { heading() })
                    for (c in state.connected) {
                        val on = c.key == device?.key
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .background(Color(0xFF181822), RoundedCornerShape(10.dp))
                                .border(1.dp, if (on) Color(0xFF78C8FF) else Color(0xFF2C2C3A), RoundedCornerShape(10.dp))
                                .selectable(selected = on, role = Role.RadioButton, onClick = { onChoose(c) })
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = on, onClick = null)
                            Spacer(Modifier.width(10.dp))
                            Text(c.name, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }

                val listening = state.listening
                val banner = when {
                    listening != null -> "${listening.labelFor(state.console)}: ${ControlsWords.LISTEN} ${ControlsWords.LISTEN_HELP}"
                    else -> state.note
                }
                // Always two lines tall, so the rows never jump while you're tapping them.
                Text(banner ?: "", style = MaterialTheme.typography.bodyLarge, minLines = 2,
                    color = if (listening != null) Color(0xFF9AD0FF) else Color(0xFFE6E6EE),
                    modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite })

                for (b in state.map.buttons) {
                    val label = b.labelFor(state.console)
                    val now = listening == b
                    val value = if (now) ControlsWords.LISTEN else state.map.describe(b)
                    val enabled = device != null
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 64.dp)
                            .background(if (now) Color(0xFF203448) else Color(0xFF181822), RoundedCornerShape(10.dp))
                            .border(1.dp, if (now) Color(0xFF78C8FF) else Color(0xFF2C2C3A), RoundedCornerShape(10.dp))
                            .clickable(enabled = enabled) { onPick(b) }
                            .clearAndSetSemantics {
                                role = Role.Button
                                contentDescription = if (now) "$label. ${ControlsWords.LISTEN}"
                                    else "$label: $value. " + if (enabled) "Tap to change." else ControlsWords.NO_CONTROLLER
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(96.dp),
                            color = if (enabled) Color.White else Color(0xFF8A8A96))
                        Text(value, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f),
                            color = when {
                                now -> Color(0xFF9AD0FF)
                                value == ControllerMap.NOT_SET -> Color(0xFFFFB86C)
                                enabled -> Color(0xFFE6E6EE)
                                else -> Color(0xFF8A8A96)
                            })
                    }
                }

                Text(ControlsWords.FINE, style = MaterialTheme.typography.bodySmall, color = Color(0xFFA8A8B4))
                OutlinedButton(
                    onClick = onReset,
                    enabled = device != null && !state.map.isDefault,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) { Text(ControlsWords.RESET) }
                Spacer(Modifier.heightIn(min = 24.dp))
            }
        }
    }
}
