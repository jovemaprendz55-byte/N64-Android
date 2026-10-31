package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Test

class N64VideoTestPatternTest {
    @Test
    fun generatesColorBarsThroughViAndReturnsExpectedFrame() {
        val frame = N64VideoTestPattern.createFrame()

        assertEquals(320, frame.width)
        assertEquals(240, frame.height)
        assertEquals(N64VideoTestPattern.ORIGIN, frame.origin)
        assertEquals(N64ViFramebufferReader.VI_STATUS_PIXEL_SIZE_16, frame.pixelSize)

        val expected = intArrayOf(
            0xFFFF0000.toInt(), // red
            0xFFFFFF00.toInt(), // yellow
            0xFF00FF00.toInt(), // green
            0xFF00FFFF.toInt(), // cyan
            0xFF0000FF.toInt(), // blue
            0xFFFF00FF.toInt(), // magenta
            0xFFFFFFFF.toInt(), // white
            0xFF000000.toInt()  // black
        )
        for (bar in expected.indices) {
            val x = bar * (N64VideoTestPattern.WIDTH / expected.size)
            assertEquals("bar $bar", expected[bar], frame.argbPixels[x])
        }
        assertEquals(expected[7], frame.argbPixels.last())
    }
}
