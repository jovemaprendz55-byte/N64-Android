package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Test

class N64BusPiRegisterTest {
    @Test
    fun piDramAddressRegisterKeepsOnlyLow24Bits() {
        val bus = N64Bus(ram = N64Memory(8 * 1024), cartridgeRom = byteArrayOf(0x11))

        bus.write32(N64Bus.PI_DRAM_ADDR_REG, 0xAB123456.toInt())

        assertEquals(0x00123456, bus.read32(N64Bus.PI_DRAM_ADDR_REG))
    }

    @Test
    fun piLengthRegistersReadAsZeroAfterImmediateDmaCompletes() {
        val bus = N64Bus(
            ram = N64Memory(8 * 1024),
            cartridgeRom = byteArrayOf(0x12, 0x34)
        )
        bus.write32(N64Bus.PI_DRAM_ADDR_REG, 0x100)
        bus.write32(N64Bus.PI_CART_ADDR_REG, N64Bus.CARTRIDGE_ROM_BASE)
        bus.write32(N64Bus.PI_RD_LEN_REG, 1)

        assertEquals(0, bus.read32(N64Bus.PI_RD_LEN_REG))
        assertEquals(0, bus.read32(N64Bus.PI_WR_LEN_REG))
        assertEquals(0x12, bus.read8(0x100))
        assertEquals(0x34, bus.read8(0x101))
    }

    @Test
    fun piWriteDmaFailsExplicitlyWhileCartridgeIsReadOnly() {
        val bus = N64Bus(ram = N64Memory(8 * 1024), cartridgeRom = byteArrayOf(0x12, 0x34))

        try {
            bus.write32(N64Bus.PI_WR_LEN_REG, 0)
            throw AssertionError("Expected PI RAM-to-cartridge DMA to be unsupported.")
        } catch (error: UnsupportedOperationException) {
            assertEquals(
                "PI RAM-to-cartridge DMA is unsupported because cartridge ROM is read-only.",
                error.message
            )
        }
    }
}
