package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class N64RdpFillProcessorTest {
    @Test
    fun fillRectangleWritesRgba5551PixelsIntoConfiguredRdramImage() {
        val memory = N64Memory(4096)
        val processor = N64RdpFillProcessor(memory, memory.size)
        val commands = listOf(
            command(N64RdpFillProcessor.CMD_SET_OTHER_MODES, value = 3L shl 20),
            command(N64RdpFillProcessor.CMD_SET_COLOR_IMAGE,
                high = (2L shl 19) or (3L shl 0), low = 0x100L),
            command(N64RdpFillProcessor.CMD_SET_FILL_COLOR, low = 0xF801F801L),
            // Fill rectangle from (1,1) through (2,2), coordinates in 10.2 fixed point.
            command(N64RdpFillProcessor.CMD_FILL_RECTANGLE,
                high = (8L shl 12) or 8L, low = (4L shl 12) or 4L)
        )

        val result = processor.execute(commands)

        assertEquals(4, result.commandsProcessed)
        assertEquals(1, result.rectanglesFilled)
        assertEquals(4, result.pixelsWritten)
        assertEquals(0xF801, memory.read16(0x100 + (1 * 4 + 1) * 2))
        assertEquals(0xF801, memory.read16(0x100 + (1 * 4 + 2) * 2))
        assertEquals(0xF801, memory.read16(0x100 + (2 * 4 + 1) * 2))
        assertEquals(0xF801, memory.read16(0x100 + (2 * 4 + 2) * 2))
        assertEquals(0, memory.read16(0x100))
    }

    @Test
    fun scissorClipsFillRectangle() {
        val memory = N64Memory(4096)
        val processor = N64RdpFillProcessor(memory, memory.size)
        val commands = listOf(
            command(N64RdpFillProcessor.CMD_SET_OTHER_MODES, value = 3 shl 20),
            command(N64RdpFillProcessor.CMD_SET_COLOR_IMAGE, high = (2L shl 19) or 3L, low = 0x100L),
            command(N64RdpFillProcessor.CMD_SET_SCISSOR,
                high = (4L shl 12) or 4L, low = (8L shl 12) or 8L),
            command(N64RdpFillProcessor.CMD_SET_FILL_COLOR, low = 0xFFFFFFFFL),
            command(N64RdpFillProcessor.CMD_FILL_RECTANGLE,
                high = (12L shl 12) or 12L, low = 0L)
        )

        val result = processor.execute(commands)

        assertEquals(4, result.pixelsWritten)
        assertEquals(0, memory.read16(0x100))
        assertEquals(0xFFFF, memory.read16(0x100 + (1 * 4 + 1) * 2))
        assertEquals(0xFFFF, memory.read16(0x100 + (2 * 4 + 2) * 2))
        assertEquals(0, memory.read16(0x100 + (3 * 4 + 3) * 2))
    }

    @Test
    fun fillCycleModeIsRequired() {
        val memory = N64Memory(256)
        val processor = N64RdpFillProcessor(memory, memory.size)
        val commands = listOf(
            command(N64RdpFillProcessor.CMD_SET_COLOR_IMAGE, high = (2L shl 19), low = 0L),
            command(N64RdpFillProcessor.CMD_FILL_RECTANGLE, high = 0L, low = 0L)
        )
        val error = runCatching { processor.execute(commands) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
    }

    private fun command(opcode: Int, high: Long = 0L, low: Long = 0L, value: Long? = null): Long {
        val data = value ?: low
        return (opcode.toLong() shl 58) or (high shl 32) or (data and 0xFFFFFFFFL)
    }
}
