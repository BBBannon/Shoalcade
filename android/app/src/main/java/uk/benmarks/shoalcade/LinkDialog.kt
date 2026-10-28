package uk.benmarks.shoalcade

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * Link two phones on the same Wi-Fi (or a hotspot) for trades and battles. One phone hosts,
 * the other joins; when they connect, both games start linked.
 */
@Composable
fun LinkDialog(game: Game, userId: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val console = game.console!!
    val lobby = remember { LinkLobby(ctx, console, game.name) }
    val state by lobby.state.collectAsState()
    var cable by remember { mutableStateOf(false) }
    var dsMac by remember { mutableStateOf(LinkPrefs.dsMac(ctx) != null) }
    var typed by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }

    // GBA link uses a different emulator (gpSP): only allow it when the save can't be harmed.
    val problem = remember(game.id) {
        if (console != Console.GBA) null else {
            val code = Storage.romFile(game)?.let { runCatching { LinkRules.gbaGameCode(LinkLobby.romHeader(it)) }.getOrNull() }
            LinkRules.gbaSaveProblem(code, Storage.readSave(userId, game)?.size)
        }
    }

    DisposableEffect(Unit) { onDispose { lobby.close() } }
    LaunchedEffect(state) {
        if (state is LobbyState.Connected) {
            lobby.markHandedOver()
            ctx.startActivity(GameActivity.intent(ctx, game, userId, link = true))
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Link play") },
        text = {
            Column {
                if (problem != null) { Text(problem); return@Column }
                when (val st = state) {
                    LobbyState.Idle -> {
                        Text("Both phones must be on the same Wi-Fi, or one phone's hotspot. Open this game's Link play on both, then:")
                        Text("• One phone taps Host and shows a PIN\n• The other taps Join and types the PIN", Modifier.padding(vertical = 8.dp))
                        if (console == Console.GBA) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(cable, { cable = it })
                                Text("Ruby/Sapphire with Emerald/FireRed/LeafGreen (host decides)")
                            }
                        }
                        if (console == Console.NDS) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(dsMac, { dsMac = it; LinkPrefs.setDsMacEnabled(ctx, it) })
                                Text("Use a different DS wireless ID on this phone")
                            }
                            Text(
                                "Only tick this on ONE phone, and only if the phones link but can't see each other in the game. " +
                                    "It changes this phone's DS ID for all DS games, which may stop Pokémon DS saves made before it from loading. " +
                                    "Best on a spare/test phone with no DS saves.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    is LobbyState.Hosting -> if (st.paused) {
                        // 5 wrong PINs (M1): nobody can join until the player chooses to carry on.
                        Text(st.notice ?: LinkLobby.HOST_PAUSED, color = MaterialTheme.colorScheme.error)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text("Waiting for the other phone…", Modifier.padding(start = 12.dp))
                        }
                        Text("PIN for the other phone", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelLarge)
                        // TalkBack reads the digits one by one ("PIN 4 0 7 1"), not as a number.
                        Text(
                            st.pin.toList().joinToString(" "),
                            Modifier.semantics { contentDescription = "PIN " + st.pin.toList().joinToString(" ") },
                            style = MaterialTheme.typography.displaySmall,
                        )
                        Text("On the other phone, tap Join and type this PIN.")
                        st.notice?.let { Text(it, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error) }
                        val port = if (st.port == LinkRules.PORT) "" else ":${st.port}"
                        Text(
                            if (st.addresses.isEmpty()) "Not on Wi-Fi? Connect both phones to the same Wi-Fi or hotspot."
                            else "If it doesn't appear, type this on the other phone: " + st.addresses.joinToString(" or ") { it + port },
                            Modifier.padding(top = 12.dp),
                        )
                    }
                    is LobbyState.Searching -> {
                        val pinOk = LinkRules.isPin(pin)
                        OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(4) }, label = { Text("PIN from the host phone") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, autoCorrectEnabled = false),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp))
                        if (!pinOk) Text("Type the PIN first.", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        if (st.peers.isEmpty()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                Text("Looking for a phone that's hosting…", Modifier.padding(start = 12.dp))
                            }
                        }
                        st.peers.forEach { p ->
                            OutlinedButton(onClick = { lobby.join(p.host, p.port, pin) }, enabled = pinOk, modifier = Modifier.fillMaxWidth()) {
                                Text("${p.name} · ${p.game}")
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(typed, { typed = it.trim() }, label = { Text("Or type the host's address") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                            modifier = Modifier.fillMaxWidth())
                        TextButton(enabled = typed.isNotBlank() && pinOk, onClick = {
                            val (host, port) = LinkRules.parseAddress(typed)
                            lobby.join(host, port, pin)
                        }) { Text("Connect") }
                    }
                    is LobbyState.Connecting -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Connecting to ${st.to}…", Modifier.padding(start = 12.dp))
                    }
                    is LobbyState.Connected -> Text("Connected. Starting the game…")
                    is LobbyState.Failed -> Text(st.reason, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            if (problem == null && state is LobbyState.Idle) {
                Row {
                    TextButton(onClick = { lobby.host(cable) }) { Text("Host") }
                    TextButton(onClick = { lobby.search() }) { Text("Join") }
                }
            }
            // A mistyped PIN: back to the list, keeping what was typed so it can be fixed.
            if ((state as? LobbyState.Hosting)?.paused == true) TextButton(onClick = { lobby.keepHosting() }) { Text("Keep hosting") }
            if ((state as? LobbyState.Failed)?.retryJoin == true) TextButton(onClick = { lobby.search() }) { Text("Try again") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (state is LobbyState.Failed) "Close" else "Cancel") } },
    )
}
