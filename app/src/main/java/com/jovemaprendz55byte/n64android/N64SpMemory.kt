package com.jovemaprendz55byte.n64android

/**
 * Byte-addressable storage for the RSP's 4 KiB data and instruction memories.
 *
 * Addresses are physical N64 bus addresses. This models storage only: DMA,
 * SP registers, execution, and synchronization between DMEM/IMEM are not modeled.
 */
internal class N64SpMemory {
    companion object {
        const val DMEM_BASE: Long = 0x0400_0000L
        const val IMEM_BASE: Long = 0x0400_1000L
        const val MEMORY_SIZE: Int = 0x1000
    }

    private val dmem = ByteArray(MEMORY_SIZE)
    private val imem = ByteArray(MEMORY_SIZE)

    fun readByte(address: Long): Byte {
        val (memory, offset) = resolve(address, 1)
        return memory[offset]
    }

    fun writeByte(address: Long, value: Byte) {
        val (memory, offset) = resolve(address, 1)
        memory[offset] = value
    }

    fun readWord(address: Long): Int {
        require(address and 3L == 0L) { "SP word access must be 4-byte aligned" }
        val (memory, offset) = resolve(address, 4)
        return ((memory[offset].toInt() and 0xFF) shl 24) or
            ((memory[offset + 1].toInt() and 0xFF) shl 16) or
            ((memory[offset + 2].toInt() and 0xFF) shl 8) or
            (memory[offset + 3].toInt() and 0xFF)
    }

    fun writeWord(address: Long, value: Int) {
        require(address and 3L == 0L) { "SP word access must be 4-byte aligned" }
        val (memory, offset) = resolve(address, 4)
        memory[offset] = (value ushr 24).toByte()
        memory[offset + 1] = (value ushr 16).toByte()
        memory[offset + 2] = (value ushr 8).toByte()
        memory[offset + 3] = value.toByte()
    }

    private fun resolve(address: Long, length: Int): Pair<ByteArray, Int> {
        val memory: ByteArray
        val offset: Long
        when (address) {
            in DMEM_BASE until (DMEM_BASE + MEMORY_SIZE) -> {
                memory = dmem
                offset = address - DMEM_BASE
            }
            in IMEM_BASE until (IMEM_BASE + MEMORY_SIZE) -> {
                memory = imem
                offset = address - IMEM_BASE
            }
            else -> throw IllegalArgumentException(
                "Address 0x${address.toString(16)} is outside SP DMEM/IMEM"
            )
        }
        require(offset + length <= MEMORY_SIZE) { "SP memory access crosses its boundary" }
        return memory to offset.toInt()
    }
}
