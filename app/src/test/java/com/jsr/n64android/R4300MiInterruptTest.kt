package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class R4300MiInterruptTest {
    @Test
    fun enabledPiDmaInterruptRaisesCop0Ip2AndEntersGeneralVector() {
        val bus = N64Bus(
            ram = N64Memory(0x2000),
            cartridgeRom = byteArrayOf(0x5A)
        )
        bus.write32(N64Bus.PI_DRAM_ADDR_REG, 0x100)
        bus.write32(N64Bus.PI_CART_ADDR_REG, N64Bus.CARTRIDGE_ROM_BASE)
        bus.write32(N64Bus.PI_WR_LEN_REG, 0)
        bus.write32(N64Bus.MI_INTR_MASK_REG, 1 shl 9) // Set PI mask (MI source bit 4).

        val cpu = R4300Cpu(bus)
        val startPc = 0xFFFFFFFF80000400UL.toLong()
        cpu.setProgramCounter(startPc)
        cpu.cop0[12] = 0x401L // IE=1 and IM[2]=1.

        cpu.step()

        assertEquals(0xFFFFFFFF80000180UL.toLong(), cpu.pc)
        assertEquals(0, cpu.lastExceptionCode)
        assertEquals(startPc, cpu.cop0[14])
        assertTrue((cpu.cop0[13] and (1L shl 10)) != 0L) // Cause.IP2.
        assertTrue((cpu.cop0[12] and 0x2L) != 0L) // Status.EXL.
        assertEquals(N64Bus.MI_INTR_PI, bus.read32(N64Bus.MI_INTR_REG) and N64Bus.MI_INTR_PI)
    }

    @Test
    fun maskedPiDmaInterruptDoesNotEnterCpuException() {
        val bus = N64Bus(
            ram = N64Memory(0x2000),
            cartridgeRom = byteArrayOf(0x5A)
        )
        bus.write32(N64Bus.PI_DRAM_ADDR_REG, 0x100)
        bus.write32(N64Bus.PI_CART_ADDR_REG, N64Bus.CARTRIDGE_ROM_BASE)
        bus.write32(N64Bus.PI_WR_LEN_REG, 0)
        bus.write32(0x400, 0) // NOP at the KSEG0 start PC; PI mask remains disabled.

        val cpu = R4300Cpu(bus)
        val startPc = 0xFFFFFFFF80000400UL.toLong()
        cpu.setProgramCounter(startPc)
        cpu.cop0[12] = 0x401L // CPU permits IP2, but MI does not assert the masked line.

        cpu.step()

        assertEquals(startPc + 4L, cpu.pc)
        assertEquals(null, cpu.lastExceptionCode)
        assertEquals(0L, cpu.cop0[13] and (1L shl 10))
    }
}
