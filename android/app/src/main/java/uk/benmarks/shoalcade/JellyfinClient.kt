package uk.benmarks.shoalcade

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentLinkedQueue
import javax.net.ssl.SSLException
import java.net.URL
import java.net.URLEncoder

data class Game(
    val id: String,
    val name: String,
    val console: Console?,
    /** ROM file name. Empty for games listed by the plugin: the download takes it from the server. */
    val fileName: String,
    val canDownload: Boolean,
    /** Jellyfin's Primary image tag (box art); changes when the picture changes. Null = no art. */
    val imageTag: String? = null,
    /** Release year from the server (older servers don't send it). */
    val year: Int? = null,
    /** Last played on Jellyfin (epoch ms): the later of Jellyfin's last-played date and the current save's time. */
    val serverPlayed: Long? = null,
    /** Starred by this user (Jellyfin favourites, shared with the browser library). */
    val favourite: Boolean = false,
)

/** Game ↔ JSON, for the offline game list and for passing a game to the download service. */
object GameJson {
    fun toJson(g: Game): JSONObject = JSONObject().put("id", g.id).put("name", g.name).put("console", g.console?.name)
        .put("file", g.fileName).put("dl", g.canDownload).put("img", g.imageTag)
        .put("year", g.year).put("played", g.serverPlayed).put("fav", g.favourite)

    fun fromJson(s: String) = fromJson(JSONObject(s))

    fun fromJson(o: JSONObject) = Game(
        id = o.getString("id"),
        name = o.getString("name"),
        console = o.optString("console").takeIf { it.isNotEmpty() && it != "null" }
            ?.let { runCatching { Console.valueOf(it) }.getOrNull() },
        fileName = o.optString("file", ""),
        canDownload = o.optBoolean("dl", true),
        imageTag = o.optString("img").takeIf { it.isNotEmpty() && it != "null" },
        year = if (o.has("year") && !o.isNull("year")) o.optInt("year") else null,
        serverPlayed = if (o.has("played") && !o.isNull("played")) o.optLong("played") else null,
        favourite = o.optBoolean("fav", false),
    )
}

data class QuickConnectCode(val serverUrl: String, val code: String, val secret: String)

open class HttpException(val code: Int, message: String) : IOException(message)

/** The server answered, but not with Shoalcade JSON (e.g. a Wi-Fi sign-in page). Treated like being offline. */
/**
 * F3: no answer from the server at all (no network, server down, wrong address, or it took longer than
 * [JellyfinClient.Timeouts.call]). [nothingSent] says whether the request certainly never arrived: after a timeout or a
 * dropped connection the server may still have finished it (G5 DL1).
 */
class ServerUnreachableException(timedOut: Boolean, cause: Throwable? = null) :
    IOException(if (timedOut) "Your Jellyfin server took too long to answer." else "Couldn't reach your Jellyfin server.", cause) {
    /** True only when no connection was ever made (refused, no route, unknown address), so nothing can have arrived. */
    val nothingSent: Boolean = !timedOut && (cause is ConnectException || cause is NoRouteToHostException || cause is UnknownHostException)
}

class BadReplyException : IOException("The server sent a reply the app doesn't understand. If this Wi-Fi needs you to sign in, do that first.")

/**
 * Talks to Jellyfin + the Shoalcade plugin (`/shoalcade/...`; no JellyEMU fallback, D45).
 * All calls run on Dispatchers.IO. The token only ever goes in the Authorization header.
 */
