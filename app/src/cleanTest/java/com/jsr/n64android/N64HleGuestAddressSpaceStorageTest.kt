package com.jsr.n64android

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class N64HleGuestAddressSpaceStorageTest {
    @Test
    fun guestReadsAndWritesUseInjectedRdramStorage() {
        val storage = N64HleByteArrayRdramStorage(0x2000)
        val memory = N64HleGuestAddressSpace(rdramSize = 0x2000, rdramStorage = storage)

        memory.write32(0x80000100L, 0x12345678L)

        assertArrayEquals(
            byteArrayOf(0x12, 0x34, 0x56, 0x78),
            storage.readBytes(0x100, 4)
        )
        storage.writeBytes(0x104, byteArrayOf(0x55, 0x66, 0x77, 0x00))
        assertEquals(0x55667700L, memory.read32(0x80000104L))
    }

    @Test
    fun guestBulkWritesAreCopiedIntoInjectedStorage() {
        val storage = N64HleByteArrayRdramStorage()
        val memory = N64HleGuestAddressSpace(rdramStorage = storage)
        val input = byteArrayOf(1, 2, 3, 4, 5)

        memory.writeBytes(0x80000200L, input)
        input[0] = 99

        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), storage.readBytes(0x200, 5))
    }

    @Test
    fun piDmaWritesCartridgeDataThroughInjectedStorage() {
        val storage = N64HleByteArrayRdramStorage()
        val memory = N64HleGuestAddressSpace(
            cartridgeRom = ByteArray(16) { (it + 10).toByte() },
            rdramStorage = storage
        )

        memory.write32(0xA4600000L, 0x300L)
        memory.write32(0xA4600004L, 0x10000004L)
        memory.write32(0xA4600008L, 3L)

        assertArrayEquals(byteArrayOf(14, 15, 16, 17), storage.readBytes(0x300, 4))
    }

    @Test
    fun spDmaTransfersBetweenLocalSpMemoryAndInjectedRdramInBlocks() {
        val storage = N64HleByteArrayRdramStorage()
        val memory = N64HleGuestAddressSpace(rdramStorage = storage)

        storage.writeBytes(0x400, byteArrayOf(0x11, 0x22, 0x33, 0x44))
        memory.write32(0xA4040000L, 0x20L)
        memory.write32(0xA4040004L, 0x400L)
        memory.write32(0xA4040008L, 3L)
        assertArrayEquals(byteArrayOf(0x11, 0x22, 0x33, 0x44), memory.readBytes(0xA4000020L, 4))

        memory.writeBytes(0xA4000030L, byteArrayOf(0x55, 0x66, 0x77, 0x00))
        memory.write32(0xA4040000L, 0x30L)
        memory.write32(0xA4040004L, 0x500L)
        memory.write32(0xA404000CL, 3L)
        assertArrayEquals(byteArrayOf(0x55, 0x66, 0x77, 0x00), storage.readBytes(0x500, 4))
    }
}
