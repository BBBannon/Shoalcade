package uk.benmarks.shoalcade

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/*
 * Celio Link interop (GBA cable trades with a PC on mGBA Celio Edition, or a real GBA on a Celio adapter).
 *
 * Written from the protocol, not from Celio's code (GPL-3.0) or gen1recomp-celio's (MIT); docs/licences.md names the
 * projects that informed it. The phone plays the Celio "device" and talks straight to Celio-Server (Socket.IO v5 over
 * a WebSocket), as gen1recomp-celio does. gpSP (cable mode, client 0 = link master) talks MPK1 to the native shim,
 * which is given one end of a socketpair exactly as for the Jellyfin relay (D176); [CelioBridge] translates on the
 * other end. linkshim.c and gpSP are unchanged.
 */

/** Celio-Server's wire: Engine.IO 4 / Socket.IO 5 text packets, and the event payloads Celio uses. */
object CelioWire {
    const val DEFAULT_SERVER = "wss://celio-server.up.railway.app"
    private const val SOCKET_PATH = "/socket.io/?EIO=4&transport=websocket"
    /** ws:// (no TLS) only to these: the PC's own loopback, and the Android emulator's alias for the PC. */
    private val PLAIN_OK = setOf("127.0.0.1", "10.0.2.2")

    // LinkStatus (Celio-Server src/messages/gameboy.ts).
    const val AWAIT_MODE_EMULATOR = 0xFF01
    const val AWAIT_MODE = 0xFF02
    const val HANDSHAKE_RECEIVED = 0xFF03
    const val LINK_CONNECTED = 0xFF05
    const val LINK_RECONNECTING = 0xFF06
    const val LINK_CLOSED = 0xFF07

    // CommandType (same file).
    const val SET_MODE_MASTER = 0x10
    const val SET_MODE_SLAVE = 0x11
    const val START_HANDSHAKE = 0x12
    const val CONNECT_LINK = 0x13

    /** One Gen 3 link command frame, and one Celio data packet (four frames). */
    const val FRAME_WORDS = 8
    const val PACKET_WORDS = 32

    /** Plain words for a typed server address, or null if it is fine. */
    fun serverProblem(typed: String): String? {
        val u = parseServer(typed) ?: return CelioText.BAD_SERVER
        val scheme = u.scheme?.lowercase()
        return when {
            scheme == "wss" -> null
            scheme == "ws" && u.host in PLAIN_OK -> null
            else -> CelioText.BAD_SERVER
        }
    }

    /** The Socket.IO WebSocket address for a server the person typed (check [serverProblem] first). */
    fun socketUrl(typed: String): String {
        val u = parseServer(typed) ?: throw IllegalArgumentException("bad server")
        val port = if (u.port > 0) ":${u.port}" else ""
        return "${u.scheme.lowercase()}://${u.host}$port$SOCKET_PATH"
    }

    private fun parseServer(typed: String): URI? = runCatching {
        val u = URI(typed.trim().trimEnd('/'))
        val ok = u.host != null && u.rawUserInfo == null && u.rawQuery == null && u.rawFragment == null &&
            (u.rawPath.isNullOrEmpty()) && (u.port == -1 || u.port in 1..65535)
        u.takeIf { ok }
    }.getOrNull()

    /** A typed room number: exactly 4 digits (Celio-Server validation.ts isSessionId), else null. */
    fun cleanRoom(typed: String): String? = typed.filter { it != ' ' }.takeIf { it.length == 4 && it.all(Char::isDigit) }

    /** Text box filter: digits only, at most 4. */
    fun typingRoom(typed: String): String = typed.filter(Char::isDigit).take(4)

    // ---- Engine.IO / Socket.IO packets --------------------------------------------------------------------------

    sealed interface Packet {
        data class Open(val pingIntervalMs: Long, val pingTimeoutMs: Long) : Packet
        data object Ping : Packet
        data object Close : Packet
        data object Connected : Packet
        data object Disconnected : Packet
        data class ConnectError(val message: String) : Packet
        /** [arg]: the first argument after the event name (JSONObject.NULL for null, null if there is none). */
        data class Event(val ackId: Int?, val name: String, val arg: Any?) : Packet
        data class Ack(val id: Int, val args: JSONArray) : Packet
        data object Other : Packet
    }

    fun parse(text: String): Packet = runCatching { parseOrThrow(text) }.getOrDefault(Packet.Other)

    private fun parseOrThrow(text: String): Packet {
        if (text.isEmpty()) return Packet.Other
        return when (text[0]) {
            '0' -> JSONObject(text.substring(1)).let {
                Packet.Open(it.optLong("pingInterval", 25_000), it.optLong("pingTimeout", 20_000))
            }
            '1' -> Packet.Close
            '2' -> Packet.Ping
            '4' -> socketIo(text.substring(1))
            else -> Packet.Other // 3 pong, 5 upgrade, 6 noop: nothing to do
        }
    }

    private fun socketIo(s: String): Packet {
        if (s.isEmpty()) return Packet.Other
        val type = s[0]
        var i = 1
        // A namespace other than "/" would start with '/': Celio only uses the main one.
        if (i < s.length && s[i] == '/') return Packet.Other
        while (i < s.length && s[i].isDigit()) i++
        val id = if (i > 1) s.substring(1, i).toIntOrNull() else null
        val body = s.substring(i)
        return when (type) {
            '0' -> Packet.Connected
            '1' -> Packet.Disconnected
            '4' -> Packet.ConnectError(runCatching { JSONObject(body).optString("message") }.getOrDefault(""))
            '2' -> {
                val a = JSONArray(body)
                val name = a.optString(0, "")
                if (name.isEmpty()) Packet.Other else Packet.Event(id, name, if (a.length() > 1) a.get(1) else null)
            }
            '3' -> if (id == null) Packet.Other else Packet.Ack(id, JSONArray(body))
            else -> Packet.Other
        }
    }

