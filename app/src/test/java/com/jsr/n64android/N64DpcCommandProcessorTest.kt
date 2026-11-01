package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class N64DpcCommandProcessorTest {
    @Test
    fun readsBigEndianRdramCommandListAndDispatchesItToRdp() {
        val memory = N64Memory(4096)
        val rdp = N64RdpFillProcessor(memory, memory.size)
        val dpc = N64DpcCommandProcessor(memory, memory.size, rdp)
        val commands = listOf(
            command(N64RdpFillProcessor.CMD_SET_OTHER_MODES, high = 3L shl 20),
            command(
                N64RdpFillProcessor.CMD_SET_COLOR_IMAGE,
                high = (2L shl 19) or 3L,
                low = 0x100L
            ),
            command(N64RdpFillProcessor.CMD_SET_FILL_COLOR, low = 0xF801F801L),
            command(
                N64RdpFillProcessor.CMD_FILL_RECTANGLE,
                high = (8L shl 12) or 8L,
                low = (4L shl 12) or 4L
            )
        )
        writeCommands(memory, 0x40, commands)

        val result = dpc.process(0x40, 0x40 + commands.size * N64DpcCommandProcessor.COMMAND_BYTES)

        assertEquals(0x40, result.startAddress)
        assertEquals(0x60, result.endAddressExclusive)
        assertEquals(4, result.commandsProcessed)
        assertEquals(1, result.rectanglesFilled)
        assertEquals(4, result.pixelsWritten)
        assertEquals(0xF801, memory.read16(0x100 + (1 * 4 + 1) * 2))
        assertEquals(0xF801, memory.read16(0x100 + (2 * 4 + 2) * 2))
        assertEquals(0, memory.read16(0x100))
    }

    @Test
    fun rejectsMultiwordTriangleInsteadOfTreatingPayloadAsCommands() {
        val memory = N64Memory(256)
        val dpc = N64DpcCommandProcessor(memory, memory.size, N64RdpFillProcessor(memory, memory.size))
        memory.write32(0x40, 0x20000000)
        memory.write32(0x44, 0)
        val error = runCatching { dpc.process(0x40, 0x60) }.exceptionOrNull()
        assertTrue(error is UnsupportedOperationException)
        assertTrue(error!!.message!!.contains("requires 32 bytes"))
    }

    @Test
    fun rejectsTruncatedMultiwordTriangle() {
        val memory = N64Memory(256)
        val dpc = N64DpcCommandProcessor(memory, memory.size, N64RdpFillProcessor(memory, memory.size))
        memory.write32(0x40, 0x20000000)
        memory.write32(0x44, 0)
        val error = runCatching { dpc.process(0x40, 0x58) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("Truncated RDP command"))
    }

    @Test
    fun emptyCommandRangeDoesNotInvokeRdpOrChangeMemory() {
        val memory = N64Memory(256)
        val dpc = N64DpcCommandProcessor(
            memory,
            memory.size,
            N64RdpFillProcessor(memory, memory.size)
        )

        val result = dpc.process(0x20, 0x20)

        assertEquals(0, result.commandsProcessed)
        assertEquals(0, result.rectanglesFilled)
        assertEquals(0, result.pixelsWritten)
    }

    @Test
    fun rejectsUnalignedCommandRange() {
        val memory = N64Memory(256)
        val dpc = N64DpcCommandProcessor(
            memory,
            memory.size,
            N64RdpFillProcessor(memory, memory.size)
        )

        val error = runCatching { dpc.process(0x04, 0x0C) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun rejectsCommandRangeOutsideRdram() {
        val memory = N64Memory(256)
        val dpc = N64DpcCommandProcessor(
            memory,
            memory.size,
            N64RdpFillProcessor(memory, memory.size)
        )

        val error = runCatching { dpc.process(0x100, 0x108) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun rejectsCommandRangeAboveConfiguredProcessingLimit() {
        val memory = N64Memory(256)
        val dpc = N64DpcCommandProcessor(
            memory,
            memory.size,
            N64RdpFillProcessor(memory, memory.size),
            maxCommandBytes = 8
        )

        val error = runCatching { dpc.process(0x00, 0x10) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    private fun writeCommands(memory: N64Memory, start: Int, commands: List<Long>) {
        commands.forEachIndexed { index, command ->
            val address = start + index * N64DpcCommandProcessor.COMMAND_BYTES
            memory.write32(address, (command ushr 32).toInt())
            memory.write32(address + 4, command.toInt())
        }
    }

    private fun command(opcode: Int, high: Long = 0L, low: Long = 0L): Long =
        (opcode.toLong() shl 58) or (high shl 32) or (low and 0xFFFFFFFFL)
}
