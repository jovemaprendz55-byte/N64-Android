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
        val detectedCic: String?,
        val checksumMatches: Boolean,
        val rdramCapacityBytes: Int,
        val rdramModelReady: Boolean
    )

    /**
     * Validate boot inputs and initialize the CPU's IPL3 handoff state.
     * A checksum mismatch is reported, never silently patched or bypassed.
     */
    fun prepareCic6105(rom: ByteArray): Preflight {
        require(rom.size >= IPL3_ROM_END) {
            "ROM pequena demais para conter o IPL3 (mínimo 0x1000 bytes)"
        }
        val header = RomHeader.parse(rom)
        require(header?.format == RomHeader.Format.Z64_BIG_ENDIAN) {
            "ROM precisa estar normalizada para Z64 big-endian"
        }

        val image = N64BootSequence(memoryBus).loadIpl3(rom)
        require(image.region != N64BootSequence.Region.UNKNOWN) {
            "Código de região desconhecido; não é seguro escolher o seed PAL/NTSC"
        }

        val rdram = N64RdramInitialization(memoryBus).initializeModel()
        val checksum = N64RomChecksum.calculate(rom)
        val expectedCrc1 = readWord(rom, 0x10)
        val expectedCrc2 = readWord(rom, 0x14)
        val pal = image.region == N64BootSequence.Region.PAL
        when (checksum?.cic) {
            N64RomChecksum.CicType.CIC_6102 -> cpu.resetForCic6102Ipl3Diagnostic(image.entryPoint, pal)
            N64RomChecksum.CicType.CIC_6105 -> cpu.resetForCic6105Ipl3Diagnostic(image.entryPoint, pal)
            N64RomChecksum.CicType.CIC_6101,
            N64RomChecksum.CicType.CIC_6103,
            N64RomChecksum.CicType.CIC_6106 -> throw IllegalStateException(
                "CIC ${checksum.cic.label} detectado, mas o atalho de inicialização ainda não tem estado de registradores validado para esse CIC."
            )
            null -> {
                // Synthetic test ROMs may have no recognizable IPL3. Keep the
                // legacy diagnostic seed for those tests only; real recognized
                // retail ROMs must always select their matching CIC handoff.
                cpu.resetForCic6105Ipl3Diagnostic(image.entryPoint, pal)
            }
        }

        return Preflight(
            romBytes = rom.size,
            ipl3Bytes = image.byteCount,
            gameSegmentBytes = minOf(GAME_SEGMENT_END - GAME_SEGMENT_START, (rom.size - GAME_SEGMENT_START).coerceAtLeast(0)),
            region = image.region,
            expectedCrc1 = expectedCrc1,
            expectedCrc2 = expectedCrc2,
            calculatedCrc1 = checksum?.crc1,
            calculatedCrc2 = checksum?.crc2,
            detectedCic = checksum?.cic?.label,
            checksumMatches = checksum?.matchesHeader == true,
            rdramCapacityBytes = rdram.capacityBytes,
            rdramModelReady = rdram.memoryModelReady
        )
    }

    companion object {
        private const val IPL3_ROM_END = 0x1000
        private const val GAME_SEGMENT_START = 0x1000
        private const val GAME_SEGMENT_END = 0x101000

        private fun readWord(bytes: ByteArray, offset: Int): UInt =
            (((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)).toUInt()
    }
}
