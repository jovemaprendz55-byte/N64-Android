package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class N64ExecutionWorkerTest {

    @Test
    fun workerCanBeStoppedAndReportsCancelledSnapshot() {
        val steps = AtomicInteger()
        val entered = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val result = AtomicReference<N64ExecutionSession.Snapshot>()
        val session = N64ExecutionSession(
            instructionLimit = 1_000_000,
            batchSize = 1
        ) {
            steps.incrementAndGet()
            entered.countDown()
        }
        val worker = N64ExecutionWorker(session) {
            result.set(it)
            completed.countDown()
        }

        assertTrue(worker.start())
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        worker.requestStop()
        assertTrue(completed.await(2, TimeUnit.SECONDS))

        assertEquals(N64ExecutionSession.State.CANCELLED, result.get().state)
        assertTrue(result.get().executedInstructions > 0L)
        assertTrue(result.get().executedInstructions < 1_000_000L)
        assertFalse(worker.isAlive())
        assertFalse(worker.start())
    }

    @Test
    fun workerReportsFailedSessionSnapshot() {
        val completed = CountDownLatch(1)
        val result = AtomicReference<N64ExecutionSession.Snapshot>()
        val session = N64ExecutionSession(instructionLimit = 10) {
            throw IllegalStateException("worker test failure")
        }
        val worker = N64ExecutionWorker(session) {
            result.set(it)
            completed.countDown()
        }

        assertTrue(worker.start())
        assertTrue(completed.await(2, TimeUnit.SECONDS))

        assertEquals(N64ExecutionSession.State.FAILED, result.get().state)
        assertTrue(result.get().failureMessage!!.contains("worker test failure"))
    }
}