class JellyfinClient(
    private val deviceId: String,
    private val session: Session? = null,
    private val deviceName: String = android.os.Build.MODEL ?: "Android",
    private val timeouts: Timeouts = Timeouts(),
) {
    /**
     * F3: a down or silent server fails fast instead of hanging. [connect] and [read] are per connection; [call] is the
     * most a whole server call may take (the connection is closed when it runs out, which also ends a stuck upload);
     * [saveCall] is the same for sending a save. ROM downloads have no total limit (they're big and can be cancelled).
     */
    data class Timeouts(
        val connect: Int = CONNECT_TIMEOUT_MS, val read: Int = READ_TIMEOUT_MS,
        val call: Long = CALL_TIMEOUT_MS, val saveCall: Long = SAVE_CALL_TIMEOUT_MS,
    )

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8").replace("+", "%20")

    private fun authHeader(token: String?): String {
        // The real app version (D41): the server shows it in the saves manager's device label.
        val base = "MediaBrowser Client=\"Shoalcade\", Device=\"${enc(deviceName)}\", " +
            "DeviceId=\"${enc(deviceId)}\", Version=\"${BuildConfig.VERSION_NAME}\""
        return if (token != null) "$base, Token=\"$token\"" else base
    }

    private fun open(url: String, method: String = "GET", token: String? = session?.token): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            setMethod(method)
            connectTimeout = timeouts.connect
            readTimeout = timeouts.read
            CALLS.get()?.open?.add(this) // so the call's time limit can close it
            setRequestProperty("Authorization", authHeader(token))
            setRequestProperty("Accept", "application/json, */*")
        }

    private fun HttpURLConnection.checkOk(): HttpURLConnection {
        val c = responseCode
        if (c !in 200..299) {
            val (err, msg) = readError(this)
            disconnect()
            throw ApiException(c, err, when (c) {
                401 -> "Wrong username or password, or your login has expired."
                else -> friendly(c, err, msg)
            })
        }
        return this
    }

    private val s get() = session ?: error("Not logged in")

    suspend fun login(server: String, user: String, password: String): Session = withContext(Dispatchers.IO) {
        val base = normaliseServer(server)
        val conn = open("$base/Users/AuthenticateByName", "POST", token = null)
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        // Phone keyboards often add a trailing space after a suggestion; Jellyfin then sees a different user.
        // Password is left alone: spaces there can be real.
        val body = JSONObject().put("Username", user.trim()).put("Pw", password).toString()
        conn.outputStream.use { it.write(body.toByteArray()) }
        val json = JSONObject(conn.checkOk().inputStream.bufferedReader().use { it.readText() })
        conn.disconnect()
        sessionFrom(base, json, user)
    }

    private fun sessionFrom(base: String, json: JSONObject, fallbackName: String = ""): Session {
        val u = json.getJSONObject("User")
        return Session(
            serverUrl = base,
            // 32 hex chars, no dashes (Jellyfin's "N" form); also the phone's save folder name.
            userId = u.getString("Id").replace("-", "").lowercase(),
            userName = u.optString("Name", fallbackName),
            token = json.getString("AccessToken"),
        )
    }

    // ---- Quick Connect: show a code here, approve it on any device already signed in to Jellyfin. ----

    /** Asks the server for a Quick Connect code. */
    suspend fun quickConnectStart(server: String): QuickConnectCode = withContext(Dispatchers.IO) {
        val base = normaliseServer(server)
        val conn = open("$base/QuickConnect/Initiate", "POST", token = null)
        conn.doOutput = true
        conn.setFixedLengthStreamingMode(0)
        if (conn.responseCode == 401) {
            conn.disconnect()
            throw HttpException(401, "Quick Connect is turned off on the server (Dashboard → General → Quick Connect).")
        }
        val json = JSONObject(conn.checkOk().inputStream.bufferedReader().use { it.readText() })
        conn.disconnect()
        QuickConnectCode(base, json.getString("Code"), json.getString("Secret"))
    }

    /** True once the code has been approved. Throws 404 HttpException if the code has expired. */
    suspend fun quickConnectApproved(qc: QuickConnectCode): Boolean = withContext(Dispatchers.IO) {
        // Jellyfin's API only accepts the secret as a query value. It is short-lived and single-use, not the login token.
        val conn = open("${qc.serverUrl}/QuickConnect/Connect?Secret=${enc(qc.secret)}", token = null)
        if (conn.responseCode == 404) {
            conn.disconnect()
            throw HttpException(404, "The Quick Connect code expired. Please try again.")
        }
        val json = JSONObject(conn.checkOk().inputStream.bufferedReader().use { it.readText() })
        conn.disconnect()
        json.optBoolean("Authenticated", false)
    }

    /** Swaps an approved code for a normal login token. */
    suspend fun quickConnectFinish(qc: QuickConnectCode): Session = withContext(Dispatchers.IO) {
        val conn = open("${qc.serverUrl}/Users/AuthenticateWithQuickConnect", "POST", token = null)
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(JSONObject().put("Secret", qc.secret).toString().toByteArray()) }
        val json = JSONObject(conn.checkOk().inputStream.bufferedReader().use { it.readText() })
        conn.disconnect()
        sessionFrom(qc.serverUrl, json)
    }

    /**
     * Ends this login on the server too (L9). Best effort: short timeouts, never throws, so Log out never
     * waits on or fails because of the network. True only if Jellyfin confirmed it.
     */
    suspend fun logout(): Boolean = withContext(Dispatchers.IO) {
        val s = session ?: return@withContext false
        runCatching {
            val conn = open("${s.serverUrl}/Sessions/Logout", "POST", s.token).apply {
                connectTimeout = LOGOUT_TIMEOUT_MS
                readTimeout = LOGOUT_TIMEOUT_MS
                doOutput = true
                setFixedLengthStreamingMode(0)
            }
            try { conn.responseCode in 200..299 } finally { conn.disconnect() }
        }.getOrDefault(false)
    }

    // ---- Shoalcade plugin ----

    /** Server version, API level and save rules, for the two-way version check (D41). 404 = plugin not installed. */
    suspend fun info(): ServerInfo = plugin { ServerInfo.parse(JSONObject(getText("/shoalcade/info"))) }

    /**
     * Every ROM the user can see (the plugin applies library access and parental rules per user).
     * The ROM file name isn't in the list: [download] takes it from the server.
     * [canDownload] comes from `/shoalcade/info` (the user's Jellyfin download permission).
     */
    suspend fun listGames(canDownload: Boolean = true): List<RemoteGame> = plugin {
        val arr = JSONArray(getText("/shoalcade/games"))
        (0 until arr.length()).mapNotNull { i -> RemoteGame.parse(arr.getJSONObject(i), canDownload) }
    }

    /** Version of the phone app inside the plugin (`/shoalcade/app/info`). Null if the admin turned it off or it isn't included. */
    suspend fun serverAppVersion(): String? = plugin {
        val conn = open(url("/shoalcade/app/info"))
        if (conn.responseCode == 404) { conn.disconnect(); return@plugin null }
        val o = JSONObject(conn.readOk())
        o.optString("version").takeIf { o.optBoolean("available") && it.isNotEmpty() && it != "null" }
    }

    /** One game with its current save(s). Null if hidden, removed or not a ROM. */
    suspend fun game(itemId: String): RemoteGame? = plugin {
        val conn = open(url("/shoalcade/games/${id(itemId)}"))
        if (conn.responseCode == 404) { conn.disconnect(); return@plugin null }
        RemoteGame.parse(JSONObject(conn.readOk()), true)
    }

    /** Box art as JPEG bytes, scaled down by the server. Null if the game has no picture. */
    suspend fun boxArt(itemId: String, tag: String): ByteArray? = plugin {
        val conn = open("${s.serverUrl}/Items/${id(itemId)}/Images/Primary?tag=${enc(tag)}&maxHeight=240&quality=85&format=Jpg")
        if (conn.responseCode == 404) { conn.disconnect(); return@plugin null }
        val bytes = conn.checkOk().inputStream.use { it.readBytes() }
        conn.disconnect()
        bytes
    }

    /**
     * Streams the ROM into [dir] through Jellyfin's own download route (D44: needs a login, download permission and
     * library access). The file name comes from the server's Content-Disposition; [fallbackName] if it sends none.
     * Writes to a .part file first so a dropped connection never leaves a half ROM. Returns the finished file.
     */
    suspend fun download(itemId: String, dir: File, fallbackName: String, onProgress: (Float) -> Unit): File =
        plugin(limit = null) {
            val conn = open("${s.serverUrl}/Items/${id(itemId)}/Download").checkOk()
            val total = conn.contentLengthLong
            val dest = File(dir, safeFileName(contentDispositionName(conn.getHeaderField("Content-Disposition")), fallbackName))
            val part = File(dir, dest.name + ".part")
            dir.mkdirs()
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        ensureActive() // lets Cancel stop a big download mid-way
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
            conn.disconnect()
            if (!part.renameTo(dest)) throw IOException("Couldn't save the download.")
            dest
        }

    /** One game's save history: one list per game, DS too (D91). */
    suspend fun history(itemId: String): SaveHistory = plugin {
        SaveHistory.parse(JSONObject(getText("/shoalcade/save/${id(itemId)}/history")))
    }

    /** The current save (or one [version]). Null if the game has no save yet. */
    suspend fun getSave(itemId: String, version: Int? = null): SaveDownload? = plugin {
        val path = "/shoalcade/save/${id(itemId)}" + (if (version != null) "/versions/$version" else "")
        val conn = open(url(path))
        if (conn.responseCode == 404) {
            val (err, msg) = readError(conn)
            conn.disconnect()
            if (version == null && err == "no_save") return@plugin null
            throw ApiException(404, err, msg ?: "Not found on the server.")
        }
        conn.checkOk()
        val bytes = conn.inputStream.use { it.readBytes() }
        val v = conn.getHeaderField("X-Shoalcade-Save-Version")?.toIntOrNull() ?: 0
        val cur = conn.getHeaderField("X-Shoalcade-Save-Current")?.toIntOrNull() ?: 0
        // The plugin always sends these headers. Without them this isn't a save (e.g. a Wi-Fi sign-in page): never use it.
        val sha = conn.getHeaderField("X-Shoalcade-Save-Sha256")?.lowercase()
            ?: run { conn.disconnect(); throw BadReplyException() }
        val name = contentDispositionName(conn.getHeaderField("Content-Disposition"))
        conn.disconnect()
        if (sha256(bytes) != sha) throw IOException("The save didn't arrive whole. Try again.")
        SaveDownload(bytes, v, cur, sha, name)
    }

    /**
     * Uploads a save (D35). [base] = the version this phone last saw (0 = none). If the server's current save is a
     * different one it answers 409, returned as [PutResult.Conflict] so the caller can ask the user.
     * [mode]: "normal" (version check), "force" (becomes current; the old one stays in history),
     * "history" (kept in history only; the current save is unchanged).
     * [expect] / [expectSha] (with "force", plugin s0.2 dev 3, L2): the Jellyfin save the user was shown (0 = none);
     * if the save in use is a different one now, nothing changes and the answer is [PutResult.Conflict].
     */
    suspend fun putSave(
        itemId: String, bytes: ByteArray, base: Int?, mode: String = "normal",
        source: String = "app", allowBlank: Boolean = false, afterSnapshot: Boolean = false,
        expect: Int? = null, expectSha: String? = null, cheats: Boolean = false,
    ): PutResult = plugin(timeouts.saveCall) {
        val q = StringBuilder("?mode=${enc(mode)}&source=${enc(source)}")
        if (base != null) q.append("&base=").append(base)
        if (expect != null) {
            q.append("&expect=").append(expect)
            if (expectSha != null) q.append("&expectSha=").append(enc(expectSha))
        }
        if (allowBlank) q.append("&allowBlank=true")
        if (afterSnapshot) q.append("&afterSnapshot=true") // D90: the game saved this after a snapshot was loaded
        if (cheats) q.append("&cheats=true") // D143: made while cheats were on (the first one pins "Before cheats")
        val conn = open(url("/shoalcade/save/${id(itemId)}$q"), "PUT")
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/octet-stream")
        conn.setFixedLengthStreamingMode(bytes.size)
        conn.outputStream.use { it.write(bytes) }
        withCurrent(writeResult(conn), itemId)
    }

    /** Makes an old version current again (the current one stays in history). Conflict if the save changed since [base]. */
    suspend fun restore(itemId: String, version: Int, base: Int?): PutResult = plugin {
        val q = if (base != null) "?base=$base" else ""
        val conn = open(url("/shoalcade/save/${id(itemId)}/versions/$version/restore$q"), "POST")
        conn.doOutput = true
        conn.setFixedLengthStreamingMode(0)
        withCurrent(writeResult(conn), itemId)
    }

    /**
     * Pins/unpins a version and/or sets its name (the browser's PATCH, D39). [pinned] / [label] null = unchanged;
     * label "" = remove the name. The label is cleaned as the server does ([cleanLabel]). Refusals (422 "pinned" =
     * too many pinned, 404 "missing") become [ApiException] with the server's message. Returns the changed version.
     */
    suspend fun updateVersion(itemId: String, version: Int, pinned: Boolean? = null, label: String? = null,
                              maxLabel: Int = DEFAULT_MAX_LABEL): SaveVersion? = plugin {
        require(pinned != null || label != null) { "Nothing to change." }
        val body = JSONObject()
        if (pinned != null) body.put("pinned", pinned)
        if (label != null) body.put("label", cleanLabel(label, maxLabel))
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        val conn = open(url("/shoalcade/save/${id(itemId)}/versions/$version"), "PATCH")
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setFixedLengthStreamingMode(bytes.size)
        conn.outputStream.use { it.write(bytes) }
        (writeResult(conn) as? PutResult.Stored)?.version
    }

    /**
     * Snapshots on/off per console key (D38), from the user's server settings. A console with no setting is on,
     * as on the server. Only the `snapshots` key is read: the app never writes these settings.
     */
    suspend fun snapshotSettings(): Map<String, Boolean> = plugin {
        val o = JSONObject(getText("/shoalcade/prefs"))
        o.keys().asSequence().filter { it != "global" }.toList().associateWith { k ->
            (o.optJSONObject(k)?.opt("snapshots") as? Boolean) ?: true
        }
    }

    // ---- Cheats (D143): one list per game and user, plus the in-game save's active list. See docs/cheats-api.md. ----

    /** The game's cheats and the ids switched on for its in-game save (empty lists if none). */
    suspend fun cheats(itemId: String): CheatDoc = plugin { CheatDoc.parse(JSONObject(getText("/shoalcade/cheats/${id(itemId)}"))) }

    /** Adds a cheat. The same code already there (spaces, "+", line breaks and case ignored) comes back unchanged. */
    suspend fun addCheat(itemId: String, name: String, code: String): ServerCheat = plugin {
        ServerCheat.parse(JSONObject(sendJson("/shoalcade/cheats/${id(itemId)}", "POST", JSONObject().put("name", name).put("code", code))))
            ?: throw BadReplyException()
    }

    /** Renames a cheat and/or changes its code. 404 ApiException "no_cheat" if it's gone. */
    suspend fun editCheat(itemId: String, cheatId: String, name: String? = null, code: String? = null): ServerCheat = plugin {
        val body = JSONObject()
        if (name != null) body.put("name", name)
        if (code != null) body.put("code", code)
        ServerCheat.parse(JSONObject(sendJson("/shoalcade/cheats/${id(itemId)}/${cheatId(cheatId)}", "PATCH", body)))
            ?: throw BadReplyException()
    }

    /** Deletes a cheat (and takes it off the active list). 404 ApiException "no_cheat" if it's already gone. */
    /** Stars or unstars a game (Jellyfin favourites: the browser library shows the same stars). Needs "favourites". */
    suspend fun setFavourite(itemId: String, on: Boolean): Unit = plugin {
        val conn = open(url("/shoalcade/games/${id(itemId)}/favourite?on=$on"), "POST")
        conn.doOutput = true
        conn.setFixedLengthStreamingMode(0)
        conn.checkOk().disconnect()
    }

    suspend fun deleteCheat(itemId: String, cheatId: String): Unit = plugin {
        open(url("/shoalcade/cheats/${id(itemId)}/${cheatId(cheatId)}"), "DELETE").checkOk().disconnect()
    }

    /** Sets the in-game save's active list. Unknown ids are dropped by the server. Returns the whole list. */
    suspend fun setActiveCheats(itemId: String, ids: List<String>): CheatDoc = plugin {
        ids.forEach { cheatId(it) }
        CheatDoc.parse(JSONObject(sendJson("/shoalcade/cheats/${id(itemId)}/active", "PUT", JSONObject().put("ids", JSONArray(ids)))))
    }

    /** Sends a JSON body; the answer's text. Refusals become [ApiException] with the server's message. */
    private fun sendJson(path: String, method: String, body: JSONObject): String {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        val conn = open(url(path), method)
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setFixedLengthStreamingMode(bytes.size)
        conn.outputStream.use { it.write(bytes) }
        return conn.readOk()
    }

    /** Cheat ids go into URLs: only the server's 12 lower-case hex digits are accepted. */
    private fun cheatId(cheatId: String): String {
        if (!ServerCheat.isId(cheatId)) throw IOException("Bad cheat id.")
        return cheatId
    }

    // ---- helpers ----

    /** Connections opened by one call, so its time limit can close them (F3). */
    private class Calls {
        val open = ConcurrentLinkedQueue<HttpURLConnection>()
        @Volatile var timedOut = false
        fun stop() { timedOut = true; open.forEach { runCatching { it.disconnect() } } }
    }

    /**
     * Runs a server call on Dispatchers.IO. A reply that isn't the JSON we expect (a Wi-Fi sign-in page or a proxy
     * answering 200) becomes an IOException, so every caller treats it as "couldn't reach the server" (audit N2).
     * F3: the whole call ends after [limit] ms (null = no limit), and no answer at all becomes
     * [ServerUnreachableException]. Every outcome updates [ServerStatus]: any answer from the server (even a refusal)
     * = connected; no answer, a sign-in page or an expired login (401) = not connected.
     */
    private suspend fun <T> plugin(limit: Long? = timeouts.call, block: suspend CoroutineScope.() -> T): T {
        val calls = Calls()
        try {
            val r = withContext(Dispatchers.IO + CALLS.asContextElement(calls)) {
                // Blocking reads and writes don't notice coroutine cancellation: closing the connection ends them.
                val dog = limit?.let { launch { delay(it); calls.stop() } }
                try { block() } finally { dog?.cancel() }
            }
            // A reply cut off by the time limit is never used, even if what arrived happens to parse (stage 7 gates).
            if (calls.timedOut) throw ServerUnreachableException(true)
            ServerStatus.report(true)
            return r
        } catch (e: JSONException) {
            ServerStatus.report(false)
            if (calls.timedOut) throw ServerUnreachableException(true, e) // cut off by the time limit, not a sign-in page
            throw BadReplyException()
        } catch (e: HttpException) {
            if (calls.timedOut) { ServerStatus.report(false); throw ServerUnreachableException(true, e) }
            ServerStatus.report(answered(e.code))
            throw e
        } catch (e: IOException) {
            val noAnswer = calls.timedOut || e is SocketException || e is InterruptedIOException || e is UnknownHostException
            if (noAnswer || e is SSLException || e is BadReplyException) ServerStatus.report(false)
            if (noAnswer) throw ServerUnreachableException(calls.timedOut, e)
            throw e
        }
    }

    private fun url(path: String) = s.serverUrl + path

    private fun getText(path: String): String = open(url(path)).readOk()

    private fun HttpURLConnection.readOk(): String {
        checkOk()
        val t = inputStream.bufferedReader().use { it.readText() }
        disconnect()
        return t
    }

    /** L6: a 409 whose body couldn't be read still carries Jellyfin's current save, so "Keep Jellyfin's" stays offered. */
    private suspend fun withCurrent(r: PutResult, itemId: String): PutResult =
        if (r is PutResult.Conflict && r.current == null) {
            PutResult.Conflict(try { game(itemId)?.save } catch (e: IOException) { null })
        } else r

    /** Turns a save-write answer into a [PutResult]; refusals (422, 429, …) become [ApiException] with a plain message. */
    private fun writeResult(conn: HttpURLConnection): PutResult {
        val code = conn.responseCode
        if (code == 409) {
            val body = runCatching { JSONObject(conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "{}") }.getOrNull()
            conn.disconnect()
            return PutResult.Conflict(body?.optJSONObject("current")?.let(SaveVersion::parse))
        }
        if (code !in 200..299) {
            val (err, msg) = readError(conn)
            conn.disconnect()
            throw ApiException(code, err, friendly(code, err, msg))
        }
        val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        conn.disconnect()
        val w = o.optJSONArray("warnings")
        return PutResult.Stored(
            unchanged = o.optString("status") == "unchanged",
            version = o.optJSONObject("version")?.let(SaveVersion::parse),
            current = o.optJSONObject("current")?.let(SaveVersion::parse),
            warnings = if (w == null) emptyList() else (0 until w.length()).map { w.getString(it) },
        )
    }

    /** Item ids go into URLs and phone paths: only plain Jellyfin ids are accepted (security review I8). */
    private fun id(itemId: String): String {
        if (!isSafeId(itemId)) throw IOException("Bad game id from the server.")
        return itemId
    }

    companion object {

        /** Adds https:// if missing and strips trailing slashes. Plain http is blocked by the manifest. */
        fun normaliseServer(raw: String): String {
            val t = raw.trim().trimEnd('/')
            return if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
        }

        private val ID = Regex("[0-9a-fA-F]{32}|[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")

        /**
         * An HTTP error that still proves the logged-in server is there (D141): any refusal except 401 (login gone) and
         * 502/503/504, which a proxy in front sends when Jellyfin itself is down (G6 delta L3).
         */
        fun answered(code: Int) = code != 401 && code !in 502..504

        /** Jellyfin item ids: 32 hex digits, with or without dashes. Anything else is never used. */
        fun isSafeId(id: String) = ID.matches(id)

        /** (error code, message) from a JSON error body, if there is one. */
        private fun readError(conn: HttpURLConnection): Pair<String?, String?> = runCatching {
            val text = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: return@runCatching null to null
            val o = JSONObject(text)
            o.optString("error").ifEmpty { null } to o.optString("message").ifEmpty { null }
        }.getOrDefault(null to null)

        /** Plain-English text for a refusal: 422 and most others use the server's own message; 429 / 413 / 507 get ours. */
        fun friendly(code: Int, error: String?, serverMessage: String?): String = when {
            code == 429 -> "The server is busy with other uploads from your account. Try again in a minute."
            code == 413 -> "That save is too big for this console, so the server refused it."
            code == 507 -> serverMessage ?: "Your save storage on the server is full."
            code == 401 -> "Your login has expired. Please log in again."
            code == 403 -> serverMessage ?: "Jellyfin refused this (no permission)."
            code == 404 -> serverMessage ?: "Not found on the server."
            code == 422 && error == "blank" ->
                "That save is blank (every byte the same), so the game has probably never saved. Nothing was uploaded."
            code == 422 && error == "console_unknown" ->
                "The server can't tell which console this save is for, so it can't be moved. Download it instead."
            serverMessage != null -> serverMessage
            else -> "Server error ($code)."
        }

        /** File name from a Content-Disposition header (RFC 6266: filename* wins over filename). */
        fun contentDispositionName(h: String?): String? {
            if (h == null) return null
            Regex("filename\\*\\s*=\\s*UTF-8''([^;]+)", RegexOption.IGNORE_CASE).find(h)?.let {
                return runCatching { java.net.URLDecoder.decode(it.groupValues[1].trim().replace("+", "%2B"), "UTF-8") }.getOrNull()
            }
            Regex("filename\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE).find(h)?.let { return it.groupValues[1] }
            return Regex("filename\\s*=\\s*([^;]+)", RegexOption.IGNORE_CASE).find(h)?.groupValues?.get(1)?.trim()
        }

        /** Never trust a server file name as a path: last path part only, no "." / "..", no control or reserved characters. */
        fun safeFileName(name: String?, fallback: String): String {
            val n = name?.substringAfterLast('/')?.substringAfterLast('\\')
                ?.filter { it >= ' ' && it !in "<>:\"|?*" }?.trim()
            return if (n.isNullOrEmpty() || n == "." || n == "..") fallback else n
        }

        /** The server's longest kept save name (SaveStore.MaxLabelLength), used until /info says otherwise. */
        const val DEFAULT_MAX_LABEL = 60
        /** Log out never hangs: the server call gives up after this (connect and read each). */
        const val LOGOUT_TIMEOUT_MS = 4_000
        /** F3 time limits ([Timeouts]): a server that's off or unreachable fails within [CONNECT_TIMEOUT_MS]. */
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 20_000
        const val CALL_TIMEOUT_MS = 30_000L
        const val SAVE_CALL_TIMEOUT_MS = 60_000L

        private val CALLS = ThreadLocal<Calls?>()

        /** A save name as the server keeps it: trimmed, no control characters, at most [max] characters. */
        fun cleanLabel(raw: String, max: Int = DEFAULT_MAX_LABEL): String =
            raw.trim().filterNot { it.isISOControl() }.take(max)

        /**
         * Sets the HTTP method. Android's HttpURLConnection accepts PATCH; the desktop JVM's (unit tests only) refuses it,
         * so there the field is set directly (needs --add-opens java.base/java.net, set in build.gradle.kts for tests).
         */
        private fun HttpURLConnection.setMethod(m: String) {
            try {
                requestMethod = m
            } catch (e: java.net.ProtocolException) {
                HttpURLConnection::class.java.getDeclaredField("method").apply { isAccessible = true }.set(this, m)
            }
        }

        fun sha256(b: ByteArray): String =
            java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    }
}

