package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
    fun cartridgeFetchPastEndIsReportedAsCpuAddressException() {
        val bus = N64Bus(ram = N64Memory(8 * 1024), cartridgeRom = byteArrayOf(0, 0, 0, 0))
        val cpu = R4300Cpu(bus)
        cpu.setProgramCounter(0xFFFFFFFFB0000004UL.toLong())

        cpu.step()

        assertEquals(4L, (cpu.cop0[13] ushr 2) and 0x1FL)
        assertEquals(0xFFFFFFFFB0000004UL.toLong(), cpu.cop0[14])
    }
}
