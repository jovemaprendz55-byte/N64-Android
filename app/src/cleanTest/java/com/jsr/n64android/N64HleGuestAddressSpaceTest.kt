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
    fun mapsSpDmemAndImemThroughKsegAliases() {
        val memory = N64HleGuestAddressSpace()
        memory.write32(0xA4000000L, 0x11223344L)
        memory.write32(0xFFFFFFFFA4001FF0UL.toLong(), 0xAABBCCDDL)

        assertEquals(0x11223344L, memory.read32(0xA0000000L + 0x04000000L))
        assertEquals(0xAABBCCDDL, memory.read32(0xA4001FF0L))
        assertEquals(0x11223344L, memory.read32(0xFFFFFFFFA4000000UL.toLong()))
    }

    @Test
    fun rejectsAccessesThatCrossSpMemoryBankBoundaries() {
        val memory = N64HleGuestAddressSpace()
        assertThrows(IllegalArgumentException::class.java) {
            memory.read32(0xA4000FFEL)
        }
        assertThrows(IllegalArgumentException::class.java) {
            memory.read32(0xA4001FFEL)
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

    @Test
    fun mapsSpRegistersThroughKsegAliasesAndAppliesStatusCommands() {
        val memory = N64HleGuestAddressSpace()
        assertEquals(1L, memory.read32(0xA4040010L)) // HALT after reset
        memory.write32(0xA4040010L, 1L) // clear HALT command
        assertEquals(0L, memory.read32(0xA0040010L))
        memory.write32(0xA4040010L, 2L) // set HALT command
        assertEquals(1L, memory.read32(0xA4040010L))

        memory.write32(0xA4080000L, 0x1237L)
        assertEquals(0x1234L, memory.read32(0xA4080000L))
        memory.write32(0xA4040000L, 0x3456L)
        assertEquals(0x1456L, memory.read32(0xA4040000L))
    }

    @Test
    fun spReadLengthDmaCopiesRdramIntoDmemAndUpdatesAddresses() {
        val memory = N64HleGuestAddressSpace()
        memory.write32(0x80000100L, 0x12345678L)
        memory.write32(0xA4040000L, 0x20L)
        memory.write32(0xA4040004L, 0x100L)
        memory.write32(0xA4040008L, 3L)

        assertEquals(0x12345678L, memory.read32(0xA4000020L))
        assertEquals(0x24L, memory.read32(0xA4040000L))
        assertEquals(0x104L, memory.read32(0xA4040004L))
        assertEquals(0L, memory.read32(0xA4040018L))
    }

    @Test
    fun spWriteLengthDmaCopiesImemIntoRdram() {
        val memory = N64HleGuestAddressSpace()
        memory.write32(0xA4001040L, 0x89ABCDEF)
        memory.write32(0xA4040000L, 0x1040L)
        memory.write32(0xA4040004L, 0x200L)
        memory.write32(0xA404000CL, 3L)

        assertEquals(0x89ABCDEFL, memory.read32(0x80000200L))
        assertEquals(0x1044L, memory.read32(0xA4040000L))
        assertEquals(0x204L, memory.read32(0xA4040004L))
    }

    @Test
    fun spDmaHonorsBlockCountAndDramSkip() {
        val memory = N64HleGuestAddressSpace()
        memory.write32(0x80000100L, 0x11223344L)
        memory.write32(0x80000108L, 0x55667788L)
        memory.write32(0xA4040000L, 0x40L)
        memory.write32(0xA4040004L, 0x100L)
        memory.write32(0xA4040008L, ((4 shl 20) or (1 shl 12) or 3).toLong())

        assertEquals(0x11223344L, memory.read32(0xA4000040L))
        assertEquals(0x55667788L, memory.read32(0xA4000044L))
        assertEquals(0x48L, memory.read32(0xA4040000L))
        assertEquals(0x110L, memory.read32(0xA4040004L))
    }

    @Test
    fun invalidSpDmaDoesNotPartiallyModifySpMemory() {
        val memory = N64HleGuestAddressSpace(0x1000)
        memory.write32(0xA4000000L, 0xAABBCCDDL)
        memory.write32(0xA4040000L, 0L)
        memory.write32(0xA4040004L, 0xFFEL)
        assertThrows(IllegalArgumentException::class.java) {
            memory.write32(0xA4040008L, 3L)
        }
        assertEquals(0xAABBCCDDL, memory.read32(0xA4000000L))
    }

    @Test
    fun reportsTypedAlignmentAndMappingFaultsWithOriginalVirtualAddress() {
        val memory = N64HleGuestAddressSpace()
        val alignment = assertThrows(N64HleGuestAddressAlignmentFault::class.java) {
            memory.read32(0x80000002L)
        }
        assertEquals(0x80000002L, alignment.virtualAddress)
        assertEquals(4, alignment.requiredAlignment)

        val unmapped = assertThrows(N64HleGuestAddressMappingFault::class.java) {
            memory.read32(0x80800000L)
        }
        assertEquals(0x80800000L, unmapped.virtualAddress)

        val unsupportedSegment = assertThrows(N64HleGuestAddressMappingFault::class.java) {
            memory.read32(0x00000100L)
        }
        assertEquals(0x00000100L, unsupportedSegment.virtualAddress)
    }


    @Test
    fun piReadLengthDmaCopiesCartridgeBytesIntoRdramAndAdvancesAddresses() {
        val rom = ByteArray(64) { (it + 1).toByte() }
        val memory = N64HleGuestAddressSpace(cartridgeRom = rom)
        memory.write32(0xA4600000L, 0x100L)
        memory.write32(0xA4600004L, 0x10000008L)
        memory.write32(0xA4600008L, 7L)

        assertArrayEquals(byteArrayOf(9, 10, 11, 12, 13, 14, 15, 16), memory.readBytes(0x80000100L, 8))
        assertEquals(0x108L, memory.read32(0xA4600000L))
        assertEquals(0x10000010L, memory.read32(0xA4600004L))
        assertEquals(0L, memory.read32(0xA4600010L))
    }

    @Test
    fun invalidPiDmaDoesNotPartiallyModifyRdramAndSetsError() {
        val memory = N64HleGuestAddressSpace(cartridgeRom = ByteArray(16) { it.toByte() })
        memory.write32(0x80000100L, 0xAABBCCDDL)
        memory.write32(0xA4600000L, 0x100L)
        memory.write32(0xA4600004L, 0x1000000CL)
        assertThrows(IllegalArgumentException::class.java) { memory.write32(0xA4600008L, 7L) }
        assertEquals(0xAABBCCDDL, memory.read32(0x80000100L))
        assertEquals(4L, memory.read32(0xA4600010L))
    }

    @Test
    fun piWriteLengthIsRejectedAndSetsErrorInsteadOfWritingRom() {
        val memory = N64HleGuestAddressSpace(cartridgeRom = byteArrayOf(1, 2, 3, 4))
        assertThrows(N64HleGuestAddressMappingFault::class.java) { memory.write32(0xA460000CL, 3L) }
        assertEquals(4L, memory.read32(0xA4600010L))
    }


    @Test
    fun piDmaRaisesMiPendingBitAndMiMaskUsesSetClearCommandPairs() {
        val memory = N64HleGuestAddressSpace(cartridgeRom = ByteArray(16) { it.toByte() })
        memory.write32(0xA4600000L, 0x100L)
        memory.write32(0xA4600004L, 0x10000000L)
        memory.write32(0xA4600008L, 3L)

        assertEquals(0x10L, memory.read32(0xA4300008L)) // MI_INTR.PI
        memory.write32(0xA430000CL, 1L shl 9) // set PI mask
        assertEquals(0x10L, memory.read32(0xA430000CL))
        memory.write32(0xA430000CL, 1L shl 8) // clear PI mask
        assertEquals(0L, memory.read32(0xA430000CL))
        assertEquals(0x10L, memory.read32(0xA4300008L)) // mask does not clear pending
    }


}
