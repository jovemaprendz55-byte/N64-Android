package com.jsr.n64android

/**
 * Storage contract for the console's physical RDRAM.
 *
 * Addresses passed here are physical RDRAM offsets (0 until sizeBytes), never
 * KSEG0/KSEG1 virtual addresses. Implementations must preserve raw byte order;
 * N64 big-endian interpretation belongs to the guest bus.
 */
internal interface N64HleRdramStorage {
    val sizeBytes: Int

    fun readByte(offset: Int): Int
    fun writeByte(offset: Int, value: Int)
    fun readBytes(offset: Int, length: Int): ByteArray
    fun writeBytes(offset: Int, bytes: ByteArray)
}

/** Heap-backed implementation used by deterministic JVM unit tests. */
internal class N64HleByteArrayRdramStorage(
    override val sizeBytes: Int = N64HleGuestAddressSpace.DEFAULT_RDRAM_SIZE
) : N64HleRdramStorage {
    private val data: ByteArray

    init {
        require(sizeBytes > 0) { "RDRAM size must be positive" }
        data = ByteArray(sizeBytes)
    }

    override fun readByte(offset: Int): Int {
        checkRange(offset, 1)
        return data[offset].toInt() and 0xFF
    }

    override fun writeByte(offset: Int, value: Int) {
        require(value in 0..0xFF) { "Byte value is outside 0..255" }
        checkRange(offset, 1)
        data[offset] = value.toByte()
    }

    override fun readBytes(offset: Int, length: Int): ByteArray {
        checkRange(offset, length)
        return data.copyOfRange(offset, offset + length)
    }

    override fun writeBytes(offset: Int, bytes: ByteArray) {
        checkRange(offset, bytes.size)
        bytes.copyInto(data, offset)
    }

    private fun checkRange(offset: Int, length: Int) {
        require(length >= 0 && offset >= 0 && offset <= sizeBytes - length) {
            "RDRAM range invalid: offset=0x${offset.toString(16)}, length=$length, size=$sizeBytes"
        }
    }
}

/**
 * Adapter for the native engine's 8 MiB RDRAM.
 *
 * This class does not own or close the engine. Its caller controls the engine
 * lifecycle and must keep it alive while this storage is in use.
 */
internal class N64HleNativeRdramStorage(
    private val engine: N64NativeEngine
) : N64HleRdramStorage {
    override val sizeBytes: Int = N64HleGuestAddressSpace.DEFAULT_RDRAM_SIZE

    override fun readByte(offset: Int): Int =
        engine.readRdramByte(offset)

    override fun writeByte(offset: Int, value: Int) =
        engine.writeRdramByte(offset, value)

    override fun readBytes(offset: Int, length: Int): ByteArray {
        checkRange(offset, length)
        return engine.readRdramBytes(offset, length)
    }

    override fun writeBytes(offset: Int, bytes: ByteArray) {
        checkRange(offset, bytes.size)
        engine.writeRdramBytes(offset, bytes)
    }

    private fun checkRange(offset: Int, length: Int) {
        require(length >= 0 && offset >= 0 && offset <= sizeBytes - length) {
            "RDRAM range invalid: offset=0x${offset.toString(16)}, length=$length, size=$sizeBytes"
        }
    }
}
