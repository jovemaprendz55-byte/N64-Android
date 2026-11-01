package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class N64SpTaskBootstrapTest {
    @Test
    fun loadsTaskHeaderAndBootMicrocodeThenStartsRspAtZero() {
        val ram = N64Memory(0x4000)
        val taskAddress = 0x100
        val bootAddress = 0x2000
        val words = intArrayOf(
            1, N64SpTaskLifecycle.OS_TASK_DP_WAIT,
            bootAddress, 8,
            0x2100, 0x100,
            0x2200, 0x80,
            0x2300, 0x40,
            0x2400, 0x1000,
            0x2500, 0x200,
            0, 0
        )
        words.forEachIndexed { index, word -> ram.write32(taskAddress + index * 4, word) }
        val bootBytes = intArrayOf(0x3C, 0x08, 0x80, 0x00, 0x25, 0x08, 0x00, 0x01)
        bootBytes.forEachIndexed { index, value -> ram.write8(bootAddress + index, value) }
        val bus = N64Bus(ram = ram)

        val result = N64SpTaskBootstrap(bus, ram.size).startFromRdram(taskAddress)

        assertEquals(taskAddress, result.taskAddress)
        assertEquals(1, result.descriptor.type)
        assertEquals(N64SpTaskLifecycle.OS_TASK_DP_WAIT, result.descriptor.flags)
        assertEquals(bootBytes.size, result.bootMicrocodeBytes)
        assertEquals(0, result.initialProgramCounter)
        bootBytes.forEachIndexed { index, value ->
            assertEquals(value, bus.read8(N64Bus.SP_IMEM_BASE + index))
        }
        assertEquals(1, bus.read32(N64Bus.SP_DMEM_BASE + N64SpTaskDescriptor.DEFAULT_DMEM_OFFSET))
        assertEquals(0, bus.read32(N64Bus.SP_PC_REG))
        assertFalse((bus.read32(N64Bus.SP_STATUS_REG) and N64Bus.SP_STATUS_HALT) != 0)
    }

    @Test
    fun invalidBootMicrocodeRangeLeavesRspHalted() {
        val ram = N64Memory(0x1000)
        val taskAddress = 0x100
        val words = intArrayOf(
            1, 0, 0x0FF0, 0x40, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0
        )
        words.forEachIndexed { index, word -> ram.write32(taskAddress + index * 4, word) }
        val bus = N64Bus(ram = ram)

        try {
            N64SpTaskBootstrap(bus, ram.size).startFromRdram(taskAddress)
            throw AssertionError("Expected invalid boot microcode range to be rejected.")
        } catch (_: IllegalArgumentException) {
            assertTrue((bus.read32(N64Bus.SP_STATUS_REG) and N64Bus.SP_STATUS_HALT) != 0)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTaskHeaderOutsideRdramBeforeChangingSp() {
        val bus = N64Bus(ram = N64Memory(0x1000))
        N64SpTaskBootstrap(bus, 0x1000).startFromRdram(0xFC1)
    }
}
