package com.jovemaprendz55byte.n64android

/**
 * Explicit, testable preflight for the clean-room N64 cartridge boot path.
 *
 * This does not replace the console's PIF firmware or execute IPL3 on behalf
 * of the CPU. It verifies that the normalized cartridge contains the IPL3 and
 * the 1 MiB payload IPL3 must copy/check before transferring control.
 */
internal class N64BootInitialization(
    private val memoryBus: N64MemoryBus,
    private val cpu: R4300Cpu
) {
    data class Preflight(
        val romBytes: Int,
        val ipl3Bytes: Int,
        val gameSegmentBytes: Int,
        val region: N64BootSequence.Region,
        val expectedCrc1: UInt,
        val expectedCrc2: UInt,
        val calculatedCrc1: UInt?,
        val calculatedCrc2: UInt?,
        val checksumMatches: Boolean
    )

    /**
     * Validate boot inputs and initialize the CPU's IPL3 handoff state.
     * A checksum mismatch is reported, never silently patched or bypassed.
     */
    fun prepareCic6105(rom: ByteArray): Preflight {
        require(rom.size >= GAME_SEGMENT_END) {
            "ROM precisa conter o IPL3 e 1 MiB de código/dados a partir de 0x1000"
        }
        val header = RomHeader.parse(rom)
        require(header?.format == RomHeader.Format.Z64_BIG_ENDIAN) {
            "ROM precisa estar normalizada para Z64 big-endian"
        }

        val image = N64BootSequence(memoryBus).loadIpl3(rom)
        require(image.region != N64BootSequence.Region.UNKNOWN) {
            "Código de região desconhecido; não é seguro escolher o seed PAL/NTSC"
        }

        val calculated = N64RomChecksum.calculateCic6105(rom)
        val expectedCrc1 = readWord(rom, 0x10)
        val expectedCrc2 = readWord(rom, 0x14)
        cpu.resetForCic6105Ipl3Diagnostic(
            entryPoint = image.entryPoint,
            pal = image.region == N64BootSequence.Region.PAL
        )

        return Preflight(
            romBytes = rom.size,
            ipl3Bytes = image.byteCount,
            gameSegmentBytes = GAME_SEGMENT_END - GAME_SEGMENT_START,
            region = image.region,
            expectedCrc1 = expectedCrc1,
            expectedCrc2 = expectedCrc2,
            calculatedCrc1 = calculated?.first,
            calculatedCrc2 = calculated?.second,
            checksumMatches = calculated != null &&
                calculated.first == expectedCrc1 && calculated.second == expectedCrc2
        )
    }

    companion object {
        private const val GAME_SEGMENT_START = 0x1000
        private const val GAME_SEGMENT_END = 0x101000

        private fun readWord(bytes: ByteArray, offset: Int): UInt =
            (((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)).toUInt()
    }
}
