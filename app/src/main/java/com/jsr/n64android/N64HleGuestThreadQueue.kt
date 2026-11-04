package com.jsr.n64android

/**
 * Guest-memory implementation of libultra's __osEnqueueThread,
 * __osPopThread and __osDequeueThread linked-list primitives.
 *
 * queueAddress is the guest address of an OSThread* head word (not the
 * address of an OSThread object). Queues may use a tail sentinel whose
 * priority is -1, as GoldenEye's __osThreadTail does.
 */
class N64HleGuestThreadQueue(
    private val memory: N64HleGuestMemory
) {
    fun enqueue(queueAddress: Long, threadAddress: Long) {
        validateQueueAddress(queueAddress)
        validateThreadAddress(threadAddress)
        val priority = memory.read32(threadAddress + N64HleGuestOsThreadLayout.THREAD_PRIORITY)
        require(priority in MIN_THREAD_PRIORITY..MAX_THREAD_PRIORITY) {
            "Guest thread priority must be in 0..255: $priority"
        }

        var linkAddress = queueAddress
        var current = readPointer(linkAddress)
        val visited = HashSet<Long>()
        while (current != NULL_POINTER) {
            validateThreadAddress(current)
            check(visited.add(current)) { "Cycle detected in guest thread queue." }
            check(current != threadAddress) { "Thread is already present in this queue." }

            val currentPriority = memory.read32(
                current + N64HleGuestOsThreadLayout.THREAD_PRIORITY
            )
            if (currentPriority < priority) break

            // In libultra, equal priorities are traversed, so new entries are FIFO.
            linkAddress = current
            current = readPointer(current)
        }

        // All pointers and structures have been checked before the first write.
        writePointer(threadAddress + N64HleGuestOsThreadLayout.THREAD_NEXT, current)
        writePointer(linkAddress, threadAddress)
        writePointer(threadAddress + N64HleGuestOsThreadLayout.THREAD_QUEUE, queueAddress)
    }

    /** Mirrors __osPopThread: returns the head (including a tail sentinel) and unlinks it. */
    fun pop(queueAddress: Long): Long {
        validateQueueAddress(queueAddress)
        val head = readPointer(queueAddress)
        if (head == NULL_POINTER) return NULL_POINTER
        validateThreadAddress(head)
        val next = readPointer(head)
        writePointer(queueAddress, next)
        return head
    }

    /** Mirrors __osDequeueThread and deliberately leaves the thread's queue field unchanged. */
    fun dequeue(queueAddress: Long, threadAddress: Long): Boolean {
        validateQueueAddress(queueAddress)
        validateThreadAddress(threadAddress)
        var linkAddress = queueAddress
        var current = readPointer(linkAddress)
        val visited = HashSet<Long>()
        while (current != NULL_POINTER) {
            validateThreadAddress(current)
            check(visited.add(current)) { "Cycle detected in guest thread queue." }
            if (current == threadAddress) {
                val next = readPointer(current)
                writePointer(linkAddress, next)
                return true
            }
            linkAddress = current
            current = readPointer(current)
        }
        return false
    }

    fun head(queueAddress: Long): Long {
        validateQueueAddress(queueAddress)
        return readPointer(queueAddress)
    }

    private fun validateQueueAddress(address: Long) {
        memory.translateRdramAddress(address, width = POINTER_SIZE, alignment = POINTER_SIZE)
    }

    private fun validateThreadAddress(address: Long) {
        require(address != NULL_POINTER) { "Null is not a valid OSThread address." }
        memory.translateRdramAddress(
            address,
            width = N64HleGuestOsThreadLayout.THREAD_SIZE,
            alignment = THREAD_ALIGNMENT
        )
    }

    private fun readPointer(address: Long): Long =
        memory.read32(address).toUInt().toLong()

    private fun writePointer(address: Long, pointer: Long) {
        val low = pointer.toInt()
        require(pointer == (low.toLong() and 0xFFFFFFFFL) || pointer == low.toLong()) {
            "Guest queue pointer must fit in 32 bits."
        }
        memory.write32(address, low)
    }

    companion object {
        const val NULL_POINTER = 0L
        const val POINTER_SIZE = 4
        const val THREAD_ALIGNMENT = 8
        const val MIN_THREAD_PRIORITY = 0
        const val MAX_THREAD_PRIORITY = 255
    }
}
