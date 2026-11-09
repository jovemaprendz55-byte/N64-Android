package com.jsr.n64android

/**
 * Synchronous STOP handshake policy. Call this from a worker thread.
 * This class never performs shutdown, plugin teardown, or EGL operations.
 */
internal class CoreStopCoordinator(
    private val bridge: CoreLifecycleBridge
) {
    data class Result(
        val stopResult: String,
        val waitResult: String?,
        val stopAccepted: Boolean
    )

    fun requestStopAndAwait(timeoutMs: Int): Result {
        val stop = bridge.requestStop()
        val accepted = stop.startsWith("Pedido de parada enviado ao Core") ||
            stop.startsWith("Pedido M64CMD_STOP ja foi enviado")
        if (!accepted) return Result(stop, null, false)

        val wait = bridge.awaitExecuteReturned(timeoutMs.coerceAtLeast(0))
        return Result(stop, wait, true)
    }
}
