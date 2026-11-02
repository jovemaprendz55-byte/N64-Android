package com.jsr.n64android

/**
 * Partial RDP command interpreter used by the DPC bridge.
 *
 * Supports fill-cycle rectangles and a deliberately limited flat RGBA16 triangle
 * path. It is not a complete RDP implementation and has no texture, shade, Z,
 * coverage, blending, or combiner pipeline.
 */
class N64RdpFillProcessor(
    private val memory: N64AddressSpace,
    private val rdramSize: Int
) {
    private var colorImageAddress = 0
    private var colorImageWidth = 0
    private var colorImageSize = 0
    private var fillColor = 0
    private var primitiveColor = 0xFFFFFFFF.toInt()
    private var cycleType = CYCLE_TYPE_ONE
    private var scissorLeft = 0
    private var scissorTop = 0
    private var scissorRight = Int.MAX_VALUE
    private var scissorBottom = Int.MAX_VALUE

    data class Result(
        val commandsProcessed: Int,
        val rectanglesFilled: Int,
        val pixelsWritten: Int,
        val trianglesRasterized: Int = 0
    )

    fun execute(commands: List<Long>): Result {
        var rectangles = 0
        var pixels = 0
        commands.forEach { command ->
            when (((command ushr 58) and 0x3FL).toInt()) {
                CMD_LOAD_SYNC, CMD_PIPE_SYNC, CMD_TILE_SYNC -> Unit
                CMD_SET_OTHER_MODES -> {
                    cycleType = ((command ushr 52) and 0x3L).toInt()
                }
                CMD_SET_COLOR_IMAGE -> configureColorImage(command)
                CMD_SET_SCISSOR -> configureScissor(command)
                CMD_SET_FILL_COLOR -> fillColor = command.toInt()
                CMD_SET_PRIM_COLOR -> primitiveColor = command.toInt()
                CMD_FILL_RECTANGLE -> {
                    check(cycleType == CYCLE_TYPE_FILL) {
                        "FillRectangle requires RDP fill-cycle mode."
                    }
                    pixels += fillRectangle(command)
                    rectangles++
                }
                else -> throw IllegalArgumentException(
                    "Unsupported RDP command: 0x${((command ushr 58) and 0x3FL).toString(16)}"
                )
            }
        }
        return Result(commands.size, rectangles, pixels)
    }

    private fun configureColorImage(command: Long) {
        val format = ((command ushr 53) and 0x7L).toInt()
        val size = ((command ushr 51) and 0x3L).toInt()
        require(format == IMAGE_FORMAT_RGBA) { "Only RGBA color images are supported." }
        require(size == IMAGE_SIZE_16 || size == IMAGE_SIZE_32) {
            "Unsupported color image size: $size"
        }
        colorImageWidth = (((command ushr 32) and 0x3FFL).toInt()) + 1
        colorImageAddress = (command and 0x03FFFFFFL).toInt()
        colorImageSize = size
        require(colorImageAddress in 0 until rdramSize)
    }

    private fun configureScissor(command: Long) {
        val left = ((command ushr 44) and 0xFFFL).toInt()
        val top = ((command ushr 32) and 0xFFFL).toInt()
        val right = ((command ushr 12) and 0xFFFL).toInt()
        val bottom = (command and 0xFFFL).toInt()
        scissorLeft = (left + 3) ushr 2
        scissorTop = (top + 3) ushr 2
        scissorRight = right ushr 2
        scissorBottom = bottom ushr 2
    }

    private fun fillRectangle(command: Long): Int {
        check(colorImageWidth > 0) { "SetColorImage must precede FillRectangle." }
        val right = ((command ushr 44) and 0xFFFL).toInt() ushr 2
        val bottom = ((command ushr 32) and 0xFFFL).toInt() ushr 2
        val left = ((command ushr 12) and 0xFFFL).toInt() ushr 2
        val top = (command and 0xFFFL).toInt() ushr 2

        val x0 = maxOf(left, scissorLeft, 0)
        val y0 = maxOf(top, scissorTop, 0)
        val x1 = minOf(right, scissorRight, colorImageWidth - 1)
        val y1 = minOf(bottom, scissorBottom, MAX_COORDINATE)
        if (x1 < x0 || y1 < y0) return 0

        val bytesPerPixel = if (colorImageSize == IMAGE_SIZE_16) 2 else 4
        var written = 0
        for (y in y0..y1) {
            for (x in x0..x1) {
                val addressLong = colorImageAddress.toLong() +
                    (y.toLong() * colorImageWidth + x.toLong()) * bytesPerPixel
                require(addressLong >= 0 && addressLong + bytesPerPixel <= rdramSize.toLong()) {
                    "RDP fill exceeds RDRAM at 0x${addressLong.toString(16)}."
                }
                val address = addressLong.toInt()
                if (colorImageSize == IMAGE_SIZE_16) {
                    val half = if (((y * colorImageWidth + x) and 1) == 0) {
                        fillColor ushr 16
                    } else {
                        fillColor and 0xFFFF
                    }
                    memory.write16(address, half)
                } else {
                    memory.write32(address, fillColor)
                }
                written++
            }
        }
        return written
    }

    /**
     * Dispatches fully framed packets from the DPC parser. The first executable
     * multiword subset is opcode 0x08: flat, untextured, non-Z triangles into RGBA16.
     * Other multiword triangle variants remain explicitly unsupported.
     */
    /** Validates support for every packet before the first state change or pixel write. */
    fun validatePackets(packets: List<N64RdpCommandPacket>) {
        val singleWordOpcodes = setOf(
            CMD_LOAD_SYNC,
            CMD_PIPE_SYNC,
            CMD_TILE_SYNC,
            CMD_SET_OTHER_MODES,
            CMD_SET_COLOR_IMAGE,
            CMD_SET_SCISSOR,
            CMD_SET_FILL_COLOR,
            CMD_SET_PRIM_COLOR,
            CMD_FILL_RECTANGLE
        )
        for (packet in packets) {
            if (packet.isSingleWord) {
                require(packet.opcode in singleWordOpcodes) {
                    "Unsupported RDP command: 0x${packet.opcode.toString(16)}"
                }
            } else if (packet.opcode == 0x08) {
                N64RdpTriangleSetup.decode(packet)
            } else {
                throw UnsupportedOperationException(
                    "RDP multiword command 0x${packet.opcode.toString(16)} execution is not implemented."
                )
            }
        }
    }

    fun executePackets(packets: List<N64RdpCommandPacket>): Result {
        validatePackets(packets)
        var commandsProcessed = 0
        var rectanglesFilled = 0
        var pixelsWritten = 0
        var trianglesRasterized = 0

        for (packet in packets) {
            if (packet.isSingleWord) {
                val result = execute(listOf(packet.firstWord))
                commandsProcessed += result.commandsProcessed
                rectanglesFilled += result.rectanglesFilled
                pixelsWritten += result.pixelsWritten
                continue
            }
            if (packet.opcode != 0x08) {
                throw UnsupportedOperationException(
                    "RDP multiword command 0x${packet.opcode.toString(16)} execution is not implemented."
                )
            }
            check(colorImageWidth > 0) { "SetColorImage must precede a triangle." }
            check(colorImageSize == IMAGE_SIZE_16) {
                "Flat triangle rasterization currently requires an RGBA16 color image."
            }
            val availableHeight = (rdramSize - colorImageAddress) / (colorImageWidth * 2)
            require(availableHeight > 0) { "Triangle color image has no available RDRAM rows." }
            val height = minOf(N64ViFramebufferReader.MAX_HEIGHT, availableHeight)
            val setup = N64RdpTriangleSetup.decode(packet)
            val result = N64RdpFlatTriangleRasterizer.render(
                setup = setup,
                memory = memory,
                rdramSize = rdramSize,
                colorImageAddress = colorImageAddress,
                width = colorImageWidth,
                height = height,
                colorRgba5551 = rgba8888ToRgba5551(primitiveColor),
                clipLeft = scissorLeft,
                clipTop = scissorTop,
                clipRightExclusive = minOf(scissorRight, colorImageWidth),
                clipBottomExclusive = minOf(scissorBottom, height)
            )
            commandsProcessed++
            trianglesRasterized++
            pixelsWritten += result.pixelsWritten
        }
        return Result(commandsProcessed, rectanglesFilled, pixelsWritten, trianglesRasterized)
    }

    private fun rgba8888ToRgba5551(color: Int): Int {
        val red = (color ushr 24) and 0xFF
        val green = (color ushr 16) and 0xFF
        val blue = (color ushr 8) and 0xFF
        val alpha = color and 0xFF
        return ((red * 31 / 255) shl 11) or
            ((green * 31 / 255) shl 6) or
            ((blue * 31 / 255) shl 1) or
            if (alpha >= 128) 1 else 0
    }
    companion object {
        const val CMD_LOAD_SYNC = 0x26
        const val CMD_PIPE_SYNC = 0x27
        const val CMD_TILE_SYNC = 0x28
        const val CMD_SET_COLOR_IMAGE = 0x3F
        const val CMD_SET_SCISSOR = 0x2D
        const val CMD_SET_FILL_COLOR = 0x37
        const val CMD_SET_PRIM_COLOR = 0x3A
        const val CMD_FILL_RECTANGLE = 0x36
        const val CMD_SET_OTHER_MODES = 0x2F
        const val IMAGE_FORMAT_RGBA = 0
        const val IMAGE_SIZE_16 = 2
        const val IMAGE_SIZE_32 = 3
        const val CYCLE_TYPE_ONE = 0
        const val CYCLE_TYPE_FILL = 3
        private const val MAX_COORDINATE = 0x3FF
    }
}
