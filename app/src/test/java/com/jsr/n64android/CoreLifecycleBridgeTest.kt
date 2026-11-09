package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreLifecycleBridgeTest {
    private class FakeBridge(
        private val stopResult: String,
        private val waitResult: String
    ) : CoreLifecycleBridge {
        val calls = mutableListOf<String>()

        override fun requestStop(): String {
            calls += "stop"
            return stopResult
        }

        override fun awaitExecuteReturned(timeoutMs: Int): String {
            calls += "wait:$timeoutMs"
            return waitResult
        }
    }

    @Test
    fun bridgePreservesStopAndWaitOrderAndTimeout() {
        val fake = FakeBridge("STOP enviado", "EXECUTE retornou")

        assertEquals("STOP enviado", fake.requestStop())
        assertEquals("EXECUTE retornou", fake.awaitExecuteReturned(3000))
        assertEquals(listOf("stop", "wait:3000"), fake.calls)
    }

    @Test
    fun bridgeCanRepresentStopRejectionWithoutPretendingWaitOccurred() {
        val fake = FakeBridge("Parada recusada", "nao deveria ser chamado")

        val stop = fake.requestStop()
        assertTrue(stop.startsWith("Parada recusada"))
        assertEquals(listOf("stop"), fake.calls)
    }
}
