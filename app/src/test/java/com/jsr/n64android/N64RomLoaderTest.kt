package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class N64RomLoaderTest {
    @Test
    fun recognizesZ64Header() {
        val bytes = ByteArray(0x40)
        bytes[0] = 0x80.toByte()
        bytes[1] = 0x37
        bytes[2] = 0x12
        bytes[3] = 0x40
        val result = N64RomLoader.load(bytes)
        assertTrue(result is RomLoadResult.Success)
        assertEquals(RomFormat.Z64_BIG_ENDIAN, (result as RomLoadResult.Success).rom.format)
    }

    @Test
    fun rejectsShortFiles() {
        assertTrue(N64RomLoader.load(byteArrayOf(0x80.toByte(), 0x37)) is RomLoadResult.Failure)
    }

    @Test
    fun normalizesV64HalfwordOrder() {
        val bytes = ByteArray(0x40)
        bytes[0] = 0x37
        bytes[1] = 0x80.toByte()
        bytes[2] = 0x40
        bytes[3] = 0x12
        val result = N64RomLoader.load(bytes) as RomLoadResult.Success
        assertEquals(0x80.toByte(), result.rom.bytes[0])
        assertEquals(0x37.toByte(), result.rom.bytes[1])
    }

    @Test
    fun normalizesN64WordOrder() {
        val bytes = ByteArray(0x40)
        bytes[0] = 0x40
        bytes[1] = 0x12
        bytes[2] = 0x37
        bytes[3] = 0x80.toByte()
        val result = N64RomLoader.load(bytes) as RomLoadResult.Success
        assertEquals(0x80.toByte(), result.rom.bytes[0])
        assertEquals(0x37.toByte(), result.rom.bytes[1])
        assertEquals(0x12.toByte(), result.rom.bytes[2])
        assertEquals(0x40, result.rom.bytes[3].toInt() and 0xFF)
    }
}
