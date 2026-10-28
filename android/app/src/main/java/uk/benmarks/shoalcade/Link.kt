package uk.benmarks.shoalcade

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket

/** Reads the link status written by the native shim (libsclinkstate.so). */
object LinkNative {
    const val OFF = 0
    const val WAITING = 1
    const val ACTIVE = 2
    const val LOST = 3
    const val REFUSED = 4
    const val NO_SUPPORT = 5

    init { System.loadLibrary("sclinkstate") }
    @JvmStatic external fun state(): Int
    @JvmStatic external fun received(): Long
    @JvmStatic external fun sent(): Long
    /** Clears the last session's status (this library stays loaded between games). */
    @JvmStatic external fun reset()
}

/** Pure rules, unit-tested in LogicTest. */
object LinkRules {
    /** 2 = PIN handshake (D133). An older phone gets a clear "update the app" message. */
    const val PROTOCOL = 2
    const val PORT = 47913
    const val SERVICE_TYPE = "_shoalcade._tcp."
    const val GBA_SAVE_SIZE = 131072
    const val GPSP_CORE = "libgpsp_libretro_android.so"
    const val SHIM = "liblinkshim.so"

    /** Gen 3 Pokémon game codes (ROM header 0xAC). All use 128 KB flash saves. */
    private val POKEMON_GEN3 = setOf("AXV", "AXP", "BPE", "BPR", "BPG")

    fun supports(console: Console?) = console == Console.GBA || console == Console.NDS

    fun gbaGameCode(romHeader: ByteArray): String? =
        if (romHeader.size >= 0xB0) String(romHeader, 0xAC, 4, Charsets.US_ASCII) else null

    /**
     * GBA link uses gpSP, which always writes a 128 KB save. Only allow it when that can't break
     * the save for mGBA afterwards: the save is already 128 KB, or there's no save yet and the game
     * is a Gen 3 Pokémon (which really is 128 KB). Returns null if OK, else the reason.
     */
    fun gbaSaveProblem(gameCode: String?, saveSize: Int?): String? = when {
        saveSize == GBA_SAVE_SIZE -> null
        saveSize == null && gameCode != null && gameCode.take(3) in POKEMON_GEN3 -> null
        saveSize == null -> "Link play for this game needs a save first. Play it normally and save once."
        else -> "This game's save type isn't supported for link play yet (only 128 KB saves, e.g. Pokémon)."
    }

    /**
     * Hello line. The host's carries its fresh [nonce]; the joiner's carries its own nonce and [proof]
     * (HMAC of both nonces with the PIN). The PIN itself is never sent (D133).
     */
    fun hello(console: Console, gameName: String, deviceName: String, cableMode: Boolean, nonce: String, proof: String? = null) = JSONObject()
        .put("app", "Shoalcade").put("v", PROTOCOL).put("console", console.name)
        .put("game", gameName).put("device", deviceName).put("cable", cableMode)
        .put("nonce", nonce).apply { if (proof != null) put("proof", proof) }
        .toString()

    /** Null if the other phone's hello is compatible, else a plain-English reason. */
    fun checkHello(theirs: JSONObject, console: Console): String? = when {
        theirs.optString("app") != "Shoalcade" -> "That isn't a Shoalcade phone."
        theirs.optInt("v") < PROTOCOL -> "The other phone has an older version of Shoalcade. Update the app on the other phone."
        theirs.optInt("v") > PROTOCOL -> "This phone has an older version of Shoalcade. Update the app on this phone."
        theirs.optString("console") != console.name ->
            "The other phone is playing a ${theirs.optString("console")} game. Both must be the same console."
        !isNonce(theirs.optString("nonce")) -> "That isn't a Shoalcade phone."
        else -> null
    }

    // ---- Link-play PIN (L7, D133) ----
    const val PIN_STRIKES = 5
    const val ROLE_JOIN = "join"
    const val ROLE_HOST = "host"
    private const val NONCE_BYTES = 16

    /** A fresh 4-digit PIN, 0000-9999. */
    fun newPin(rnd: java.util.Random = java.security.SecureRandom()): String = "%04d".format(rnd.nextInt(10_000))

    fun isPin(s: String) = s.length == 4 && s.all { it in '0'..'9' }

