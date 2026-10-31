package uk.benmarks.shoalcade

import java.io.File

/**
 * A quick look at a game file before an emulator opens it. The emulators can crash the whole app on a file that
 * isn't a game at all (e.g. a 13-byte "iso": the PSP engine stopped the app), so a file that clearly isn't one is
 * refused with a message instead. Only clear cases are refused: too small to hold the console's header, or the
 * wrong file type marker for disc and container formats. Cartridge checksums are not checked, so homebrew and
 * patched games with a wrong checksum still play.
 */
object RomCheck {

    /** Why the file can't be a game for [console], or null if it may be (the emulator decides). */
    fun problem(console: Console, file: File): String? {
        val size = file.length()
        if (size <= 0L) return "the file is empty"
        val head = readHead(file) ?: return "the file can't be read"
        return problem(console, file.extension.lowercase(), size, head)
    }

    /** As [problem], from the file's extension, size and first [HEAD] bytes (unit-tested). */
    fun problem(console: Console, ext: String, size: Long, head: ByteArray): String? {
        fun at(offset: Int, magic: String) = head.size >= offset + magic.length &&
            magic.indices.all { head[offset + it] == magic[it].code.toByte() }
        fun tooSmall(min: Int) = if (size < min) "it's too small (${size} bytes)" else null
        fun blank(from: Int, to: Int) = head.size >= to && (from until to).all { head[it] == head[from] } &&
            (head[from] == 0.toByte() || head[from] == 0xFF.toByte())
        val mark = "the file type marker is wrong"

        return when (console) {
            // Cartridges: big enough for the header, and the header isn't all 00 or FF.
            Console.GB, Console.GBC -> tooSmall(0x150) ?: if (blank(0x134, 0x150)) "the game header is blank" else null
            Console.GBA -> tooSmall(0xC0) ?: if (blank(0xA0, 0xC0)) "the game header is blank" else null
            Console.NDS -> tooSmall(0x200) ?: if (blank(0x00, 0x20)) "the game header is blank" else null
            Console.N64 -> tooSmall(0x1000) ?: run {
                val b = head.take(4).map { it.toInt() and 0xFF }
                // Big-endian (.z64), byte-swapped (.v64) and little-endian (.n64) headers.
                if (b == listOf(0x80, 0x37, 0x12, 0x40) || b == listOf(0x37, 0x80, 0x40, 0x12) || b == listOf(0x40, 0x12, 0x37, 0x80)) null else mark
            }
            // Discs and containers: a fixed marker near the start.
            Console.PSP, Console.PSX -> when (ext) {
                "iso" -> tooSmall(0x8006) ?: if (at(0x8001, "CD001")) null else mark
                "cso" -> tooSmall(0x18) ?: if (at(0, "CISO")) null else mark
                "pbp" -> tooSmall(0x28) ?: if (at(0, "\u0000PBP")) null else mark
                "chd" -> tooSmall(0x7C) ?: if (at(0, "MComprHD")) null else mark
                else -> null
            }
            Console.N3DS -> when (ext) {
                "3ds", "cci" -> tooSmall(0x200) ?: if (at(0x100, "NCSD")) null else mark
                "cxi" -> tooSmall(0x200) ?: if (at(0x100, "NCCH")) null else mark
                "3dsx" -> tooSmall(0x20) ?: if (at(0, "3DSX")) null else mark
                else -> tooSmall(0x200) // compressed forms: the 3DS engine checks those itself
            }
        }
    }

    /** "This file doesn't look like a Game Boy Advance game: it's too small (13 bytes). …" */
    fun message(console: Console, why: String) =
        "This file doesn't look like a ${console.tag} game: $why. Check the game file on your Jellyfin server, then download it again."

    /** Bytes read from the start: enough for every check above (the ISO marker sits at 0x8001). */
    const val HEAD = 0x8010

    private fun readHead(file: File): ByteArray? = runCatching {
        file.inputStream().use { input ->
            val buf = ByteArray(minOf(HEAD.toLong(), file.length()).toInt())
            var n = 0
            while (n < buf.size) {
                val r = input.read(buf, n, buf.size - n)
                if (r < 0) break
                n += r
            }
            if (n == buf.size) buf else buf.copyOf(n)
        }
    }.getOrNull()
}
