package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class N64RdpTriangleSetupTest {
    @Test
    fun decodesFlatTriangleEdgeCoefficientsFromBigEndianWords() {
        val header = (0x08L shl 58) or
            (1L shl 55) or
            (5L shl 48) or
            (0x3FFCL shl 32) or
            (0x0008L shl 16) or
            0x0004L
        val packet = N64RdpCommandPacket(
            opcode = 0x08,
            byteLength = 32,
            words = listOf(
                header,
                (0x00018000L shl 32) or 0x00004000L,
                (0x00028000L shl 32) or 0x00002000L,
                (0x00030000L shl 32) or 0xFFFFE000L
            )
        )

        val setup = N64RdpTriangleSetup.decode(packet)

        assertEquals(0x08, setup.opcode)
        assertTrue(setup.rightMajor)
        assertEquals(5, setup.tile)
        assertEquals(-4, setup.yLow)
        assertEquals(8, setup.yMiddle)
        assertEquals(4, setup.yHigh)
        assertEquals(0x00018000, setup.xLow)
        assertEquals(0x00004000, setup.dxLowDy)
        assertEquals(0x00028000, setup.xHigh)
        assertEquals(0x00002000, setup.dxHighDy)
        assertEquals(0x00030000, setup.xMiddle)
        assertEquals(0xFFFFE000.toInt(), setup.dxMiddleDy)
    }

    @Test
    fun decodesEdgePrefixForEveryTriangleOpcode() {
        for (opcode in 0x08..0x0F) {
            val byteLength = N64RdpCommandLength.bytesForOpcode(opcode)
            val words = MutableList(byteLength / 8) { 0L }
            words[0] = opcode.toLong() shl 58
            val packet = N64RdpCommandPacket(opcode, byteLength, words)

            assertEquals(opcode, N64RdpTriangleSetup.decode(packet).opcode)
        }
    }

    @Test
    fun rejectsNonTrianglePacket() {
        val packet = N64RdpCommandPacket(0x2F, 8, listOf(0xBC00000000000000UL.toLong()))
        val error = runCatching { N64RdpTriangleSetup.decode(packet) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun leftMajorFlagIsFalseWhenHeaderBitIsClear() {
        val packet = N64RdpCommandPacket(
            opcode = 0x08,
            byteLength = 32,
            words = listOf(0x2000000000000000L, 0L, 0L, 0L)
        )

        assertFalse(N64RdpTriangleSetup.decode(packet).rightMajor)
    }
}
