package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class R4300DiagnosticFetchTest {
    @Test
    fun diagnosticInstructionFetchUsesTlbForKusegAddress() {
        val memory = N64Memory(0x3000)
        val expectedInstruction = 0x2402002A // addiu v0, zero, 42
        memory.write32(0x1000, expectedInstruction)

        val cpu = R4300Cpu(memory)
        cpu.tlbEnabled = true
        cpu.cop0[10] = 0L // ASID 0
        cpu.tlb.write(
            0,
            R4300Tlb.Entry(
                virtualPagePairBase = 0x2000L,
                asid = 0,
                evenPage = R4300Tlb.Page(physicalFrameNumber = 1, valid = true, dirty = true),
                oddPage = R4300Tlb.Page(physicalFrameNumber = 1, valid = true, dirty = true)
            )
        )

        assertEquals(expectedInstruction, cpu.readInstructionForDiagnostic(0x2000L))
    }

    @Test
    fun diagnosticInstructionFetchPreservesTlbMissInsteadOfInventingPhysicalAddress() {
        val cpu = R4300Cpu(N64Memory(0x3000))
        cpu.tlbEnabled = true

        val fault = assertThrows(R4300Tlb.TlbFault::class.java) {
            cpu.readInstructionForDiagnostic(0x4000L)
        }

        assertEquals(R4300Tlb.FaultKind.MISS, fault.kind)
        assertEquals(0x4000L, fault.virtualAddress)
        assertEquals(R4300Tlb.Access.FETCH, fault.access)
    }
}
