package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

class N64RomDocumentReaderTest {
    private fun rom(): ByteArray = ByteArray(0x80).also {
        it[0] = 0x80.toByte()
        it[1] = 0x37
        it[2] = 0x12
        it[3] = 0x40
        it[8] = 0x80.toByte()
        it[10] = 0x04
        it[0x10] = 0x12
        it[0x11] = 0x34
        it[0x12] = 0x56
        it[0x13] = 0x78
        "TEST ROM".toByteArray(Charsets.US_ASCII).copyInto(it, 0x20)
    }

    @Test
    fun readsAndInspectsCanonicalRomFromStream() {
        val result = N64RomDocumentReader.read(ByteArrayInputStream(rom()))
        assertTrue(result is N64RomDocumentReader.Result.Success)
        val success = result as N64RomDocumentReader.Result.Success
        assertEquals(0x80, success.sourceSizeBytes)
        assertEquals("TEST ROM", success.cartridge.title)
        assertEquals(0x12345678L, success.cartridge.crc1)
    }

    @Test
    fun rejectsFileLargerThanConfiguredLimit() {
        val result = N64RomDocumentReader.read(ByteArrayInputStream(ByteArray(0x80)), maxBytes = 0x70)
        assertTrue(result is N64RomDocumentReader.Result.Failure)
        assertTrue((result as N64RomDocumentReader.Result.Failure).reason.contains("limite"))
    }

    @Test
    fun rejectsUnknownHeaderAndShortFile() {
        val unknown = N64RomDocumentReader.read(ByteArrayInputStream(ByteArray(0x80)))
        val short = N64RomDocumentReader.read(ByteArrayInputStream(ByteArray(0x3F)))
        assertTrue(unknown is N64RomDocumentReader.Result.Failure)
        assertTrue(short is N64RomDocumentReader.Result.Failure)
    }

    @Test
    fun reportsInputOutputFailure() {
        val broken = object : InputStream() {
            override fun read(): Int = throw IOException("falha simulada")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                throw IOException("falha simulada")
        }
        val result = N64RomDocumentReader.read(broken)
        assertTrue(result is N64RomDocumentReader.Result.Failure)
        assertTrue((result as N64RomDocumentReader.Result.Failure).reason.contains("falha simulada"))
    }
}
