package uk.benmarks.shoalcade.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import uk.benmarks.shoalcade.desktop.AppModel
import uk.benmarks.shoalcade.desktop.PlaySession

/**
 * The library window while a game is open in the player's own window: the loading bar until the first picture
 * (PROTOCOL.md `firstFrame`), then the two ways out with the phone's words, Close Game and Close Game (and Upload),
 * and what the game said (auto upload results, a refused save…).
 */
@Composable
fun PlayingScreen(vm: AppModel, p: AppModel.Playing) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(p.game.name, style = MaterialTheme.typography.headlineSmall)
        when (p.phase) {
            PlaySession.Phase.LOADING -> {
                Text("Loading…", modifier = Modifier.padding(top = 12.dp))
                LinearProgressIndicator(Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(top = 8.dp))
            }
            PlaySession.Phase.PLAYING -> Text("Playing in its own window.", modifier = Modifier.padding(top = 12.dp))
            PlaySession.Phase.CLOSING, PlaySession.Phase.ENDED -> {
                Text("Closing the game and keeping your save…", modifier = Modifier.padding(top = 12.dp))
                LinearProgressIndicator(Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(top = 8.dp))
            }
        }
        val open = p.phase == PlaySession.Phase.PLAYING || p.phase == PlaySession.Phase.LOADING
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 20.dp)) {
            OutlinedButton(onClick = { vm.closeGame(upload = false) }, enabled = open) { Text("Close Game") }
            Button(onClick = { vm.closeGame(upload = true) }, enabled = open) { Text("Close Game (and Upload)") }
        }
        p.notes.takeLast(4).forEach { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp).widthIn(max = 640.dp)) }
    }
}