    /** 16 random bytes as hex: one per connection, so an old answer never fits a new one. */
    fun newNonce(rnd: java.util.Random = java.security.SecureRandom()): String =
        ByteArray(NONCE_BYTES).also { rnd.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    fun isNonce(s: String) = s.length == NONCE_BYTES * 2 && s.all { it in '0'..'9' || it in 'a'..'f' }

    /**
     * HMAC-SHA256 over the role, console and both nonces, keyed by the PIN. Phone addresses are
     * left out on purpose: a hotspot or the emulator's port forward shows each side a different one.
     */
    fun proof(pin: String, role: String, console: Console, hostNonce: String, joinNonce: String): String {
        val key = java.security.MessageDigest.getInstance("SHA-256").digest("Shoalcade link PIN v$PROTOCOL:$pin".toByteArray(Charsets.UTF_8))
        val mac = javax.crypto.Mac.getInstance("HmacSHA256").apply { init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256")) }
        val msg = listOf("Shoalcade link", PROTOCOL.toString(), role, console.name, hostNonce, joinNonce).joinToString("\n")
        return mac.doFinal(msg.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /** Constant-time check of [given] against the proof we expect, so timing gives nothing away. */
    fun proofMatches(pin: String, role: String, console: Console, hostNonce: String, joinNonce: String, given: String): Boolean =
        java.security.MessageDigest.isEqual(
            proof(pin, role, console, hostNonce, joinNonce).toByteArray(Charsets.US_ASCII),
            given.toByteArray(Charsets.US_ASCII),
        )

    /**
     * Interfaces link play may listen on: Wi-Fi and hotspot, never mobile data or a VPN (L7).
     * A deny list, because hotspot names differ by maker (ap0, swlan0, wlan1, ap_br_wlan2…).
     */
    fun isLinkInterface(name: String): Boolean {
        val n = name.lowercase()
        return listOf("rmnet", "ccmni", "pdp", "v4-", "clat", "tun", "ppp", "ims", "seth", "wwan", "dummy", "ipsec", "lo", "radio", "usb", "rndis")
            .none { n.startsWith(it) }
    }

    /** "192.168.1.5" or "192.168.1.5:5000" → host and port (default [PORT]). */
    fun parseAddress(typed: String): Pair<String, Int> {
        val t = typed.trim()
        val host = t.substringBefore(':')
        val port = t.substringAfter(':', "").toIntOrNull()?.takeIf { it in 1..65535 } ?: PORT
        return host to port
    }

    /** melonDS DS needs a literal MAC "xx:xx:xx:xx:xx:xx". 00:09:BF is Nintendo's prefix. */
    fun randomDsMac(rnd: java.util.Random = java.security.SecureRandom()): String =
        "00:09:BF:" + (0 until 3).joinToString(":") { "%02X".format(rnd.nextInt(256)) }
}

/**
 * The host's PIN and its wrong-try count (L7, D133; security review M1). After [LinkRules.PIN_STRIKES] wrong
 * answers the host is [paused]: every try is refused without even checking it, until the player taps
 * Keep hosting ([resume]), which makes a new PIN. So guessing needs a person to tap, every 5 tries.
 */
class PinGuard(private val rnd: java.util.Random = java.security.SecureRandom()) {
    enum class Result { OK, WRONG, PAUSED }

    @Volatile var pin: String = LinkRules.newPin(rnd); private set
    @Volatile var strikes = 0; private set
    @Volatile var paused = false; private set

    @Synchronized
    fun check(console: Console, hostNonce: String, joinNonce: String, proof: String): Result {
        if (paused) return Result.PAUSED
        if (LinkRules.proofMatches(pin, LinkRules.ROLE_JOIN, console, hostNonce, joinNonce, proof)) {
            strikes = 0
            return Result.OK
        }
        strikes++
        if (strikes < LinkRules.PIN_STRIKES) return Result.WRONG
        paused = true
        return Result.PAUSED
    }

    /** The host player tapped Keep hosting: a new PIN, strikes cleared, joins accepted again. */
    @Synchronized
    fun resume() {
        val old = pin
        do pin = LinkRules.newPin(rnd) while (pin == old)
        strikes = 0
        paused = false
    }
}

/**
 * The hello swap on a fresh socket, before the game's own data starts (the data path is unchanged).
 * 1. Host → joiner: hello with a fresh nonce.
 * 2. Joiner → host: hello with its own nonce and proof = HMAC(PIN; "join", console, both nonces).
 * 3. Host → joiner: {"ok":true, proof = HMAC(PIN; "host", …)} or {"ok":false, "newPin":…}
 *    ("newPin": true = too many wrong PINs; the host shows a new one once its player taps Keep hosting).
 * Lines are read byte by byte so nothing after them is swallowed into a buffer.
 */
object LinkHandshake {
    sealed interface HostResult {
        data class Linked(val peerName: String) : HostResult
        /** [paused]: too many wrong PINs, so the host waits for its player to tap Keep hosting. */
        data class WrongPin(val paused: Boolean) : HostResult
        /** Not a usable joiner (hung up, old app, other console, junk): keep waiting. */
        data class Dropped(val why: String) : HostResult
    }

    sealed interface JoinResult {
        data class Linked(val peerName: String, val cableMode: Boolean) : JoinResult
        data class Refused(val reason: String, val wrongPin: Boolean = false) : JoinResult
    }

    const val WRONG_PIN = "Wrong PIN. Check the PIN on the host phone and try again."
    const val NEW_PIN = "Too many wrong PINs. Ask the host to tap Keep hosting, then type the new PIN."
    const val HOST_NO_PIN = "That phone doesn't know the PIN, so it wasn't linked."

    fun host(
        input: InputStream, output: OutputStream, guard: PinGuard, console: Console, gameName: String,
        deviceName: String, cableMode: Boolean, nonce: String = LinkRules.newNonce(),
    ): HostResult {
        send(output, LinkRules.hello(console, gameName, deviceName, cableMode, nonce))
        val theirs = try { JSONObject(readLine(input)) } catch (e: Exception) { return HostResult.Dropped(e.message ?: "no hello") }
        LinkRules.checkHello(theirs, console)?.let { return HostResult.Dropped(it) }
        val joinNonce = theirs.optString("nonce")
        return when (guard.check(console, nonce, joinNonce, theirs.optString("proof"))) {
            PinGuard.Result.OK -> {
                // Tell the joiner, with our own proof, so it knows it reached the phone showing the PIN.
                send(output, JSONObject().put("ok", true)
                    .put("proof", LinkRules.proof(guard.pin, LinkRules.ROLE_HOST, console, nonce, joinNonce)).toString())
                HostResult.Linked(theirs.optString("device"))
            }
            PinGuard.Result.WRONG -> { send(output, JSONObject().put("ok", false).put("newPin", false).toString()); HostResult.WrongPin(false) }
            PinGuard.Result.PAUSED -> { send(output, JSONObject().put("ok", false).put("newPin", true).toString()); HostResult.WrongPin(true) }
        }
    }

    fun join(
        input: InputStream, output: OutputStream, pin: String, console: Console, gameName: String,
        deviceName: String, nonce: String = LinkRules.newNonce(),
    ): JoinResult {
        val theirs = JSONObject(readLine(input))
        LinkRules.checkHello(theirs, console)?.let { return JoinResult.Refused(it) }
        val hostNonce = theirs.optString("nonce")
        // The host decides the GBA link mode; we follow it.
        val cable = theirs.optBoolean("cable")
        val proof = LinkRules.proof(pin, LinkRules.ROLE_JOIN, console, hostNonce, nonce)
        send(output, LinkRules.hello(console, gameName, deviceName, cable, nonce, proof))
        val verdict = JSONObject(readLine(input))
        if (!verdict.optBoolean("ok")) return JoinResult.Refused(if (verdict.optBoolean("newPin")) NEW_PIN else WRONG_PIN, wrongPin = true)
        if (!LinkRules.proofMatches(pin, LinkRules.ROLE_HOST, console, hostNonce, nonce, verdict.optString("proof")))
            return JoinResult.Refused(HOST_NO_PIN)
        return JoinResult.Linked(theirs.optString("device"), cable)
    }

    fun send(output: OutputStream, line: String) {
        output.write((line + "\n").toByteArray(Charsets.UTF_8))
        output.flush()
    }

    /** Reads one line byte by byte, so nothing after it is swallowed into a buffer. */
    fun readLine(input: InputStream): String {
        val buf = java.io.ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) throw IOException("the other phone hung up")
            if (b == '\n'.code) break
            buf.write(b)
            if (buf.size() > 4096) throw IOException("unexpected data")
        }
        return buf.toString(Charsets.UTF_8.name())
    }
}

/** A connected link, handed from the lobby to [GameActivity]. */
class LinkConnection(
    val socket: Socket,
    val isHost: Boolean,
    val cableMode: Boolean,
    val peerName: String,
) {
    /** A duplicate of the socket's fd for the native shim; the shim owns and closes it. */
    fun detachFdForNative(): Int = ParcelFileDescriptor.fromSocket(socket).detachFd()
}

/** Holds the one pending/active link session and the Wi-Fi locks that keep it fast. */
object LinkHolder {
    @Volatile var pending: LinkConnection? = null
    private var multicast: WifiManager.MulticastLock? = null
    private var lowLatency: WifiManager.WifiLock? = null

    /** Stops Android dropping discovery packets and throttling Wi-Fi to save power. */
    fun acquireLocks(ctx: Context) {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (multicast == null) multicast = wm.createMulticastLock("Shoalcade link").apply { setReferenceCounted(false); acquire() }
        if (lowLatency == null) {
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            lowLatency = wm.createWifiLock(mode, "Shoalcade link").apply { setReferenceCounted(false); acquire() }
        }
    }

    fun end() {
        pending?.let { runCatching { it.socket.close() } }
        pending = null
        runCatching { multicast?.release() }
        runCatching { lowLatency?.release() }
        multicast = null
        lowLatency = null
    }
}

/** Per-phone link settings. */
object LinkPrefs {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("link", Context.MODE_PRIVATE)

    /** Fixed DS wireless ID for this phone, or null to use the emulator's default. */
    fun dsMac(ctx: Context): String? = prefs(ctx).getString("ds_mac", null)

    fun setDsMacEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().apply {
            if (on) { if (dsMac(ctx) == null) putString("ds_mac", LinkRules.randomDsMac()) } else remove("ds_mac")
        }.apply()
    }

    fun deviceName(): String = Build.MODEL ?: "Android phone"
}

/** One phone found on the network. */
data class LinkPeer(val name: String, val game: String, val host: String, val port: Int)

sealed interface LobbyState {
    data object Idle : LobbyState
    /**
     * [notice]: what happened to the last try, e.g. a wrong PIN; [pin] is shown for the joiner to type.
     * [paused]: 5 wrong PINs, so nobody can join until the player taps Keep hosting (M1).
     */
    data class Hosting(val addresses: List<String>, val port: Int, val pin: String, val notice: String? = null,
                       val paused: Boolean = false) : LobbyState
    data class Searching(val peers: List<LinkPeer>) : LobbyState
    data class Connecting(val to: String) : LobbyState
    data class Connected(val connection: LinkConnection) : LobbyState
    /** [retryJoin]: the joiner can fix it and try again (e.g. a mistyped PIN). */
    data class Failed(val reason: String, val retryJoin: Boolean = false) : LobbyState
}

/**
 * Finds the other phone and connects to it. Host: listen + advertise over mDNS (NsdManager).
 * Join: discover, or type the host's address, plus the PIN the host shows. [LinkHandshake] runs before the game starts.
 */
class LinkLobby(ctx: Context, private val console: Console, private val gameName: String) {
    private val app = ctx.applicationContext
    private val nsd = app.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<LobbyState>(LobbyState.Idle)
    val state: StateFlow<LobbyState> = _state

