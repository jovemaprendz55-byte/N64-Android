package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Test

class N64BusPiRegisterTest {
    @Test
    fun detectsCic6102SeedFromIpl3Checksum() {
        val bus = N64Bus(cartridgeRom = romWithIpl3WordSum(0xD057C85244L))

        assertEquals(0x3F, bus.detectCicSeed())
    }

    @Test
    fun detectsCic6105SeedFromIpl3Checksum() {
        val bus = N64Bus(cartridgeRom = romWithIpl3WordSum(0x11A49F60E96L))

        assertEquals(0x91, bus.detectCicSeed())
    }

    @Test
    fun unknownIpl3ChecksumDoesNotGuessCicSeed() {
        val bus = N64Bus(cartridgeRom = ByteArray(0x1000))

        assertEquals(null, bus.detectCicSeed())
    }

    private fun romWithIpl3WordSum(targetSum: Long): ByteArray {
        val rom = ByteArray(0x1000)
        var remaining = targetSum
        for (offset in 0x40 until 0x1000 step 4) {
            val word = minOf(remaining, 0xFFFFFFFFL)
            rom[offset] = (word ushr 24).toByte()
            rom[offset + 1] = (word ushr 16).toByte()
            rom[offset + 2] = (word ushr 8).toByte()
            rom[offset + 3] = word.toByte()
            remaining -= word
        }
        assertEquals(0L, remaining)
        return rom
    }

    @Test
    fun spPcRegisterIsMappedAndKeepsAlignedRSPAddressBits() {
        val bus = N64Bus()

        bus.write32(N64Bus.SP_PC_REG, 0x1234)
        assertEquals(0x1234, bus.read32(N64Bus.SP_PC_REG))

        bus.write32(N64Bus.SP_PC_REG, 0xFFFFFFFF.toInt())
        assertEquals(N64Bus.SP_PC_MASK, bus.read32(N64Bus.SP_PC_REG))
    }

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
        bus.write32(N64Bus.PI_WR_LEN_REG, 1)

        assertEquals(0, bus.read32(N64Bus.PI_RD_LEN_REG))
        assertEquals(0, bus.read32(N64Bus.PI_WR_LEN_REG))
        assertEquals(0x12, bus.read8(0x100))
        assertEquals(0x34, bus.read8(0x101))
    }

    @Test
    fun piWriteDmaToReadOnlyRomIsIgnoredAndAdvancesAddressRegisters() {
        val ram = N64Memory(8 * 1024)
        ram.write32(0x100, 0x11223344)
        val bus = N64Bus(ram = ram, cartridgeRom = byteArrayOf(0x12, 0x34))

        bus.write32(N64Bus.PI_DRAM_ADDR_REG, 0x100)
        bus.write32(N64Bus.PI_CART_ADDR_REG, N64Bus.CARTRIDGE_ROM_BASE)
        // PI_RD_LEN is the RDRAM-to-cartridge direction; writing 3 requests four bytes.
        bus.write32(N64Bus.PI_RD_LEN_REG, 3)

        assertEquals(0x104, bus.read32(N64Bus.PI_DRAM_ADDR_REG))
        assertEquals(N64Bus.CARTRIDGE_ROM_BASE + 4, bus.read32(N64Bus.PI_CART_ADDR_REG))
        assertEquals(0, bus.read32(N64Bus.PI_RD_LEN_REG))
        assertEquals(0x11223344, ram.read32(0x100))
        assertEquals(0x12, bus.read8(N64Bus.CARTRIDGE_ROM_BASE))
        assertEquals(0x34, bus.read8(N64Bus.CARTRIDGE_ROM_BASE + 1))
    }
}
