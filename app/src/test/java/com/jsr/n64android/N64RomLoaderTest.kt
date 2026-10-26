package com.jsr.n64android

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class N64RomLoaderTest {
    @Test fun recognizesZ64HeaderWithoutChangingBytes() {
        val bytes = romWithHeader(0x80, 0x37, 0x12, 0x40)
        val result = N64RomLoader.load(bytes) as RomLoadResult.Success
        assertEquals(RomFormat.Z64_BIG_ENDIAN, result.rom.format)
        assertArrayEquals(bytes, result.rom.bytes)
    }

    @Test fun rejectsShortFiles() {
        assertTrue(N64RomLoader.load(byteArrayOf(0x80.toByte(), 0x37)) is RomLoadResult.Failure)
    }

    @Test fun rejectsUnknownHeader() {
        assertTrue(N64RomLoader.load(ByteArray(0x40)) is RomLoadResult.Failure)
    }

    @Test fun normalizesV64HalfwordOrder() {
        val result = N64RomLoader.load(romWithHeader(0x37, 0x80, 0x40, 0x12)) as RomLoadResult.Success
        assertEquals(0x80.toByte(), result.rom.bytes[0])
        assertEquals(0x37.toByte(), result.rom.bytes[1])
        assertEquals(0x12.toByte(), result.rom.bytes[2])
        assertEquals(0x40.toByte(), result.rom.bytes[3])
    }

    @Test fun normalizesN64WordOrder() {
        val result = N64RomLoader.load(romWithHeader(0x40, 0x12, 0x37, 0x80)) as RomLoadResult.Success
        assertEquals(0x80.toByte(), result.rom.bytes[0])
        assertEquals(0x37.toByte(), result.rom.bytes[1])
        assertEquals(0x12.toByte(), result.rom.bytes[2])
        assertEquals(0x40, result.rom.bytes[3].toInt() and 0xFF)
    }

    private fun romWithHeader(a: Int, b: Int, c: Int, d: Int): ByteArray =
        ByteArray(0x40).apply {
            this[0] = a.toByte()
            this[1] = b.toByte()
            this[2] = c.toByte()
            this[3] = d.toByte()
        }
}
