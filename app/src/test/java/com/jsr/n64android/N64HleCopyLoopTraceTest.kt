package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class N64HleCopyLoopTraceTest {
    @Test
    fun capturesLoopIterationsAndTheFirst64InstructionsFromExit() {
        val trace = N64HleCopyLoopTrace()

        val first = trace.beforeInstruction(N64HleCopyLoopTrace.LOOP_STORE_PC) { "before-1" }
        trace.afterInstruction(first, 0xAD000000.toInt(), N64HleCopyLoopTrace.LOOP_START_PC) { "after-1" }

        val exit = trace.beforeInstruction(N64HleCopyLoopTrace.LOOP_STORE_PC) { "before-2" }
        trace.afterInstruction(exit, 0xAD000000.toInt(), 0x70000600L) { "after-2" }

        repeat(70) { index ->
            val observation = trace.beforeInstruction(0x70000600L + index) { "unused" }
            trace.afterInstruction(observation, null, 0x70000604L + index) { "after-$index" }
        }

        val snapshot = trace.snapshot()
        assertEquals(2L, snapshot.iterations)
        assertEquals(1L, snapshot.takenBranches)
        assertTrue(snapshot.exitObserved)
        assertEquals(2, snapshot.sampleLines.size)
        assertEquals(N64HleCopyLoopTrace.POST_EXIT_LIMIT, snapshot.postExitLines.size)
        assertTrue(snapshot.postExitLines.first().contains("PC=0x70000588"))
        assertTrue(snapshot.postExitLines.first().contains("REG{after-2}"))
    }

    @Test
    fun boundsDetailedLoopLinesAndSamplesOnlyInitialAndPeriodicIterations() {
        val trace = N64HleCopyLoopTrace()

        repeat(40) {
            val observation = trace.beforeInstruction(N64HleCopyLoopTrace.LOOP_FIRST_PC) { "before" }
            trace.afterInstruction(observation, 0, N64HleCopyLoopTrace.LOOP_START_PC) { "after" }
        }
        val bounded = trace.snapshot()
        assertEquals(N64HleCopyLoopTrace.LOOP_TRACE_LIMIT, bounded.loopLines.size)
        assertEquals(8, bounded.sampleLines.size)
        assertFalse(bounded.exitObserved)

        val periodicTrace = N64HleCopyLoopTrace()
        repeat(4096) {
            val observation = periodicTrace.beforeInstruction(N64HleCopyLoopTrace.LOOP_STORE_PC) { "before" }
            periodicTrace.afterInstruction(observation, 0, N64HleCopyLoopTrace.LOOP_START_PC) { "after" }
        }
        assertEquals(9, periodicTrace.snapshot().sampleLines.size)
    }

    @Test
    fun doesNotRequestRegisterSnapshotsOutsideRetainedRegions() {
        val trace = N64HleCopyLoopTrace()
        var calls = 0
        val observation = trace.beforeInstruction(0x70001000L) {
            calls++
            "registers"
        }
        trace.afterInstruction(observation, null, 0x70001004L) {
            calls++
            "registers"
        }
        assertEquals(0, calls)
    }
}
