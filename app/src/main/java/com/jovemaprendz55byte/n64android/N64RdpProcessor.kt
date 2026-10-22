package com.jovemaprendz55byte.n64android

/**
 * Clean-room, deliberately partial RDP command processor.
 *
 * Implements DPC display-list traversal and the RDP commands needed for a first
 * visible framebuffer: SetColorImage, SetFillColor, SetScissor and FillRectangle.
 * Texturing, triangle rasterization, blending, Z buffering, TMEM and RSP task
 * scheduling are not implemented yet.
 */
internal class N64RdpProcessor(private val rdram: ByteArray) {
    private val registers = IntArray(8)
    private var colorImageAddress = 0
    private var colorImageWidth = 0
    private var colorImageSize = 2
    private var fillColor = 0xFFFFFFFF.toInt()
    private var scissorLeft = 0
    private var scissorTop = 0
    private var scissorRight = 4096
    private var scissorBottom = 4096

    var commandsProcessed: Long = 0
        private set
    var rectanglesFilled: Long = 0
        private set
    var frameWrites: Long = 0
        private set
    var lastError: String? = null
        private set

    fun readRegister(address: Long): Int {
        val index = registerIndex(address)
        return when (index) {
            2 -> registers[2] // CURRENT
            3 -> registers[3] // STATUS
            4 -> 0 // CLOCK is not cycle-accurate
            5, 6, 7 -> 0 // busy/TMEM counters
            else -> registers[index]
        }
    }

    fun writeRegister(address: Long, value: Int) {
        val index = registerIndex(address)
        when (index) {
            0 -> {
                val start = value and 0x00FF_FFF8
                registers[0] = start
                if (registers[2] == 0) registers[2] = start
            }
            1 -> {
                registers[1] = value and 0x00FF_FFF8
                if (registers[2] == 0) registers[2] = registers[0]
                processDisplayList(registers[2], registers[1])
                registers[2] = registers[1]
            }
            3 -> {
                // DPC_STATUS uses command bits rather than direct register writes.
                if (value and (1 shl 0) != 0) registers[3] = registers[3] and STATUS_XBUS.inv()
                if (value and (1 shl 1) != 0) registers[3] = registers[3] or STATUS_XBUS
                if (value and (1 shl 2) != 0) registers[3] = registers[3] and STATUS_FREEZE.inv()
                if (value and (1 shl 3) != 0) registers[3] = registers[3] or STATUS_FREEZE
                if (value and (1 shl 4) != 0) registers[3] = registers[3] and STATUS_FLUSH.inv()
                if (value and (1 shl 5) != 0) registers[3] = registers[3] or STATUS_FLUSH
            }
            else -> registers[index] = value
        }
    }

    fun diagnosticSummary(): String =
        "RDP_COMMANDS=$commandsProcessed, RDP_RECTS=$rectanglesFilled, RDP_FRAME_WRITES=$frameWrites" +
            (lastError?.let { ", RDP_ERROR=$it" } ?: "")

    private fun processDisplayList(start: Int, end: Int) {
        lastError = null
        if (registers[3] and STATUS_FREEZE != 0) return
        if (start < 0 || end < start || end > rdram.size || (end - start) % 8 != 0) {
            lastError = "invalid DPC range start=0x${start.toString(16)} end=0x${end.toString(16)}"
            return
        }
        var address = start
        var budget = MAX_COMMANDS_PER_SUBMIT
        while (address < end && budget-- > 0) {
            val w0 = readRdramWord(address)
            val w1 = readRdramWord(address + 4)
            executeCommand(w0, w1)
            commandsProcessed++
            address += 8
        }
        if (address < end) lastError = "display-list command budget exceeded"
        if (registers[3] and STATUS_FLUSH != 0) registers[3] = registers[3] and STATUS_FLUSH.inv()
    }

