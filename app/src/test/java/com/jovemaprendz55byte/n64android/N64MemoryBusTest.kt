package com.jovemaprendz55byte.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class N64MemoryBusTest {
    @Test fun routesBigEndianWordsToRdramAndSpMemories() {
        val bus = N64MemoryBus()
        bus.writeWord(0x100L, 0x12345678)
        bus.writeWord(N64SpMemory.DMEM_BASE, 0xAABBCCDD.toInt())
        bus.writeWord(N64SpMemory.IMEM_BASE, 0x10203040)

        assertEquals(0x12345678, bus.readWord(0x100L))
        assertEquals(0x12.toByte(), bus.readByte(0x100L))
        assertEquals(0xAABBCCDD.toInt(), bus.readWord(N64SpMemory.DMEM_BASE))
        assertEquals(0x10203040, bus.readWord(N64SpMemory.IMEM_BASE))
    }

    @Test fun keepsRdramDmemAndImemStorageIndependent() {
        val bus = N64MemoryBus()
        bus.writeWord(0L, 0x11223344)
        bus.writeWord(N64SpMemory.DMEM_BASE, 0x55667788)
        bus.writeWord(N64SpMemory.IMEM_BASE, 0x99AABBCC.toInt())

        assertEquals(0x11223344, bus.readWord(0L))
        assertEquals(0x55667788, bus.readWord(N64SpMemory.DMEM_BASE))
        assertEquals(0x99AABBCC.toInt(), bus.readWord(N64SpMemory.IMEM_BASE))
    }

    @Test fun supportsHalfwordAccessesInBigEndianOrder() {
        val bus = N64MemoryBus()
        bus.writeHalf(0x20L, 0xABCD.toShort())
        assertEquals(0xABCD.toShort(), bus.readHalf(0x20L))
        assertEquals(0xAB.toByte(), bus.readByte(0x20L))
        assertEquals(0xCD.toByte(), bus.readByte(0x21L))
    }

    @Test fun bulkLoadsAreRestrictedToRdramAndCheckBounds() {
        val bus = N64MemoryBus(rdramSize = 0x100)
        bus.loadRdram(0x10L, byteArrayOf(1, 2, 3, 4))
        assertEquals(0x01020304, bus.readWord(0x10L))
        assertThrows(IllegalArgumentException::class.java) {
            bus.loadRdram(0xFEL, byteArrayOf(1, 2, 3))
        }
        assertThrows(IllegalArgumentException::class.java) {
            bus.loadRdram(N64SpMemory.DMEM_BASE, byteArrayOf(1))
        }
    }

    @Test fun rejectsUnsupportedDeviceRegistersAndUnmappedAddresses() {
        val bus = N64MemoryBus()
        assertThrows(IllegalArgumentException::class.java) { bus.readWord(0x04040000L) }
        assertThrows(IllegalArgumentException::class.java) { bus.readByte(0x05000000L) }
    }

    @Test fun rejectsMisalignedAccessesAndOutOfRangeRdram() {
        val bus = N64MemoryBus(rdramSize = 0x100)
        assertThrows(IllegalArgumentException::class.java) { bus.readWord(2L) }
        assertThrows(IllegalArgumentException::class.java) { bus.writeHalf(1L, 7) }
        assertThrows(IllegalArgumentException::class.java) { bus.readWord(0x100L) }
        assertThrows(IllegalArgumentException::class.java) { bus.writeByte(0x100L, 0) }
    }
}
