package uk.benmarks.shoalcade.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import uk.benmarks.shoalcade.desktop.AppModel
import uk.benmarks.shoalcade.desktop.PlaySession

/**
 * The library window while a game is open in the player's own window, drawn as the plugin's player (play.css): the
 * dark stage and the toolbar rail. The loading bar until the first picture (PROTOCOL.md `firstFrame`), then the two
 * ways out on the rail with the phone's words, Close Game and Close Game (and Upload), and what the game said (auto
 * upload results, a refused save…). Cheats (D143) opens the app's cheat editor on the running game.
 */
@Composable
fun PlayingScreen(vm: AppModel, p: AppModel.Playing) {
    // D143: the game's cheats while it runs (not a link session or the 3DS): the game and its menu follow at once.
    var cheats by remember { mutableStateOf(false) }
    if (cheats) CheatsPanel(vm, p.game) { cheats = false }
    val canCheat = p.session.cheatHost != null && p.phase == PlaySession.Phase.PLAYING
    LaunchedEffect(canCheat) { if (!canCheat) cheats = false }
    PlayingView(p.game.name, p.phase, p.notes.takeLast(4), onCheats = if (p.session.cheatHost != null) ({ cheats = true }) else null) { upload -> vm.closeGame(upload) }
}

/** [PlayingScreen]'s drawing, on its own so the test tool can draw each phase. */
@Composable
fun PlayingView(name: String, phase: PlaySession.Phase, notes: List<String>, onCheats: (() -> Unit)? = null, onClose: (upload: Boolean) -> Unit) {
    val open = phase == PlaySession.Phase.PLAYING || phase == PlaySession.Phase.LOADING
    Row(Modifier.fillMaxSize().background(Pocket.Stage)) {
        PocketRail(Modifier.fillMaxHeight(), top = {
            PocketRailButton(Icons.Default.Close, "Close Game", { onClose(false) }, enabled = open)
            PocketRailButton(Icons.Default.CloudUpload, "Close Game (and Upload)", { onClose(true) }, enabled = open)
            if (onCheats != null) PocketRailButton(Icons.Outlined.Code, "Cheats", onCheats, enabled = phase == PlaySession.Phase.PLAYING)
        })
        Box(Modifier.weight(1f).fillMaxHeight().padding(32.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(name, style = MaterialTheme.typography.headlineMedium, color = Pocket.Surface, textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() })
                val status = when (phase) {
                    PlaySession.Phase.LOADING -> "Loading…"
                    PlaySession.Phase.PLAYING -> "Playing in its own window."
                    PlaySession.Phase.CLOSING, PlaySession.Phase.ENDED -> "Closing the game and keeping your save…"
                }
                Text(status, color = Pocket.AccentSoft, modifier = Modifier.padding(top = 12.dp))
                if (phase != PlaySession.Phase.PLAYING) {
                    LinearProgressIndicator(Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(top = 8.dp), color = Pocket.AccentSoft, trackColor = Pocket.Accent)
                }
                notes.forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Pocket.AccentSoft, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp).widthIn(max = 640.dp))
                }
            }
        }
    }
}
