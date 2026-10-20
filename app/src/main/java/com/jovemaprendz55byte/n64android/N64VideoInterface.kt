package com.jovemaprendz55byte.n64android

/**
 * Minimal VI register bank and framebuffer scanout from RDRAM.
 *
 * This models register storage and the two common framebuffer pixel sizes. It
 * does not emulate VI timing, interrupts, gamma/AA filtering, or RDP commands.
 */
internal class N64VideoInterface {
    private val registers = IntArray(REGISTER_COUNT)

    fun readRegister(physicalAddress: Long): Int {
        val index = registerIndex(physicalAddress)
        return registers[index]
    }

    fun writeRegister(physicalAddress: Long, value: Int) {
        val index = registerIndex(physicalAddress)
        registers[index] = when (physicalAddress) {
            VI_ORIGIN -> value and 0x00FF_FFF8
            VI_WIDTH -> value and 0xFFF
            VI_STATUS -> value and 0x3
            else -> value
        }
    }

    /**
     * Copies the active framebuffer from RDRAM into [destination].
     * [outputHeight] is supplied by the caller because VI active height is
     * derived from timing registers and is not yet calculated by this model.
     * Returns false for blank/unsupported formats or invalid RDRAM ranges.
     */
    fun scanout(
        rdram: ByteArray,
        destination: N64VideoFrameBuffer,
        outputHeight: Int = destination.height
    ): Boolean {
        val pixelSize = registers[indexOf(VI_STATUS)] and 0x3
        val bytesPerPixel = when (pixelSize) {
            2 -> 2 // RGBA5551
            3 -> 4 // RGBA8888
            else -> return false
        }
        val origin = registers[indexOf(VI_ORIGIN)]
        val sourceWidth = registers[indexOf(VI_WIDTH)]
        val height = minOf(outputHeight, destination.height)
        if (sourceWidth <= 0 || height <= 0 || sourceWidth > destination.width) return false

        val rowBytes = sourceWidth.toLong() * bytesPerPixel
        val finalExclusive = origin.toLong() + (height - 1L) * rowBytes + rowBytes
        if (origin < 0 || finalExclusive > rdram.size.toLong()) return false

        // A smaller active VI image must not leave pixels from a previous frame
        // visible in the unused part of the host framebuffer.
        destination.clear()

        for (y in 0 until height) {
            val rowStart = origin + y * rowBytes.toInt()
            for (x in 0 until sourceWidth) {
                val p = rowStart + x * bytesPerPixel
                when (pixelSize) {
                    2 -> {
                        val value = ((rdram[p].toInt() and 0xFF) shl 8) or
                            (rdram[p + 1].toInt() and 0xFF)
                        destination.setRgba5551(x, y, value)
                    }
                    3 -> {
                        val red = rdram[p].toInt() and 0xFF
                        val green = rdram[p + 1].toInt() and 0xFF
                        val blue = rdram[p + 2].toInt() and 0xFF
                        val alpha = rdram[p + 3].toInt() and 0xFF
                        destination.setPixelArgb(x, y,
                            (alpha shl 24) or (red shl 16) or (green shl 8) or blue)
                    }
                }
            }
        }
        return true
    }

    private fun registerIndex(address: Long): Int {
        require(address >= VI_STATUS && address <= VI_Y_SCALE && address % 4L == 0L) {
            "Invalid VI register address 0x${address.toString(16)}"
        }
        return ((address - VI_STATUS) / 4L).toInt()
    }

    private fun indexOf(address: Long): Int = ((address - VI_STATUS) / 4L).toInt()

    companion object {
        const val VI_STATUS = 0x0440_0000L
        const val VI_ORIGIN = 0x0440_0004L
        const val VI_WIDTH = 0x0440_0008L
        const val VI_V_INTR = 0x0440_000CL
        const val VI_V_CURRENT = 0x0440_0010L
        const val VI_BURST = 0x0440_0014L
        const val VI_V_SYNC = 0x0440_0018L
        const val VI_H_SYNC = 0x0440_001CL
        const val VI_LEAP = 0x0440_0020L
        const val VI_H_START = 0x0440_0024L
        const val VI_V_START = 0x0440_0028L
        const val VI_V_BURST = 0x0440_002CL
        const val VI_X_SCALE = 0x0440_0030L
        const val VI_Y_SCALE = 0x0440_0034L

        private const val REGISTER_COUNT = 14
    }
}
