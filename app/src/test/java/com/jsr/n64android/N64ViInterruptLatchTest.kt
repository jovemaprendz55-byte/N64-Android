package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Test

class N64ViInterruptLatchTest {
    @Test fun matchingSampleAssertsPendingLine() {
        val latch = N64ViInterruptLatch()

        assertEquals(true, latch.sampleCurrent(24, 24))
        assertEquals(true, latch.pending)
    }

    @Test fun nonmatchingSampleDoesNotAssertPendingLine() {
        val latch = N64ViInterruptLatch()

        assertEquals(false, latch.sampleCurrent(24, 26))
        assertEquals(false, latch.pending)
    }

    @Test fun pendingLineRemainsAssertedUntilAcknowledged() {
        val latch = N64ViInterruptLatch()

        latch.sampleCurrent(24, 24)
        assertEquals(true, latch.sampleCurrent(26, 24))
        assertEquals(true, latch.pending)

        latch.acknowledge()
        assertEquals(false, latch.pending)
    }

    @Test fun acknowledgeDoesNotChangeCounterValue() {
        val counter = N64ViHalfLineCounter(initialCurrent = 24)
        val latch = N64ViInterruptLatch()

        latch.sampleCurrent(counter.current, 24)
        latch.acknowledge()

        assertEquals(24, counter.current)
        assertEquals(false, latch.pending)
    }

    @Test fun rejectsNegativeHalfLineValues() {
        val latch = N64ViInterruptLatch()

        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            latch.sampleCurrent(-1, 0)
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            latch.sampleCurrent(0, -1)
        }
    }
}
