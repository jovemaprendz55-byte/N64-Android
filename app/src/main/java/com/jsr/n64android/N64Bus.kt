package com.jsr.n64android

/**
 * Small first-step physical bus.
 *
 * It maps installed RDRAM at physical address zero and reserves the PIF ROM
 * window. No IPL3/PIF image is bundled; attempting to fetch it fails explicitly
 * rather than accidentally treating it as ordinary RAM.
 */
class N64Bus(
    private val ram: N64Memory = N64Memory(),
    pifRom: ByteArray? = null
) : N64AddressSpace {
    private val pif = pifRom?.copyOf()

    init {
        require(pif == null || pif.size == PIF_ROM_SIZE) {
            "PIF ROM must be exactly $PIF_ROM_SIZE bytes."
        }
    }

    override fun read8(address: Int): Int {
        require(address >= 0) { "Negative physical address: 0x${address.toUInt().toString(16)}" }
        if (address < ram.size) return ram.read8(address)
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
        return (read8(address) shl 24) or (read8(address + 1) shl 16) or
            (read8(address + 2) shl 8) or read8(address + 3)
    }

    override fun write8(address: Int, value: Int) {
        require(address >= 0) { "Negative physical address." }
        if (address < ram.size) {
            ram.write8(address, value)
            return
        }
        if (address >= PIF_ROM_BASE && address < PIF_ROM_BASE + PIF_ROM_SIZE) {
            throw UnsupportedOperationException("PIF ROM is read-only.")
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
        write8(address, value ushr 24)
        write8(address + 1, value ushr 16)
        write8(address + 2, value ushr 8)
        write8(address + 3, value)
    }

    private fun requireAligned(address: Int, alignment: Int) {
        require(address >= 0 && (address and (alignment - 1)) == 0) {
            "Physical address must be $alignment-byte aligned: 0x${address.toString(16)}."
        }
    }

    companion object {
        const val PIF_ROM_BASE = 0x1FC00000
        const val PIF_ROM_SIZE = 0x7C0
    }
}
