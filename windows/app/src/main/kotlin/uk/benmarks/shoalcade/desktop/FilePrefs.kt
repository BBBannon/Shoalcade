package uk.benmarks.shoalcade.desktop

import android.content.SharedPreferences
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The desktop's SharedPreferences: string keys and values in one JSON file, written whole through [AtomicFile] (temp
 * file, flush, rename), so a crash never leaves half a file. A file that can't be read starts empty (these are
 * preferences and sync marks, never saves). One instance per file in this process, so every reader sees each write.
 */
class FilePrefs private constructor(private val file: File) : SharedPreferences {
    private val map: MutableMap<String, String> = read(file)

    @Synchronized override fun getString(key: String, defValue: String?): String? = map[key] ?: defValue
    @Synchronized override fun getBoolean(key: String, defValue: Boolean): Boolean = map[key]?.toBooleanStrictOrNull() ?: defValue
    @Synchronized override fun getAll(): Map<String, *> = HashMap(map)
    override fun edit(): SharedPreferences.Editor = Edit()

    private inner class Edit : SharedPreferences.Editor {
        private val puts = LinkedHashMap<String, String?>()
        private var clear = false
        override fun putString(key: String, value: String?) = apply { puts[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { puts[key] = value.toString() }
        override fun remove(key: String) = apply { puts[key] = null }
        override fun clear() = apply { clear = true }
        override fun apply() { commit() }
        override fun commit(): Boolean = synchronized(this@FilePrefs) {
            val next = if (clear) HashMap() else HashMap(map)
            puts.forEach { (k, v) -> if (v == null) next.remove(k) else next[k] = v }
            val ok = runCatching { AtomicFile.write(file, JSONObject(next as Map<*, *>).toString().toByteArray(Charsets.UTF_8)) }.isSuccess
            if (ok) { map.clear(); map.putAll(next) }
            ok
        }
    }

    companion object {
        private val open = ConcurrentHashMap<String, FilePrefs>()

        fun open(file: File): FilePrefs = open.computeIfAbsent(file.absoluteFile.normalize().path) { FilePrefs(File(it)) }

        private fun read(f: File): MutableMap<String, String> = runCatching {
            val o = JSONObject(f.readText(Charsets.UTF_8))
            o.keySet().associateWithTo(HashMap()) { o.getString(it) }
        }.getOrElse { HashMap() }
    }
}
