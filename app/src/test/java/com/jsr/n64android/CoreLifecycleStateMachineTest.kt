package com.jsr.n64android

import org.junit.Assert.assertEquals
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreLifecycleStateMachineTest {
    @Test
    fun concurrentStopRequestsJoinOneGeneration() {
        val machine = CoreLifecycleStateMachine()
        val threadCount = 32
        val ready = CountDownLatch(threadCount)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threadCount)

        try {
            val results = (0 until threadCount).map { index ->
                pool.submit<CoreLifecycleStateMachine.Snapshot> {
                    ready.countDown()
                    check(start.await(2, TimeUnit.SECONDS))
                    machine.begin(
                        if (index % 2 == 0) {
                            CoreLifecycleStateMachine.Request.MANUAL_STOP
                        } else {
                            CoreLifecycleStateMachine.Request.ACTIVITY_PAUSE
                        }
                    )
                }
            }

            assertTrue("All workers should be ready", ready.await(2, TimeUnit.SECONDS))
            start.countDown()
            val snapshots = results.map { it.get(2, TimeUnit.SECONDS) }

            assertEquals(1, snapshots.map { it.generation }.distinct().size)
            assertTrue(snapshots.all {
                it.phase == CoreLifecycleStateMachine.Phase.STOP_REQUESTED
            })
            assertEquals(CoreLifecycleStateMachine.Phase.STOP_REQUESTED, machine.snapshot().phase)
        } finally {
            start.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun timeoutKeepsGenerationLatchedUntilRecovery() {
        val machine = CoreLifecycleStateMachine()
        val first = machine.begin(CoreLifecycleStateMachine.Request.ACTIVITY_PAUSE)

        assertTrue(machine.markExecuteReturnTimeout(first.generation))
        val second = machine.begin(CoreLifecycleStateMachine.Request.MANUAL_STOP)

        assertEquals(first.generation, second.generation)
        assertEquals(CoreLifecycleStateMachine.Phase.EXECUTE_RETURN_TIMEOUT, second.phase)
        assertFalse(machine.markGlTeardownQueued(first.generation))
    }

    @Test
    fun glTeardownTimeoutAlsoPreventsNewGeneration() {
        val machine = CoreLifecycleStateMachine()
        val first = machine.begin(CoreLifecycleStateMachine.Request.ACTIVITY_DESTROY)
        assertTrue(machine.markExecuteReturned(first.generation))
        assertTrue(machine.markGlTeardownQueued(first.generation))
        assertTrue(machine.markGlTeardownTimeout(first.generation))

        val second = machine.begin(CoreLifecycleStateMachine.Request.SCREEN_CHANGE)
        assertEquals(first.generation, second.generation)
        assertEquals(CoreLifecycleStateMachine.Phase.GL_TEARDOWN_TIMEOUT, second.phase)
    }

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
    fun moreRestrictiveLifecycleIntentJoinsAndUpgradesExistingGeneration() {
        val machine = CoreLifecycleStateMachine()
        val manual = machine.begin(CoreLifecycleStateMachine.Request.MANUAL_STOP)
        val pause = machine.begin(CoreLifecycleStateMachine.Request.ACTIVITY_PAUSE)
        val destroy = machine.begin(CoreLifecycleStateMachine.Request.ACTIVITY_DESTROY)

        assertEquals(manual.generation, pause.generation)
        assertEquals(manual.generation, destroy.generation)
        assertEquals(CoreLifecycleStateMachine.Phase.STOP_REQUESTED, destroy.phase)
        assertEquals(
            CoreLifecycleStateMachine.Request.ACTIVITY_DESTROY,
            destroy.request
        )
        assertEquals(
            CoreLifecycleStateMachine.Request.ACTIVITY_DESTROY,
            machine.snapshot().request
        )
    }

    @Test
    fun lessRestrictiveRequestCannotDowngradeDestroyIntent() {
        val machine = CoreLifecycleStateMachine()
        val destroy = machine.begin(CoreLifecycleStateMachine.Request.ACTIVITY_DESTROY)
        val manual = machine.begin(CoreLifecycleStateMachine.Request.MANUAL_STOP)

        assertEquals(destroy.generation, manual.generation)
        assertEquals(
            CoreLifecycleStateMachine.Request.ACTIVITY_DESTROY,
            manual.request
        )
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
