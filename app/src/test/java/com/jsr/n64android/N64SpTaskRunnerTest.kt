package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class N64SpTaskRunnerTest {
    @Test
    fun bootstrapLoadsAndRspExecutesBootProgramUntilBreak() {
        val ram = N64Memory(0x4000)
        val taskAddress = 0x100
        val bootAddress = 0x2000
        val task = intArrayOf(
            1, 0,
            bootAddress, 8,
            0x2100, 0x100,
            0x2200, 0x80,
            0x2300, 0x40,
            0x2400, 0x1000,
            0x2500, 0x200,
            0, 0
        )
        task.forEachIndexed { index, word -> ram.write32(taskAddress + index * 4, word) }

        // ADDIU $t0,$zero,42 ; BREAK
        val bootWords = intArrayOf(
            (0x09 shl 26) or (8 shl 16) or 42,
            0x0000000D
        )
        bootWords.forEachIndexed { index, word -> ram.write32(bootAddress + index * 4, word) }

        val bus = N64Bus(ram = ram)
        val result = N64SpTaskRunner(bus, ram.size).startAndRun(taskAddress, 16)

        assertEquals(2, result.executedInstructions)
        assertEquals(42, N64RspCore(bus).gpr[8]) // A new core is reset-state; bus status remains authoritative.
        assertTrue(result.halted)
        assertTrue(result.broke)
        assertFalse(result.instructionBudgetExhausted)
        assertEquals(N64Bus.SP_STATUS_HALT or N64Bus.SP_STATUS_BROKE, result.spStatus)
        assertEquals(8, result.programCounter)
        assertEquals(0x0000000D, result.lastInstruction)
    }

    @Test
    fun reportsWhenInstructionBudgetEndsWithoutBreak() {
        val ram = N64Memory(0x4000)
        val taskAddress = 0x100
        val bootAddress = 0x2000
        val task = intArrayOf(
            1, 0, bootAddress, 4,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
        )
        task.forEachIndexed { index, word -> ram.write32(taskAddress + index * 4, word) }
        // One ADDIU followed by zero words (SLL $zero,$zero,0).
        ram.write32(bootAddress, (0x09 shl 26) or (8 shl 16) or 7)

        val result = N64SpTaskRunner(N64Bus(ram = ram), ram.size)
            .startAndRun(taskAddress, 1)

        assertEquals(1, result.executedInstructions)
        assertFalse(result.halted)
        assertFalse(result.broke)
        assertTrue(result.instructionBudgetExhausted)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsZeroInstructionBudgetBeforeStartingTask() {
        val bus = N64Bus(ram = N64Memory(0x1000))
        N64SpTaskRunner(bus, 0x1000).startAndRun(0x100, 0)
    }
}
