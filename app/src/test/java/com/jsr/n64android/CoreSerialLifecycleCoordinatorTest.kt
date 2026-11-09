package com.jsr.n64android

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreSerialLifecycleCoordinatorTest {
    private class BlockingBridge : CoreLifecycleBridge {
        val stopCalls = AtomicInteger()
        val waitCalls = AtomicInteger()
        val stopEntered = CountDownLatch(1)
        val allowStop = CountDownLatch(1)
        val allowWait = CountDownLatch(1)

        override fun requestStop(): String {
            stopCalls.incrementAndGet()
            stopEntered.countDown()
            check(allowStop.await(2, TimeUnit.SECONDS))
            return "Pedido de parada enviado ao Core"
        }

        override fun awaitExecuteReturned(timeoutMs: Int): String {
            waitCalls.incrementAndGet()
            assertEquals(2500, timeoutMs)
            check(allowWait.await(2, TimeUnit.SECONDS))
            return "M64CMD_EXECUTE retornou. Nenhum teardown foi executado."
        }
    }

    @Test
    fun concurrentRequestsShareOneStopAndDrainOperationAndUpgradeIntent() {
        val bridge = BlockingBridge()
        val pool = Executors.newSingleThreadExecutor()
        try {
            val coordinator = CoreSerialLifecycleCoordinator(bridge, pool)
            val first = coordinator.requestStopAndAwait(
                CoreLifecycleStateMachine.Request.MANUAL_STOP, 2500
            )
            assertTrue(bridge.stopEntered.await(2, TimeUnit.SECONDS))

            val second = coordinator.requestStopAndAwait(
                CoreLifecycleStateMachine.Request.ACTIVITY_PAUSE, 2500
            )
            val third = coordinator.requestStopAndAwait(
                CoreLifecycleStateMachine.Request.ACTIVITY_DESTROY, 2500
            )

            assertSame(first, second)
            assertSame(first, third)
            assertEquals(
                CoreLifecycleStateMachine.Request.ACTIVITY_DESTROY,
                coordinator.snapshot().request
            )

            bridge.allowStop.countDown()
            // Wait until the drain call has entered before releasing it.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (bridge.waitCalls.get() == 0 && System.nanoTime() < deadline) {
                Thread.yield()
            }
            assertEquals(1, bridge.waitCalls.get())
            bridge.allowWait.countDown()

            val result = first.get(2, TimeUnit.SECONDS)
            assertTrue(result.stopAccepted)
            assertEquals(1, bridge.stopCalls.get())
            assertEquals(1, bridge.waitCalls.get())
            assertEquals(
                CoreLifecycleStateMachine.Request.ACTIVITY_DESTROY,
                result.request
            )
            assertEquals(
                CoreLifecycleStateMachine.Phase.EXECUTE_RETURNED,
                coordinator.snapshot().phase
            )
        } finally {
            bridge.allowStop.countDown()
            bridge.allowWait.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun rejectedStopIsNotWaitedAndCanBeRetriedAfterCompletion() {
        val calls = AtomicInteger()
        val bridge = object : CoreLifecycleBridge {
            override fun requestStop(): String {
                calls.incrementAndGet()
                return "Parada recusada: nao existe Execute ativo."
            }
            override fun awaitExecuteReturned(timeoutMs: Int): String =
                error("STOP recusado nao pode aguardar")
        }
        val direct = Executor { it.run() }
        val coordinator = CoreSerialLifecycleCoordinator(bridge, direct)

        val first = coordinator.requestStopAndAwait(
            CoreLifecycleStateMachine.Request.MANUAL_STOP, 3000
        ).join()
        val second = coordinator.requestStopAndAwait(
            CoreLifecycleStateMachine.Request.MANUAL_STOP, 3000
        ).join()

        assertFalse(first.stopAccepted)
        assertFalse(second.stopAccepted)
        assertEquals(2, calls.get())
        assertTrue(second.generation > first.generation)
    }

    @Test
    fun executorFailureCompletesFutureExceptionallyAndKeepsStateLatched() {
        val direct = Executor { throw IllegalStateException("worker unavailable") }
        val bridge = object : CoreLifecycleBridge {
            override fun requestStop(): String = error("must not run")
            override fun awaitExecuteReturned(timeoutMs: Int): String = error("must not run")
        }
        val coordinator = CoreSerialLifecycleCoordinator(bridge, direct)
        val future = coordinator.requestStopAndAwait(
            CoreLifecycleStateMachine.Request.ACTIVITY_PAUSE, 1000
        )

        assertTrue(future.isCompletedExceptionally)
        assertEquals(
            CoreLifecycleStateMachine.Phase.EXECUTE_RETURN_TIMEOUT,
            coordinator.snapshot().phase
        )
    }
}
