package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Test

class N64SpTaskDescriptorTest {
    @Test
    fun readsAllSixteenOstaskWordsFromDefaultDmemLocation() {
        val bus = N64Bus()
        val expected = intArrayOf(
            1, 0x20, 0x1000, 0x100, 0x2000, 0x1000, 0x3000, 0x800,
            0x4000, 0x400, 0x5000, 0x6000, 0x7000, 0x800,
            0x9000, 0x100
        )
        expected.forEachIndexed { index, value ->
            bus.write32(
                N64Bus.SP_DMEM_BASE + N64SpTaskDescriptor.DEFAULT_DMEM_OFFSET + index * 4,
                value
            )
        }

        val task = N64SpTaskDescriptor.readFrom(bus)

        assertEquals(expected[0], task.type)
        assertEquals(expected[1], task.flags)
        assertEquals(expected[2], task.ucodeBoot)
        assertEquals(expected[3], task.ucodeBootSize)
        assertEquals(expected[4], task.ucode)
        assertEquals(expected[5], task.ucodeSize)
        assertEquals(expected[6], task.ucodeData)
        assertEquals(expected[7], task.ucodeDataSize)
        assertEquals(expected[8], task.dramStack)
        assertEquals(expected[9], task.dramStackSize)
        assertEquals(expected[10], task.outputBuffer)
        assertEquals(expected[11], task.outputBufferSize)
        assertEquals(expected[12], task.data)
        assertEquals(expected[13], task.dataSize)
        assertEquals(expected[14], task.yieldData)
        assertEquals(expected[15], task.yieldDataSize)
    }

    @Test
    fun supportsAnExplicitAlignedDmemOffset() {
        val bus = N64Bus()
        bus.write32(N64Bus.SP_DMEM_BASE + 0x100, 2)
        bus.write32(N64Bus.SP_DMEM_BASE + 0x100 + 12 * 4, 0x12345678)

        val task = N64SpTaskDescriptor.readFrom(bus, 0x100)

        assertEquals(2, task.type)
        assertEquals(0x12345678, task.data)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDescriptorThatWouldExtendPastDmem() {
        N64SpTaskDescriptor.readFrom(N64Bus(), 0xFC4)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnalignedDescriptorAddress() {
        N64SpTaskDescriptor.readFrom(N64Bus(), 0x101)
    }
}
