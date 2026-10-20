package com.jovemaprendz55byte.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class N64VideoInterfaceTest {
    @Test
    fun viRegistersAreAccessibleThroughMemoryBus() {
        val bus = N64MemoryBus()
        bus.writeWord(N64VideoInterface.VI_STATUS, 2)
        bus.writeWord(N64VideoInterface.VI_ORIGIN, 0x123)
        bus.writeWord(N64VideoInterface.VI_WIDTH, 320)

        assertEquals(2, bus.readWord(N64VideoInterface.VI_STATUS))
        assertEquals(0x120, bus.readWord(N64VideoInterface.VI_ORIGIN))
        assertEquals(320, bus.readWord(N64VideoInterface.VI_WIDTH))
    }

    @Test
    fun scansOutBigEndianRgba5551PixelsFromRdram() {
        val bus = N64MemoryBus()
        bus.loadRdram(0x100L, byteArrayOf(
            0xF8.toByte(), 0x01, // opaque red
            0x07, 0xC1.toByte()  // opaque green
        ))
        bus.writeWord(N64VideoInterface.VI_STATUS, 2)
        bus.writeWord(N64VideoInterface.VI_ORIGIN, 0x100)
        bus.writeWord(N64VideoInterface.VI_WIDTH, 2)

        val frame = N64VideoFrameBuffer(2, 1)
        assertTrue(bus.renderVideoFrame(frame))
        assertEquals(0xFFFF0000.toInt(), frame.pixelAt(0, 0))
        assertEquals(0xFF00FF00.toInt(), frame.pixelAt(1, 0))
    }

    @Test
    fun scansOutRgba8888PixelsFromRdram() {
        val bus = N64MemoryBus()
        bus.loadRdram(0x200L, byteArrayOf(
            0x12, 0x34, 0x56, 0x78
        ))
        bus.writeWord(N64VideoInterface.VI_STATUS, 3)
        bus.writeWord(N64VideoInterface.VI_ORIGIN, 0x200)
        bus.writeWord(N64VideoInterface.VI_WIDTH, 1)

        val frame = N64VideoFrameBuffer(1, 1)
        assertTrue(bus.renderVideoFrame(frame))
        assertEquals(0x78123456, frame.pixelAt(0, 0))
    }

    @Test
    fun smallerActiveImageClearsPixelsLeftOverFromPreviousFrame() {
        val bus = N64MemoryBus()
        bus.loadRdram(0L, byteArrayOf(0x12, 0x34, 0x56, 0x78))
        bus.writeWord(N64VideoInterface.VI_STATUS, 3)
        bus.writeWord(N64VideoInterface.VI_ORIGIN, 0)
        bus.writeWord(N64VideoInterface.VI_WIDTH, 1)

        val frame = N64VideoFrameBuffer(3, 2)
        frame.clear(0xFFABCDEF.toInt())

        assertTrue(bus.renderVideoFrame(frame, height = 1))
        assertEquals(0x78123456, frame.pixelAt(0, 0))
        assertEquals(0xFF000000.toInt(), frame.pixelAt(1, 0))
        assertEquals(0xFF000000.toInt(), frame.pixelAt(2, 0))
        assertEquals(0xFF000000.toInt(), frame.pixelAt(0, 1))
        assertEquals(0xFF000000.toInt(), frame.pixelAt(1, 1))
        assertEquals(0xFF000000.toInt(), frame.pixelAt(2, 1))
    }

    @Test
    fun blankOrUnsupportedPixelFormatDoesNotRender() {
        val bus = N64MemoryBus()
        val frame = N64VideoFrameBuffer(1, 1)
        frame.clear(0xFFABCDEF.toInt())

        bus.writeWord(N64VideoInterface.VI_STATUS, 0)
        assertFalse(bus.renderVideoFrame(frame))
        assertEquals(0xFFABCDEF.toInt(), frame.pixelAt(0, 0))

        bus.writeWord(N64VideoInterface.VI_STATUS, 1)
        assertFalse(bus.renderVideoFrame(frame))
    }

    @Test
    fun invalidFramebufferRangeIsRejectedWithoutPartialRender() {
        val bus = N64MemoryBus(rdramSize = 0x100)
        bus.writeWord(N64VideoInterface.VI_STATUS, 3)
        bus.writeWord(N64VideoInterface.VI_ORIGIN, 0x100)
        bus.writeWord(N64VideoInterface.VI_WIDTH, 2)
        val frame = N64VideoFrameBuffer(2, 1)
        frame.clear(0xFF010203.toInt())

        assertFalse(bus.renderVideoFrame(frame))
        assertEquals(0xFF010203.toInt(), frame.pixelAt(0, 0))
    }

    @Test
    fun scanoutRejectsWidthLargerThanDestination() {
        val bus = N64MemoryBus()
        bus.writeWord(N64VideoInterface.VI_STATUS, 2)
        bus.writeWord(N64VideoInterface.VI_ORIGIN, 0)
        bus.writeWord(N64VideoInterface.VI_WIDTH, 3)

        assertFalse(bus.renderVideoFrame(N64VideoFrameBuffer(2, 1)))
    }
}
