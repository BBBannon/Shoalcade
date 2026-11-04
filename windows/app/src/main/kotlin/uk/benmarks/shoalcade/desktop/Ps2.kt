package uk.benmarks.shoalcade.desktop

import android.content.Context
import java.io.File
import java.io.IOException

/**
 * PS2 on Windows (D218; plan-windows-gc-wii-ps2.md §7 and §14.1): which emulator plays PS2 games on this PC, and the
 * PS2 BIOS the person copied from their own console. Both are kept on this PC only (the `ps2` store); the BIOS is
 * never bundled, never uploaded and never sent anywhere.
 *
 * **PS2 emulator** (D-3): LRPS2 (the default) or PCEE2 (newer PCSX2). Both use the same memory card, the game's own
 * `<save folder>\<game file name>.ps2` in the same 8 MB format, so switching never moves or rewrites the card. Each
 * core has its own snapshot tag ([Cores.tag]), so a snapshot only loads in the emulator that made it.
 *
 * **PS2 BIOS file…**: the file is checked the way both cores check it (PCSX2's `IsBIOS`: 4 to 8 MB, a ROM directory
 * that starts with `RESET` and holds `ROMVER`), copied into the player's system folder `pcsx2\bios\` and named in the
 * `pcsx2_bios` core option (both cores read that option). A PS2 game never starts without one: PCEE2 would show a
 * black screen.
 */
object Ps2 {
    enum class Emulator(val core: String, val label: String) {
        LRPS2("lrps2", "LRPS2"),
        PCEE2("pcee2", "PCEE2 (newer PCSX2)"),
    }

    const val EMULATOR_TITLE = "PS2 emulator"
    const val EMULATOR_HELP = "Try the other one if a game doesn't work. Snapshots only load in the emulator that made them."
    const val BIOS_BUTTON = "PS2 BIOS file…"
    const val BIOS_HELP = "PS2 games need a BIOS file copied from your own PlayStation 2. Shoalcade never includes one or " +
        "sends it anywhere: it stays on this PC."
    const val BIOS_MISSING = "This PS2 game needs a BIOS file. Choose one in Settings → PS2 BIOS file…"
    const val BIOS_BAD = "That file isn't a PS2 BIOS. Choose the BIOS file you copied from your PlayStation 2."

    const val MIN_SIZE = 4L * 1024 * 1024
    const val MAX_SIZE = 8L * 1024 * 1024

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("ps2", Context.MODE_PRIVATE)

    fun emulator(ctx: Context): Emulator =
        prefs(ctx).getString("emulator", null)?.let { k -> Emulator.entries.firstOrNull { it.core == k } } ?: Emulator.LRPS2

    const val PRESSURE_TITLE = "Right trigger as pressure ✕"
    const val PRESSURE_HELP = "For games that use light presses of ✕ (for example to aim or drive gently): the right " +
        "trigger presses ✕ as hard as you pull it. R2 then moves to clicking the right stick."

    /** P4b: the right trigger as a pressure-sensitive ✕, off by default (kept on this PC). */
    fun pressureCross(ctx: Context): Boolean = prefs(ctx).getBoolean("pressureCross", false)

    fun setPressureCross(ctx: Context, on: Boolean) {
        prefs(ctx).edit().apply { if (on) putBoolean("pressureCross", true) else remove("pressureCross") }.apply()
    }

    fun setEmulator(ctx: Context, e: Emulator) {
        prefs(ctx).edit().apply { if (e == Emulator.LRPS2) remove("emulator") else putString("emulator", e.core) }.apply()
    }

    // ---- The BIOS check ----

    /** What [check] found: [region] ("Europe") and [version] ("2.00") from the BIOS's own ROMVER. */
    data class Bios(val region: String, val version: String) {
        val label get() = "Found: PS2 BIOS, $region, v$version"
    }

