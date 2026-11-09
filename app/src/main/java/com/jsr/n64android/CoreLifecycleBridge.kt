package com.jsr.n64android

/**
 * Narrow contract for lifecycle STOP coordination.
 *
 * Implementations must be backed by an application-lifetime native owner before
 * this interface is wired to Activity callbacks or background workers. In
 * particular, this contract does not authorize shutdown/GL teardown off the GL
 * thread and does not imply that a STOP request has completed Execute.
 */
internal interface CoreLifecycleBridge {
    fun requestStop(): String
    fun awaitExecuteReturned(timeoutMs: Int): String
}
