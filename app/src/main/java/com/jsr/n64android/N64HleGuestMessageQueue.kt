package com.jsr.n64android

/**
 * Guest-memory implementation of libultra OSMesgQueue ring-buffer operations.
 *
 * When a context switcher is supplied, blocking operations save the current
 * thread at retryPc (the intercepted API entry), while wakeups save the
 * currently running thread at callerResumePc (the caller's return PC).
 */
class N64HleGuestMessageQueue(
    private val memory: N64HleGuestMemory,
    private val codec: N64HleGuestOsMesgQueueCodec,
    private val queues: N64HleGuestThreadQueue,
    private val scheduler: N64HleGuestThreadScheduler,
    private val threadTailAddress: Long,
    private val contextSwitcher: N64HleGuestContextSwitcher? = null
) {
    enum class Status { SENT, RECEIVED, JAMMED, WOULD_BLOCK, BLOCKED }

    data class Result(
        val status: Status,
        val message: Long? = null,
        val dispatch: N64HleGuestThreadScheduler.DispatchDecision? = null
    )

    /**
     * @param callerResumePc PC after this API call, used if waking a waiter preempts.
     * @param retryPc API entry PC, used when this running thread must block and retry.
     */
    fun send(
        queueAddress: Long,
        message: Long,
        blocking: Boolean = false,
        callerResumePc: Long? = null,
        retryPc: Long? = null
    ): Result {
        val value = message.toGuestWord()
        val queue = codec.read(queueAddress)
        if (queue.validCount >= queue.msgCount) {
            return if (blocking) {
                Result(
                    Status.BLOCKED,
                    dispatch = blockCurrent(
                        queueAddress + N64HleGuestOsMesgQueueLayout.FULL_QUEUE,
                        retryPc
                    )
                )
            } else {
                Result(Status.WOULD_BLOCK)
            }
        }

        ensureWakeContinuation(
            queueAddress + N64HleGuestOsMesgQueueLayout.MT_QUEUE,
            callerResumePc
        )
        val last = (queue.first + queue.validCount) % queue.msgCount
        memory.write32(queue.msgBuffer + last * MESSAGE_SIZE, value)
        codec.updateCount(queueAddress, queue.validCount + 1)
        return Result(
            Status.SENT,
            dispatch = wakeOne(
                queueAddress + N64HleGuestOsMesgQueueLayout.MT_QUEUE,
                callerResumePc
            )
        )
    }

    /** See [send] for the meanings of callerResumePc and retryPc. */
    fun receive(
        queueAddress: Long,
        blocking: Boolean = false,
        callerResumePc: Long? = null,
        retryPc: Long? = null
    ): Result {
        val queue = codec.read(queueAddress)
        if (queue.validCount == 0) {
            return if (blocking) {
                Result(
                    Status.BLOCKED,
                    dispatch = blockCurrent(
                        queueAddress + N64HleGuestOsMesgQueueLayout.MT_QUEUE,
                        retryPc
                    )
                )
            } else {
                Result(Status.WOULD_BLOCK)
            }
        }

        ensureWakeContinuation(
            queueAddress + N64HleGuestOsMesgQueueLayout.FULL_QUEUE,
            callerResumePc
        )
        val message = memory.read32(queue.msgBuffer + queue.first * MESSAGE_SIZE).toUInt().toLong()
        codec.updateFirst(queueAddress, (queue.first + 1) % queue.msgCount)
        codec.updateCount(queueAddress, queue.validCount - 1)
        return Result(
            Status.RECEIVED,
            message,
            wakeOne(
                queueAddress + N64HleGuestOsMesgQueueLayout.FULL_QUEUE,
                callerResumePc
            )
        )
    }

    /** See [send] for the meanings of callerResumePc and retryPc. */
    fun jam(
        queueAddress: Long,
        message: Long,
        blocking: Boolean = false,
        callerResumePc: Long? = null,
        retryPc: Long? = null
    ): Result {
        val value = message.toGuestWord()
        val queue = codec.read(queueAddress)
        if (queue.validCount >= queue.msgCount) {
            return if (blocking) {
                Result(
                    Status.BLOCKED,
                    dispatch = blockCurrent(
                        queueAddress + N64HleGuestOsMesgQueueLayout.FULL_QUEUE,
                        retryPc
                    )
                )
            } else {
                Result(Status.WOULD_BLOCK)
            }
        }

        ensureWakeContinuation(
            queueAddress + N64HleGuestOsMesgQueueLayout.MT_QUEUE,
            callerResumePc
        )
        val first = (queue.first + queue.msgCount - 1) % queue.msgCount
        memory.write32(queue.msgBuffer + first * MESSAGE_SIZE, value)
        codec.updateFirst(queueAddress, first)
        codec.updateCount(queueAddress, queue.validCount + 1)
        return Result(
            Status.JAMMED,
            dispatch = wakeOne(
                queueAddress + N64HleGuestOsMesgQueueLayout.MT_QUEUE,
                callerResumePc
            )
        )
    }

    private fun blockCurrent(
        waitQueueAddress: Long,
        retryPc: Long?
    ): N64HleGuestThreadScheduler.DispatchDecision {
        if (contextSwitcher == null) return scheduler.blockRunningThread(waitQueueAddress)
        requireNotNull(retryPc) {
            "A blocked HLE message call requires its guest API-entry retry PC."
        }
        return contextSwitcher.blockCurrent(waitQueueAddress, retryPc)
    }

    private fun ensureWakeContinuation(waitQueueAddress: Long, callerResumePc: Long?) {
        if (contextSwitcher == null) return
        val head = queues.head(waitQueueAddress)
        if (head != N64HleGuestThreadQueue.NULL_POINTER && head != threadTailAddress) {
            requireNotNull(callerResumePc) {
                "Waking a guest thread requires the current caller's return PC."
            }
        }
    }

    private fun wakeOne(
        waitQueueAddress: Long,
        callerResumePc: Long?
    ): N64HleGuestThreadScheduler.DispatchDecision? {
        val head = queues.head(waitQueueAddress)
        if (head == N64HleGuestThreadQueue.NULL_POINTER || head == threadTailAddress) return null
        if (contextSwitcher != null) {
            requireNotNull(callerResumePc) {
                "Waking a guest thread requires the current caller's return PC."
            }
        }
        val waitingThread = queues.pop(waitQueueAddress)
        return if (contextSwitcher != null) {
            contextSwitcher.startThread(waitingThread, callerResumePc!!)
        } else {
            scheduler.startThread(waitingThread)
        }
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
