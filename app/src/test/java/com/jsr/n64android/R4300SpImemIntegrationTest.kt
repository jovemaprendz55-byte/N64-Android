package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Test

class R4300SpImemIntegrationTest {
    @Test
    fun cpuFetchesAndExecutesInstructionsFromSpImemThroughKseg1() {
        val bus = N64Bus()
        // addiu $t0, $zero, 42; addiu $t1, $t0, 1
        bus.write32(N64Bus.SP_IMEM_BASE, (0x09 shl 26) or (8 shl 16) or 42)
        bus.write32(N64Bus.SP_IMEM_BASE + 4, (0x09 shl 26) or (8 shl 21) or (9 shl 16) or 1)

        val cpu = R4300Cpu(bus)
        cpu.setProgramCounter(0xA4001000L)
        cpu.step()
        cpu.step()

        assertEquals(42L, cpu.gpr[8])
        assertEquals(43L, cpu.gpr[9])
        assertEquals(0xA4001008L, cpu.pc)
    }
}
