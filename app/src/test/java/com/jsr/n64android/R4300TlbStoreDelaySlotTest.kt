package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Test

class R4300TlbStoreDelaySlotTest {
    @Test fun sbTlbMissInBranchDelaySlotSetsBdEpcAndBadVAddr() {
        val memory = N64Memory(64)
        memory.write32(0, (0x04 shl 26) or (0 shl 21) or (0 shl 16) or 2) // beq $zero, $zero, target
        memory.write32(4, (0x28 shl 26) or (1 shl 21) or (2 shl 16) or 0x059F) // sb $v0, 0x59F($at)
        val cpu = R4300Cpu(memory)
        cpu.tlbEnabled = true
        cpu.gpr[1] = 0x70201000L
        cpu.gpr[2] = 0x5AL
        cpu.setProgramCounter(0xFFFFFFFF80000000UL.toLong())

        cpu.step() // Branch sets up the delay slot.
        cpu.step() // SB faults while executing in the delay slot.

        assertEquals(3L, (cpu.cop0[13] ushr 2) and 0x1FL) // TLB Store Miss
        assertEquals(1L shl 31, cpu.cop0[13] and (1L shl 31)) // Cause.BD
        assertEquals(0xFFFFFFFF80000000UL.toLong(), cpu.cop0[14]) // EPC points to branch
        assertEquals(0x7020159FL, cpu.cop0[8]) // BadVAddr preserves the unaligned byte address
        assertEquals(0xFFFFFFFF80000004UL.toLong(), cpu.lastExceptionInstructionAddress)
        assertEquals((0x28 shl 26) or (1 shl 21) or (2 shl 16) or 0x059F, cpu.lastExceptionInstructionWord)
        assertEquals(0xFFFFFFFF80000000UL.toLong(), cpu.pc) // First-level TLB refill vector when EXL was clear
    }
}
