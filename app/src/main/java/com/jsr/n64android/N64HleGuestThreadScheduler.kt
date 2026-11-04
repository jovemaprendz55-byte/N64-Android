package com.jsr.n64android

/**
 * Guest-memory model of libultra thread state transitions.
 * Global addresses are guest words containing pointers, never assumed ROM symbols.
 * This models state/queue decisions only; it does not save or restore live CPU context.
 */
class N64HleGuestThreadScheduler(
    private val memory: N64HleGuestMemory,
    private val codec: N64HleGuestOsThreadCodec,
    private val queues: N64HleGuestThreadQueue,
    private val runQueueAddress: Long,
    private val activeQueueAddress: Long,
    private val runningThreadAddress: Long,
    private val runQueueTailAddress: Long
) {
    data class DispatchDecision(
        val previousRunningThread: Long,
        val selectedThread: Long,
        val switched: Boolean
    )

    init {
        validateGlobalPointer(runQueueAddress)
        validateGlobalPointer(activeQueueAddress)
        validateGlobalPointer(runningThreadAddress)
        validateThread(runQueueTailAddress)
        require(setOf(runQueueAddress, activeQueueAddress, runningThreadAddress).size == 3) {
            "Scheduler global pointer words must be distinct."
        }
    }

    /** Mirrors osCreateThread's prepend to __osActiveQueue after caller initializes OSThread. */
    fun registerCreatedThread(threadAddress: Long) {
        validateThread(threadAddress)
        require(codec.read(threadAddress).state == N64HleGuestOsThreadLayout.STATE_STOPPED) {
            "A newly created thread must begin STOPPED."
        }
        val head = readPointer(activeQueueAddress)
        val visited = HashSet<Long>()
        var current = head
        while (current != NULL_POINTER && current != runQueueTailAddress) {
            validateThread(current)
            check(visited.add(current)) { "Cycle detected in active thread list." }
            check(current != threadAddress) { "Thread is already registered in active list." }
            current = codec.read(current).threadListNext
        }
        writePointer(threadAddress + N64HleGuestOsThreadLayout.THREAD_TL_NEXT, head)
        writePointer(activeQueueAddress, threadAddress)
    }

    /** Models the state/queue part of osStartThread; it does not switch CPU registers. */
    fun startThread(threadAddress: Long): DispatchDecision {
        validateThread(threadAddress)
        val thread = codec.read(threadAddress)
        when (thread.state) {
            N64HleGuestOsThreadLayout.STATE_WAITING -> {
                writeState(threadAddress, N64HleGuestOsThreadLayout.STATE_RUNNABLE)
                queues.enqueue(runQueueAddress, threadAddress)
            }
            N64HleGuestOsThreadLayout.STATE_STOPPED -> {
                if (thread.queue == NULL_POINTER || thread.queue == runQueueAddress) {
                    writeState(threadAddress, N64HleGuestOsThreadLayout.STATE_RUNNABLE)
                    queues.enqueue(runQueueAddress, threadAddress)
                } else {
                    writeState(threadAddress, N64HleGuestOsThreadLayout.STATE_WAITING)
                    queues.enqueue(thread.queue, threadAddress)
                    val awakened = queues.pop(thread.queue)
                    if (awakened != NULL_POINTER && awakened != runQueueTailAddress) {
                        queues.enqueue(runQueueAddress, awakened)
                    }
                }
            }
            N64HleGuestOsThreadLayout.STATE_RUNNABLE,
            N64HleGuestOsThreadLayout.STATE_RUNNING -> Unit
            else -> error("Unknown libultra thread state ${thread.state}.")
        }
        return preemptIfNeeded()
    }

    /** Models osStopThread. Null means the current running thread. */
    fun stopThread(threadAddress: Long = NULL_POINTER): DispatchDecision {
        val current = readPointer(runningThreadAddress)
        val target = if (threadAddress == NULL_POINTER) current else threadAddress
        require(target != NULL_POINTER) { "There is no running thread to stop." }
        validateThread(target)
        val thread = codec.read(target)
        when (thread.state) {
            N64HleGuestOsThreadLayout.STATE_RUNNING -> {
                require(target == current) { "Only the current thread may be in RUNNING state." }
                writeState(target, N64HleGuestOsThreadLayout.STATE_STOPPED)
                return dispatchNext(current)
            }
            N64HleGuestOsThreadLayout.STATE_RUNNABLE,
            N64HleGuestOsThreadLayout.STATE_WAITING -> {
                require(thread.queue != NULL_POINTER) {
                    "Runnable/waiting thread has no owning queue."
                }
                writeState(target, N64HleGuestOsThreadLayout.STATE_STOPPED)
                queues.dequeue(thread.queue, target)
            }
            N64HleGuestOsThreadLayout.STATE_STOPPED -> Unit
            else -> error("Unknown libultra thread state ${thread.state}.")
        }
        return DispatchDecision(current, current, false)
    }

    /** Models the blocking part of __osEnqueueAndYield(queue): save is external, state/queue are guest-backed. */
    fun blockRunningThread(waitQueueAddress: Long): DispatchDecision {
        memory.translateRdramAddress(waitQueueAddress, width = 4, alignment = 4)
        val current = readPointer(runningThreadAddress)
        require(current != NULL_POINTER && current != runQueueTailAddress) {
            "There is no valid running thread to block."
        }
        require(codec.read(current).state == N64HleGuestOsThreadLayout.STATE_RUNNING) {
            "Current thread is not in RUNNING state."
        }
        writeState(current, N64HleGuestOsThreadLayout.STATE_WAITING)
        queues.enqueue(waitQueueAddress, current)
        return dispatchNext(current)
    }

    /** Models osYieldThread: enqueue current at its priority and choose the next runnable thread. */
    fun yieldThread(): DispatchDecision {
        val current = readPointer(runningThreadAddress)
        require(current != NULL_POINTER && current != runQueueTailAddress) {
            "There is no valid running thread to yield."
        }
        require(codec.read(current).state == N64HleGuestOsThreadLayout.STATE_RUNNING) {
            "Current thread is not in RUNNING state."
        }
        writeState(current, N64HleGuestOsThreadLayout.STATE_RUNNABLE)
        queues.enqueue(runQueueAddress, current)
        return dispatchNext(current)
    }

    /** Selects the next ready thread without changing the live CPU context. */
    fun dispatchNext(previousRunningThread: Long = readPointer(runningThreadAddress)): DispatchDecision {
        val candidate = queues.head(runQueueAddress)
        if (candidate == NULL_POINTER || candidate == runQueueTailAddress) {
            writePointer(runningThreadAddress, NULL_POINTER)
            return DispatchDecision(previousRunningThread, NULL_POINTER, previousRunningThread != NULL_POINTER)
        }
        val selected = queues.pop(runQueueAddress)
        validateThread(selected)
        writeState(selected, N64HleGuestOsThreadLayout.STATE_RUNNING)
        writePointer(runningThreadAddress, selected)
        return DispatchDecision(previousRunningThread, selected, previousRunningThread != selected)
    }

    private fun preemptIfNeeded(): DispatchDecision {
        val current = readPointer(runningThreadAddress)
        if (current == NULL_POINTER || current == runQueueTailAddress) return dispatchNext(current)
        val head = queues.head(runQueueAddress)
        if (head == NULL_POINTER || head == runQueueTailAddress) {
            return DispatchDecision(current, current, false)
        }
        if (codec.read(head).priority <= codec.read(current).priority) {
            return DispatchDecision(current, current, false)
        }
        writeState(current, N64HleGuestOsThreadLayout.STATE_RUNNABLE)
        queues.enqueue(runQueueAddress, current)
        return dispatchNext(current)
    }

    private fun writeState(threadAddress: Long, state: Int) {
        memory.write16(threadAddress + N64HleGuestOsThreadLayout.THREAD_STATE, state)
    }

    private fun validateGlobalPointer(address: Long) {
        memory.translateRdramAddress(address, width = 4, alignment = 4)
    }

    private fun validateThread(address: Long) {
        require(address != NULL_POINTER) { "Null is not a valid OSThread address." }
        memory.translateRdramAddress(
            address,
            width = N64HleGuestOsThreadLayout.THREAD_SIZE,
            alignment = 8
        )
    }

    private fun readPointer(address: Long): Long = memory.read32(address).toUInt().toLong()

    private fun writePointer(address: Long, pointer: Long) {
        require(pointer == (pointer.toInt().toLong() and 0xFFFFFFFFL) || pointer == pointer.toInt().toLong()) {
            "Guest pointer must fit in 32 bits."
        }
        memory.write32(address, pointer.toInt())
    }

    companion object {
        const val NULL_POINTER = 0L
    }
}
