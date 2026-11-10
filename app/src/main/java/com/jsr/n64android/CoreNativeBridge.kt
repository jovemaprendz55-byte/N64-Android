package com.jsr.n64android

/**
 * Process-lifetime owner for native Core lifecycle calls.
 *
 * The native library must be loaded before this object is first used.
 * STOP and Execute-return waiting are exposed through CoreLifecycleBridge.
 * nativeShutdown() is a separate endpoint and must only be called by a
 * lifecycle owner after the required Execute/GL/EGL safety gates are proven.
 */
internal object CoreNativeBridge : CoreLifecycleBridge {
    external fun nativeStopCore(): String
    external fun nativeAwaitExecuteReturned(timeoutMs: Int): String
    external fun nativeShutdown(): String

    override fun requestStop(): String = nativeStopCore()

    override fun awaitExecuteReturned(timeoutMs: Int): String =
        nativeAwaitExecuteReturned(timeoutMs)
}
