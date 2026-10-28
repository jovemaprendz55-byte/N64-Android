package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Test

class N64BusSpDmaTest {
    @Test
    fun spReadLengthCopiesRdramIntoDmemAndAdvancesAddresses() {
        val ram = N64Memory(8 * 1024)
        ram.write32(0x100, 0x12345678)
        val bus = N64Bus(ram = ram)

        bus.write32(N64Bus.SP_MEM_ADDR_REG, 0x20)
        bus.write32(N64Bus.SP_DRAM_ADDR_REG, 0x100)
        bus.write32(N64Bus.SP_RD_LEN_REG, 3)

        assertEquals(0x12345678L, bus.read32(N64Bus.SP_DMEM_BASE + 0x20))
        assertEquals(0x24L, bus.read32(N64Bus.SP_MEM_ADDR_REG))
        assertEquals(0x104L, bus.read32(N64Bus.SP_DRAM_ADDR_REG))
        assertEquals(0L, bus.read32(N64Bus.SP_DMA_BUSY_REG))
        assertEquals(0L, bus.read32(N64Bus.SP_DMA_FULL_REG))
    }

    @Test
    fun spWriteLengthCopiesImemIntoRdram() {
        val ram = N64Memory(8 * 1024)
        val bus = N64Bus(ram = ram)
        bus.write32(N64Bus.SP_IMEM_BASE + 0x40, 0x89ABCDEF.toInt())

        bus.write32(N64Bus.SP_MEM_ADDR_REG, 0x1040)
        bus.write32(N64Bus.SP_DRAM_ADDR_REG, 0x200)
        bus.write32(N64Bus.SP_WR_LEN_REG, 3)

        assertEquals(0x89ABCDEF.toInt(), ram.read32(0x200))
        assertEquals(0x1044L, bus.read32(N64Bus.SP_MEM_ADDR_REG))
        assertEquals(0x204L, bus.read32(N64Bus.SP_DRAM_ADDR_REG))
    }

    @Test
    fun spDmaWrapsWithinSelectedFourKiBMemory() {
        val ram = N64Memory(8 * 1024)
        ram.write32(0xFFC, 0xCAFEBABE.toInt())
        val bus = N64Bus(ram = ram)

        bus.write32(N64Bus.SP_MEM_ADDR_REG, 0xFFC)
        bus.write32(N64Bus.SP_DRAM_ADDR_REG, 0xFFC)
        bus.write32(N64Bus.SP_RD_LEN_REG, 7)

        assertEquals(0xCAFEBABEL, bus.read32(N64Bus.SP_DMEM_BASE + 0xFFC))
        assertEquals(0L, bus.read32(N64Bus.SP_DMEM_BASE))
        assertEquals(0x004L, bus.read32(N64Bus.SP_MEM_ADDR_REG))
    }
}
