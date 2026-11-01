package com.jsr.n64android

/**
 * Physical address space used by the CPU. Addresses passed to this interface are
 * physical addresses after the CPU's virtual-segment translation.
 */
interface N64AddressSpace {
    fun read8(address: Int): Int
    fun read16(address: Int): Int
    fun read32(address: Int): Int
    fun write8(address: Int, value: Int)
    fun write16(address: Int, value: Int)
    fun write32(address: Int, value: Int)

    /** Atomically reads and reserves an aligned 32-bit or 64-bit physical location. */
    fun loadLinked(address: Int, width: Int): Long =
        throw UnsupportedOperationException("Load-linked is not supported by this address space.")

    /** Atomically stores only when the matching load-linked reservation remains valid. */
    fun storeConditional(address: Int, width: Int, value: Long): Boolean =
        throw UnsupportedOperationException("Store-conditional is not supported by this address space.")
}

/**
 * Initial RAM storage for the custom emulator.
 * This is not yet the complete N64 physical memory map or MMIO bus.
 */
class N64Memory(ramSize: Int = DEFAULT_RAM_SIZE) : N64AddressSpace {
    private val ram: ByteArray
    private var reservationGranule: Int? = null
    private var reservationAddress: Int? = null
    private var reservationWidth: Int = 0

    init {
        require(ramSize > 0) { "RAM size must be positive." }
        ram = ByteArray(ramSize)
    }

    val size: Int get() = ram.size

    override fun read8(address: Int): Int {
        checkRange(address, 1)
        return ram[address].toInt() and 0xFF
    }

    override fun read16(address: Int): Int {
        checkRange(address, 2)
        return (read8(address) shl 8) or read8(address + 1)
    }

    override fun read32(address: Int): Int {
        checkRange(address, 4)
        return (read8(address) shl 24) or (read8(address + 1) shl 16) or
            (read8(address + 2) shl 8) or read8(address + 3)
    }

    @Synchronized
    override fun write8(address: Int, value: Int) {
        checkRange(address, 1)
        invalidateReservationForWrite(address, 1)
        ram[address] = value.toByte()
    }

    @Synchronized
    override fun write16(address: Int, value: Int) {
        checkRange(address, 2)
        invalidateReservationForWrite(address, 2)
        ram[address] = (value ushr 8).toByte()
        ram[address + 1] = value.toByte()
    }

    @Synchronized
    override fun write32(address: Int, value: Int) {
        checkRange(address, 4)
        invalidateReservationForWrite(address, 4)
        write32Raw(address, value)
    }

    @Synchronized
    override fun loadLinked(address: Int, width: Int): Long {
        require(width == 4 || width == 8) { "Load-linked width must be 4 or 8 bytes." }
        require(address % width == 0) { "Load-linked address must be naturally aligned." }
        checkRange(address, width)
        reservationAddress = address
        reservationWidth = width
        reservationGranule = address ushr RESERVATION_GRANULE_SHIFT
        return if (width == 4) read32(address).toLong() else {
            (read32(address).toLong() shl 32) or (read32(address + 4).toLong() and 0xFFFFFFFFL)
        }
    }

    @Synchronized
    override fun storeConditional(address: Int, width: Int, value: Long): Boolean {
        require(width == 4 || width == 8) { "Store-conditional width must be 4 or 8 bytes." }
        require(address % width == 0) { "Store-conditional address must be naturally aligned." }
        checkRange(address, width)
        val succeeds = reservationAddress == address &&
            reservationWidth == width &&
            reservationGranule == (address ushr RESERVATION_GRANULE_SHIFT)
        clearReservation()
        if (!succeeds) return false
        invalidateReservationForWrite(address, width)
        if (width == 4) {
            write32Raw(address, value.toInt())
        } else {
            write32Raw(address, (value ushr 32).toInt())
            write32Raw(address + 4, value.toInt())
        }
        return true
    }

    @Synchronized
    fun clear() {
        ram.fill(0)
        clearReservation()
    }

    private fun write32Raw(address: Int, value: Int) {
        ram[address] = (value ushr 24).toByte()
        ram[address + 1] = (value ushr 16).toByte()
        ram[address + 2] = (value ushr 8).toByte()
        ram[address + 3] = value.toByte()
    }

    private fun invalidateReservationForWrite(address: Int, width: Int) {
        val granule = reservationGranule ?: return
        val first = address ushr RESERVATION_GRANULE_SHIFT
        val last = (address + width - 1) ushr RESERVATION_GRANULE_SHIFT
        if (granule in first..last) clearReservation()
    }

    private fun clearReservation() {
        reservationAddress = null
        reservationWidth = 0
        reservationGranule = null
    }

    private fun checkRange(address: Int, width: Int) {
        require(address >= 0 && width > 0 && address <= ram.size - width) {
            "RAM access outside allocated range: address=0x${address.toUInt().toString(16)}, width=$width, size=${ram.size}"
        }
    }

    companion object {
        const val DEFAULT_RAM_SIZE = 8 * 1024 * 1024
        // Conservative implementation-defined reservation block, shared by CPU and DMA writes.
        private const val RESERVATION_GRANULE_SHIFT = 4
    }
}
