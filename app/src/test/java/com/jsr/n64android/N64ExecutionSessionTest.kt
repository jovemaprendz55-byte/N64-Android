package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class N64ExecutionSessionTest {

    @Test fun completesAtInstructionLimitAcrossBoundedBatches() {
        var steps = 0
        val session = N64ExecutionSession(
            instructionLimit = 7,
            batchSize = 3
        ) { steps++ }

        val result = session.execute()

        assertEquals(7, steps)
        assertEquals(7L, result.executedInstructions)
        assertEquals(N64ExecutionSession.State.COMPLETED, result.state)
    }

    @Test fun cancellationStopsBetweenInstructions() {
        var steps = 0
        lateinit var session: N64ExecutionSession
        session = N64ExecutionSession(
            instructionLimit = 100,
            batchSize = 8
        ) {
            steps++
            if (steps == 3) session.requestStop()
        }

        val result = session.execute()

        assertEquals(3, steps)
        assertEquals(3L, result.executedInstructions)
        assertEquals(N64ExecutionSession.State.CANCELLED, result.state)
    }

    @Test fun cancellationFromBeforeInstructionDoesNotExecuteNextInstruction() {
        var steps = 0
        lateinit var session: N64ExecutionSession
        session = N64ExecutionSession(
            instructionLimit = 10,
            batchSize = 4,
            beforeInstruction = { session.requestStop() },
            stepInstruction = { steps++ }
        )

        val result = session.execute()

        assertEquals(0, steps)
        assertEquals(0L, result.executedInstructions)
        assertEquals(N64ExecutionSession.State.CANCELLED, result.state)
    }

    @Test fun sessionCannotBeExecutedMoreThanOnce() {
        val session = N64ExecutionSession(instructionLimit = 1) {}

        session.execute()

        assertThrows(IllegalStateException::class.java) { session.execute() }
        assertEquals(N64ExecutionSession.State.COMPLETED, session.snapshot().state)
    }

    @Test fun instructionFailureMarksSessionFailedAndPreservesCount() {
        var steps = 0
        val session = N64ExecutionSession(instructionLimit = 10, batchSize = 4) {
            steps++
            if (steps == 2) throw IllegalStateException("synthetic CPU fault")
        }

        assertThrows(IllegalStateException::class.java) { session.execute() }

        val result = session.snapshot()
        assertEquals(1L, result.executedInstructions)
        assertEquals(N64ExecutionSession.State.FAILED, result.state)
        assertTrue(result.failureMessage!!.contains("synthetic CPU fault"))
    }

    @Test fun rejectsInvalidExecutionLimits() {
        assertThrows(IllegalArgumentException::class.java) {
            N64ExecutionSession(instructionLimit = 0) {}
        }
        assertThrows(IllegalArgumentException::class.java) {
            N64ExecutionSession(instructionLimit = 1, batchSize = 0) {}
        }
    }
}
