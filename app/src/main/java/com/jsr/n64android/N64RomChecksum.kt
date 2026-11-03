package com.jsr.n64android

/**
 * CIC 6102/7101 checksum used by the cartridge IPL3 over the first 1 MiB
 * staged at the game's entry point. Input words are read big-endian.
 *
 * This is the checksum for the game image (ROM 0x1000..0x100FFF), not the
 * separate IPL3/PIF lockout checksum over ROM 0x40..0xFFF.
 */
object N64RomChecksum {
    data class Pair(val crc1: Int, val crc2: Int)

    private const val IMAGE_SIZE = 0x100000
    private const val WORD_SIZE = 4
    private const val MASK = 0xFFFFFFFFL
    private const val CIC_6102_MAGIC = 0x5D588B65L

    fun calculate6102(image: ByteArray, seed: Int = 0x3F): Pair {
        require(image.size == IMAGE_SIZE) {
            "CIC 6102 checksum requires exactly 1 MiB; got ${image.size} bytes."
        }
        require(seed == 0x3F) { "Unsupported CIC 6102 checksum seed: 0x${seed.toString(16)}." }

        val initial = (seed.toLong() * CIC_6102_MAGIC + 1L) and MASK
        var a3 = initial
        var t2 = initial
        var t3 = initial
        var s0 = initial
        var a2 = initial
        var t4 = initial

        var offset = 0
        while (offset < image.size) {
            val word = ((image[offset].toLong() and 0xFFL) shl 24) or
                ((image[offset + 1].toLong() and 0xFFL) shl 16) or
                ((image[offset + 2].toLong() and 0xFFL) shl 8) or
                (image[offset + 3].toLong() and 0xFFL)

            val sum = (a3 + word) and MASK
            if (sum < a3) t2 = (t2 + 1L) and MASK
            a3 = sum

            val rotate = Integer.rotateLeft(word.toInt(), (word and 0x1FL).toInt()).toLong() and MASK
            t3 = (t3 xor word) and MASK
            s0 = (s0 + rotate) and MASK

            a2 = (a2 xor if (a2 < word) (a3 xor word) else rotate) and MASK
            t4 = (t4 + (word xor s0)) and MASK
            offset += WORD_SIZE
        }

        val crc1 = ((a3 xor t2) xor t3).toInt()
        val crc2 = ((s0 xor a2) xor t4).toInt()
        return Pair(crc1, crc2)
    }
}
