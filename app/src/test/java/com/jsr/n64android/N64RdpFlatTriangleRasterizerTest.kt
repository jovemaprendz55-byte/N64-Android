package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class N64RdpFlatTriangleRasterizerTest {
    @Test
    fun writesFlatTrianglePixelsToRdramAndLeavesOutsidePixelsUntouched() {
        val memory = N64Memory(4096)
        val setup = N64RdpTriangleSetup(
            opcode = 0x08, rightMajor = false, tile = 0,
            yLow = 8 * 4, yMiddle = 4 * 4, yHigh = 0,
            xLow = 6 shl 16, dxLowDy = 0,
            xHigh = 1 shl 16, dxHighDy = 0,
            xMiddle = 4 shl 16, dxMiddleDy = 0
        )

        val result = N64RdpFlatTriangleRasterizer.render(
            setup, memory, memory.size, 0x100, 8, 8, 0xF801
        )

        assertTrue(result.spansWritten > 0)
        assertTrue(result.pixelsWritten > 0)
        assertEquals(0xF801, memory.read16(0x100 + (0 * 8 + 1) * 2))
        assertEquals(0, memory.read16(0x100))
    }

    @Test
    fun rejectsFramebufferOutsideRdramBeforeWriting() {
        val memory = N64Memory(128)
        val setup = N64RdpTriangleSetup(
            opcode = 0x08, rightMajor = false, tile = 0,
            yLow = 8, yMiddle = 4, yHigh = 0,
            xLow = 6 shl 16, dxLowDy = 0,
            xHigh = 1 shl 16, dxHighDy = 0,
            xMiddle = 4 shl 16, dxMiddleDy = 0
        )

        val failure = runCatching {
            N64RdpFlatTriangleRasterizer.render(setup, memory, memory.size, 64, 8, 8, 0xF801)
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(0, memory.read16(64))
    }

    @Test
    fun syntheticPipelineProducesViFrameContainingRasterizedTriangle() {
        val result = N64TrianglePipelineDemo.createFrame()

        assertEquals(320, result.frame.width)
        assertEquals(240, result.frame.height)
        assertTrue(result.spansWritten > 0)
        assertTrue(result.pixelsWritten > 0)
        assertEquals(0xFFFF0000.toInt(), result.frame.argbPixels[100 * 320 + 100])
        assertEquals(0xFF000000.toInt(), result.frame.argbPixels[10 * 320 + 10])
    }
}
