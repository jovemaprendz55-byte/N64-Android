package com.jsr.n64android

/**
 * Synchronous bridge for the currently supported DPC-to-RDP command-list subset.
 *
 * Reads an end-exclusive, 8-byte-aligned command range from physical RDRAM as
 * big-endian 64-bit words, frames complete packets, then submits the supported subset
 * to the RDP processor (fill-cycle commands and flat RGBA16 opcode 0x08 triangles).
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
        val pixelsWritten: Int,
        val trianglesRasterized: Int = 0,
        val fullSyncCommands: Int = 0
    )

    init {
        require(rdramSize > 0) { "RDRAM size must be positive." }
        require(maxCommandBytes > 0 && maxCommandBytes % COMMAND_BYTES == 0) {
            "Maximum command bytes must be a positive multiple of $COMMAND_BYTES."
        }
    }

    /**
     * Processes commands in [startAddress, endAddressExclusive).
     * The command-stream parser owns range validation and packet framing so
     * the DPC bridge has a single source of truth for those rules.
     * Supported multiword execution is currently limited to opcode 0x08.
     */
    fun process(startAddress: Int, endAddressExclusive: Int): Result {
        val packets = N64RdpCommandStreamParser.parse(
            memory = memory,
            startAddress = startAddress,
            endAddressExclusive = endAddressExclusive,
            rdramSize = rdramSize,
            maxCommandBytes = maxCommandBytes
        )
        // The parser frames and validates every complete packet before any RDP command runs.
        val fullSyncCommands = packets.count { it.opcode == CMD_FULL_SYNC }
        val renderPackets = packets.filterNot { it.opcode == CMD_FULL_SYNC }
        val rdpResult = rdp.executePackets(renderPackets)
        return Result(
            startAddress = startAddress,
            endAddressExclusive = endAddressExclusive,
            commandsProcessed = rdpResult.commandsProcessed + fullSyncCommands,
            rectanglesFilled = rdpResult.rectanglesFilled,
            pixelsWritten = rdpResult.pixelsWritten,
            trianglesRasterized = rdpResult.trianglesRasterized,
            fullSyncCommands = fullSyncCommands
        )
    }

    companion object {
        const val COMMAND_BYTES = 8
        const val DEFAULT_MAX_COMMAND_BYTES = 1 shl 20
        const val CMD_FULL_SYNC = 0x29
    }
}
