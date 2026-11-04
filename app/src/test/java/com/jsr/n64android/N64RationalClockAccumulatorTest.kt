package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class N64RationalClockAccumulatorTest {
    @Test
    fun ntscViRatioPreservesFractionAcrossSingleAndBatchedAdvances() {
        val single = N64RationalClockAccumulator(numerator = 714, denominator = 1375)
        var singleTicks = 0L
        repeat(1375) { singleTicks += single.advance(1L) }

        val batched = N64RationalClockAccumulator(numerator = 714, denominator = 1375)
        val batchedTicks = batched.advance(1375L)

        assertEquals(714L, singleTicks)
        assertEquals(singleTicks, batchedTicks)
        assertEquals(single.remainder(), batched.remainder())
        assertEquals(0L, batched.remainder())
    }

    @Test
    fun fractionalRemainderIsCarriedBetweenCalls() {
        val clock = N64RationalClockAccumulator(numerator = 3, denominator = 5)

        assertEquals(0L, clock.advance(1L))
        assertEquals(3L, clock.remainder())
        assertEquals(1L, clock.advance(1L))
        assertEquals(1L, clock.remainder())
        assertEquals(1L, clock.advance(3L))
        assertEquals(0L, clock.remainder())
    }

    @Test
    fun zeroAdvanceDoesNotChangeRemainder() {
        val clock = N64RationalClockAccumulator(numerator = 3, denominator = 5)
        assertEquals(0L, clock.advance(1L))
        assertEquals(0L, clock.advance(0L))
        assertEquals(3L, clock.remainder())
    }

    @Test
    fun negativeAdvanceAndInvalidRatiosAreRejected() {
        val clock = N64RationalClockAccumulator(numerator = 714, denominator = 1375)
        assertThrows(IllegalArgumentException::class.java) { clock.advance(-1L) }
        assertThrows(IllegalArgumentException::class.java) {
            N64RationalClockAccumulator(numerator = 0, denominator = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            N64RationalClockAccumulator(numerator = 1, denominator = 0)
        }
    }
}