    private var servers: List<ServerSocket> = emptyList()
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var job: Job? = null
    private val peers = linkedMapOf<String, LinkPeer>()
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    @Volatile private var handedOver = false
    @Volatile private var guard: PinGuard? = null

    fun host(cableMode: Boolean) {
        stopAll()
        // Listen only on Wi-Fi / hotspot addresses, never mobile data (L7).
        val wifi = linkAddresses()
        if (wifi.isEmpty()) { fail(NO_WIFI); return }
        LinkHolder.acquireLocks(app)
        // Debug builds also listen on the phone's own loopback, for the PC test rig's `adb forward` (docs/testing-link.md).
        val binds = wifi + if (BuildConfig.DEBUG) listOf(InetAddress.getLoopbackAddress()) else emptyList()
        job = scope.launch {
            try {
                val sockets = bindAll(binds)
                servers = sockets
                val port = sockets.first().localPort
                val shown = wifi.mapNotNull { it.hostAddress }
                val g = PinGuard()
                advertise(port)
                _state.value = LobbyState.Hosting(shown, port, g.pin)
                val incoming = Channel<Socket>()
                for (ss in sockets) launch {
                    while (true) {
                        val s = try { ss.accept() } catch (e: IOException) { break } // closed: hosting is over
                        try { incoming.send(s) } catch (e: Exception) { runCatching { s.close() }; break }
                    }
                }
                guard = g
                // One try at a time. A phone that drops out never stops hosting; 5 wrong PINs pause it (M1).
                while (true) {
                    val s = incoming.receive()
                    val r = try {
                        s.tcpNoDelay = true
                        s.soTimeout = 10_000
                        LinkHandshake.host(s.getInputStream(), s.getOutputStream(), g, console, gameName, LinkPrefs.deviceName(), cableMode)
                    } catch (e: Exception) { LinkHandshake.HostResult.Dropped(e.message ?: "error") }
                    when (r) {
                        is LinkHandshake.HostResult.Linked -> {
                            incoming.close()
                            closeServers()
                            unadvertise()
                            s.soTimeout = 0
                            connected(LinkConnection(s, isHost = true, cableMode = cableMode, peerName = r.peerName))
                            return@launch
                        }
                        is LinkHandshake.HostResult.WrongPin -> {
                            runCatching { s.close() }
                            // Read the guard, not r: Keep hosting may have been tapped meanwhile.
                            val paused = g.paused
                            _state.value = LobbyState.Hosting(shown, port, g.pin, if (paused) HOST_PAUSED else HOST_WRONG_PIN, paused)
                        }
                        is LinkHandshake.HostResult.Dropped -> runCatching { s.close() }
                    }
                }
            } catch (e: Exception) {
                if (!handedOver && _state.value !is LobbyState.Idle && _state.value !is LobbyState.Connected) fail("Hosting stopped: ${e.message}")
            }
        }
    }

