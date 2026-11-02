package com.jsr.n64android

/**
 * Host-side first-stage model of libultra-style OS scheduling primitives.
 *
 * This is deliberately not advertised as guest ABI interception: it does not
 * read/write guest OSThread/OSMesgQueue structures and does not switch R4300
 * register contexts. It provides deterministic semantics that can later be
 * connected to guest memory and CPU context save/restore.
 */
class N64HleOsKernel {
    enum class ThreadState { STOPPED, RUNNABLE, RUNNING, WAITING_MESSAGE, WAITING_EVENT, TERMINATED }

    data class ThreadSnapshot(
        val id: Int,
        val priority: Int,
        val entryPoint: Long,
        val argument: Long,
        val state: ThreadState,
        val wakeSequence: Long
    )

    data class MessageQueueSnapshot(
        val capacity: Int,
        val queuedMessages: List<Long>,
        val waitingSenders: Int,
        val waitingReceivers: Int
    )

    data class Event(val type: Int, val value: Long, val sequence: Long)

    private data class ThreadRecord(
        val id: Int,
        val priority: Int,
        val entryPoint: Long,
        val argument: Long,
        var state: ThreadState = ThreadState.STOPPED,
        var wakeSequence: Long = 0L
    )

    private data class QueueRecord(
        val capacity: Int,
        val messages: ArrayDeque<Long> = ArrayDeque(),
        val waitingSenders: ArrayDeque<Int> = ArrayDeque(),
        val waitingReceivers: ArrayDeque<Int> = ArrayDeque()
    )

    private val threads = linkedMapOf<Int, ThreadRecord>()
    private val queues = linkedMapOf<Int, QueueRecord>()
    private val events = ArrayDeque<Event>()
    private var sequence = 0L
    private var currentThreadId: Int? = null

    @Synchronized
    fun createThread(id: Int, priority: Int, entryPoint: Long, argument: Long = 0L) {
        require(id >= 0) { "Thread id must be non-negative." }
        require(priority >= 0) { "Thread priority must be non-negative." }
        require(entryPoint != 0L && entryPoint and 3L == 0L) { "Thread entry point must be nonzero and 4-byte aligned." }
        require(id !in threads) { "Thread $id already exists." }
        threads[id] = ThreadRecord(id, priority, entryPoint, argument)
    }

    @Synchronized
    fun startThread(id: Int) {
        val thread = requireThread(id)
        check(thread.state == ThreadState.STOPPED) { "Only a stopped thread can be started." }
        thread.state = ThreadState.RUNNABLE
        thread.wakeSequence = ++sequence
    }

    /** Selects highest priority (lowest numeric value), FIFO among equal priorities. */
    @Synchronized
    fun dispatch(): ThreadSnapshot? {
        val current = currentThreadId?.let(threads::get)
        if (current?.state == ThreadState.RUNNING) current.state = ThreadState.RUNNABLE
        val next = threads.values
            .asSequence()
            .filter { it.state == ThreadState.RUNNABLE }
            .sortedWith(compareBy<ThreadRecord> { it.priority }.thenBy { it.wakeSequence })
            .firstOrNull()
        currentThreadId = next?.id
        if (next != null) next.state = ThreadState.RUNNING
        return next?.snapshot()
    }

    @Synchronized
    fun yieldCurrent(): ThreadSnapshot? {
        val current = currentThreadId?.let(threads::get) ?: return dispatch()
        if (current.state == ThreadState.RUNNING) {
            current.state = ThreadState.RUNNABLE
            current.wakeSequence = ++sequence
        }
        currentThreadId = null
        return dispatch()
    }

    @Synchronized
    fun terminateCurrent() {
        val id = currentThreadId ?: return
        requireThread(id).state = ThreadState.TERMINATED
        currentThreadId = null
    }

    @Synchronized
    fun createMessageQueue(id: Int, capacity: Int) {
        require(id >= 0) { "Queue id must be non-negative." }
        require(capacity > 0) { "Message queue capacity must be positive." }
        require(id !in queues) { "Message queue $id already exists." }
        queues[id] = QueueRecord(capacity)
    }

    /** Non-blocking send. Returns false when the bounded queue is full. */
    @Synchronized
    fun sendMessage(queueId: Int, message: Long): Boolean {
        val queue = requireQueue(queueId)
        if (queue.messages.size >= queue.capacity) return false
        queue.messages.addLast(message)
        wakeOneReceiver(queue)
        return true
    }

    /** Non-blocking receive. Returns null when the queue is empty. */
    @Synchronized
    fun receiveMessage(queueId: Int): Long? {
        val queue = requireQueue(queueId)
        if (queue.messages.isEmpty()) return null
        val message = queue.messages.removeFirst()
        wakeOneSender(queue)
        return message
    }

    /** Explicitly blocks/unblocks threads; actual wait-list integration is added in a later stage. */
    @Synchronized
    fun blockCurrentForEvent() {
        val id = currentThreadId ?: return
        val thread = requireThread(id)
        thread.state = ThreadState.WAITING_EVENT
        currentThreadId = null
    }

    @Synchronized
    fun postEvent(type: Int, value: Long = 0L) {
        events.addLast(Event(type, value, ++sequence))
        // Wake the oldest thread waiting for an event. Event delivery remains queued
        // for the guest-facing dispatcher to consume in a later integration stage.
        threads.values
            .filter { it.state == ThreadState.WAITING_EVENT }
            .minByOrNull { it.wakeSequence }
            ?.let {
                it.state = ThreadState.RUNNABLE
                it.wakeSequence = sequence
            }
    }

    @Synchronized
    fun pollEvent(): Event? = if (events.isEmpty()) null else events.removeFirst()

    @Synchronized
    fun currentThread(): ThreadSnapshot? = currentThreadId?.let(threads::get)?.snapshot()

    @Synchronized
    fun threadSnapshot(id: Int): ThreadSnapshot = requireThread(id).snapshot()

    @Synchronized
    fun messageQueueSnapshot(id: Int): MessageQueueSnapshot {
        val queue = requireQueue(id)
        return MessageQueueSnapshot(
            capacity = queue.capacity,
            queuedMessages = queue.messages.toList(),
            waitingSenders = queue.waitingSenders.size,
            waitingReceivers = queue.waitingReceivers.size
        )
    }

    private fun wakeOneReceiver(queue: QueueRecord) {
        while (queue.waitingReceivers.isNotEmpty()) {
            val thread = threads[queue.waitingReceivers.removeFirst()] ?: continue
            if (thread.state == ThreadState.WAITING_MESSAGE) {
                thread.state = ThreadState.RUNNABLE
                thread.wakeSequence = ++sequence
                break
            }
        }
    }

    private fun wakeOneSender(queue: QueueRecord) {
        while (queue.waitingSenders.isNotEmpty()) {
            val thread = threads[queue.waitingSenders.removeFirst()] ?: continue
            if (thread.state == ThreadState.WAITING_MESSAGE) {
                thread.state = ThreadState.RUNNABLE
                thread.wakeSequence = ++sequence
                break
            }
        }
    }

    private fun requireThread(id: Int): ThreadRecord =
        threads[id] ?: throw IllegalArgumentException("Unknown thread id $id.")

    private fun requireQueue(id: Int): QueueRecord =
        queues[id] ?: throw IllegalArgumentException("Unknown message queue id $id.")

    private fun ThreadRecord.snapshot() = ThreadSnapshot(
        id, priority, entryPoint, argument, state, wakeSequence
    )
}
