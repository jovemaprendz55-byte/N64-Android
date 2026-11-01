package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class N64RdpCommandLengthTest {
    @Test
    fun triangleOpcodesReportTheirVariableLengths() {
        val expected = listOf(32, 48, 96, 112, 96, 112, 160, 176)
        expected.forEachIndexed { index, bytes ->
            assertEquals(bytes, N64RdpCommandLength.bytesForOpcode(0x08 + index))
        }
    }

    @Test
    fun textureRectanglesAreTwoWords() {
        assertEquals(16, N64RdpCommandLength.bytesForOpcode(0x24))
        assertEquals(16, N64RdpCommandLength.bytesForOpcode(0x25))
    }

    @Test
    fun standardCommandsRemainOneWord() {
        listOf(0x00, 0x2D, 0x2F, 0x36, 0x37, 0x3F).forEach { opcode ->
            assertEquals(8, N64RdpCommandLength.bytesForOpcode(opcode))
        }
    }

    @Test
    fun rejectsOpcodeOutsideSixBitRange() {
        val error = runCatching { N64RdpCommandLength.bytesForOpcode(0x40) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }
}