    const val PONG = "3"
    const val LEAVE_NAMESPACE = "41"

    /** Socket.IO connect, with the auth Celio-Server reads (index.ts: socket.handshake.auth.clientId). */
    fun connect(clientId: String): String = "40" + JSONObject().put("clientId", clientId)

    /** An event: `42[id]["name", args...]`. A Kotlin null argument is sent as JSON null. */
    fun event(ackId: Int?, name: String, args: List<Any?>): String {
        val a = JSONArray().put(name)
        args.forEach { a.put(it ?: JSONObject.NULL) }
        return "42" + (ackId?.toString() ?: "") + a
    }

    fun ack(id: Int, value: Any): String = "43$id" + JSONArray().put(value)

    /** deviceStatus payload: {uuid, linkStatus} (validation.ts isStatusPacket). */
    fun status(uuid: String, linkStatus: Int): JSONObject = JSONObject().put("uuid", uuid).put("linkStatus", linkStatus)

    /** deviceData payload: a batch with one packet of 32 words (validation.ts isDataPacketBatch). */
    fun data(sequence: Long, words: IntArray): JSONArray {
        require(words.size == PACKET_WORDS)
        val d = JSONArray()
        words.forEach { d.put(it and 0xFFFF) }
        return JSONArray().put(JSONObject().put("sequence", sequence).put("data", d))
    }

    /** A deviceData batch from the server; packets that aren't well formed are left out. */
    fun dataPackets(arg: Any?): List<Pair<Long, IntArray>> {
        val list = arg as? JSONArray ?: (arg as? JSONObject)?.let { JSONArray().put(it) } ?: return emptyList()
        val out = ArrayList<Pair<Long, IntArray>>()
        for (i in 0 until minOf(list.length(), 1024)) {
            val o = list.optJSONObject(i) ?: continue
            val seq = whole(o.opt("sequence")) ?: continue
            val d = o.optJSONArray("data") ?: continue
            if (seq < 0 || d.length() != PACKET_WORDS) continue
            val words = IntArray(PACKET_WORDS)
            var ok = true
            for (w in 0 until PACKET_WORDS) {
                val v = whole(d.opt(w))
                if (v == null || v < 0 || v > 0xFFFF) { ok = false; break }
                words[w] = v.toInt()
            }
            if (ok) out += seq to words
        }
        return out
    }

    /** deviceCommand payload {uuid, command} → (uuid, command), or null. */
    fun command(arg: Any?): Pair<String, Int>? {
        val o = arg as? JSONObject ?: return null
        val c = whole(o.opt("command")) ?: return null
        return o.optString("uuid", "") to c.toInt()
    }

    /** The uuid a session event carries (Celio retries them, so the same one can arrive twice). */
    fun uuid(arg: Any?): String = (arg as? JSONObject)?.optString("uuid", "") ?: ""

    /** sessionCreate / sessionJoin answer (a true-myth Result): the room, or a plain-English reason. */
    sealed interface RoomResult {
        data class Ok(val room: String, val full: Boolean) : RoomResult
        data class Refused(val reason: String) : RoomResult
    }

    fun roomResult(args: JSONArray): RoomResult {
        val o = args.optJSONObject(0) ?: return RoomResult.Refused(CelioText.ODD_ANSWER)
        if (o.optString("variant") == "Ok") {
            val v = o.optJSONObject("value") ?: return RoomResult.Refused(CelioText.ODD_ANSWER)
            val id = v.optString("id")
            return if (cleanRoom(id) == id) RoomResult.Ok(id, v.optBoolean("full")) else RoomResult.Refused(CelioText.ODD_ANSWER)
        }
        return RoomResult.Refused(when (o.optString("error")) {
            "Session not found" -> CelioText.NO_ROOM
            "Session is full" -> CelioText.ROOM_FULL
            "Invalid request" -> CelioText.ROOM_DIGITS
            else -> CelioText.ODD_ANSWER
        })
    }

    private fun whole(v: Any?): Long? = when (v) {
        is Int -> v.toLong()
        is Long -> v
        is Number -> v.toDouble().takeIf { it == Math.floor(it) && !it.isInfinite() }?.toLong()
        else -> null
    }
}

/** gpSP's mul_poke netpacket (serial_proto.c serialpoke_senddata / serialpoke_net_receive): always 24 bytes. */
object Mpk1 {
    const val MAGIC = 0x4d504b31
    const val SIZE = 24
    const val PREINIT = 0
    const val HANDSHAKE = 1
    const val CONNECTED = 2
    private const val HAS_DATA = 0x80000000.toInt()

    /** [state] in the low 16 bits; with [frame], bit 31 set and its 8 words packed big-endian in pairs. */
    fun encode(state: Int, frame: IntArray?): ByteArray {
        val b = ByteBuffer.allocate(SIZE).putInt(MAGIC).putInt((state and 0xFFFF) or (if (frame != null) HAS_DATA else 0))
        if (frame != null) { require(frame.size == CelioWire.FRAME_WORDS); frame.forEach { b.putShort(it.toShort()) } }
        return b.array()
    }