    /**
     * PCSX2's `IsBIOS` (BiosTools.cpp LoadBiosVersion), on [bytes] of a file [size] bytes long: 4-8 MB, then 16-byte
     * ROM directory records from the start until one named `RESET`; from there each record's file follows the last
     * (sizes rounded up to 16) until a blank name, and `ROMVER` must be one of them (14 characters: version, region
     * letter, ...). Null = not a BIOS.
     */
    fun check(bytes: ByteArray, size: Long = bytes.size.toLong()): Bios? {
        if (size !in MIN_SIZE..MAX_SIZE) return null
        fun name(at: Int): String? {
            if (at + 16 > bytes.size) return null
            val raw = bytes.copyOfRange(at, at + 10)
            val end = raw.indexOf(0)
            if (end < 0) return null // ten characters with no end: not a name (PCSX2 stops there)
            return String(raw, 0, end, Charsets.ISO_8859_1)
        }
        fun u32(at: Int) = (bytes[at].toLong() and 0xFF) or ((bytes[at + 1].toLong() and 0xFF) shl 8) or
            ((bytes[at + 2].toLong() and 0xFF) shl 16) or ((bytes[at + 3].toLong() and 0xFF) shl 24)
        var rec = -1
        var at = 0
        while (at + 16 <= bytes.size) {
            if (name(at) == "RESET") { rec = at; break }
            at += 16
        }
        if (rec < 0) return null
        var offset = 0L
        var romver: String? = null
        while (true) {
            val n = name(rec) ?: break
            if (n.isEmpty()) break
            val fileSize = u32(rec + 12)
            if (n == "ROMVER" && offset >= 0 && offset + 14 <= bytes.size) romver = String(bytes, offset.toInt(), 14, Charsets.ISO_8859_1)
            offset += if (fileSize % 16 == 0L) fileSize else (fileSize + 16) and 0xFFFFFFF0L
            rec += 16
        }
        val v = romver ?: return null
        if (!v.substring(0, 4).all { it.isDigit() }) return null
        val region = when (v[4]) {
            'J' -> "Japan"; 'A' -> "USA"; 'E' -> "Europe"; 'H' -> "Asia"; 'C' -> "China"
            else -> "region ${v[4]}"
        }
        return Bios(region, "${v.substring(0, 2).trimStart('0').ifEmpty { "0" }}.${v.substring(2, 4)}")
    }

    /** [check] on a file (read only if its size can be a BIOS). */
    fun check(f: File): Bios? {
        val len = f.length()
        if (!f.isFile || len !in MIN_SIZE..MAX_SIZE) return null
        return runCatching { check(f.readBytes(), len) }.getOrNull()
    }

    // ---- Where it lives ----

    /** The player's system folder for both PS2 cores: `<systemDir>\pcsx2\bios\`. */
    fun biosDir(systemDir: File) = File(systemDir, "pcsx2/bios")

    /** A name safe as a file name and as the core option's value (letters, digits, `.`, `-`, `_`, spaces). */
    fun safeName(name: String): String {
        val n = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().trimStart('.').take(64)
        return n.ifEmpty { "ps2-bios.bin" }
    }

    /**
     * Checks [source] and copies it into [systemDir]'s BIOS folder (never moves or changes the person's own file), then
     * keeps its name as the one to use. Throws IOException with [BIOS_BAD] if it isn't one. Returns what was found.
     */
    fun install(ctx: Context, systemDir: File, source: File): Bios {
        val bytes = try {
            if (!source.isFile || source.length() !in MIN_SIZE..MAX_SIZE) throw IOException(BIOS_BAD)
            source.readBytes()
        } catch (e: IOException) {
            throw if (e.message == BIOS_BAD) e else IOException("Couldn't read that file: ${e.message}")
        }
        val bios = check(bytes) ?: throw IOException(BIOS_BAD)
        val target = File(biosDir(systemDir), safeName(source.name))
        AtomicFile.write(target, bytes)
        prefs(ctx).edit().putString("bios", target.name).apply()
        return bios
    }

    /** The BIOS this PC uses, if it is still there and still a BIOS; else null (PS2 games then don't start). */
    fun installed(ctx: Context, systemDir: File): File? {
        val n = prefs(ctx).getString("bios", null) ?: return null
        if (safeName(n) != n) return null
        return File(biosDir(systemDir), n).takeIf { it.isFile && it.length() in MIN_SIZE..MAX_SIZE }
    }

    /** What Settings shows about the BIOS now. */
    fun status(ctx: Context, systemDir: File): String =
        installed(ctx, systemDir)?.let { f -> check(f)?.label?.let { "$it (${f.name})" } } ?: "No PS2 BIOS chosen yet."

    /** The core option naming the BIOS, for a PS2 game's start (both cores read `pcsx2_bios`). */
    fun options(ctx: Context, systemDir: File): Map<String, String> =
        installed(ctx, systemDir)?.let { mapOf("pcsx2_bios" to it.name) } ?: emptyMap()
}
