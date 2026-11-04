package uk.benmarks.shoalcade

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * D248 Continue: a card at the top of the games list with the game last played that is downloaded on this phone, so
 * it works offline. One tap goes through the normal Play path (the launch choice when there is a snapshot, then the
 * game's own save checks), never round them. The switch is kept on the phone in the plain SharedPreferences file
 * `display` (key `continue`, no secrets), like Haptics.
 */
internal object ContinueGame {
    private const val KEY = "continue"
    const val TITLE = "Continue"

    /** On unless switched off; anything but "0" in the file reads as on. */
    fun on(store: SyncStore): Boolean = store.get(KEY) != "0"
    fun setOn(store: SyncStore, on: Boolean) = store.set(KEY, if (on) null else "0")

    /** What TalkBack says for the card. */
    fun description(g: Game) = "$TITLE ${g.name}"

    /**
     * The game for the card, or null for no card. The rule: hidden while searching (the person is looking for
     * something else); otherwise the most recently played game ([played], the same times as the "Last played" sort)
     * that is downloaded here ([onPhone]) and the phone can play. The console chips and toggles don't hide it.
     */
    fun pick(games: List<Game>, onPhone: Set<String>, search: String, played: (Game) -> Long?): Game? {
        if (search.isNotBlank()) return null
        return games.asSequence()
            .filter { it.id in onPhone && it.console?.core != null && it.console?.isWindowsOnly != true }
            .mapNotNull { g -> played(g)?.let { g to it } }
            .maxByOrNull { it.second }?.first
    }
}

/** The Continue card: box art, "Continue" and the game's name; a tap plays it ([onPlay] = the list's own Play). */
@Composable
internal fun ContinueCard(g: Game, artVersion: Int, onPlay: () -> Unit) {
    Card(
        onClick = onPlay,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).heightIn(min = 48.dp)
            .semantics { contentDescription = ContinueGame.description(g) },
    ) {
        Row(
            Modifier.padding(12.dp).clearAndSetSemantics {}, // the card says it all in one line for TalkBack
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BoxArtImage(g, artVersion)
            Column(Modifier.weight(1f)) {
                Text("▶ ${ContinueGame.TITLE}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(g.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                g.console?.let { Text(it.shortName, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
