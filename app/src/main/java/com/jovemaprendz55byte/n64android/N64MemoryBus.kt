package com.jovemaprendz55byte.n64android

/**
 * Big-endian physical memory bus for the implemented N64 RAM and SP regions.
 *
 * SP register accesses and simple SP DMA are modeled synchronously. This does
 * not model cycle timing, interrupts, bus contention, or full RSP execution.
 */
internal class N64MemoryBus(
    rdramSize: Int = R4300Cpu.DEFAULT_RDRAM_SIZE,
    private val spMemory: N64SpMemory = N64SpMemory(),
    private val controllerPort: N64ControllerPort = N64ControllerPort()
) {
    private var cartridgeRom: ByteArray? = null
    private val videoInterface = N64VideoInterface()
    private val pifRam = N64PifRam(controllerPort)

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
    private var siDramAddress = 0
    private var siStatus = 0

    companion object {
        const val SP_MEM_ADDR = 0x0404_0000L
        const val SP_DRAM_ADDR = 0x0404_0004L
        const val SP_RD_LEN = 0x0404_0008L
        const val SP_WR_LEN = 0x0404_000CL
        const val SP_STATUS = 0x0404_0010L
        const val SP_DMA_FULL = 0x0404_0014L
        const val SP_DMA_BUSY = 0x0404_0018L
        const val SP_SEMAPHORE = 0x0404_001CL

        const val SI_DRAM_ADDR = 0x0480_0000L
        const val SI_PIF_ADDR_RD64B = 0x0480_0004L
        const val SI_PIF_ADDR_WR64B = 0x0480_0010L
        const val SI_STATUS = 0x0480_0018L

        private const val SP_STATUS_HALT = 1 shl 0
        private const val SP_STATUS_BROKE = 1 shl 1
        private const val SP_STATUS_DMA_BUSY = 1 shl 2
        private const val SP_STATUS_DMA_FULL = 1 shl 3
        private const val SP_STATUS_SINGLE_STEP = 1 shl 5
        private const val SP_STATUS_INTERRUPT_BREAK = 1 shl 6
        private const val SI_STATUS_DMA_BUSY = 1 shl 0
        private const val SI_STATUS_INTERRUPT = 1 shl 12
    }

    fun readByte(physicalAddress: Long): Byte = when (region(physicalAddress)) {
        N64MemoryMap.Region.RDRAM -> rdram[rdramIndex(physicalAddress)]
        N64MemoryMap.Region.SP_DMEM, N64MemoryMap.Region.SP_IMEM ->
            spMemory.readByte(physicalAddress)
        N64MemoryMap.Region.CARTRIDGE_DOMAIN -> readCartridgeByte(physicalAddress)
        N64MemoryMap.Region.PIF -> {
            if (physicalAddress in N64PifRam.PIF_RAM_BASE until N64PifRam.PIF_RAM_END) {
                pifRam.readByte(physicalAddress)
            } else {
                unsupported(physicalAddress) // PIF boot ROM is not implemented.
            }
        }
        else -> unsupported(physicalAddress)
    }

    fun writeByte(physicalAddress: Long, value: Byte) {
        when (region(physicalAddress)) {
            N64MemoryMap.Region.RDRAM -> rdram[rdramIndex(physicalAddress)] = value
            N64MemoryMap.Region.SP_DMEM, N64MemoryMap.Region.SP_IMEM ->
                spMemory.writeByte(physicalAddress, value)
            N64MemoryMap.Region.PIF -> {
                if (physicalAddress in N64PifRam.PIF_RAM_BASE until N64PifRam.PIF_RAM_END) {
                    pifRam.writeByte(physicalAddress, value)
                } else {
                    unsupported(physicalAddress)
                }
            }
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
            N64MemoryMap.Region.VI_REGISTERS -> videoInterface.readRegister(physicalAddress)
            N64MemoryMap.Region.SI_REGISTERS -> readSiRegister(physicalAddress)
            N64MemoryMap.Region.CARTRIDGE_DOMAIN -> readCartridgeWord(physicalAddress)
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
            N64MemoryMap.Region.VI_REGISTERS -> videoInterface.writeRegister(physicalAddress, value)
            N64MemoryMap.Region.SI_REGISTERS -> writeSiRegister(physicalAddress, value)
            else -> unsupported(physicalAddress)
        }
    }

    /** Maps a normalized, big-endian cartridge image into the N64 cartridge domain. */
    fun loadCartridgeRom(bytes: ByteArray) {
        require(bytes.isNotEmpty() && bytes.size <= 64 * 1024 * 1024) {
            "Cartridge ROM must be between 1 byte and 64 MiB"
        }
        require(bytes.size % 4 == 0) { "Cartridge ROM size must be a multiple of four bytes" }
        cartridgeRom = bytes.copyOf()
    }

    private fun readCartridgeByte(physicalAddress: Long): Byte {
        val rom = cartridgeRom
            ?: throw IllegalStateException("No cartridge ROM has been loaded")
        val offset = physicalAddress - 0x1000_0000L
        require(offset >= 0L && offset < rom.size.toLong()) {
            "Cartridge read is outside the loaded ROM"
        }
        return rom[offset.toInt()]
    }

    private fun readCartridgeWord(physicalAddress: Long): Int {
        require(physicalAddress <= 0x1FFF_FFFFL - 3L) {
            "Cartridge word access exceeds the physical address range"
        }
        return ((readCartridgeByte(physicalAddress).toInt() and 0xFF) shl 24) or
            ((readCartridgeByte(physicalAddress + 1).toInt() and 0xFF) shl 16) or
            ((readCartridgeByte(physicalAddress + 2).toInt() and 0xFF) shl 8) or
            (readCartridgeByte(physicalAddress + 3).toInt() and 0xFF)
    }

    /** Copies the current VI framebuffer from RDRAM to a CPU-side frame. */
    internal fun renderVideoFrame(destination: N64VideoFrameBuffer, height: Int = destination.height): Boolean =
        videoInterface.scanout(rdram, destination, height)

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

    private fun requireRdramRange(physicalAddress: Long, length: Int) {
        require(length >= 0 && region(physicalAddress) == N64MemoryMap.Region.RDRAM) {
            "DMA range must start inside RDRAM"
        }
        require(physicalAddress <= Int.MAX_VALUE.toLong()) { "DMA address is too large" }
        val start = physicalAddress.toInt()
        require(start <= rdram.size && length <= rdram.size - start) {
            "DMA range crosses the RDRAM boundary"
        }
    }

    private fun readSiRegister(address: Long): Int = when (address) {
        SI_DRAM_ADDR -> siDramAddress
        SI_PIF_ADDR_RD64B, SI_PIF_ADDR_WR64B -> 0
        SI_STATUS -> siStatus
        else -> throw IllegalArgumentException("Unknown SI register 0x${address.toString(16)}")
    }

    private fun writeSiRegister(address: Long, value: Int) {
        when (address) {
            SI_DRAM_ADDR -> siDramAddress = value and 0x00FF_FFF8
            SI_PIF_ADDR_WR64B -> {
                siStatus = siStatus or SI_STATUS_DMA_BUSY
                try {
                    val source = ByteArray(N64PifRam.SIZE) { offset ->
                        readByte(siDramAddress.toLong() + offset)
                    }
                    pifRam.write64(source)
                } finally {
                    siStatus = siStatus and SI_STATUS_DMA_BUSY.inv()
                }
            }
            SI_PIF_ADDR_RD64B -> {
                siStatus = siStatus or SI_STATUS_DMA_BUSY
                try {
                    // Validate the complete DMA destination before writing any
                    // bytes, so an invalid range cannot partially corrupt RDRAM.
                    requireRdramRange(siDramAddress.toLong(), N64PifRam.SIZE)
                    val destination = pifRam.read64()
                    destination.forEachIndexed { offset, byte ->
                        writeByte(siDramAddress.toLong() + offset, byte)
                    }
                } finally {
                    siStatus = siStatus and SI_STATUS_DMA_BUSY.inv()
                }
            }
            SI_STATUS -> {
                // SI interrupt timing is not modeled; bit 12 is the
                // write-one-to-clear interrupt acknowledge.
                if (value and SI_STATUS_INTERRUPT != 0) {
                    siStatus = siStatus and SI_STATUS_INTERRUPT.inv()
                }
            }
            else -> throw IllegalArgumentException("Unknown SI register 0x${address.toString(16)}")
        }
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
                    // Wrap the 12-bit offset within the selected DMEM/IMEM bank.
                    spMemAddress = (spMemAddress and 0x1000) or ((spMemAddress + 1) and 0x0FFF)
                    spDramAddress = (spDramAddress + 1) and 0x00FF_FFFF
                }
                spDramAddress = (spDramAddress + skip) and 0x00FF_FFFF
            }
        } finally {
            spStatus = spStatus and SP_STATUS_DMA_BUSY.inv()
        }
    }

    internal fun isSpHalted(): Boolean = spStatus and SP_STATUS_HALT != 0

    /** Marks the RSP's BREAK condition and halts execution. */
    internal fun markSpBroke() {
        spStatus = spStatus or SP_STATUS_BROKE or SP_STATUS_HALT
    }

    private fun applySpStatusCommand(value: Int) {
        if (value and (1 shl 0) != 0) spStatus = spStatus and SP_STATUS_HALT.inv()
        if (value and (1 shl 1) != 0) spStatus = spStatus or SP_STATUS_HALT
        if (value and (1 shl 2) != 0) spStatus = spStatus and SP_STATUS_BROKE.inv()
        // Interrupt-on-break is controlled by command bits 7/8; bits 3/4
        // control the separate MI interrupt line, which is not modeled here.
        if (value and (1 shl 7) != 0) {
            spStatus = spStatus and SP_STATUS_INTERRUPT_BREAK.inv()
        }
        if (value and (1 shl 8) != 0) {
            spStatus = spStatus or SP_STATUS_INTERRUPT_BREAK
        }
        if (value and (1 shl 5) != 0) {
            spStatus = spStatus and SP_STATUS_SINGLE_STEP.inv()
        }
        if (value and (1 shl 6) != 0) {
            spStatus = spStatus or SP_STATUS_SINGLE_STEP
        }

        // Signal commands use alternating clear/set bits (7/8 through 21/22).
        for (signal in 0 until 8) {
            val statusBit = 1 shl (7 + signal)
            val clearCommandBit = 1 shl (9 + signal * 2)
            val setCommandBit = 1 shl (10 + signal * 2)
            if (value and clearCommandBit != 0) spStatus = spStatus and statusBit.inv()
            if (value and setCommandBit != 0) spStatus = spStatus or statusBit
        }
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
