package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Test

class N64RomInspectorTest {
    @Test fun readsTitleGameCodeCrcAndBootPreviewFromBigEndianRom() {
        val rom = ByteArray(0x60)
        "TEST GAME".toByteArray(Charsets.US_ASCII).copyInto(rom, 0x20)
        "ABCD".toByteArray(Charsets.US_ASCII).copyInto(rom, 0x3B)
        rom[0x10] = 0x12
        rom[0x11] = 0x34
        rom[0x12] = 0x56
        rom[0x13] = 0x78
        rom[0x14] = 0x01
        rom[0x15] = 0x23
        rom[0x16] = 0x45
        rom[0x17] = 0x67
        rom[0x40] = 0xDE.toByte()
        rom[0x41] = 0xAD.toByte()
        rom[0x42] = 0xBE.toByte()
        rom[0x43] = 0xEF.toByte()

        val info = N64RomInspector.inspect(rom)

        assertEquals("TEST GAME", info.title)
        assertEquals("ABCD", info.gameCode)
        assertEquals("0x12345678", info.crc1)
        assertEquals("0x01234567", info.crc2)
        assertEquals("0xDEADBEEF", info.bootCodePreview.first())
        assertEquals(8, info.bootCodePreview.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTooShortRomForInspection() {
        N64RomInspector.inspect(ByteArray(0x40))
    }
}
