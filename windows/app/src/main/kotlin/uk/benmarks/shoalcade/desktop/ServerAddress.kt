package uk.benmarks.shoalcade.desktop

import uk.benmarks.shoalcade.JellyfinClient
import java.io.IOException
import java.net.URI

/**
 * HTTPS only (plan §7), as the phone, where Android's network rules block plain http. The desktop has no such rule,
 * so it is checked here before any request. The Dev build also allows http://localhost (and 127.0.0.1 / [::1]) for
 * the test-bed, like the phone's debug build allows its emulator alias (debug network_security_config.xml).
 */
object ServerAddress {
    const val HTTPS_ONLY = "HTTPS only."

    private val LOCAL = setOf("localhost", "127.0.0.1", "[::1]", "::1")

    /** The server address as the shared [JellyfinClient.normaliseServer] makes it, or an error to show. */
    fun check(raw: String, dev: Boolean): String {
        val t = raw.trim()
        // Another scheme ("ftp://…") would otherwise become "https://ftp://…"; a capital "HTTPS://" is fine.
        val scheme = SCHEME.find(t)?.groupValues?.get(1)?.lowercase()
        if (scheme != null && scheme !in setOf("http", "https")) throw IOException(NOT_HTTPS)
        val rest = if (scheme == null) t else t.substring(scheme.length + 3)
        if (rest.trim('/').isEmpty()) throw IOException(NOT_ADDRESS)
        val url = JellyfinClient.normaliseServer(if (scheme == null) rest else "$scheme://$rest")
        val uri = runCatching { URI(url) }.getOrNull()
        val host = uri?.host?.takeIf { it.isNotEmpty() } ?: throw IOException(NOT_ADDRESS)
        when (uri.scheme) {
            "https" -> return url
            "http" -> if (dev && host.lowercase() in LOCAL) return url
        }
        throw IOException(NOT_HTTPS)
    }

    private val SCHEME = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*)://")
    private const val NOT_ADDRESS = "That doesn't look like a server address."
    private const val NOT_HTTPS = "Use your server's https:// address. $HTTPS_ONLY"
}
