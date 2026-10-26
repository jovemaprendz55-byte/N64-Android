package com.jsr.n64android

/**
 * Initial RAM abstraction for the custom R4300 implementation.
 * The full N64 physical memory map and MMIO devices are not implemented yet.
 */
class N64Memory(ramSize: Int = 8 * 1024 * 1024) {
    private val ram = ByteArray(ramSize)

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

    fun clear() = ram.fill(0)

    private fun checked(address: Int): Int {
        require(address >= 0 && address < ram.size) {
            "Endereço RAM fora do intervalo."
        }
        return address
    }
}
