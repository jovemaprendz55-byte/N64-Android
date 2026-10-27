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

    @Test
    fun branchFromSpImemExecutesDelaySlotAndSkipsFallthroughInstruction() {
        val bus = N64Bus()
        val base = N64Bus.SP_IMEM_BASE
        // addiu $t0, $zero, 1
        bus.write32(base, (0x09 shl 26) or (8 shl 16) or 1)
        // beq $t0, $t0, +2: branch target is base + 12
        bus.write32(base + 4, (0x04 shl 26) or (8 shl 21) or (8 shl 16) or 2)
        // Delay slot: addiu $t1, $zero, 7
        bus.write32(base + 8, (0x09 shl 26) or (9 shl 16) or 7)
        // Must be skipped: addiu $t2, $zero, 9
        bus.write32(base + 12 - 4, (0x09 shl 26) or (10 shl 16) or 9)
        // Target: addiu $t3, $zero, 10
        bus.write32(base + 12, (0x09 shl 26) or (11 shl 16) or 10)

        val cpu = R4300Cpu(bus)
        cpu.setProgramCounter(0xA4001000L)
        repeat(4) { cpu.step() }

        assertEquals(1L, cpu.gpr[8])
        assertEquals(7L, cpu.gpr[9])
        assertEquals(0L, cpu.gpr[10])
        assertEquals(10L, cpu.gpr[11])
        assertEquals(0xA4001010L, cpu.pc)
    }
