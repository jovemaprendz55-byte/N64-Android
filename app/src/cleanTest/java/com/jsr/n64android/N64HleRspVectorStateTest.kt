package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Test

class N64HleRspVectorStateTest {
    @Test
    fun storesEightBigEndianOrderedHalfwordLanesPerVector() {
        val state = N64HleRspVectorState()
        for (lane in 0 until 8) state.writeElement(3, lane, 0x1100 + lane)
        assertEquals(0x1100, state.readElement(3, 0))
        assertEquals(0x1107, state.readElement(3, 7))
    }

    @Test
    fun exposesSignedAndUnsignedViewsOfElements() {
        val state = N64HleRspVectorState()
        state.writeElement(1, 0, 0xFFFF)
        state.writeElement(1, 1, 0x8000)
        assertEquals(0xFFFF, state.readElement(1, 0))
        assertEquals(-1, state.readSignedElement(1, 0))
        assertEquals(-32768, state.readSignedElement(1, 1))
    }

    @Test
    fun storesAccumulatorAsFortyEightBitLaneValues() {
        val state = N64HleRspVectorState()
        state.writeAccumulatorLane(0, 0x1_1234_5678_9ABCL)
        state.writeAccumulatorLane(7, -1L)
        assertEquals(0x1234_5678_9ABCL, state.readAccumulatorLane(0))
        assertEquals(0xFFFF_FFFF_FFFFL, state.readAccumulatorLane(7))
        assertEquals(-1L, state.readSignedAccumulatorLane(7))
    }

    @Test
    fun copiesRegistersAndTracksVectorFlags() {
        val state = N64HleRspVectorState()
        state.writeElement(2, 4, 0xCAFE)
        state.copyRegister(2, 9)
        state.setFlags(0x1234, 0x5678, 0x9A)
        assertEquals(0xCAFE, state.readElement(9, 4))
        assertEquals(0x1234, state.vco)
        assertEquals(0x5678, state.vcc)
        assertEquals(0x9A, state.vce)
        state.clearFlags()
        assertEquals(0, state.vco)
        assertEquals(0, state.vcc)
        assertEquals(0, state.vce)
    }

    @Test
    fun vectorAddUsesCarryInSaturatesAndClearsVco() {
        val state = N64HleRspVectorState()
        state.writeElement(1, 0, 32767)
        state.writeElement(2, 0, 1)
        state.writeElement(1, 1, 10)
        state.writeElement(2, 1, 20)
        state.setFlags(vco = 0b10, vcc = 0, vce = 0)
        state.add(destination = 3, left = 1, right = 2)
        assertEquals(32767, state.readSignedElement(3, 0))
        assertEquals(31, state.readSignedElement(3, 1))
        assertEquals(0, state.vco)
        assertEquals(31L shl 16, state.readSignedAccumulatorLane(1))
    }

    @Test
    fun vectorSubtractUsesBorrowInAndSaturates() {
        val state = N64HleRspVectorState()
        state.writeElement(1, 0, -32768)
        state.writeElement(2, 0, 1)
        state.writeElement(1, 1, 30)
        state.writeElement(2, 1, 10)
        state.setFlags(vco = 0b10, vcc = 0, vce = 0)
        state.subtract(destination = 3, left = 1, right = 2)
        assertEquals(-32768, state.readSignedElement(3, 0))
        assertEquals(19, state.readSignedElement(3, 1))
        assertEquals(0, state.vco)
    }

    @Test
    fun vectorFractionalMultiplyRoundsAndSaturates() {
        val state = N64HleRspVectorState()
        state.writeElement(1, 0, 0x4000)
        state.writeElement(2, 0, 0x4000)
        state.writeElement(1, 1, 0x8000)
        state.writeElement(2, 1, 0x8000)
        state.multiplyFractional(destination = 3, left = 1, right = 2)
        assertEquals(0x2000, state.readElement(3, 0))
        assertEquals(32767, state.readSignedElement(3, 1))
        assertEquals(0x2000_8000L, state.readAccumulatorLane(0))
    }
}
