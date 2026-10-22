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

    @Test fun piDmaCopiesCartridgeBytesIntoRdram() {
        val bus = N64MemoryBus()
        bus.loadCartridgeRom(byteArrayOf(
            0x80.toByte(), 0x37, 0x12, 0x40,
            0x11, 0x22, 0x33, 0x44
        ))

        bus.writeWord(N64MemoryBus.PI_DRAM_ADDR, 0x200)
        bus.writeWord(N64MemoryBus.PI_CART_ADDR, 0x10000004)
        bus.writeWord(N64MemoryBus.PI_RD_LEN, 3) // PI read DMA copies cartridge bytes to RDRAM.

        assertEquals(0x11223344, bus.readWord(0x200L))
        assertEquals(0x204, bus.readWord(N64MemoryBus.PI_DRAM_ADDR))
        assertEquals(0x10000008, bus.readWord(N64MemoryBus.PI_CART_ADDR))
    }

    @Test fun piDmaUsesBulkCopyAndReportsOneDmaHistoryEntry() {
        val bus = N64MemoryBus()
        val rom = ByteArray(0x1000) { (it and 0xFF).toByte() }
        bus.loadCartridgeRom(rom)

        bus.writeWord(N64MemoryBus.PI_DRAM_ADDR, 0x400)
        bus.writeWord(N64MemoryBus.PI_CART_ADDR, 0x10000000)
        bus.writeWord(N64MemoryBus.PI_RD_LEN, 0x3FF)

        assertEquals(0x00010203, bus.readWord(0x400L))
        assertEquals(0xFCFDFEFF.toInt(), bus.readWord(0x7FCL))
        assertEquals(0x800, bus.readWord(N64MemoryBus.PI_DRAM_ADDR))
        assertEquals(0x10000400, bus.readWord(N64MemoryBus.PI_CART_ADDR))
        assertEquals(1024, bus.piDmaDiagnosticSummary().let { summary ->
            Regex("RDRAM_WRITES=(\\d+)").find(summary)?.groupValues?.get(1)?.toInt()
        })
        assertEquals(true, bus.piDmaDiagnosticSummary().contains("0x00000400/DMA=1024"))
    }

    @Test fun cartridgeReadFailsBeforeRomIsLoaded() {
        val bus = N64MemoryBus()
        assertThrows(IllegalStateException::class.java) { bus.readByte(0x10000000L) }
    }

    @Test fun rejectsUnsupportedDeviceRegistersAndUnmappedAddresses() {
        val bus = N64MemoryBus()
        assertThrows(IllegalArgumentException::class.java) { bus.readWord(0x04100000L) }
        assertThrows(IllegalArgumentException::class.java) { bus.readByte(0x05000000L) }
    }

    @Test fun miRegistersSupportBasicReadsAndCommandWrites() {
        val bus = N64MemoryBus()
        assertEquals(0, bus.readWord(N64MemoryBus.MI_MODE))
        assertEquals(0x02020102, bus.readWord(N64MemoryBus.MI_VERSION))
        assertEquals(0, bus.readWord(N64MemoryBus.MI_INTR))
        assertEquals(0, bus.readWord(N64MemoryBus.MI_INTR_MASK))
        bus.writeWord(N64MemoryBus.MI_MODE, (1 shl 4) or (1 shl 6) or (1 shl 9))
        assertEquals((1 shl 0) or (1 shl 1) or (1 shl 2), bus.readWord(N64MemoryBus.MI_MODE))
        bus.writeWord(N64MemoryBus.MI_MODE, (1 shl 3) or (1 shl 5) or (1 shl 8))
        assertEquals(0, bus.readWord(N64MemoryBus.MI_MODE))
        bus.writeWord(N64MemoryBus.MI_INTR_MASK, (1 shl 1) or (1 shl 5))
        assertEquals((1 shl 0) or (1 shl 2), bus.readWord(N64MemoryBus.MI_INTR_MASK))
        bus.writeWord(N64MemoryBus.MI_INTR_MASK, 1 shl 0)
        assertEquals(1 shl 2, bus.readWord(N64MemoryBus.MI_INTR_MASK))
        bus.writeWord(N64MemoryBus.MI_VERSION, 0xFFFFFFFF.toInt())
        bus.writeWord(N64MemoryBus.MI_INTR, 0xFFFFFFFF.toInt())
        assertEquals(0x02020102, bus.readWord(N64MemoryBus.MI_VERSION))
        assertEquals(0, bus.readWord(N64MemoryBus.MI_INTR))
    }

    @Test fun rdramRegisterWindowSupportsBasicWordReadsAndWrites() {
        val bus = N64MemoryBus()
        val address = 0x03F8008L
        assertEquals(0, bus.readWord(address))
        bus.writeWord(address, 0x12345678)
        assertEquals(0x12345678, bus.readWord(address))
    }

    @Test fun rdramModeInitBitClearsForBothRdramDeviceBanks() {
        val bus = N64MemoryBus()
        val firstDeviceMode = 0x03F0_000CL
        val secondDeviceMode = 0x03F8_000CL

        bus.writeWord(firstDeviceMode, 0xC6C0C0C0.toInt())
        bus.writeWord(secondDeviceMode, 0xC4000000.toInt())

        // INIT is a self-clearing command/status bit on each RDRAM device.
        assertEquals(0x46C0C0C0, bus.readWord(firstDeviceMode))
        assertEquals(0x44000000, bus.readWord(secondDeviceMode))
    }

    @Test fun riRegistersSupportBasicWordReadsAndWrites() {
        val bus = N64MemoryBus()

        assertEquals(0, bus.readWord(N64MemoryBus.RI_MODE))
        bus.writeWord(N64MemoryBus.RI_MODE, 0x00000003)
        bus.writeWord(N64MemoryBus.RI_CONFIG, 0x00000012)
        bus.writeWord(N64MemoryBus.RI_REFRESH, 0x00000024)

        assertEquals(0x00000003, bus.readWord(N64MemoryBus.RI_MODE))
        assertEquals(0x00000012, bus.readWord(N64MemoryBus.RI_CONFIG))
        assertEquals(0x00000024, bus.readWord(N64MemoryBus.RI_REFRESH))
        assertEquals(0, bus.readWord(N64MemoryBus.RI_WERROR))
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


    @Test fun spDmaTargetsImemWhenSpMemoryAddressSelectsInstructionBank() {
        val bus = N64MemoryBus()
        bus.loadRdram(0x200L, byteArrayOf(
            0x12, 0x34, 0x56, 0x78, 0x01, 0x23, 0x45, 0x67
        ))
        bus.writeWord(N64MemoryBus.SP_MEM_ADDR, 0x1000)
        bus.writeWord(N64MemoryBus.SP_DRAM_ADDR, 0x200)
        bus.writeWord(N64MemoryBus.SP_RD_LEN, 7)

        assertEquals(0x12345678, bus.readWord(N64SpMemory.IMEM_BASE))
        assertEquals(0x01234567, bus.readWord(N64SpMemory.IMEM_BASE + 4))
        assertEquals(0, bus.readWord(N64SpMemory.DMEM_BASE))
        assertEquals(0, bus.readWord(N64MemoryBus.SP_DMA_BUSY))
    }


    @Test fun spDmaClearsBusyFlagWhenRdramTransferFails() {
        val bus = N64MemoryBus(rdramSize = 0x100)
        bus.writeWord(N64MemoryBus.SP_MEM_ADDR, 0)
        bus.writeWord(N64MemoryBus.SP_DRAM_ADDR, 0xF8)

        // The address register is aligned down to 8 bytes; 16 bytes cross RDRAM's end.
        assertThrows(IllegalArgumentException::class.java) {
            bus.writeWord(N64MemoryBus.SP_RD_LEN, 15)
        }

        assertEquals(0, bus.readWord(N64MemoryBus.SP_DMA_BUSY))
    }


    @Test fun spStatusCommandsControlHaltBrokeAndSignalFlags() {
        val bus = N64MemoryBus()

        assertEquals(1, bus.readWord(N64MemoryBus.SP_STATUS) and 1)
        bus.writeWord(N64MemoryBus.SP_STATUS, 1) // clear HALT
        assertEquals(0, bus.readWord(N64MemoryBus.SP_STATUS) and 1)
        bus.writeWord(N64MemoryBus.SP_STATUS, 2) // set HALT
        assertEquals(1, bus.readWord(N64MemoryBus.SP_STATUS) and 1)

        bus.markSpBroke()
        assertEquals(2, bus.readWord(N64MemoryBus.SP_STATUS) and 2)
        bus.writeWord(N64MemoryBus.SP_STATUS, 4) // clear BROKE
        assertEquals(0, bus.readWord(N64MemoryBus.SP_STATUS) and 2)

        bus.writeWord(N64MemoryBus.SP_STATUS, 1 shl 10) // set signal 0
        assertEquals(1 shl 7, bus.readWord(N64MemoryBus.SP_STATUS) and (1 shl 7))
        bus.writeWord(N64MemoryBus.SP_STATUS, 1 shl 9) // clear signal 0
        assertEquals(0, bus.readWord(N64MemoryBus.SP_STATUS) and (1 shl 7))
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
    @Test fun aiRegistersAreAccessibleThroughBasicStorageModel() {
        val bus = N64MemoryBus()
        bus.writeWord(N64MemoryBus.AI_DRAM_ADDR, 0x12345678)
        bus.writeWord(N64MemoryBus.AI_LEN, 0x100)
        bus.writeWord(N64MemoryBus.AI_DACRATE, 0x3FFF)
        assertEquals(0x12345678, bus.readWord(N64MemoryBus.AI_DRAM_ADDR))
        assertEquals(0x100, bus.readWord(N64MemoryBus.AI_LEN))
        assertEquals(0, bus.readWord(N64MemoryBus.AI_STATUS))
        assertEquals(0x3FFF, bus.readWord(N64MemoryBus.AI_DACRATE))
    }

    @Test fun piRegistersAreAccessibleThroughBasicStorageModel() {
        val bus = N64MemoryBus()
        bus.writeWord(N64MemoryBus.PI_DRAM_ADDR, 0xFF123456.toInt())
        bus.writeWord(N64MemoryBus.PI_CART_ADDR, 0x10000000)
        bus.writeWord(N64MemoryBus.PI_WR_LEN, 0x40)
        assertEquals(0x00123456, bus.readWord(N64MemoryBus.PI_DRAM_ADDR))
        assertEquals(0x10000000, bus.readWord(N64MemoryBus.PI_CART_ADDR))
        assertEquals(0x40, bus.readWord(N64MemoryBus.PI_WR_LEN))
    }

}
