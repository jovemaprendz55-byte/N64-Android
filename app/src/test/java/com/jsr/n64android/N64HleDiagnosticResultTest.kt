package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class N64HleDiagnosticResultTest {
    @Test
    fun storesReportFrameExecutionCountAndStopReason() {
        val frame = N64VideoFrame(
            width = 1,
            height = 1,
            argbPixels = intArrayOf(0xFF123456.toInt()),
            origin = 0x400,
            pixelSize = 3
        )
        val result = N64HleDiagnosticResult(
            reportLines = listOf("BOOT HLE", "Instruções executadas: 12"),
            videoFrame = frame,
            executedInstructions = 12,
            stopReason = "Limite de instruções alcançado."
        )

        assertEquals(2, result.reportLines.size)
        assertEquals(frame, result.videoFrame)
        assertEquals(12, result.executedInstructions)
        assertEquals("Limite de instruções alcançado.", result.stopReason)
        assertNull(result.failureMessage)

        val failed = result.copy(failureMessage = "IllegalStateException: teste")
        assertEquals("IllegalStateException: teste", failed.failureMessage)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativeInstructionCount() {
        N64HleDiagnosticResult(
            reportLines = emptyList(),
            videoFrame = null,
            executedInstructions = -1,
            stopReason = "Cancelado"
        )
    }
}