    class Packet(val state: Int, val frame: IntArray?)

    /** Null if it isn't an MPK1 packet (gpSP ignores those too). */
    fun decode(p: ByteArray): Packet? {
        if (p.size != SIZE) return null
        val b = ByteBuffer.wrap(p)
        if (b.int != MAGIC) return null
        val flags = b.int
        val frame = if (flags and HAS_DATA != 0) IntArray(CelioWire.FRAME_WORDS) { b.short.toInt() and 0xFFFF } else null
        return Packet(flags and 0xFFFF, frame)
    }
}

/** Timings for [CelioBridge] (tests shrink them). */
data class CelioTimings(
    /** A status MPK1 to gpSP at least this often (gpSP forgets a peer after 240 frames = 4 s). */
    val keepAliveMs: Long = 250,
    /** A part-filled packet of frames goes out after this long (the reference device fills it within 4 blocks). */
    val flushMs: Long = 100,
    /** Partner frames handed to gpSP but not yet used (gpSP takes one per block; it holds 128 at most). */
    val window: Int = 8,
    /** Partner frames waiting for gpSP before the link counts as too slow (about 17 s of frames). */
    val maxQueued: Int = 1024,
)

/**
 * The status/command state machine between gpSP (link master, client 0) and Celio. Plain JVM code: MPK1 bytes in and
 * out, Celio statuses and 32-word packets out, commands and partner packets in. [Out] calls must not block.
 *
 * Per link session (a trade is several: gen1recomp-celio lib/gbalink.lua L5-9):
 *  1. gpSP state 0→1 (the game sent 0xB9A0) → status HandshakeReceived. gpSP shows the game 0xFFFF until…
 *  2. …command StartHandshake (the server sends it once both devices are in HandshakeReceived) → fake peer state 1,
 *     so gpSP shows the game 0xB9A0 and the game answers 0x8FFF.
 *  3. gpSP state 1→2 → status LinkConnected (the partner's device gets ConnectLink). Frames flow: our game's non-empty
 *     frames four to a packet; the partner's frames to gpSP one per block. Checksums stay local on both sides.
 *  4. Both sides sent 0x5FFF (READY_CLOSE_LINK) → status LinkReconnecting (or LinkClosed after the partner's
 *     "exit room" key 0xCAFE 0x0017), fake peer back to 0, and back to 1 for the next session.
 * Shoalcade always sends AwaitModeEmulator and leads the link (Celio-Server session.ts: always SetModeSlave).
 */
class CelioBridge(private val out: Out, private val t: CelioTimings = CelioTimings()) {
    interface Out {
        /** One MPK1 packet for gpSP (the shim's framing is added by the caller). */
        fun toGame(packet: ByteArray)
        fun status(linkStatus: Int)
        fun data(words: IntArray)
        /** The link can't go on; [why] is plain English. */
        fun fail(why: String)
    }

    enum class Phase { WAITING_PARTNER, WAITING_GAME, LINKING, LINKED, FINISHED }

    private var partner = false
    private var modeAsked = false
    private var leader = false
    private var game = Mpk1.PREINIT
    private var peer = Mpk1.PREINIT
    private var handshakeSent = false
    private var started = false
    private var linked = false
    private var finished = false
    private val batch = ArrayList<IntArray>(4)
    private var batchAt = 0L
    private var emptyStreak = 0
    private val inQ = ArrayDeque<IntArray>()
    private var inFlight = 0
    private var sentClose = false
    private var gotClose = false
    private var keepAlive = true
    private var lastToGame = Long.MIN_VALUE / 2
    private var failed = false

    /** Link sessions started (one trade is several). */
    @get:Synchronized var sessions = 0; private set

    @get:Synchronized val phase: Phase get() = when {
        finished -> Phase.FINISHED
        !partner -> Phase.WAITING_PARTNER
        linked -> Phase.LINKED
        handshakeSent -> Phase.LINKING
        else -> Phase.WAITING_GAME
    }

    /** The game closed the link for good ("exit room"): LinkClosed has gone to Celio. */
    @get:Synchronized val closedForGood get() = finished

    @Synchronized fun partnerJoined() {
        partner = true
        if (!modeAsked) { modeAsked = true; out.status(CelioWire.AWAIT_MODE_EMULATOR) }
        maybeHandshake()
    }

    @Synchronized fun partnerLeft() { partner = false }

    @Synchronized fun command(c: Int, now: Long) {
        when (c) {
            CelioWire.SET_MODE_SLAVE -> { leader = true; maybeHandshake() }
            CelioWire.SET_MODE_MASTER -> fail(CelioText.BOTH_LEAD) // only AwaitMode gets this; we never send it
            CelioWire.START_HANDSHAKE -> if (handshakeSent) { started = true; setPeer(Mpk1.HANDSHAKE, now) }
            else -> {} // ConnectLink: our own LinkConnected echoed to us (gbalink.lua L142-144); others aren't for us
        }
    }

    /** The shim just took its end: gpSP starts from scratch. */
    @Synchronized fun attached(now: Long) {
        game = Mpk1.PREINIT
        setPeer(peer, now)
    }

