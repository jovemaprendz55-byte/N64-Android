package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class N64ViHalfLineCounterTest {
    @Test fun progressiveNtscAdvancesByTwoAndWrapsAtTerminalCountPlusOne() {
        val counter = N64ViHalfLineCounter()

        repeat(262) { counter.advanceScanline(525) }
        assertEquals(524, counter.current)
        assertEquals(0, counter.fieldParity)

        counter.advanceScanline(525)
        assertEquals(0, counter.current)
    }

    @Test fun interlacedNtscAlternatesFieldParityAcrossTerminalCountWrap() {
        val counter = N64ViHalfLineCounter()

        repeat(262) { counter.advanceScanline(524) }
        assertEquals(524, counter.current)
        assertEquals(0, counter.fieldParity)

        counter.advanceScanline(524)
        assertEquals(1, counter.current)
        assertEquals(1, counter.fieldParity)

        repeat(262) { counter.advanceScanline(524) }
        assertEquals(0, counter.current)
        assertEquals(0, counter.fieldParity)
    }

    @Test fun counterSupportsNonzeroInitialPhase() {
        val counter = N64ViHalfLineCounter(initialCurrent = 7)

        assertEquals(9, counter.advanceScanline(524))
        assertEquals(1, counter.fieldParity)
    }

    @Test fun rejectsNegativeInitialCurrentAndTerminalCount() {
        assertThrows(IllegalArgumentException::class.java) {
            N64ViHalfLineCounter(initialCurrent = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            N64ViHalfLineCounter().advanceScanline(-1)
        }
    }

    @Test fun largeTerminalCountDoesNotOverflowWhenAddingOne() {
        val counter = N64ViHalfLineCounter(initialCurrent = Int.MAX_VALUE)

        assertEquals(24, counter.advanceScanline(Int.MAX_VALUE))
    }
}
