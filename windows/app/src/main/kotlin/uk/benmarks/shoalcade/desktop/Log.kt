package uk.benmarks.shoalcade.desktop

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The shell's log (`logs\shoalcade.log`, plus stderr). Gate W3: logs never contain the token. Every token the app
 * holds is registered with [secret] the moment it is loaded or made, and every line is passed through [redact]
 * before it is written, so even a careless message (an exception that quotes a header) can't leak it.
 */
object Log {
    private val secrets = CopyOnWriteArraySet<String>()
    @Volatile private var file: File? = null
    private val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    /** `Token="…"` in a Jellyfin Authorization header, whatever the value. */
    private val TOKEN_FIELD = Regex("""(Token=\\?")[^"\\]*""", RegexOption.IGNORE_CASE)

    fun to(f: File) { f.parentFile?.mkdirs(); file = f }

    /** Never shown again in a log line. Short strings are ignored (they'd blank out ordinary words). */
    fun secret(s: String?) { if (s != null && s.length >= 8) secrets += s }

    fun redact(line: String): String {
        var out = TOKEN_FIELD.replace(line) { it.groupValues[1] + "•••" }
        for (s in secrets) out = out.replace(s, "•••")
        return out
    }

    fun i(msg: String) = write("I", msg)
    fun w(msg: String, e: Throwable? = null) = write("W", msg + (e?.let { " (${it.javaClass.simpleName}: ${it.message})" } ?: ""))

    @Synchronized private fun write(level: String, msg: String) {
        val line = "${LocalDateTime.now().format(time)} $level ${redact(msg)}"
        System.err.println(line)
        file?.let { f -> runCatching { f.appendText(line + "\n") } }
    }
}