    @Synchronized fun fromGame(packet: ByteArray, now: Long) {
        val p = Mpk1.decode(packet) ?: return
        val before = game
        game = p.state
        if (before != game) {
            // A reset (the game changed SIO mode) wipes gpSP's view of us, so say again where we are.
            setPeer(peer, now)
            if (before == Mpk1.CONNECTED) endSession(now)
        }
        when {
            game == Mpk1.CONNECTED && before != Mpk1.CONNECTED -> beginSession(now) // the 0x8FFF packet, not a block
            game == Mpk1.CONNECTED -> if (linked) block(p.frame, now)
            game == Mpk1.HANDSHAKE -> maybeHandshake()
        }
    }

    /** One Celio data packet from the partner (already in sequence order). */
    @Synchronized fun fromPartner(words: IntArray, now: Long) {
        if (!linked || words.size != CelioWire.PACKET_WORDS) return // between sessions: left over from the last one
        for (f in 0 until 4) {
            val frame = words.copyOfRange(f * 8, f * 8 + 8)
            if (frame.all { it == 0 }) continue // gpSP gives the game zeros anyway when nothing is queued
            if (inQ.size >= t.maxQueued) { fail(CelioText.TOO_SLOW); return }
            inQ.addLast(frame)
        }
        feed(now)
    }

    @Synchronized fun tick(now: Long) {
        if (batch.isNotEmpty() && now - batchAt >= t.flushMs) flush()
        if (now - lastToGame >= t.keepAliveMs) setPeer(peer, now)
    }

    private fun maybeHandshake() {
        if (game == Mpk1.HANDSHAKE && partner && leader && !handshakeSent && !finished && !failed) {
            handshakeSent = true
            out.status(CelioWire.HANDSHAKE_RECEIVED)
        }
    }

    private fun beginSession(now: Long) {
        // gpSP's game only sends 0x8FFF once it has seen our 0xB9A0, which needs StartHandshake.
        if (!started || finished) return
        linked = true
        sessions++
        inQ.clear(); inFlight = 0
        batch.clear(); emptyStreak = 0
        sentClose = false; gotClose = false; keepAlive = true
        out.status(CelioWire.LINK_CONNECTED)
        setPeer(Mpk1.CONNECTED, now)
    }

    /** One finished block from gpSP: our game's frame (null = all zeros). */
    private fun block(frame: IntArray?, now: Long) {
        if (inFlight > 0) inFlight--
        val w = frame?.copyOf() ?: IntArray(CelioWire.FRAME_WORDS)
        if (w[0] == 0x5FFF) sentClose = true
        // Only the first "no key held" frame of a run is sent, as the reference device does.
        if (w[0] == 0xCAFE && w[1] == 0x0011) {
            if (++emptyStreak > 1) { w[0] = 0; w[1] = 0 }
        } else emptyStreak = 0
        if (batch.isNotEmpty() || w.any { it != 0 }) {
            if (batch.isEmpty()) batchAt = now
            batch += w
            if (batch.size == 4) flush()
        }
        if (sentClose && gotClose) { endSession(now); return }
        feed(now)
    }

    private fun feed(now: Long) {
        while (linked && inQ.isNotEmpty() && inFlight < t.window) {
            val f = inQ.removeFirst()
            if (f[0] == 0xCAFE && f[1] == 0x0017) keepAlive = false
            if (f[0] == 0x5FFF) gotClose = true
            out.toGame(Mpk1.encode(Mpk1.CONNECTED, f))
            lastToGame = now
            inFlight++
        }
    }

    private fun endSession(now: Long) {
        if (!linked) return
        flush()
        linked = false; started = false; handshakeSent = false
        inQ.clear(); inFlight = 0
        if (keepAlive) out.status(CelioWire.LINK_RECONNECTING)
        else { finished = true; out.status(CelioWire.LINK_CLOSED) }
        setPeer(Mpk1.PREINIT, now)
    }

    private fun flush() {
        if (batch.isEmpty()) return
        val words = IntArray(CelioWire.PACKET_WORDS)
        batch.forEachIndexed { i, f -> f.copyInto(words, i * 8) }
        batch.clear()
        out.data(words)
    }

    private fun setPeer(state: Int, now: Long) {
        peer = state
        out.toGame(Mpk1.encode(state, null))
        lastToGame = now
    }

    private fun fail(why: String) {
        if (failed) return
        failed = true
        out.fail(why)
    }
}

// ---- WebSocket + Socket.IO ----------------------------------------------------------------------------------------

/** A text-only WebSocket, as [SocketIoClient] needs it (OkHttp on the phone, fakes in tests). */
interface WsConnector {
    fun connect(url: String, listener: WsListener): WsHandle
}

interface WsHandle {
    fun send(text: String): Boolean
    fun close()
}

interface WsListener {
    fun onText(text: String)
    fun onGone(why: String)
}

/** OkHttp's WebSocket. Sends nothing about the phone or the person: no cookies, a neutral User-Agent. */
class OkHttpWsConnector(private val client: okhttp3.OkHttpClient = shared) : WsConnector {
    override fun connect(url: String, listener: WsListener): WsHandle {
        val req = okhttp3.Request.Builder().url(url).header("User-Agent", "Shoalcade").build()
        val ws = client.newWebSocket(req, object : okhttp3.WebSocketListener() {
            override fun onMessage(webSocket: okhttp3.WebSocket, text: String) = listener.onText(text)
            override fun onClosing(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                listener.onGone("closed")
            }
            override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) = listener.onGone("closed")
            override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) =
                listener.onGone(t.javaClass.simpleName)
        })
        return object : WsHandle {
            override fun send(text: String) = ws.send(text)
            override fun close() { if (!ws.close(1000, null)) ws.cancel() }
        }
    }

    companion object {
        val shared: okhttp3.OkHttpClient by lazy {
            okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS) // Socket.IO's own ping (every 500 ms on Celio) is the liveness check
                .writeTimeout(10, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build()
        }
    }
}

