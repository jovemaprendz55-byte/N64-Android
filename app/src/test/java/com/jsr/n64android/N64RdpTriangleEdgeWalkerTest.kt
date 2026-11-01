package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class N64RdpTriangleEdgeWalkerTest {
    private fun setup(
        rightMajor: Boolean = false,
        yHigh: Int = 0,
        yMiddle: Int = 8,
        yLow: Int = 16,
        xHigh: Int = 1 shl 16,
        dxHighDy: Int = 0,
        xMiddle: Int = 4 shl 16,
        dxMiddleDy: Int = 0,
        xLow: Int = 4 shl 16,
        dxLowDy: Int = 0
    ) = N64RdpTriangleSetup(
        opcode = 0x08,
        rightMajor = rightMajor,
        tile = 0,
        yLow = yLow,
        yMiddle = yMiddle,
        yHigh = yHigh,
        xLow = xLow,
        dxLowDy = dxLowDy,
        xHigh = xHigh,
        dxHighDy = dxHighDy,
        xMiddle = xMiddle,
        dxMiddleDy = dxMiddleDy
    )

    @Test
    fun emitsHorizontalSpansForUpperAndLowerEdges() {
        val spans = N64RdpTriangleEdgeWalker.walk(setup(), width = 8, height = 6)

        assertEquals(
            listOf(
                N64RdpTriangleSpan(0, 1, 4),
                N64RdpTriangleSpan(1, 1, 4),
                N64RdpTriangleSpan(2, 1, 4),
                N64RdpTriangleSpan(3, 1, 4)
            ),
            spans
        )
    }

    @Test
    fun clipsSpansToFramebufferAndScissorRectangle() {
        val spans = N64RdpTriangleEdgeWalker.walk(
            setup(),
            width = 8,
            height = 6,
            clipLeft = 2,
            clipTop = 1,
            clipRightExclusive = 3,
            clipBottomExclusive = 3
        )

        assertEquals(
            listOf(N64RdpTriangleSpan(1, 2, 3), N64RdpTriangleSpan(2, 2, 3)),
            spans
        )
    }

    @Test
    fun selectsMajorEdgeOnTheRightWhenRightMajorFlagIsSet() {
        val spans = N64RdpTriangleEdgeWalker.walk(
            setup(rightMajor = true, xHigh = 4 shl 16, xMiddle = 1 shl 16, xLow = 1 shl 16),
            width = 8,
            height = 4
        )

        assertEquals(4, spans.size)
        assertTrue(spans.all { it.xStart == 1 && it.xEndExclusive == 4 })
    }

    @Test
    fun appliesMajorEdgeSlopeAtPixelCenter() {
        val spans = N64RdpTriangleEdgeWalker.walk(
            setup(dxHighDy = 1 shl 16),
            width = 8,
            height = 4
        )

        assertEquals(1, spans.first { it.y == 0 }.xStart)
        assertEquals(2, spans.first { it.y == 1 }.xStart)
    }

    @Test
    fun returnsNoSpansForDegenerateVerticalRange() {
        val spans = N64RdpTriangleEdgeWalker.walk(
            setup(yHigh = 4, yMiddle = 4, yLow = 4),
            width = 8,
            height = 4
        )

        assertTrue(spans.isEmpty())
    }
}
