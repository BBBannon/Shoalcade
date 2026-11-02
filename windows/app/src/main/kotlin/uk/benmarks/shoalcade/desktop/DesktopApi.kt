package uk.benmarks.shoalcade.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import uk.benmarks.shoalcade.ApiException
import uk.benmarks.shoalcade.BadReplyException
import uk.benmarks.shoalcade.BuildConfig
import uk.benmarks.shoalcade.JellyfinClient
import uk.benmarks.shoalcade.SaveVersion
import uk.benmarks.shoalcade.ServerCheat
import uk.benmarks.shoalcade.ServerStatus
import uk.benmarks.shoalcade.ServerUnreachableException
import uk.benmarks.shoalcade.Session
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.file.StandardCopyOption
import java.time.Duration

/**
 * The plugin routes the phone app never calls, which the Windows app has because the browser has them (plan §3.2):
 * the saves manager's Move / Delete a version / Start fresh / Delete all of a removed game / Download all my saves,
 * the saves list (games no longer in the library), and the server's snapshots (slots 1-3 + Automatic, with picture
 * and cheats). Plus Pin / Name (PATCH), because the JVM's own HttpURLConnection can't send PATCH over https.
 *
 * Same rules as the shared [JellyfinClient]: the token only ever goes in the Authorization header (same header,
 * same device name and id, so Jellyfin sees one device); item ids are checked before they go in a URL; refusals
 * become [ApiException] with the server's own words ([JellyfinClient.friendly]); no answer becomes
 * [ServerUnreachableException]; every answer updates the server light ([ServerStatus]).
 */
