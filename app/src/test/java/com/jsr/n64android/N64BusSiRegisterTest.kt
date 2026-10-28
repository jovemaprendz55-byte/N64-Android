package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Test

class N64BusSiRegisterTest {
    @Test
    fun siAddressRegistersStoreHardwareWidthValues() {
        val bus = N64Bus()

        bus.write32(N64Bus.SI_DRAM_ADDR_REG, 0xFF123456.toInt())
        bus.write32(N64Bus.SI_PIF_ADDR_RD64B_REG, 0xFFFFFFFF.toInt())
        bus.write32(N64Bus.SI_PIF_ADDR_WR64B_REG, 0xE00007C0.toInt())

        assertEquals(0x00123456, bus.read32(N64Bus.SI_DRAM_ADDR_REG))
        assertEquals(0x1FFFFFFF, bus.read32(N64Bus.SI_PIF_ADDR_RD64B_REG))
        assertEquals(0x000007C0, bus.read32(N64Bus.SI_PIF_ADDR_WR64B_REG))
    }

    @Test
    fun siStatusRegisterCanBeReadAndInterruptClearWriteIsAccepted() {
        val bus = N64Bus()

        assertEquals(0, bus.read32(N64Bus.SI_STATUS_REG))
        bus.write32(N64Bus.SI_STATUS_REG, N64Bus.SI_STATUS_INTERRUPT)
        assertEquals(0, bus.read32(N64Bus.SI_STATUS_REG))
    }
}
