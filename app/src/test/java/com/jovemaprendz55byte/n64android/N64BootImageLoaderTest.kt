package com.jovemaprendz55byte.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class N64BootImageLoaderTest {
    @Test
    fun copiesAndVerifiesWholeBootRegionAndReportsRegion() {
        val bus = N64MemoryBus()
        val rom = ByteArray(N64BootImageLoader.BOOT_REGION_SIZE).also { bytes ->
            bytes[0] = 0x80.toByte()
            bytes[1] = 0x37
            bytes[2] = 0x12
            bytes[3] = 0x40
            bytes[0x3E] = 'E'.code.toByte()
            for (i in 0x40 until bytes.size) bytes[i] = (i * 17).toByte()
        }

        val result = N64BootImageLoader(bus).load(rom)

        assertEquals(N64BootImageLoader.BOOT_REGION_SIZE, result.bytesCopied)
        assertEquals(0xFC0, result.payloadBytes)
        assertEquals(N64BootImageLoader.IPL3_ENTRY_POINT, result.entryPoint)
        assertEquals(N64BootImageLoader.Region.NTSC, result.region)
        assertTrue(result.verificationPassed)
        for (offset in rom.indices) {
            assertEquals("byte at offset 0x%X".format(offset), rom[offset],
                bus.readByte(N64SpMemory.DMEM_BASE + offset))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsRomWithoutCompleteBootRegion() {
        N64BootImageLoader(N64MemoryBus()).load(ByteArray(0xFFF))
    }

    @Test
    fun unknownCountryIsReportedWithoutGuessingRegion() {
        val rom = ByteArray(N64BootImageLoader.BOOT_REGION_SIZE)
        rom[0x3E] = 'Z'.code.toByte()

        assertEquals(N64BootImageLoader.Region.UNKNOWN, N64BootImageLoader(N64MemoryBus()).load(rom).region)
    }
}
