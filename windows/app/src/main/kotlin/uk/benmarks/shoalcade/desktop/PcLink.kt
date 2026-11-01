package uk.benmarks.shoalcade.desktop

import uk.benmarks.shoalcade.CelioSession
import uk.benmarks.shoalcade.LinkConnection
import uk.benmarks.shoalcade.LinkHandshake
import uk.benmarks.shoalcade.LinkRules
import uk.benmarks.shoalcade.RelayLinkPump
import uk.benmarks.shoalcade.RelayHttp
import uk.benmarks.shoalcade.RelayPipe
import uk.benmarks.shoalcade.RelaySeat
import uk.benmarks.shoalcade.RelayTransport
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** The PC's own link rules (the shared ones are the phone's [LinkRules]). */
object PcLinkRules {
    /**
     * Adapters a PC may host on: the phone's rule ([LinkRules.isLinkInterface]: never mobile data, a VPN or loopback),
     * plus Windows' virtual and VPN adapters, which Java names like real ones ("eth3"), so their description decides.
     */
    fun isLinkInterface(name: String, description: String?): Boolean {
        if (!LinkRules.isLinkInterface(name)) return false
        val d = (description ?: "").lowercase()
        return VIRTUAL.none { it in d }
    }

    private val VIRTUAL = listOf(
        "virtual", "vmware", "virtualbox", "vbox", "hyper-v", "vethernet", "wsl", "docker", "vpn", "tap-windows", "tap-",
        "wireguard", "tailscale", "zerotier", "hamachi", "openvpn", "wintun", "loopback", "bluetooth", "teredo", "isatap",
    )

    /** This PC's home network IPv4 addresses (Wi-Fi or Ethernet; never a VPN or virtual adapter): where a host listens. */
    fun linkAddresses(): List<Inet4Address> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && isLinkInterface(it.name, it.displayName) }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
    }.getOrDefault(emptyList())

    /** The one-time word the player must say first on its local link stream (so no other program can take it). */
    fun newToken(rnd: SecureRandom = SecureRandom()): String = ByteArray(16).also { rnd.nextBytes(it) }.joinToString("") { "%02x".format(it) }
}

/**
 * The relay's transport (the phone's [RelayHttp.transport]) as the PC must use it. On the desktop JVM, closing the long
 * download stream waits while another thread is reading it (the JDK's chunked stream locks both, and
 * HttpURLConnection.disconnect closes the stream first), i.e. until the next heartbeat, up to 15 s. [RelayPipe.close]
 * closes it before telling Jellyfin it left, and its "lost" path closes it while holding its lock, so the other player
 * would hear late and the pipe would stall. Here that close runs in the background instead, so leaving is at once.
 * (Android's HTTP stack doesn't block there, so the phone is unaffected.)
 */
object PcRelay {
    fun transport(http: RelayHttp, seat: RelaySeat): RelayTransport = NoWaitClose(http.transport(seat))

    private class NoWaitClose(private val t: RelayTransport) : RelayTransport by t {
        override fun openDown(from: Long): InputStream {
            val s = t.openDown(from)
            return object : java.io.FilterInputStream(s) {
                override fun close() { thread(isDaemon = true, name = "link relay down close") { runCatching { s.close() } } }
            }
        }
    }
}

/**
 * One connected link, from the lobby to the game: over Wi-Fi a socket to the other player, over the internet a
 * [RelayPipe], for Celio Link a [CelioSession] (the phone's [LinkConnection] has the same three).
 *
 * The phone hands its native shim the socket's own fd. The Windows player is its own program, so instead it connects
 * to [openForPlayer]'s local port (127.0.0.1 only), says the one-time token, and from then on that stream carries
 * exactly what the phone's shim socket carries (u32 big-endian length + packet). This class copies it to and from the
 * real end unchanged: Wi-Fi byte for byte, the relay with the phone's own [RelayLinkPump], Celio with the phone's own
 * [CelioSession.attachLocal]. Either side ending ends both, so the other player sees the link drop just as on a phone.
 */
