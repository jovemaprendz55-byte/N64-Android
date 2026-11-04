package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Test

class R4300StatusInterruptTest {
    private fun mtc0Status(rt: Int = 1): Int =
        (0x10 shl 26) or (0x04 shl 21) or (rt shl 16) or (12 shl 11)

    @Test
    fun mtc0StatusEnablingIeAndIm2ServicesPendingInterruptBeforeNextInstruction() {
        val memory = N64Memory(32)
        memory.write32(0, mtc0Status())
        memory.write32(4, 0) // NOP must not execute: interrupt is sampled first.
        val cpu = R4300Cpu(memory)
        cpu.gpr[1] = 0x401L // Status.IE + Status.IM2
        cpu.cop0[13] = 1L shl 10 // Cause.IP2 already pending.
        cpu.setProgramCounter(0)

        cpu.step() // MTC0 Status
        assertEquals(4L, cpu.pc)
        assertEquals(0x401L, cpu.cop0[12])

        cpu.step() // Interrupt must be taken before fetching the NOP.

        assertEquals(4L, cpu.cop0[14]) // EPC identifies the next instruction.
        assertEquals(0L, cpu.cop0[13] and (0x1FL shl 2)) // Interrupt ExcCode.
        assertEquals(0x2L, cpu.cop0[12] and 0x2L) // EXL set.
        assertEquals(0xFFFFFFFF80000180UL.toLong(), cpu.pc)
        assertEquals(0L, cpu.gpr[0])
    }

    @Test
    fun pendingInterruptIsNotServicedWhenGlobalIeIsClear() {
        val memory = N64Memory(32)
        memory.write32(0, 0) // NOP
        val cpu = R4300Cpu(memory)
        cpu.cop0[12] = 0x400L // IM2 enabled, global IE disabled.
        cpu.cop0[13] = 1L shl 10 // Cause.IP2 pending.
        cpu.setProgramCounter(0)

        cpu.step()

        assertEquals(4L, cpu.pc)
        assertEquals(0L, cpu.cop0[12] and 0x2L) // EXL remains clear.
        assertEquals(0L, cpu.cop0[14])
    }

    @Test
    fun pendingInterruptIsNotServicedWhenItsStatusMaskIsClear() {
        val memory = N64Memory(32)
        memory.write32(0, 0) // NOP
        val cpu = R4300Cpu(memory)
        cpu.cop0[12] = 0x1L // Global IE enabled, IM2 disabled.
        cpu.cop0[13] = 1L shl 10 // Cause.IP2 pending.
        cpu.setProgramCounter(0)

        cpu.step()

        assertEquals(4L, cpu.pc)
        assertEquals(0L, cpu.cop0[12] and 0x2L) // EXL remains clear.
        assertEquals(0L, cpu.cop0[14])
    }
}
