package uk.benmarks.shoalcade.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import uk.benmarks.shoalcade.BuildConfig
import uk.benmarks.shoalcade.CelioSession
import uk.benmarks.shoalcade.CelioText
import uk.benmarks.shoalcade.CelioWire
import uk.benmarks.shoalcade.Console
import uk.benmarks.shoalcade.LinkConnection
import uk.benmarks.shoalcade.LinkHandshake
import uk.benmarks.shoalcade.LinkLobby
import uk.benmarks.shoalcade.LinkPeer
import uk.benmarks.shoalcade.LinkPrefs
import uk.benmarks.shoalcade.LinkRules
import uk.benmarks.shoalcade.LobbyState
import uk.benmarks.shoalcade.OkHttpWsConnector
import uk.benmarks.shoalcade.PinGuard
import uk.benmarks.shoalcade.RelayHttp
import uk.benmarks.shoalcade.RelayPipe
import uk.benmarks.shoalcade.RelayRefusedException
import uk.benmarks.shoalcade.RelayWire
import uk.benmarks.shoalcade.WsConnector
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * The PC's link lobby: the phone's [LinkLobby] (Link.kt) step for step, with the same states ([LobbyState]), the same
 * PIN handshake ([LinkHandshake], D133: literally the phone's code), the same relay (D176) and Celio Link (D177) code.
 * Only the Android parts differ: mDNS is [LinkFinder] (JmDNS) instead of NsdManager, there are no Wi-Fi locks, and the
 * connected link becomes a [PcLink] (taken with [take]) because the player is its own program.
 *
 * [LobbyState.Connected] carries the phone's [LinkConnection] (for its isHost / cableMode / peerName); the stream
 * itself is in [take]'s [PcLink].
 */
class PcLinkLobby(
    private val console: Console,
    private val gameName: String,
    private val relay: RelayHttp? = null,
    private val finder: LinkFinder? = JmdnsFinder(),
    /** Where a host listens and is shown (this PC's home network addresses). */
    private val addresses: () -> List<InetAddress> = { PcLinkRules.linkAddresses() },
    /** Dev build: also listen on 127.0.0.1, as the phone's debug build does (two players on one PC, tests). */
    private val alsoLoopback: Boolean = BuildConfig.DEBUG,
    private val deviceName: String = LinkPrefs.deviceName(),
    private val wsConnector: () -> WsConnector = { OkHttpWsConnector() },
    /** The fixed port first (as the phone); tests give 0 so runs never clash. */
    private val port: Int = LinkRules.PORT,
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<LobbyState>(LobbyState.Idle)
    val state: StateFlow<LobbyState> = _state

    private var servers: List<ServerSocket> = emptyList()
    private var shown: Closeable? = null
    private var browsing: Closeable? = null
    private var job: Job? = null
    @Volatile private var guard: PinGuard? = null
    @Volatile private var pipe: RelayPipe? = null
    @Volatile private var celio: CelioSession? = null
    @Volatile private var pending: PcLink? = null
    @Volatile private var handedOver = false

    /** The connected link, once [state] is [LobbyState.Connected]. The caller owns it from now on. */
    fun take(): PcLink? {
        val l = pending ?: return null
        pending = null
        handedOver = true
        return l
    }

    fun host(cableMode: Boolean) {
        stopAll()
        val lan = addresses()
        if (lan.isEmpty() && !alsoLoopback) { fail(NO_NETWORK); return }
        val binds = lan + if (alsoLoopback) listOf(InetAddress.getLoopbackAddress()) else emptyList()
        job = scope.launch {
            try {
                val sockets = bindAll(binds)
                servers = sockets
                val port = sockets.first().localPort
                val shownAddresses = lan.mapNotNull { it.hostAddress }
                val g = PinGuard()
                if (lan.isNotEmpty()) shown = finder?.advertise(lan, "Shoalcade $deviceName", port, gameName, console.name)
                _state.value = LobbyState.Hosting(shownAddresses, port, g.pin)
                val incoming = Channel<Socket>()
                for (ss in sockets) launch {
                    while (true) {
                        val s = try { ss.accept() } catch (e: IOException) { break } // closed: hosting is over
                        try { incoming.send(s) } catch (e: Exception) { runCatching { s.close() }; break }
                    }
                }
                guard = g
                // One try at a time. A player that drops out never stops hosting; 5 wrong PINs pause it (M1).
                while (true) {
                    val s = incoming.receive()
                    val r = try {
                        s.tcpNoDelay = true
                        s.soTimeout = 10_000
                        LinkHandshake.host(s.getInputStream(), s.getOutputStream(), g, console, gameName, deviceName, cableMode)
                    } catch (e: Exception) { LinkHandshake.HostResult.Dropped(e.message ?: "error") }
                    when (r) {
                        is LinkHandshake.HostResult.Linked -> {
                            incoming.close()
                            closeServers()
                            unadvertise()
                            s.soTimeout = 0
                            connected(PcLink(LinkConnection(s, isHost = true, cableMode = cableMode, peerName = r.peerName), PcLink.End.Wifi(s)))
                            return@launch
                        }
                        is LinkHandshake.HostResult.WrongPin -> {
                            runCatching { s.close() }
                            val paused = g.paused // read the guard, not r: Keep hosting may have been tapped meanwhile
                            _state.value = LobbyState.Hosting(shownAddresses, port, g.pin, if (paused) HOST_PAUSED else HOST_WRONG_PIN, paused)
                        }
                        is LinkHandshake.HostResult.Dropped -> { Log.i("link: a joiner was dropped: ${r.why}"); runCatching { s.close() } }
                    }
                }
            } catch (e: Exception) {
                if (!handedOver && _state.value !is LobbyState.Idle && _state.value !is LobbyState.Connected) fail("Hosting stopped: ${e.message}")
            }
        }
    }

    /** After 5 wrong PINs: the host chose to carry on, so make a new PIN and take joins again (M1). */
    fun keepHosting() {
        val g = guard ?: return
        when (val st = _state.value) {
            is LobbyState.Hosting -> { g.resume(); _state.value = st.copy(pin = g.pin, notice = HOST_NEW_PIN, paused = false) }
            is LobbyState.HostingOnline -> { g.resume(); _state.value = st.copy(pin = g.pin, notice = HOST_NEW_PIN, paused = false) }
            else -> {}
        }
    }

    /** Internet link (GBA): a room on Jellyfin, then the same PIN handshake end to end over the relay (D176). */
    fun hostOnline(cableMode: Boolean) {
        val http = relay ?: return
        stopAll()
        job = scope.launch {
            var p: RelayPipe? = null
            try {
                val seat = http.create(console)
                p = RelayPipe(PcRelay.transport(http, seat), seat.gen).also { pipe = it; it.start() }
                val g = PinGuard()
                guard = g
                val room = RelayWire.showCode(seat.code)
                _state.value = LobbyState.HostingOnline(room, g.pin)
                val peer = LinkHandshake.hostOverRelay(p, g, console, gameName, deviceName, cableMode) {
                    val paused = g.paused
                    _state.value = LobbyState.HostingOnline(room, g.pin, if (paused) HOST_PAUSED else HOST_WRONG_PIN, paused)
                }
                pipe = null
                connected(PcLink(LinkConnection.relay(p, isHost = true, cableMode = cableMode, peerName = peer), PcLink.End.Relay(p)))
            } catch (e: Exception) {
                if (pipe === p) { p?.close(); pipe = null }
                if (!isActive) return@launch
                if (!handedOver && _state.value !is LobbyState.Idle && _state.value !is LobbyState.Connected) fail(PcWords.link(e.message ?: RelayWire.ENDED))
            }
        }
    }

    fun joinOnlineForm() {
        stopAll()
        _state.value = LobbyState.JoiningOnline
    }

    fun joinOnline(code: String, pin: String) {
        val http = relay ?: return
        stopAll()
        _state.value = LobbyState.Connecting(RelayWire.showCode(code))
        job = scope.launch {
            var p: RelayPipe? = null
            try {
                val seat = http.join(code, console)
                p = RelayPipe(PcRelay.transport(http, seat), seat.gen).also { pipe = it; it.start() }
                p.readTimeoutMs = LinkLobby.HANDSHAKE_MS
                when (val r = LinkHandshake.join(p.input, p.output, pin, console, gameName, deviceName)) {
                    is LinkHandshake.JoinResult.Linked -> {
                        p.readTimeoutMs = 0
                        pipe = null
                        connected(PcLink(LinkConnection.relay(p, isHost = false, cableMode = r.cableMode, peerName = r.peerName), PcLink.End.Relay(p)))
                    }
                    is LinkHandshake.JoinResult.Refused -> {
                        p.close(); pipe = null
                        _state.value = LobbyState.Failed(PcWords.link(r.reason), retryJoin = true, online = true)
                    }
                }
            } catch (e: Exception) {
                if (pipe === p) { p?.close(); pipe = null }
                if (!isActive) return@launch
                val why = p?.lost ?: e.message ?: RelayWire.ENDED
                if (_state.value !is LobbyState.Idle) _state.value = LobbyState.Failed(PcWords.link(
                    if (e is RelayRefusedException || p?.lost != null) why else "${LinkLobby.JOIN_ONLINE_FAILED} $why"), retryJoin = true, online = true)
            }
        }
    }

    fun celioJoinForm() {
        stopAll()
        _state.value = LobbyState.CelioJoining
    }

    /** Celio Link (GBA, cable mode, no Jellyfin): make a room on [server] ([room] null) or join the PC player's [room]. */
    fun celio(server: String, room: String?) {
        stopAll()
        _state.value = LobbyState.CelioConnecting
        val s = CelioSession(CelioWire.socketUrl(server), wsConnector())
        celio = s
        job = scope.launch {
            s.start(room)
            s.status.collect { st ->
                when (st) {
                    CelioSession.Status.Connecting -> {}
                    is CelioSession.Status.InRoom -> if (st.partner) {
                        celio = null
                        connected(PcLink(LinkConnection.celio(s), PcLink.End.Celio(s)))
                        cancel() // stop watching: the game has the session now
                    } else _state.value = LobbyState.CelioRoom(st.room)
                    is CelioSession.Status.Lost -> {
                        if (celio === s) { s.close(); celio = null }
                        _state.value = LobbyState.Failed(PcWords.link(st.why), retryJoin = room != null, celio = true)
                        cancel()
                    }
                    CelioSession.Status.Finished -> {
                        if (celio === s) { s.close(); celio = null }
                        _state.value = LobbyState.Failed(CelioText.PARTNER_LEFT, celio = true)
                        cancel()
                    }
                }
            }
        }
    }

    /** Join: look for hosts on this network (and a typed address always works). */
    fun search() {
        stopAll()
        _state.value = LobbyState.Searching(emptyList())
        val lan = addresses()
        if (lan.isNotEmpty()) browsing = finder?.browse(lan, console.name) { peers ->
            if (_state.value is LobbyState.Searching) _state.value = LobbyState.Searching(peers.sortedBy { it.name })
        }
    }

    fun join(host: String, port: Int = LinkRules.PORT, pin: String) {
        stopDiscovery()
        _state.value = LobbyState.Connecting(host)
        job = scope.launch {
            val s = Socket()
            try {
                s.connect(InetSocketAddress(host, port), 8_000)
                s.tcpNoDelay = true
                s.soTimeout = 10_000
                when (val r = LinkHandshake.join(s.getInputStream(), s.getOutputStream(), pin, console, gameName, deviceName)) {
                    is LinkHandshake.JoinResult.Linked -> {
                        s.soTimeout = 0
                        connected(PcLink(LinkConnection(s, isHost = false, cableMode = r.cableMode, peerName = r.peerName), PcLink.End.Wifi(s)))
                    }
                    is LinkHandshake.JoinResult.Refused -> {
                        runCatching { s.close() }
                        _state.value = LobbyState.Failed(PcWords.link(r.reason), retryJoin = r.wrongPin)
                    }
                }
            } catch (e: Exception) {
                runCatching { s.close() }
                fail(PcWords.link("Couldn't connect to $host: ${e.message}"))
            }
        }
    }

    /** The dialog closed. A link not taken by a game is ended. */
    override fun close() {
        stopAll()
        scope.cancel()
        if (!handedOver) { pending?.close(); pending = null }
    }

    private fun connected(l: PcLink) {
        pending = l
        _state.value = LobbyState.Connected(l.connection)
    }

    private fun fail(reason: String) { _state.value = LobbyState.Failed(reason) }

    private fun stopAll() {
        job?.cancel()
        job = null
        guard = null
        pipe?.close() // a room not handed to a game: leave it
        pipe = null
        celio?.close()
        celio = null
        closeServers()
        unadvertise()
        stopDiscovery()
    }

    private fun closeServers() {
        servers.forEach { runCatching { it.close() } }
        servers = emptyList()
    }

    private fun unadvertise() { shown?.let { runCatching { it.close() } }; shown = null }
    private fun stopDiscovery() { browsing?.let { runCatching { it.close() } }; browsing = null }

    /** Same port on every address: the fixed one (as the phone), else any free one. */
    private fun bindAll(addrs: List<InetAddress>): List<ServerSocket> {
        fun bindOrNull(a: InetAddress, port: Int): ServerSocket? {
            val ss = ServerSocket().apply { reuseAddress = true }
            return try { ss.bind(InetSocketAddress(a, port)); ss } catch (e: IOException) { runCatching { ss.close() }; null }
        }
        if (port != 0) {
            val fixed = addrs.map { bindOrNull(it, port) }
            if (fixed.all { it != null }) return fixed.filterNotNull()
            fixed.forEach { runCatching { it?.close() } }
        }
        val first = bindOrNull(addrs.first(), 0) ?: throw IOException("no free port")
        return listOf(first) + addrs.drop(1).mapNotNull { bindOrNull(it, first.localPort) }
    }

    companion object {
        const val NO_NETWORK = "Connect this PC to the same Wi-Fi or home network as the other player, then try again."
        val HOST_WRONG_PIN = LinkLobby.HOST_WRONG_PIN
        val HOST_PAUSED = PcWords.link(LinkLobby.HOST_PAUSED)
        val HOST_NEW_PIN = LinkLobby.HOST_NEW_PIN
    }
}
