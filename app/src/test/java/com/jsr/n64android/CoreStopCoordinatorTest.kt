package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreStopCoordinatorTest {
    private class FakeBridge(
        private val stop: String,
        private val wait: String
    ) : CoreLifecycleBridge {
        val calls = mutableListOf<String>()

        override fun requestStop(): String {
            calls += "stop"
            return stop
        }

        override fun awaitExecuteReturned(timeoutMs: Int): String {
            calls += "wait:$timeoutMs"
            return wait
        }
    }

    @Test
    fun acceptedStopWaitsForExecuteReturn() {
        val bridge = FakeBridge(
            "Pedido de parada enviado ao Core",
            "M64CMD_EXECUTE retornou"
        )
        val result = CoreStopCoordinator(bridge).requestStopAndAwait(3000)

        assertTrue(result.stopAccepted)
        assertEquals("M64CMD_EXECUTE retornou", result.waitResult)
        assertEquals(listOf("stop", "wait:3000"), bridge.calls)
    }

    @Test
    fun duplicateAcceptedStopAlsoWaitsForReturn() {
        val bridge = FakeBridge(
            "Pedido M64CMD_STOP ja foi enviado",
            "M64CMD_EXECUTE retornou"
        )
        val result = CoreStopCoordinator(bridge).requestStopAndAwait(1500)

        assertTrue(result.stopAccepted)
        assertEquals(listOf("stop", "wait:1500"), bridge.calls)
    }

    @Test
    fun rejectedStopNeverWaits() {
        val bridge = FakeBridge("Parada recusada: nenhum Execute ativo", "unused")
        val result = CoreStopCoordinator(bridge).requestStopAndAwait(3000)

        assertFalse(result.stopAccepted)
        assertNull(result.waitResult)
        assertEquals(listOf("stop"), bridge.calls)
    }

    @Test
    fun negativeTimeoutIsClampedToZero() {
        val bridge = FakeBridge("Pedido de parada enviado ao Core", "timeout")
        CoreStopCoordinator(bridge).requestStopAndAwait(-10)

        assertEquals(listOf("stop", "wait:0"), bridge.calls)
    }
}
