package uk.benmarks.shoalcade.desktop

import uk.benmarks.shoalcade.LinkPeer
import uk.benmarks.shoalcade.LinkRules
import java.io.Closeable
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener
import kotlin.concurrent.thread

/**
 * Link play's "who is hosting on this Wi-Fi" (the phone uses Android's NsdManager for it, LinkLobby). Same DNS-SD
 * service as the phone, so phones and PCs see each other: type [LinkRules.SERVICE_TYPE], name "Shoalcade <device>",
 * TXT `game` (first 60 characters) and `console` (e.g. `GBA`).
 */
interface LinkFinder {
    /** Shows this host on [addresses] until closed. */
    fun advertise(addresses: List<InetAddress>, name: String, port: Int, game: String, console: String): Closeable

    /** Calls [onChange] with every host found on [addresses] for [console] (the phone's rule: other consoles are left out). */
    fun browse(addresses: List<InetAddress>, console: String, onChange: (List<LinkPeer>) -> Unit): Closeable
}

/** [LinkFinder] on JmDNS (Apache-2.0): one JmDNS per address, as each one binds a single interface. */
class JmdnsFinder : LinkFinder {
    /** JmDNS wants "<type>local."; the phone's NSD type is "_shoalcade._tcp.". */
    private val type = LinkRules.SERVICE_TYPE + "local."

    override fun advertise(addresses: List<InetAddress>, name: String, port: Int, game: String, console: String): Closeable {
        val all = open(addresses)
        val props = mapOf("game" to game.take(60), "console" to console)
        for (j in all) runCatching { j.registerService(ServiceInfo.create(type, name, port, 0, 0, props)) }
            .onFailure { Log.w("link: couldn't show this host on ${j.inetAddress}: ${it.message}") } // a typed address still works
        return closer(all) { j -> runCatching { j.unregisterAllServices() } }
    }

    override fun browse(addresses: List<InetAddress>, console: String, onChange: (List<LinkPeer>) -> Unit): Closeable {
        val all = open(addresses)
        val peers = ConcurrentHashMap<String, LinkPeer>()
        val listener = object : ServiceListener {
            override fun serviceAdded(e: ServiceEvent) { e.dns.requestServiceInfo(e.type, e.name, 3_000) }
            override fun serviceRemoved(e: ServiceEvent) { if (peers.remove(e.name) != null) onChange(peers.values.toList()) }
            override fun serviceResolved(e: ServiceEvent) {
                val info = e.info ?: return
                val host = info.inet4Addresses.firstOrNull()?.hostAddress ?: info.hostAddresses.firstOrNull() ?: return
                if (info.getPropertyString("console") != console || info.port <= 0) return
                peers[e.name] = LinkPeer(e.name.removePrefix("Shoalcade "), info.getPropertyString("game") ?: "", host, info.port)
                onChange(peers.values.toList())
            }
        }
        for (j in all) runCatching { j.addServiceListener(type, listener) }
        return closer(all) { j -> runCatching { j.removeServiceListener(type, listener) } }
    }

    private fun open(addresses: List<InetAddress>): List<JmDNS> = addresses.filterIsInstance<Inet4Address>().mapNotNull { a ->
        runCatching { JmDNS.create(a, "shoalcade-" + a.hostAddress.replace('.', '-')) }
            .onFailure { Log.w("link: no mDNS on ${a.hostAddress}: ${it.message}") }.getOrNull()
    }

    /** JmDNS's close says goodbye on the network and can take a few seconds, so it runs in the background. */
    private fun closer(all: List<JmDNS>, first: (JmDNS) -> Unit) = Closeable {
        thread(isDaemon = true, name = "link mdns close") { all.forEach { j -> first(j); runCatching { j.close() } } }
    }
}
