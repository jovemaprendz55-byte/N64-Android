package com.jsr.n64android

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

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

    @Test fun rejectsNonWordAlignedRomSize() {
        val bytes = romWithHeader(0x80, 0x37, 0x12, 0x40) + byteArrayOf(1)
        assertTrue(N64RomLoader.load(bytes) is RomLoadResult.Failure)
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

    @Test fun boundedStreamReaderAcceptsExactLimit() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        assertArrayEquals(bytes, N64RomLoader.readAllBytes(ByteArrayInputStream(bytes), 4))
    }

    @Test fun boundedStreamReaderRejectsDataBeyondLimit() {
        val input = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5))
        try {
            N64RomLoader.readAllBytes(input, 4)
            throw AssertionError("Expected IOException for oversized ROM")
        } catch (_: IOException) {
            // Expected: the fifth byte must not be buffered.
        }
    }

    @Test fun boundedStreamReaderHandlesTemporaryZeroLengthReads() {
        val delegate = ByteArrayInputStream(byteArrayOf(9, 8, 7))
        val intermittent = object : InputStream() {
            private var returnZero = true
            override fun read(): Int = delegate.read()
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (returnZero) { returnZero = false; return 0 }
                return delegate.read(buffer, offset, length)
            }
        }
        assertArrayEquals(byteArrayOf(9, 8, 7), N64RomLoader.readAllBytes(intermittent, 3))
    }
    private fun romWithHeader(a: Int, b: Int, c: Int, d: Int): ByteArray =
        ByteArray(0x40).apply {
            this[0] = a.toByte()
            this[1] = b.toByte()
            this[2] = c.toByte()
            this[3] = d.toByte()
        }
}