class DesktopApi(
    private val session: Session,
    private val deviceId: String,
    private val deviceName: String = android.os.Build.MODEL ?: "Windows",
    private val timeouts: JellyfinClient.Timeouts = JellyfinClient.Timeouts(),
) {
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(timeouts.connect.toLong()))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8").replace("+", "%20")

    private val auth: String
        get() = "MediaBrowser Client=\"Shoalcade\", Device=\"${enc(deviceName)}\", DeviceId=\"${enc(deviceId)}\", " +
            "Version=\"${BuildConfig.VERSION_NAME}\", Token=\"${session.token}\""

    private fun id(itemId: String): String {
        if (!JellyfinClient.isSafeId(itemId)) throw IOException("Bad game id from the server.")
        return itemId
    }

    private fun slot(s: String): String {
        if (s !in SLOTS) throw IOException("Bad snapshot slot.")
        return s
    }

    private fun request(path: String, timeout: Long? = timeouts.call): HttpRequest.Builder =
        HttpRequest.newBuilder(URI.create(session.serverUrl + path))
            .header("Authorization", auth)
            .header("Accept", "application/json, */*")
            .apply { if (timeout != null) timeout(Duration.ofMillis(timeout)) }

    /** Sends [req]; 2xx comes back, anything else is an [ApiException] (409 included, for the callers that ask again). */
    private suspend fun <T> send(req: HttpRequest, body: HttpResponse.BodyHandler<T>): HttpResponse<T> = withContext(Dispatchers.IO) {
        val res = try {
            http.send(req, body)
        } catch (e: HttpConnectTimeoutException) {
            ServerStatus.report(false); throw ServerUnreachableException(true, e)
        } catch (e: HttpTimeoutException) {
            ServerStatus.report(false); throw ServerUnreachableException(true, e)
        } catch (e: ConnectException) {
            ServerStatus.report(false); throw ServerUnreachableException(false, e)
        } catch (e: IOException) {
            ServerStatus.report(false); throw ServerUnreachableException(false, e)
        }
        ServerStatus.report(JellyfinClient.answered(res.statusCode()))
        res
    }

    private fun check(res: HttpResponse<*>, text: () -> String?) {
        val c = res.statusCode()
        if (c in 200..299) return
        val (err, msg) = runCatching { JSONObject(text() ?: "{}").let { it.optString("error").ifEmpty { null } to it.optString("message").ifEmpty { null } } }
            .getOrDefault(null to null)
        throw ApiException(c, err, JellyfinClient.friendly(c, err, msg))
    }

    private suspend fun json(req: HttpRequest): JSONObject {
        val res = send(req, HttpResponse.BodyHandlers.ofString())
        check(res) { res.body() }
        return try { JSONObject(res.body().ifBlank { "{}" }) } catch (e: JSONException) { ServerStatus.report(false); throw BadReplyException() }
    }

    private fun jsonBody(o: JSONObject) = HttpRequest.BodyPublishers.ofString(o.toString())

    // ---- Saves manager (SavesController.cs; the browser's api.js / saves.js) ----

    /** Pin / unpin and/or name a version (PATCH, as [JellyfinClient.updateVersion]; label cleaned the same way). */
    suspend fun updateVersion(itemId: String, version: Int, pinned: Boolean? = null, label: String? = null, maxLabel: Int = 60): SaveVersion? {
        require(pinned != null || label != null) { "Nothing to change." }
        val body = JSONObject()
        if (pinned != null) body.put("pinned", pinned)
        if (label != null) body.put("label", JellyfinClient.cleanLabel(label, maxLabel))
        val o = json(request("/shoalcade/save/${id(itemId)}/versions/$version").header("Content-Type", "application/json")
            .method("PATCH", jsonBody(body)).build())
        return o.optJSONObject("version")?.let(SaveVersion::parse)
    }

    /** Delete a version for good. The server refuses the one in use and pinned ones. */
    suspend fun deleteVersion(itemId: String, version: Int) {
        json(request("/shoalcade/save/${id(itemId)}/versions/$version").DELETE().build())
    }

    /** "Start fresh": no save in use afterwards; every version stays in the list. [base] = the version shown in use. */
    suspend fun startFresh(itemId: String, base: Int): SaveVersion? {
        val o = json(request("/shoalcade/save/${id(itemId)}/clear?base=$base").POST(HttpRequest.BodyPublishers.noBody()).build())
        return o.optJSONObject("current")?.let(SaveVersion::parse)
    }

    /**
     * Move every version (and the game's snapshots and cheats) to [to]. Returns the server's notes (snapshots moved too…).
     * [fromConsole]: for a removed game whose console wasn't recorded, the console key the person picked (F7 gap 2); the
     * server never lets it override a recorded one and checks every version fits it.
     */
    suspend fun move(itemId: String, to: String, fromConsole: String? = null): List<String> {
        val pick = fromConsole?.let { "&fromConsole=${enc(it)}" } ?: ""
        val o = json(request("/shoalcade/save/${id(itemId)}/move?to=${id(to)}$pick").POST(HttpRequest.BodyPublishers.noBody()).build())
        val w = o.optJSONArray("warnings") ?: JSONArray()
        return (0 until w.length()).mapNotNull { w.opt(it) as? String }
    }

    /** Deletes everything of a game no longer in the library. The counts shown must still be right (409 otherwise). */
    suspend fun deleteAll(itemId: String, versions: Int, snapshots: Int): Pair<Int, Int> {
        val o = json(request("/shoalcade/save/${id(itemId)}/delete-all?versions=$versions&snapshots=$snapshots")
            .POST(HttpRequest.BodyPublishers.noBody()).build())
        return o.optInt("versions") to o.optInt("snapshots")
    }

    /** One game in the saves list (`GET /shoalcade/saves`): [status] "ok" (in the library) or "removed". */
    data class SaveEntry(val itemId: String, val gameName: String?, val console: String?, val status: String,
                         val current: SaveVersion?, val versionCount: Int, val totalBytes: Long, val snapshots: Int)

    suspend fun savesList(): List<SaveEntry> {
        val o = json(request("/shoalcade/saves").GET().build())
        val a = o.optJSONArray("saves") ?: JSONArray()
        return (0 until a.length()).map { a.getJSONObject(it) }.filter { JellyfinClient.isSafeId(it.optString("itemId")) }.map { e ->
            SaveEntry(e.getString("itemId"), e.optString("gameName").takeIf { it.isNotEmpty() && it != "null" },
                e.optString("console").takeIf { it.isNotEmpty() && it != "null" }, e.optString("status"),
                e.optJSONObject("current")?.let(SaveVersion::parse), e.optInt("versionCount"), e.optLong("totalBytes"),
                e.optInt("snapshotCount"))
        }
    }

    /**
     * "Download all my saves": the server's zip of every save in use, written to [dest] through a temporary file (a
     * cut-off download never leaves a half zip there). Returns the server's suggested name.
     */
    suspend fun exportAll(dest: File): String? {
        val part = File(dest.parentFile, dest.name + ".part")
        val res = send(request("/shoalcade/saves/export", timeout = null).GET().build(), HttpResponse.BodyHandlers.ofFile(part.toPath()))
        try {
            check(res) { runCatching { part.readText() }.getOrNull() }
            withContext(Dispatchers.IO) {
                java.nio.file.Files.move(part.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }
        } finally {
            part.delete()
        }
        return JellyfinClient.contentDispositionName(res.headers().firstValue("Content-Disposition").orElse(null))
    }

    // ---- The Windows app's own update (AppController S2, D165 / D181) ----

    /** A newer Windows app: its version and the page its installer is on. */
    data class WindowsApp(val version: String, val url: String)

    /**
     * `GET /shoalcade/app/info?platform=windows`: the version the user's plugin says is current, and its release page.
     * Null if the admin turned it off (404), or the answer has no version or no https page (never opens anything else).
     */
    suspend fun windowsApp(): WindowsApp? {
        val res = send(request("/shoalcade/app/info?platform=windows").GET().build(), HttpResponse.BodyHandlers.ofString())
        if (res.statusCode() == 404) return null
        check(res) { res.body() }
        val o = try { JSONObject(res.body()) } catch (e: JSONException) { throw BadReplyException() }
        val v = o.optString("version").takeIf { o.optBoolean("available") && it.isNotEmpty() && it != "null" } ?: return null
        val url = o.optString("url").takeIf { safePage(it) } ?: return null
        return WindowsApp(v, url)
    }

    // ---- Snapshots on the server (StatesController.cs; the browser's play.js "snapshots") ----

    /** One filled slot. [core] = the core build that made it (a snapshot only ever loads in the same one). */
    data class Snapshot(val slot: String, val size: Long, val created: String, val core: String?, val hasImage: Boolean,
                        val sha256: String?, val damaged: Boolean, val cheats: List<Pair<String, String>>) {
        val createdMillis: Long? get() = runCatching { java.time.OffsetDateTime.parse(created).toInstant().toEpochMilli() }.getOrNull()

        companion object {
            fun parse(o: JSONObject): Snapshot {
                val c = o.optJSONArray("cheats") ?: JSONArray()
                return Snapshot(o.optString("slot"), o.optLong("size"), o.optString("created"),
                    o.optString("core").takeIf { it.isNotEmpty() && it != "null" }, o.optBoolean("hasImage"),
                    o.optString("sha256").takeIf { it.isNotEmpty() && it != "null" }?.lowercase(), o.optBoolean("damaged"),
                    (0 until c.length()).mapNotNull { c.optJSONObject(it) }.map { it.optString("name") to it.optString("code") })
            }
        }
    }

    /**
     * The filled slots, whether snapshots are on for this console (the person's server setting, D38), and the biggest
     * snapshot the server takes ([SnapshotList.maxBytes], null from an older plugin).
     */
    data class SnapshotList(val list: List<Snapshot>, val enabled: Boolean, val maxBytes: Long?)

    suspend fun snapshots(itemId: String): SnapshotList {
        val o = json(request("/shoalcade/state/${id(itemId)}").GET().build())
        val a = o.optJSONArray("snapshots") ?: JSONArray()
        return SnapshotList((0 until a.length()).mapNotNull { a.optJSONObject(it) }.map(Snapshot::parse).filter { it.slot in SLOTS },
            o.optBoolean("enabled", true), o.optLong("maxBytes", 0L).takeIf { it > 0 })
    }

    /** A snapshot's bytes and the core that made it (`X-Shoalcade-Core`). */
    suspend fun snapshotBytes(itemId: String, slot: String): Pair<ByteArray, String?> {
        val res = send(request("/shoalcade/state/${id(itemId)}/${slot(slot)}", timeout = null).GET().build(), HttpResponse.BodyHandlers.ofByteArray())
        check(res) { String(res.body(), Charsets.UTF_8) }
        return res.body() to res.headers().firstValue("X-Shoalcade-Core").orElse(null)
    }

    suspend fun snapshotImage(itemId: String, slot: String): ByteArray? {
        val res = send(request("/shoalcade/state/${id(itemId)}/${slot(slot)}/image").GET().build(), HttpResponse.BodyHandlers.ofByteArray())
        if (res.statusCode() == 404) return null
        check(res) { String(res.body(), Charsets.UTF_8) }
        return res.body()
    }

    /** Stores a snapshot made by [core] (≤ 32 letters, digits, `-` or `_`, as the server keeps it). */
    suspend fun putSnapshot(itemId: String, slot: String, bytes: ByteArray, core: String): Snapshot {
        val o = json(request("/shoalcade/state/${id(itemId)}/${slot(slot)}?core=${enc(core)}", timeout = timeouts.saveCall)
            .header("Content-Type", "application/octet-stream").PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build())
        return Snapshot.parse(o)
    }

    suspend fun putSnapshotImage(itemId: String, slot: String, png: ByteArray) {
        json(request("/shoalcade/state/${id(itemId)}/${slot(slot)}/image").header("Content-Type", "image/png")
            .PUT(HttpRequest.BodyPublishers.ofByteArray(png)).build())
    }

    /** The cheats that were on (D143), name and code each; sent after the snapshot itself, as the browser does. */
    suspend fun putSnapshotCheats(itemId: String, slot: String, cheats: List<Pair<String, String>>) {
        val arr = JSONArray()
        cheats.forEach { (n, c) -> arr.put(JSONObject().put("name", n).put("code", c)) }
        json(request("/shoalcade/state/${id(itemId)}/${slot(slot)}/cheats").header("Content-Type", "application/json")
            .PUT(jsonBody(JSONObject().put("cheats", arr))).build())
    }

    suspend fun deleteSnapshot(itemId: String, slot: String) {
        val res = send(request("/shoalcade/state/${id(itemId)}/${slot(slot)}").DELETE().build(), HttpResponse.BodyHandlers.ofString())
        if (res.statusCode() == 404) return // already gone
        check(res) { res.body() }
    }

    companion object {
        /** The server's slots: 1-3 and the Automatic one ("auto"). */
        val SLOTS = listOf("1", "2", "3", "auto")

        /** Only an https web page with a host is ever opened from a server's answer (no file:, no javascript:, no http). */
        fun safePage(u: String?): Boolean = runCatching {
            val uri = URI(u ?: return false)
            uri.scheme.equals("https", true) && !uri.host.isNullOrEmpty() && uri.userInfo == null
        }.getOrDefault(false)

        /** A cheat as the server's snapshot list keeps it. */
        fun cheatPairs(list: List<ServerCheat>) = list.map { it.name to it.code }
    }
}
