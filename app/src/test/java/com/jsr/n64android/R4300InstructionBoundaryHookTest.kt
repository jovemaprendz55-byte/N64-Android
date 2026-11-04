package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class R4300InstructionBoundaryHookTest {
    @Test
    fun handledHleBoundarySkipsInstructionFetchAndAdvancesSuppliedCycles() {
        val memory = N64Memory(8 * 1024 * 1024)
        memory.write32(0, -1) // Reserved instruction if the CPU fetches it.
        val cpu = R4300Cpu(memory)
        val startPc = 0xFFFFFFFF80000000UL.toLong()
        val nextPc = 0xFFFFFFFF80000004UL.toLong()
        cpu.setProgramCounter(startPc)
        var observedPc = 0L
        cpu.setInstructionBoundaryHook { pc ->
            observedPc = pc
            cpu.setProgramCounter(nextPc)
            6
        }

        cpu.step()

        assertEquals(startPc, observedPc)
        assertEquals(nextPc, cpu.pc)
        assertEquals(3L, cpu.cop0[9])
    }

    @Test
    fun nullHookResultExecutesGuestInstructionNormally() {
        val memory = N64Memory(8 * 1024 * 1024)
        memory.write32(0, 0) // NOP
        val cpu = R4300Cpu(memory)
        val startPc = 0xFFFFFFFF80000000UL.toLong()
        cpu.setProgramCounter(startPc)
        cpu.setInstructionBoundaryHook { null }

        cpu.step()

        assertEquals(startPc + 4L, cpu.pc)
        assertEquals(1L, cpu.cop0[9])
    }

    @Test
    fun pendingEnabledInterruptIsServicedBeforeHleHook() {
        val memory = N64Memory(8 * 1024 * 1024)
        memory.write32(0, 0)
        val cpu = R4300Cpu(memory)
        cpu.setProgramCounter(0xFFFFFFFF80000000UL.toLong())
        cpu.cop0[12] = 0x8001L // IE + IM7.
        cpu.cop0[13] = 0x8000L // IP7 pending.
        var hookCalls = 0
        cpu.setInstructionBoundaryHook {
            hookCalls++
            4
        }

        cpu.step()

        assertEquals(0, hookCalls)
        assertNotEquals(0xFFFFFFFF80000000UL.toLong(), cpu.pc)
        assertEquals(1L, cpu.cop0[9])
    }
}
