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
        assertThrows(IllegalArgumentException::class.java) { bus.readWord(0x04300000L) }
        assertThrows(IllegalArgumentException::class.java) { bus.readByte(0x05000000L) }
    }

    @Test fun rejectsMisalignedAccessesAndOutOfRangeRdram() {
        val bus = N64MemoryBus(rdramSize = 0x100)
        assertThrows(IllegalArgumentException::class.java) { bus.readWord(2L) }
        assertThrows(IllegalArgumentException::class.java) { bus.writeHalf(1L, 7) }
        assertThrows(IllegalArgumentException::class.java) { bus.readWord(0x100L) }
        assertThrows(IllegalArgumentException::class.java) { bus.writeByte(0x100L, 0) }
    }

    @Test fun spRegistersExposeAddressesAndSemaphoreBehavior() {
        val bus = N64MemoryBus()
        bus.writeWord(N64MemoryBus.SP_MEM_ADDR, 0x100)
        bus.writeWord(N64MemoryBus.SP_DRAM_ADDR, 0x200)
        assertEquals(0x100, bus.readWord(N64MemoryBus.SP_MEM_ADDR))
        assertEquals(0x200, bus.readWord(N64MemoryBus.SP_DRAM_ADDR))
        assertEquals(0, bus.readWord(N64MemoryBus.SP_SEMAPHORE))
        assertEquals(1, bus.readWord(N64MemoryBus.SP_SEMAPHORE))
        bus.writeWord(N64MemoryBus.SP_SEMAPHORE, 0)
        assertEquals(0, bus.readWord(N64MemoryBus.SP_SEMAPHORE))
    }

    @Test fun spDmaCopiesRdramToDmemAndBack() {
        val bus = N64MemoryBus()
        bus.loadRdram(0x100L, byteArrayOf(
            0x10, 0x20, 0x30, 0x40, 0x50, 0x60, 0x70, 0x7F
        ))
        bus.writeWord(N64MemoryBus.SP_MEM_ADDR, 0)
        bus.writeWord(N64MemoryBus.SP_DRAM_ADDR, 0x100)
        bus.writeWord(N64MemoryBus.SP_RD_LEN, 7)
        assertEquals(0x10203040, bus.readWord(N64SpMemory.DMEM_BASE))
        assertEquals(0x5060707F, bus.readWord(N64SpMemory.DMEM_BASE + 4))

        bus.writeWord(N64MemoryBus.SP_MEM_ADDR, 0)
        bus.writeWord(N64MemoryBus.SP_DRAM_ADDR, 0x180)
        bus.writeWord(N64MemoryBus.SP_WR_LEN, 7)
        assertEquals(0x10203040, bus.readWord(0x180))
        assertEquals(0x5060707F, bus.readWord(0x184))
        assertEquals(0, bus.readWord(N64MemoryBus.SP_DMA_BUSY))
    }
}
