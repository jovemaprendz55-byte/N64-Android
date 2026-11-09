package com.jsr.n64android

/**
 * Process-lifetime owner for native Core lifecycle calls.
 *
 * The native library must be loaded before this object is first used.
 * This bridge only requests STOP and waits for synchronous EXECUTE return;
 * it never performs Core shutdown, plugin teardown, or EGL operations.
 */
internal object CoreNativeBridge : CoreLifecycleBridge {
    external fun nativeStopCore(): String
    external fun nativeAwaitExecuteReturned(timeoutMs: Int): String
    external fun nativeShutdown(): String

    override fun requestStop(): String = nativeStopCore()

    override fun awaitExecuteReturned(timeoutMs: Int): String =
        nativeAwaitExecuteReturned(timeoutMs)
}
