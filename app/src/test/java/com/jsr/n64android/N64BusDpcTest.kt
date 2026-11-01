package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class N64BusDpcTest {
    @Test
    fun endWriteExecutesRdramDisplayListAndAdvancesCurrent() {
        val bus = N64Bus(ram = N64Memory(4096))
        val start = 0x40
        val end = start + 4 * N64DpcCommandProcessor.COMMAND_BYTES
        writeCommands(bus, start, fillRectangleCommands())

        bus.write32(N64Bus.DPC_START_REG, start)
        assertEquals(start, bus.read32(N64Bus.DPC_START_REG))
        assertEquals(start, bus.read32(N64Bus.DPC_CURRENT_REG))
        bus.write32(N64Bus.DPC_END_REG, end)

        assertEquals(end, bus.read32(N64Bus.DPC_END_REG))
        assertEquals(end, bus.read32(N64Bus.DPC_CURRENT_REG))
        assertEquals(N64Bus.DPC_STATUS_IDLE, bus.read32(N64Bus.DPC_STATUS_REG))
        assertEquals(0xF801, bus.read16(0x100 + (1 * 4 + 1) * 2))
        assertEquals(0xF801, bus.read16(0x100 + (2 * 4 + 2) * 2))
    }

    @Test
    fun emptyRangeLeavesCurrentAtEnd() {
        val bus = N64Bus(ram = N64Memory(1024))
        bus.write32(N64Bus.DPC_START_REG, 0x80)
        bus.write32(N64Bus.DPC_END_REG, 0x80)
        assertEquals(0x80, bus.read32(N64Bus.DPC_CURRENT_REG))
        assertEquals(0, bus.read32(N64Bus.DPC_STATUS_REG))
    }

    @Test
    fun rejectsUnalignedEndAndDoesNotAdvanceCurrent() {
        val bus = N64Bus(ram = N64Memory(1024))
        bus.write32(N64Bus.DPC_START_REG, 0x40)
        val error = runCatching { bus.write32(N64Bus.DPC_END_REG, 0x4C) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertEquals(0x40, bus.read32(N64Bus.DPC_CURRENT_REG))
    }

    @Test
    fun rejectsCommandRangeOutsideRdram() {
        val bus = N64Bus(ram = N64Memory(256))
        bus.write32(N64Bus.DPC_START_REG, 0x100)
        val error = runCatching { bus.write32(N64Bus.DPC_END_REG, 0x108) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertEquals(0x100, bus.read32(N64Bus.DPC_CURRENT_REG))
    }

    @Test
    fun dpcRegistersAreIncludedInMmioTrace() {
        val bus = N64Bus(ram = N64Memory(256))
        bus.read32(N64Bus.DPC_STATUS_REG)
        assertTrue(bus.diagnosticMmioHistory.last().contains("DPC"))
    }

    private fun fillRectangleCommands(): List<Long> = listOf(
        command(N64RdpFillProcessor.CMD_SET_OTHER_MODES, high = 3L shl 20),
        command(N64RdpFillProcessor.CMD_SET_COLOR_IMAGE, high = (2L shl 19) or 3L, low = 0x100L),
        command(N64RdpFillProcessor.CMD_SET_FILL_COLOR, low = 0xF801F801L),
        command(N64RdpFillProcessor.CMD_FILL_RECTANGLE, high = (8L shl 12) or 8L, low = (4L shl 12) or 4L)
    )

    private fun writeCommands(memory: N64AddressSpace, start: Int, commands: List<Long>) {
        commands.forEachIndexed { index, command ->
            val address = start + index * N64DpcCommandProcessor.COMMAND_BYTES
            memory.write32(address, (command ushr 32).toInt())
            memory.write32(address + 4, command.toInt())
        }
    }

    private fun command(opcode: Int, high: Long = 0L, low: Long = 0L): Long =
        (opcode.toLong() shl 58) or (high shl 32) or (low and 0xFFFFFFFFL)
}
