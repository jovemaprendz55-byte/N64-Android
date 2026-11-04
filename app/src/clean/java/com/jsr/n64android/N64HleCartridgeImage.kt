package com.jsr.n64android

/**
 * Immutable, normalized view of an N64 cartridge image.
 *
 * This class only recognizes the three common byte orders. It does not execute
 * IPL3, emulate PIF, infer CIC state, or claim that a game has booted.
 */
class N64HleCartridgeImage private constructor(
    private val canonicalRom: ByteArray,
    val byteOrder: ByteOrder
) {
    enum class ByteOrder { BIG_ENDIAN, BYTE_SWAPPED_16, LITTLE_ENDIAN_32 }
    val sizeBytes: Int get() = canonicalRom.size
    val entryPoint: Long
    val crc1: Long
    val crc2: Long
    val title: String
    val cartridgeId: String
    val countryCode: Int

    init {
        entryPoint = readU32(0x08)
        crc1 = readU32(0x10)
        crc2 = readU32(0x14)
        title = readAscii(0x20, 20).trim()
        cartridgeId = readAscii(0x3C, 2)
        countryCode = canonicalRom[0x3E].toInt() and 0xFF
    }

    /** Returns a defensive copy in canonical big-endian N64 byte order. */
    fun copyCanonicalBytes(): ByteArray = canonicalRom.copyOf()

    /** Reads a bounded, defensive copy of canonical ROM data. */
    fun readBytes(offset: Int, length: Int): ByteArray {
        require(offset >= 0) { "ROM offset must be non-negative" }
        require(length >= 0) { "ROM length must be non-negative" }
        require(offset <= canonicalRom.size && length <= canonicalRom.size - offset) {
            "ROM range is outside the cartridge image"
        }
        return canonicalRom.copyOfRange(offset, offset + length)
    }

    private fun readU32(offset: Int): Long =
        ((canonicalRom[offset].toLong() and 0xFF) shl 24) or
            ((canonicalRom[offset + 1].toLong() and 0xFF) shl 16) or
            ((canonicalRom[offset + 2].toLong() and 0xFF) shl 8) or
            (canonicalRom[offset + 3].toLong() and 0xFF)

    private fun readAscii(offset: Int, length: Int): String =
        String(canonicalRom, offset, length, Charsets.US_ASCII)
            .map { if (it.code in 0x20..0x7E) it else ' ' }
            .joinToString("")

    companion object {
        private const val HEADER_SIZE = 0x40
        private val BIG_ENDIAN_MAGIC = byteArrayOf(0x80.toByte(), 0x37, 0x12, 0x40)
        private val BYTE_SWAPPED_MAGIC = byteArrayOf(0x37, 0x80.toByte(), 0x40, 0x12)
        private val LITTLE_ENDIAN_MAGIC = byteArrayOf(0x40, 0x12, 0x37, 0x80.toByte())

        fun parse(input: ByteArray): N64HleCartridgeImage {
            require(input.size >= HEADER_SIZE) { "N64 ROM is shorter than its 64-byte header" }
            require(input.size % 4 == 0) { "N64 ROM size must be aligned to a 32-bit word" }
            val order = when {
                hasMagic(input, BIG_ENDIAN_MAGIC) -> ByteOrder.BIG_ENDIAN
                hasMagic(input, BYTE_SWAPPED_MAGIC) -> ByteOrder.BYTE_SWAPPED_16
                hasMagic(input, LITTLE_ENDIAN_MAGIC) -> ByteOrder.LITTLE_ENDIAN_32
                else -> throw IllegalArgumentException("Unrecognized N64 cartridge byte-order magic")
            }
            val canonical = when (order) {
                ByteOrder.BIG_ENDIAN -> input.copyOf()
                ByteOrder.BYTE_SWAPPED_16 -> swapAdjacentBytes(input)
                ByteOrder.LITTLE_ENDIAN_32 -> reverseWords(input)
            }
            require(hasMagic(canonical, BIG_ENDIAN_MAGIC)) {
                "N64 ROM byte-order normalization failed"
            }
            return N64HleCartridgeImage(canonical, order)
        }

        private fun hasMagic(bytes: ByteArray, magic: ByteArray): Boolean =
            magic.indices.all { bytes[it] == magic[it] }

        private fun swapAdjacentBytes(input: ByteArray): ByteArray {
            val output = input.copyOf()
            var i = 0
            while (i + 1 < output.size) {
                val value = output[i]
                output[i] = output[i + 1]
                output[i + 1] = value
                i += 2
            }
            return output
        }

        private fun reverseWords(input: ByteArray): ByteArray {
            val output = input.copyOf()
            var i = 0
            while (i + 3 < output.size) {
                output[i] = input[i + 3]
                output[i + 1] = input[i + 2]
                output[i + 2] = input[i + 1]
                output[i + 3] = input[i]
                i += 4
            }
            return output
        }
    }
}