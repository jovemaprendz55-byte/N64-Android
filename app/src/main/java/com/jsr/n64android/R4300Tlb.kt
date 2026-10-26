package com.jsr.n64android

/**
 * Initial R4300 TLB model for 4 KiB pages.
 *
 * This deliberately models the 32-entry paired-page lookup separately from the CPU.
 * CPU integration, variable PageMask values, wired/random replacement and full COP0
 * exception delivery are not implemented yet.
 */
class R4300Tlb(private val entryCount: Int = ENTRY_COUNT) {
    init {
        require(entryCount > 0) { "TLB must contain at least one entry." }
    }

    data class Entry(
        val virtualPagePairBase: Long,
        val asid: Int,
        val evenPage: Page,
        val oddPage: Page
    )

    data class Page(
        val physicalFrameNumber: Int,
        val valid: Boolean,
        val dirty: Boolean,
        val global: Boolean = false
    ) {
        init {
            require(physicalFrameNumber >= 0) { "Physical frame number must be non-negative." }
            require(physicalFrameNumber <= MAX_PFN) { "Physical frame number exceeds the 24-bit R4300 PFN field." }
        }
    }

    enum class Access { READ, WRITE, FETCH }
    enum class FaultKind { MISS, INVALID, MODIFIED }

    class TlbFault(
        val kind: FaultKind,
        val virtualAddress: Long
    ) : RuntimeException("R4300 TLB $kind fault at 0x${virtualAddress.toString(16)}")

    private val entries = arrayOfNulls<Entry>(entryCount)

    fun clear() {
        entries.fill(null)
    }

    fun write(index: Int, entry: Entry) {
        require(index in entries.indices) { "TLB index $index is out of range." }
        require(entry.virtualPagePairBase and PAGE_PAIR_MASK == entry.virtualPagePairBase) {
            "TLB virtual pair base must be aligned to 8 KiB."
        }
        require(entry.asid in 0..0xFF) { "ASID must fit in 8 bits." }
        entries[index] = entry
    }

    fun read(index: Int): Entry {
        require(index in entries.indices) { "TLB index $index is out of range." }
        return entries[index] ?: throw IllegalStateException("TLB entry $index is not initialized.")
    }

    fun translate(virtualAddress: Long, asid: Int, access: Access): Int {
        require(asid in 0..0xFF) { "ASID must fit in 8 bits." }
        val address = virtualAddress and 0xFFFFFFFFL
        val pairBase = address and PAGE_PAIR_MASK
        val offset = address and PAGE_OFFSET_MASK
        val odd = (address and PAGE_SIZE.toLong()) != 0L

        val entry = entries.firstOrNull {
            it != null && it.virtualPagePairBase == pairBase && (it.asid == asid || it.evenPage.global && it.oddPage.global)
        } ?: throw TlbFault(FaultKind.MISS, virtualAddress)

        val page = if (odd) entry.oddPage else entry.evenPage
        if (!page.valid) throw TlbFault(FaultKind.INVALID, virtualAddress)
        if (access == Access.WRITE && !page.dirty) throw TlbFault(FaultKind.MODIFIED, virtualAddress)

        val physical = (page.physicalFrameNumber.toLong() shl PAGE_SHIFT) or offset
        return physical.toInt()
    }

    companion object {
        const val ENTRY_COUNT = 32
        const val PAGE_SHIFT = 12
        const val PAGE_SIZE = 1 shl PAGE_SHIFT
        const val PAGE_OFFSET_MASK = PAGE_SIZE.toLong() - 1L
        const val PAGE_PAIR_MASK = -0x2000L
        const val MAX_PFN = 0xFFFFFF
    }
}