/** A game from `/shoalcade/games`, with its current save. */
data class RemoteGame(val game: Game, val save: SaveVersion?) {
    companion object {
        /** Null for an entry with a bad id (never used in a path). */
        fun parse(o: JSONObject, canDownload: Boolean): RemoteGame? {
            val id = o.optString("id")
            if (!JellyfinClient.isSafeId(id)) return null
            val save = o.optJSONObject("save")?.let(SaveVersion::parse)
            val year = if (o.has("year") && !o.isNull("year")) o.optInt("year").takeIf { it in 1950..2100 } else null
            val lastPlayed = if (o.isNull("lastPlayed")) null else serverTime(o.optString("lastPlayed"))
            return RemoteGame(
                Game(
                    id = id,
                    name = o.optString("name", "Unknown"),
                    console = Console.fromKey(o.optString("console")),
                    fileName = "",
                    canDownload = canDownload,
                    imageTag = o.optString("imageTag").takeIf { it.isNotEmpty() && it != "null" },
                    year = year,
                    serverPlayed = GameSort.lastPlayed(lastPlayed, save?.createdMillis),
                    favourite = o.optBoolean("favourite", false),
                ),
                save,
            )
        }

        /** ISO-8601 time; one without an offset is UTC (.NET writes Jellyfin's UTC dates that way). Null if unreadable. */
        fun serverTime(s: String): Long? = runCatching { java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli() }
            .recoverCatching { java.time.LocalDateTime.parse(s).toInstant(java.time.ZoneOffset.UTC).toEpochMilli() }
            .getOrNull()
    }
}

