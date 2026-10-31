package com.jsr.n64android

/**
 * Small, isolated RDP command interpreter for deterministic fill-cycle tests.
 *
 * Supported commands: SetOtherModes (cycle type), SetColorImage (RGBA16/32),
 * SetScissor, SetFillColor and FillRectangle. It is not connected to the RSP
 * or DPC command processor yet and is not a complete RDP implementation.
 */
class N64RdpFillProcessor(
    private val memory: N64AddressSpace,
    private val rdramSize: Int
) {
    private var colorImageAddress = 0
    private var colorImageWidth = 0
    private var colorImageSize = 0
    private var fillColor = 0
    private var cycleType = CYCLE_TYPE_ONE
    private var scissorLeft = 0
    private var scissorTop = 0
    private var scissorRight = Int.MAX_VALUE
    private var scissorBottom = Int.MAX_VALUE

    data class Result(
        val commandsProcessed: Int,
        val rectanglesFilled: Int,
        val pixelsWritten: Int
    )

    fun execute(commands: List<Long>): Result {
        var rectangles = 0
        var pixels = 0
        commands.forEach { command ->
            when (((command ushr 58) and 0x3FL).toInt()) {
                CMD_SET_OTHER_MODES -> {
                    val value = command.toInt()
                    cycleType = (value ushr 20) and 0x3
                }
                CMD_SET_COLOR_IMAGE -> configureColorImage(command)
                CMD_SET_SCISSOR -> configureScissor(command)
                CMD_SET_FILL_COLOR -> fillColor = command.toInt()
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

    companion object {
        const val CMD_SET_COLOR_IMAGE = 0x3F
        const val CMD_SET_SCISSOR = 0x2D
        const val CMD_SET_FILL_COLOR = 0x37
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