    private fun executeCommand(w0: Int, w1: Int) {
        when ((w0 ushr 24) and 0x3F) {
            CMD_SET_COLOR_IMAGE -> {
                colorImageSize = (w0 ushr 19) and 0x3
                colorImageWidth = (w0 and 0x3FF) + 1
                colorImageAddress = w1 and 0x00FF_FFFF
            }
            CMD_SET_FILL_COLOR -> fillColor = w1
            CMD_SET_SCISSOR -> {
                scissorLeft = ((w0 ushr 12) and 0xFFF) ushr 2
                scissorTop = (w0 and 0xFFF) ushr 2
                scissorRight = ((w1 ushr 12) and 0xFFF) ushr 2
                scissorBottom = (w1 and 0xFFF) ushr 2
            }
            CMD_FILL_RECTANGLE -> fillRectangle(w0, w1)
            // State commands not needed by solid fill are safely consumed.
            else -> Unit
        }
    }

    private fun fillRectangle(w0: Int, w1: Int) {
        if (colorImageWidth <= 0 || colorImageSize !in 2..3) return
        val bytesPerPixel = if (colorImageSize == 2) 2 else 4
        val x0 = maxOf(((w0 ushr 12) and 0xFFF) ushr 2, scissorLeft, 0)
        val y0 = maxOf((w0 and 0xFFF) ushr 2, scissorTop, 0)
        val x1 = minOf((((w1 ushr 12) and 0xFFF) ushr 2), scissorRight - 1, colorImageWidth - 1)
        val y1 = minOf(((w1 and 0xFFF) ushr 2), scissorBottom - 1)
        if (x1 < x0 || y1 < y0) return
        val maxPixels = rdram.size / bytesPerPixel
        for (y in y0..y1) {
            for (x in x0..x1) {
                val pixelIndex = colorImageAddress.toLong() / bytesPerPixel +
                    y.toLong() * colorImageWidth + x
                if (pixelIndex < 0 || pixelIndex >= maxPixels.toLong()) continue
                val offset = (pixelIndex * bytesPerPixel).toInt()
                if (bytesPerPixel == 2) {
                    // Fill mode alternates the upper/lower halfword by scanline.
                    val value = if (y and 1 == 0) fillColor and 0xFFFF else fillColor ushr 16
                    rdram[offset] = (value ushr 8).toByte()
                    rdram[offset + 1] = value.toByte()
                } else {
                    rdram[offset] = (fillColor ushr 24).toByte()
                    rdram[offset + 1] = (fillColor ushr 16).toByte()
                    rdram[offset + 2] = (fillColor ushr 8).toByte()
                    rdram[offset + 3] = fillColor.toByte()
                }
                frameWrites++
            }
        }
        rectanglesFilled++
    }

    private fun readRdramWord(address: Int): Int {
        require(address >= 0 && address <= rdram.size - 4) { "RDP command read outside RDRAM" }
        return ((rdram[address].toInt() and 0xFF) shl 24) or
            ((rdram[address + 1].toInt() and 0xFF) shl 16) or
            ((rdram[address + 2].toInt() and 0xFF) shl 8) or
            (rdram[address + 3].toInt() and 0xFF)
    }

    private fun registerIndex(address: Long): Int {
        require(address in DPC_START..DPC_END && address % 4L == 0L) {
            "Invalid DPC register address 0x${address.toString(16)}"
        }
        return ((address - DPC_START) / 4L).toInt()
    }

    companion object {
        const val DPC_START = 0x0410_0000L
        const val DPC_END = 0x0410_001CL
        private const val CMD_SET_SCISSOR = 0x2D
        private const val CMD_SET_FILL_COLOR = 0x37
        private const val CMD_FILL_RECTANGLE = 0x36
        private const val CMD_SET_COLOR_IMAGE = 0x3F
        private const val STATUS_XBUS = 1 shl 0
        private const val STATUS_FREEZE = 1 shl 1
        private const val STATUS_FLUSH = 1 shl 2
        private const val MAX_COMMANDS_PER_SUBMIT = 1_000_000
    }
}