/** Timings for [SocketIoClient] (Celio's own: client.ts emitWithRetry 1 s × 5; a dropped client is kept 5 s). */
data class SioTimings(
    val ackTimeoutMs: Long = 1_000,
    val ackRetries: Int = 5,
    /** Reconnect for this long after the socket drops (Celio-Server keeps the place for 5 s, client.ts). */
    val graceMs: Long = 4_500,
    val retryGapMs: Long = 500,
    /** A reconnect attempt with no Socket.IO connect after this long is dropped and tried again. */
    val attemptMs: Long = 2_000,
    /** First connection: give up after this long. */
    val connectMs: Long = 10_000,
    /** Slack on top of the server's pingInterval + pingTimeout before the socket counts as dead. */
    val pingSlackMs: Long = 500,
    /** Most emits waiting for their ack before the link counts as too slow. */
    val maxPending: Int = 512,
)

/**
 * A small Socket.IO v5 client (Engine.IO 4, WebSocket only, main namespace, JSON events), enough for Celio:
 * the server's ping is answered at once on the socket's own thread (Celio allows 2 s); every server emit is acked;
 * our emits with an ack are sent again (new ack id, same payload) until acked, and Celio removes the duplicates
 * by uuid or sequence. A dropped socket is reopened with the same clientId within [SioTimings.graceMs], and every
 * emit still waiting goes out again. [Listener] calls are made outside this client's lock, in arrival order.
 */
