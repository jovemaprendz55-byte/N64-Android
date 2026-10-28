package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Test

class R4300ExceptionMetadataTest {
    @Test fun recordsFaultingInstructionAndBranchForTlbStoreMissInDelaySlot() {
        val memory = N64Memory(64)
        memory.write32(0, (0x04 shl 26) or 2) // beq $zero, $zero, target
        memory.write32(4, (0x2B shl 26) or (1 shl 21) or (2 shl 16)) // sw $v0, 0($at)
        val cpu = R4300Cpu(memory)
        cpu.tlbEnabled = true
        cpu.gpr[1] = 0x00400000L
        cpu.gpr[2] = 0x12345678L
        cpu.setProgramCounter(0xFFFFFFFF80000000UL.toLong())

        cpu.step() // Branch.
        cpu.step() // Store misses in the delay slot.

        assertEquals(0xFFFFFFFF80000004UL.toLong(), cpu.lastExceptionInstructionAddress)
        assertEquals(((0x2B shl 26) or (1 shl 21) or (2 shl 16)), cpu.lastExceptionInstructionWord)
        assertEquals(3, cpu.lastExceptionCode) // TLB store miss.
        assertEquals(0xFFFFFFFF80000000UL.toLong(), cpu.lastExceptionBranchAddress)
        assertEquals(0x00400000L, cpu.cop0[8]) // BadVAddr.
        assertEquals(0xFFFFFFFF80000000UL.toLong(), cpu.cop0[14]) // EPC points to branch.
        assertEquals(0x8000000CUL, cpu.cop0[13].toULong()) // BD + ExcCode 3.
    }
}
