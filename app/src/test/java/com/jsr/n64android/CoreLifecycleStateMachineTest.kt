package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreLifecycleStateMachineTest {
    @Test
    fun repeatedStopRequestsJoinOneGeneration() {
        val machine = CoreLifecycleStateMachine()
        val first = machine.begin(CoreLifecycleStateMachine.Request.MANUAL_STOP)
        val second = machine.begin(CoreLifecycleStateMachine.Request.MANUAL_STOP)

        assertEquals(first.generation, second.generation)
        assertEquals(first, second)
        assertEquals(CoreLifecycleStateMachine.Phase.STOP_REQUESTED, second.phase)
    }

    @Test
    fun lifecycleRequestsJoinManualStopAlreadyInFlight() {
        val machine = CoreLifecycleStateMachine()
        val manual = machine.begin(CoreLifecycleStateMachine.Request.MANUAL_STOP)
        val pause = machine.begin(CoreLifecycleStateMachine.Request.ACTIVITY_PAUSE)
        val destroy = machine.begin(CoreLifecycleStateMachine.Request.ACTIVITY_DESTROY)

        assertEquals(manual.generation, pause.generation)
        assertEquals(manual.generation, destroy.generation)
        assertEquals(CoreLifecycleStateMachine.Request.MANUAL_STOP, destroy.request)
    }

    @Test
    fun executeTimeoutCannotProceedToGlTeardown() {
        val machine = CoreLifecycleStateMachine()
        val request = machine.begin(CoreLifecycleStateMachine.Request.ACTIVITY_PAUSE)

        assertTrue(machine.markExecuteReturnTimeout(request.generation))
        assertFalse(machine.markGlTeardownQueued(request.generation))
        assertFalse(machine.markShutdownComplete(request.generation))
        assertEquals(
            CoreLifecycleStateMachine.Phase.EXECUTE_RETURN_TIMEOUT,
            machine.snapshot().phase
        )
    }

    @Test
    fun naturalExecuteReturnAllowsGlTeardownOnlyInOrder() {
        val machine = CoreLifecycleStateMachine()
        val request = machine.begin(CoreLifecycleStateMachine.Request.ROM_REPLACEMENT)

        assertTrue(machine.markExecuteReturned(request.generation))
        assertTrue(machine.markGlTeardownQueued(request.generation))
        assertTrue(machine.markShutdownComplete(request.generation))
        assertEquals(
            CoreLifecycleStateMachine.Phase.SHUTDOWN_COMPLETE,
            machine.snapshot().phase
        )
    }

    @Test
    fun staleCallbacksCannotChangeNewGeneration() {
        val machine = CoreLifecycleStateMachine()
        val old = machine.begin(CoreLifecycleStateMachine.Request.MANUAL_STOP)
        assertTrue(machine.markStopRejected(old.generation))
        val current = machine.begin(CoreLifecycleStateMachine.Request.SCREEN_CHANGE)

        assertTrue(current.generation > old.generation)
        assertFalse(machine.markExecuteReturned(old.generation))
        assertEquals(CoreLifecycleStateMachine.Phase.STOP_REQUESTED, machine.snapshot().phase)
    }

    @Test
    fun lostSurfacePreventsSuccessfulShutdown() {
        val machine = CoreLifecycleStateMachine()
        val request = machine.begin(CoreLifecycleStateMachine.Request.ACTIVITY_DESTROY)

        assertTrue(machine.markExecuteReturned(request.generation))
        assertTrue(machine.markSurfaceUnavailable(request.generation))
        assertFalse(machine.markShutdownComplete(request.generation))
        assertEquals(
            CoreLifecycleStateMachine.Phase.SURFACE_UNAVAILABLE,
            machine.snapshot().phase
        )
    }

    @Test
    fun glTeardownTimeoutIsNotReportedAsComplete() {
        val machine = CoreLifecycleStateMachine()
        val request = machine.begin(CoreLifecycleStateMachine.Request.SCREEN_CHANGE)

        assertTrue(machine.markExecuteReturned(request.generation))
        assertTrue(machine.markGlTeardownQueued(request.generation))
        assertTrue(machine.markGlTeardownTimeout(request.generation))
        assertFalse(machine.markShutdownComplete(request.generation))
        assertEquals(
            CoreLifecycleStateMachine.Phase.GL_TEARDOWN_TIMEOUT,
            machine.snapshot().phase
        )
    }
}
