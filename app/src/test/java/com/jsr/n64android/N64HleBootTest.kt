package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class N64HleBootTest {
    @Test
    fun preparesGameBootcodeWithoutExecutingIpl3OrUsingPifRom() {
        val rom = ByteArray(0x2000)
        // Synthetic IPL3 checksum for CIC 6102/7101: 0xD057C85244.
        for (word in 0 until 208) putWord(rom, 0x40 + word * 4, 0xFFFFFFFF.toInt())
        putWord(rom, 0x40 + 208 * 4, 0x57C85314)
        for (i in 0 until N64HleBoot.BOOTCODE_SIZE) {
            rom[N64HleBoot.BOOTCODE_ROM_OFFSET + i] = ((i * 13 + 7) and 0xFF).toByte()
        }

        val bus = N64Bus(ram = N64Memory(), pifRom = null, cartridgeRom = rom)
        val cpu = R4300Cpu(bus)
        val result = N64HleBoot.prepare(bus, cpu)

        assertEquals(0x3F, result.cicSeed)
        assertEquals(N64HleBoot.BOOTCODE_SIZE, result.copiedBytes)
        assertEquals(N64HleBoot.BOOTCODE_ROM_OFFSET, result.romOffset)
        assertEquals(N64HleBoot.BOOTCODE_RAM_ADDRESS, result.ramAddress)
        assertEquals(N64HleBoot.ENTRY_PC, cpu.pc)
        assertEquals(N64HleBoot.INITIAL_STACK, cpu.gpr[29])
        assertEquals(0x3FL, cpu.gpr[22])
        assertFalse(result.pifRomUsed)

        for (i in 0 until N64HleBoot.BOOTCODE_SIZE) {
            assertEquals(rom[N64HleBoot.BOOTCODE_ROM_OFFSET + i].toInt() and 0xFF,
                bus.peek8ForDiagnostic(N64HleBoot.BOOTCODE_RAM_ADDRESS + i))
        }
        // IPL3 remains untouched in SP DMEM: HLE did not stage or execute it.
        assertEquals(0, bus.read8(0x04000040))
    }

    @Test
    fun rejectsUnknownCicBeforeChangingCpuState() {
        val rom = ByteArray(0x2000)
        val bus = N64Bus(cartridgeRom = rom)
        val cpu = R4300Cpu(bus)
        cpu.gpr[8] = 123L

        try {
            N64HleBoot.prepare(bus, cpu)
            throw AssertionError("Unknown CIC should be rejected.")
        } catch (error: IllegalArgumentException) {
            assertEquals(123L, cpu.gpr[8])
            assertNotEquals(N64HleBoot.ENTRY_PC, cpu.pc)
        }
    }

    private fun putWord(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }
}
