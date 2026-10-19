package com.jovemaprendz55byte.n64android

/**
 * Small big-endian physical memory bus for implemented N64 RAM regions.
 *
 * RDRAM and SP DMEM/IMEM are backed by storage. Other mapped devices are
 * classified by N64MemoryMap but intentionally rejected until implemented.
 * This bus does not model mirroring, DMA, registers, timing, or TLB translation.
 */
internal class N64MemoryBus(
    rdramSize: Int = R4300Cpu.DEFAULT_RDRAM_SIZE,
    private val spMemory: N64SpMemory = N64SpMemory()
) {
    private val rdram = ByteArray(rdramSize.also { size ->
        require(size > 0 && size % 4 == 0) {
            "RDRAM size must be a positive multiple of four bytes"
        }
        require(size <= R4300Cpu.DEFAULT_RDRAM_SIZE) {
            "RDRAM size cannot exceed 8 MiB"
        }
    })

    fun readByte(physicalAddress: Long): Byte = when (region(physicalAddress)) {
        N64MemoryMap.Region.RDRAM -> rdram[rdramIndex(physicalAddress)]
        N64MemoryMap.Region.SP_DMEM, N64MemoryMap.Region.SP_IMEM ->
            spMemory.readByte(physicalAddress)
        else -> unsupported(physicalAddress)
    }

    fun writeByte(physicalAddress: Long, value: Byte) {
        when (region(physicalAddress)) {
            N64MemoryMap.Region.RDRAM -> rdram[rdramIndex(physicalAddress)] = value
            N64MemoryMap.Region.SP_DMEM, N64MemoryMap.Region.SP_IMEM ->
                spMemory.writeByte(physicalAddress, value)
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
            else -> unsupported(physicalAddress)
        }
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
