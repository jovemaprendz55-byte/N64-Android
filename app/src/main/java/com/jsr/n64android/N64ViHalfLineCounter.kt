package com.jsr.n64android

/**
 * Isolated VI half-line counter model.
 *
 * VI_V_SYNC is treated as a terminal count, so the number of counter
 * positions is registerValue + 1. One scanline sample advances VI_CURRENT
 * by two half-line units. Interlace field parity is represented by bit 0.
 *
 * This helper is intentionally not connected to N64Bus timing yet.
 */
internal class N64ViHalfLineCounter(
    initialCurrent: Int = 0
) {
    var current: Int = initialCurrent
        private set

    init {
        require(initialCurrent >= 0) { "initialCurrent must be non-negative" }
    }

    fun advanceScanline(viVSync: Int): Int {
        require(viVSync >= 0) { "VI_V_SYNC terminal count must be non-negative" }
        val positions = viVSync.toLong() + 1L
        current = ((current.toLong() + HALF_LINES_PER_SCANLINE) % positions).toInt()
        return current
    }

    val fieldParity: Int
        get() = current and 1

    companion object {
        const val HALF_LINES_PER_SCANLINE = 2
    }
}
