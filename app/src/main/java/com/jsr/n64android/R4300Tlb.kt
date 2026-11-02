package com.jsr.n64android

/**
 * R4300 32-entry TLB model with variable paired-page sizes.
 *
 * PageMask follows the MIPS III encoding. This is an instruction-level
 * translation model, not a cycle-accurate MMU or a complete cache model.
 */
class R4300Tlb(private val entryCount: Int = ENTRY_COUNT) {
    init {
        require(entryCount > 0) { "TLB must contain at least one entry." }
    }

    data class Entry(
        val virtualPagePairBase: Long,
        val asid: Int,
        val evenPage: Page,
        val oddPage: Page,
        val pageMask: Long = 0L
    )

    data class Page(
        val physicalFrameNumber: Int,
        val valid: Boolean,
        val dirty: Boolean,
        val global: Boolean = false
    ) {
        init {
            require(physicalFrameNumber >= 0) { "Physical frame number must be non-negative." }
            require(physicalFrameNumber <= MAX_PFN) { "Physical frame number exceeds the 20-bit R4300 PFN field." }
        }
    }

    data class Snapshot(val entries: List<Entry?>)

    data class Translation(
        val physicalAddress: Int,
        val entryIndex: Int,
        val pageSize: Long,
        val oddPage: Boolean,
        val asid: Int,
        val global: Boolean
    )

    enum class Access { READ, WRITE, FETCH }
    enum class FaultKind { MISS, INVALID, MODIFIED }

    class TlbFault(
        val kind: FaultKind,
        val virtualAddress: Long,
        val access: Access
    ) : RuntimeException("R4300 TLB $kind fault at 0x${virtualAddress.toString(16)}")

    private val entries = arrayOfNulls<Entry>(entryCount)

    val size: Int get() = entryCount

    fun clear() {
        entries.fill(null)
    }

    /** Invalidates non-global entries belonging to an address space; global mappings survive. */
    fun invalidateAsid(asid: Int): Int {
        require(asid in 0..0xFF) { "ASID must fit in 8 bits." }
        var removed = 0
        entries.indices.forEach { index ->
            val entry = entries[index]
            if (entry != null && entry.asid == asid && !isGlobal(entry)) {
                entries[index] = null
                removed++
            }
        }
        return removed
    }

    /** Invalidates every non-global mapping while preserving architecturally global entries. */
    fun invalidateNonGlobal(): Int {
        var removed = 0
        entries.indices.forEach { index ->
            val entry = entries[index]
            if (entry != null && !isGlobal(entry)) {
                entries[index] = null
                removed++
            }
        }
        return removed
    }

    /** Captures all indexed entries, including invalid/uninitialized slots. */
    fun snapshot(): Snapshot = Snapshot(entries.toList())

    /**
     * Restores a snapshot atomically. All entries are validated and normalized
     * before the live TLB is changed.
     */
    fun restore(snapshot: Snapshot) {
        require(snapshot.entries.size == entryCount) {
            "TLB snapshot has ${snapshot.entries.size} entries; expected $entryCount."
        }
        val normalized = snapshot.entries.map { it?.let(::normalizeEntry) }
        normalized.forEachIndexed { index, entry -> entries[index] = entry }
    }

    fun write(index: Int, entry: Entry) {
        require(index in entries.indices) { "TLB index $index is out of range." }
        entries[index] = normalizeEntry(entry)
    }

    fun read(index: Int): Entry {
        require(index in entries.indices) { "TLB index $index is out of range." }
        return entries[index] ?: throw IllegalStateException("TLB entry $index is not initialized.")
    }

    /** Returns the matching indexed entry, or null when VPN/ASID probing misses. */
    fun probe(virtualAddress: Long, asid: Int): Int? {
        require(asid in 0..0xFF) { "ASID must fit in 8 bits." }
        val address = virtualAddress and 0xFFFFFFFFL
        return entries.indices.firstOrNull { index ->
            val entry = entries[index] ?: return@firstOrNull false
            matches(entry, address, asid)
        }
    }

    fun translate(virtualAddress: Long, asid: Int, access: Access): Int =
        translateWithMetadata(virtualAddress, asid, access).physicalAddress

