package com.jovemaprendz55byte.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class N64RdpProcessorTest {
    @Test
    fun processesDisplayListAndFillsRdramFramebuffer() {
        val bus = N64MemoryBus()
        val listAddress = 0x100
        val imageAddress = 0x200
        val commands = intArrayOf(
            (0x3F shl 24) or (2 shl 19) or 1, imageAddress, // RGBA5551, width=2
            (0x37 shl 24), 0xFFFF_F801.toInt(),             // alternate white/red fill pixels
            (0x36 shl 24) or (0 shl 12) or 0, (4 shl 12) or 4 // 2x2 rectangle
        )
        val bytes = ByteArray(commands.size * 4)
        commands.forEachIndexed { i, word ->
            bytes[i * 4] = (word ushr 24).toByte()
            bytes[i * 4 + 1] = (word ushr 16).toByte()
            bytes[i * 4 + 2] = (word ushr 8).toByte()
            bytes[i * 4 + 3] = word.toByte()
        }
        bus.loadRdram(listAddress.toLong(), bytes)

        bus.writeWord(N64RdpProcessor.DPC_START, listAddress)
        bus.writeWord(N64RdpProcessor.DPC_START + 4, listAddress + bytes.size)
        bus.writeWord(N64VideoInterface.VI_STATUS, 2)
        bus.writeWord(N64VideoInterface.VI_ORIGIN, imageAddress)
        bus.writeWord(N64VideoInterface.VI_WIDTH, 2)

        val frame = N64VideoFrameBuffer(2, 2)
        assertTrue(bus.renderVideoFrame(frame))
        for (y in 0..1) for (x in 0..1) {
            val expected = if (x == 0) 0xFFFF0000.toInt() else 0xFFFFFFFF.toInt()
            assertEquals(expected, frame.pixelAt(x, y))
        }
        assertTrue(bus.videoDiagnosticSummary().contains("RDP_COMMANDS=3"))
        assertTrue(bus.videoDiagnosticSummary().contains("RDP_RECTS=1"))
    }

    @Test
    fun ignoresUnsupportedCommandsWithoutCrashingTheList() {
        val bus = N64MemoryBus()
        val address = 0x100
        // A syntactically valid, unsupported 64-bit command followed by SetFillColor.
        val bytes = byteArrayOf(
            0x00, 0, 0, 0, 0, 0, 0, 0,
            0x37, 0, 0, 0, -1, -1, -1, -1
        )
        bus.loadRdram(address.toLong(), bytes)
        bus.writeWord(N64RdpProcessor.DPC_START, address)
        bus.writeWord(N64RdpProcessor.DPC_START + 4, address + bytes.size)
        assertTrue(bus.videoDiagnosticSummary().contains("RDP_COMMANDS=2"))
    }
}