class SocketIoClient(
    private val url: String,
    private val clientId: String,
    private val connector: WsConnector,
    private val listener: Listener,
    private val timings: SioTimings = SioTimings(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    interface Listener {
        fun onConnect(first: Boolean)
        fun onEvent(name: String, arg: Any?)
        fun onLost(why: String)
    }

    private class Pending(val name: String, val args: List<Any?>, val onAck: ((JSONArray) -> Unit)?, val timeoutMs: Long, val retries: Int) {
        var at = 0L
        var tries = 0
    }

    private val lock = Object()
    private var ws: WsHandle? = null
    private var gen = 0
    private var connected = false
    private var everConnected = false
    private var pingWindow = 0L
    private var lastPing = 0L
    private var openedAt = 0L
    /** The socket dropped at [lostSince]; reconnecting until the grace time is up. */
    private var reconnecting = false
    private var lostSince = 0L
    private var nextTry = 0L
    private var closed = false
    private var lost: String? = null
    private var nextAck = 0
    private val pending = LinkedHashMap<Int, Pending>()

    val isConnected: Boolean get() = synchronized(lock) { connected }

    /** Emits still waiting for their ack. */
    val waiting: Int get() = synchronized(lock) { pending.size }

    fun start() = synchronized(lock) { openedAt = clock(); open() }

    /**
     * Sends event [name] with [args]. With [ack] (the default) it is sent again until the server acks it; [onAck] then
     * gets the ack's arguments. Without, it goes only if connected now. False if the client has stopped.
     */
    fun emit(name: String, args: List<Any?>, ack: Boolean = true, onAck: ((JSONArray) -> Unit)? = null,
             timeoutMs: Long = timings.ackTimeoutMs, retries: Int = timings.ackRetries): Boolean {
        var lostNow: String? = null
        synchronized(lock) {
            if (closed || lost != null) return false
            if (!ack) { if (connected) send(CelioWire.event(null, name, args)); return true }
            if (pending.size >= timings.maxPending) lostNow = failLocked(CelioText.TOO_SLOW)
            else sendPending(Pending(name, args, onAck, timeoutMs, retries))
        }
        lostNow?.let { listener.onLost(it); return false }
        return true
    }

    /** Retries, the ping watchdog and reconnects: call every few tens of milliseconds. */
    fun tick() {
        var lostNow: String? = null
        synchronized(lock) {
            if (closed || lost != null) return
            val now = clock()
            if (connected && now - lastPing > pingWindow + timings.pingSlackMs) drop()
            if (!connected) {
                when {
                    reconnecting && now - lostSince > timings.graceMs ->
                        lostNow = failLocked(if (everConnected) CelioText.LOST else CelioText.NO_SERVER)
                    !reconnecting && now - openedAt > timings.connectMs -> lostNow = failLocked(CelioText.NO_SERVER)
                    reconnecting && ws == null && now >= nextTry -> {
                        nextTry = now + timings.retryGapMs
                        openedAt = now
                        open()
                    }
                    reconnecting && ws != null && now - openedAt > timings.attemptMs -> drop() // hung attempt
                }
            } else {
                for ((id, p) in pending.entries.toList()) {
                    if (now - p.at <= p.timeoutMs) continue
                    pending.remove(id)
                    if (p.tries > p.retries) { lostNow = failLocked(CelioText.NOT_ANSWERING); break }
                    sendPending(p)
                }
            }
        }
        lostNow?.let { listener.onLost(it) }
    }

    /** Stops; [leave] first tells Celio we left the room. */
    fun close(leave: Boolean) = synchronized(lock) {
        if (closed) return
        closed = true
        if (connected && lost == null) {
            if (leave) send(CelioWire.event(null, "sessionLeft", emptyList()))
            send(CelioWire.LEAVE_NAMESPACE)
        }
        ws?.close()
        ws = null
        pending.clear()
    }

    private fun open() {
        val g = ++gen
        ws = connector.connect(url, object : WsListener {
            override fun onText(text: String) = handle(g, text)
            override fun onGone(why: String) = synchronized(lock) { if (g == gen && !closed && lost == null) drop() }
        })
    }

    /** The socket is gone (or dead): reconnect within the grace time. Pending emits stay. */
    private fun drop() {
        runCatching { ws?.close() }
        ws = null
        gen++ // anything still coming from the old socket is ignored
        connected = false
        val now = clock()
        if (!reconnecting) { reconnecting = true; lostSince = now; nextTry = now }
    }

    private fun handle(g: Int, text: String) {
        val after = ArrayList<() -> Unit>(2)
        synchronized(lock) {
            if (g != gen || closed || lost != null) return
            when (val p = CelioWire.parse(text)) {
                is CelioWire.Packet.Open -> {
                    pingWindow = p.pingIntervalMs + p.pingTimeoutMs
                    lastPing = clock()
                    send(CelioWire.connect(clientId))
                }
                CelioWire.Packet.Ping -> { lastPing = clock(); send(CelioWire.PONG) }
                CelioWire.Packet.Close -> drop()
                CelioWire.Packet.Connected -> {
                    val first = !everConnected
                    connected = true; everConnected = true; reconnecting = false; lastPing = clock()
                    // Emits still waiting go out again on the new socket (not counted as a retry).
                    val waiting = pending.values.toList()
                    pending.clear()
                    waiting.forEach { it.tries--; sendPending(it) }
                    after += { listener.onConnect(first) }
                }
                CelioWire.Packet.Disconnected -> failLocked(CelioText.LOST)?.let { w -> after += { listener.onLost(w) } }
                is CelioWire.Packet.ConnectError -> failLocked(CelioText.REFUSED)?.let { w -> after += { listener.onLost(w) } }
                is CelioWire.Packet.Event -> {
                    // Every server emit wants an ack, or Celio evicts the room after its retries (client.ts).
                    p.ackId?.let { send(CelioWire.ack(it, true)) }
                    after += { listener.onEvent(p.name, p.arg) }
                }
                is CelioWire.Packet.Ack -> pending.remove(p.id)?.onAck?.let { cb -> after += { cb(p.args) } }
                CelioWire.Packet.Other -> {}
            }
        }
        after.forEach { it() }
    }

    private fun sendPending(p: Pending) {
        val id = nextAck++
        p.at = clock()
        p.tries++
        pending[id] = p
        if (connected) send(CelioWire.event(id, p.name, p.args))
    }

    private fun send(text: String) { ws?.send(text) }

    /** Marks the client lost; returns the reason for the caller to pass on outside the lock (null if already). */
    private fun failLocked(why: String): String? {
        if (lost != null || closed) return null
        lost = why
        runCatching { ws?.close() }
        ws = null
        pending.clear()
        return why
    }
}

// ---- One Celio room, from the lobby to the end of the game --------------------------------------------------------

/**
 * A Celio room and its link: Socket.IO to the server, [CelioBridge] in the middle, and (once the game takes it) the
 * shim's socketpair. Made in the lobby (so the room and partner are there before the game starts) and handed to
 * [GameActivity] in a [LinkConnection]. Sends Celio only what its protocol needs: a random clientId (new each time,
 * never stored), the room number, link statuses with random uuids, and the game's link frames. No name, no account,
 * no Jellyfin anything.
 */
class CelioSession(
    private val socketUrl: String,
    connector: WsConnector,
    private val timings: CelioTimings = CelioTimings(),
    sioTimings: SioTimings = SioTimings(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val tickMs: Long = 16,
    /** How long to wait after our LinkClosed for the partner's, before leaving the room (gen1recomp celio.lua L255). */
    private val lingerMs: Long = 5_000,
) : Closeable {
    sealed interface Status {
        data object Connecting : Status
        /** In room [room]; [partner]: the PC player is in it too. */
        data class InRoom(val room: String, val partner: Boolean) : Status
        /** Both games closed the link: the room is over (the game keeps going, unlinked). */
        data object Finished : Status
        data class Lost(val why: String) : Status
    }

    private val lock = Object()
    private val _status = MutableStateFlow<Status>(Status.Connecting)
    val status: StateFlow<Status> = _status

    @Volatile var room: String? = null; private set
    private var joinRoom: String? = null
    private var outSequence = 0L
    private var expected = 0L
    private val buffered = HashMap<Long, IntArray>()
    private val seen = LinkedHashSet<String>()
    @Volatile private var stopped = false
    private var ticker: Thread? = null

    // The shim's end (once the game takes it).
    @Volatile private var toGame: ArrayBlockingQueue<ByteArray>? = null
    private var closeLocal: (() -> Unit)? = null
    private var localThreads: List<Thread> = emptyList()

    private val out = object : CelioBridge.Out {
        override fun toGame(packet: ByteArray) {
            val q = toGame ?: return // the game hasn't started yet
            if (!q.offer(packet)) lose(CelioText.TOO_SLOW)
        }
        override fun status(linkStatus: Int) {
            sio.emit("deviceStatus", listOf(CelioWire.status(UUID.randomUUID().toString(), linkStatus)), onAck = refused)
        }
        override fun data(words: IntArray) {
            // One packet per emit, as the live Celio-Client sends them (and the mGBA builds parse them).
            val seq = synchronized(lock) { outSequence++ }
            sio.emit("deviceData", listOf(CelioWire.data(seq, words)), onAck = refused)
        }
        override fun fail(why: String) = lose(why)
    }

    /** Celio acks a status or data packet it rejected with false (session.ts:71-110): say so, don't fail silently. */
    private val refused = { a: JSONArray -> if (a.opt(0) == false) lose(CelioText.DATA_REFUSED) }

    val bridge = CelioBridge(out, timings)

    private val sio = SocketIoClient(socketUrl, UUID.randomUUID().toString(), connector, object : SocketIoClient.Listener {
        override fun onConnect(first: Boolean) { if (first) enterRoom() }
        override fun onEvent(name: String, arg: Any?) = event(name, arg)
        override fun onLost(why: String) = lose(why)
    }, sioTimings, clock)

    /** Connects and makes a room ([join] null) or joins room [join]. */
    fun start(join: String?) {
        joinRoom = join
        sio.start()
        ticker = Thread({
            while (!stopped) {
                runCatching { sio.tick(); bridge.tick(clock()) }
                try { Thread.sleep(tickMs) } catch (_: InterruptedException) { break }
            }
        }, "celio tick").apply { isDaemon = true; start() }
    }

    /** Status line for the game screen. */
    fun line(): String = when (val s = _status.value) {
        Status.Connecting -> CelioText.CONNECTING
        is Status.Lost -> "⚠️ " + CelioText.LINK_LOST
        Status.Finished -> "🔗 " + CelioText.FINISHED
        is Status.InRoom -> if (!s.partner) "🔗 ${CelioText.ROOM} ${s.room} · ${CelioText.WAITING}" else when (bridge.phase) {
            CelioBridge.Phase.LINKED -> "🔗 " + CelioText.LINKED
            CelioBridge.Phase.LINKING -> "🔗 " + CelioText.LINKING
            CelioBridge.Phase.FINISHED -> "🔗 " + CelioText.FINISHED
            else -> "🔗 ${CelioText.ROOM} ${s.room} · ${CelioText.GO_LINK}"
        }
    }

    /**
     * The game took its end of the socketpair: [input]/[output] are ours. MPK1 packets are framed as linkshim.c
     * frames them (u32 big-endian length, then the packet). Either side ending ends the link.
     */
    fun attachLocal(input: InputStream, output: OutputStream, close: () -> Unit) {
        val q = ArrayBlockingQueue<ByteArray>(1024)
        synchronized(lock) {
            if (stopped) { runCatching(close); return }
            closeLocal = close
            toGame = q
        }
        bridge.attached(clock())
        val reader = Thread({
            val din = DataInputStream(input)
            try {
                while (true) {
                    val len = din.readInt()
                    if (len <= 0 || len > 64 * 1024) throw IOException("bad packet")
                    val b = ByteArray(len)
                    din.readFully(b)
                    bridge.fromGame(b, clock())
                }
            } catch (_: EOFException) {
            } catch (_: IOException) {
            }
            close() // the game has ended
        }, "celio from game")
        val writer = Thread({
            try {
                while (true) {
                    val p = q.take()
                    if (p.isEmpty()) break
                    output.write(ByteBuffer.allocate(4 + p.size).putInt(p.size).put(p).array())
                    output.flush()
                }
            } catch (_: IOException) {
                close()
            } catch (_: InterruptedException) {
            }
        }, "celio to game")
        localThreads = listOf(reader, writer)
        localThreads.forEach { it.isDaemon = true; it.start() }
    }

    private fun enterRoom() {
        val code = joinRoom
        val onAnswer = { a: JSONArray ->
            when (val r = CelioWire.roomResult(a)) {
                is CelioWire.RoomResult.Ok -> {
                    room = r.room
                    if (r.full) bridge.partnerJoined() // joined a room the PC player made: they're already in it
                    setStatus(Status.InRoom(r.room, r.full))
                }
                is CelioWire.RoomResult.Refused -> lose(r.reason)
            }
        }
        // Sent once: a repeat of sessionCreate would be refused ("Session already exists").
        if (code == null) sio.emit("sessionCreate", listOf(null), onAck = onAnswer, timeoutMs = 8_000, retries = 0)
        else sio.emit("sessionJoin", listOf(code), onAck = onAnswer, timeoutMs = 8_000, retries = 0)
    }

    private fun event(name: String, arg: Any?) {
        val now = clock()
        when (name) {
            "deviceData" -> {
                val ready = ArrayList<IntArray>()
                synchronized(lock) {
                    for ((seq, words) in CelioWire.dataPackets(arg)) {
                        if (seq < expected || buffered.containsKey(seq)) continue // a retry we already have
                        buffered[seq] = words
                    }
                    while (true) { ready += buffered.remove(expected) ?: break; expected++ }
                    if (buffered.size > 512) { lose(CelioText.TOO_SLOW); return }
                }
                ready.forEach { bridge.fromPartner(it, now) }
            }
            "deviceCommand" -> {
                val (uuid, c) = CelioWire.command(arg) ?: return
                if (firstTime(uuid)) bridge.command(c, now)
            }
            "partnerJoined" -> if (firstTime(CelioWire.uuid(arg))) {
                bridge.partnerJoined()
                room?.let { setStatus(Status.InRoom(it, true)) }
            }
            "partnerLeft" -> if (firstTime(CelioWire.uuid(arg))) {
                bridge.partnerLeft()
                room?.let { setStatus(Status.InRoom(it, false)) }
            }
            "sessionClose" -> if (firstTime(CelioWire.uuid(arg))) {
                // The room is over. After both games closed the link that's the normal end; otherwise the PC left.
                if (bridge.closedForGood) {
                    setStatus(Status.Finished)
                    sio.close(leave = false)
                } else lose(CelioText.PARTNER_LEFT)
            }
        }
    }

    /** False for a uuid already seen (Celio retries its emits). Events without one always count. */
    private fun firstTime(uuid: String): Boolean = synchronized(lock) {
        if (uuid.isEmpty()) return true
        if (!seen.add(uuid)) return false
        if (seen.size > 1024) seen.remove(seen.first())
        true
    }

    private fun setStatus(s: Status) = synchronized(lock) {
        val now = _status.value
        if (now is Status.Lost || (now == Status.Finished && s !is Status.Lost)) return
        _status.value = s
    }

    /** Lost for good: the game's link ends ("Link lost") and the room is left. Never throws. */
    private fun lose(why: String) {
        synchronized(lock) {
            if (_status.value is Status.Lost) return
            _status.value = Status.Lost(why)
        }
        runCatching { sio.close(leave = true) }
        stopLocal()
    }

    private fun stopLocal() {
        val (c, q) = synchronized(lock) { (closeLocal to toGame).also { closeLocal = null; toGame = null } }
        q?.clear()
        q?.offer(ByteArray(0))
        runCatching { c?.invoke() }
    }

    /**
     * Ends the link (the game closed, or the lobby was left). After a trade the room is left only once the partner has
     * closed too, or after [lingerMs], so its GBA sees our last frames; this runs in the background.
     */
    override fun close() {
        if (stopped) return
        stopped = true
        ticker?.interrupt()
        stopLocal()
        val s = _status.value
        if (s is Status.Lost || s == Status.Finished) { sio.close(leave = false); return }
        if (bridge.sessions == 0) { sio.close(leave = true); return }
        Thread({
            if (!bridge.closedForGood) out.status(CelioWire.LINK_CLOSED)
            val until = clock() + lingerMs
            while (clock() < until && _status.value !is Status.Lost && _status.value != Status.Finished) {
                runCatching { sio.tick() }
                try { Thread.sleep(50) } catch (_: InterruptedException) { break }
            }
            sio.close(leave = true)
        }, "celio leave").apply { isDaemon = true; start() }
    }

    /** For tests: the shim-side threads have finished. */
    fun joinLocal(ms: Long) = localThreads.forEach { it.join(ms) }
}

/** Celio Link words (docs/wording.md, "Celio Link (app)"). */
object CelioText {
    // Settings
    const val HEADING = "Link play"
    const val SWITCH = "Celio Link (trade with PC players)"
    const val SWITCH_HINT = "Link data and your IP address go to the Celio Link server, a service not run by Shoalcade or your Jellyfin."
    const val SERVER = "Celio Link server"
    const val USE_PUBLIC = "Use the public server"
    const val BAD_SERVER = "Type a wss:// server address (ws:// only for 127.0.0.1 or 10.0.2.2 tests)."

    // Link play dialog
    const val TITLE = "Trade with a PC player (Celio Link)"
    const val INTRO = "For a PC player on mGBA Celio Edition, or a real GBA on a Celio adapter. Link cable trades only."
    const val MAKE = "Make a room"
    const val JOIN = "Join a room"
    const val ROOM_FOR_PC = "Room number for the PC player"
    const val ROOM_HELP = "On the PC, join a Celio Link room and type this number."
    const val ROOM_FROM_PC = "Room number from the PC player"
    const val JOIN_BUTTON = "Join"

    // Status lines (connecting, waiting for the other player, linked, link lost)
    const val CONNECTING = "Connecting to Celio Link…"
    const val WAITING = "Waiting for the other player…"
    const val ROOM = "Celio room"
    const val GO_LINK = "start the link in both games"
    const val LINKING = "Linking…"
    const val LINKED = "Linked to the PC player (Celio Link)"
    const val LINK_LOST = "Link lost"
    const val FINISHED = "Link finished. To trade again, quit and make a new room."
    const val PEER = "the PC player"

    // Problems
    const val NO_ROOM = "No room with that number. Check the number on the PC."
    const val ROOM_FULL = "That room already has two players."
    const val ROOM_DIGITS = "Room numbers are 4 digits."
    const val ODD_ANSWER = "The Celio Link server sent an answer the app doesn't understand."
    const val NO_SERVER = "Couldn't reach the Celio Link server. Check the phone's internet connection."
    const val LOST = "Lost touch with the Celio Link server. Check the phone's internet connection."
    const val NOT_ANSWERING = "The Celio Link server stopped answering."
    const val REFUSED = "The Celio Link server refused the connection."
    const val PARTNER_LEFT = "The PC player left."
    const val TOO_SLOW = "The link was too slow, so it stopped."
    const val DATA_REFUSED = "The Celio Link server didn't accept this phone's link data. It may have changed: check for a Shoalcade update."
    const val BOTH_LEAD = "The other player's device also wants to lead the link, so they can't link. Two Shoalcade phones can't meet over Celio Link: use Link play over the internet instead."
}
