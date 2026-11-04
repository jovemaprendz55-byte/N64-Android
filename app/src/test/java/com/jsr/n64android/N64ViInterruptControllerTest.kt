package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Test

class N64ViInterruptControllerTest {
    @Test fun scanlineAdvanceUsesHalfLinesAndAssertsMatchingInterrupt() {
        val controller = N64ViInterruptController()

        repeat(12) { controller.advanceScanline(525, 24) }

        assertEquals(24, controller.current)
        assertEquals(true, controller.interruptPending)
    }

    @Test fun interruptRemainsLatchedAfterCounterMovesPastCompareValue() {
        val controller = N64ViInterruptController()

        repeat(12) { controller.advanceScanline(525, 24) }
        controller.advanceScanline(525, 24)

        assertEquals(26, controller.current)
        assertEquals(true, controller.interruptPending)
    }

    @Test fun acknowledgeClearsLatchWithoutResettingHalfLineCounter() {
        val controller = N64ViInterruptController()

        repeat(12) { controller.advanceScanline(525, 24) }
        controller.acknowledgeInterrupt()

        assertEquals(24, controller.current)
        assertEquals(false, controller.interruptPending)
    }

    @Test fun nonmatchingSamplesDoNotAssertInterrupt() {
        val controller = N64ViInterruptController()

        repeat(11) { controller.advanceScanline(525, 24) }

        assertEquals(22, controller.current)
        assertEquals(false, controller.interruptPending)
    }

    @Test fun matchingInterruptCanBeAssertedAgainAfterAcknowledgeAndCounterWrap() {
        val controller = N64ViInterruptController(initialCurrent = 24)

        controller.acknowledgeInterrupt()
        // 525 is the progressive NTSC terminal count: 526 half-line positions.
        repeat(263) { controller.advanceScanline(525, 24) }

        assertEquals(24, controller.current)
        assertEquals(true, controller.interruptPending)
    }
}
