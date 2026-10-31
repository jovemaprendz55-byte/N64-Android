package com.jsr.n64android

/**
 * Synchronous bridge for the currently supported DPC-to-RDP command-list subset.
 *
 * Reads an end-exclusive, 8-byte-aligned command range from physical RDRAM as
 * big-endian 64-bit words and submits it to the isolated RDP fill processor.
 *
 * This is deliberately not a complete DPC: MMIO registers, double-buffered
 * START/END state, XBUS/DMEM DMA, timing, interrupts and asynchronous FIFO
 * behavior are not modeled here. The RDP decoder also supports only its
 * explicitly implemented command subset.
 */
class N64DpcCommandProcessor(
    private val memory: N64AddressSpace,
    private val rdramSize: Int,
    private val rdp: N64RdpFillProcessor,
    private val maxCommandBytes: Int = DEFAULT_MAX_COMMAND_BYTES
) {
    data class Result(
        val startAddress: Int,
        val endAddressExclusive: Int,
        val commandsProcessed: Int,
        val rectanglesFilled: Int,
        val pixelsWritten: Int
    )

    init {
        require(rdramSize > 0) { "RDRAM size must be positive." }
        require(maxCommandBytes > 0 && maxCommandBytes % COMMAND_BYTES == 0) {
            "Maximum command bytes must be a positive multiple of $COMMAND_BYTES."
        }
    }

    /**
     * Processes commands in [startAddress, endAddressExclusive).
     * Every currently supported RDP command occupies exactly 8 bytes.
     */
    fun process(startAddress: Int, endAddressExclusive: Int): Result {
        require(startAddress >= 0) { "DPC start address cannot be negative." }
        require(endAddressExclusive >= startAddress) {
            "DPC end address must not precede start address."
        }
        require((startAddress and (COMMAND_BYTES - 1)) == 0) {
            "DPC start address must be $COMMAND_BYTES-byte aligned."
        }
        require((endAddressExclusive and (COMMAND_BYTES - 1)) == 0) {
            "DPC end address must be $COMMAND_BYTES-byte aligned."
        }
        require(endAddressExclusive <= rdramSize) {
            "DPC command range exceeds RDRAM: end=0x${endAddressExclusive.toString(16)}, RDRAM=$rdramSize."
        }

        val byteCount = endAddressExclusive - startAddress
        require(byteCount <= maxCommandBytes) {
            "DPC command range exceeds processing limit: bytes=$byteCount, limit=$maxCommandBytes."
        }

        val commands = ArrayList<Long>(byteCount / COMMAND_BYTES)
        var address = startAddress
        while (address < endAddressExclusive) {
            val high = memory.read32(address).toLong() and 0xFFFFFFFFL
            val low = memory.read32(address + 4).toLong() and 0xFFFFFFFFL
            commands.add((high shl 32) or low)
            address += COMMAND_BYTES
        }

        val rdpResult = rdp.execute(commands)
        return Result(
            startAddress = startAddress,
            endAddressExclusive = endAddressExclusive,
            commandsProcessed = rdpResult.commandsProcessed,
            rectanglesFilled = rdpResult.rectanglesFilled,
            pixelsWritten = rdpResult.pixelsWritten
        )
    }

    companion object {
        const val COMMAND_BYTES = 8
        const val DEFAULT_MAX_COMMAND_BYTES = 1 shl 20
    }
}
