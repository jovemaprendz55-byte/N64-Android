package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Test

class N64BusMiRegisterTest {
    @Test
    fun miInterruptMaskSupportsPairedClearAndSetCommands() {
        val bus = N64Bus(ram = N64Memory(8 * 1024))

        assertEquals(0, bus.read32(N64Bus.MI_INTR_MASK_REG))
        bus.write32(N64Bus.MI_INTR_MASK_REG, (1 shl 1) or (1 shl 5) or (1 shl 11))
        assertEquals((1 shl 0) or (1 shl 2) or (1 shl 5), bus.read32(N64Bus.MI_INTR_MASK_REG))

        // Clear SP and DP; set VI.
        bus.write32(N64Bus.MI_INTR_MASK_REG, (1 shl 0) or (1 shl 10) or (1 shl 7))
        assertEquals((1 shl 2) or (1 shl 3) or (1 shl 5), bus.read32(N64Bus.MI_INTR_MASK_REG))
    }

    @Test
    fun miVersionAndInterruptRegistersAreReadable() {
        val bus = N64Bus(ram = N64Memory(8 * 1024))

        assertEquals(N64Bus.MI_VERSION_VALUE, bus.read32(N64Bus.MI_VERSION_REG))
        assertEquals(0, bus.read32(N64Bus.MI_INTR_REG))
    }

    @Test
    fun miInitModeAcceptsWriteOneCommands() {
        val bus = N64Bus(ram = N64Memory(8 * 1024))

        bus.write32(N64Bus.MI_INIT_MODE_REG, N64Bus.MI_INIT_MODE_SET_INIT or N64Bus.MI_INIT_MODE_SET_EBUS)
        assertEquals(N64Bus.MI_INIT_MODE_INIT or N64Bus.MI_INIT_MODE_EBUS, bus.read32(N64Bus.MI_INIT_MODE_REG))

        bus.write32(N64Bus.MI_INIT_MODE_REG, N64Bus.MI_INIT_MODE_CLR_INIT)
        assertEquals(N64Bus.MI_INIT_MODE_EBUS, bus.read32(N64Bus.MI_INIT_MODE_REG))
    }
}
