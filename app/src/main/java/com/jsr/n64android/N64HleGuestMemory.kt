package com.jsr.n64android

/**
 * Safe bridge from 32-bit libultra guest pointers to physical RDRAM.
 *
 * Only direct-mapped KSEG0/KSEG1 addresses are accepted here. KUSEG and
 * supervisor/kernel TLB segments must go through the CPU MMU and are rejected.
 * All multi-byte values use the big-endian byte order of N64 RDRAM.
 */
class N64HleGuestMemory(
    private val physicalMemory: N64AddressSpace,
    private val rdramSize: Int
) {
    init {
        require(rdramSize > 0) { "RDRAM size must be positive." }
    }

    fun translateRdramAddress(guestAddress: Long, width: Int = 1, alignment: Int = 1): Int {
        require(width > 0) { "Access width must be positive." }
        require(alignment > 0 && alignment and (alignment - 1) == 0) {
            "Alignment must be a positive power of two."
        }
        val lowAddress = guestAddress.toInt()
        val zeroExtended = lowAddress.toLong() and 0xFFFFFFFFL
        val signExtended = lowAddress.toLong()
        require(guestAddress == zeroExtended || guestAddress == signExtended) {
            "Guest pointer is not a 32-bit address: 0x${guestAddress.toULong().toString(16)}"
        }

        val segment = lowAddress.toUInt()
        val isKseg0 = segment in KSEG0_START..KSEG0_END
        val isKseg1 = segment in KSEG1_START..KSEG1_END
        require(isKseg0 || isKseg1) {
            "Guest address is not direct-mapped KSEG0/KSEG1: 0x${segment.toString(16)}"
        }
        require(lowAddress and (alignment - 1) == 0) {
            "Misaligned guest access: address=0x${segment.toString(16)}, alignment=$alignment"
        }

        val physical = lowAddress and PHYSICAL_ADDRESS_MASK
        require(physical.toLong() + width.toLong() <= rdramSize.toLong()) {
            "Guest access exceeds RDRAM: guest=0x${segment.toString(16)}, physical=0x${physical.toString(16)}, width=$width, RDRAM=$rdramSize"
        }
        return physical
    }

    fun read8(address: Long): Int =
        physicalMemory.read8(translateRdramAddress(address, width = 1, alignment = 1))

    fun read16(address: Long): Int =
        physicalMemory.read16(translateRdramAddress(address, width = 2, alignment = 2))

    fun read32(address: Long): Int =
        physicalMemory.read32(translateRdramAddress(address, width = 4, alignment = 4))

    fun write8(address: Long, value: Int) {
        physicalMemory.write8(translateRdramAddress(address, width = 1, alignment = 1), value)
    }

    fun write16(address: Long, value: Int) {
        physicalMemory.write16(translateRdramAddress(address, width = 2, alignment = 2), value)
    }

    fun write32(address: Long, value: Int) {
        physicalMemory.write32(translateRdramAddress(address, width = 4, alignment = 4), value)
    }

    fun readBytes(address: Long, length: Int): ByteArray {
        require(length >= 0) { "Byte count cannot be negative." }
        val physical = translateRdramAddress(address, width = length.coerceAtLeast(1), alignment = 1)
        return ByteArray(length) { index -> physicalMemory.read8(physical + index).toByte() }
    }

    fun writeBytes(address: Long, bytes: ByteArray) {
        val physical = translateRdramAddress(address, width = bytes.size.coerceAtLeast(1), alignment = 1)
        bytes.forEachIndexed { index, value -> physicalMemory.write8(physical + index, value.toInt()) }
    }

    companion object {
        const val KSEG0_START: UInt = 0x80000000u
        const val KSEG0_END: UInt = 0x9FFFFFFFu
        const val KSEG1_START: UInt = 0xA0000000u
        const val KSEG1_END: UInt = 0xBFFFFFFFu
        const val PHYSICAL_ADDRESS_MASK: Int = 0x1FFFFFFF
    }
}
