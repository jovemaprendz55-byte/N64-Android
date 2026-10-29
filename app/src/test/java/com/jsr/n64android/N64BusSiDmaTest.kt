package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Test

class N64BusSiDmaTest {
    @Test
    fun pifRamIsReadableAndWritableButSeparateFromPifRom() {
        val pifRom = ByteArray(N64Bus.PIF_ROM_SIZE) { 0x5A.toByte() }
        val bus = N64Bus(pifRom = pifRom)

        assertEquals(0x5A, bus.read8(N64Bus.PIF_ROM_BASE))
        bus.write8(N64Bus.PIF_RAM_BASE, 0xA5)
        bus.write8(N64Bus.PIF_RAM_BASE + N64Bus.PIF_RAM_SIZE - 1, 0x3C)

        assertEquals(0xA5, bus.read8(N64Bus.PIF_RAM_BASE))
        assertEquals(0x3C, bus.read8(N64Bus.PIF_RAM_BASE + N64Bus.PIF_RAM_SIZE - 1))
        assertEquals(0x5A, bus.read8(N64Bus.PIF_ROM_BASE))
    }

    @Test
    fun siWriteDmaCopies64BytesFromRdramToPifRam() {
        val ram = N64Memory(8 * 1024)
        val bus = N64Bus(ram = ram)

        for (index in 0 until N64Bus.PIF_RAM_SIZE) {
            ram.write8(0x100 + index, (index * 3) and 0xFF)
        }
        bus.write32(N64Bus.SI_DRAM_ADDR_REG, 0x100)
        bus.write32(N64Bus.SI_PIF_ADDR_WR64B_REG, N64Bus.PIF_RAM_BASE)

        for (index in 0 until N64Bus.PIF_RAM_SIZE) {
            assertEquals((index * 3) and 0xFF, bus.read8(N64Bus.PIF_RAM_BASE + index))
        }
        assertEquals(0x100 + N64Bus.PIF_RAM_SIZE, bus.read32(N64Bus.SI_DRAM_ADDR_REG))
        assertEquals(N64Bus.SI_STATUS_INTERRUPT, bus.read32(N64Bus.SI_STATUS_REG) and N64Bus.SI_STATUS_INTERRUPT)
        assertEquals(0, bus.read32(N64Bus.SI_STATUS_REG) and (N64Bus.SI_STATUS_DMA_BUSY or N64Bus.SI_STATUS_IO_BUSY))
    }

    @Test
    fun siReadDmaCopies64BytesFromPifRamToRdram() {
        val ram = N64Memory(8 * 1024)
        val bus = N64Bus(ram = ram)

        for (index in 0 until N64Bus.PIF_RAM_SIZE) {
            bus.write8(N64Bus.PIF_RAM_BASE + index, 0xFF - index)
        }
        bus.write32(N64Bus.SI_DRAM_ADDR_REG, 0x200)
        bus.write32(N64Bus.SI_PIF_ADDR_RD64B_REG, N64Bus.PIF_RAM_BASE)

        for (index in 0 until N64Bus.PIF_RAM_SIZE) {
            assertEquals(0xFF - index, ram.read8(0x200 + index))
        }
        assertEquals(0x200 + N64Bus.PIF_RAM_SIZE, bus.read32(N64Bus.SI_DRAM_ADDR_REG))
        assertEquals(N64Bus.SI_STATUS_INTERRUPT, bus.read32(N64Bus.SI_STATUS_REG) and N64Bus.SI_STATUS_INTERRUPT)
        assertEquals(0, bus.read32(N64Bus.SI_STATUS_REG) and (N64Bus.SI_STATUS_DMA_BUSY or N64Bus.SI_STATUS_IO_BUSY))
    }

    @Test(expected = IllegalArgumentException::class)
    fun siWriteDmaRejectsRdramRangeOutsideInstalledMemory() {
        val bus = N64Bus(ram = N64Memory(256))
        bus.write32(N64Bus.SI_DRAM_ADDR_REG, 0x100)
        bus.write32(N64Bus.SI_PIF_ADDR_WR64B_REG, N64Bus.PIF_RAM_BASE)
    }
}
