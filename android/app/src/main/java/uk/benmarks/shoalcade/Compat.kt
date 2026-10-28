package uk.benmarks.shoalcade

import java.io.IOException

/**
 * Two-way version check with the Shoalcade plugin (D41). The server says which app it needs (`minAppVersion`,
 * e.g. "a0.5") and its API level; this app says which server it needs ([MIN_SERVER], [API_LEVEL]).
 * The app's own version goes to the server in every request's Authorization header (BuildConfig.VERSION_NAME).
 */
object Compat {
    /** API level this app speaks. Must match the plugin's `GamesController.ApiLevel`. */
    const val API_LEVEL = 1

    /**
     * Oldest server plugin this app works with. s0.2: app 0.6 needs its "saved after loading a snapshot" mark,
     * one DS save list (D91) and `display`; on s0.1 the mark would be dropped silently (honesty audit M4).
     */
    const val MIN_SERVER = "s0.2"

    const val PLUGIN_MISSING = "The Shoalcade server plugin isn't on this Jellyfin server, or is older than s0.2 dev 2 (renamed from JellyGames). " +
        "Install the plugin on the server. Games already on the phone still play."

    /** A dev build's tail (D88): "-dev1" in the app's "0.6.0-dev1", " dev 1" in the plugin's "s0.2 dev 1". */
    private val DEV = Regex("""[\s-]*dev\s*(\d+)$""", RegexOption.IGNORE_CASE)

    /**
     * "a0.5" / "s0.1" / "0.5.0" / "0.1.0.0" → [0, 5, 0, …]. Null if it isn't a version. A dev tail is left out
     * ("0.6.0-dev1" → [0, 6, 0], "s0.2 dev 1" → [0, 2]), so a dev build counts as the version it leads up to.
     */
    fun parse(v: String?): List<Int>? {
        val t = v?.trim()?.removePrefix("a")?.removePrefix("s")?.replace(DEV, "")?.takeIf { it.isNotEmpty() } ?: return null
        val parts = t.split('.').map { it.toIntOrNull() ?: return null }
        return parts
    }

    /** The dev build number: "0.6.0-dev1" → 1, "s0.2 dev 1" → 1; 0 for a release ("1.0.0", "s1.0"). */
    fun devNumber(v: String?): Int = v?.trim()?.let { DEV.find(it) }?.groupValues?.get(1)?.toIntOrNull() ?: 0

    /** Compares two versions, padding the shorter with zeros. */
    fun compare(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val d = (a.getOrElse(i) { 0 }).compareTo(b.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    /** App version label in the project's style: "0.5.0" → "a0.5", "0.6.0-dev1" → "a0.6 dev 1" (patch shown only if not 0). */
    fun appLabel(versionName: String = BuildConfig.VERSION_NAME): String {
        val p = parse(versionName) ?: return "a$versionName"
        val dev = devNumber(versionName)
        return "a" + p.take(2).joinToString(".") + (if (p.size > 2 && p[2] != 0) ".${p[2]}" else "") +
            (if (dev > 0) " dev $dev" else "")
    }

    /** Null if this app and the server work together; otherwise a plain-English message saying what to update. */
    fun problem(info: ServerInfo, appVersion: String = BuildConfig.VERSION_NAME): String? {
        val app = parse(appVersion) ?: return null // a build with an odd name: don't block
        val server = parse(info.server) ?: parse(info.serverVersion)
        val serverLabel = info.display.ifEmpty { info.server }.ifEmpty { "unknown" }
        val minApp = parse(info.minAppVersion)
        return when {
            server == null || info.apiLevel < API_LEVEL || compare(server, parse(MIN_SERVER)!!) < 0 ->
                "The Shoalcade server plugin ($serverLabel) is too old for this app (${appLabel(appVersion)}). " +
                    "Update the server plugin to $MIN_SERVER or newer."
            info.apiLevel > API_LEVEL ->
                "This app (${appLabel(appVersion)}) is too old for the server plugin ($serverLabel). Update the app."
            minApp == null ->
                "The server plugin ($serverLabel) didn't say which app it needs. Update the server plugin."
            compare(app, minApp) < 0 ->
                "This app (${appLabel(appVersion)}) is too old for the server plugin ($serverLabel), which needs " +
                    "${info.minAppVersion} or newer. Update the app."
            else -> null
        }
    }

    /** Server URL → null (works) or the problem. Checked once per app run; [AppViewModel.refresh] re-checks. */
    private val checked = java.util.concurrent.ConcurrentHashMap<String, String>()
    private const val OK = ""

    fun remember(serverUrl: String, problem: String?) { checked[serverUrl] = problem ?: OK }

    /** Throws an IOException with a plain message if the server and app don't match (or the plugin is missing). */
    suspend fun ensure(client: JellyfinClient, serverUrl: String) {
        val known = checked[serverUrl] ?: run {
            val p = try {
                problem(client.info())
            } catch (e: HttpException) {
                if (e.code == 404) PLUGIN_MISSING else throw e
            }
            remember(serverUrl, p)
            p ?: OK
        }
        if (known != OK) throw IOException(known)
    }
}
