package com.jsr.n64android

/**
 * A horizontal pixel span produced by walking one RDP triangle scanline.
 * X is inclusive/exclusive; Y is a framebuffer row.
 */
data class N64RdpTriangleSpan(
    val y: Int,
    val xStart: Int,
    val xEndExclusive: Int
)

/**
 * Experimental edge walker for the RDP triangle edge-coefficient format.
 *
 * Uses pixel-center sampling and signed 12.2/16.16 fixed-point coefficients.
 * Coverage/edge tie-breaking still needs hardware validation; this class does
 * not apply color, texture, coverage, blending, or depth operations.
 */
object N64RdpTriangleEdgeWalker {
    private const val SUBPIXELS_PER_PIXEL = 4L
    private const val FIXED_ONE = 1L shl 16
    private const val HALF_PIXEL = FIXED_ONE / 2

    fun walk(
        setup: N64RdpTriangleSetup,
        width: Int,
        height: Int,
        clipLeft: Int = 0,
        clipTop: Int = 0,
        clipRightExclusive: Int = width,
        clipBottomExclusive: Int = height
    ): List<N64RdpTriangleSpan> {
        require(width in 1..N64ViFramebufferReader.MAX_WIDTH)
        require(height in 1..N64ViFramebufferReader.MAX_HEIGHT)
        require(clipLeft <= clipRightExclusive && clipTop <= clipBottomExclusive)

        val leftClip = clipLeft.coerceIn(0, width)
        val rightClip = clipRightExclusive.coerceIn(0, width)
        val topClip = clipTop.coerceIn(0, height)
        val bottomClip = clipBottomExclusive.coerceIn(0, height)
        if (leftClip >= rightClip || topClip >= bottomClip) return emptyList()
        if (setup.yLow <= setup.yHigh) return emptyList()
        require(setup.yMiddle in setup.yHigh..setup.yLow) {
            "Triangle middle Y must lie between high Y and low Y."
        }

        val spans = ArrayList<N64RdpTriangleSpan>()
        for (y in topClip until bottomClip) {
            val sampleY = y.toLong() * SUBPIXELS_PER_PIXEL + SUBPIXELS_PER_PIXEL / 2L
            if (sampleY < setup.yHigh.toLong() || sampleY >= setup.yLow.toLong()) continue

            val majorX = interpolateEdge(
                setup.xHigh,
                setup.dxHighDy,
                sampleY - setup.yHigh.toLong()
            )
            val minorX = if (sampleY < setup.yMiddle.toLong()) {
                interpolateEdge(
                    setup.xMiddle,
                    setup.dxMiddleDy,
                    sampleY - setup.yHigh.toLong()
                )
            } else {
                interpolateEdge(
                    setup.xLow,
                    setup.dxLowDy,
                    sampleY - setup.yLow.toLong()
                )
            }

            val leftX = if (setup.rightMajor) minorX else majorX
            val rightX = if (setup.rightMajor) majorX else minorX
            if (rightX <= leftX) continue

            val firstPixel = ceilDiv(leftX - HALF_PIXEL, FIXED_ONE).toInt()
            val endPixel = ceilDiv(rightX - HALF_PIXEL, FIXED_ONE).toInt()
            val xStart = maxOf(firstPixel, leftClip)
            val xEnd = minOf(endPixel, rightClip)
            if (xStart < xEnd) spans.add(N64RdpTriangleSpan(y, xStart, xEnd))
        }
        return spans
    }

    private fun interpolateEdge(originX: Int, slope: Int, deltaYQuarterPixels: Long): Long {
        return originX.toLong() + slope.toLong() * deltaYQuarterPixels / SUBPIXELS_PER_PIXEL
    }

    private fun ceilDiv(value: Long, divisor: Long): Long {
        return -Math.floorDiv(-value, divisor)
    }
}