    /** Returns the physical address plus the TLB slot/page metadata used for it. */
    fun translateWithMetadata(virtualAddress: Long, asid: Int, access: Access): Translation {
        require(asid in 0..0xFF) { "ASID must fit in 8 bits." }
        val address = virtualAddress and 0xFFFFFFFFL
        val index = entries.indices.firstOrNull { slot ->
            val entry = entries[slot] ?: return@firstOrNull false
            matches(entry, address, asid)
        } ?: throw TlbFault(FaultKind.MISS, virtualAddress, access)
        val entry = entries[index]!!

        val pageSize = pageSize(entry.pageMask)
        val offset = address and (pageSize - 1L)
        val odd = (address and pageSize) != 0L
        val page = if (odd) entry.oddPage else entry.evenPage
        if (!page.valid) throw TlbFault(FaultKind.INVALID, virtualAddress, access)
        if (access == Access.WRITE && !page.dirty) {
            throw TlbFault(FaultKind.MODIFIED, virtualAddress, access)
        }

        // PFN bits below the selected page size are ignored by hardware.
        val physicalPageBase = (page.physicalFrameNumber.toLong() shl PAGE_SHIFT) and -pageSize
        return Translation(
            physicalAddress = (physicalPageBase or offset).toInt(),
            entryIndex = index,
            pageSize = pageSize,
            oddPage = odd,
            asid = entry.asid,
            global = isGlobal(entry)
        )
    }


    /** Stable indexed view of every TLB slot, including empty slots, for debugging refill behavior. */
    fun diagnosticSnapshot(): String = buildString {
        appendLine("R4300 TLB entries=$entryCount")
        entries.forEachIndexed { index, entry ->
            if (entry == null) {
                appendLine("TLB[$index]=EMPTY")
            } else {
                appendLine(
                    "TLB[$index] VPN2=0x${entry.virtualPagePairBase.toString(16).uppercase().padStart(8, '0')} " +
                        "ASID=${entry.asid} PageMask=0x${entry.pageMask.toString(16).uppercase().padStart(8, '0')} " +
                        "G=${isGlobal(entry)} EVEN(PFN=0x${entry.evenPage.physicalFrameNumber.toString(16).uppercase()},V=${entry.evenPage.valid},D=${entry.evenPage.dirty}) " +
                        "ODD(PFN=0x${entry.oddPage.physicalFrameNumber.toString(16).uppercase()},V=${entry.oddPage.valid},D=${entry.oddPage.dirty})"
                )
            }
        }
    }

    private fun normalizeEntry(entry: Entry): Entry {
        require(entry.asid in 0..0xFF) { "ASID must fit in 8 bits." }
        val pageMask = normalizePageMask(entry.pageMask)
        val pairMask = pairAddressMask(pageMask)
        val base = entry.virtualPagePairBase and 0xFFFFFFFFL
        require(base and pairMask.inv() and 0xFFFFFFFFL == 0L) {
            "TLB virtual pair base must be aligned for its PageMask."
        }
        return entry.copy(virtualPagePairBase = base and pairMask, pageMask = pageMask)
    }

    private fun matches(entry: Entry, address: Long, asid: Int): Boolean {
        val mask = pairAddressMask(entry.pageMask)
        return (entry.virtualPagePairBase and mask) == (address and mask) &&
            (entry.asid == asid || isGlobal(entry))
    }

    private fun isGlobal(entry: Entry): Boolean =
        entry.evenPage.global && entry.oddPage.global

    companion object {
        const val ENTRY_COUNT = 32
        const val PAGE_SHIFT = 12
        const val PAGE_SIZE = 1 shl PAGE_SHIFT
        const val PAGE_OFFSET_MASK = PAGE_SIZE.toLong() - 1L
        const val PAGE_PAIR_MASK = -0x2000L
        const val MAX_PFN = 0xFFFFF
        const val PAGEMASK_REGISTER_MASK = 0x01FFE000L

        private val SUPPORTED_PAGE_MASKS = setOf(
            0x00000000L,
            0x00006000L,
            0x0001E000L,
            0x0007E000L,
            0x001FE000L,
            0x007FE000L,
            0x01FFE000L
        )

        fun normalizePageMask(value: Long): Long {
            val mask = value and PAGEMASK_REGISTER_MASK
            require(mask in SUPPORTED_PAGE_MASKS) {
                "Unsupported or non-contiguous R4300 PageMask 0x${mask.toString(16)}."
            }
            return mask
        }

        fun pageSize(pageMask: Long): Long {
            val mask = normalizePageMask(pageMask)
            return ((mask or 0x1FFFL) + 1L) ushr 1
        }

        fun pairAddressMask(pageMask: Long): Long {
            val mask = normalizePageMask(pageMask)
            return (mask or 0x1FFFL).inv() and 0xFFFFFFFFL
        }
    }
}
