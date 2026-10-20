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

    @Test fun cartridgeRomIsReadableAndReadOnly() {
        val bus = N64MemoryBus()
        bus.loadCartridgeRom(byteArrayOf(0x80.toByte(), 0x37, 0x12, 0x40, 1, 2, 3, 4))
        assertEquals(0x80371240.toInt(), bus.readWord(0x10000000L))
        assertEquals(1, bus.readByte(0x10000004L).toInt())
        assertThrows(IllegalArgumentException::class.java) {
            bus.writeByte(0x10000000L, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            bus.readWord(0x10000008L)
        }
    }

    @Test fun cartridgeReadFailsBeforeRomIsLoaded() {
        val bus = N64MemoryBus()
        assertThrows(IllegalStateException::class.java) { bus.readByte(0x10000000L) }
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

    @Test fun spDmaWrapsWithinSelectedDmemBank() {
        val bus = N64MemoryBus()
        bus.loadRdram(0x100L, byteArrayOf(
            0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x7F,
            0x21, 0x32, 0x43, 0x54, 0x65, 0x76, 0x17, 0x28
        ))
        bus.writeWord(N64MemoryBus.SP_MEM_ADDR, 0x0FF8)
        bus.writeWord(N64MemoryBus.SP_DRAM_ADDR, 0x100)
        bus.writeWord(N64MemoryBus.SP_RD_LEN, 15)

        assertEquals(0x11223344, bus.readWord(N64SpMemory.DMEM_BASE + 0xFF8))
        assertEquals(0x5566777F, bus.readWord(N64SpMemory.DMEM_BASE + 0xFFC))
        assertEquals(0x21324354, bus.readWord(N64SpMemory.DMEM_BASE))
        assertEquals(0x65761728, bus.readWord(N64SpMemory.DMEM_BASE + 4))
        assertEquals(0, bus.readWord(N64SpMemory.IMEM_BASE))
    }


    @Test fun spDmaAppliesSkipBetweenEveryEncodedBlock() {
        val bus = N64MemoryBus()
        val source = ByteArray(0x30) { it.toByte() }
        bus.loadRdram(0x100L, source)

        bus.writeWord(N64MemoryBus.SP_MEM_ADDR, 0x20)
        bus.writeWord(N64MemoryBus.SP_DRAM_ADDR, 0x100)
        // Two 8-byte blocks; the DRAM address skips 0x10 bytes after each block.
        val command = (0x10 shl 20) or (1 shl 12) or 7
        bus.writeWord(N64MemoryBus.SP_RD_LEN, command)

        for (i in 0 until 8) {
            assertEquals("first block byte $i", source[i], bus.readByte(N64SpMemory.DMEM_BASE + 0x20 + i))
            assertEquals("second block byte $i", source[0x18 + i], bus.readByte(N64SpMemory.DMEM_BASE + 0x28 + i))
        }
        // The programmed SP memory destination remains contiguous across blocks.
        assertEquals(0, bus.readByte(N64SpMemory.DMEM_BASE + 0x30).toInt())
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
