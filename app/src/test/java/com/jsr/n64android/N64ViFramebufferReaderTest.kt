package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Test

class N64ViFramebufferReaderTest {
    @Test fun convertsRgba5551PixelsFromBigEndianRdram() {
        val memory = N64Memory(64)
        memory.write16(0, 0xF801) // opaque red
        memory.write16(2, 0x07C1) // opaque green
        memory.write16(4, 0x003F) // opaque blue
        val frame = N64ViFramebufferReader(memory, memory.size).capture(
            origin = 0,
            width = 3,
            height = 1,
            viStatus = N64ViFramebufferReader.VI_STATUS_PIXEL_SIZE_16
        )

        assertEquals(0xFFFF0000.toInt(), frame.argbPixels[0])
        assertEquals(0xFF00FF00.toInt(), frame.argbPixels[1])
        assertEquals(0xFF0000FF.toInt(), frame.argbPixels[2])
    }

    @Test fun readsRowsUsingViStrideAndSkipsPaddingPixels() {
        val memory = N64Memory(64)
        memory.write16(0, 0xF801)
        memory.write16(2, 0x07C1)
        memory.write16(4, 0x003F)
        memory.write16(6, 0xFFFF)
        memory.write16(8, 0x003F)
        memory.write16(10, 0xF83F)

        val frame = N64ViFramebufferReader(memory, memory.size).capture(
            origin = 0,
            width = 2,
            height = 2,
            viStatus = N64ViFramebufferReader.VI_STATUS_PIXEL_SIZE_16,
            stridePixels = 4
        )

        assertEquals(2, frame.width)
        assertEquals(2, frame.height)
        assertEquals(0xFFFF0000.toInt(), frame.argbPixels[0])
        assertEquals(0xFF00FF00.toInt(), frame.argbPixels[1])
        assertEquals(0xFF0000FF.toInt(), frame.argbPixels[2])
        assertEquals(0xFFFF00FF.toInt(), frame.argbPixels[3])
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsStrideSmallerThanOutputWidth() {
        val memory = N64Memory(64)
        N64ViFramebufferReader(memory, memory.size).capture(
            origin = 0, width = 3, height = 1,
            viStatus = N64ViFramebufferReader.VI_STATUS_PIXEL_SIZE_16,
            stridePixels = 2
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun validatesLastVisibleRowUsingStride() {
        val memory = N64Memory(16)
        N64ViFramebufferReader(memory, memory.size).capture(
            origin = 0, width = 2, height = 2,
            viStatus = N64ViFramebufferReader.VI_STATUS_PIXEL_SIZE_16,
            stridePixels = 8
        )
    }

    @Test fun convertsRgba8888PixelsWithoutChangingChannels() {
        val memory = N64Memory(16)
        memory.write32(4, 0x12345678)
        val frame = N64ViFramebufferReader(memory, memory.size).capture(
            origin = 4,
            width = 1,
            height = 1,
            viStatus = N64ViFramebufferReader.VI_STATUS_PIXEL_SIZE_32
        )

        assertEquals(0x78123456, frame.argbPixels[0])
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsFramebufferOutsideRdram() {
        val memory = N64Memory(16)
        N64ViFramebufferReader(memory, memory.size).capture(
            origin = 12,
            width = 1,
            height = 2,
            viStatus = N64ViFramebufferReader.VI_STATUS_PIXEL_SIZE_32
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsupportedPixelSize() {
        val memory = N64Memory(16)
        N64ViFramebufferReader(memory, memory.size).capture(0, 1, 1, 0)
    }
}
