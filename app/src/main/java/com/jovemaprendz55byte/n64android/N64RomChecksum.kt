package com.jovemaprendz55byte.n64android

/**
 * Independent host-side CIC-6105 ROM checksum calculation.
 *
 * This is diagnostic-only: it checks the normalized cartridge bytes without
 * relying on the emulated R4300, PI DMA, or RDRAM implementation.
 */
internal object N64RomChecksum {
    private const val CHECKSUM_START = 0x1000
    private const val CHECKSUM_END = 0x101000
    private const val CIC6105_SEED = 0xDF26F436u

    /** Returns CRC1/CRC2, or null when the ROM is too short for the 1 MiB region. */
    fun calculateCic6105(rom: ByteArray): Pair<UInt, UInt>? {
        if (rom.size < CHECKSUM_END) return null

        var t1 = CIC6105_SEED
        var t2 = CIC6105_SEED
        var t3 = CIC6105_SEED
        var t4 = CIC6105_SEED
        var t5 = CIC6105_SEED
        var t6 = CIC6105_SEED

        var offset = CHECKSUM_START
        while (offset < CHECKSUM_END) {
            val data = readWord(rom, offset)
            val sum = t6 + data
            if (sum < t6) t4 += 1u
            t6 = sum
            t3 = t3 xor data

            val rotate = Integer.rotateLeft(data.toInt(), (data and 0x1Fu).toInt()).toUInt()
            t5 += rotate
            t2 = if (t2 > data) t2 xor rotate else t2 xor t6 xor data

            // CIC-6105 folds in a word from the IPL3 bootcode for each data word.
            val bootWord = readWord(rom, 0x750 + (offset and 0xFF))
            t1 += bootWord xor data
            offset += 4
        }

        return Pair(t6 xor t4 xor t3, t5 xor t2 xor t1)
    }

    private fun readWord(bytes: ByteArray, offset: Int): UInt =
        (((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)).toUInt()
}
