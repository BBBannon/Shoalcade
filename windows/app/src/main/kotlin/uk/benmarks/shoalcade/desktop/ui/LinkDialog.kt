package uk.benmarks.shoalcade.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import uk.benmarks.shoalcade.CelioText
import uk.benmarks.shoalcade.CelioWire
import uk.benmarks.shoalcade.Console
import uk.benmarks.shoalcade.Game
import uk.benmarks.shoalcade.LinkPrefs
import uk.benmarks.shoalcade.LinkRules
import uk.benmarks.shoalcade.LobbyState
import uk.benmarks.shoalcade.RelayWire
import uk.benmarks.shoalcade.desktop.AppModel
import uk.benmarks.shoalcade.desktop.DesktopStorage
import uk.benmarks.shoalcade.desktop.PcLinkLobby
import uk.benmarks.shoalcade.desktop.PcWords

/**
 * Link play (library ⋮ → Link play): the phone's LinkDialog with the same choices, states and order (Wi-Fi Host / Join
 * with a PIN; GBA: internet through Jellyfin, and Celio Link once switched on). The other side can be a phone or a PC:
 * both speak the same protocol (D133). When the link is made the game starts linked.
 */
@Composable
fun LinkDialog(vm: AppModel, game: Game, onDismiss: () -> Unit) {
    val console = game.console!!
    // Internet link is GBA only: DS wireless needs local Wi-Fi's low latency.
    val relay = remember { if (console == Console.GBA) vm.linkRelay() else null }
    val lobby = remember { PcLinkLobby(console, game.name, relay) }
    // GBA link uses a different emulator (gpSP): only allow it when the save can't be harmed.
    val problem = remember(game.id) { vm.linkProblem(game) }
    DisposableEffect(Unit) { onDispose { lobby.close() } }
    LinkDialogView(console, lobby, online = relay != null, problem = problem, onLinked = { vm.startLinked(game, it) }, onDismiss = onDismiss)
}

