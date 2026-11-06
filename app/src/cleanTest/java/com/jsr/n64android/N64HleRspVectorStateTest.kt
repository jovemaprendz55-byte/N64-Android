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
}
