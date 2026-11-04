package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class N64CpuCycleClockTest {
    @Test
    fun totalIsIndependentOfAdvanceBatching() {
        val singleSteps = N64CpuCycleClock()
        repeat(10) { singleSteps.advance(2) }

        val batched = N64CpuCycleClock()
        batched.advance(8)
        batched.advance(12)

        assertEquals(20L, singleSteps.totalCycles)
        assertEquals(singleSteps.totalCycles, batched.totalCycles)
    }

    @Test
    fun zeroCyclesDoNotChangeTotal() {
        val clock = N64CpuCycleClock()
        clock.advance(0)
        assertEquals(0L, clock.totalCycles)
    }

    @Test
    fun negativeAdvanceIsRejected() {
        val clock = N64CpuCycleClock()
        assertThrows(IllegalArgumentException::class.java) { clock.advance(-1) }
        assertEquals(0L, clock.totalCycles)
    }
}
