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
        val (bytes, p) = translateAligned(address, 4, 4)
        return ((bytes[p].toLong() and 0xFF) shl 24) or
            ((bytes[p + 1].toLong() and 0xFF) shl 16) or
            ((bytes[p + 2].toLong() and 0xFF) shl 8) or
            (bytes[p + 3].toLong() and 0xFF)
    }

    fun write32(address: Long, value: Long) {
        require(value in 0L..0xFFFF_FFFFL) { "Word value is outside 0..0xFFFFFFFF" }
        val (bytes, p) = translateAligned(address, 4, 4)
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

    private fun translateAligned(address: Long, length: Int, alignment: Int): Pair<ByteArray, Int> {
        val resolved = translate(address, length)
        require((address and (alignment - 1).toLong()) == 0L) {
            "Guest address must be aligned to $alignment bytes"
        }
        return resolved
    }

    private fun translate(address: Long, length: Int): Pair<ByteArray, Int> {
        require(length >= 0) { "Length must be non-negative" }
        val upper = address ushr 32
        require(upper == 0L || upper == 0xFFFF_FFFFL) {
            "Guest address must be a 32-bit zero-extended or sign-extended value"
        }
        val guest = address and 0xFFFF_FFFFL
        val segment = guest ushr 29
        require(segment == 4L || segment == 5L) {
            "Only KSEG0 and KSEG1 guest addresses map to this guest memory space"
        }
        val physical = (guest and PHYSICAL_MASK).toInt()
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
        throw IllegalArgumentException(
            "Guest address 0x${guest.toString(16)} is outside mapped RDRAM/SP memory"
        )
    }

    companion object {
        const val DEFAULT_RDRAM_SIZE = 8 * 1024 * 1024
        private const val MAX_PHYSICAL_RDRAM = 0x2000_0000
        private const val PHYSICAL_MASK = 0x1FFF_FFFFL
        private const val SP_DMEM_PHYSICAL = 0x0400_0000
        private const val SP_IMEM_PHYSICAL = 0x0400_1000
        private const val SP_MEMORY_SIZE = 0x1000
    }
}