/** One stored save version on the server. [created] is the server's ISO-8601 time. */
data class SaveVersion(
    val version: Int,
    val size: Long,
    val sha256: String,
    val created: String,
    val source: String,
    val device: String?,
    val pinned: Boolean,
    val label: String?,
    val restoredFrom: Int?,
    val isCurrent: Boolean,
    /** "browser" = merged in from the old separate DS browser save list (D91); else null. */
    val origin: String? = null,
    /** The game saved this after a snapshot was loaded (D90). */
    val afterSnapshot: Boolean = false,
    /** Made while cheats were on (D143). */
    val cheats: Boolean = false,
) {
    /** Epoch ms, or null if the date can't be read. */
    val createdMillis: Long? get() = runCatching { java.time.OffsetDateTime.parse(created).toInstant().toEpochMilli() }.getOrNull()

    companion object {
        fun parse(o: JSONObject) = SaveVersion(
            version = o.getInt("version"),
            size = o.optLong("size"),
            sha256 = o.optString("sha256").lowercase(),
            created = o.optString("created"),
            source = o.optString("source"),
            device = o.optString("device").takeIf { it.isNotEmpty() && it != "null" },
            pinned = o.optBoolean("pinned"),
            label = o.optString("label").takeIf { it.isNotEmpty() && it != "null" },
            restoredFrom = if (o.has("restoredFrom") && !o.isNull("restoredFrom")) o.getInt("restoredFrom") else null,
            isCurrent = o.optBoolean("isCurrent"),
            origin = o.optString("origin").takeIf { it.isNotEmpty() && it != "null" },
            afterSnapshot = o.optBoolean("afterSnapshot"),
            cheats = o.optBoolean("cheats"),
        )
    }
}

