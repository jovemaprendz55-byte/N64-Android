package com.jsr.n64android

/**
 * Strict guest memory map for the clean HLE runtime.
 *
 * KSEG0/KSEG1 addresses map to RDRAM, RSP DMEM and RSP IMEM. MMIO, TLB-mapped
 * segments and physical-address shortcuts remain outside this API. All
 * multi-byte values use the N64's big-endian byte order.
 */
class N64HleGuestAddressSpace(
    rdramSize: Int = DEFAULT_RDRAM_SIZE
) {
    private val rdram: ByteArray
    private val spDmem = ByteArray(SP_MEMORY_SIZE)
    private val spImem = ByteArray(SP_MEMORY_SIZE)
    private var spMemAddress = 0
    private var spDramAddress = 0
    private var spStatus = SP_STATUS_HALT
    private var spPc = 0
    private var spSemaphore = 0

    val sizeBytes: Int get() = rdram.size

    init {
        require(rdramSize > 0) { "RDRAM size must be positive" }
        require(rdramSize <= MAX_PHYSICAL_RDRAM) { "RDRAM size exceeds the supported physical window" }
        rdram = ByteArray(rdramSize)
    }

    fun read8(address: Long): Int {
        val (bytes, offset) = translate(address, 1)
        return bytes[offset].toInt() and 0xFF
    }

    fun write8(address: Long, value: Int) {
        require(value in 0..0xFF) { "Byte value is outside 0..255" }
        val (bytes, offset) = translate(address, 1)
        bytes[offset] = value.toByte()
    }

    fun read16(address: Long): Int {
        val (bytes, p) = translateAligned(address, 2, 2)
        return ((bytes[p].toInt() and 0xFF) shl 8) or
            (bytes[p + 1].toInt() and 0xFF)
    }

    fun write16(address: Long, value: Int) {
        require(value in 0..0xFFFF) { "Halfword value is outside 0..65535" }
        val (bytes, p) = translateAligned(address, 2, 2)
        bytes[p] = (value ushr 8).toByte()
        bytes[p + 1] = value.toByte()
    }

    fun read32(address: Long): Long {
        requireAligned(address, 4)
        val physical = physicalAddress(address)
        if (isSpRegister(physical)) return readSpRegister(physical).toLong() and 0xFFFF_FFFFL
        val (bytes, p) = translate(address, 4)
        return ((bytes[p].toLong() and 0xFF) shl 24) or
            ((bytes[p + 1].toLong() and 0xFF) shl 16) or
            ((bytes[p + 2].toLong() and 0xFF) shl 8) or
            (bytes[p + 3].toLong() and 0xFF)
    }

    fun write32(address: Long, value: Long) {
        require(value in 0L..0xFFFF_FFFFL) { "Word value is outside 0..0xFFFFFFFF" }
        requireAligned(address, 4)
        val physical = physicalAddress(address)
        if (isSpRegister(physical)) {
            writeSpRegister(physical, value.toInt())
            return
        }
        val (bytes, p) = translate(address, 4)
        bytes[p] = (value ushr 24).toByte()
        bytes[p + 1] = (value ushr 16).toByte()
        bytes[p + 2] = (value ushr 8).toByte()
        bytes[p + 3] = value.toByte()
    }

    fun read64(address: Long): Long {
        val (bytes, p) = translateAligned(address, 8, 8)
        var value = 0L
        for (i in 0 until 8) value = (value shl 8) or (bytes[p + i].toLong() and 0xFF)
        return value
    }

    fun write64(address: Long, value: Long) {
        val (bytes, p) = translateAligned(address, 8, 8)
        for (i in 0 until 8) {
            bytes[p + i] = (value ushr (56 - i * 8)).toByte()
        }
    }

    fun readBytes(address: Long, length: Int): ByteArray {
        require(length >= 0) { "Length must be non-negative" }
        val (bytes, offset) = translate(address, length)
        return bytes.copyOfRange(offset, offset + length)
    }

    fun writeBytes(address: Long, bytes: ByteArray) {
        val (target, offset) = translate(address, bytes.size)
        bytes.copyInto(target, offset)
    }

    private fun requireAligned(address: Long, alignment: Int) {
        if ((address and (alignment - 1).toLong()) != 0L) {
            throw N64HleGuestAddressAlignmentFault(address, alignment)
        }
    }

    private fun physicalAddress(address: Long): Int {
        val upper = address ushr 32
        if (upper != 0L && upper != 0xFFFF_FFFFL) {
            throw N64HleGuestAddressMappingFault(
                address,
                "address is not a 32-bit zero-extended or sign-extended value"
            )
        }
        val guest = address and 0xFFFF_FFFFL
        val segment = guest ushr 29
        if (segment != 4L && segment != 5L) {
            throw N64HleGuestAddressMappingFault(
                address,
                "only KSEG0 and KSEG1 are mapped by this HLE memory space"
            )
        }
        return (guest and PHYSICAL_MASK).toInt()
    }

    private fun isSpRegister(physical: Int): Boolean =
        (physical in SP_REG_BASE..(SP_REG_BASE + 0x1C) && (physical and 3) == 0) ||
            physical == SP_PC_REG

    private fun readSpRegister(address: Int): Int = when (address) {
        SP_MEM_ADDR_REG -> spMemAddress
        SP_DRAM_ADDR_REG -> spDramAddress
        SP_RD_LEN_REG, SP_WR_LEN_REG -> 0
        SP_STATUS_REG -> spStatus
        SP_DMA_FULL_REG, SP_DMA_BUSY_REG -> 0
        SP_SEMAPHORE_REG -> spSemaphore
        SP_PC_REG -> spPc
        else -> throw IllegalArgumentException("Unsupported SP register 0x${address.toString(16)}")
    }

    private fun writeSpRegister(address: Int, value: Int) {
        when (address) {
            SP_MEM_ADDR_REG -> spMemAddress = value and SP_MEM_ADDR_MASK
            SP_DRAM_ADDR_REG -> spDramAddress = value and SP_DRAM_ADDR_MASK
            SP_RD_LEN_REG -> transferSpDma(value, toSp = true)
            SP_WR_LEN_REG -> transferSpDma(value, toSp = false)
            SP_STATUS_REG -> writeSpStatusCommand(value)
            SP_DMA_FULL_REG, SP_DMA_BUSY_REG -> Unit
            SP_SEMAPHORE_REG -> spSemaphore = 1
            SP_PC_REG -> spPc = value and SP_PC_MASK
            else -> throw IllegalArgumentException("Unsupported SP register 0x${address.toString(16)}")
        }
    }

    private fun writeSpStatusCommand(command: Int) {
        if ((command and SP_STATUS_CMD_CLR_HALT) != 0) spStatus = spStatus and SP_STATUS_HALT.inv()
        if ((command and SP_STATUS_CMD_SET_HALT) != 0) spStatus = spStatus or SP_STATUS_HALT
        if ((command and SP_STATUS_CMD_CLR_BROKE) != 0) spStatus = spStatus and SP_STATUS_BROKE.inv()
        if ((command and SP_STATUS_CMD_CLR_INTR) != 0) spStatus = spStatus and SP_STATUS_INTR.inv()
        if ((command and SP_STATUS_CMD_SET_INTR) != 0) spStatus = spStatus or SP_STATUS_INTR
        if ((command and SP_STATUS_CMD_CLR_SSTEP) != 0) spStatus = spStatus and SP_STATUS_SSTEP.inv()
        if ((command and SP_STATUS_CMD_SET_SSTEP) != 0) spStatus = spStatus or SP_STATUS_SSTEP
        if ((command and SP_STATUS_CMD_CLR_INTR_BREAK) != 0) spStatus = spStatus and SP_STATUS_INTR_BREAK.inv()
        if ((command and SP_STATUS_CMD_SET_INTR_BREAK) != 0) spStatus = spStatus or SP_STATUS_INTR_BREAK
    }

    /** Synchronous, simplified SP DMA with length/count/skip fields and 4 KiB wrap. */
    private fun transferSpDma(command: Int, toSp: Boolean) {
        val blockLength = (command and 0xFFF) + 1
        val blockCount = ((command ushr 12) and 0xFF) + 1
        val dramSkip = (command ushr 20) and 0xFFF
        val memorySelect = spMemAddress and 0x1000
        var memoryOffset = spMemAddress and 0xFFF
        var dramAddress = spDramAddress and SP_DRAM_ADDR_MASK
        var checkAddress = dramAddress.toLong()
        repeat(blockCount) {
            require(checkAddress >= 0L && checkAddress + blockLength <= rdram.size.toLong()) {
                "SP DMA RDRAM range is invalid: address=0x${checkAddress.toString(16)}, length=$blockLength"
            }
            checkAddress += blockLength + dramSkip
        }
        repeat(blockCount) {
            val selected = if (memorySelect == 0) spDmem else spImem
            repeat(blockLength) { index ->
                val spOffset = memoryOffset and 0xFFF
                if (toSp) selected[spOffset] = rdram[dramAddress + index]
                else rdram[dramAddress + index] = selected[spOffset]
                memoryOffset = (memoryOffset + 1) and 0xFFF
            }
            dramAddress += blockLength + dramSkip
        }
        spMemAddress = memorySelect or memoryOffset
        spDramAddress = dramAddress and SP_DRAM_ADDR_MASK
    }
    private fun translateAligned(address: Long, length: Int, alignment: Int): Pair<ByteArray, Int> {
        requireAligned(address, alignment)
        return translate(address, length)
    }

    private fun translate(address: Long, length: Int): Pair<ByteArray, Int> {
        require(length >= 0) { "Length must be non-negative" }
        val physical = physicalAddress(address)
        if (physical >= 0 && physical <= rdram.size && length <= rdram.size - physical) {
            return rdram to physical
        }
        if (physical >= SP_DMEM_PHYSICAL && physical <= SP_DMEM_PHYSICAL + SP_MEMORY_SIZE &&
            length <= SP_MEMORY_SIZE - (physical - SP_DMEM_PHYSICAL)) {
            return spDmem to (physical - SP_DMEM_PHYSICAL)
        }
        if (physical >= SP_IMEM_PHYSICAL && physical <= SP_IMEM_PHYSICAL + SP_MEMORY_SIZE &&
            length <= SP_MEMORY_SIZE - (physical - SP_IMEM_PHYSICAL)) {
            return spImem to (physical - SP_IMEM_PHYSICAL)
        }
        throw N64HleGuestAddressMappingFault(
            address,
            "outside mapped RDRAM/SP memory"
        )
    }

    companion object {
        const val DEFAULT_RDRAM_SIZE = 8 * 1024 * 1024
        private const val MAX_PHYSICAL_RDRAM = 0x2000_0000
        private const val PHYSICAL_MASK = 0x1FFF_FFFFL
        private const val SP_DMEM_PHYSICAL = 0x0400_0000
        private const val SP_IMEM_PHYSICAL = 0x0400_1000
        private const val SP_MEMORY_SIZE = 0x1000
        private const val SP_REG_BASE = 0x0404_0000
        private const val SP_MEM_ADDR_REG = SP_REG_BASE
        private const val SP_DRAM_ADDR_REG = SP_REG_BASE + 0x04
        private const val SP_RD_LEN_REG = SP_REG_BASE + 0x08
        private const val SP_WR_LEN_REG = SP_REG_BASE + 0x0C
        private const val SP_STATUS_REG = SP_REG_BASE + 0x10
        private const val SP_DMA_FULL_REG = SP_REG_BASE + 0x14
        private const val SP_DMA_BUSY_REG = SP_REG_BASE + 0x18
        private const val SP_SEMAPHORE_REG = SP_REG_BASE + 0x1C
        private const val SP_PC_REG = 0x0408_0000
        private const val SP_MEM_ADDR_MASK = 0x1FFF
        private const val SP_DRAM_ADDR_MASK = 0x00FF_FFFF
        private const val SP_PC_MASK = 0x1FFC
        private const val SP_STATUS_HALT = 1 shl 0
        private const val SP_STATUS_BROKE = 1 shl 1
        private const val SP_STATUS_INTR = 1 shl 2
        private const val SP_STATUS_SSTEP = 1 shl 5
        private const val SP_STATUS_INTR_BREAK = 1 shl 6
        private const val SP_STATUS_CMD_CLR_HALT = 1 shl 0
        private const val SP_STATUS_CMD_SET_HALT = 1 shl 1
        private const val SP_STATUS_CMD_CLR_BROKE = 1 shl 2
        private const val SP_STATUS_CMD_CLR_INTR = 1 shl 3
        private const val SP_STATUS_CMD_SET_INTR = 1 shl 4
        private const val SP_STATUS_CMD_CLR_SSTEP = 1 shl 5
        private const val SP_STATUS_CMD_SET_SSTEP = 1 shl 6
        private const val SP_STATUS_CMD_CLR_INTR_BREAK = 1 shl 7
        private const val SP_STATUS_CMD_SET_INTR_BREAK = 1 shl 8
    }
}
