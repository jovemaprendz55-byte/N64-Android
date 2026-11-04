package com.jsr.n64android

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class N64HleGuestAddressSpaceTest {
    @Test
    fun readsAndWritesBigEndianValuesAcrossAccessWidths() {
        val memory = N64HleGuestAddressSpace()
        memory.write8(0x80000100L, 0xAB)
        memory.write16(0x80000102L, 0xCDEF)
        memory.write32(0x80000104L, 0x12345678L)
        memory.write64(0x80000108L, 0x0123456789ABCDEFL)

        assertEquals(0xAB, memory.read8(0x80000100L))
        assertEquals(0xCDEF, memory.read16(0x80000102L))
        assertEquals(0x12345678L, memory.read32(0x80000104L))
        assertEquals(0x0123456789ABCDEFL, memory.read64(0x80000108L))
        assertArrayEquals(
            byteArrayOf(0x12, 0x34, 0x56, 0x78),
            memory.readBytes(0x80000104L, 4)
        )
    }

    @Test
    fun kseg0AndKseg1AliasAndSignExtendedAddressesShareRdram() {
        val memory = N64HleGuestAddressSpace()
        memory.write32(0x80000200L, 0xAABBCCDDL)

        assertEquals(0xAABBCCDDL, memory.read32(0xA0000200L))
        assertEquals(0xAABBCCDDL, memory.read32(0xFFFF_FFFF_8000_0200UL.toLong()))
    }

    @Test
    fun byteArrayWritesAreCopiedAndReadsAreDefensive() {
        val memory = N64HleGuestAddressSpace()
        val input = byteArrayOf(1, 2, 3, 4)
        memory.writeBytes(0x80000300L, input)
        input[0] = 99
        val output = memory.readBytes(0x80000300L, 4)
        output[1] = 88

        assertArrayEquals(byteArrayOf(1, 2, 3, 4), memory.readBytes(0x80000300L, 4))
    }

    @Test
    fun rejectsUnmappedSegmentsMisalignmentAndOutOfRangeAccesses() {
        val memory = N64HleGuestAddressSpace()
        assertThrows(IllegalArgumentException::class.java) { memory.read32(0x00000100L) }
        assertThrows(IllegalArgumentException::class.java) { memory.read32(0xC0000100L) }
        assertThrows(IllegalArgumentException::class.java) { memory.read32(0x80000002L) }
        assertThrows(IllegalArgumentException::class.java) { memory.read64(0x80000004L) }
        assertThrows(IllegalArgumentException::class.java) { memory.read32(0x80800000L) }
        assertThrows(IllegalArgumentException::class.java) { memory.readBytes(0x9FFFFFFFL, 2) }
        assertThrows(IllegalArgumentException::class.java) {
            memory.read32(0x1_8000_0000L)
        }
    }

    @Test
    fun validatesWriteWidthsAndMemorySize() {
        val memory = N64HleGuestAddressSpace(0x1000)
        assertThrows(IllegalArgumentException::class.java) { memory.write8(0x80000000L, 256) }
        assertThrows(IllegalArgumentException::class.java) { memory.write16(0x80000000L, -1) }
        assertThrows(IllegalArgumentException::class.java) { memory.write32(0x80000000L, 0x1_0000_0000L) }
        assertThrows(IllegalArgumentException::class.java) { memory.read8(0x80001000L) }
        assertEquals(0x1000, memory.sizeBytes)
    }
}