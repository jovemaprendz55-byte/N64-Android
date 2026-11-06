package com.jsr.n64android

/**
 * Owns the lifetime of the independent native engine scaffold.
 *
 * The native side currently provides an 8 MiB big-endian RDRAM word-access
 * primitive and lifecycle/API diagnostics. CPU, RSP, RDP, audio, and game
 * execution are not implemented by this class.
 */
internal class N64NativeEngine : AutoCloseable {
    private var handle: Long

    init {
        System.loadLibrary("n64android_engine")
        handle = nativeCreate()
        check(handle != 0L) { "Não foi possível criar a engine nativa." }
    }

    fun apiVersion(): Int {
        checkOpen()
        return nativeGetApiVersion(handle)
    }

    fun state(): State {
        checkOpen()
        return when (nativeGetState(handle)) {
            STATE_CREATED -> State.CREATED
            else -> State.UNKNOWN
        }
    }

    /** Reads one aligned 32-bit word from RDRAM using N64 big-endian byte order. */
    fun readRdramWord(address: Int): Int {
        checkOpen()
        requireValidWordAddress(address)
        return nativeReadRdramWord(handle, address)
    }

    /** Writes one aligned 32-bit word to RDRAM using N64 big-endian byte order. */
    fun writeRdramWord(address: Int, value: Int) {
        checkOpen()
        requireValidWordAddress(address)
        check(nativeWriteRdramWord(handle, address, value)) {
            "Não foi possível escrever na RDRAM."
        }
    }

    private fun checkOpen() {
        check(handle != 0L) { "A engine nativa já foi encerrada." }
    }

    private fun requireValidWordAddress(address: Int) {
        require(address >= 0 && address % 4 == 0 && address <= RDRAM_SIZE - 4) {
            "Endereço RDRAM inválido ou desalinhado: 0x${address.toString(16)}"
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
    private external fun nativeWriteRdramWord(handle: Long, address: Int, value: Int): Boolean
    private external fun nativeReadRdramWord(handle: Long, address: Int): Int

    private companion object {
        const val STATE_CREATED = 1
        const val RDRAM_SIZE = 8 * 1024 * 1024
    }
}
