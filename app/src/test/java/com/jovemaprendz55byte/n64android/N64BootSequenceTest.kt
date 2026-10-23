package com.jovemaprendz55byte.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class N64BootSequenceTest {
    @Test
    fun loadIpl3CopiesEntireBootRegionToDmem() {
        val bus = N64MemoryBus()
        val boot = N64BootSequence(bus)
        val rom = ByteArray(0x1000) { index ->
            ((index * 37 + 11) and 0xFF).toByte()
        }
        rom[0x3E] = 'J'.code.toByte()
        val original = rom.copyOf()
        bus.writeByte(N64SpMemory.IMEM_BASE, 0x5A.toByte())

        val image = boot.loadIpl3(rom)

        assertEquals(0x1000, image.byteCount)
        assertEquals(0xFC0, image.ipl3PayloadByteCount)
        assertEquals(0xFFFF_FFFF_A400_0040UL.toLong(), image.entryPoint)
        assertEquals(N64BootSequence.Region.NTSC, image.region)

        // Check header start/end, IPL3 payload start, and final DMEM byte.
        assertEquals(rom[0].toInt() and 0xFF, bus.readByte(N64SpMemory.DMEM_BASE).toInt() and 0xFF)
        assertEquals(rom[0x3F].toInt() and 0xFF, bus.readByte(N64SpMemory.DMEM_BASE + 0x3FL).toInt() and 0xFF)
        assertEquals(rom[0x40].toInt() and 0xFF, bus.readByte(N64SpMemory.DMEM_BASE + 0x40L).toInt() and 0xFF)
        assertEquals(rom[0xFFF].toInt() and 0xFF, bus.readByte(N64SpMemory.DMEM_BASE + 0xFFFL).toInt() and 0xFF)

        for (offset in 0 until 0x1000) {
            assertEquals("DMEM mismatch at offset 0x${offset.toString(16)}", rom[offset],
                bus.readByte(N64SpMemory.DMEM_BASE + offset))
        }

        // The write ends at DMEM+0xFFF; the first IMEM byte is not overwritten.
        assertEquals(0x5A, bus.readByte(N64SpMemory.IMEM_BASE).toInt() and 0xFF)
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
        assertEquals(0x1000, image.byteCount)
        assertEquals(0xFC0, image.ipl3PayloadByteCount)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsRomWithoutCompleteBootRegion() {
        N64BootSequence(N64MemoryBus()).loadIpl3(ByteArray(0xFFF))
    }
}
