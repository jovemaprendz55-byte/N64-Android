package com.jovemaprendz55byte.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RomHeaderTest {
    @Test fun recognizesBigEndianZ64Header() {
        val bytes = validHeader(0x80, 0x37, 0x12, 0x40)
        "TEST GAME".toByteArray().copyInto(bytes, 0x20)
        bytes[0x3E] = 'E'.code.toByte()
        val header = RomHeader.parse(bytes)
        assertEquals(RomHeader.Format.Z64_BIG_ENDIAN, header?.format)
        assertEquals("TEST GAME", header?.title)
        assertEquals('E', header?.countryCode)
    }

    @Test fun recognizesByteSwappedV64Header() {
        assertEquals(RomHeader.Format.V64_BYTE_SWAPPED,
            RomHeader.parse(validHeader(0x37, 0x80, 0x40, 0x12))?.format)
    }

    @Test fun recognizesLittleEndianN64Header() {
        assertEquals(RomHeader.Format.N64_LITTLE_ENDIAN,
            RomHeader.parse(validHeader(0x40, 0x12, 0x37, 0x80))?.format)
    }

    @Test fun readsTitleAndCountryFromByteSwappedV64Header() {
        val canonical = validHeader(0x80, 0x37, 0x12, 0x40)
        "TEST GAME".toByteArray().copyInto(canonical, 0x20)
        canonical[0x3E] = 'E'.code.toByte()
        val v64 = ByteArray(canonical.size) { index -> canonical[index xor 1] }

        val header = RomHeader.parse(v64)
        assertEquals("TEST GAME", header?.title)
        assertEquals('E', header?.countryCode)
    }

    @Test fun readsTitleAndCountryFromLittleEndianN64Header() {
        val canonical = validHeader(0x80, 0x37, 0x12, 0x40)
        "TEST GAME".toByteArray().copyInto(canonical, 0x20)
        canonical[0x3E] = 'E'.code.toByte()
        val n64 = ByteArray(canonical.size) { index ->
            canonical[(index and -4) + (3 - (index and 3))]
        }

        val header = RomHeader.parse(n64)
        assertEquals("TEST GAME", header?.title)
        assertEquals('E', header?.countryCode)
    }

    @Test fun rejectsShortOrUnknownHeaders() {
        assertNull(RomHeader.parse(ByteArray(20)))
        assertNull(RomHeader.parse(ByteArray(0x40)))
    }

    private fun validHeader(a: Int, b: Int, c: Int, d: Int) = ByteArray(0x40).apply {
        this[0] = a.toByte(); this[1] = b.toByte()
        this[2] = c.toByte(); this[3] = d.toByte()
    }
}
