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
            bootAddress, 12,
            0x2100, 0x100,
            0x2200, 0x80,
            0x2300, 0x40,
            0x2400, 0x1000,
            0x2500, 0x200,
            0, 0
        )
        task.forEachIndexed { index, word -> ram.write32(taskAddress + index * 4, word) }

        // ADDIU $t0,$zero,42 ; SW $t0,0($zero) ; BREAK
        val bootWords = intArrayOf(
            (0x09 shl 26) or (8 shl 16) or 42,
            (0x2B shl 26) or (8 shl 16),
            0x0000000D
        )
        bootWords.forEachIndexed { index, word -> ram.write32(bootAddress + index * 4, word) }

        val bus = N64Bus(ram = ram)
        val result = N64SpTaskRunner(bus, ram.size).startAndRun(taskAddress, 16)

        assertEquals(3, result.executedInstructions)
        assertEquals(42, bus.read32(N64Bus.SP_DMEM_BASE))
        assertTrue(result.halted)
        assertTrue(result.broke)
        assertFalse(result.instructionBudgetExhausted)
        assertEquals(N64Bus.SP_STATUS_HALT or N64Bus.SP_STATUS_BROKE, result.spStatus)
        assertEquals(12, result.programCounter)
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

    @Test
    fun bootMicrocodeDmasTaskMicrocodeIntoImemAndTransfersExecution() {
        val ram = N64Memory(0x4000)
        val taskAddress = 0x100
        val bootAddress = 0x2000
        val taskMicrocodeAddress = 0x2100
        val taskMicrocode = intArrayOf(
            (0x09 shl 26) or (11 shl 16) or 99, // ADDIU $t3,$zero,99
            (0x2B shl 26) or (11 shl 16) or 4,  // SW $t3,4($zero)
            0x0000000D                           // BREAK
        )
        taskMicrocode.forEachIndexed { index, word ->
            ram.write32(taskMicrocodeAddress + index * 4, word)
        }

        fun mtc0(rt: Int, rd: Int) =
            (0x10 shl 26) or (0x04 shl 21) or (rt shl 16) or (rd shl 11)

        // Boot microcode: configure SP DMA, load 12 bytes into IMEM[0],
        // then jump back to zero so the newly loaded task microcode executes.
        val bootMicrocode = intArrayOf(
            (0x09 shl 26) or (8 shl 16) or 0x1000, // IMEM offset zero
            mtc0(8, 0),                            // SP_MEM_ADDR
            (0x09 shl 26) or (9 shl 16) or taskMicrocodeAddress,
            mtc0(9, 1),                            // SP_DRAM_ADDR
            (0x09 shl 26) or (10 shl 16) or 11,    // 12-byte DMA length minus one
            mtc0(10, 2),                           // SP_RD_LEN starts DMA
            0x08000000,                            // J 0
            0x00000000                             // delay slot
        )
        bootMicrocode.forEachIndexed { index, word ->
            ram.write32(bootAddress + index * 4, word)
        }

        val task = intArrayOf(
            1, 0,
            bootAddress, bootMicrocode.size * 4,
            taskMicrocodeAddress, taskMicrocode.size * 4,
            0, 0,
            0, 0,
            0, 0,
            0, 0,
            0, 0
        )
        task.forEachIndexed { index, word ->
            ram.write32(taskAddress + index * 4, word)
        }

        val bus = N64Bus(ram = ram)
        val result = N64SpTaskRunner(bus, ram.size).startAndRun(taskAddress, 32)

        assertEquals(11, result.executedInstructions)
        assertEquals(99, bus.read32(N64Bus.SP_DMEM_BASE + 4))
        assertTrue(result.halted)
        assertTrue(result.broke)
        assertEquals(0x0000000D, result.lastInstruction)
    }

}