/** The Link play window for [lobby] (also drawn by the test tool for pictures). [onLinked] gets the link once made. */
@Composable
fun LinkDialogView(console: Console, lobby: PcLinkLobby, online: Boolean, problem: String?, onLinked: (uk.benmarks.shoalcade.desktop.PcLink) -> Unit,
                   onDismiss: () -> Unit) {
    val ctx = remember { DesktopStorage.context() }
    val celioOn = remember { console == Console.GBA && LinkPrefs.celioOn(ctx) }
    val state by lobby.state.collectAsState()
    var cable by remember { mutableStateOf(false) }
    var dsMac by remember { mutableStateOf(LinkPrefs.dsMac(ctx) != null) }
    var typed by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var room by remember { mutableStateOf("") }
    var celioRoom by remember { mutableStateOf("") }

    LaunchedEffect(state) {
        if (state is LobbyState.Connected) lobby.take()?.let(onLinked)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(min = 520.dp),
        title = { Text("Link play") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (problem != null) { Text(problem); return@Column }
                when (val st = state) {
                    LobbyState.Idle -> {
                        Text(LinkText.INTRO)
                        Text(LinkText.STEPS, Modifier.padding(vertical = 8.dp))
                        if (console == Console.GBA) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(cable, { cable = it })
                                Text("Link cable mode: Ruby/Sapphire with Emerald/FireRed/LeafGreen, and Emerald hacks like Monomyth (host decides)")
                            }
                        }
                        if (console == Console.NDS) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(dsMac, { dsMac = it; LinkPrefs.setDsMacEnabled(ctx, it) })
                                Text("Use a different DS wireless ID on this PC")
                            }
                            Text(LinkText.DS_MAC_HINT, style = MaterialTheme.typography.bodySmall)
                        }
                        if (online) {
                            HorizontalDivider(Modifier.padding(vertical = 8.dp))
                            Text(PcWords.link(OnlineLink.ONLINE_INTRO))
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
                        BigDigits(CelioText.ROOM_FOR_PC, st.room)
                        Text(CelioText.ROOM_HELP)
                    }
                    LobbyState.CelioJoining -> {
                        val code = CelioWire.cleanRoom(celioRoom)
                        OutlinedTextField(celioRoom, { celioRoom = CelioWire.typingRoom(it) }, label = { Text(CelioText.ROOM_FROM_PC) },
                            singleLine = true, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp))
                        if (code == null) Text(CelioText.ROOM_DIGITS, style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { if (code != null) lobby.celio(LinkPrefs.celioServer(ctx), code) }, enabled = code != null,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(CelioText.JOIN_BUTTON) }
                    }
                    is LobbyState.HostingOnline -> if (st.paused) {
                        Text(st.notice ?: PcLinkLobby.HOST_PAUSED, color = MaterialTheme.colorScheme.error)
                    } else {
                        Waiting(LinkText.WAITING)
                        Text(OnlineLink.ROOM_CODE, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelLarge)
                        Text(st.room, Modifier.semantics {
                            contentDescription = OnlineLink.ROOM_CODE + " " + st.room.filter { it != ' ' }.toList().joinToString(" ")
                        }, style = MaterialTheme.typography.displaySmall)
                        BigDigits(LinkText.PIN_FOR_OTHER, st.pin, "PIN")
                        Text(PcWords.link(OnlineLink.HOST_ONLINE_HELP))
                        st.notice?.let { Text(it, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error) }
                        Text(OnlineLink.ONLINE_SLOWER, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                    }
                    LobbyState.JoiningOnline -> {
                        val code = RelayWire.cleanCode(room)
                        OutlinedTextField(room, { room = RelayWire.typingCode(it) }, label = { Text(PcWords.link(OnlineLink.ROOM_CODE_FROM_HOST)) },
                            singleLine = true, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp))
                        PinField(pin) { pin = it }
                        Text(OnlineLink.ONLINE_SLOWER, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { if (code != null) lobby.joinOnline(code, pin) }, enabled = code != null && LinkRules.isPin(pin),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Connect") }
                    }
                    is LobbyState.Hosting -> if (st.paused) {
                        // 5 wrong PINs (M1): nobody can join until the host chooses to carry on.
                        Text(st.notice ?: PcLinkLobby.HOST_PAUSED, color = MaterialTheme.colorScheme.error)
                    } else {
                        Waiting(LinkText.WAITING)
                        BigDigits(LinkText.PIN_FOR_OTHER, st.pin, "PIN")
                        Text(LinkText.TYPE_THIS_PIN)
                        st.notice?.let { Text(it, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error) }
                        val port = if (st.port == LinkRules.PORT) "" else ":${st.port}"
                        Text(
                            if (st.addresses.isEmpty()) PcLinkLobby.NO_NETWORK
                            else LinkText.TYPE_ADDRESS + st.addresses.joinToString(" or ") { it + port },
                            Modifier.padding(top = 12.dp),
                        )
                        Text(LinkText.FIREWALL, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                    }
                    is LobbyState.Searching -> {
                        val pinOk = LinkRules.isPin(pin)
                        PinField(pin) { pin = it }
                        Spacer(Modifier.height(8.dp))
                        if (st.peers.isEmpty()) Waiting(LinkText.LOOKING)
                        st.peers.forEach { p ->
                            OutlinedButton(onClick = { lobby.join(p.host, p.port, pin) }, enabled = pinOk, modifier = Modifier.fillMaxWidth()) {
                                Text("${p.name} · ${p.game}")
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(typed, { typed = it.trim() }, label = { Text("Or type the host's address") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth())
                        TextButton(enabled = typed.isNotBlank() && pinOk, onClick = {
                            val (host, port) = LinkRules.parseAddress(typed)
                            lobby.join(host, port, pin)
                        }) { Text("Connect") }
                    }
                    is LobbyState.Connecting -> Waiting("Connecting to ${st.to}…")
                    is LobbyState.Connected -> Text("Connected. Starting the game…")
                    is LobbyState.Failed -> Text(st.reason, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Row {
                if (problem == null && state is LobbyState.Idle) {
                    TextButton(onClick = { lobby.host(cable) }) { Text("Host") }
                    TextButton(onClick = { lobby.search() }) { Text("Join") }
                }
                val paused = (state as? LobbyState.Hosting)?.paused == true || (state as? LobbyState.HostingOnline)?.paused == true
                if (paused) TextButton(onClick = { lobby.keepHosting() }) { Text("Keep hosting") }
                // A mistyped PIN: back to the form, keeping what was typed so it can be fixed.
                (state as? LobbyState.Failed)?.takeIf { it.retryJoin }?.let { f ->
                    TextButton(onClick = { if (f.celio) lobby.celioJoinForm() else if (f.online) lobby.joinOnlineForm() else lobby.search() }) { Text("Try again") }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (state is LobbyState.Failed) "Close" else "Cancel") } },
    )
}

@Composable
private fun Waiting(text: String) = Row(verticalAlignment = Alignment.CenterVertically) {
    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    Text(text, Modifier.padding(start = 12.dp))
}

/** A PIN or room number, big, read digit by digit by a screen reader ("PIN 4 0 7 1"), as on the phone. */
@Composable
private fun BigDigits(label: String, digits: String, spoken: String = label) {
    Text(label, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelLarge)
    Text(digits.toList().joinToString(" "), Modifier.semantics { contentDescription = spoken + " " + digits.toList().joinToString(" ") },
        style = MaterialTheme.typography.displaySmall)
}

@Composable
private fun PinField(pin: String, onChange: (String) -> Unit) {
    OutlinedTextField(pin, { onChange(it.filter(Char::isDigit).take(4)) }, label = { Text(LinkText.PIN_FROM_HOST) },
        singleLine = true, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp))
    if (!LinkRules.isPin(pin)) Text("Type the PIN first.", style = MaterialTheme.typography.bodySmall)
}

/**
 * Settings → Link play (the phone's Settings section): Celio Link (trade with PC players), off by default, per PC,
 * with its server. A third-party service, so it says so.
 */
@Composable
fun LinkSettingsDialog(onDismiss: () -> Unit) {
    val ctx = remember { DesktopStorage.context() }
    var celioOn by remember { mutableStateOf(LinkPrefs.celioOn(ctx)) }
    var server by remember { mutableStateOf(LinkPrefs.celioServer(ctx)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(min = 520.dp),
        title = { Text(CelioText.HEADING) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(CelioText.SWITCH, Modifier.weight(1f))
                    Switch(celioOn, { celioOn = it; LinkPrefs.setCelioOn(ctx, it) })
                }
                Text(CelioText.SWITCH_HINT, style = MaterialTheme.typography.bodySmall)
                if (celioOn) {
                    val problem = CelioWire.serverProblem(server)
                    OutlinedTextField(server, { server = it.trim(); LinkPrefs.setCelioServer(ctx, server) },
                        label = { Text(CelioText.SERVER) }, singleLine = true, isError = problem != null,
                        supportingText = problem?.let { { Text(it) } }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    if (server != CelioWire.DEFAULT_SERVER) {
                        TextButton(onClick = { server = CelioWire.DEFAULT_SERVER; LinkPrefs.setCelioServer(ctx, server) }) { Text(CelioText.USE_PUBLIC) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/** The phone's link words (docs/wording.md "Link play PIN"), for a PC: the other side may be a phone or a PC. */
object LinkText {
    const val INTRO = "Both must be on the same Wi-Fi or home network (a phone's hotspot works too). Open this game's Link play on both, then:"
    const val STEPS = "• One taps Host and shows a PIN\n• The other taps Join and types the PIN"
    const val WAITING = "Waiting for the other player…"
    const val PIN_FOR_OTHER = "PIN for the other player"
    const val PIN_FROM_HOST = "PIN from the host"
    const val TYPE_THIS_PIN = "On the other phone or PC, tap Join and type this PIN."
    const val TYPE_ADDRESS = "If it doesn't appear, type this on the other phone or PC: "
    const val LOOKING = "Looking for a phone or PC that's hosting…"
    const val FIREWALL = "If Windows asks, allow Shoalcade on private networks, so the other player can reach this PC."
    const val DS_MAC_HINT = "Only tick this on ONE of the two, and only if they link but can't see each other in the game. " +
        "It changes this PC's DS ID for all DS games, which may stop Pokémon DS saves made before it from loading. " +
        "Best on a spare/test setup with no DS saves."
}

/**
 * The phone's internet link words (its LinkDialog.kt OnlineLink, docs/wording.md "Internet link play (app)"), copied:
 * that file is Android UI, so it isn't compiled here. Shown through [PcWords.link] where they say "phone".
 */
internal object OnlineLink {
    const val HOST_ONLINE = "Host over the internet"
    const val JOIN_ONLINE = "Join over the internet"
    const val ONLINE_INTRO = "Or link over the internet, through your Jellyfin: both phones must be logged in to the same Jellyfin."
    const val ONLINE_SLOWER = "Internet link can be slower than Wi-Fi."
    const val ROOM_CODE = "Room code"
    const val ROOM_CODE_FROM_HOST = "Room code from the host phone"
    const val HOST_ONLINE_HELP = "On the other phone, tap Join over the internet and type the room code and this PIN."
}
