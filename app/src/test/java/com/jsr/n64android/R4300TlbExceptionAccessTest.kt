package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Test

class R4300TlbExceptionAccessTest {
    @Test
    fun recordsFetchAccessWhenInstructionFetchMissesTlb() {
        val cpu = R4300Cpu(N64Memory(0x4000))
        cpu.tlbEnabled = true
        cpu.setProgramCounter(0x4000L)

        cpu.step()

        assertEquals(2, cpu.lastExceptionCode)
        assertEquals("FETCH", cpu.lastExceptionAccessType)
    }

    @Test
    fun recordsReadAccessWhenLoadMissesTlb() {
        val memory = N64Memory(0x4000)
        memory.write32(0, 0x8D280000.toInt()) // lw t0, 0(t1)
        val cpu = R4300Cpu(memory)
        cpu.tlbEnabled = true
        cpu.setProgramCounter(0xFFFFFFFF80000000UL.toLong())
        cpu.gpr[9] = 0x4000L

        cpu.step()

        assertEquals(2, cpu.lastExceptionCode)
        assertEquals("READ", cpu.lastExceptionAccessType)
        assertEquals(0x4000L, cpu.cop0[8])
    }

    @Test
    fun recordsWriteAccessWhenStoreMissesTlb() {
        val memory = N64Memory(0x4000)
        memory.write32(0, 0xAD280000.toInt()) // sw t0, 0(t1)
        val cpu = R4300Cpu(memory)
        cpu.tlbEnabled = true
        cpu.setProgramCounter(0xFFFFFFFF80000000UL.toLong())
        cpu.gpr[9] = 0x4000L
        cpu.gpr[8] = 42L

        cpu.step()

        assertEquals(3, cpu.lastExceptionCode)
        assertEquals("WRITE", cpu.lastExceptionAccessType)
        assertEquals(0x4000L, cpu.cop0[8])
    }
}
