package uk.benmarks.shoalcade

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics

/**
 * Every dialog's title (TalkBack pass, 02/11/2026): the same words as before, marked as a heading, so TalkBack says
 * "heading" and jumping by headings lands on it, as the Settings and menu headings already do.
 */
@Composable
internal fun DialogTitle(text: String) = Text(text, Modifier.semantics { heading() })

/** The game row's ⋮ button, spoken with the game's name, so it is clear when TalkBack jumps straight to it. */
internal fun moreLabel(game: Game) = "More for ${game.name}"
