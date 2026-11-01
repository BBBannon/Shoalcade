package uk.benmarks.shoalcade.desktop

import uk.benmarks.shoalcade.SyncResult
import java.net.Proxy
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler

/**
 * Where Windows saves say they came from (D187): `source=desktop`, shown as "Windows" in the history.
 *
 * The phone's shared JellyfinClient sends `source=app` on every save it uploads (its `putSave` default), and the
 * shared SaveSyncCore calls it without a source, so neither can be changed from here without editing the phone app.
 * Until the phone's JellyfinClient takes the source as a setting (a one-line change, recommended for the release),
 * [install] rewrites exactly that one query value on exactly that one route (`PUT …/shoalcade/save/<id>?…&source=app`)
 * as the connection is opened. Nothing else is touched: other routes, other sources ("upload" for a file the person
 * chose) and every header stay as they are. This is the only place the Windows source is named.
 *
 * Plugin 0.10.0.0 and later take it (server change S1); an older plugin answers 400 `bad_source`: see [refusedByPlugin].
 */
object SaveSource {
    /** The one Windows save source (D187). */
    const val DESKTOP = "desktop"

    /**
     * What this app sends. Kept as a setting for tests only (setting "app" switches the rewrite off). Never changed by
     * the app itself.
     */
    @Volatile internal var value: String = DESKTOP

    /**
     * On once [install] has run. Tests switch it off again after themselves, because the JVM's URL handler can't be
     * taken away and the phone's own tests (same test run) must keep sending exactly what the phone sends.
     */
    @Volatile internal var enabled = false

    private val savePath = Regex("""/shoalcade/save/[0-9A-Fa-f-]{32,36}$""")

    /** The URL with `source=app` swapped for [value] on the save upload route; any other URL comes back unchanged. */
    fun rewrite(url: URL): URL {
        val q = url.query ?: return url
        if (!enabled || value == "app" || !savePath.containsMatchIn(url.path ?: "")) return url
        val parts = q.split('&')
        if (parts.none { it == "source=app" }) return url
        val newQuery = parts.joinToString("&") { if (it == "source=app") "source=$value" else it }
        return URL(url.protocol, url.host, url.port, url.path + "?" + newQuery + (url.ref?.let { "#$it" } ?: ""))
    }

    @Volatile private var installed = false

    /**
     * Installs the rewrite for http and https (once per process). Needs `--add-opens java.base/java.net=ALL-UNNAMED`
     * (the app and the tests set it; the shared JellyfinClient needs the same for PATCH). If the JVM already has a
     * URL handler factory, saves keep going up as `source=app`: wrongly labelled, never lost. That is logged.
     */
    @Synchronized fun install() {
        enabled = true
        if (installed) return
        installed = true
        try {
            val field = URL::class.java.getDeclaredField("handler").apply { isAccessible = true }
            // The JDK's own handlers, taken before the factory exists (asking afterwards would come back to it).
            val defaults = listOf("http", "https").associateWith { p -> field.get(URL("$p://localhost/")) as URLStreamHandler }
            URL.setURLStreamHandlerFactory { protocol ->
                defaults[protocol]?.let { inner ->
                    object : URLStreamHandler() {
                        override fun openConnection(u: URL): URLConnection = URL(null, rewrite(u).toString(), inner).openConnection()
                        override fun openConnection(u: URL, p: Proxy): URLConnection = URL(null, rewrite(u).toString(), inner).openConnection(p)
                        override fun getDefaultPort() = if (protocol == "https") 443 else 80
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w("Windows save source not set up, so saves go up marked as the phone app: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * Jellyfin refused the upload request itself (400: a plugin older than 0.10.0.0 answers `bad_source` to `source=desktop`, S1).
     * The save is fine and stays on this PC; it waits to go up once the plugin takes it. SaveSyncCore counts every 400
     * as "this save, for good" and wouldn't keep it waiting, so the desktop does ([UploadWhenOnline], [PlaySession]).
     */
    fun refusedByPlugin(r: SyncResult?): Boolean = r is SyncResult.Failed && r.code == 400

    /** Said after the save's own message when [refusedByPlugin]. */
    const val REFUSED_NOTE = "Your Jellyfin's Shoalcade plugin doesn't take saves from Windows yet (it needs updating). " +
        "The save is kept on this PC and waits to go up."
}
