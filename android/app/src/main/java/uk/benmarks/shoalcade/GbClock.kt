package uk.benmarks.shoalcade

import java.io.File

/**
 * Keeps the Game Boy cartridge clock (MBC3, e.g. Pokémon Gold/Silver/Crystal) between sessions, like the
 * battery in a real cartridge. mGBA keeps the clock (48 bytes) straight after the save in memory, and the
 * clock shim (cpp/clockshim.c) reports both as one block, so LibretroDroid loads and reads them together.
 * The save part stays byte-identical to before; the clock goes in its own file (Storage.clockFile).
 */
object GbClock {
    const val SHIM = "libclockshim.so"
    const val SIZE = 48 // mGBA's GBMBCRTCSaveBuffer

    /** Cartridge type byte 0x147: 0x0F = MBC3 + clock, 0x10 = MBC3 + clock + RAM. */
    fun hasClock(rom: File): Boolean = runCatching {
        rom.inputStream().use { s ->
            val header = ByteArray(0x148)
            if (s.readNBytesCompat(header) < header.size) return false
            header[0x147].toInt() and 0xFF in setOf(0x0F, 0x10)
        }
    }.getOrDefault(false)

    /** What to hand LibretroDroid at start: save + clock if we have both, else just the save. */
    fun join(save: ByteArray?, clock: ByteArray?): ByteArray? =
        if (save != null && clock != null && clock.size == SIZE) save + clock else save

    /**
     * Splits what LibretroDroid read back into (save, clock). Saves are always a power of two in size,
     * so a block of power-of-two + 48 bytes has the clock on the end; anything else is passed through as is.
     */
    fun split(bytes: ByteArray?): Pair<ByteArray?, ByteArray?> {
        if (bytes == null || bytes.size <= SIZE) return bytes to null
        val n = bytes.size - SIZE
        if (n and (n - 1) != 0) return bytes to null
        return bytes.copyOfRange(0, n) to bytes.copyOfRange(n, bytes.size)
    }

    private fun java.io.InputStream.readNBytesCompat(buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val r = read(buf, off, buf.size - off)
            if (r < 0) break
            off += r
        }
        return off
    }
}
