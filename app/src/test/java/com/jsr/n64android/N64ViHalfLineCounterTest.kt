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

    @Test fun interruptComparatorUsesExactHalfLineValue() {
        val counter = N64ViHalfLineCounter()

        repeat(262) { counter.advanceScanline(524) }
        assertEquals(524, counter.current)
        assertEquals(true, counter.matchesInterrupt(524))
        assertEquals(false, counter.matchesInterrupt(262))
        assertEquals(false, counter.matchesInterrupt(525))
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

        assertEquals(1, counter.advanceScanline(Int.MAX_VALUE))
    }

    @Test fun standardNtscAndPalModesFollowTheirHalfLineTerminalCounts() {
        assertVideoMode(vSync = 0x20D, interlaced = false, scanlinesPerField = 263)
        assertVideoMode(vSync = 0x20C, interlaced = true, scanlinesPerField = 262)
        assertVideoMode(vSync = 0x271, interlaced = false, scanlinesPerField = 313)
        assertVideoMode(vSync = 0x270, interlaced = true, scanlinesPerField = 312)
    }

    private fun assertVideoMode(vSync: Int, interlaced: Boolean, scanlinesPerField: Int) {
        val counter = N64ViHalfLineCounter()
        val terminal = vSync
        if (interlaced) {
            repeat(scanlinesPerField) { counter.advanceScanline(terminal) }
            assertEquals(terminal, counter.current)
            assertEquals(0, counter.fieldParity)

            counter.advanceScanline(terminal)
            assertEquals(1, counter.current)
            assertEquals(1, counter.fieldParity)

            repeat(scanlinesPerField) { counter.advanceScanline(terminal) }
            assertEquals(0, counter.current)
            assertEquals(0, counter.fieldParity)
        } else {
            repeat(scanlinesPerField - 1) { counter.advanceScanline(terminal) }
            assertEquals(terminal - 1, counter.current)
            assertEquals(0, counter.fieldParity)

            counter.advanceScanline(terminal)
            assertEquals(0, counter.current)
            assertEquals(0, counter.fieldParity)
        }
    }


    @Test fun acceptsTenBitMaximumViVSyncTerminalCount() {
        val counter = N64ViHalfLineCounter()

        repeat(511) { counter.advanceScanline(0x3FF) }
        assertEquals(1022, counter.current)

        counter.advanceScanline(0x3FF)
        assertEquals(0, counter.current)
    }

}
