package uk.benmarks.shoalcade

/**
 * PS1 memory card rules (D135). The PS1 save is memory card 1: a raw 128 KB card image, the same bytes the browser
 * player keeps. PCSX ReARMed formats an empty card by itself when it has none, so an untouched card is NOT all one
 * byte and the old blank check would treat it as a real save. [isEmptyCard] catches it. Same rule as the plugin's
 * PsxCard.IsEmptyCard; both are tested against tools/testbed/psx_cards/.
 */
object PsxCard {
    const val SIZE = 131_072
    private const val FRAME = 128
    private const val FREE = 0xA0

    /**
     * True for a formatted card with nothing saved on it: exactly [SIZE] bytes, frame 0 starts "MC", frames 0–15
     * (header + directory) each have a valid XOR checksum in their last byte, and all 15 directory entries are free.
     * Anything else (other sizes, damaged or used cards, other data that happens to start "MC") is not empty.
     */
    fun isEmptyCard(bytes: ByteArray?): Boolean {
        if (bytes == null || bytes.size != SIZE) return false
        if (bytes[0] != 'M'.code.toByte() || bytes[1] != 'C'.code.toByte()) return false
        for (f in 0 until 16) {
            val at = f * FRAME
            var x = 0
            for (i in at until at + FRAME - 1) x = x xor (bytes[i].toInt() and 0xFF)
            if (x != (bytes[at + FRAME - 1].toInt() and 0xFF)) return false
            if (f > 0 && (bytes[at].toInt() and 0xFF) != FREE) return false
        }
        return true
    }
}
