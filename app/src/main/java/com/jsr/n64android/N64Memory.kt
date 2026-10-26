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
}

/**
 * Initial RAM storage for the custom emulator.
 * This is not yet the complete N64 physical memory map or MMIO bus.
 */
class N64Memory(ramSize: Int = DEFAULT_RAM_SIZE) : N64AddressSpace {
    private val ram: ByteArray

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

    override fun write8(address: Int, value: Int) {
        checkRange(address, 1)
        ram[address] = value.toByte()
    }

    override fun write16(address: Int, value: Int) {
        checkRange(address, 2)
        ram[address] = (value ushr 8).toByte()
        ram[address + 1] = value.toByte()
    }

    override fun write32(address: Int, value: Int) {
        checkRange(address, 4)
        ram[address] = (value ushr 24).toByte()
        ram[address + 1] = (value ushr 16).toByte()
        ram[address + 2] = (value ushr 8).toByte()
        ram[address + 3] = value.toByte()
    }

    fun clear() { ram.fill(0) }

    private fun checkRange(address: Int, width: Int) {
        require(address >= 0 && width > 0 && address <= ram.size - width) {
            "RAM access outside allocated range: address=0x${address.toUInt().toString(16)}, width=$width, size=${ram.size}"
        }
    }

    companion object { const val DEFAULT_RAM_SIZE = 8 * 1024 * 1024 }
}
