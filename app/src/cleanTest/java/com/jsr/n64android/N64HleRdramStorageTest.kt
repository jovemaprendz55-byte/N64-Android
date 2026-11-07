package com.jsr.n64android

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class N64HleRdramStorageTest {
    @Test
    fun byteArrayStoragePreservesRawBytesAndCopiesReads() {
        val storage = N64HleByteArrayRdramStorage(32)
        storage.writeBytes(4, byteArrayOf(0x80.toByte(), 0x12, 0x34, 0xFF.toByte()))

        assertEquals(0x80, storage.readByte(4))
        assertArrayEquals(
            byteArrayOf(0x80.toByte(), 0x12, 0x34, 0xFF.toByte()),
            storage.readBytes(4, 4)
        )

        val copy = storage.readBytes(4, 4)
        copy[0] = 0
        assertEquals(0x80, storage.readByte(4))
    }

    @Test
    fun byteArrayStorageWritesSingleByteWithUnsignedValidation() {
        val storage = N64HleByteArrayRdramStorage(8)
        storage.writeByte(7, 0xFE)

        assertEquals(0xFE, storage.readByte(7))
        assertThrows(IllegalArgumentException::class.java) {
            storage.writeByte(0, 0x100)
        }
    }

    @Test
    fun byteArrayStorageAcceptsEmptyRangeAtEnd() {
        val storage = N64HleByteArrayRdramStorage(8)

        assertArrayEquals(byteArrayOf(), storage.readBytes(8, 0))
        storage.writeBytes(8, byteArrayOf())
    }

    @Test
    fun byteArrayStorageRejectsNegativeAndOverflowingRanges() {
        val storage = N64HleByteArrayRdramStorage(8)

        assertThrows(IllegalArgumentException::class.java) {
            storage.readBytes(-1, 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            storage.readBytes(7, 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            storage.writeBytes(9, byteArrayOf())
        }
    }

    @Test
    fun byteArrayStorageRejectsNonPositiveCapacity() {
        assertThrows(IllegalArgumentException::class.java) {
            N64HleByteArrayRdramStorage(0)
        }
    }
}
