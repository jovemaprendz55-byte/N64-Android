package com.jovemaprendz55byte.n64android

/**
 * Address helpers for the Nintendo 64's 32-bit MIPS virtual address layout.
 *
 * KSEG0 and KSEG1 are direct-mapped segments. KUSEG/KSEG2/KSEG3 require TLB
 * handling and deliberately return null here. R4300Cpu uses this helper when
 * resolving instruction fetches and data accesses through the simplified bus.
 */
internal object N64Addressing {
    private const val ADDRESS_MASK = 0xFFFF_FFFFL
    private const val KSEG0_START = 0x8000_0000L
    private const val KSEG1_END = 0xBFFF_FFFFL
    private const val PHYSICAL_MASK = 0x1FFF_FFFFL

    /**
     * Translate a KSEG0/KSEG1 address to its 29-bit physical address.
     *
     * Accepts either a zero-extended 32-bit address (0x80000000) or the
     * sign-extended 64-bit form used by the R4300 (0xFFFFFFFF80000000).
     * Returns null for addresses that need TLB translation or are not
     * representable as a 32-bit N64 virtual address.
     */
    fun translateDirectMappedAddress(address: Long): Long? {
        val address32 = when {
            address >= 0L && address <= ADDRESS_MASK -> address
            address < 0L && (address shr 32) == -1L -> address and ADDRESS_MASK
            else -> return null
        }

        if (address32 < KSEG0_START || address32 > KSEG1_END) return null
        return address32 and PHYSICAL_MASK
    }
}
