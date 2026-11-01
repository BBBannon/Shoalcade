package uk.benmarks.shoalcade

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * Internet link play through the person's own Jellyfin (plugin feature "link-relay", GBA only for now).
 * The plugin is a dumb byte pipe: the PIN handshake ([LinkHandshake], D133) runs end to end on top of [RelayPipe],
 * then [RelayLinkPump] copies between the pipe and one end of a socketpair whose other end the native shim
 * (linkshim.c, unchanged) uses exactly like the Wi-Fi socket.
 */
object RelayWire {
    const val FEATURE = "link-relay"

    // Down-stream records: [type: 1][length: 4, big-endian][payload] (the plugin's LinkRecord).
    const val HEARTBEAT = 0
    const val DATA = 1
    const val PEER_JOINED = 2
    const val PEER_LEFT = 3
    const val CLOSED = 4

    const val MAX_POST = 64 * 1024
    private const val MAX_RECORD = 1024 * 1024

    const val SEAT_HEADER = "X-Shoalcade-Seat"
    const val FROM_HEADER = "X-Shoalcade-From"
    const val SEQ_HEADER = "X-Shoalcade-Seq"
    const val GEN_HEADER = "X-Shoalcade-Gen"

    /** Room code letters (the plugin's): no 0/O or 1/I. */
    const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    const val CODE_LENGTH = 6

    class Record(val type: Int, val payload: ByteArray) {
        /** Bytes this record took on the stream (for carrying on after a dropped connection). */
        val size get() = 5 + payload.size
        val gen get() = if (payload.size >= 4) java.nio.ByteBuffer.wrap(payload).int else -1
        val text get() = String(payload, Charsets.US_ASCII)
    }

    /** The next record, or null at a clean end of stream (between records). A cut-off record throws. */
    fun read(input: InputStream): Record? {
        val head = ByteArray(5)
        val first = input.read()
        if (first < 0) return null
        head[0] = first.toByte()
        readFully(input, head, 1, 4)
        val len = java.nio.ByteBuffer.wrap(head, 1, 4).int
        if (len < 0 || len > MAX_RECORD) throw IOException("bad link record")
        val payload = ByteArray(len)
        readFully(input, payload, 0, len)
        return Record(first, payload)
    }

    fun record(type: Int, payload: ByteArray = ByteArray(0)): ByteArray =
        java.nio.ByteBuffer.allocate(5 + payload.size).put(type.toByte()).putInt(payload.size).put(payload).array()

    fun genRecord(type: Int, gen: Int): ByteArray = record(type, java.nio.ByteBuffer.allocate(4).putInt(gen).array())

    private fun readFully(input: InputStream, b: ByteArray, off: Int, len: Int) {
        var n = 0
        while (n < len) {
            val r = input.read(b, off + n, len - n)
            if (r < 0) throw EOFException("link stream cut off")
            n += r
        }
    }

    /** What a typed room code means: upper case, spaces and dashes dropped; null if it can't be one. */
    fun cleanCode(typed: String): String? {
        val c = typed.filter { it != ' ' && it != '-' }.uppercase()
        return c.takeIf { it.length == CODE_LENGTH && it.all { ch -> ch in CODE_ALPHABET } }
    }

    /** Text box filter: keeps only room code letters, at most 6. */
    fun typingCode(typed: String): String = typed.uppercase().filter { it in CODE_ALPHABET }.take(CODE_LENGTH)

    /** "K7QM2P" → "K7Q M2P", easier to read across a room (TalkBack reads it letter by letter). */
    fun showCode(code: String): String = if (code.length == CODE_LENGTH) code.take(3) + " " + code.drop(3) else code

    /** Plain words for the reason in a Closed record. */
    fun closedReason(code: String): String = when (code) {
        "host_left", "host_gone" -> PARTNER_LEFT
        "left", "gone", "sent_away" -> PARTNER_LEFT
        "expired" -> "Nobody joined in 10 minutes, so the room closed. Tap Host over the internet again."
        "replaced" -> "This room was closed because you opened another one."
        "overflow" -> "The link was too slow, so it stopped."
        else -> ENDED
    }

    const val PARTNER_LEFT = "The other phone left."
    const val ENDED = "That link has ended."
    const val NO_ANSWER = "Lost touch with your Jellyfin. Check the phone's internet connection."
}

/** A seat in a room on the plugin. [seat] is secret to this phone and only ever sent in a header. */
data class RelaySeat(val code: String, val seat: String, val gen: Int)

/** The plugin refused (wrong room code, full room, too many tries…): [message] is plain English. */
class RelayRefusedException(message: String) : IOException(message)

