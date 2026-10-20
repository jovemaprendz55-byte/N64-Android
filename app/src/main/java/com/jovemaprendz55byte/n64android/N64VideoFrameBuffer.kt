package com.jovemaprendz55byte.n64android

/**
 * CPU-side frame storage for the future VI/RDP renderer.
 *
 * Pixels are stored as Android-style ARGB ints. This class does not execute RDP
 * commands, read VI registers, or present frames on screen; it is a tested
 * conversion and clipping boundary for those components.
 */
class N64VideoFrameBuffer(val width: Int, val height: Int) {
    private val pixels: IntArray

    init {
        require(width in 1..MAX_DIMENSION) { "Frame width must be between 1 and $MAX_DIMENSION" }
        require(height in 1..MAX_DIMENSION) { "Frame height must be between 1 and $MAX_DIMENSION" }
        require(width.toLong() * height.toLong() <= MAX_PIXELS) { "Frame is too large" }
        pixels = IntArray(width * height)
    }

    val pixelCount: Int get() = pixels.size

    /** Clears the whole frame to an ARGB color (0xAARRGGBB). */
    fun clear(argb: Int = 0xFF000000.toInt()) {
        pixels.fill(argb)
    }

    /** Sets a pixel from an ARGB int (0xAARRGGBB). Out-of-frame writes are ignored. */
    fun setPixelArgb(x: Int, y: Int, argb: Int) {
        if (!contains(x, y)) return
        pixels[y * width + x] = argb
    }

    /** Sets one pixel from a 16-bit RGBA5551 value. Out-of-frame writes are ignored. */
    fun setRgba5551(x: Int, y: Int, value: Int) {
        if (!contains(x, y)) return
        val red5 = (value ushr 11) and 0x1F
        val green5 = (value ushr 6) and 0x1F
        val blue5 = (value ushr 1) and 0x1F
        val alpha = if ((value and 1) != 0) 0xFF else 0x00
        pixels[y * width + x] = (alpha shl 24) or
            (expand5(red5) shl 16) or (expand5(green5) shl 8) or expand5(blue5)
    }

    /**
     * Copies RGBA8888 bytes into the frame.
     * [sourceOffset] points to R,G,B,A bytes; [sourceStride] is the byte distance
     * between source rows. Rows/columns outside the frame are clipped.
     */
    fun copyRgba8888(
        source: ByteArray,
        sourceOffset: Int = 0,
        sourceWidth: Int,
        sourceHeight: Int,
        sourceStride: Int = sourceWidth * 4,
        destinationX: Int = 0,
        destinationY: Int = 0
    ) {
        require(sourceWidth >= 0 && sourceHeight >= 0) { "Source dimensions cannot be negative" }
        require(sourceStride >= sourceWidth * 4) { "Source stride is smaller than a row" }
        require(sourceOffset >= 0 && sourceOffset <= source.size) { "Invalid source offset" }
        if (sourceWidth == 0 || sourceHeight == 0) return

        for (sy in 0 until sourceHeight) {
            val dy = destinationY.toLong() + sy
            if (dy !in 0L until height.toLong()) continue
            val rowStart = sourceOffset.toLong() + sy.toLong() * sourceStride
            for (sx in 0 until sourceWidth) {
                val dx = destinationX.toLong() + sx
                if (dx !in 0L until width.toLong()) continue
                val p = rowStart + sx.toLong() * 4L
                require(p >= 0L && p + 3L < source.size.toLong()) {
                    "Source buffer does not contain the requested pixels"
                }
                val red = source[p.toInt()].toInt() and 0xFF
                val green = source[p.toInt() + 1].toInt() and 0xFF
                val blue = source[p.toInt() + 2].toInt() and 0xFF
                val alpha = source[p.toInt() + 3].toInt() and 0xFF
                pixels[dy.toInt() * width + dx.toInt()] =
                    (alpha shl 24) or (red shl 16) or (green shl 8) or blue
            }
        }
    }

    /** Returns an independent copy, safe for a renderer to consume. */
    fun snapshot(): IntArray = pixels.copyOf()

    fun pixelAt(x: Int, y: Int): Int {
        require(contains(x, y)) { "Pixel coordinates are outside the frame" }
        return pixels[y * width + x]
    }

    private fun contains(x: Int, y: Int): Boolean = x >= 0 && x < width && y >= 0 && y < height

    private fun expand5(value: Int): Int = (value shl 3) or (value ushr 2)

    companion object {
        private const val MAX_DIMENSION = 4096
        private const val MAX_PIXELS = 16L * 1024L * 1024L
    }
}
