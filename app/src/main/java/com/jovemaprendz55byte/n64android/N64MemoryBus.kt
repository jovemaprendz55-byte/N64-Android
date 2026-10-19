package com.jovemaprendz55byte.n64android

/**
 * Big-endian physical memory bus for the implemented N64 RAM and SP regions.
 *
 * SP register accesses and simple SP DMA are modeled synchronously. This does
 * not model cycle timing, interrupts, bus contention, or full RSP execution.
 */
internal class N64MemoryBus(
    rdramSize: Int = R4300Cpu.DEFAULT_RDRAM_SIZE,
    private val spMemory: N64SpMemory = N64SpMemory()
) {
    private val rdram = ByteArray(rdramSize.also { size ->
        require(size > 0 && size % 4 == 0) {
            "RDRAM size must be a positive multiple of four bytes"
        }
        require(size <= R4300Cpu.DEFAULT_RDRAM_SIZE) {
            "RDRAM size cannot exceed 8 MiB"
        }
    })

    private var spMemAddress = 0
    private var spDramAddress = 0
    private var spReadLength = 0
    private var spWriteLength = 0
    private var spStatus = SP_STATUS_HALT
    private var spSemaphore = 0

    companion object {
        const val SP_MEM_ADDR = 0x0404_0000L
        const val SP_DRAM_ADDR = 0x0404_0004L
        const val SP_RD_LEN = 0x0404_0008L
        const val SP_WR_LEN = 0x0404_000CL
        const val SP_STATUS = 0x0404_0010L
        const val SP_DMA_FULL = 0x0404_0014L
        const val SP_DMA_BUSY = 0x0404_0018L
        const val SP_SEMAPHORE = 0x0404_001CL

        private const val SP_STATUS_HALT = 1 shl 0
        private const val SP_STATUS_BROKE = 1 shl 1
        private const val SP_STATUS_DMA_BUSY = 1 shl 2
        private const val SP_STATUS_DMA_FULL = 1 shl 3
    }

    fun readByte(physicalAddress: Long): Byte = when (region(physicalAddress)) {
        N64MemoryMap.Region.RDRAM -> rdram[rdramIndex(physicalAddress)]
        N64MemoryMap.Region.SP_DMEM, N64MemoryMap.Region.SP_IMEM ->
            spMemory.readByte(physicalAddress)
        else -> unsupported(physicalAddress)
    }

    fun writeByte(physicalAddress: Long, value: Byte) {
        when (region(physicalAddress)) {
            N64MemoryMap.Region.RDRAM -> rdram[rdramIndex(physicalAddress)] = value
            N64MemoryMap.Region.SP_DMEM, N64MemoryMap.Region.SP_IMEM ->
                spMemory.writeByte(physicalAddress, value)
            else -> unsupported(physicalAddress)
        }
    }

    fun readHalf(physicalAddress: Long): Short {
        require(physicalAddress and 1L == 0L) { "Halfword access must be 2-byte aligned" }
        return (((readByte(physicalAddress).toInt() and 0xFF) shl 8) or
            (readByte(physicalAddress + 1).toInt() and 0xFF)).toShort()
    }

    fun writeHalf(physicalAddress: Long, value: Short) {
        require(physicalAddress and 1L == 0L) { "Halfword access must be 2-byte aligned" }
        writeByte(physicalAddress, (value.toInt() ushr 8).toByte())
        writeByte(physicalAddress + 1, value.toByte())
    }

    fun readWord(physicalAddress: Long): Int {
        require(physicalAddress and 3L == 0L) { "Word access must be 4-byte aligned" }
        return when (region(physicalAddress)) {
            N64MemoryMap.Region.SP_DMEM, N64MemoryMap.Region.SP_IMEM ->
                spMemory.readWord(physicalAddress)
            N64MemoryMap.Region.RDRAM -> {
                val address = physicalAddress.toInt()
                require(address <= rdram.size - 4) { "RDRAM word access crosses its boundary" }
                ((rdram[address].toInt() and 0xFF) shl 24) or
                    ((rdram[address + 1].toInt() and 0xFF) shl 16) or
                    ((rdram[address + 2].toInt() and 0xFF) shl 8) or
                    (rdram[address + 3].toInt() and 0xFF)
            }
            N64MemoryMap.Region.SP_REGISTERS -> readSpRegister(physicalAddress)
            else -> unsupported(physicalAddress)
        }
    }

    fun writeWord(physicalAddress: Long, value: Int) {
        require(physicalAddress and 3L == 0L) { "Word access must be 4-byte aligned" }
        when (region(physicalAddress)) {
            N64MemoryMap.Region.SP_DMEM, N64MemoryMap.Region.SP_IMEM ->
                spMemory.writeWord(physicalAddress, value)
            N64MemoryMap.Region.RDRAM -> {
                val address = physicalAddress.toInt()
                require(address <= rdram.size - 4) { "RDRAM word access crosses its boundary" }
                rdram[address] = (value ushr 24).toByte()
                rdram[address + 1] = (value ushr 16).toByte()
                rdram[address + 2] = (value ushr 8).toByte()
                rdram[address + 3] = value.toByte()
            }
            N64MemoryMap.Region.SP_REGISTERS -> writeSpRegister(physicalAddress, value)
            else -> unsupported(physicalAddress)
        }
    }

    fun loadRdram(physicalAddress: Long, bytes: ByteArray) {
        require(region(physicalAddress) == N64MemoryMap.Region.RDRAM) {
            "Bulk loads are supported only in RDRAM"
        }
        require(physicalAddress <= Int.MAX_VALUE.toLong()) { "Address is too large" }
        val start = physicalAddress.toInt()
        require(start <= rdram.size && bytes.size <= rdram.size - start) {
            "RDRAM load crosses its boundary"
        }
        bytes.copyInto(rdram, start)
    }

    private fun readSpRegister(address: Long): Int = when (address) {
        SP_MEM_ADDR -> spMemAddress
        SP_DRAM_ADDR -> spDramAddress
        SP_RD_LEN -> spReadLength
        SP_WR_LEN -> spWriteLength
        SP_STATUS -> spStatus
        SP_DMA_FULL -> if (spStatus and SP_STATUS_DMA_FULL != 0) 1 else 0
        SP_DMA_BUSY -> if (spStatus and SP_STATUS_DMA_BUSY != 0) 1 else 0
        SP_SEMAPHORE -> spSemaphore.also { spSemaphore = 1 }
        else -> throw IllegalArgumentException("Unknown SP register 0x${address.toString(16)}")
    }

    private fun writeSpRegister(address: Long, value: Int) {
        when (address) {
            SP_MEM_ADDR -> spMemAddress = value and 0x1FF8
            SP_DRAM_ADDR -> spDramAddress = value and 0x00FF_FFF8
            SP_RD_LEN -> {
                spReadLength = value
                transferSpDma(value, toSpMemory = true)
            }
            SP_WR_LEN -> {
                spWriteLength = value
                transferSpDma(value, toSpMemory = false)
            }
            SP_STATUS -> applySpStatusCommand(value)
            SP_DMA_FULL, SP_DMA_BUSY -> Unit // read-only status registers
            SP_SEMAPHORE -> spSemaphore = 0
            else -> throw IllegalArgumentException("Unknown SP register 0x${address.toString(16)}")
        }
    }

    /**
     * Simplified synchronous SP DMA. Encoded length/count/skip are honored;
     * each block length is rounded up to an 8-byte boundary as on the N64.
     */
    private fun transferSpDma(command: Int, toSpMemory: Boolean) {
        val blockLength = (((command and 0xFFF) + 1 + 7) / 8) * 8
        val blockCount = ((command ushr 12) and 0xFF) + 1
        val skip = (command ushr 20) and 0xFFF
        spStatus = spStatus or SP_STATUS_DMA_BUSY
        try {
            repeat(blockCount) {
                repeat(blockLength) {
                    val spAddress = N64SpMemory.DMEM_BASE + (spMemAddress and 0x1FFF).toLong()
                    val dramAddress = spDramAddress.toLong()
                    if (toSpMemory) {
                        spMemory.writeByte(spAddress, readByte(dramAddress))
                    } else {
                        writeByte(dramAddress, spMemory.readByte(spAddress))
                    }
                    spMemAddress = (spMemAddress + 1) and 0x1FFF
                    spDramAddress = (spDramAddress + 1) and 0x00FF_FFFF
                }
                spDramAddress = (spDramAddress + skip) and 0x00FF_FFFF
            }
        } finally {
            spStatus = spStatus and SP_STATUS_DMA_BUSY.inv()
        }
    }

    private fun applySpStatusCommand(value: Int) {
        if (value and (1 shl 0) != 0) spStatus = spStatus and SP_STATUS_HALT.inv()
        if (value and (1 shl 1) != 0) spStatus = spStatus or SP_STATUS_HALT
        if (value and (1 shl 2) != 0) spStatus = spStatus and SP_STATUS_BROKE.inv()
        // Interrupt and signal command bits are accepted but interrupt wiring is not modeled.
    }

    private fun rdramIndex(address: Long): Int {
        require(address >= 0L && address < rdram.size.toLong()) {
            "RDRAM byte access crosses its boundary"
        }
        return address.toInt()
    }

    private fun region(address: Long): N64MemoryMap.Region? =
        N64MemoryMap.regionOf(address).also { mapped ->
            require(mapped != null) {
                "Physical address 0x${address.toString(16)} is outside a mapped N64 region"
            }
        }

    private fun unsupported(address: Long): Nothing {
        val mapped = N64MemoryMap.regionOf(address)
        throw IllegalArgumentException(
            "Physical region ${mapped ?: "unknown"} is not implemented by the memory bus " +
                "(address 0x${address.toString(16)})"
        )
    }
}
