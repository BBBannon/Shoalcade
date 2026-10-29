package uk.benmarks.shoalcade

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** One file of a folder game (RPG Maker), as the server lists it: `/shoalcade/games/{id}/files`. */
data class FolderFile(val path: String, val size: Long, val sha256: String)

/** The server's list of a folder game's files. [json] is kept as sent, and written next to the game once it's complete. */
data class FolderList(val total: Long, val files: List<FolderFile>, val mayNotWork: Boolean, val engine: String?, val json: String) {
    companion object {
        /** Throws if the reply isn't a usable list (a bad path is never accepted: see [FolderDownload.safePath]). */
        fun parse(text: String): FolderList {
            val o = JSONObject(text)
            val a = o.optJSONArray("files") ?: throw IOException("The server sent no file list.")
            val files = (0 until a.length()).map { i ->
                val f = a.getJSONObject(i)
                val path = f.getString("path")
                if (FolderDownload.safePath(path) == null) throw IOException("The server's file list has a bad file name.")
                FolderFile(path, f.getLong("size"), f.optString("sha256").lowercase())
            }
            if (files.none { it.path.equals("Game.ini", true) }) throw IOException("This game has no Game.ini.")
            return FolderList(o.optLong("total", files.sumOf { it.size }), files, o.optBoolean("mayNotWork"), o.textOrNull("engine"), text)
        }
    }
}

/**
 * Downloads a folder game file by file (D147). Only files that are new or changed since the copy on the phone are
 * fetched (a file whose size and SHA-256 already match is kept), files the server no longer lists are removed, and the
 * list is written last ([Storage.FOLDER_LIST]), so the game only counts as downloaded once every file is in.
 * Stopping half way keeps what arrived: the next Download carries on from there.
 */
object FolderDownload {
    /** Files fetched at once: many games are thousands of small files, so one at a time would mostly be waiting. */
    private const val PARALLEL = 4

    /** A server path as a relative path, or null if it could step outside the game folder or isn't a plain name. */
    fun safePath(p: String): String? {
        if (p.isEmpty() || p.length > 1024 || p.startsWith("/") || p.contains('\\') || p.contains('\u0000') || p.contains(':')) return null
        val parts = p.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) return null
        if (parts.last() == Storage.FOLDER_LIST || parts.last().endsWith(".part")) return null
        return p
    }

    /** What [run] has to do: files to fetch and files to remove. Pure, so it's unit-tested. */
    data class Plan(val fetch: List<FolderFile>, val remove: List<String>) {
        val bytes get() = fetch.sumOf { it.size }
    }

    /**
     * [have] = relative paths of files already in the game folder, with their size; [known] = the SHA-256 the phone
     * last wrote for each (from the previous list). A file is kept only when its size and hash both match the server's;
     * [hashOf] works out the hash of a file the phone has but has no record for (a download stopped half way).
     */
    fun plan(list: FolderList, have: Map<String, Long>, known: Map<String, String>, hashOf: (String) -> String?): Plan {
        val wanted = list.files.mapTo(HashSet()) { it.path }
        val fetch = list.files.filter { f ->
            have[f.path] != f.size || f.sha256.isEmpty() || (known[f.path] ?: hashOf(f.path)) != f.sha256
        }
        return Plan(fetch, have.keys.filter { it !in wanted }.sorted())
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().toHex()
    }

    /** Every file in [dir] except the list and leftovers, by relative path. [files] = the files under it, if already listed. */
    fun filesIn(dir: File, files: Sequence<File> = dir.walkTopDown().filter { it.isFile }): Map<String, Long> = files
        .filter { it.name != Storage.FOLDER_LIST && !it.name.endsWith(".part") }
        .associate { it.relativeTo(dir).invariantSeparatorsPath to it.length() }

    /**
     * For the size question: bytes [run] would fetch now. A file with no hash on record (a stopped download) counts as
     * whole when its size matches, so this never hashes (the question shows at once).
     */
    fun stillToFetch(dir: File, list: FolderList): Long {
        val sha = list.files.associate { it.path to it.sha256 }
        return plan(list, filesIn(dir), knownHashes(File(dir, Storage.FOLDER_LIST))) { sha[it] }.bytes
    }

    private fun knownHashes(listFile: File): Map<String, String> = runCatching {
        FolderList.parse(listFile.readText()).files.associate { it.path to it.sha256 }
    }.getOrDefault(emptyMap())

    /**
     * Brings [dir] in line with [list]. [fetchFile] streams one server file to the given destination (it must write a
     * temporary file and rename it). [onProgress] gets 0..1 over the bytes that need fetching.
     */
    suspend fun run(
        dir: File,
        list: FolderList,
        fetchFile: suspend (FolderFile, File) -> Unit,
        onProgress: (Float) -> Unit,
    ) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val listFile = File(dir, Storage.FOLDER_LIST)
        val known = knownHashes(listFile) // the hashes of the last complete download
        // From here until the end the game isn't "downloaded": a stop half way never leaves a mix that looks complete.
        listFile.delete()
        val base = dir.canonicalFile
        fun target(rel: String): File {
            val f = File(dir, rel).canonicalFile
            if (!f.path.startsWith(base.path + File.separator)) throw IOException("The server's file list has a bad file name.")
            return f
        }
        // The folder is listed once: its files for the plan, its folders (deepest first) to tidy up after removals.
        val (files, dirs) = dir.walkTopDown().partition { it.isFile }
        val plan = plan(list, filesIn(dir, files.asSequence()), known) { rel -> runCatching { sha256(target(rel)) }.getOrNull() }
        for (rel in plan.remove) target(rel).delete()
        dirs.drop(1).asReversed().forEach { if (it.list()?.isEmpty() == true) it.delete() }

        val total = plan.bytes.coerceAtLeast(1)
        var done = 0L
        val lock = Mutex()
        val queue = ArrayDeque(plan.fetch)
        onProgress(0f)
        coroutineScope {
            (1..PARALLEL).map {
                async {
                    while (true) {
                        ensureActive()
                        val f = lock.withLock { queue.removeFirstOrNull() } ?: break
                        val dest = target(f.path)
                        dest.parentFile?.mkdirs()
                        fetchFile(f, dest)
                        if (dest.length() != f.size) throw IOException("${f.path} didn't download completely.")
                        lock.withLock { done += f.size; onProgress(done.toFloat() / total) }
                    }
                }
            }.awaitAll()
        }
        // Written last: now the game counts as downloaded, and the next update knows each file's hash.
        val tmp = File(dir, Storage.FOLDER_LIST + ".part")
        tmp.writeText(list.json)
        if (!replace(tmp, listFile)) throw IOException("Couldn't finish the download.")
        onProgress(1f)
    }

    /** Puts [part] in place of [dest]. Android's rename replaces; where it can't (Windows test hosts), the old file goes first. */
    fun replace(part: File, dest: File): Boolean = part.renameTo(dest) || (dest.delete() && part.renameTo(dest))

    /** For the size warning: "767 MB". */
    fun sizeText(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(java.util.Locale.UK, "%.1f GB", bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "${bytes / (1L shl 20)} MB"
        else -> "${(bytes / 1024).coerceAtLeast(1)} KB"
    }

    /** A list as the server sends it (tests). */
    internal fun listJson(files: List<FolderFile>): String = JSONObject()
        .put("total", files.sumOf { it.size })
        .put("files", JSONArray(files.map { JSONObject().put("path", it.path).put("size", it.size).put("sha256", it.sha256) }))
        .toString()
}

/** Lower-case hex, as the server writes SHA-256s. */
internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
