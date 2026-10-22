package com.jovemaprendz55byte.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class N64BootSequenceTest {
    @Test
    fun copiesCartridgeIpl3BytesToSpDmemWithoutChangingSourceRom() {
        val bus = N64MemoryBus()
        val boot = N64BootSequence(bus)
        val rom = ByteArray(0x1000) { (it and 0xFF).toByte() }
        rom[0x3E] = 'J'.code.toByte()
        val original = rom.copyOf()

        val image = boot.loadIpl3(rom)

        assertEquals(0xFC0, image.byteCount)
        assertEquals(0xFFFF_FFFF_A400_0040UL.toLong(), image.entryPoint)
        assertEquals(N64BootSequence.Region.NTSC, image.region)
        for (offset in 0x40 until 0x1000) {
            assertEquals(rom[offset], bus.readByte(0x0400_0000L + offset))
        }
        assertTrue(rom.contentEquals(original))
    }

    @Test
    fun unknownCountryDoesNotPretendToIdentifyCicOrRegion() {
        val boot = N64BootSequence(N64MemoryBus())
        val rom = ByteArray(0x1000)
        rom[0x3E] = 'Z'.code.toByte()

        val image = boot.loadIpl3(rom)

        assertEquals(N64BootSequence.Region.UNKNOWN, image.region)
        assertEquals('Z'.code, image.countryCode)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsRomWithoutCompleteIpl3Image() {
        N64BootSequence(N64MemoryBus()).loadIpl3(ByteArray(0xFFF))
    }
}
