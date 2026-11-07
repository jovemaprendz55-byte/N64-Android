package com.jsr.n64android

/**
 * Owns the independent native engine and its 8 MiB big-endian RDRAM.
 *
 * This is a memory/lifecycle foundation only. CPU execution, RSP, RDP, MMIO,
 * audio, timing, and game compatibility are not implemented here.
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

    fun readRdramByte(address: Int): Int =
        read(address, 1) { engine, offset -> nativeReadRdramByte(engine, offset) }

    fun readRdramHalf(address: Int): Int =
        read(address, 2) { engine, offset -> nativeReadRdramHalf(engine, offset) }

    fun readRdramWord(address: Int): Int =
        read(address, 4) { engine, offset -> nativeReadRdramWord(engine, offset) }

    fun readRdramDouble(address: Int): Long {
        checkOpen()
        requireAddress(address, 8)
        return nativeReadRdramDouble(handle, address)
    }

    fun writeRdramByte(address: Int, value: Int) =
        write(address, 1, value, 0xFF) { engine, offset, data ->
            nativeWriteRdramByte(engine, offset, data)
        }

    fun writeRdramHalf(address: Int, value: Int) =
        write(address, 2, value, 0xFFFF) { engine, offset, data ->
            nativeWriteRdramHalf(engine, offset, data)
        }

    fun writeRdramWord(address: Int, value: Int) =
        write(address, 4, value, null) { engine, offset, data ->
            nativeWriteRdramWord(engine, offset, data)
        }

    fun writeRdramDouble(address: Int, value: Long) {
        checkOpen()
        requireAddress(address, 8)
        check(nativeWriteRdramDouble(handle, address, value)) {
            "Não foi possível escrever na RDRAM."
        }
    }

    private inline fun read(
        address: Int,
        width: Int,
        reader: (Long, Int) -> Int
    ): Int {
        checkOpen()
        requireAddress(address, width)
        return reader(handle, address)
    }

    private inline fun write(
        address: Int,
        width: Int,
        value: Int,
        maxValue: Int?,
        writer: (Long, Int, Int) -> Boolean
    ) {
        checkOpen()
        requireAddress(address, width)
        if (maxValue != null) {
            require(value in 0..maxValue) {
                "Valor excede a largura de $width byte(s)."
            }
        }
        check(writer(handle, address, value)) {
            "Não foi possível escrever na RDRAM."
        }
    }

    private fun requireAddress(address: Int, width: Int) {
        require(address >= 0 && address % width == 0 && address <= RDRAM_SIZE - width) {
            "Endereço RDRAM inválido ou desalinhado para $width byte(s): 0x${address.toString(16)}"
        }
    }

    private fun checkOpen() {
        check(handle != 0L) { "A engine nativa já foi encerrada." }
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
    private external fun nativeReadRdramByte(handle: Long, address: Int): Int
    private external fun nativeWriteRdramByte(handle: Long, address: Int, value: Int): Boolean
    private external fun nativeReadRdramHalf(handle: Long, address: Int): Int
    private external fun nativeWriteRdramHalf(handle: Long, address: Int, value: Int): Boolean
    private external fun nativeReadRdramWord(handle: Long, address: Int): Int
    private external fun nativeWriteRdramWord(handle: Long, address: Int, value: Int): Boolean
    private external fun nativeReadRdramDouble(handle: Long, address: Int): Long
    private external fun nativeWriteRdramDouble(handle: Long, address: Int, value: Long): Boolean

    private companion object {
        const val STATE_CREATED = 1
        const val RDRAM_SIZE = 8 * 1024 * 1024
    }
}