class PcLink(val connection: LinkConnection, private val end: End) : Closeable {
    sealed interface End {
        class Wifi(val socket: Socket) : End
        class Relay(val pipe: RelayPipe) : End
        class Celio(val session: CelioSession) : End
    }

    val isHost get() = connection.isHost
    val cableMode get() = connection.cableMode
    val peerName get() = connection.peerName
    val overInternet get() = end is End.Relay
    val celio: CelioSession? get() = (end as? End.Celio)?.session

    /** Why an internet or Celio link ended (the shared code's words, e.g. "The link was too slow, so it stopped."). */
    val problem: String? get() = when (end) {
        is End.Relay -> end.pipe.lost
        is End.Celio -> (end.session.status.value as? CelioSession.Status.Lost)?.why
        is End.Wifi -> null
    }

    /** Where the player connects, and what it must say first. */
    data class Door(val port: Int, val token: String)

    private val closed = AtomicBoolean(false)
    @Volatile private var door: ServerSocket? = null
    @Volatile private var player: Socket? = null
    @Volatile private var pump: RelayLinkPump? = null
    /** True once the player has connected and its stream is joined to the other side. */
    @Volatile var joined = false; private set
    /** Set when either side ended the link (for the shell's status). */
    @Volatile var ended: String? = null; private set

    /**
     * Opens the local door for the player: 127.0.0.1 on a free port. Connections that don't say the token first (within
     * [lineMs]) are dropped and the door stays open; the first that does is joined to the link. If no player has come
     * after [waitMs], the link ends (the game didn't start).
     */
    fun openForPlayer(waitMs: Int = 120_000, lineMs: Int = 10_000): Door {
        check(door == null) { "The door is already open." }
        val ss = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
        ss.soTimeout = waitMs
        door = ss
        val token = PcLinkRules.newToken()
        thread(isDaemon = true, name = "link door") {
            try {
                while (!closed.get()) {
                    val s = ss.accept()
                    val ok = try {
                        s.soTimeout = lineMs
                        val said = LinkHandshake.readLine(s.getInputStream())
                        MessageDigest.isEqual(said.toByteArray(Charsets.US_ASCII), token.toByteArray(Charsets.US_ASCII))
                    } catch (_: IOException) { false }
                    if (!ok) { Log.w("link: a local connection didn't give the player's token; dropped"); runCatching { s.close() }; continue }
                    s.soTimeout = 0
                    s.tcpNoDelay = true
                    runCatching { ss.close() }
                    join(s)
                    return@thread
                }
            } catch (_: SocketTimeoutException) {
                finish("the game didn't take the link")
            } catch (_: IOException) {
                // the door was closed (the link ended)
            }
        }
        return Door(ss.localPort, token)
    }

    private fun join(s: Socket) {
        player = s
        if (closed.get()) { runCatching { s.close() }; return }
        joined = true
        when (end) {
            is End.Wifi -> {
                val other = end.socket
                copy(s.getInputStream(), other.getOutputStream(), "link up")
                copy(other.getInputStream(), s.getOutputStream(), "link down")
            }
            is End.Relay -> RelayLinkPump(end.pipe, s.getInputStream(), s.getOutputStream()) {
                runCatching { s.close() }; finish("relay")
            }.also { pump = it }.start()
            is End.Celio -> end.session.attachLocal(s.getInputStream(), s.getOutputStream()) { runCatching { s.close() }; finish("celio") }
        }
    }

    private fun copy(from: InputStream, to: OutputStream, name: String) = thread(isDaemon = true, name = name) {
        val buf = ByteArray(16 * 1024)
        try {
            while (true) {
                val n = from.read(buf)
                if (n < 0) break
                to.write(buf, 0, n)
            }
        } catch (_: IOException) {
        } finally {
            finish(name)
        }
    }

    private fun finish(why: String) {
        if (ended == null) ended = why
        close()
    }

    /** Ends the link: the door, the player's stream and the real end (the other player sees the link drop). */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { door?.close() }
        runCatching { player?.close() }
        runCatching { pump?.close() }
        runCatching { connection.close() } // the Wi-Fi socket, the relay pipe (leaves the room) or the Celio session
    }
}
