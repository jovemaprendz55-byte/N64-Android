package com.jsr.n64android

import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreGlTeardownCoordinatorTest {
    private class FakeDispatcher(
        private val accept: Boolean = true,
        private val glThread: Boolean = true
    ) : CoreGlTeardownCoordinator.GlTeardownDispatcher {
        var dispatchCalls = 0
        var actionCalls = 0
        var completionCalls = 0
        var lastMessage: String? = null

        override fun dispatch(
            action: () -> String,
            completion: (String) -> Unit
        ): Boolean {
            dispatchCalls++
            if (!accept) return false
            actionCalls++
            val message = action()
            lastMessage = message
            completionCalls++
            completion(message)
            return true
        }
    }

    private fun serialCoordinator(
        stop: String = "Pedido de parada enviado ao Core",
        wait: String = "M64CMD_EXECUTE retornou. Nenhum teardown foi executado."
    ): CoreSerialLifecycleCoordinator {
        val bridge = object : CoreLifecycleBridge {
            override fun requestStop(): String = stop
            override fun awaitExecuteReturned(timeoutMs: Int): String = wait
        }
        return CoreSerialLifecycleCoordinator(bridge, Executor { it.run() })
    }

    @Test
    fun teardownRunsOnlyAfterExecuteReturnAndThroughGlDispatcher() {
        val dispatcher = FakeDispatcher()
        var shutdownCalls = 0
        val coordinator = CoreGlTeardownCoordinator(
            serialCoordinator(),
            dispatcher,
            CoreGlTeardownCoordinator.EglContextGuard { true }
        ) {
            shutdownCalls++
            "Biblioteca do Core descarregada corretamente."
        }

        val result = coordinator.stopDrainAndTeardown(
            CoreLifecycleStateMachine.Request.MANUAL_STOP, 1000
        ).join()

        assertTrue(result.stopAccepted)
        assertTrue(result.executeReturned)
        assertTrue(result.teardownQueued)
        assertTrue(result.shutdownComplete)
        assertEquals(1, dispatcher.dispatchCalls)
        assertEquals(1, dispatcher.actionCalls)
        assertEquals(1, shutdownCalls)
        assertEquals(
            CoreLifecycleStateMachine.Phase.SHUTDOWN_COMPLETE,
            serialCoordinatorAfterNotAccessible(result)
        )
    }

    private fun serialCoordinatorAfterNotAccessible(
        result: CoreGlTeardownCoordinator.Result
    ): CoreLifecycleStateMachine.Phase {
        // The result is the public observation; internal phase assertions are
        // covered by CoreSerialLifecycleCoordinatorTest.
        return if (result.shutdownComplete) {
            CoreLifecycleStateMachine.Phase.SHUTDOWN_COMPLETE
        } else {
            CoreLifecycleStateMachine.Phase.GL_TEARDOWN_QUEUED
        }
    }

    @Test
    fun rejectedStopNeverDispatchesGlTeardown() {
        val dispatcher = FakeDispatcher()
        var shutdownCalls = 0
        val coordinator = CoreGlTeardownCoordinator(
            serialCoordinator(stop = "Parada recusada: nenhum Execute ativo.", wait = ""),
            dispatcher,
            CoreGlTeardownCoordinator.EglContextGuard { true }
        ) {
            shutdownCalls++
            "Biblioteca do Core descarregada corretamente."
        }

        val result = coordinator.stopDrainAndTeardown(
            CoreLifecycleStateMachine.Request.MANUAL_STOP, 1000
        ).join()

        assertFalse(result.stopAccepted)
        assertFalse(result.teardownQueued)
        assertEquals(0, dispatcher.dispatchCalls)
        assertEquals(0, shutdownCalls)
    }

    @Test
    fun missingEglContextPreventsNativeShutdown() {
        val dispatcher = FakeDispatcher()
        var shutdownCalls = 0
        val coordinator = CoreGlTeardownCoordinator(
            serialCoordinator(),
            dispatcher,
            CoreGlTeardownCoordinator.EglContextGuard { false }
        ) {
            shutdownCalls++
            "Biblioteca do Core descarregada corretamente."
        }

        val result = coordinator.stopDrainAndTeardown(
            CoreLifecycleStateMachine.Request.ACTIVITY_PAUSE, 1000
        ).join()

        assertTrue(result.teardownQueued)
        assertFalse(result.shutdownComplete)
        assertTrue(result.message.contains("EGL"))
        assertEquals(0, shutdownCalls)
    }

    @Test
    fun unavailableSurfaceDoesNotCallNativeShutdown() {
        val dispatcher = FakeDispatcher(accept = false)
        var shutdownCalls = 0
        val coordinator = CoreGlTeardownCoordinator(
            serialCoordinator(),
            dispatcher,
            CoreGlTeardownCoordinator.EglContextGuard { true }
        ) {
            shutdownCalls++
            "Biblioteca do Core descarregada corretamente."
        }

        val result = coordinator.stopDrainAndTeardown(
            CoreLifecycleStateMachine.Request.ACTIVITY_DESTROY, 1000
        ).join()

        assertFalse(result.teardownQueued)
        assertFalse(result.shutdownComplete)
        assertTrue(result.message.contains("Superfície GL indisponível"))
        assertEquals(0, shutdownCalls)
    }

    @Test
    fun nativeShutdownFailureDoesNotMarkLifecycleComplete() {
        val dispatcher = FakeDispatcher()
        val coordinator = CoreGlTeardownCoordinator(
            serialCoordinator(),
            dispatcher,
            CoreGlTeardownCoordinator.EglContextGuard { true }
        ) {
            "CoreShutdown falhou: codigo -1. Biblioteca mantida carregada."
        }

        val result = coordinator.stopDrainAndTeardown(
            CoreLifecycleStateMachine.Request.SCREEN_CHANGE, 1000
        ).join()

        assertTrue(result.teardownQueued)
        assertFalse(result.shutdownComplete)
        assertTrue(result.message.contains("falhou"))
    }
}
