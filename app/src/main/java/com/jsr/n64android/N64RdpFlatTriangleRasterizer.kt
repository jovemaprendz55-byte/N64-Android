package com.jsr.n64android

/**
 * First deliberately small RDP rasterization subset: flat RGBA5551 triangles.
 *
 * This consumes the decoded edge setup and writes pixels into RDRAM. It does
 * not implement coverage, combiner, blending, Z, texture, or edge tie-breaking
 * beyond the current experimental span walker.
 */
object N64RdpFlatTriangleRasterizer {
    data class Result(val spansWritten: Int, val pixelsWritten: Int)

    fun render(
        setup: N64RdpTriangleSetup,
        memory: N64AddressSpace,
        rdramSize: Int,
        colorImageAddress: Int,
        width: Int,
        height: Int,
        colorRgba5551: Int,
        clipLeft: Int = 0,
        clipTop: Int = 0,
        clipRightExclusive: Int = width,
        clipBottomExclusive: Int = height
    ): Result {
        require(rdramSize > 0)
        require(colorImageAddress >= 0)
        require(width in 1..N64ViFramebufferReader.MAX_WIDTH)
        require(height in 1..N64ViFramebufferReader.MAX_HEIGHT)
        val end = colorImageAddress.toLong() + width.toLong() * height.toLong() * 2L
        require(end <= rdramSize.toLong()) { "Triangle color image exceeds RDRAM." }

        val spans = N64RdpTriangleEdgeWalker.walk(
            setup, width, height, clipLeft, clipTop, clipRightExclusive, clipBottomExclusive
        )
        var pixelsWritten = 0
        for (span in spans) {
            var address = colorImageAddress + (span.y * width + span.xStart) * 2
            for (x in span.xStart until span.xEndExclusive) {
                memory.write16(address, colorRgba5551 and 0xFFFF)
                address += 2
                pixelsWritten++
            }
        }
        return Result(spans.size, pixelsWritten)
    }
}

/** End-to-end synthetic proof: triangle spans -> RDRAM -> VI capture. Not a ROM. */
object N64TrianglePipelineDemo {
    const val WIDTH = 320
    const val HEIGHT = 240
    const val ORIGIN = 0x1000
    const val TRIANGLE_COLOR = 0xF801
    const val BACKGROUND_COLOR = 0x0001

    data class FrameResult(val frame: N64VideoFrame, val spansWritten: Int, val pixelsWritten: Int)

    fun createFrame(): FrameResult {
        val memory = N64Memory()
        val bus = N64Bus(ram = memory)
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                memory.write16(ORIGIN + (y * WIDTH + x) * 2, BACKGROUND_COLOR)
            }
        }

        val setup = N64RdpTriangleSetup(
            opcode = 0x08,
            rightMajor = false,
            tile = 0,
            yLow = 190 * 4,
            yMiddle = 100 * 4,
            yHigh = 40 * 4,
            xLow = 230 shl 16,
            dxLowDy = 0,
            xHigh = 80 shl 16,
            dxHighDy = 0,
            xMiddle = 160 shl 16,
            dxMiddleDy = 0
        )
        val result = N64RdpFlatTriangleRasterizer.render(
            setup = setup,
            memory = memory,
            rdramSize = memory.size,
            colorImageAddress = ORIGIN,
            width = WIDTH,
            height = HEIGHT,
            colorRgba5551 = TRIANGLE_COLOR
        )

        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_STATUS_OFFSET,
            N64ViFramebufferReader.VI_STATUS_PIXEL_SIZE_16)
        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_ORIGIN_OFFSET, ORIGIN)
        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_WIDTH_OFFSET, WIDTH)
        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_V_START_OFFSET, (0x20 shl 16) or 0x200)
        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_Y_SCALE_OFFSET, 0x400)

        val frame = checkNotNull(bus.captureViFrameFromRegisters()) {
            "VI did not capture the triangle framebuffer."
        }
        return FrameResult(frame, result.spansWritten, result.pixelsWritten)
    }
}
