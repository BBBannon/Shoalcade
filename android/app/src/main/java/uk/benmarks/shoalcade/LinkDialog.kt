package uk.benmarks.shoalcade

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Link two phones on the same Wi-Fi (or a hotspot) for trades and battles. One phone hosts,
 * the other joins; when they connect, both games start linked.
 * GBA can also link over the internet through the person's Jellyfin ([relay]: null when the plugin can't, or offline).
 */
@Composable
fun LinkDialog(game: Game, userId: String, relay: RelayHttp? = null, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val console = game.console!!
    // Internet link is GBA only: DS wireless needs local Wi-Fi's low latency.
    val online = relay != null && console == Console.GBA
    // Celio Link: GBA only (cable trades), and only once switched on in Settings. Needs no Jellyfin login.
    val celioOn = remember { console == Console.GBA && LinkPrefs.celioOn(ctx) }
    var celioRoom by remember { mutableStateOf("") }
    val lobby = remember { LinkLobby(ctx, console, game.name, if (online) relay else null) }
    val state by lobby.state.collectAsState()
    var cable by remember { mutableStateOf(false) }
    var dsMac by remember { mutableStateOf(LinkPrefs.dsMac(ctx) != null) }
    var typed by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var room by remember { mutableStateOf("") }

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
        title = { DialogTitle("Link play") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (problem != null) { Text(problem); return@Column }
                when (val st = state) {
                    LobbyState.Idle -> {
                        Text("Both phones must be on the same Wi-Fi, or one phone's hotspot. Open this game's Link play on both, then:")
                        Text("• One phone taps Host and shows a PIN\n• The other taps Join and types the PIN", Modifier.padding(vertical = 8.dp))
                        if (console == Console.GBA) {
                            CheckRow(cable, "Link cable mode: Ruby/Sapphire with Emerald/FireRed/LeafGreen, and Emerald hacks like Monomyth (host decides)") {
                                cable = it
                            }
                        }
                        if (console == Console.NDS) {
                            CheckRow(dsMac, "Use a different DS wireless ID on this phone") { dsMac = it; LinkPrefs.setDsMacEnabled(ctx, it) }
                            Text(
                                "Only tick this on ONE phone, and only if the phones link but can't see each other in the game. " +
                                    "It changes this phone's DS ID for all DS games, which may stop Pokémon DS saves made before it from loading. " +
                                    "Best on a spare/test phone with no DS saves.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (online) {
                            HorizontalDivider(Modifier.padding(vertical = 8.dp))
                            Text(OnlineLink.ONLINE_INTRO)
                            Text(OnlineLink.ONLINE_SLOWER, style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = { lobby.hostOnline(cable) }, Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(OnlineLink.HOST_ONLINE) }
                            OutlinedButton(onClick = { lobby.joinOnlineForm() }, Modifier.fillMaxWidth()) { Text(OnlineLink.JOIN_ONLINE) }
                        }
                        if (celioOn) {
                            HorizontalDivider(Modifier.padding(vertical = 8.dp))
                            Text(CelioText.TITLE, style = MaterialTheme.typography.titleSmall)
                            Text(CelioText.INTRO)
                            Text(CelioText.SWITCH_HINT, style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = { lobby.celio(LinkPrefs.celioServer(ctx), null) },
                                Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(CelioText.MAKE) }
                            OutlinedButton(onClick = { lobby.celioJoinForm() }, Modifier.fillMaxWidth()) { Text(CelioText.JOIN) }
                        }
                    }
                    LobbyState.CelioConnecting -> Waiting(CelioText.CONNECTING)
                    is LobbyState.CelioRoom -> {
                        Waiting(CelioText.WAITING)
                        BigCode(CelioText.ROOM_FOR_PC, spaced(st.room), CelioText.ROOM_FOR_PC + " " + spaced(st.room))
                        Text(CelioText.ROOM_HELP)
                    }
                    LobbyState.CelioJoining -> {
                        val code = CelioWire.cleanRoom(celioRoom)
                        OutlinedTextField(celioRoom, { celioRoom = CelioWire.typingRoom(it) }, label = { Text(CelioText.ROOM_FROM_PC) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, autoCorrectEnabled = false),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp))
                        if (code == null) Text(CelioText.ROOM_DIGITS, style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { if (code != null) lobby.celio(LinkPrefs.celioServer(ctx), code) }, enabled = code != null,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(CelioText.JOIN_BUTTON) }
                    }
                    is LobbyState.HostingOnline -> if (st.paused) {
                        Text(st.notice ?: LinkLobby.HOST_PAUSED, color = MaterialTheme.colorScheme.error)
                    } else {
                        Waiting("Waiting for the other phone…")
                        BigCode(OnlineLink.ROOM_CODE, st.room, OnlineLink.ROOM_CODE + " " + spaced(st.room.filter { it != ' ' }))
                        BigCode(PIN_FOR_OTHER, spaced(st.pin), "PIN " + spaced(st.pin), top = 8.dp)
                        Text(OnlineLink.HOST_ONLINE_HELP)
                        st.notice?.let { Text(it, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error) }
                        Text(OnlineLink.ONLINE_SLOWER, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                    }
                    LobbyState.JoiningOnline -> {
                        val code = RelayWire.cleanCode(room)
                        OutlinedTextField(room, { room = RelayWire.typingCode(it) }, label = { Text(OnlineLink.ROOM_CODE_FROM_HOST) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, capitalization = KeyboardCapitalization.Characters,
                                autoCorrectEnabled = false),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp))
                        PinField(pin) { pin = it }
                        Text(OnlineLink.ONLINE_SLOWER, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { if (code != null) lobby.joinOnline(code, pin) }, enabled = code != null && LinkRules.isPin(pin),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Connect") }
                    }
                    is LobbyState.Hosting -> if (st.paused) {
                        // 5 wrong PINs (M1): nobody can join until the player chooses to carry on.
                        Text(st.notice ?: LinkLobby.HOST_PAUSED, color = MaterialTheme.colorScheme.error)
                    } else {
                        Waiting("Waiting for the other phone…")
                        BigCode(PIN_FOR_OTHER, spaced(st.pin), "PIN " + spaced(st.pin))
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
                        PinField(pin) { pin = it }
                        Spacer(Modifier.height(8.dp))
                        if (st.peers.isEmpty()) Waiting("Looking for a phone that's hosting…")
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
                    is LobbyState.Connecting -> Waiting("Connecting to ${st.to}…")
                    is LobbyState.Connected -> Text("Connected. Starting the game…")
                    is LobbyState.Failed -> LiveText(st.reason, MaterialTheme.colorScheme.error)
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
            val paused = (state as? LobbyState.Hosting)?.paused == true || (state as? LobbyState.HostingOnline)?.paused == true
            if (paused) TextButton(onClick = { lobby.keepHosting() }) { Text("Keep hosting") }
            (state as? LobbyState.Failed)?.takeIf { it.retryJoin }?.let { f ->
                TextButton(onClick = { if (f.celio) lobby.celioJoinForm() else if (f.online) lobby.joinOnlineForm() else lobby.search() }) { Text("Try again") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (state is LobbyState.Failed) "Close" else "Cancel") } },
    )
}

private const val PIN_FOR_OTHER = "PIN for the other phone"

/** "4071" → "4 0 7 1": shown spaced out, and TalkBack reads the digits one by one, not as a number. */
private fun spaced(code: String) = code.toList().joinToString(" ")

/** The whole row is the checkbox, so TalkBack reads its words with it and the label is tappable too. */
@Composable
private fun CheckRow(checked: Boolean, text: String, onChange: (Boolean) -> Unit) = Row(
    Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange),
    verticalAlignment = Alignment.CenterVertically,
) {
    Checkbox(checked, onCheckedChange = null)
    Text(text)
}

/** A code to give the other player: its [label], then the code big; TalkBack says [spoken] instead. */
@Composable
private fun BigCode(label: String, shown: String, spoken: String, top: Dp = 12.dp) {
    Text(label, Modifier.padding(top = top), style = MaterialTheme.typography.labelLarge)
    Text(shown, Modifier.semantics { contentDescription = spoken }, style = MaterialTheme.typography.displaySmall)
}

/** The joining phone's PIN box (digits only, at most 4), and a hint until it holds a whole PIN. */
@Composable
private fun PinField(pin: String, onChange: (String) -> Unit) {
    OutlinedTextField(pin, { onChange(it.filter(Char::isDigit).take(4)) }, label = { Text("PIN from the host phone") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp))
    if (!LinkRules.isPin(pin)) Text("Type the PIN first.", style = MaterialTheme.typography.bodySmall)
}

// Internet link play words (docs/wording.md, "Internet link play (app)").
object OnlineLink {
    const val HOST_ONLINE = "Host over the internet"
    const val JOIN_ONLINE = "Join over the internet"
    const val ONLINE_INTRO = "Or link over the internet, through your Jellyfin: both phones must be logged in to the same Jellyfin."
    const val ONLINE_SLOWER = "Internet link can be slower than Wi-Fi."
    const val ROOM_CODE = "Room code"
    const val ROOM_CODE_FROM_HOST = "Room code from the host phone"
    const val HOST_ONLINE_HELP = "On the other phone, tap Join over the internet and type the room code and this PIN."
}
