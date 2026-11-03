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
        assertTrue(cpu.diagnosticControlFlowHistory.any { it.startsWith("EXCEPTION code=0 ") && it.contains("vector=0xFFFFFFFF80000180") })
        assertEquals(N64Bus.MI_INTR_PI, bus.read32(N64Bus.MI_INTR_REG) and N64Bus.MI_INTR_PI)
    }

    @Test
    fun enabledViScanlineInterruptRaisesCop0Ip2AndEntersGeneralVector() {
        val memory = N64Memory(0x2000)
        memory.write32(0, 0) // NOP at KSEG0 start.
        val bus = N64Bus(ram = memory)
        bus.write32(N64Bus.MI_INTR_MASK_REG, N64Bus.MI_INTR_MASK_VI_SET)
        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_INTR_OFFSET, 1)
        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_V_SYNC_OFFSET, 525)
        bus.advanceViInstructions(N64Bus.VI_INSTRUCTIONS_PER_SCANLINE)

        val cpu = R4300Cpu(bus)
        val startPc = 0xFFFFFFFF80000000UL.toLong()
        cpu.setProgramCounter(startPc)
        cpu.cop0[12] = 0x401L // IE=1 and IM[2]=1.

        cpu.step()

        assertEquals(0xFFFFFFFF80000180UL.toLong(), cpu.pc)
        assertEquals(0, cpu.lastExceptionCode)
        assertEquals(startPc, cpu.cop0[14])
        assertTrue((cpu.cop0[13] and (1L shl 10)) != 0L) // Cause.IP2.
        assertTrue(cpu.diagnosticInterruptHistory.single().contains("MI IRQ"))
        assertTrue(cpu.diagnosticInterruptHistory.single().contains("VECTOR=0xFFFFFFFF80000180"))
        assertEquals(N64Bus.MI_INTR_VI, bus.read32(N64Bus.MI_INTR_REG) and N64Bus.MI_INTR_VI)

        bus.write32(N64Bus.VI_CURRENT_REG, 0)
        assertEquals(0, bus.read32(N64Bus.MI_INTR_REG) and N64Bus.MI_INTR_VI)
        assertTrue(bus.diagnosticMiInterruptSourceHistory.any { it.startsWith("ASSERT source=VI") })
        assertTrue(bus.diagnosticMiInterruptSourceHistory.any { it.startsWith("ACK source=VI") })
    }

    @Test
    fun eretIsRecordedWithReturnTargetAndClearedExl() {
        val memory = N64Memory(64)
        memory.write32(0, 0x42000018) // ERET
        val cpu = R4300Cpu(memory)
        cpu.setProgramCounter(0)
        cpu.cop0[12] = 0x3L // IE=1, EXL=1
        cpu.cop0[14] = 0xFFFFFFFF80000400UL.toLong()

        cpu.step()

        assertEquals(0xFFFFFFFF80000400UL.toLong(), cpu.pc)
        assertEquals(0x1L, cpu.cop0[12])
        assertTrue(cpu.diagnosticControlFlowHistory.any { it.startsWith("ERET ") && it.contains("target=0xFFFFFFFF80000400") })
    }

    @Test
    fun miMaskSetAndClearAreRecordedOnlyOnActualTransitions() {
        val bus = N64Bus(ram = N64Memory(0x2000))

        bus.write32(N64Bus.MI_INTR_MASK_REG, N64Bus.MI_INTR_MASK_VI_SET)
        bus.write32(N64Bus.MI_INTR_MASK_REG, N64Bus.MI_INTR_MASK_VI_SET)
        bus.write32(N64Bus.MI_INTR_MASK_REG, 1 shl 6) // Clear VI mask bit.

        assertEquals(2, bus.diagnosticMiInterruptSourceHistory.count { it.startsWith("MASK_SET source=VI") || it.startsWith("MASK_CLEAR source=VI") })
        assertTrue(bus.diagnosticMiInterruptSourceHistory.any { it.startsWith("MASK_SET source=VI") })
        assertTrue(bus.diagnosticMiInterruptSourceHistory.any { it.startsWith("MASK_CLEAR source=VI") })
    }

    @Test
    fun resetClearsPreviouslyDeliveredMiInterruptHistory() {
        val memory = N64Memory(0x2000)
        memory.write32(0, 0) // NOP at KSEG0 start.
        val bus = N64Bus(ram = memory)
        bus.write32(N64Bus.MI_INTR_MASK_REG, N64Bus.MI_INTR_MASK_VI_SET)
        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_INTR_OFFSET, 1)
        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_V_SYNC_OFFSET, 525)
        bus.advanceViInstructions(N64Bus.VI_INSTRUCTIONS_PER_SCANLINE)

        val cpu = R4300Cpu(bus)
        cpu.setProgramCounter(0xFFFFFFFF80000000UL.toLong())
        cpu.cop0[12] = 0x401L
        cpu.step()
        assertTrue(cpu.diagnosticInterruptHistory.isNotEmpty())

        cpu.reset()

        assertTrue(cpu.diagnosticInterruptHistory.isEmpty())
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

        // The PI source is latched even while its MI mask is disabled.
        assertEquals(startPc + 4L, cpu.pc)
        assertEquals(null, cpu.lastExceptionCode)
        assertEquals(0L, cpu.cop0[13] and (1L shl 10))
        assertEquals(N64Bus.MI_INTR_PI, bus.read32(N64Bus.MI_INTR_REG) and N64Bus.MI_INTR_PI)
        assertTrue(bus.diagnosticMiInterruptSourceHistory.any {
            it.startsWith("ASSERT source=PI") && it.contains("MI_LINE=false")
        })

        // Enabling the mask exposes the already-latched source to the CPU.
        bus.write32(N64Bus.MI_INTR_MASK_REG, 1 shl 9)
        assertTrue(bus.diagnosticMiInterruptSourceHistory.any {
            it.startsWith("MASK_SET source=PI") && it.contains("MI_LINE=true")
        })
        cpu.step()
        assertEquals(0xFFFFFFFF80000180UL.toLong(), cpu.pc)
        assertEquals(0, cpu.lastExceptionCode)
        assertTrue((cpu.cop0[13] and (1L shl 10)) != 0L)
    }
}
