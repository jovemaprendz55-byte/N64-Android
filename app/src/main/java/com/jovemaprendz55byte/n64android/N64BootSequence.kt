package com.jovemaprendz55byte.n64android

/**
 * Clean-room cartridge boot helpers.
 *
 * Copies the first 4 KiB boot region from the normalized cartridge image to
 * SP DMEM. The IPL3 payload itself starts at ROM offset 0x40; copying the
 * header bytes at 0x00..0x3F is a separate fact from the IPL3 entry point.
 * This does not execute or emulate the proprietary PIF boot ROM.
 */
internal class N64BootSequence(
    private val memoryBus: N64MemoryBus
) {
    enum class Region { NTSC, PAL, UNKNOWN }

    data class Ipl3Image(
        val entryPoint: Long,
        /** Number of bytes copied to DMEM (the complete 4 KiB boot region). */
        val byteCount: Int,
        val region: Region,
        val countryCode: Int,
        /** Number of cartridge bytes belonging to the IPL3 payload, excluding the 0x40-byte header. */
        val ipl3PayloadByteCount: Int = BOOT_REGION_END - IPL3_PAYLOAD_START
    )

    /**
     * Copy ROM 0x000..0xFFF to SP DMEM 0x04000000..0x04000FFF.
     * The IPL3 code begins at DMEM+0x40 and the CPU entry remains 0xA4000040.
     */
    fun loadIpl3(rom: ByteArray): Ipl3Image {
        require(rom.size >= BOOT_REGION_END) {
            "ROM is too small to contain the 4 KiB boot region (need at least 0x1000 bytes)"
        }

        for (romOffset in 0 until BOOT_REGION_END) {
            memoryBus.writeByte(SP_DMEM_BASE + romOffset, rom[romOffset])
        }

        val countryCode = rom[COUNTRY_CODE_OFFSET].toInt() and 0xFF
        val region = when (countryCode.toChar()) {
            'E', 'J' -> Region.NTSC
            'P', 'D', 'F', 'I', 'S', 'U', 'X', 'Y' -> Region.PAL
            else -> Region.UNKNOWN
        }

        return Ipl3Image(
            entryPoint = IPL3_ENTRY_POINT,
            byteCount = BOOT_REGION_END,
            region = region,
            countryCode = countryCode,
            ipl3PayloadByteCount = BOOT_REGION_END - IPL3_PAYLOAD_START
        )
    }

    companion object {
        private const val IPL3_PAYLOAD_START = 0x40
        private const val BOOT_REGION_END = 0x1000
        private const val COUNTRY_CODE_OFFSET = 0x3E
        private const val SP_DMEM_BASE = N64SpMemory.DMEM_BASE
        const val IPL3_ENTRY_POINT = -1_543_503_808L // 0xFFFFFFFFA4000040
    }
}
