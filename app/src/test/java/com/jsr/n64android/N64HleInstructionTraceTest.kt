package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class N64HleInstructionTraceTest {
    @Test
    fun keepsOnlyTheMostRecentInstructions() {
        val trace = N64HleInstructionTrace(recentLimit = 2)
        trace.record(0x100, 0x12345678, 0x104)
        trace.record(0x104, null, 0x108)
        trace.record(0x108, 0, 0x10C)

        assertEquals(
            listOf(
                "PC=0x104 INST=N/A NEXT=0x108",
                "PC=0x108 INST=00000000 NEXT=0x10C"
            ),
            trace.snapshot().recentLines
        )
    }

    @Test
    fun countsRepeatedPcsAndBoundsDistinctPcTracking() {
        val trace = N64HleInstructionTrace(trackedPcLimit = 2)
        trace.record(0x100, 0, 0x104)
        trace.record(0x104, 0, 0x108)
        trace.record(0x100, 0, 0x104)
        trace.record(0x108, 0, 0x10C)

        val snapshot = trace.snapshot()
        assertEquals(2, snapshot.distinctPcCount)
        assertEquals(listOf("PC=0x100 visits=2", "PC=0x104 visits=1"), snapshot.mostVisitedLines)
    }

    @Test
    fun rejectsInvalidLimits() {
        assertThrows(IllegalArgumentException::class.java) {
            N64HleInstructionTrace(recentLimit = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            N64HleInstructionTrace(trackedPcLimit = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            N64HleInstructionTrace().snapshot(mostVisitedLimit = -1)
        }
    }
}
