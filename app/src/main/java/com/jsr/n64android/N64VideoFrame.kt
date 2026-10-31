package com.jsr.n64android

/** Immutable image captured from RDRAM using VI origin, stride and pixel-size fields. */
data class N64VideoFrame(
    val width: Int,
    val height: Int,
    val argbPixels: IntArray,
    val origin: Int,
    val pixelSize: Int
) {
    init {
        require(width > 0 && height > 0)
        require(argbPixels.size == width * height)
    }
}

/**
 * Converts VI_STATUS pixel size 2 (RGBA5551) and 3 (RGBA8888).
 * N64 framebuffer pixels are stored in big-endian byte order.
 * Active-height/timing calculation is intentionally not inferred here yet.
 */
class N64ViFramebufferReader(private val memory: N64AddressSpace, private val rdramSize: Int) {
    fun capture(origin: Int, width: Int, height: Int, viStatus: Int): N64VideoFrame {
        require(width in 1..MAX_WIDTH) { "Unsupported VI width: $width" }
        require(height in 1..MAX_HEIGHT) { "Unsupported VI height: $height" }
        val pixelSize = viStatus and VI_STATUS_PIXEL_SIZE_MASK
        require(pixelSize == VI_STATUS_PIXEL_SIZE_16 || pixelSize == VI_STATUS_PIXEL_SIZE_32) {
            "Unsupported VI pixel size: $pixelSize"
        }

        val bytesPerPixel = if (pixelSize == VI_STATUS_PIXEL_SIZE_16) 2 else 4
        val byteCount = width.toLong() * height.toLong() * bytesPerPixel
        val start = origin.toLong() and RDRAM_ADDRESS_MASK
        require(start + byteCount <= rdramSize.toLong()) {
            "VI framebuffer exceeds RDRAM: origin=0x${start.toString(16)}, bytes=$byteCount, RDRAM=$rdramSize"
        }

        val pixels = IntArray(width * height)
        var address = start.toInt()
        for (index in pixels.indices) {
            pixels[index] = if (pixelSize == VI_STATUS_PIXEL_SIZE_16) {
                val value = (memory.read8(address) shl 8) or memory.read8(address + 1)
                address += 2
                val red = (value ushr 11) and 0x1F
                val green = (value ushr 6) and 0x1F
                val blue = (value ushr 1) and 0x1F
                val alpha = if ((value and 1) != 0) 0xFF else 0x00
                (alpha shl 24) or ((red shl 3 or (red ushr 2)) shl 16) or
                    ((green shl 3 or (green ushr 2)) shl 8) or (blue shl 3 or (blue ushr 2))
            } else {
                val red = memory.read8(address)
                val green = memory.read8(address + 1)
                val blue = memory.read8(address + 2)
                val alpha = memory.read8(address + 3)
                address += 4
                (alpha shl 24) or (red shl 16) or (green shl 8) or blue
            }
        }
        return N64VideoFrame(width, height, pixels, start.toInt(), pixelSize)
    }

    companion object {
        const val VI_STATUS_PIXEL_SIZE_MASK = 0x3
        const val VI_STATUS_PIXEL_SIZE_16 = 2
        const val VI_STATUS_PIXEL_SIZE_32 = 3
        const val MAX_WIDTH = 1024
        const val MAX_HEIGHT = 1024
        private const val RDRAM_ADDRESS_MASK = 0x00FFFFFFL
    }
}
