package com.jovemaprendz55byte.n64android

/**
 * Isolated R4300 TLB model for the clean rebuild.
 *
 * Implements the standard 32-entry, paired 4 KiB-page format used by the
 * R4300: each entry maps an even and odd 4 KiB page (8 KiB virtual pair).
 * Segment bypass rules, exception-vector delivery, and variable PageMask
 * formats are intentionally handled by the CPU integration layer, not here.
 */
internal class R4300Tlb(
    private val entryCount: Int = ENTRY_COUNT
) {
    init {
        require(entryCount > 0) { "TLB must contain at least one entry" }
    }

    data class Entry(
        val entryHi: Long,
        val entryLo0: Long,
        val entryLo1: Long,
        val pageMask: Long = 0L
    )

    enum class Fault { MISS, INVALID, MODIFICATION }

    class TranslationException(
        val fault: Fault,
        val virtualAddress: Long,
        message: String
    ) : RuntimeException(message)

    data class Translation(
        val physicalAddress: Long,
        val cacheAttribute: Int,
        val dirty: Boolean,
        val global: Boolean,
        val entryIndex: Int
    )

    private val entries = arrayOfNulls<Entry>(entryCount)

    fun reset() {
        entries.fill(null)
    }

    fun write(index: Int, entry: Entry) {
        require(index in entries.indices) { "TLB index $index is out of range" }
        // This first stage deliberately supports only the architectural 4 KiB
        // page format. Reject unsupported PageMask values instead of silently
        // translating them incorrectly.
        require(entry.pageMask == 0L) { "Only 4 KiB TLB pages are supported" }
        entries[index] = entry.copy(
            entryHi = entry.entryHi and ENTRYHI_MASK,
            entryLo0 = entry.entryLo0 and ENTRYLO_MASK,
            entryLo1 = entry.entryLo1 and ENTRYLO_MASK
        )
    }

    fun read(index: Int): Entry {
        require(index in entries.indices) { "TLB index $index is out of range" }
        return entries[index] ?: Entry(0L, 0L, 0L)
    }

    fun probe(entryHi: Long): Int {
        val vpn2 = (entryHi and ENTRYHI_VPN2_MASK)
        val asid = entryHi and ASID_MASK
        return entries.indexOfFirst { entry ->
            entry != null &&
                (entry.entryHi and ENTRYHI_VPN2_MASK) == vpn2 &&
                (isGlobal(entry) || (entry.entryHi and ASID_MASK) == asid)
        }
    }

    fun translate(virtualAddress: Long, asid: Int, store: Boolean = false): Translation {
        require(asid in 0..0xFF) { "ASID must fit in 8 bits" }
        val va = virtualAddress and 0xFFFF_FFFFL
        val vpn2 = va and ENTRYHI_VPN2_MASK
        val oddPage = (va and PAGE_SIZE.toLong()) != 0L
        val entryIndex = entries.indexOfFirst { entry ->
            entry != null &&
                (entry.entryHi and ENTRYHI_VPN2_MASK) == vpn2 &&
                (isGlobal(entry) || (entry.entryHi and ASID_MASK) == asid.toLong())
        }
        if (entryIndex < 0) {
            throw TranslationException(Fault.MISS, virtualAddress,
                "TLB miss for virtual address 0x${virtualAddress.toULong().toString(16)}")
        }

        val entry = entries[entryIndex]!!
        val lo = if (oddPage) entry.entryLo1 else entry.entryLo0
        val valid = (lo and ENTRYLO_VALID) != 0L
        if (!valid) {
            throw TranslationException(Fault.INVALID, virtualAddress,
                "TLB invalid page for virtual address 0x${virtualAddress.toULong().toString(16)}")
        }
        val dirty = (lo and ENTRYLO_DIRTY) != 0L
        if (store && !dirty) {
            throw TranslationException(Fault.MODIFICATION, virtualAddress,
                "TLB write to clean page at 0x${virtualAddress.toULong().toString(16)}")
        }

        val pfn = (lo and ENTRYLO_PFN_MASK) ushr ENTRYLO_PFN_SHIFT
        val physicalAddress = (pfn shl PAGE_SHIFT) or (va and PAGE_OFFSET_MASK)
        return Translation(
            physicalAddress = physicalAddress,
            cacheAttribute = ((lo and ENTRYLO_CACHE_MASK) ushr ENTRYLO_CACHE_SHIFT).toInt(),
            dirty = dirty,
            global = isGlobal(entry),
            entryIndex = entryIndex
        )
    }

    private fun isGlobal(entry: Entry): Boolean =
        (entry.entryLo0 and ENTRYLO_GLOBAL) != 0L &&
            (entry.entryLo1 and ENTRYLO_GLOBAL) != 0L

    companion object {
        const val ENTRY_COUNT = 32
        const val PAGE_SHIFT = 12
        const val PAGE_SIZE = 1 shl PAGE_SHIFT
        private const val PAGE_OFFSET_MASK = 0xFFFL
        private const val ENTRYHI_VPN2_MASK = 0xFFFF_E000L
        private const val ENTRYHI_MASK = 0xFFFF_FFFF_FFFF_E0FFUL.toLong()
        private const val ASID_MASK = 0xFFL
        private const val ENTRYLO_MASK = 0x3FFF_FFFFL
        private const val ENTRYLO_PFN_SHIFT = 6
        private const val ENTRYLO_PFN_MASK = 0x03FF_FFC0L
        private const val ENTRYLO_CACHE_SHIFT = 3
        private const val ENTRYLO_CACHE_MASK = 0x38L
        private const val ENTRYLO_DIRTY = 0x4L
        private const val ENTRYLO_VALID = 0x2L
        private const val ENTRYLO_GLOBAL = 0x1L

        fun entryLo(
            pfn: Int,
            cacheAttribute: Int = 3,
            dirty: Boolean = true,
            valid: Boolean = true,
            global: Boolean = false
        ): Long {
            require(pfn in 0..0xFFFFF) { "PFN must fit in 20 bits" }
            require(cacheAttribute in 0..7) { "Cache attribute must fit in 3 bits" }
            return (pfn.toLong() shl ENTRYLO_PFN_SHIFT) or
                (cacheAttribute.toLong() shl ENTRYLO_CACHE_SHIFT) or
                (if (dirty) ENTRYLO_DIRTY else 0L) or
                (if (valid) ENTRYLO_VALID else 0L) or
                (if (global) ENTRYLO_GLOBAL else 0L)
        }

        fun entryHi(virtualAddress: Long, asid: Int): Long {
            require(asid in 0..0xFF) { "ASID must fit in 8 bits" }
            return (virtualAddress and ENTRYHI_VPN2_MASK) or asid.toLong()
        }
    }
}
