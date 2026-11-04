package com.jsr.n64android

/**
 * Strict RDRAM address space for the new HLE runtime.
 *
 * Only KSEG0/KSEG1 guest virtual addresses are accepted. MMIO, TLB-mapped
 * segments and physical-address shortcuts are deliberately outside this API.
 * All multi-byte values use the N64's big-endian byte order.
 */
class N64HleGuestAddressSpace(
    rdramSize: Int = DEFAULT_RDRAM_SIZE
) {
    private val rdram: ByteArray

    val sizeBytes: Int get() = rdram.size

    init {
        require(rdramSize > 0) { "RDRAM size must be positive" }
        require(rdramSize <= MAX_PHYSICAL_RDRAM) { "RDRAM size exceeds the supported physical window" }
        rdram = ByteArray(rdramSize)
    }

    fun read8(address: Long): Int = rdram[translate(address, 1)].toInt() and 0xFF

    fun write8(address: Long, value: Int) {
        require(value in 0..0xFF) { "Byte value is outside 0..255" }
        rdram[translate(address, 1)] = value.toByte()
    }

    fun read16(address: Long): Int {
        val p = translateAligned(address, 2, 2)
        return ((rdram[p].toInt() and 0xFF) shl 8) or
            (rdram[p + 1].toInt() and 0xFF)
    }

    fun write16(address: Long, value: Int) {
        require(value in 0..0xFFFF) { "Halfword value is outside 0..65535" }
        val p = translateAligned(address, 2, 2)
        rdram[p] = (value ushr 8).toByte()
        rdram[p + 1] = value.toByte()
    }

    fun read32(address: Long): Long {
        val p = translateAligned(address, 4, 4)
        return ((rdram[p].toLong() and 0xFF) shl 24) or
            ((rdram[p + 1].toLong() and 0xFF) shl 16) or
            ((rdram[p + 2].toLong() and 0xFF) shl 8) or
            (rdram[p + 3].toLong() and 0xFF)
    }

    fun write32(address: Long, value: Long) {
        require(value in 0L..0xFFFF_FFFFL) { "Word value is outside 0..0xFFFFFFFF" }
        val p = translateAligned(address, 4, 4)
        rdram[p] = (value ushr 24).toByte()
        rdram[p + 1] = (value ushr 16).toByte()
        rdram[p + 2] = (value ushr 8).toByte()
        rdram[p + 3] = value.toByte()
    }

    fun read64(address: Long): Long {
        val p = translateAligned(address, 8, 8)
        var value = 0L
        for (i in 0 until 8) value = (value shl 8) or (rdram[p + i].toLong() and 0xFF)
        return value
    }

    fun write64(address: Long, value: Long) {
        val p = translateAligned(address, 8, 8)
        for (i in 0 until 8) {
            rdram[p + i] = (value ushr (56 - i * 8)).toByte()
        }
    }

    fun readBytes(address: Long, length: Int): ByteArray {
        require(length >= 0) { "Length must be non-negative" }
        val p = translate(address, length)
        return rdram.copyOfRange(p, p + length)
    }

    fun writeBytes(address: Long, bytes: ByteArray) {
        val p = translate(address, bytes.size)
        bytes.copyInto(rdram, p)
    }

    private fun translateAligned(address: Long, length: Int, alignment: Int): Int {
        val p = translate(address, length)
        require((address and (alignment - 1).toLong()) == 0L) {
            "Guest address must be aligned to $alignment bytes"
        }
        return p
    }

    private fun translate(address: Long, length: Int): Int {
        require(length >= 0) { "Length must be non-negative" }
        val upper = address ushr 32
        require(upper == 0L || upper == 0xFFFF_FFFFL) {
            "Guest address must be a 32-bit zero-extended or sign-extended value"
        }
        val guest = address and 0xFFFF_FFFFL
        val segment = guest ushr 29
        require(segment == 4L || segment == 5L) {
            "Only KSEG0 and KSEG1 guest addresses map to this RDRAM space"
        }
        val physical = (guest and PHYSICAL_MASK).toInt()
        require(physical <= rdram.size && length <= rdram.size - physical) {
            "Guest RDRAM access is outside the configured memory range"
        }
        return physical
    }

    companion object {
        const val DEFAULT_RDRAM_SIZE = 8 * 1024 * 1024
        private const val MAX_PHYSICAL_RDRAM = 0x2000_0000
        private const val PHYSICAL_MASK = 0x1FFF_FFFFL
    }
}