/** A game's save history, newest first. [current] = 0 when there's no save. */
data class SaveHistory(val current: Int, val versions: List<SaveVersion>) {
    companion object {
        fun parse(o: JSONObject): SaveHistory {
            val a = o.optJSONArray("versions") ?: JSONArray()
            return SaveHistory(o.optInt("current"),
                (0 until a.length()).map { SaveVersion.parse(a.getJSONObject(it)) })
        }
    }
}

/** Save bytes plus which version they are and which version is current. */
class SaveDownload(val bytes: ByteArray, val version: Int, val current: Int, val sha256: String, val fileName: String?)

sealed class PutResult {
    /** Stored, or [unchanged] (the server already had these exact bytes). */
    data class Stored(val unchanged: Boolean, val version: SaveVersion?, val current: SaveVersion?, val warnings: List<String>) : PutResult()
    /** 409: the server has a different save than the one this phone last saw. Ask the user (D35). */
    data class Conflict(val current: SaveVersion?) : PutResult()
}

/** A refusal with the server's error code (e.g. 422 "blank", 429 "busy"). The message is plain English. */
class ApiException(code: Int, val error: String?, message: String) : HttpException(code, message)

/** `/shoalcade/info`. */
data class ServerInfo(
    val server: String,
    val serverVersion: String,
    val apiLevel: Int,
    val minAppVersion: String,
    val canDownload: Boolean,
    val keepRecent: Int,
    val keepDailyDays: Int,
    /** Most pinned versions per game, and longest save name (server's saveRules). */
    val maxPinned: Int = 0,
    val maxLabelLength: Int = JellyfinClient.DEFAULT_MAX_LABEL,
    /** What to show for the plugin, e.g. "s0.2 dev 1" (D88). Empty from plugins older than s0.2. */
    val display: String = "",
    /** Extra routes this server has, e.g. "cheats" (D143). Empty from older servers. */
    val features: List<String> = emptyList(),
) {
    companion object {
        fun parse(o: JSONObject): ServerInfo {
            val rules = o.optJSONObject("saveRules")
            return ServerInfo(
                server = o.optString("server"),
                serverVersion = o.optString("serverVersion"),
                apiLevel = o.optInt("apiLevel", 0),
                minAppVersion = o.optString("minAppVersion"),
                canDownload = o.optJSONObject("user")?.optBoolean("canDownload", true) ?: true,
                keepRecent = rules?.optInt("keepRecent") ?: 0,
                keepDailyDays = rules?.optInt("keepDailyDays") ?: 0,
                maxPinned = rules?.optInt("maxPinned") ?: 0,
                maxLabelLength = rules?.optInt("maxLabelLength", JellyfinClient.DEFAULT_MAX_LABEL)?.takeIf { it > 0 }
                    ?: JellyfinClient.DEFAULT_MAX_LABEL,
                display = o.optString("display"),
                features = o.optJSONArray("features")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
            )
        }
    }
}

/** One cheat in Jellyfin's list (D143). */
data class ServerCheat(val id: String, val name: String, val code: String) {
    companion object {
        private val ID = Regex("[0-9a-f]{12}")
        fun isId(id: String) = ID.matches(id)

        /** Null for an entry with a bad id (never used in a path). */
        fun parse(o: JSONObject): ServerCheat? {
            val id = o.optString("id")
            if (!isId(id)) return null
            return ServerCheat(id, o.optString("name"), o.optString("code"))
        }
    }
}

/** `/shoalcade/cheats/{itemId}`: the game's cheats and the ids switched on for its in-game save. */
data class CheatDoc(val cheats: List<ServerCheat>, val active: List<String>) {
    companion object {
        fun parse(o: JSONObject): CheatDoc {
            val c = o.optJSONArray("cheats") ?: JSONArray()
            val a = o.optJSONArray("active") ?: JSONArray()
            return CheatDoc((0 until c.length()).mapNotNull { ServerCheat.parse(c.getJSONObject(it)) },
                (0 until a.length()).map { a.optString(it) }.filter { ServerCheat.isId(it) })
        }
    }
}
