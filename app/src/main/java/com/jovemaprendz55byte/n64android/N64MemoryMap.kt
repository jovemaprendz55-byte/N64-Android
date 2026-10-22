package com.jovemaprendz55byte.n64android

/**
 * Initial physical address-region classifier for the Nintendo 64.
 * Selected regions only; this does not implement device behavior or bus timing.
 */
internal object N64MemoryMap {
    enum class Region {
        RDRAM, RDRAM_REGISTERS, SP_DMEM, SP_IMEM, SP_REGISTERS,
        DPC_REGISTERS, DPS_REGISTERS, MI_REGISTERS, VI_REGISTERS,
        AI_REGISTERS, PI_REGISTERS, RI_REGISTERS, SI_REGISTERS,
        CARTRIDGE_DOMAIN, PIF
    }

    private data class Range(val start: Long, val end: Long, val region: Region)

    private val ranges = listOf(
        Range(0x0000_0000L, 0x007F_FFFFL, Region.RDRAM),
        Range(0x03F0_0000L, 0x03FF_FFFFL, Region.RDRAM_REGISTERS),
        Range(0x0400_0000L, 0x0400_0FFFL, Region.SP_DMEM),
        Range(0x0400_1000L, 0x0400_1FFFL, Region.SP_IMEM),
        // SP control registers occupy a 1 MiB physical window; SP_PC is at 0x04080000.
        Range(0x0404_0000L, 0x040F_FFFFL, Region.SP_REGISTERS),
        Range(0x0410_0000L, 0x0410_001FL, Region.DPC_REGISTERS),
        Range(0x0420_0000L, 0x0420_001FL, Region.DPS_REGISTERS),
        Range(0x0430_0000L, 0x0430_000FL, Region.MI_REGISTERS),
        Range(0x0440_0000L, 0x0440_0037L, Region.VI_REGISTERS),
        Range(0x0450_0000L, 0x0450_0017L, Region.AI_REGISTERS),
        Range(0x0460_0000L, 0x0460_0033L, Region.PI_REGISTERS),
        Range(0x0470_0000L, 0x0470_001FL, Region.RI_REGISTERS),
        Range(0x0480_0000L, 0x0480_001BL, Region.SI_REGISTERS),
        Range(0x1000_0000L, 0x1FBF_FFFFL, Region.CARTRIDGE_DOMAIN),
        Range(0x1FC0_0000L, 0x1FC0_07FFL, Region.PIF)
    )

    /** Classifies a 29-bit physical address, returning null for unlisted regions. */
    fun regionOf(physicalAddress: Long): Region? {
        if (physicalAddress !in 0L..0x1FFF_FFFFL) return null
        return ranges.firstOrNull { physicalAddress in it.start..it.end }?.region
    }
}
