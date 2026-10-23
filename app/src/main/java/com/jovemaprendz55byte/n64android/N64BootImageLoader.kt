package com.jovemaprendz55byte.n64android

/**
 * New isolated cartridge IPL3 loader.
 *
 * This class replaces the old N64BootSequence copy path when the handoff
 * coordinator is migrated. It validates the boot-region bounds, writes the
 * entire 4 KiB cartridge boot region to SP DMEM, and verifies the copied bytes
 * through the physical memory bus. It does not pretend to execute PIF ROM.
 */
internal class N64BootImageLoader(private val memoryBus: N64MemoryBus) {
    data class LoadedImage(
        val entryPoint: Long,
        val bytesCopied: Int,
        val payloadBytes: Int,
        val countryCode: Int,
        val region: Region,
        val verificationPassed: Boolean
    )

    enum class Region { NTSC, PAL, UNKNOWN }

    fun load(rom: ByteArray): LoadedImage {
        require(rom.size >= BOOT_REGION_SIZE) {
            "ROM too small for N64 boot region: need at least 0x1000 bytes"
        }

        val countryCode = rom[COUNTRY_CODE_OFFSET].toInt() and 0xFF
        val region = when (countryCode.toChar()) {
            'E', 'J' -> Region.NTSC
            'P', 'D', 'F', 'I', 'S', 'U', 'X', 'Y' -> Region.PAL
            else -> Region.UNKNOWN
        }

        for (offset in 0 until BOOT_REGION_SIZE) {
            memoryBus.writeByte(SP_DMEM_BASE + offset, rom[offset])
        }

        // Verify every byte, not only the IPL3 entry word. This catches
        // address aliasing, incomplete copies, and wrong SP-DMEM mapping.
        for (offset in 0 until BOOT_REGION_SIZE) {
            check(memoryBus.readByte(SP_DMEM_BASE + offset) == rom[offset]) {
                "IPL3 copy verification failed at ROM/SP-DMEM offset 0x%03X".format(offset)
            }
        }

        return LoadedImage(
            entryPoint = IPL3_ENTRY_POINT,
            bytesCopied = BOOT_REGION_SIZE,
            payloadBytes = BOOT_REGION_SIZE - IPL3_PAYLOAD_START,
            countryCode = countryCode,
            region = region,
            verificationPassed = true
        )
    }

    companion object {
        const val BOOT_REGION_SIZE = 0x1000
        const val IPL3_PAYLOAD_START = 0x40
        const val IPL3_ENTRY_POINT = -1_543_503_808L // 0xFFFFFFFFA4000040
        private const val COUNTRY_CODE_OFFSET = 0x3E
        private const val SP_DMEM_BASE = N64SpMemory.DMEM_BASE
    }
}
