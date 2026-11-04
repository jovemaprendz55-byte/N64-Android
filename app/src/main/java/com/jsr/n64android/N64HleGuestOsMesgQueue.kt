package com.jsr.n64android

/** 32-bit libultra OSMesgQueue layout from GoldenEye's PR/os.h. */
object N64HleGuestOsMesgQueueLayout {
    const val MT_QUEUE = 0x00
    const val FULL_QUEUE = 0x04
    const val VALID_COUNT = 0x08
    const val FIRST = 0x0C
    const val MSG_COUNT = 0x10
    const val MSG_BUFFER = 0x14
    const val SIZE = 0x18
}

data class N64HleGuestOsMesgQueue(
    val mtQueue: Long,
    val fullQueue: Long,
    val validCount: Int,
    val first: Int,
    val msgCount: Int,
    val msgBuffer: Long
)

/** Checked big-endian codec for the six-word libultra OSMesgQueue structure. */
class N64HleGuestOsMesgQueueCodec(private val memory: N64HleGuestMemory) {
    fun create(address: Long, msgBuffer: Long, msgCount: Int, threadTail: Long) {
        validateQueueStruct(address)
        require(msgCount > 0) { "OSMesgQueue capacity must be positive." }
        val byteCount = Math.multiplyExact(msgCount, MESSAGE_SIZE)
        memory.translateRdramAddress(msgBuffer, width = byteCount, alignment = MESSAGE_SIZE)
        memory.translateRdramAddress(threadTail, width = N64HleGuestOsThreadLayout.THREAD_SIZE, alignment = 8)
        memory.write32(address + N64HleGuestOsMesgQueueLayout.MT_QUEUE, threadTail.toInt())
        memory.write32(address + N64HleGuestOsMesgQueueLayout.FULL_QUEUE, threadTail.toInt())
        memory.write32(address + N64HleGuestOsMesgQueueLayout.VALID_COUNT, 0)
        memory.write32(address + N64HleGuestOsMesgQueueLayout.FIRST, 0)
        memory.write32(address + N64HleGuestOsMesgQueueLayout.MSG_COUNT, msgCount)
        memory.write32(address + N64HleGuestOsMesgQueueLayout.MSG_BUFFER, msgBuffer.toInt())
    }

    fun read(address: Long): N64HleGuestOsMesgQueue {
        validateQueueStruct(address)
        val l = N64HleGuestOsMesgQueueLayout
        val mtQueue = memory.read32(address + l.MT_QUEUE).toUnsignedLong()
        val fullQueue = memory.read32(address + l.FULL_QUEUE).toUnsignedLong()
        val validCount = memory.read32(address + l.VALID_COUNT)
        val first = memory.read32(address + l.FIRST)
        val msgCount = memory.read32(address + l.MSG_COUNT)
        val msgBuffer = memory.read32(address + l.MSG_BUFFER).toUnsignedLong()
        require(msgCount > 0) { "OSMesgQueue capacity must be positive." }
        require(validCount in 0..msgCount) { "OSMesgQueue validCount is outside capacity." }
        require(first in 0 until msgCount) { "OSMesgQueue first index is outside capacity." }
        memory.translateRdramAddress(mtQueue, width = 4, alignment = 4)
        memory.translateRdramAddress(fullQueue, width = 4, alignment = 4)
        memory.translateRdramAddress(msgBuffer, width = Math.multiplyExact(msgCount, MESSAGE_SIZE), alignment = MESSAGE_SIZE)
        return N64HleGuestOsMesgQueue(mtQueue, fullQueue, validCount, first, msgCount, msgBuffer)
    }

    fun updateCount(address: Long, validCount: Int) {
        val queue = read(address)
        require(validCount in 0..queue.msgCount) { "OSMesgQueue validCount is outside capacity." }
        memory.write32(address + N64HleGuestOsMesgQueueLayout.VALID_COUNT, validCount)
    }

    fun updateFirst(address: Long, first: Int) {
        val queue = read(address)
        require(first in 0 until queue.msgCount) { "OSMesgQueue first index is outside capacity." }
        memory.write32(address + N64HleGuestOsMesgQueueLayout.FIRST, first)
    }

    private fun validateQueueStruct(address: Long) {
        memory.translateRdramAddress(address, width = N64HleGuestOsMesgQueueLayout.SIZE, alignment = 4)
    }

    private fun Int.toUnsignedLong(): Long = toUInt().toLong()

    companion object {
        const val MESSAGE_SIZE = 4
    }
}
