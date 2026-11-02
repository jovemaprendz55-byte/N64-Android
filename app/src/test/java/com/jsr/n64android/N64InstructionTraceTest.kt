package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class N64InstructionTraceTest {
    @Test fun detectsBranchAndDelaySlotLoopWithoutFalseSingleInstructionAssumption() {
        val trace = N64InstructionTrace()
        val branch = N64InstructionTrace.Entry(0x7000071CL, 0x1000FFFF)
        val delay = N64InstructionTrace.Entry(0x70000720L, 0)
        repeat(3) {
            trace.record(branch.pc, branch.instruction)
            trace.record(delay.pc, delay.instruction)
        }

        val loop = trace.detectLoop()
        assertEquals(2, loop?.period)
        assertEquals(listOf(branch, delay), loop?.pattern)
    }

    @Test fun doesNotReportLoopForNonRepeatingInstructions() {
        val trace = N64InstructionTrace()
        repeat(8) { index -> trace.record(index * 4L, 0x24000000 + index) }
        assertNull(trace.detectLoop())
    }

    @Test fun traceRemainsBounded() {
        val trace = N64InstructionTrace(capacity = 8)
        repeat(20) { trace.record(it * 4L, it) }
        assertEquals(8, trace.snapshot().size)
        assertTrue(trace.snapshot().first().pc == 48L)
    }
}
