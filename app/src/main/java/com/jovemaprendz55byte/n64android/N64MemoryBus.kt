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

    // Diagnostic boot code may touch the RDRAM controller register window.
    // Store words by offset until hardware-specific register semantics are implemented.
    private val rdramRegisters = IntArray(0x0010_0000 / 4)
    private var rdramRegisterWriteCount = 0L
    private val rdramRegisterReadHistory = mutableListOf<String>()
    private val rdramRegisterWriteHistory = mutableListOf<String>()
    private var rdramWriteCount = 0L
    private val rdramWriteHistory = mutableListOf<String>()
    private var lastRdramRegisterWriteAddress = 0L
    private var lastRdramRegisterWriteValue = 0

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
    // Basic MI state. Device interrupt sources are not yet wired to these bits.
    private var miMode = 0
    private var miInterrupt = 0
    private var miInterruptMask = 0
    // Basic register storage only; peripheral timing and DMA/interrupt side effects are not modeled.
    private val riRegisters = IntArray(8)
    private val aiRegisters = IntArray(6)
    private val piRegisters = IntArray(13)
    // Keep the most recent PI DMA details so boot hangs can be tied to actual ROM copies.
    private var piDmaCount = 0L
    private var lastPiDmaDramAddress = 0
    private var lastPiDmaCartAddress = 0L
    private var lastPiDmaLength = 0
    private var piRegisterWriteCount = 0L
    private var lastPiRegisterWriteAddress = 0L
    private var lastPiRegisterWriteValue = 0
    private val piRegisterWriteHistory = mutableListOf<String>()

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

        const val MI_MODE = 0x0430_0000L
        const val MI_VERSION = 0x0430_0004L
        const val MI_INTR = 0x0430_0008L
        const val MI_INTR_MASK = 0x0430_000CL

        const val AI_DRAM_ADDR = 0x0450_0000L
        const val AI_LEN = 0x0450_0004L
        const val AI_CONTROL = 0x0450_0008L
        const val AI_STATUS = 0x0450_000CL
        const val AI_DACRATE = 0x0450_0010L
        const val AI_BITRATE = 0x0450_0014L

        const val PI_DRAM_ADDR = 0x0460_0000L
        const val PI_CART_ADDR = 0x0460_0004L
        const val PI_RD_LEN = 0x0460_0008L
        const val PI_WR_LEN = 0x0460_000CL
        const val PI_STATUS = 0x0460_0010L

        const val RI_MODE = 0x0470_0000L
        const val RI_CONFIG = 0x0470_0004L
        const val RI_CURRENT_LOAD = 0x0470_0008L
        const val RI_SELECT = 0x0470_000CL
        const val RI_REFRESH = 0x0470_0010L
        const val RI_LATENCY = 0x0470_0014L
        const val RI_RERROR = 0x0470_0018L
        const val RI_WERROR = 0x0470_001CL
        private const val RDRAM_MODE_REG_OFFSET = 0x0000_000CL
        private const val RDRAM_DEVICE_REGISTER_WINDOW_SIZE = 0x0008_0000L
        private const val RDRAM_MODE_INIT = Int.MIN_VALUE

        private const val SP_STATUS_HALT = 1 shl 0
        private const val SP_STATUS_BROKE = 1 shl 1
        private const val SP_STATUS_DMA_BUSY = 1 shl 2
        private const val SP_STATUS_DMA_FULL = 1 shl 3
        private const val SP_STATUS_SINGLE_STEP = 1 shl 5
        private const val SP_STATUS_INTERRUPT_BREAK = 1 shl 6
        private const val SI_STATUS_DMA_BUSY = 1 shl 0
        private const val SI_STATUS_INTERRUPT = 1 shl 12
        private const val MI_VERSION_VALUE = 0x0202_0102
        private const val MI_MODE_INIT = 1 shl 0
        private const val MI_MODE_EBUS = 1 shl 1
        private const val MI_MODE_RDRAM = 1 shl 2
        private const val MI_MODE_INIT_CLEAR = 1 shl 3
        private const val MI_MODE_INIT_SET = 1 shl 4
        private const val MI_MODE_EBUS_CLEAR = 1 shl 5
        private const val MI_MODE_EBUS_SET = 1 shl 6
        private const val MI_MODE_RDRAM_CLEAR = 1 shl 8
        private const val MI_MODE_RDRAM_SET = 1 shl 9
        private const val MI_INTR_DP_CLEAR = 1 shl 7
        private const val MI_INTR_MASK_SP = 1 shl 0
        private const val MI_INTR_MASK_SI = 1 shl 1
        private const val MI_INTR_MASK_AI = 1 shl 2
        private const val MI_INTR_MASK_VI = 1 shl 3
        private const val MI_INTR_MASK_PI = 1 shl 4
        private const val MI_INTR_MASK_DP = 1 shl 5
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
            N64MemoryMap.Region.RDRAM -> {
                rdram[rdramIndex(physicalAddress)] = value
                recordRdramWrite(physicalAddress, value.toInt() and 0xFF, 1)
            }
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
            N64MemoryMap.Region.RDRAM_REGISTERS -> readRdramRegister(physicalAddress)
            N64MemoryMap.Region.SP_REGISTERS -> readSpRegister(physicalAddress)
            N64MemoryMap.Region.MI_REGISTERS -> readMiRegister(physicalAddress)
            N64MemoryMap.Region.VI_REGISTERS -> videoInterface.readRegister(physicalAddress)
            N64MemoryMap.Region.AI_REGISTERS -> readAiRegister(physicalAddress)
            N64MemoryMap.Region.PI_REGISTERS -> readPiRegister(physicalAddress)
            N64MemoryMap.Region.SI_REGISTERS -> readSiRegister(physicalAddress)
            N64MemoryMap.Region.RI_REGISTERS -> readRiRegister(physicalAddress)
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
                recordRdramWrite(physicalAddress, value, 4)
            }
            N64MemoryMap.Region.RDRAM_REGISTERS -> {
                rdramRegisters[((physicalAddress - 0x03F0_0000L) ushr 2).toInt()] = value
                rdramRegisterWriteCount++
                lastRdramRegisterWriteAddress = physicalAddress
                lastRdramRegisterWriteValue = value
                rdramRegisterWriteHistory += "0x" +
                    physicalAddress.toULong().toString(16).uppercase(java.util.Locale.ROOT).padStart(8, '0') +
                    "=0x" + value.toUInt().toString(16).uppercase(java.util.Locale.ROOT).padStart(8, '0')
                if (rdramRegisterWriteHistory.size > 16) rdramRegisterWriteHistory.removeAt(0)
            }
            N64MemoryMap.Region.SP_REGISTERS -> writeSpRegister(physicalAddress, value)
            N64MemoryMap.Region.MI_REGISTERS -> writeMiRegister(physicalAddress, value)
            N64MemoryMap.Region.VI_REGISTERS -> videoInterface.writeRegister(physicalAddress, value)
            N64MemoryMap.Region.AI_REGISTERS -> writeAiRegister(physicalAddress, value)
            N64MemoryMap.Region.PI_REGISTERS -> writePiRegister(physicalAddress, value)
            N64MemoryMap.Region.SI_REGISTERS -> writeSiRegister(physicalAddress, value)
            N64MemoryMap.Region.RI_REGISTERS -> writeRiRegister(physicalAddress, value)
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
            "Cartridge read is outside the loaded ROM: physical=0x" +
                physicalAddress.toString(16).uppercase(java.util.Locale.ROOT) +
                ", ROM offset=0x" + offset.toString(16).uppercase(java.util.Locale.ROOT) +
                ", loaded size=0x" + rom.size.toString(16).uppercase(java.util.Locale.ROOT) +
                " (" + rom.size + " bytes). Check the CPU jump target and cartridge mapping."
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

    internal fun videoDiagnosticSummary(): String = videoInterface.diagnosticSummary()

    /** Summarizes the last cartridge-to-RDRAM DMA actually performed. */
    internal fun piDmaDiagnosticSummary(): String = buildString {
        append("PI_DMA_COUNT=").append(piDmaCount)
        append(", PI_DMA_LAST_DRAM=0x")
            .append(lastPiDmaDramAddress.toUInt().toString(16).uppercase(java.util.Locale.ROOT).padStart(8, '0'))
        append(", PI_DMA_LAST_CART=0x")
            .append(lastPiDmaCartAddress.toULong().toString(16).uppercase(java.util.Locale.ROOT).padStart(8, '0'))
        append(", PI_DMA_LAST_LENGTH=0x")
            .append(lastPiDmaLength.toUInt().toString(16).uppercase(java.util.Locale.ROOT).padStart(8, '0'))
        append(", PI_REG_WRITES=").append(piRegisterWriteCount)
        append(", PI_REG_LAST_ADDR=0x")
            .append(lastPiRegisterWriteAddress.toULong().toString(16).uppercase(java.util.Locale.ROOT).padStart(8, '0'))
        append(", PI_REG_LAST_VALUE=0x")
            .append(lastPiRegisterWriteValue.toUInt().toString(16).uppercase(java.util.Locale.ROOT).padStart(8, '0'))
        append(", PI_REG_HISTORY=").append(piRegisterWriteHistory.joinToString(";"))
        append(", RDRAM_REG_WRITES=").append(rdramRegisterWriteCount)
        append(", RDRAM_REG_LAST_ADDR=0x")
            .append(lastRdramRegisterWriteAddress.toULong().toString(16).uppercase(java.util.Locale.ROOT).padStart(8, '0'))
        append(", RDRAM_REG_LAST_VALUE=0x")
            .append(lastRdramRegisterWriteValue.toUInt().toString(16).uppercase(java.util.Locale.ROOT).padStart(8, '0'))
        append(", RDRAM_REG_READ_HISTORY=").append(rdramRegisterReadHistory.joinToString(";"))
        append(", RDRAM_REG_WRITE_HISTORY=").append(rdramRegisterWriteHistory.joinToString(";"))
        append(", RDRAM_WRITES=").append(rdramWriteCount)
        append(", RDRAM_WRITE_HISTORY=").append(rdramWriteHistory.joinToString(";"))
    }

    private fun readRdramRegister(address: Long): Int {
        val index = ((address - 0x03F0_0000L) ushr 2).toInt()
        // Each RDRAM device has its own register window (0x03F00000 and
        // 0x03F80000). MODE at offset 0x0C exposes INIT as a self-clearing
        // command/status bit. Apply the same behavior to both device banks;
        // otherwise IPL3 can poll the second device forever.
        val isModeRegister =
            (address - 0x03F0_0000L) % RDRAM_DEVICE_REGISTER_WINDOW_SIZE ==
                RDRAM_MODE_REG_OFFSET
        val value = if (isModeRegister) {
            rdramRegisters[index] = rdramRegisters[index] and RDRAM_MODE_INIT.inv()
            rdramRegisters[index]
        } else {
            rdramRegisters[index]
        }
        rdramRegisterReadHistory += "0x" +
            address.toULong().toString(16).uppercase(java.util.Locale.ROOT).padStart(8, '0') +
            "=0x" + value.toUInt().toString(16).uppercase(java.util.Locale.ROOT).padStart(8, '0')
        if (rdramRegisterReadHistory.size > 16) rdramRegisterReadHistory.removeAt(0)
        return value
    }

    private fun recordRdramWrite(address: Long, value: Int, width: Int) {
        rdramWriteCount++
        val digits = if (width == 1) 2 else 8
        rdramWriteHistory += "0x" +
            address.toULong().toString(16).uppercase(java.util.Locale.ROOT).padStart(8, '0') +
            "/$width=0x" + value.toUInt().toString(16).uppercase(java.util.Locale.ROOT).padStart(digits, '0')
        if (rdramWriteHistory.size > 16) rdramWriteHistory.removeAt(0)
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

    private fun readMiRegister(address: Long): Int = when (address) {
        MI_MODE -> miMode
        MI_VERSION -> MI_VERSION_VALUE
        MI_INTR -> miInterrupt
        MI_INTR_MASK -> miInterruptMask
        else -> throw IllegalArgumentException("Unknown MI register 0x${address.toString(16)}")
    }

    private fun writeMiRegister(address: Long, value: Int) {
        when (address) {
            MI_MODE -> {
                if (value and MI_MODE_INIT_CLEAR != 0) miMode = miMode and MI_MODE_INIT.inv()
                if (value and MI_MODE_INIT_SET != 0) miMode = miMode or MI_MODE_INIT
                if (value and MI_MODE_EBUS_CLEAR != 0) miMode = miMode and MI_MODE_EBUS.inv()
                if (value and MI_MODE_EBUS_SET != 0) miMode = miMode or MI_MODE_EBUS
                if (value and MI_MODE_RDRAM_CLEAR != 0) miMode = miMode and MI_MODE_RDRAM.inv()
                if (value and MI_MODE_RDRAM_SET != 0) miMode = miMode or MI_MODE_RDRAM
                // The DP interrupt-clear command is accepted, but DP IRQs are not modeled.
                if (value and MI_INTR_DP_CLEAR != 0) miInterrupt = miInterrupt and MI_INTR_MASK_DP.inv()
            }
            MI_INTR_MASK -> {
                // MI_INTR_MASK is command-based: each interrupt has a clear/set pair.
                val sources = intArrayOf(
                    MI_INTR_MASK_SP, MI_INTR_MASK_SI, MI_INTR_MASK_AI,
                    MI_INTR_MASK_VI, MI_INTR_MASK_PI, MI_INTR_MASK_DP
                )
                sources.forEachIndexed { index, bit ->
                    if (value and (1 shl (index * 2)) != 0) miInterruptMask = miInterruptMask and bit.inv()
                    if (value and (1 shl (index * 2 + 1)) != 0) miInterruptMask = miInterruptMask or bit
                }
            }
            MI_VERSION, MI_INTR -> Unit // Read-only registers.
            else -> throw IllegalArgumentException("Unknown MI register 0x${address.toString(16)}")
        }
    }

    private fun readAiRegister(address: Long): Int {
        val index = ((address - AI_DRAM_ADDR) / 4L).toInt()
        require(address >= AI_DRAM_ADDR && index in aiRegisters.indices) {
            "Unknown AI register 0x${address.toString(16)}"
        }
        return if (address == AI_STATUS) 0 else aiRegisters[index]
    }

    private fun writeAiRegister(address: Long, value: Int) {
        val index = ((address - AI_DRAM_ADDR) / 4L).toInt()
        require(address >= AI_DRAM_ADDR && index in aiRegisters.indices) {
            "Unknown AI register 0x${address.toString(16)}"
        }
        if (address != AI_STATUS) aiRegisters[index] = value
    }

    private fun readPiRegister(address: Long): Int {
        val index = ((address - PI_DRAM_ADDR) / 4L).toInt()
        require(address >= PI_DRAM_ADDR && index in piRegisters.indices) {
            "Unknown PI register 0x${address.toString(16)}"
        }
        return if (address == PI_STATUS) piRegisters[index] and 0x3 else piRegisters[index]
    }

    private fun writePiRegister(address: Long, value: Int) {
        val index = ((address - PI_DRAM_ADDR) / 4L).toInt()
        require(address >= PI_DRAM_ADDR && index in piRegisters.indices) {
            "Unknown PI register 0x${address.toString(16)}"
        }
        piRegisterWriteCount++
        lastPiRegisterWriteAddress = address
        lastPiRegisterWriteValue = value
        piRegisterWriteHistory += "0x" +
            address.toULong().toString(16).uppercase(java.util.Locale.ROOT).padStart(8, '0') +
            "=0x" + value.toUInt().toString(16).uppercase(java.util.Locale.ROOT).padStart(8, '0')
        if (piRegisterWriteHistory.size > 8) piRegisterWriteHistory.removeAt(0)
        when (address) {
            PI_DRAM_ADDR -> piRegisters[index] = value and 0x00FF_FFFF
            PI_CART_ADDR -> piRegisters[index] = value
            PI_RD_LEN -> {
                piRegisters[index] = value
                // PI read transfers cartridge data into RDRAM.
                transferPiDma(cartToRdram = true, command = value)
            }
            PI_WR_LEN -> {
                piRegisters[index] = value
                // PI write transfers RDRAM to the cartridge bus. The ROM is
                // read-only, so retain the register write without changing it.
            }
            PI_STATUS -> {
                // PI_STATUS command bit 1 clears the DMA interrupt flag.
                if (value and 0x2 != 0) piRegisters[index] = piRegisters[index] and 0x8.inv()
            }
            else -> piRegisters[index] = value
        }
    }

    /**
     * Performs the synchronous cartridge-to-RDRAM DMA used by IPL3 to load
     * the game boot code. Timing, bus contention and PI interrupts are not
     * modeled, but the transferred bytes are visible to the CPU immediately.
     */
    private fun transferPiDma(cartToRdram: Boolean, command: Int) {
        if (!cartToRdram) return
        val length = (command and 0x00FF_FFFF) + 1
        val dramAddress = piRegisters[0] and 0x00FF_FFFF
        val cartAddress = piRegisters[1].toLong() and 0xFFFF_FFFFL
        require(length > 0) { "PI DMA length must be positive" }
        requireRdramRange(dramAddress.toLong(), length)
        val cartOffset = cartAddress - 0x1000_0000L
        val rom = cartridgeRom ?: throw IllegalStateException("No cartridge ROM has been loaded")
        require(cartOffset >= 0L && cartOffset <= rom.size.toLong() &&
            length.toLong() <= rom.size.toLong() - cartOffset) {
            "PI DMA cartridge range is outside the loaded ROM: cart=0x${cartAddress.toString(16)}, length=0x${length.toString(16)}, ROM size=0x${rom.size.toString(16)}"
        }
        piDmaCount++
        lastPiDmaDramAddress = dramAddress
        lastPiDmaCartAddress = cartAddress
        lastPiDmaLength = length
        for (offset in 0 until length) {
            writeByte(dramAddress.toLong() + offset, rom[(cartOffset + offset).toInt()])
        }
        piRegisters[0] = (dramAddress + length) and 0x00FF_FFFF
        piRegisters[1] = ((cartAddress + length) and 0xFFFF_FFFFL).toInt()
    }

    private fun readRiRegister(address: Long): Int {
        val index = ((address - RI_MODE) / 4L).toInt()
        require(address >= RI_MODE && index in riRegisters.indices) {
            "Unknown RI register 0x${address.toString(16)}"
        }
        return riRegisters[index]
    }

    private fun writeRiRegister(address: Long, value: Int) {
        val index = ((address - RI_MODE) / 4L).toInt()
        require(address >= RI_MODE && index in riRegisters.indices) {
            "Unknown RI register 0x${address.toString(16)}"
        }
        riRegisters[index] = value
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
