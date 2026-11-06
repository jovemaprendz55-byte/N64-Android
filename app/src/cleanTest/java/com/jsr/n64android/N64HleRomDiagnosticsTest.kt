package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class N64HleRomDiagnosticsTest {
    private fun validCartridge(): N64HleCartridgeImage {
        val rom = ByteArray(0x1000 + 0x100000)
        rom[0] = 0x80.toByte()
        rom[1] = 0x37
        rom[2] = 0x12
        rom[3] = 0x40
        putWord(rom, 0x08, 0x80000400.toInt())
        putWord(rom, 0x10, 0xF6CC53D6.toInt())
        putWord(rom, 0x14, 0x5198A84B)
        "GOLDENEYE 007".toByteArray(Charsets.US_ASCII).copyInto(rom, 0x20)
        rom[0x3C] = 'N'.code.toByte()
        rom[0x3D] = 'G'.code.toByte()
        rom[0x3E] = 'E'.code.toByte()

        for (word in 0 until 208) putWord(rom, 0x40 + word * 4, -1)
        putWord(rom, 0x40 + 208 * 4, 0x57C85314)
        for (i in 0 until 0x100000) {
            rom[0x1000 + i] = ((i * 13 + 7) and 0xFF).toByte()
        }
        return N64HleCartridgeImage.parse(rom)
    }

    @Test
    fun reportsHeaderIpl3CicAndMatchingChecksumsWithoutBooting() {
        val cartridge = validCartridge()
        val before = cartridge.copyCanonicalBytes()

        val report = N64HleRomDiagnostics.diagnose(cartridge)

        assertEquals(N64HleRomDiagnostics.Status.VALID, report.status)
        assertEquals("GOLDENEYE 007", report.title)
        assertEquals("NG", report.cartridgeId)
        assertEquals('E'.code, report.countryCode)
        assertEquals(0x80000400L, report.entryPoint)
        assertEquals(N64HleRomDiagnostics.CicProfile.CIC_6102_7101, report.cicProfile)
        assertEquals(0x3F, report.cicSeed)
        assertEquals(0xD057C85244L, report.ipl3WordSum)
        assertNotNull(report.checksum)
        assertEquals(0xF6CC53D6L, report.checksum?.calculatedCrc1)
        assertEquals(0x5198A84BL, report.checksum?.calculatedCrc2)
        assertEquals(N64HleRomDiagnostics.Status.VALID, report.checksum?.status)
        org.junit.Assert.assertArrayEquals(before, cartridge.copyCanonicalBytes())
    }

    @Test
    fun reportsHeaderChecksumMismatchSeparatelyFromUnknownCic() {
        val bytes = validCartridge().copyCanonicalBytes()
        putWord(bytes, 0x10, 0x01020304)
        val report = N64HleRomDiagnostics.diagnose(N64HleCartridgeImage.parse(bytes))

        assertEquals(N64HleRomDiagnostics.Status.DIVERGENT, report.status)
        assertEquals(N64HleRomDiagnostics.Status.DIVERGENT, report.checksum?.status)
        assertEquals(0x01020304L, report.checksum?.headerCrc1)
        assertEquals(0xF6CC53D6L, report.checksum?.calculatedCrc1)
    }

    @Test
    fun reportsUnknownIpl3SignatureAsUnsupportedWithoutGuessingFromTitleOrRegion() {
        val bytes = validCartridge().copyCanonicalBytes()
        bytes[0x40] = (bytes[0x40].toInt() xor 1).toByte()
        val report = N64HleRomDiagnostics.diagnose(N64HleCartridgeImage.parse(bytes))

        assertEquals(N64HleRomDiagnostics.Status.UNSUPPORTED, report.status)
        assertEquals(N64HleRomDiagnostics.CicProfile.UNKNOWN, report.cicProfile)
        assertNull(report.cicSeed)
        assertNull(report.checksum?.calculatedCrc1)
    }

    @Test
    fun reportsInsufficientGameImageAfterRecognizingIpl3Signature() {
        val bytes = validCartridge().copyCanonicalBytes().copyOf(0x1000)
        val report = N64HleRomDiagnostics.diagnose(N64HleCartridgeImage.parse(bytes))

        assertEquals(N64HleRomDiagnostics.Status.INSUFFICIENT_DATA, report.status)
        assertEquals(N64HleRomDiagnostics.CicProfile.CIC_6102_7101, report.cicProfile)
        assertEquals(0x3F, report.cicSeed)
        assertEquals(0xD057C85244L, report.ipl3WordSum)
        assertNull(report.checksum?.calculatedCrc1)
    }

    @Test
    fun reportsInsufficientIpl3DataWithoutAttemptingChecksum() {
        val bytes = validCartridge().copyCanonicalBytes().copyOf(0x80)
        val report = N64HleRomDiagnostics.diagnose(N64HleCartridgeImage.parse(bytes))

        assertEquals(N64HleRomDiagnostics.Status.INSUFFICIENT_DATA, report.status)
        assertEquals(N64HleRomDiagnostics.CicProfile.INSUFFICIENT_DATA, report.cicProfile)
        assertNull(report.ipl3WordSum)
        assertNull(report.checksum?.calculatedCrc1)
    }

    private fun putWord(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }
}
