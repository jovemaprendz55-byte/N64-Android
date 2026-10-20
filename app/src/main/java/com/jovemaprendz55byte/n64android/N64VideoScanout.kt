package com.jovemaprendz55byte.n64android

/**
 * Converts linear N64 framebuffer memory into a host frame.
 *
 * The VI register model and RDRAM address mapping are intentionally separate;
 * callers must pass bytes from a valid framebuffer range. Supports common
 * 16-bit RGBA5551 and 32-bit RGBA8888 source pixels.
 */
object N64VideoScanout {
    fun copyRgba5551(
        source: ByteArray,
        sourceOffset: Int = 0,
        sourceWidth: Int,
        sourceHeight: Int,
        sourceStride: Int = sourceWidth * 2,
        destination: N64VideoFrameBuffer,
        destinationX: Int = 0,
        destinationY: Int = 0
    ) {
        require(sourceWidth >= 0 && sourceHeight >= 0) { "Source dimensions cannot be negative" }
        require(sourceWidth <= Int.MAX_VALUE / 2) { "Source row is too wide" }
        require(sourceStride >= sourceWidth * 2) { "Source stride is smaller than a row" }
        require(sourceOffset >= 0 && sourceOffset <= source.size) { "Invalid source offset" }
        if (sourceWidth == 0 || sourceHeight == 0) return

        for (sy in 0 until sourceHeight) {
            val dy = destinationY.toLong() + sy
            if (dy !in 0L until destination.height.toLong()) continue
            for (sx in 0 until sourceWidth) {
                val dx = destinationX.toLong() + sx
                if (dx !in 0L until destination.width.toLong()) continue
                val p = sourceOffset.toLong() + sy.toLong() * sourceStride + sx.toLong() * 2L
                require(p >= 0L && p + 1L < source.size.toLong()) {
                    "Source buffer does not contain the requested pixels"
                }
                val pixel = ((source[p.toInt()].toInt() and 0xFF) shl 8) or
                    (source[p.toInt() + 1].toInt() and 0xFF)
                destination.setRgba5551(dx.toInt(), dy.toInt(), pixel)
            }
        }
    }

    fun copyRgba8888(
        source: ByteArray,
        sourceOffset: Int = 0,
        sourceWidth: Int,
        sourceHeight: Int,
        sourceStride: Int = sourceWidth * 4,
        destination: N64VideoFrameBuffer,
        destinationX: Int = 0,
        destinationY: Int = 0
    ) {
        destination.copyRgba8888(
            source = source,
            sourceOffset = sourceOffset,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            sourceStride = sourceStride,
            destinationX = destinationX,
            destinationY = destinationY
        )
    }
}
