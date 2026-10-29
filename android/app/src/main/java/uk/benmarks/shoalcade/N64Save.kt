package uk.benmarks.shoalcade

/**
 * N64 save rules (plan-n64 A3, Step 0). The N64 save is Mupen64Plus-Next's whole save RAM: EEPROM, the four
 * Controller Paks, SRAM and FlashRAM in one 296,960-byte block, the same bytes the browser player keeps. The core
 * formats all four paks by itself, so a game that never saved is NOT all one byte and the old blank check would treat
 * it as a real save. [isUntouched] catches it. Same rule as the plugin's and the test-bed's (`makeroms.n64_untouched`);
 * all are tested against tools/testbed/n64_saves/.
 *
 * The paks' 24-byte serials come from a time-seeded RNG after the first load in a process (all zero on the first),
 * and the app keeps the core loaded between games, so the rule checks structure, never one fixed hash.
 */
object N64Save {
    const val SIZE = 0x48800 // 296,960
    private const val EEPROM = 0x00000
    private const val EEPROM_END = 0x00800
    private val PAKS = intArrayOf(0x00800, 0x08800, 0x10800, 0x18800)
    private const val PAK_SIZE = 0x8000
    private const val SRAM = 0x20800
    private const val FLASH_END = 0x48800 // SRAM 0x20800-0x28800, then FlashRAM to the end: one all-0xFF run

    /**
     * True for the core's own fresh block: exactly [SIZE] bytes, EEPROM, SRAM and FlashRAM all 0xFF, and every pak
     * freshly formatted and empty (any serial). Anything else (other sizes, any byte the game wrote) is a real save.
     */
    fun isUntouched(bytes: ByteArray?): Boolean {
        if (bytes == null || bytes.size != SIZE) return false
        if (!allFF(bytes, EEPROM, EEPROM_END) || !allFF(bytes, SRAM, FLASH_END)) return false
        return PAKS.all { freshPak(bytes, it) }
    }

    private fun allFF(b: ByteArray, from: Int, to: Int): Boolean {
        for (i in from until to) if (b[i] != 0xFF.toByte()) return false
        return true
    }

    private fun zero(b: ByteArray, from: Int, to: Int): Boolean {
        for (i in from until to) if (b[i] != 0.toByte()) return false
        return true
    }

    /**
     * A pak exactly as the core's format_mempak() leaves it. Page 0 = eight 32-byte blocks: 0, 2, 5, 7 zero; 1, 3, 4, 6
     * the same ID block (24-byte serial, device 00 01, banks 01, version 00, sum = big-endian 16-bit sum of words 0-13,
     * isum = 0xFFF2 - sum). Pages 1 and 2 = the index table (00 71, eight 00, then 00 03 × 123). Pages 3-127 zero.
     */
    private fun freshPak(b: ByteArray, at: Int): Boolean {
        for (blk in intArrayOf(0, 2, 5, 7)) if (!zero(b, at + blk * 32, at + blk * 32 + 32)) return false
        val id = at + 32
        for (blk in intArrayOf(3, 4, 6)) for (i in 0 until 32) if (b[at + blk * 32 + i] != b[id + i]) return false
        fun u8(i: Int) = b[id + i].toInt() and 0xFF
        if (u8(24) != 0x00 || u8(25) != 0x01 || u8(26) != 0x01 || u8(27) != 0x00) return false
        var sum = 0
        for (w in 0 until 14) sum += (u8(w * 2) shl 8) or u8(w * 2 + 1)
        sum = sum and 0xFFFF
        if (((u8(28) shl 8) or u8(29)) != sum) return false
        if (((u8(30) shl 8) or u8(31)) != ((0xFFF2 - sum) and 0xFFFF)) return false
        for (page in intArrayOf(1, 2)) {
            val p = at + page * 256
            if (b[p] != 0x00.toByte() || b[p + 1] != 0x71.toByte() || !zero(b, p + 2, p + 10)) return false
            for (i in 0 until 123) if (b[p + 10 + i * 2] != 0x00.toByte() || b[p + 11 + i * 2] != 0x03.toByte()) return false
        }
        return zero(b, at + 768, at + PAK_SIZE)
    }
}
