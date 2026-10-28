package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class N64BusDmaTest {
    @Test
    fun piDmaCopiesCartridgeBytesIntoRdramAndAdvancesAddresses() {
        val rom = byteArrayOf(
            0x80.toByte(), 0x37, 0x12, 0x40,
            0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()
        )
        val ram = N64Memory(8 * 1024)
        val bus = N64Bus(ram = ram, cartridgeRom = rom)

        bus.write32(N64Bus.PI_DRAM_ADDR_REG, 0x100)
        bus.write32(N64Bus.PI_CART_ADDR_REG, N64Bus.CARTRIDGE_ROM_BASE)
        // PI_WR_LEN is length minus one: writing 3 requests four bytes.
        bus.write32(N64Bus.PI_WR_LEN_REG, 3)

        assertEquals(0x80, bus.read8(0x100))
        assertEquals(0x37, bus.read8(0x101))
        assertEquals(0x12, bus.read8(0x102))
        assertEquals(0x40, bus.read8(0x103))
        assertEquals(0x104, bus.read32(N64Bus.PI_DRAM_ADDR_REG))
        assertEquals(N64Bus.CARTRIDGE_ROM_BASE + 4, bus.read32(N64Bus.PI_CART_ADDR_REG))
    }

    @Test
    fun piDmaRejectsSourceThatRunsPastLoadedRom() {
        val bus = N64Bus(
            ram = N64Memory(8 * 1024),
            cartridgeRom = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        )
        bus.write32(N64Bus.PI_DRAM_ADDR_REG, 0x100)
        bus.write32(N64Bus.PI_CART_ADDR_REG, N64Bus.CARTRIDGE_ROM_BASE + 2)

        try {
            bus.write32(N64Bus.PI_WR_LEN_REG, 3)
            throw AssertionError("Expected PI DMA beyond the ROM to fail.")
        } catch (error: IllegalArgumentException) {
            assertEquals(
                "PI DMA source exceeds loaded cartridge ROM: offset=0x2, length=4.",
                error.message
            )
        }
    }

    @Test
    fun piDmaRejectsDestinationThatRunsPastRdramWithoutPartialCopy() {
        val ram = N64Memory(8 * 1024)
        val bus = N64Bus(ram = ram, cartridgeRom = byteArrayOf(0x11, 0x22, 0x33, 0x44))
        bus.write8(8190, 0x55)
        bus.write8(8191, 0x66)
        bus.write32(N64Bus.PI_DRAM_ADDR_REG, 8190)
        bus.write32(N64Bus.PI_CART_ADDR_REG, N64Bus.CARTRIDGE_ROM_BASE)

        try {
            bus.write32(N64Bus.PI_WR_LEN_REG, 3)
            throw AssertionError("Expected PI DMA outside RDRAM to fail.")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("destination is outside installed RDRAM"))
        }

        assertEquals(0x55, bus.read8(8190))
        assertEquals(0x66, bus.read8(8191))
    }

    @Test
    fun piDmaRejectsSourceOutsideCartridgeWindow() {
        val bus = N64Bus(ram = N64Memory(8 * 1024), cartridgeRom = byteArrayOf(1, 2, 3, 4))
        bus.write32(N64Bus.PI_DRAM_ADDR_REG, 0x100)
        bus.write32(N64Bus.PI_CART_ADDR_REG, N64Bus.CARTRIDGE_ROM_BASE - 4)

        try {
            bus.write32(N64Bus.PI_WR_LEN_REG, 3)
            throw AssertionError("Expected PI DMA source outside cartridge window to fail.")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("source is not in the cartridge ROM window"))
        }
    }
}
