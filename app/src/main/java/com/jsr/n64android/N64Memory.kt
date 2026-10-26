package com.jsr.n64android

/**
 * Initial RAM storage for the custom emulator.
 * This is not yet the complete N64 physical memory map or MMIO bus.
 */
class N64Memory(ramSize: Int = DEFAULT_RAM_SIZE) {
    private val ram: ByteArray

    init {
        require(ramSize > 0) { "RAM size must be positive." }
        ram = ByteArray(ramSize)
    }

    val size: Int get() = ram.size

    fun read8(address: Int): Int = ram[checked(address)].toInt() and 0xFF

    fun read16(address: Int): Int =
        (read8(address) shl 8) or read8(address + 1)

    fun read32(address: Int): Int =
        (read8(address) shl 24) or (read8(address + 1) shl 16) or
            (read8(address + 2) shl 8) or read8(address + 3)

    fun write8(address: Int, value: Int) {
        ram[checked(address)] = value.toByte()
    }

    fun write16(address: Int, value: Int) {
        write8(address, value ushr 8)
        write8(address + 1, value)
    }

    fun write32(address: Int, value: Int) {
        write8(address, value ushr 24)
        write8(address + 1, value ushr 16)
        write8(address + 2, value ushr 8)
        write8(address + 3, value)
    }

    fun clear() { ram.fill(0) }

    private fun checked(address: Int): Int {
        require(address >= 0 && address < ram.size) {
            "RAM address outside the allocated range: 0x${address.toUInt().toString(16)}"
        }
        return address
    }

    companion object { const val DEFAULT_RAM_SIZE = 8 * 1024 * 1024 }
}
