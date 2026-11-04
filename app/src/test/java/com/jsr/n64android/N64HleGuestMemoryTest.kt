package com.jsr.n64android

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class N64HleGuestMemoryTest {
    private fun guestMemory(size: Int = 8 * 1024 * 1024): Pair<N64Memory, N64HleGuestMemory> {
        val ram = N64Memory(size)
        return ram to N64HleGuestMemory(ram, size)
    }

    @Test
    fun kseg0AndKseg1ResolveToSameRdramBytes() {
        val (ram, guest) = guestMemory()
        guest.write32(0x80001234L, 0x12345678)
        assertEquals(0x12345678, guest.read32(0xA0001234L))
        assertEquals(0x12345678, ram.read32(0x1234))
    }

    @Test
    fun acceptsSignExtendedR4300KsegPointers() {
        val (_, guest) = guestMemory()
        guest.write32(0xFFFFFFFF80002000UL.toLong(), 0xCAFEBABE.toInt())
        assertEquals(0xCAFEBABE.toInt(), guest.read32(0xFFFFFFFFA0002000UL.toLong()))
    }

    @Test
    fun readsAndWritesBigEndianByteSequences() {
        val (_, guest) = guestMemory()
        val bytes = byteArrayOf(0x80.toByte(), 0x01, 0xFE.toByte(), 0x7F)
        guest.writeBytes(0x80000100L, bytes)
        assertArrayEquals(bytes, guest.readBytes(0xA0000100L, bytes.size))
        assertEquals(0x8001FE7F.toInt(), guest.read32(0x80000100L))
    }

    @Test
    fun rejectsTlbMappedAndNonGuestSegments() {
        val (_, guest) = guestMemory()
        assertThrows(IllegalArgumentException::class.java) { guest.read32(0x00001000L) }
        assertThrows(IllegalArgumentException::class.java) { guest.read32(0xC0001000L) }
        assertThrows(IllegalArgumentException::class.java) { guest.read32(0xE0001000L) }
    }

    @Test
    fun rejectsMisalignedAndOutOfRdramAccesses() {
        val (_, guest) = guestMemory(size = 0x2000)
        assertThrows(IllegalArgumentException::class.java) { guest.read32(0x80000002L) }
        assertThrows(IllegalArgumentException::class.java) { guest.read16(0x80000001L) }
        assertThrows(IllegalArgumentException::class.java) { guest.read32(0x80002000L) }
        assertThrows(IllegalArgumentException::class.java) { guest.readBytes(0x80001FFFL, 2) }
    }

    @Test
    fun rejectsNon32BitAndNegativeLengthPointers() {
        val (_, guest) = guestMemory()
        assertThrows(IllegalArgumentException::class.java) { guest.read8(0x0000000180000000L) }
        assertThrows(IllegalArgumentException::class.java) { guest.readBytes(0x80000000L, -1) }
    }
}