/** The room or seat has gone on the plugin: no point retrying. */
class RelayGoneException(message: String) : IOException(message)

/** Over the plugin's rate limit: send the same thing again shortly. */
class RelayBusyException : IOException("busy")

/** The seat's calls to the plugin, as [RelayPipe] needs them (faked in tests). */
interface RelayTransport {
    /** Opens the down stream from byte [from] of this seat's records. Closing it ends the connection. */
    fun openDown(from: Long): InputStream

    /** Sends one batch. False = the plugin took it but the other phone of [gen] had already left. */
    fun up(seq: Long, gen: Int, data: ByteArray): Boolean

    /** "Still here" (the plugin drops a seat after 30 s without a call). */
    fun keepAlive()

    /** Host: send away the joiner of [gen] (wrong PIN, not a Shoalcade phone); the seat is free again. */
    fun kick(gen: Int)

    /** Leave the room: a joiner frees its seat, the host closes the room. */
    fun leave()
}

/**
 * HTTP to the plugin's `/shoalcade/link` routes. The Jellyfin token only goes in the Authorization header (built by
 * [JellyfinClient]); the seat token only in [RelayWire.SEAT_HEADER]. Nothing here logs.
 */
class RelayHttp(
    private val serverUrl: String,
    private val authorization: String,
    private val connectTimeoutMs: Int = 10_000,
    /** Three missed heartbeats (the plugin sends one every 15 s). */
    private val downReadTimeoutMs: Int = 45_000,
    private val callTimeoutMs: Int = 15_000,
) {
    fun create(console: Console): RelaySeat = seat(post("/shoalcade/link/rooms", JSONObject().put("console", console.name)))

    fun join(code: String, console: Console): RelaySeat =
        seat(post("/shoalcade/link/join", JSONObject().put("console", console.name).put("code", code)))

    fun transport(seat: RelaySeat): RelayTransport = object : RelayTransport {
        override fun openDown(from: Long): InputStream {
            val c = open("/shoalcade/link/down", "GET", seat.seat, downReadTimeoutMs)
            c.setRequestProperty(RelayWire.FROM_HEADER, from.toString())
            c.setRequestProperty("Accept", "application/octet-stream")
            check(c)
            val body = c.inputStream
            return object : java.io.FilterInputStream(body) {
                override fun close() { runCatching { c.disconnect() }; runCatching { body.close() } }
            }
        }

        override fun up(seq: Long, gen: Int, data: ByteArray): Boolean {
            val c = open("/shoalcade/link/up", "POST", seat.seat, callTimeoutMs)
            c.setRequestProperty(RelayWire.SEQ_HEADER, seq.toString())
            c.setRequestProperty(RelayWire.GEN_HEADER, gen.toString())
            return sendBytes(c, data)
        }

        override fun keepAlive() { sendBytes(open("/shoalcade/link/up", "POST", seat.seat, callTimeoutMs), ByteArray(0)) }

        override fun kick(gen: Int) {
            val c = open("/shoalcade/link/kick", "POST", seat.seat, callTimeoutMs)
            c.setRequestProperty(RelayWire.GEN_HEADER, gen.toString())
            sendBytes(c, ByteArray(0))
        }

        override fun leave() {
            val c = open("/shoalcade/link/seat", "DELETE", seat.seat, callTimeoutMs)
            try { c.responseCode } finally { c.disconnect() }
        }
    }

    private fun open(path: String, method: String, seat: String?, readMs: Int): HttpURLConnection =
        (URL(serverUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = readMs
            useCaches = false
            setRequestProperty("Authorization", authorization)
            if (seat != null) setRequestProperty(RelayWire.SEAT_HEADER, seat)
        }

    /** POSTs [data]; true = 204, false = the other phone had left (409 peer_gone). */
    private fun sendBytes(c: HttpURLConnection, data: ByteArray): Boolean {
        try {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/octet-stream")
            c.setFixedLengthStreamingMode(data.size)
            c.outputStream.use { it.write(data) }
            if (c.responseCode == 409 && error(c).first == "peer_gone") return false
            check(c)
            c.inputStream.use { it.readBytes() }
            return true
        } finally {
            c.disconnect()
        }
    }

    private fun post(path: String, body: JSONObject): JSONObject {
        val c = open(path, "POST", null, callTimeoutMs)
        try {
            val bytes = body.toString().toByteArray(Charsets.UTF_8)
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use { it.write(bytes) }
            val code = c.responseCode
            if (code !in 200..299) {
                val (_, message) = error(c)
                throw RelayRefusedException(when {
                    code == 401 -> "Your login has expired. Please log in again."
                    message != null && code in listOf(404, 409, 410, 422, 429, 503) -> message
                    code == 404 -> "This Jellyfin's Shoalcade plugin can't do internet link play. Update the plugin."
                    else -> "Jellyfin refused the link (error $code)."
                })
            }
            return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally {
            c.disconnect()
        }
    }

    private fun seat(o: JSONObject): RelaySeat {
        val code = o.optString("code")
        val seat = o.optString("seat")
        if (RelayWire.cleanCode(code) != code || !Regex("[0-9a-f]{32}").matches(seat)) throw IOException("Jellyfin sent a reply the app doesn't understand.")
        return RelaySeat(code, seat, o.optInt("gen"))
    }

    /** 2xx goes on; 404/409/410 = the room or seat has gone; 429 = busy; anything else is a retryable failure. */
    private fun check(c: HttpURLConnection) {
        val code = c.responseCode
        if (code in 200..299) return
        val (err, message) = error(c)
        c.disconnect()
        when (code) {
            429 -> throw RelayBusyException()
            404, 409, 410 -> throw RelayGoneException(message ?: RelayWire.ENDED)
            401 -> throw RelayGoneException("Your login has expired. Please log in again.")
            else -> throw IOException("Jellyfin error $code ${err ?: ""}".trim())
        }
    }

    private fun error(c: HttpURLConnection): Pair<String?, String?> = runCatching {
        val o = JSONObject(c.errorStream?.bufferedReader()?.use { it.readText() } ?: "{}")
        o.optString("error").ifEmpty { null } to o.optString("message").ifEmpty { null }
    }.getOrDefault(null to null)
}

/** Timings for [RelayPipe] (tests shrink them). */
data class RelayTimings(
    /** A "still here" call this often when nothing else was sent (the plugin drops a seat after 30 s). */
    val keepAliveMs: Long = 10_000,
    /** At least this long between uploads, so bytes that arrive meanwhile go up together (≤ 40 uploads a second). */
    val minPostGapMs: Long = 25,
    /** How long to keep retrying after the network goes before the link counts as lost (gpSP itself waits 4 s). */
    val retryWindowMs: Long = 12_000,
    val firstBackoffMs: Long = 200,
    val maxBackoffMs: Long = 2_000,
    /** Most bytes waiting either way before the link is dropped (the other side stopped reading). */
    val maxQueued: Int = 256 * 1024,
)

/**
 * One phone's end of a relay link, as plain streams: [input] is what the other phone sent, [output] goes to it.
 * Down: one long streaming GET, carried on from the last byte after a drop. Up: everything written since the last
 * upload goes in the next numbered POST (one at a time, so a slow network makes bigger batches, never a queue of
 * requests); a failed POST is sent again with the same number, which the plugin ignores if it already had it.
 * Lost for good (and [onLost] once) when the room closes or the network is gone for [RelayTimings.retryWindowMs].
 *
 * Host only: [awaitJoin] waits for a phone to take the seat; bytes from or for an earlier joiner are dropped
 * (generations), and when that joiner leaves [input] reads as ended until the next one joins.
 */
class RelayPipe(
    private val transport: RelayTransport,
    gen: Int,
    private val timings: RelayTimings = RelayTimings(),
    private val clock: () -> Long = System::currentTimeMillis,
) : Closeable {
    private val lock = Object()
    private val inQ = ArrayDeque<ByteArray>()
    private var inHead = 0
    private var inBytes = 0
    private var inEnded = false
    private val outQ = ByteArrayOutputStream()
    private var inFlight = false
    /** Host: the newest joiner not yet picked up by [awaitJoin] (an older one that already left is never offered). */
    private var pendingJoin: Int? = null
    private var closed = false
    @Volatile private var down: InputStream? = null
    private var threads: List<Thread> = emptyList()

    /** The generation bytes go to (the host's changes each time a phone joins). */
    @Volatile var gen: Int = gen; private set

    /** Why the link ended for good, or null while it's up. */
    @Volatile var lost: String? = null; private set

    /** Longest wait in [input] reads (0 = forever); a timeout throws [SocketTimeoutException]. */
    @Volatile var readTimeoutMs: Long = 0

    @Volatile var onLost: ((String) -> Unit)? = null

    fun start() {
        threads = listOf(Thread(::downLoop, "link relay down"), Thread(::upLoop, "link relay up"))
        threads.forEach { it.isDaemon = true; it.start() }
    }

    /** Host: blocks until a phone takes the seat; its generation. Throws if the link is lost or closed. */
    fun awaitJoin(timeoutMs: Long = 0): Int? = synchronized(lock) {
        val until = if (timeoutMs > 0) clock() + timeoutMs else Long.MAX_VALUE
        while (pendingJoin == null) {
            lost?.let { throw IOException(it) }
            if (closed) throw IOException(RelayWire.ENDED)
            val left = until - clock()
            if (left <= 0) return null
            lock.wait(minOf(left, 1_000))
        }
        pendingJoin.also { pendingJoin = null }
    }

    /** Host: sends the joiner of [gen] away after what was written has gone up (so it sees "Wrong PIN" first). */
    fun kick(gen: Int, flushMs: Long = 5_000) {
        runCatching { flushOut(flushMs) }
        runCatching { transport.kick(gen) }
    }

    /** Blocks until everything written has reached the plugin (or [timeoutMs] passes). */
    fun flushOut(timeoutMs: Long): Unit = synchronized(lock) {
        val until = clock() + timeoutMs
        while ((outQ.size() > 0 || inFlight) && lost == null && !closed) {
            val left = until - clock()
            if (left <= 0) throw SocketTimeoutException("link upload timed out")
            lock.wait(minOf(left, 200))
        }
        lost?.let { throw IOException(it) }
    }

    val input: InputStream = object : InputStream() {
        override fun read(): Int {
            val b = ByteArray(1)
            return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int = synchronized(lock) {
            if (len == 0) return 0
            val t = readTimeoutMs
            val until = if (t > 0) clock() + t else Long.MAX_VALUE
            while (inQ.isEmpty()) {
                lost?.let { throw IOException(it) }
                if (inEnded || closed) return -1
                val left = until - clock()
                if (left <= 0) throw SocketTimeoutException("the other phone didn't answer")
                lock.wait(minOf(left, 1_000))
            }
            val head = inQ.first()
            val n = minOf(len, head.size - inHead)
            System.arraycopy(head, inHead, b, off, n)
            inHead += n
            inBytes -= n
            if (inHead == head.size) { inQ.removeFirst(); inHead = 0 }
            n
        }

        override fun available(): Int = synchronized(lock) { inBytes }
    }

    /** Writes never wait for the network; [OutputStream.flush] waits until it has all gone up (the handshake uses it). */
    val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            synchronized(lock) {
                lost?.let { throw IOException(it) }
                if (closed) throw IOException(RelayWire.ENDED)
                if (outQ.size() + len > timings.maxQueued) { loseLocked("The link was too slow, so it stopped."); throw IOException(lost) }
                outQ.write(b, off, len)
                lock.notifyAll()
            }
        }

        override fun flush() { flushOut(timings.retryWindowMs) }
    }

    /** Leaves the room (in the background: this may be called on the main thread) and stops. */
    override fun close() {
        val first = synchronized(lock) {
            if (closed) return
            closed = true
            lock.notifyAll()
            lost == null
        }
        runCatching { down?.close() }
        if (first) Thread({ runCatching { transport.leave() } }, "link relay leave").apply { isDaemon = true }.start()
    }

    /** For tests: waits for both loops to finish. */
    fun join(ms: Long) = threads.forEach { it.join(ms) }

    private fun lose(why: String) = synchronized(lock) { loseLocked(why) }

    private fun loseLocked(why: String) {
        if (lost != null || closed) return
        lost = why
        lock.notifyAll()
        runCatching { down?.close() }
        Thread({
            runCatching { transport.leave() }
            onLost?.invoke(why)
        }, "link relay lost").apply { isDaemon = true }.start()
    }

    private val stopped get() = synchronized(lock) { closed || lost != null }

    private fun downLoop() {
        var offset = 0L
        var failingSince = 0L
        var backoff = timings.firstBackoffMs
        while (!stopped) {
            try {
                val s = transport.openDown(offset)
                down = s
                if (stopped) { runCatching { s.close() }; return }
                s.use {
                    while (true) {
                        val r = RelayWire.read(it) ?: throw EOFException("the link stream ended")
                        offset += r.size
                        failingSince = 0L
                        backoff = timings.firstBackoffMs
                        if (!onRecord(r)) return
                    }
                }
            } catch (e: RelayGoneException) {
                lose(e.message ?: RelayWire.ENDED); return
            } catch (e: IOException) {
                if (stopped) return
                val now = clock()
                if (failingSince == 0L) failingSince = now
                if (now - failingSince > timings.retryWindowMs) { lose(RelayWire.NO_ANSWER); return }
                Thread.sleep(backoff)
                backoff = minOf(backoff * 2, timings.maxBackoffMs)
            }
        }
    }

    /** False = the room closed. */
    private fun onRecord(r: RelayWire.Record): Boolean {
        when (r.type) {
            RelayWire.DATA -> synchronized(lock) {
                if (inEnded) return true // the joiner it came from has left
                if (inBytes + r.payload.size > timings.maxQueued) { loseLocked("The link was too slow, so it stopped."); return false }
                inQ.addLast(r.payload)
                inBytes += r.payload.size
                lock.notifyAll()
            }
            RelayWire.PEER_JOINED -> synchronized(lock) {
                // A new joiner: nothing from or for the last one may reach it.
                inQ.clear(); inHead = 0; inBytes = 0; inEnded = false
                outQ.reset()
                gen = r.gen
                pendingJoin = r.gen
                lock.notifyAll()
            }
            RelayWire.PEER_LEFT -> synchronized(lock) {
                if (pendingJoin == r.gen) pendingJoin = null
                if (r.gen == gen) {
                    inEnded = true
                    outQ.reset()
                    lock.notifyAll()
                }
            }
            RelayWire.CLOSED -> { lose(RelayWire.closedReason(r.text)); return false }
        }
        return true
    }

    private fun upLoop() {
        var seq = 0L
        var lastCall = clock()
        var lastPost = 0L
        while (true) {
            // Wait for bytes (or the keep-alive time).
            val hasData = synchronized(lock) {
                while (outQ.size() == 0 && !closed && lost == null) {
                    val left = lastCall + timings.keepAliveMs - clock()
                    if (left <= 0) break
                    lock.wait(minOf(left, 1_000))
                }
                if (closed || lost != null) return
                outQ.size() > 0
            }
            if (!hasData) {
                if (call { transport.keepAlive(); true } == null) return
                lastCall = clock()
                continue
            }
            // Let a few more bytes arrive, so they share one upload.
            val gap = lastPost + timings.minPostGapMs - clock()
            if (gap > 0) Thread.sleep(gap)
            val (batch, g) = synchronized(lock) {
                if (closed || lost != null) return
                val all = outQ.toByteArray()
                val n = minOf(all.size, RelayWire.MAX_POST)
                outQ.reset()
                if (n < all.size) outQ.write(all, n, all.size - n)
                inFlight = true
                all.copyOf(n) to gen
            }
            seq++
            val ok = call { transport.up(seq, g, batch) }
            synchronized(lock) { inFlight = false; lock.notifyAll() }
            if (ok == null) return
            lastPost = clock()
            lastCall = lastPost
        }
    }

    /** Runs one call, retrying (same arguments) through network trouble; null = lost or closed. */
    private fun <T> call(block: () -> T): T? {
        var failingSince = 0L
        var backoff = timings.firstBackoffMs
        while (!stopped) {
            try {
                return block()
            } catch (e: RelayGoneException) {
                lose(e.message ?: RelayWire.ENDED); return null
            } catch (e: IOException) {
                val now = clock()
                if (failingSince == 0L) failingSince = now
                if (now - failingSince > timings.retryWindowMs) { lose(RelayWire.NO_ANSWER); return null }
                Thread.sleep(backoff)
                backoff = minOf(backoff * 2, timings.maxBackoffMs)
            }
        }
        return null
    }
}

/**
 * Copies between a [RelayPipe] and the app's end of the socketpair the shim uses: what the shim writes goes up,
 * what arrives goes to the shim. Either side ending ends both: the shim then sees "other phone left", exactly as
 * when a Wi-Fi socket closes. [closeLocal] must unblock a read on [localIn] (closing the fd does).
 */
class RelayLinkPump(
    private val pipe: RelayPipe,
    private val localIn: InputStream,
    private val localOut: OutputStream,
    private val closeLocal: () -> Unit,
) : Closeable {
    private val done = java.util.concurrent.atomic.AtomicBoolean(false)
    private var threads: List<Thread> = emptyList()

    fun start() {
        threads = listOf(
            Thread({ copy(localIn, pipe.output, flush = false) }, "link pump up"),
            Thread({ copy(pipe.input, localOut, flush = true) }, "link pump down"),
        )
        threads.forEach { it.isDaemon = true; it.start() }
    }

    private fun copy(from: InputStream, to: OutputStream, flush: Boolean) {
        val buf = ByteArray(16 * 1024)
        try {
            while (true) {
                val n = from.read(buf)
                if (n < 0) break
                to.write(buf, 0, n)
                if (flush) to.flush()
            }
        } catch (_: IOException) {
        } finally {
            close()
        }
    }

    override fun close() {
        if (!done.compareAndSet(false, true)) return
        runCatching { pipe.close() }
        runCatching { closeLocal() }
    }

    /** For tests: waits for both copies to finish. */
    fun join(ms: Long) = threads.forEach { it.join(ms) }
}
