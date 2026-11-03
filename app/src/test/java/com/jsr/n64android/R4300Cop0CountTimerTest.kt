package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Test

class R4300Cop0CountTimerTest {
    @Test fun countAdvancesAtHalfRateAndCompareRaisesIp7() {
        val cpu = R4300Cpu(N64Memory(64))
        cpu.cop0[11] = 2L

        cpu.advanceCountCycles(1)
        assertEquals(0L, cpu.cop0[9])
        cpu.advanceCountCycles(1)
        assertEquals(1L, cpu.cop0[9])
        assertEquals(0L, cpu.cop0[13] and (1L shl 15))

        cpu.advanceCountCycles(2)
        assertEquals(2L, cpu.cop0[9])
        assertEquals(1L shl 15, cpu.cop0[13] and (1L shl 15))
    }

    @Test fun countWrapsAsUnsignedThirtyTwoBitRegister() {
        val cpu = R4300Cpu(N64Memory(64))
        cpu.cop0[9] = 0xFFFFFFFFL
        cpu.cop0[11] = 0L

        cpu.advanceCountCycles(2)

        assertEquals(0L, cpu.cop0[9])
        assertEquals(1L shl 15, cpu.cop0[13] and (1L shl 15))
    }

    @Test fun writingCompareAcknowledgesOnlyTimerPendingBit() {
        val memory = N64Memory(64)
        val mtc0Compare = (0x10 shl 26) or (0x04 shl 21) or (8 shl 16) or (11 shl 11)
        memory.write32(0, mtc0Compare)
        val cpu = R4300Cpu(memory)
        cpu.gpr[8] = 0x12345678L
        cpu.cop0[13] = (1L shl 15) or (1L shl 10)
        cpu.setProgramCounter(0)

        cpu.step()

        assertEquals(0x12345678L, cpu.cop0[11])
        assertEquals(0L, cpu.cop0[13] and (1L shl 15))
        assertEquals(1L shl 10, cpu.cop0[13] and (1L shl 10))
    }
}
