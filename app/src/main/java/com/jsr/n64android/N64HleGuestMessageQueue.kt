package com.jsr.n64android

/**
 * Guest-memory implementation of libultra OSMesgQueue ring-buffer operations.
 *
 * Blocking calls report BLOCKED after placing the current guest thread on the
 * correct wait queue. The caller must resume the guest continuation after the
 * scheduler later wakes that thread; this host-side method cannot suspend a
 * Kotlin call stack or emulate __osEnqueueAndYield's register save itself.
 */
class N64HleGuestMessageQueue(
    private val memory: N64HleGuestMemory,
    private val codec: N64HleGuestOsMesgQueueCodec,
    private val queues: N64HleGuestThreadQueue,
    private val scheduler: N64HleGuestThreadScheduler,
    private val threadTailAddress: Long
) {
    enum class Status { SENT, RECEIVED, JAMMED, WOULD_BLOCK, BLOCKED }

    data class Result(
        val status: Status,
        val message: Long? = null,
        val dispatch: N64HleGuestThreadScheduler.DispatchDecision? = null
    )

    fun send(queueAddress: Long, message: Long, blocking: Boolean = false): Result {
        val value = message.toGuestWord()
        val queue = codec.read(queueAddress)
        if (queue.validCount >= queue.msgCount) {
            return if (blocking) {
                Result(
                    Status.BLOCKED,
                    dispatch = scheduler.blockRunningThread(
                        queueAddress + N64HleGuestOsMesgQueueLayout.FULL_QUEUE
                    )
                )
            } else {
                Result(Status.WOULD_BLOCK)
            }
        }

        val last = (queue.first + queue.validCount) % queue.msgCount
        memory.write32(queue.msgBuffer + last * MESSAGE_SIZE, value)
        codec.updateCount(queueAddress, queue.validCount + 1)
        return Result(
            Status.SENT,
            dispatch = wakeOne(queueAddress + N64HleGuestOsMesgQueueLayout.MT_QUEUE)
        )
    }

    fun receive(queueAddress: Long, blocking: Boolean = false): Result {
        val queue = codec.read(queueAddress)
        if (queue.validCount == 0) {
            return if (blocking) {
                Result(
                    Status.BLOCKED,
                    dispatch = scheduler.blockRunningThread(
                        queueAddress + N64HleGuestOsMesgQueueLayout.MT_QUEUE
                    )
                )
            } else {
                Result(Status.WOULD_BLOCK)
            }
        }

        val message = memory.read32(queue.msgBuffer + queue.first * MESSAGE_SIZE).toUInt().toLong()
        codec.updateFirst(queueAddress, (queue.first + 1) % queue.msgCount)
        codec.updateCount(queueAddress, queue.validCount - 1)
        return Result(
            Status.RECEIVED,
            message,
            wakeOne(queueAddress + N64HleGuestOsMesgQueueLayout.FULL_QUEUE)
        )
    }

    fun jam(queueAddress: Long, message: Long, blocking: Boolean = false): Result {
        val value = message.toGuestWord()
        val queue = codec.read(queueAddress)
        if (queue.validCount >= queue.msgCount) {
            return if (blocking) {
                Result(
                    Status.BLOCKED,
                    dispatch = scheduler.blockRunningThread(
                        queueAddress + N64HleGuestOsMesgQueueLayout.FULL_QUEUE
                    )
                )
            } else {
                Result(Status.WOULD_BLOCK)
            }
        }

        val first = (queue.first + queue.msgCount - 1) % queue.msgCount
        memory.write32(queue.msgBuffer + first * MESSAGE_SIZE, value)
        codec.updateFirst(queueAddress, first)
        codec.updateCount(queueAddress, queue.validCount + 1)
        return Result(
            Status.JAMMED,
            dispatch = wakeOne(queueAddress + N64HleGuestOsMesgQueueLayout.MT_QUEUE)
        )
    }

    private fun wakeOne(waitQueueAddress: Long): N64HleGuestThreadScheduler.DispatchDecision? {
        val head = queues.head(waitQueueAddress)
        if (head == N64HleGuestThreadQueue.NULL_POINTER || head == threadTailAddress) return null
        val waitingThread = queues.pop(waitQueueAddress)
        return scheduler.startThread(waitingThread)
    }

    private fun Long.toGuestWord(): Int {
        val low = toInt()
        require(this == (low.toLong() and 0xFFFFFFFFL) || this == low.toLong()) {
            "OSMesg must fit in one 32-bit guest word."
        }
        return low
    }

    companion object {
        const val MESSAGE_SIZE = 4
    }
}
