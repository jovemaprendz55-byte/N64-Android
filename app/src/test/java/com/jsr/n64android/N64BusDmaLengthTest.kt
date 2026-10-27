package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Test

class N64BusDmaLengthTest {
    @Test
    fun zeroLengthRegisterValueCopiesExactlyOneByte() {
        val bus = N64Bus(
            ram = N64Memory(8 * 1024),
            cartridgeRom = byteArrayOf(0xA5.toByte(), 0x5A)
        )
        bus.write32(N64Bus.PI_DRAM_ADDR_REG, 0x200)
        bus.write32(N64Bus.PI_CART_ADDR_REG, N64Bus.CARTRIDGE_ROM_BASE)

        // PI_RD_LEN encodes byte count minus one, so zero means one byte.
        bus.write32(N64Bus.PI_RD_LEN_REG, 0)

        assertEquals(0xA5, bus.read8(0x200))
        assertEquals(0, bus.read8(0x201))
        assertEquals(0x201, bus.read32(N64Bus.PI_DRAM_ADDR_REG))
        assertEquals(N64Bus.CARTRIDGE_ROM_BASE + 1, bus.read32(N64Bus.PI_CART_ADDR_REG))
    }

    @Test
    fun consecutiveDmaReadsContinueFromAdvancedCartridgeAddress() {
        val bus = N64Bus(
            ram = N64Memory(8 * 1024),
            cartridgeRom = byteArrayOf(0x10, 0x20, 0x30, 0x40)
        )
        bus.write32(N64Bus.PI_DRAM_ADDR_REG, 0x300)
        bus.write32(N64Bus.PI_CART_ADDR_REG, N64Bus.CARTRIDGE_ROM_BASE)
        bus.write32(N64Bus.PI_RD_LEN_REG, 0)
        bus.write32(N64Bus.PI_RD_LEN_REG, 0)

        assertEquals(0x10, bus.read8(0x300))
        assertEquals(0x20, bus.read8(0x301))
        assertEquals(0x302, bus.read32(N64Bus.PI_DRAM_ADDR_REG))
        assertEquals(N64Bus.CARTRIDGE_ROM_BASE + 2, bus.read32(N64Bus.PI_CART_ADDR_REG))
    }
}
