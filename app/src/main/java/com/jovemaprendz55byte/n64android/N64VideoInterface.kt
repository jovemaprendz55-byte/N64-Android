package com.jovemaprendz55byte.n64android

/**
 * Initial VI register block and framebuffer scanout.
 *
 * This converts an RDRAM framebuffer configured through VI registers to opaque
 * ARGB pixels. It is a scanout foundation, not an RDP command processor: games
 * that render through display lists still need DPC/RDP support and timing.
 */
internal class N64VideoInterface(
    private val readWord: (Long) -> Int,
    private val readHalf: (Long) -> Short
) {
    data class Frame(val width: Int, val height: Int, val pixels: IntArray)

    private val registers = IntArray(14)

    companion object {
        const val BASE = 0x0440_0000L
        private const val CONTROL = 0x00
        private const val ORIGIN = 0x04
        private const val WIDTH = 0x08
        private const val V_SYNC = 0x18
        private const val V_START = 0x28
        private const val X_SCALE = 0x30
        private const val Y_SCALE = 0x34
        private const val MAX_WIDTH = 640
        private const val MAX_HEIGHT = 480
    }

    fun readRegister(address: Long): Int {
        val index = registerIndex(address)
        return if (index == 4) 0 else registers[index]
    }

    fun writeRegister(address: Long, value: Int) {
        val index = registerIndex(address)
        if (index == 4) return // VI_CURRENT is read-only.
        registers[index] = when (index) {
            1 -> value and 0x00FF_FFF8 // VI_ORIGIN is an aligned RDRAM address.
            2 -> value and 0x0FFF
            else -> value
        }
    }

    /** Returns the currently configured framebuffer, or null if VI is disabled/invalid. */
    fun captureFrame(): Frame? {
        val control = registers[CONTROL / 4]
        val pixelType = control and 0x3
        if (pixelType !in 2..3) return null

        val width = registers[WIDTH / 4] and 0x0FFF
        if (width !in 1..MAX_WIDTH) return null

        val vStart = registers[V_START / 4]
        val start = (vStart ushr 16) and 0x3FF
        val end = vStart and 0x3FF
        val activeLines = if (end > start) (end - start) / 2 else 0
        val height = activeLines.coerceIn(1, MAX_HEIGHT).takeIf { activeLines > 0 }
            ?: if ((registers[V_SYNC / 4] and 0x3FF) >= 600) 288 else 240

        val bytesPerPixel = if (pixelType == 2) 2 else 4
        val origin = registers[ORIGIN / 4].toLong() and 0x00FF_FFFFL
        val xScale = registers[X_SCALE / 4] and 0x0FFF
        val yScale = registers[Y_SCALE / 4] and 0x0FFF
        val xStep = (xScale and 0x0FFF).takeIf { it != 0 } ?: 0x400
        val yStep = (yScale and 0x0FFF).takeIf { it != 0 } ?: 0x400
        val output = IntArray(width * height)

        return try {
            for (y in 0 until height) {
                val sourceY = ((y.toLong() * yStep) ushr 10).toInt()
                for (x in 0 until width) {
                    val sourceX = ((x.toLong() * xStep) ushr 10).toInt()
                    val address = origin + (sourceY.toLong() * width + sourceX) * bytesPerPixel
                    output[y * width + x] = if (pixelType == 2) {
                        val rgba5551 = readHalf(address).toInt() and 0xFFFF
                        val red = ((rgba5551 ushr 11) and 0x1F) * 255 / 31
                        val green = ((rgba5551 ushr 6) and 0x1F) * 255 / 31
                        val blue = ((rgba5551 ushr 1) and 0x1F) * 255 / 31
                        (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
                    } else {
                        val rgba = readWord(address)
                        val red = (rgba ushr 24) and 0xFF
                        val green = (rgba ushr 16) and 0xFF
                        val blue = (rgba ushr 8) and 0xFF
                        (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
                    }
                }
            }
            Frame(width, height, output)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun registerIndex(address: Long): Int {
        require(address >= BASE && address <= BASE + 0x34 && (address - BASE) % 4L == 0L) {
            "Invalid VI register address 0x${address.toString(16)}"
        }
        return ((address - BASE) / 4L).toInt()
    }
}
