package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class N64DpcCommandProcessorTest {

    @Test
    fun busRetainsLastDpcRangeAndSuccessfulDispatchSummary() {
        val memory = N64Memory(4096)
        val bus = N64Bus(ram = memory)
        // RDP Full Sync (0x29), encoded as one big-endian 64-bit command.
        memory.write32(0x40, 0xA4000000.toInt())
        memory.write32(0x44, 0)

        bus.write32(N64Bus.DPC_START_REG, 0x40)
        bus.write32(N64Bus.DPC_END_REG, 0x48)

        val snapshot = bus.diagnosticDpcSnapshot()
        assertTrue(snapshot.contains("START=0x40"))
        assertTrue(snapshot.contains("END=0x48"))
        assertTrue(snapshot.contains("CURRENT=0x48"))
        assertTrue(snapshot.contains("commands=1"))
        assertTrue(snapshot.contains("fullSync=1"))
        assertEquals(null, bus.diagnosticLastDpcFailure)
    }


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
    fun dispatchesFlatTrianglePacketThroughDpcIntoRdram() {
        val memory = N64Memory(4096)
        val rdp = N64RdpFillProcessor(memory, memory.size)
        val dpc = N64DpcCommandProcessor(memory, memory.size, rdp)
        val commands = listOf(
            command(N64RdpFillProcessor.CMD_SET_COLOR_IMAGE, high = (2L shl 19) or 7L, low = 0x100L),
            command(
                N64RdpFillProcessor.CMD_SET_SCISSOR,
                high = 0L,
                low = (8L shl 12) or 8L
            ),
            command(N64RdpFillProcessor.CMD_SET_PRIM_COLOR, low = 0xFF0000FFL)
        )
        writeCommands(memory, 0x40, commands)
        val triangleHeader = (0x08L shl 58) or (32L shl 32) or (16L shl 16)
        val triangleWords = listOf(
            triangleHeader,
            (6L shl 16) shl 32,
            (1L shl 16) shl 32,
            (4L shl 16) shl 32
        )
        writeWords(memory, 0x40 + commands.size * 8, triangleWords)

        val result = dpc.process(0x40, 0x40 + commands.size * 8 + 32)

        assertEquals(4, result.commandsProcessed)
        assertEquals(1, result.trianglesRasterized)
        assertTrue(result.pixelsWritten > 0)
        assertEquals(0xF801, memory.read16(0x100 + (0 * 8 + 1) * 2))
        assertEquals(0, memory.read16(0x100))
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
    fun rejectsTruncatedTextureRectangle() {
        val memory = N64Memory(256)
        val dpc = N64DpcCommandProcessor(memory, memory.size, N64RdpFillProcessor(memory, memory.size))
        memory.write32(0x40, 0x90000000.toInt()) // opcode 0x24: 16-byte command
        memory.write32(0x44, 0)

        val error = runCatching { dpc.process(0x40, 0x48) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("requires 16 bytes"))
    }

    @Test
    fun validatesEntireDisplayListBeforeDispatchingAnySupportedPrefix() {
        val memory = N64Memory(512)
        val rdp = N64RdpFillProcessor(memory, memory.size)
        val dpc = N64DpcCommandProcessor(memory, memory.size, rdp)
        val prefix = listOf(
            command(N64RdpFillProcessor.CMD_SET_OTHER_MODES, high = 3L shl 20),
            command(N64RdpFillProcessor.CMD_SET_COLOR_IMAGE, high = (2L shl 19) or 3L, low = 0x100L),
            command(N64RdpFillProcessor.CMD_SET_FILL_COLOR, low = 0xF801F801L)
        )
        writeCommands(memory, 0x40, prefix)
        memory.write32(0x58, 0x24000000)
        memory.write32(0x5C, 0)

        val error = runCatching { dpc.process(0x40, 0x88) }.exceptionOrNull()

        assertTrue(error is UnsupportedOperationException)
        assertEquals(0, memory.read16(0x100))

        val followUp = listOf(
            command(N64RdpFillProcessor.CMD_SET_OTHER_MODES, high = 3L shl 20),
            command(N64RdpFillProcessor.CMD_SET_COLOR_IMAGE, high = (2L shl 19) or 3L, low = 0x100L),
            command(
                N64RdpFillProcessor.CMD_FILL_RECTANGLE,
                high = (4L shl 12) or 4L,
                low = (4L shl 12) or 4L
            )
        )
        writeCommands(memory, 0x100, followUp)
        dpc.process(0x100, 0x100 + followUp.size * N64DpcCommandProcessor.COMMAND_BYTES)

        assertEquals(0, memory.read16(0x100 + (1 * 4 + 1) * 2))
    }


    @Test
    fun rejectsEveryKnownMultiwordOpcodeWhenEndCutsOffItsPayload() {
        val lengths = listOf(
            0x08 to 32,
            0x09 to 48,
            0x0A to 96,
            0x0B to 112,
            0x0C to 96,
            0x0D to 112,
            0x0E to 160,
            0x0F to 176,
            0x24 to 16,
            0x25 to 16
        )

        lengths.forEach { (opcode, commandBytes) ->
            val memory = N64Memory(512)
            val dpc = N64DpcCommandProcessor(
                memory,
                memory.size,
                N64RdpFillProcessor(memory, memory.size)
            )
            memory.write32(0x40, opcode shl 26)
            memory.write32(0x44, 0)

            val error = runCatching {
                dpc.process(0x40, 0x40 + commandBytes - 8)
            }.exceptionOrNull()

            assertTrue("opcode 0x${opcode.toString(16)}", error is IllegalArgumentException)
            assertTrue(
                "opcode 0x${opcode.toString(16)}",
                error!!.message!!.contains("Truncated RDP command")
            )
        }
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

    private fun writeWords(memory: N64Memory, start: Int, words: List<Long>) {
        words.forEachIndexed { index, word ->
            val address = start + index * 8
            memory.write32(address, (word ushr 32).toInt())
            memory.write32(address + 4, word.toInt())
        }
    }

    private fun writeCommands(memory: N64Memory, start: Int, commands: List<Long>) {
        writeWords(memory, start, commands)
    }

    private fun command(opcode: Int, high: Long = 0L, low: Long = 0L): Long =
        (opcode.toLong() shl 58) or (high shl 32) or (low and 0xFFFFFFFFL)

    @Test
    fun acceptsLoadPipeAndTileSyncAsSynchronousNoOpsAndCountsFullSync() {
        val memory = N64Memory(512)
        val rdp = N64RdpFillProcessor(memory, memory.size)
        val dpc = N64DpcCommandProcessor(memory, memory.size, rdp)
        val commands = listOf(
            command(0x26), // LOAD_SYNC
            command(0x27), // PIPE_SYNC
            command(0x28), // TILE_SYNC
            command(N64DpcCommandProcessor.CMD_FULL_SYNC)
        )
        writeCommands(memory, 0x40, commands)

        val result = dpc.process(0x40, 0x40 + commands.size * 8)

        assertEquals(4, result.commandsProcessed)
        assertEquals(1, result.fullSyncCommands)
        assertEquals(0, result.pixelsWritten)
        assertEquals(0, result.rectanglesFilled)
    }

}
