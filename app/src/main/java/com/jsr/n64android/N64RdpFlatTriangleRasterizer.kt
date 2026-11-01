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

/** End-to-end synthetic DPC display list -> RDP -> RDRAM -> VI capture. Not a ROM. */
object N64TrianglePipelineDemo {
    const val WIDTH = 320
    const val HEIGHT = 240
    const val ORIGIN = 0x1000
    const val DISPLAY_LIST_ADDRESS = 0x30000
    const val TRIANGLE_COLOR = 0xFF0000FF.toInt()
    const val BACKGROUND_COLOR = 0x0001

    data class FrameResult(
        val frame: N64VideoFrame,
        val commandsProcessed: Int,
        val trianglesRasterized: Int,
        val pixelsWritten: Int
    )

    fun createFrame(): FrameResult {
        val memory = N64Memory()
        val bus = N64Bus(ram = memory)
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                memory.write16(ORIGIN + (y * WIDTH + x) * 2, BACKGROUND_COLOR)
            }
        }

        val commands = listOf(
            command(
                N64RdpFillProcessor.CMD_SET_COLOR_IMAGE,
                high = (N64RdpFillProcessor.IMAGE_SIZE_16.toLong() shl 19) or (WIDTH - 1).toLong(),
                low = ORIGIN.toLong()
            ),
            command(
                N64RdpFillProcessor.CMD_SET_SCISSOR,
                low = (WIDTH.toLong() * 4L shl 12) or (HEIGHT.toLong() * 4L)
            ),
            command(N64RdpFillProcessor.CMD_SET_PRIM_COLOR, low = TRIANGLE_COLOR.toLong() and 0xFFFFFFFFL)
        )
        writeCommands(memory, DISPLAY_LIST_ADDRESS, commands)

        val triangleHeader = (0x08L shl 58) or
            (190L * 4L shl 32) or
            (100L * 4L shl 16) or
            (40L * 4L)
        val triangleWords = listOf(
            triangleHeader,
            packEdgeWord(230 shl 16, 0),
            packEdgeWord(80 shl 16, 0),
            packEdgeWord(160 shl 16, 0)
        )
        writeWords(memory, DISPLAY_LIST_ADDRESS + commands.size * 8, triangleWords)

        val dpc = N64DpcCommandProcessor(
            memory = memory,
            rdramSize = memory.size,
            rdp = N64RdpFillProcessor(memory, memory.size)
        )
        val dpcResult = dpc.process(
            DISPLAY_LIST_ADDRESS,
            DISPLAY_LIST_ADDRESS + commands.size * 8 + triangleWords.size * 8
        )

        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_STATUS_OFFSET,
            N64ViFramebufferReader.VI_STATUS_PIXEL_SIZE_16)
        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_ORIGIN_OFFSET, ORIGIN)
        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_WIDTH_OFFSET, WIDTH)
        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_H_START_OFFSET, (0x20 shl 16) or (0x20 + WIDTH))
        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_X_SCALE_OFFSET, 0x400)
        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_V_START_OFFSET, (0x20 shl 16) or 0x200)
        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_Y_SCALE_OFFSET, 0x400)

        val frame = checkNotNull(bus.captureViFrameFromRegisters()) {
            "VI did not capture the DPC triangle framebuffer."
        }
        return FrameResult(
            frame = frame,
            commandsProcessed = dpcResult.commandsProcessed,
            trianglesRasterized = dpcResult.trianglesRasterized,
            pixelsWritten = dpcResult.pixelsWritten
        )
    }

    private fun command(opcode: Int, high: Long = 0L, low: Long = 0L): Long =
        (opcode.toLong() shl 58) or (high shl 32) or (low and 0xFFFFFFFFL)

    private fun packEdgeWord(x: Int, slope: Int): Long =
        ((x.toLong() and 0xFFFFFFFFL) shl 32) or (slope.toLong() and 0xFFFFFFFFL)

    private fun writeCommands(memory: N64Memory, start: Int, commands: List<Long>) {
        writeWords(memory, start, commands)
    }

    private fun writeWords(memory: N64Memory, start: Int, words: List<Long>) {
        words.forEachIndexed { index, word ->
            val address = start + index * 8
            memory.write32(address, (word ushr 32).toInt())
            memory.write32(address + 4, word.toInt())
        }
    }
}
