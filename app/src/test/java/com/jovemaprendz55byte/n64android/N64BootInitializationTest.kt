package com.jovemaprendz55byte.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class N64BootInitializationTest {
    @Test
    fun preflightChecksRdramAndReportsGameChecksumWithoutBypassingIt() {
        val bus = N64MemoryBus()
        val cpu = R4300Cpu(bus)
        val originalProbeWord = bus.readWord(0x3FCL)

        val result = N64BootInitialization(bus, cpu).prepareCic6105(validRom())

        assertEquals(0xFC0, result.ipl3Bytes)
        assertEquals(0x1000, result.bootRegionBytes)
        assertEquals(0x100000, result.gameSegmentBytes)
        assertEquals(R4300Cpu.DEFAULT_RDRAM_SIZE, result.rdramCapacityBytes)
        assertTrue(result.rdramModelReady)
        assertEquals(originalProbeWord, bus.readWord(0x3FCL))
        assertEquals(0xFFFF_FFFF_A400_0040UL.toLong(), cpu.pc)
        // This synthetic ROM does not contain a valid retail checksum. The
        // preflight must report that instead of pretending boot succeeded.
        assertFalse(result.checksumMatches)
    }

    @Test(expected = IllegalArgumentException::class)
    fun preflightRejectsRomTooSmallToContainIpl3() {
        N64BootInitialization(N64MemoryBus(), R4300Cpu()).prepareCic6105(validRom(0x0FFC))
    }

    private fun validRom(size: Int = 0x101000): ByteArray = ByteArray(size).also { rom ->
        rom[0] = 0x80.toByte()
        rom[1] = 0x37
        rom[2] = 0x12
        rom[3] = 0x40
        rom[0x3E] = 'E'.code.toByte()
        for (offset in 0x40 until minOf(0x1000, rom.size)) {
            rom[offset] = (offset and 0xFF).toByte()
        }
    }
}
