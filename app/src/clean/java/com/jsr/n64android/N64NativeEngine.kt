package com.jsr.n64android

/**
 * Owns the lifetime of the independent native engine scaffold.
 *
 * This currently validates JNI loading and lifecycle only; it does not emulate
 * the R4300, RSP, RDP, audio, or any N64 hardware.
 */
internal class N64NativeEngine : AutoCloseable {
    private var handle: Long

    init {
        System.loadLibrary("n64android_engine")
        handle = nativeCreate()
        check(handle != 0L) { "Não foi possível criar a engine nativa." }
    }

    fun apiVersion(): Int {
        check(handle != 0L) { "A engine nativa já foi encerrada." }
        return nativeGetApiVersion(handle)
    }

    fun state(): State {
        check(handle != 0L) { "A engine nativa já foi encerrada." }
        return when (nativeGetState(handle)) {
            STATE_CREATED -> State.CREATED
            else -> State.UNKNOWN
        }
    }

    override fun close() {
        val current = handle
        if (current != 0L) {
            handle = 0L
            nativeDestroy(current)
        }
    }

    enum class State { CREATED, UNKNOWN }

    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeGetApiVersion(handle: Long): Int
    private external fun nativeGetState(handle: Long): Int

    private companion object {
        const val STATE_CREATED = 1
    }
}
