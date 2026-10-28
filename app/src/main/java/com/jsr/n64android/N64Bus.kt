package com.jsr.n64android

/**
 * Initial N64 physical bus.
 *
 * Maps RDRAM, cartridge/PIF ROM and a small initial PI register subset.
 * PIF RAM, other MMIO devices, timing, interrupts and full PI behavior remain absent.
 */
class N64Bus(
    private val ram: N64Memory = N64Memory(),
    pifRom: ByteArray? = null,
    cartridgeRom: ByteArray? = null
) : N64AddressSpace {
    private val pif = pifRom?.copyOf()
    // SP scratch memories used during early boot: 4 KiB DMEM and 4 KiB IMEM.
    private val spDmem = ByteArray(SP_MEM_SIZE)
    private val spImem = ByteArray(SP_MEM_SIZE)
    private val cartridge = cartridgeRom?.copyOf()
    private var piDramAddress = 0
    private var piCartAddress = CARTRIDGE_ROM_BASE
    private var piStatus = 0
    // RSP program counter register (SP_PC); the RSP core itself is not yet emulated.
    private var spPc = 0
    // Minimal SP control/status state; the RSP execution and DMA engines remain unimplemented.
    private var spStatus = SP_STATUS_HALT
    private var spSemaphore = 0
    // Minimal MIPS Interface state. Interrupt sources are not yet driven by emulated devices.
    private var miInitMode = 0
    private var miInterruptMask = 0
    // Minimal RI register state; these values are placeholders, not full RDRAM timing emulation.
    private val riRegisters = IntArray(RI_REGISTER_COUNT).also { it[RI_SELECT_OFFSET / 4] = RI_SELECT_RESET }

    /**
     * Identifies a small supported subset of Nintendo CIC variants from the IPL3 word sum.
     * The sum covers ROM offsets 0x40..0xFFF as unsigned big-endian 32-bit words.
     * Returns the seed IPL3 expects in GPR[22] ($s6), or null when not recognized.
     */
    fun detectCicSeed(): Int? {
        val image = cartridge ?: return null
        if (image.size < 0x1000) return null

        var checksum = 0L
        for (offset in 0x40 until 0x1000 step 4) {
            val word = ((image[offset].toLong() and 0xFFL) shl 24) or
                ((image[offset + 1].toLong() and 0xFFL) shl 16) or
                ((image[offset + 2].toLong() and 0xFFL) shl 8) or
                (image[offset + 3].toLong() and 0xFFL)
            checksum += word
        }

        return when (checksum) {
            0xD057C85244L -> 0x3F // CIC-NUS-6102 / 7101, used by GoldenEye 007 (USA).
            0x11A49F60E96L -> 0x91 // CIC-NUS-6105 / 7105.
            else -> null
        }
    }

    init {
        require(pif == null || pif.size == PIF_ROM_SIZE) {
            "PIF ROM must be exactly $PIF_ROM_SIZE bytes."
        }
        require(cartridge == null || cartridge.isNotEmpty()) {
            "Cartridge ROM cannot be empty."
        }
        require(cartridge == null || cartridge.size <= MAX_CARTRIDGE_ROM_SIZE) {
            "Cartridge ROM exceeds the currently supported 64 MiB limit."
        }
    }

    override fun read8(address: Int): Int {
        require(address >= 0) { "Negative physical address: 0x${address.toUInt().toString(16)}" }
        if (address < ram.size) return ram.read8(address)
        if (address in SP_DMEM_BASE until SP_DMEM_BASE + SP_MEM_SIZE) {
            return spDmem[address - SP_DMEM_BASE].toInt() and 0xFF
        }
        if (address in SP_IMEM_BASE until SP_IMEM_BASE + SP_MEM_SIZE) {
            return spImem[address - SP_IMEM_BASE].toInt() and 0xFF
        }

        if (address >= CARTRIDGE_ROM_BASE && address < PIF_ROM_BASE) {
            val image = cartridge ?: throw UnsupportedOperationException(
                "Cartridge ROM is not loaded (physical address 0x${address.toString(16)})."
            )
            val offset = address - CARTRIDGE_ROM_BASE
            if (offset >= image.size) {
                throw UnsupportedOperationException(
                    "Cartridge read is beyond the loaded ROM size at 0x${address.toString(16)}."
                )
            }
            return image[offset].toInt() and 0xFF
        }

        if (address >= PIF_ROM_BASE && address < PIF_ROM_BASE + PIF_ROM_SIZE) {
            val image = pif ?: throw UnsupportedOperationException(
                "PIF boot ROM is not loaded (physical address 0x${address.toString(16)})."
            )
            return image[address - PIF_ROM_BASE].toInt() and 0xFF
        }
        throw UnsupportedOperationException(
            "Unmapped N64 physical read at 0x${address.toString(16)}."
        )
    }

    override fun read16(address: Int): Int {
        requireAligned(address, 2)
        return (read8(address) shl 8) or read8(address + 1)
    }

    override fun read32(address: Int): Int {
        requireAligned(address, 4)
        when (address) {
            SP_PC_REG -> return spPc
            SP_STATUS_REG -> return spStatus
            SP_DMA_FULL_REG -> return 0
            SP_DMA_BUSY_REG -> return 0
            SP_SEMAPHORE_REG -> return spSemaphore
            PI_DRAM_ADDR_REG -> return piDramAddress
            PI_CART_ADDR_REG -> return piCartAddress
            PI_RD_LEN_REG, PI_WR_LEN_REG -> return 0
            PI_STATUS_REG -> return piStatus
            MI_INIT_MODE_REG -> return miInitMode
            MI_VERSION_REG -> return MI_VERSION_VALUE
            MI_INTR_REG -> return 0 // No device currently raises MI interrupt lines.
            MI_INTR_MASK_REG -> return miInterruptMask
            in RI_REG_BASE until RI_REG_BASE + RI_REG_SIZE -> return riRegisters[(address - RI_REG_BASE) / 4]
        }
        return (read8(address) shl 24) or (read8(address + 1) shl 16) or
            (read8(address + 2) shl 8) or read8(address + 3)
    }

    override fun write8(address: Int, value: Int) {
        require(address >= 0) { "Negative physical address." }
        if (address < ram.size) {
            ram.write8(address, value)
            return
        }
        if (address in SP_DMEM_BASE until SP_DMEM_BASE + SP_MEM_SIZE) {
            spDmem[address - SP_DMEM_BASE] = value.toByte()
            return
        }
        if (address in SP_IMEM_BASE until SP_IMEM_BASE + SP_MEM_SIZE) {
            spImem[address - SP_IMEM_BASE] = value.toByte()
            return
        }
        if ((address >= CARTRIDGE_ROM_BASE && address < PIF_ROM_BASE) ||
            (address >= PIF_ROM_BASE && address < PIF_ROM_BASE + PIF_ROM_SIZE)
        ) {
            throw UnsupportedOperationException("Cartridge/PIF ROM is read-only.")
        }
        throw UnsupportedOperationException(
            "Unmapped N64 physical write at 0x${address.toString(16)}."
        )
    }

    override fun write16(address: Int, value: Int) {
        requireAligned(address, 2)
        write8(address, value ushr 8)
        write8(address + 1, value)
    }

    override fun write32(address: Int, value: Int) {
        requireAligned(address, 4)
        when (address) {
            SP_PC_REG -> { spPc = value and SP_PC_MASK; return }
            SP_STATUS_REG -> { writeSpStatusCommand(value); return }
            SP_SEMAPHORE_REG -> { spSemaphore = 1; return }
            PI_DRAM_ADDR_REG -> { piDramAddress = value and 0x00FFFFFF; return }
            PI_CART_ADDR_REG -> { piCartAddress = value; return }
            // PI DMA direction is named from RDRAM's point of view:
            // WR_LEN writes cartridge data into RDRAM; RD_LEN reads RDRAM data to the cartridge.
            PI_RD_LEN_REG -> { dmaRdramToCartridge((value and 0x00FFFFFF) + 1); return }
            PI_WR_LEN_REG -> { dmaCartridgeToRam((value and 0x00FFFFFF) + 1); return }
            PI_STATUS_REG -> { piStatus = piStatus and (value.inv() and 0x3); return }
            MI_INIT_MODE_REG -> { writeMiInitModeCommand(value); return }
            MI_INTR_MASK_REG -> { writeMiInterruptMaskCommand(value); return }
            in RI_REG_BASE until RI_REG_BASE + RI_REG_SIZE -> {
                riRegisters[(address - RI_REG_BASE) / 4] = value
                return
            }
        }
        write8(address, value ushr 24)
        write8(address + 1, value ushr 16)
        write8(address + 2, value ushr 8)
        write8(address + 3, value)
    }

    /** Simplified PI WR_LEN DMA from cartridge ROM into RDRAM, without timing or interrupts. */
    private fun dmaCartridgeToRam(length: Int) {
        val image = cartridge ?: throw UnsupportedOperationException(
            "PI DMA requested but cartridge ROM is not loaded."
        )
        require(length > 0 && piDramAddress <= ram.size - length) {
            "PI DMA destination is outside installed RDRAM: address=0x${piDramAddress.toString(16)}, length=$length."
        }
        require(piCartAddress >= CARTRIDGE_ROM_BASE) {
            "PI DMA source is not in the cartridge ROM window: 0x${piCartAddress.toString(16)}."
        }
        val offset = piCartAddress - CARTRIDGE_ROM_BASE
        require(offset >= 0 && offset <= image.size - length) {
            "PI DMA source exceeds loaded cartridge ROM: offset=0x${offset.toString(16)}, length=$length."
        }
        for (index in 0 until length) {
            ram.write8(piDramAddress + index, image[offset + index].toInt() and 0xFF)
        }
        piDramAddress = (piDramAddress + length) and 0x00FFFFFF
        piCartAddress += length
        piStatus = piStatus and PI_STATUS_DMA_BUSY.inv()
    }

    /**
     * Minimal PI RD_LEN DMA behavior for a read-only cartridge ROM.
     *
     * The PI consumes the RDRAM-to-cartridge request and advances its address registers,
     * but reads targeting the ROM window do not modify the loaded ROM image.
     * Writable cartridge devices (SRAM/Flash) are not implemented by this bus.
     */
    private fun dmaRdramToCartridge(length: Int) {
        require(length > 0 && piDramAddress <= ram.size - length) {
            "PI write DMA source is outside installed RDRAM: address=0x${piDramAddress.toString(16)}, length=$length."
        }
        require(piCartAddress >= CARTRIDGE_ROM_BASE && piCartAddress < PIF_ROM_BASE) {
            "PI write DMA destination is outside the read-only cartridge ROM window: 0x${piCartAddress.toString(16)}."
        }
        // A ROM cannot be changed by this DMA direction. Model the transfer as
        // ignored writes rather than aborting CPU execution; save-device writes
        // need a separate writable cartridge-device implementation.
        piDramAddress = (piDramAddress + length) and 0x00FFFFFF
        piCartAddress += length
        piStatus = piStatus and PI_STATUS_DMA_BUSY.inv()
    }

    /** Applies MI_INIT_MODE write-one command bits; reset and device side effects remain partial. */
    private fun writeMiInitModeCommand(command: Int) {
        if ((command and MI_INIT_MODE_CLR_INIT) != 0) miInitMode = miInitMode and MI_INIT_MODE_INIT.inv()
        if ((command and MI_INIT_MODE_SET_INIT) != 0) miInitMode = miInitMode or MI_INIT_MODE_INIT
        if ((command and MI_INIT_MODE_CLR_EBUS) != 0) miInitMode = miInitMode and MI_INIT_MODE_EBUS.inv()
        if ((command and MI_INIT_MODE_SET_EBUS) != 0) miInitMode = miInitMode or MI_INIT_MODE_EBUS
        if ((command and MI_INIT_MODE_CLR_DP_INTR) != 0) miInitMode = miInitMode and MI_INIT_MODE_DP_INTR.inv()
        if ((command and MI_INIT_MODE_CLR_RDRAM_REG) != 0) miInitMode = miInitMode and MI_INIT_MODE_RDRAM_REG.inv()
        if ((command and MI_INIT_MODE_SET_RDRAM_REG) != 0) miInitMode = miInitMode or MI_INIT_MODE_RDRAM_REG
    }

    /** MI_INTR_MASK uses paired clear/set command bits for SP, SI, AI, VI, PI and DP. */
    private fun writeMiInterruptMaskCommand(command: Int) {
        for (source in 0 until 6) {
            val clearCommand = 1 shl (source * 2)
            val setCommand = 1 shl (source * 2 + 1)
            val maskBit = 1 shl source
            if ((command and clearCommand) != 0) miInterruptMask = miInterruptMask and maskBit.inv()
            if ((command and setCommand) != 0) miInterruptMask = miInterruptMask or maskBit
        }
    }

    /** Applies the SP_STATUS write-one command bits to the readable status bits. */
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
        for (signal in 0 until 8) {
            val clearCommand = 1 shl (9 + signal * 2)
            val setCommand = 1 shl (10 + signal * 2)
            val signalBit = 1 shl (7 + signal)
            if ((command and clearCommand) != 0) spStatus = spStatus and signalBit.inv()
            if ((command and setCommand) != 0) spStatus = spStatus or signalBit
        }
    }

    private fun requireAligned(address: Int, alignment: Int) {
        require(address >= 0 && (address and (alignment - 1)) == 0) {
            "Physical address must be $alignment-byte aligned: 0x${address.toString(16)}."
        }
    }

    companion object {
        const val SP_DMEM_BASE = 0x04000000
        const val SP_IMEM_BASE = 0x04001000
        const val SP_MEM_SIZE = 0x1000
        const val SP_REG_BASE = 0x04040000
        const val SP_MEM_ADDR_REG = SP_REG_BASE
        const val SP_DRAM_ADDR_REG = SP_REG_BASE + 0x04
        const val SP_RD_LEN_REG = SP_REG_BASE + 0x08
        const val SP_WR_LEN_REG = SP_REG_BASE + 0x0C
        const val SP_STATUS_REG = SP_REG_BASE + 0x10
        const val SP_DMA_FULL_REG = SP_REG_BASE + 0x14
        const val SP_DMA_BUSY_REG = SP_REG_BASE + 0x18
        const val SP_SEMAPHORE_REG = SP_REG_BASE + 0x1C
        const val SP_STATUS_HALT = 1 shl 0
        const val SP_STATUS_BROKE = 1 shl 1
        const val SP_STATUS_INTR = 1 shl 2
        const val SP_STATUS_SSTEP = 1 shl 5
        const val SP_STATUS_INTR_BREAK = 1 shl 6
        const val SP_STATUS_CMD_CLR_HALT = 1 shl 0
        const val SP_STATUS_CMD_SET_HALT = 1 shl 1
        const val SP_STATUS_CMD_CLR_BROKE = 1 shl 2
        const val SP_STATUS_CMD_CLR_INTR = 1 shl 3
        const val SP_STATUS_CMD_SET_INTR = 1 shl 4
        const val SP_STATUS_CMD_CLR_SSTEP = 1 shl 5
        const val SP_STATUS_CMD_SET_SSTEP = 1 shl 6
        const val SP_STATUS_CMD_CLR_INTR_BREAK = 1 shl 7
        const val SP_STATUS_CMD_SET_INTR_BREAK = 1 shl 8
        const val SP_PC_REG = 0x04080000
        const val SP_PC_MASK = 0x00001FFC
        const val CARTRIDGE_ROM_BASE = 0x10000000
        const val MAX_CARTRIDGE_ROM_SIZE = 64 * 1024 * 1024
        const val PIF_ROM_BASE = 0x1FC00000
        const val PIF_ROM_SIZE = 0x7C0
        const val PI_REG_BASE = 0x04600000
        const val PI_DRAM_ADDR_REG = PI_REG_BASE
        const val PI_CART_ADDR_REG = PI_REG_BASE + 0x04
        const val PI_RD_LEN_REG = PI_REG_BASE + 0x08
        const val PI_WR_LEN_REG = PI_REG_BASE + 0x0C
        const val PI_STATUS_REG = PI_REG_BASE + 0x10
        const val PI_STATUS_DMA_BUSY = 0x01
        const val MI_REG_BASE = 0x04300000
        const val MI_INIT_MODE_REG = MI_REG_BASE
        const val MI_VERSION_REG = MI_REG_BASE + 0x04
        const val MI_INTR_REG = MI_REG_BASE + 0x08
        const val MI_INTR_MASK_REG = MI_REG_BASE + 0x0C
        const val MI_VERSION_VALUE = 0x02020102
        const val MI_INIT_MODE_INIT = 1 shl 7
        const val MI_INIT_MODE_EBUS = 1 shl 8
        const val MI_INIT_MODE_DP_INTR = 1 shl 11
        const val MI_INIT_MODE_RDRAM_REG = 1 shl 12
        const val MI_INIT_MODE_CLR_INIT = 1 shl 0
        const val MI_INIT_MODE_SET_INIT = 1 shl 1
        const val MI_INIT_MODE_CLR_EBUS = 1 shl 2
        const val MI_INIT_MODE_SET_EBUS = 1 shl 3
        const val MI_INIT_MODE_CLR_DP_INTR = 1 shl 4
        const val MI_INIT_MODE_CLR_RDRAM_REG = 1 shl 6
        const val MI_INIT_MODE_SET_RDRAM_REG = 1 shl 7
        const val RI_REG_BASE = 0x04700000
        const val RI_REG_SIZE = 0x20
        const val RI_SELECT_OFFSET = 0x0C
        const val RI_SELECT_RESET = 0x00000014
        const val RI_REGISTER_COUNT = RI_REG_SIZE / 4
    }
}