    /** After 5 wrong PINs: the host player chose to carry on, so make a new PIN and take joins again (M1). */
    fun keepHosting() {
        val g = guard ?: return
        val st = _state.value as? LobbyState.Hosting ?: return
        g.resume()
        _state.value = st.copy(pin = g.pin, notice = HOST_NEW_PIN, paused = false)
    }

    fun search() {
        stopAll()
        LinkHolder.acquireLocks(app)
        peers.clear()
        _state.value = LobbyState.Searching(emptyList())
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = fail("Couldn't search the network (error $errorCode).")
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) = enqueueResolve(info)
            override fun onServiceLost(info: NsdServiceInfo) {
                synchronized(peers) { peers.remove(info.serviceName) }
                publishPeers()
            }
        }
        discovery = l
        nsd.discoverServices(LinkRules.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
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
                when (val r = LinkHandshake.join(s.getInputStream(), s.getOutputStream(), pin, console, gameName, LinkPrefs.deviceName())) {
                    is LinkHandshake.JoinResult.Linked -> {
                        s.soTimeout = 0
                        connected(LinkConnection(s, isHost = false, cableMode = r.cableMode, peerName = r.peerName))
                    }
                    is LinkHandshake.JoinResult.Refused -> {
                        runCatching { s.close() }
                        _state.value = LobbyState.Failed(r.reason, retryJoin = r.wrongPin)
                    }
                }
            } catch (e: Exception) {
                runCatching { s.close() }
                fail("Couldn't connect to $host: ${e.message}")
            }
        }
    }

    /** Called when the screen closes. Keeps the socket only if a game took it over. */
    fun close() {
        stopAll()
        scope.cancel()
        if (!handedOver) LinkHolder.end()
    }

    fun markHandedOver() { handedOver = true }

    private fun connected(c: LinkConnection) {
        LinkHolder.pending = c
        _state.value = LobbyState.Connected(c)
    }

    private fun fail(reason: String) { _state.value = LobbyState.Failed(reason) }

    private fun stopAll() {
        job?.cancel()
        job = null
        guard = null
        closeServers()
        unadvertise()
        stopDiscovery()
    }

    private fun closeServers() {
        servers.forEach { runCatching { it.close() } }
        servers = emptyList()
    }

    /** Same port on every address: the fixed one (the test emulators forward it), else any free one. */
    private fun bindAll(addrs: List<InetAddress>): List<ServerSocket> {
        fun bindOrNull(a: InetAddress, port: Int): ServerSocket? {
            val ss = ServerSocket().apply { reuseAddress = true }
            return try { ss.bind(InetSocketAddress(a, port)); ss } catch (e: IOException) { runCatching { ss.close() }; null }
        }
        val fixed = addrs.map { bindOrNull(it, LinkRules.PORT) }
        if (fixed.all { it != null }) return fixed.filterNotNull()
        fixed.forEach { runCatching { it?.close() } }
        val first = bindOrNull(addrs.first(), 0) ?: throw IOException("no free port")
        return listOf(first) + addrs.drop(1).mapNotNull { bindOrNull(it, first.localPort) }
    }

    private fun advertise(port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = "Shoalcade ${LinkPrefs.deviceName()}"
            serviceType = LinkRules.SERVICE_TYPE
            setPort(port)
            setAttribute("game", gameName.take(60))
            setAttribute("console", console.name)
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {} // typed address still works
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        registration = l
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
    }

    private fun unadvertise() {
        registration?.let { runCatching { nsd.unregisterService(it) } }
        registration = null
    }

    private fun stopDiscovery() {
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discovery = null
        synchronized(resolveQueue) { resolveQueue.clear(); resolving = false }
    }

    // Before Android 14, NsdManager can only resolve one service at a time, so queue them.
    private fun enqueueResolve(info: NsdServiceInfo) {
        synchronized(resolveQueue) { resolveQueue.addLast(info) }
        resolveNext()
    }

    @Suppress("DEPRECATION") // resolveService is the API that exists on Android 11-13 too
    private fun resolveNext() {
        val next = synchronized(resolveQueue) {
            if (resolving || resolveQueue.isEmpty()) return
            resolving = true
            resolveQueue.removeFirst()
        }
        nsd.resolveService(next, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = done()
            override fun onServiceResolved(info: NsdServiceInfo) {
                val host = info.host
                val theirConsole = info.attributes["console"]?.toString(Charsets.UTF_8)
                if (host != null && theirConsole == console.name) {
                    val game = info.attributes["game"]?.toString(Charsets.UTF_8) ?: ""
                    val name = info.serviceName.removePrefix("Shoalcade ")
                    synchronized(peers) { peers[info.serviceName] = LinkPeer(name, game, host.hostAddress ?: "", info.port) }
                    publishPeers()
                }
                done()
            }
            fun done() {
                synchronized(resolveQueue) { resolving = false }
                resolveNext()
            }
        })
    }

    private fun publishPeers() {
        if (_state.value is LobbyState.Searching) _state.value = LobbyState.Searching(synchronized(peers) { peers.values.toList() })
    }

    companion object {
        const val NO_WIFI = "Connect both phones to the same Wi-Fi, or turn on a hotspot, then try again."
        const val HOST_WRONG_PIN = "Someone typed a wrong PIN. Still waiting…"
        const val HOST_PAUSED = "Someone typed the wrong PIN 5 times, so nobody can join for now. Tap Keep hosting for a new PIN, or Cancel to stop."
        const val HOST_NEW_PIN = "Here's a new PIN. Use this one instead."

        /** This phone's Wi-Fi/hotspot IPv4 addresses (never mobile data or a VPN): where the host listens. */
        fun linkAddresses(): List<Inet4Address> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && LinkRules.isLinkInterface(it.name) }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter { it.isSiteLocalAddress }
        }.getOrDefault(emptyList())

        /** First 0xB0 bytes of a ROM (enough for the GBA header). */
        fun romHeader(rom: File): ByteArray = rom.inputStream().use { i ->
            val b = ByteArray(0xB0)
            var n = 0
            while (n < b.size) { val r = i.read(b, n, b.size - n); if (r < 0) break; n += r }
            b.copyOf(n)
        }
    }
}
