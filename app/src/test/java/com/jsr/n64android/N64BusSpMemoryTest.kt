package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Test

class N64BusSpMemoryTest {
    @Test
    fun dmemAndImemAreMappedAsIndependentBigEndianScratchMemories() {
        val bus = N64Bus(ram = N64Memory(8 * 1024))

        bus.write32(N64Bus.SP_DMEM_BASE, 0x12345678)
        bus.write32(N64Bus.SP_IMEM_BASE, 0xA1B2C3D4.toInt())

        assertEquals(0x12345678, bus.read32(N64Bus.SP_DMEM_BASE))
        assertEquals(0xA1B2C3D4.toInt(), bus.read32(N64Bus.SP_IMEM_BASE))
        assertEquals(0x12, bus.read8(N64Bus.SP_DMEM_BASE))
        assertEquals(0xA1, bus.read8(N64Bus.SP_IMEM_BASE))
    }

    @Test
    fun spStatusRegisterAcceptsControlCommands() {
        val bus = N64Bus(ram = N64Memory(8 * 1024))

        assertEquals(N64Bus.SP_STATUS_HALT, bus.read32(N64Bus.SP_STATUS_REG))
        bus.write32(N64Bus.SP_STATUS_REG, N64Bus.SP_STATUS_CMD_CLR_HALT)
        assertEquals(0, bus.read32(N64Bus.SP_STATUS_REG))
        bus.write32(N64Bus.SP_STATUS_REG, N64Bus.SP_STATUS_CMD_SET_HALT)
        assertEquals(N64Bus.SP_STATUS_HALT, bus.read32(N64Bus.SP_STATUS_REG))
    }

    @Test
    fun spScratchMemoryLastByteIsAccessible() {
        val bus = N64Bus(ram = N64Memory(8 * 1024))
        val lastDmemByte = N64Bus.SP_DMEM_BASE + N64Bus.SP_MEM_SIZE - 1
        val lastImemByte = N64Bus.SP_IMEM_BASE + N64Bus.SP_MEM_SIZE - 1

        bus.write8(lastDmemByte, 0x5A)
        bus.write8(lastImemByte, 0xC3)

        assertEquals(0x5A, bus.read8(lastDmemByte))
        assertEquals(0xC3, bus.read8(lastImemByte))
    }
}
