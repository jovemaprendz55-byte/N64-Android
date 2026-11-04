package com.jsr.n64android

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class N64HleCartridgeImageTest {
    private fun canonicalRom(): ByteArray = ByteArray(0x80).also { rom ->
        rom[0] = 0x80.toByte()
        rom[1] = 0x37
        rom[2] = 0x12
        rom[3] = 0x40
        rom[4] = 0x00
        rom[5] = 0x00
        rom[6] = 0x00
        rom[7] = 0x0F
        rom[8] = 0x80.toByte()
        rom[9] = 0x00
        rom[10] = 0x04
        rom[11] = 0x00
        rom[0x10] = 0x12
        rom[0x11] = 0x34
        rom[0x12] = 0x56
        rom[0x13] = 0x78
        rom[0x14] = 0x9A.toByte()
        rom[0x15] = 0xBC.toByte()
        rom[0x16] = 0xDE.toByte()
        rom[0x17] = 0xF0.toByte()
        "GOLDENEYE 007".toByteArray(Charsets.US_ASCII).copyInto(rom, 0x20)
        rom[0x3C] = 'N'.code.toByte()
        rom[0x3D] = 'G'.code.toByte()
        rom[0x3E] = 'E'.code.toByte()
    }

    private fun swapPairs(input: ByteArray): ByteArray =
        input.copyOf().also { out ->
            var i = 0
            while (i < out.size) {
                val v = out[i]
                out[i] = out[i + 1]
                out[i + 1] = v
                i += 2
            }
        }

    private fun reverseWords(input: ByteArray): ByteArray =
        input.copyOf().also { out ->
            var i = 0
            while (i < out.size) {
                out[i] = input[i + 3]
                out[i + 1] = input[i + 2]
                out[i + 2] = input[i + 1]
                out[i + 3] = input[i]
                i += 4
            }
        }

    @Test
    fun parsesBigEndianHeaderWithoutExecutingBootcode() {
        val rom = canonicalRom()
        val image = N64HleCartridgeImage.parse(rom)

        assertEquals(N64HleCartridgeImage.ByteOrder.BIG_ENDIAN, image.byteOrder)
        assertEquals(0x80000400L, image.entryPoint)
        assertEquals(0x12345678L, image.crc1)
        assertEquals(0x9ABCDEF0L, image.crc2)
        assertEquals("GOLDENEYE 007", image.title)
        assertEquals("NG", image.cartridgeId)
        assertEquals('E'.code, image.countryCode)
        assertArrayEquals(rom, image.copyCanonicalBytes())
    }

    @Test
    fun normalizesByteSwappedAndLittleEndianImages() {
        val canonical = canonicalRom()
        val byteSwapped = N64HleCartridgeImage.parse(swapPairs(canonical))
        val littleEndian = N64HleCartridgeImage.parse(reverseWords(canonical))

        assertEquals(N64HleCartridgeImage.ByteOrder.BYTE_SWAPPED_16, byteSwapped.byteOrder)
        assertEquals(N64HleCartridgeImage.ByteOrder.LITTLE_ENDIAN_32, littleEndian.byteOrder)
        assertArrayEquals(canonical, byteSwapped.copyCanonicalBytes())
        assertArrayEquals(canonical, littleEndian.copyCanonicalBytes())
    }

    @Test
    fun returnedDataIsDefensiveAndRangeReadsAreBounded() {
        val source = canonicalRom()
        val image = N64HleCartridgeImage.parse(source)
        source[0] = 0
        val copy = image.copyCanonicalBytes()
        copy[0] = 0

        assertEquals(0x80, image.readBytes(0, 1)[0].toInt() and 0xFF)
        assertThrows(IllegalArgumentException::class.java) { image.readBytes(-1, 1) }
        assertThrows(IllegalArgumentException::class.java) { image.readBytes(0x7F, 2) }
    }

    @Test
    fun rejectsShortUnknownAndMisalignedImages() {
        assertThrows(IllegalArgumentException::class.java) {
            N64HleCartridgeImage.parse(ByteArray(0x3F))
        }
        assertThrows(IllegalArgumentException::class.java) {
            N64HleCartridgeImage.parse(ByteArray(0x40))
        }
        assertThrows(IllegalArgumentException::class.java) {
            N64HleCartridgeImage.parse(canonicalRom().copyOf(0x7F))
        }
    }
}