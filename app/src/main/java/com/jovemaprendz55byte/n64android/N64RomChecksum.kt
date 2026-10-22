package com.jovemaprendz55byte.n64android

import java.util.zip.CRC32

/**
 * Cartridge-header checksum diagnostics for canonical Z64 (big-endian) ROMs.
 *
 * CIC is detected from the IPL3 CRC32, not inferred from the region/country byte.
 * The checksum routine never modifies the ROM or writes a replacement header.
 */
internal object N64RomChecksum {
    private const val IPL3_START = 0x40
    private const val IPL3_END = 0x1000
    private const val CHECKSUM_START = 0x1000
    private const val CHECKSUM_END = 0x101000

    enum class CicType(val label: String, private val seed: UInt) {
        CIC_6101("6101", 0xF8CA4DDCu),
        CIC_6102("6102/7101", 0xF8CA4DDCu),
        CIC_6103("6103/7103", 0xA3886759u),
        CIC_6105("6105/7105", 0xDF26F436u),
        CIC_6106("6106/7106", 0x1FEA617Au);

        internal fun checksumSeed(): UInt = seed
    }

    data class Result(
        val cic: CicType,
        val crc1: UInt,
        val crc2: UInt,
        val expectedCrc1: UInt,
        val expectedCrc2: UInt
    ) {
        val matchesHeader: Boolean
            get() = crc1 == expectedCrc1 && crc2 == expectedCrc2
    }

    /** Identifies common retail IPL3s using CRC32 over ROM bytes 0x40..0xFFF. */
    fun detectCic(rom: ByteArray): CicType? {
        if (rom.size < IPL3_END) return null
        val crc = CRC32()
        crc.update(rom, IPL3_START, IPL3_END - IPL3_START)
        return when (crc.value.toUInt()) {
            0x6170A4A1u -> CicType.CIC_6101
            0x90BB6CB5u -> CicType.CIC_6102
            0x0B050EE0u -> CicType.CIC_6103
            0x98BC2C86u -> CicType.CIC_6105
            0xACC8580Au -> CicType.CIC_6106
            else -> null
        }
    }

    /**
     * Calculates the header checksum using the detected IPL3. Returns null for
     * short ROMs or unrecognized IPL3s rather than guessing the CIC algorithm.
     */
    fun calculate(rom: ByteArray): Result? {
        val cic = detectCic(rom) ?: return null
        val calculated = calculate(rom, cic) ?: return null
        return Result(
            cic = cic,
            crc1 = calculated.first,
            crc2 = calculated.second,
            expectedCrc1 = readWord(rom, 0x10),
            expectedCrc2 = readWord(rom, 0x14)
        )
    }

    /** Calculates a selected common CIC algorithm; exposed for deterministic unit tests. */
    fun calculate(rom: ByteArray, cic: CicType): Pair<UInt, UInt>? {
        if (rom.size < CHECKSUM_END) return null

        var t1 = cic.checksumSeed()
        var t2 = cic.checksumSeed()
        var t3 = cic.checksumSeed()
        var t4 = cic.checksumSeed()
        var t5 = cic.checksumSeed()
        var t6 = cic.checksumSeed()

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

            t1 += if (cic == CicType.CIC_6105) {
                // CIC-6105 indexes the IPL3 table at 0x750..0x84C repeatedly.
                readWord(rom, 0x750 + (offset and 0xFF)) xor data
            } else {
                t5 xor data
            }
            offset += 4
        }

        return when (cic) {
            CicType.CIC_6103 -> Pair((t6 xor t4) + t3, (t5 xor t2) + t1)
            CicType.CIC_6106 -> Pair((t6 * t4) + t3, (t5 * t2) + t1)
            else -> Pair(t6 xor t4 xor t3, t5 xor t2 xor t1)
        }
    }

    /** Backward-compatible explicit CIC-6105 calculation used by existing tests. */
    fun calculateCic6105(rom: ByteArray): Pair<UInt, UInt>? =
        calculate(rom, CicType.CIC_6105)

    private fun readWord(bytes: ByteArray, offset: Int): UInt =
        (((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)).toUInt()
}
