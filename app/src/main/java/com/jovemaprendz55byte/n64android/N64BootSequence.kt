package com.jovemaprendz55byte.n64android

/**
 * Clean-room cartridge boot helpers.
 *
 * The IPL3 program is part of the loaded cartridge image; this class copies
 * those bytes to SP DMEM. It deliberately does not pretend to implement the
 * proprietary PIF boot ROM or infer a CIC from the country byte alone.
 */
internal class N64BootSequence(
    private val memoryBus: N64MemoryBus
) {
    enum class Region { NTSC, PAL, UNKNOWN }

    data class Ipl3Image(
        val entryPoint: Long,
        val byteCount: Int,
        val region: Region,
        val countryCode: Int
    )

    /**
     * Copy the cartridge IPL3 payload (ROM 0x40..0xFFF) to SP DMEM at 0x40.
     * The caller must separately perform a verified PIF/CIC handoff before
     * treating this as a real hardware boot.
     */
    fun loadIpl3(rom: ByteArray): Ipl3Image {
        require(rom.size >= IPL3_ROM_END) {
            "ROM is too small to contain the IPL3 image (need at least 0x1000 bytes)"
        }

        for (romOffset in IPL3_ROM_START until IPL3_ROM_END) {
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
            byteCount = IPL3_ROM_END - IPL3_ROM_START,
            region = region,
            countryCode = countryCode
        )
    }

    companion object {
        private const val IPL3_ROM_START = 0x40
        private const val IPL3_ROM_END = 0x1000
        private const val COUNTRY_CODE_OFFSET = 0x3E
        private const val SP_DMEM_BASE = 0x0400_0000L
        const val IPL3_ENTRY_POINT = -1_543_503_808L
    }
}
