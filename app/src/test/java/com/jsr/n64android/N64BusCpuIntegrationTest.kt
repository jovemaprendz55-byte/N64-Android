package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Test

class N64BusCpuIntegrationTest {
    @Test
    fun cpuFetchesAndExecutesInstructionsThroughSharedN64Bus() {
        val bus = N64Bus(ram = N64Memory(8 * 1024))
        // addiu $t0, $zero, 42; addiu $t1, $t0, 1
        bus.write32(0, (0x09 shl 26) or (8 shl 16) or 42)
        bus.write32(4, (0x09 shl 26) or (8 shl 21) or (9 shl 16) or 1)
        val cpu = R4300Cpu(bus)
        cpu.setProgramCounter(0xFFFFFFFF80000000UL.toLong())

        cpu.step()
        cpu.step()

        assertEquals(42L, cpu.gpr[8])
        assertEquals(43L, cpu.gpr[9])
        assertEquals(0xFFFFFFFF80000008UL.toLong(), cpu.pc)
        assertEquals(0L, cpu.cop0[12] and 0x2L)
    }

    @Test
    fun cpuCanFetchBigEndianInstructionBytesFromMappedCartridgeWindow() {
        // addiu $t0, $zero, 42, stored as big-endian cartridge bytes.
        val instruction = (0x09 shl 26) or (8 shl 16) or 42
        val rom = byteArrayOf(
            (instruction ushr 24).toByte(),
            (instruction ushr 16).toByte(),
            (instruction ushr 8).toByte(),
            instruction.toByte()
        )
        val bus = N64Bus(ram = N64Memory(8 * 1024), cartridgeRom = rom)
        val cpu = R4300Cpu(bus)
        // KSEG1 maps 0xB0000000 to physical cartridge base 0x10000000.
        cpu.setProgramCounter(0xFFFFFFFFB0000000UL.toLong())

        cpu.step()

        assertEquals(42L, cpu.gpr[8])
        assertEquals(0xFFFFFFFFB0000004UL.toLong(), cpu.pc)
    }

    @Test
    fun cartridgeFetchPastEndFailsExplicitlyAtUnmappedBusBoundary() {
        val bus = N64Bus(ram = N64Memory(8 * 1024), cartridgeRom = byteArrayOf(0, 0, 0, 0))
        val cpu = R4300Cpu(bus)
        cpu.setProgramCounter(0xFFFFFFFFB0000004UL.toLong())

        try {
            cpu.step()
            throw AssertionError("Expected an unmapped cartridge read to fail explicitly.")
        } catch (error: UnsupportedOperationException) {
            assertEquals("Cartridge read is beyond the loaded ROM size at 0x10000004.", error.message)
        }
    }
    @Test
    fun cpuExecutesCartridgeCodeAfterPiDmaCopiesItIntoRdram() {
        // addiu $t0, $zero, 42; addiu $t1, $t0, 1, encoded big-endian.
        val first = (0x09 shl 26) or (8 shl 16) or 42
        val second = (0x09 shl 26) or (8 shl 21) or (9 shl 16) or 1
        val rom = byteArrayOf(
            (first ushr 24).toByte(), (first ushr 16).toByte(),
            (first ushr 8).toByte(), first.toByte(),
            (second ushr 24).toByte(), (second ushr 16).toByte(),
            (second ushr 8).toByte(), second.toByte()
        )
        val bus = N64Bus(ram = N64Memory(8 * 1024), cartridgeRom = rom)
        bus.write32(N64Bus.PI_DRAM_ADDR_REG, 0x100)
        bus.write32(N64Bus.PI_CART_ADDR_REG, N64Bus.CARTRIDGE_ROM_BASE)
        bus.write32(N64Bus.PI_RD_LEN_REG, rom.size - 1)

        val cpu = R4300Cpu(bus)
        cpu.setProgramCounter(0xFFFFFFFF80000100UL.toLong())
        cpu.step()
        cpu.step()

        assertEquals(42L, cpu.gpr[8])
        assertEquals(43L, cpu.gpr[9])
        assertEquals(0xFFFFFFFF80000108UL.toLong(), cpu.pc)
    }

}
