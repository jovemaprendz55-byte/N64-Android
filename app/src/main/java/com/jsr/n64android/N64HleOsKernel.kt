package com.jsr.n64android

/**
 * Host-side first-stage model of libultra-style OS scheduling primitives.
 *
 * This remains a host model, not guest ABI interception: it does not read/write
 * guest OSThread/OSMesgQueue structures or switch R4300 register contexts.
 */
class N64HleOsKernel {
    enum class ThreadState { STOPPED, RUNNABLE, RUNNING, WAITING_MESSAGE, WAITING_EVENT, TERMINATED }
    private enum class MessageWaitKind { SEND, RECEIVE }

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
        var wakeSequence: Long = 0L,
        var waitingQueueId: Int? = null,
        var messageWaitKind: MessageWaitKind? = null,
        var pendingMessage: Long? = null
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
        require(entryPoint != 0L && entryPoint and 3L == 0L) {
            "Thread entry point must be nonzero and 4-byte aligned."
        }
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

    /** Non-blocking send. Does not bypass senders already waiting for queue space. */
    @Synchronized
    fun sendMessage(queueId: Int, message: Long): Boolean {
        val queue = requireQueue(queueId)
        if (queue.messages.size >= queue.capacity || queue.waitingSenders.isNotEmpty()) return false
        queue.messages.addLast(message)
        wakeOneReceiver(queueId, queue)
        return true
    }

    /** Non-blocking receive. Frees a slot for the oldest blocked sender. */
    @Synchronized
    fun receiveMessage(queueId: Int): Long? {
        val queue = requireQueue(queueId)
        if (queue.messages.isEmpty()) return null
        val message = queue.messages.removeFirst()
        wakeOneSender(queueId, queue)
        return message
    }

    /**
     * Sends immediately when possible. If full, parks the current thread and
     * retains its message until a receiver frees a queue slot. Returns false
     * when the calling thread was blocked.
     */
    @Synchronized
    fun sendMessageBlocking(queueId: Int, message: Long): Boolean {
        val queue = requireQueue(queueId)
        if (queue.messages.size < queue.capacity && queue.waitingSenders.isEmpty()) {
            queue.messages.addLast(message)
            wakeOneReceiver(queueId, queue)
            return true
        }
        val thread = requireRunningThread()
        thread.state = ThreadState.WAITING_MESSAGE
        thread.waitingQueueId = queueId
        thread.messageWaitKind = MessageWaitKind.SEND
        thread.pendingMessage = message
        queue.waitingSenders.addLast(thread.id)
        currentThreadId = null
        return false
    }

    /**
     * Receives immediately when possible. If empty, parks the current thread
     * until a sender posts a message. Returns null when the calling thread
     * was blocked.
     */
    @Synchronized
    fun receiveMessageBlocking(queueId: Int): Long? {
        val queue = requireQueue(queueId)
        if (queue.messages.isNotEmpty()) {
            val message = queue.messages.removeFirst()
            wakeOneSender(queueId, queue)
            return message
        }
        val thread = requireRunningThread()
        thread.state = ThreadState.WAITING_MESSAGE
        thread.waitingQueueId = queueId
        thread.messageWaitKind = MessageWaitKind.RECEIVE
        queue.waitingReceivers.addLast(thread.id)
        currentThreadId = null
        return null
    }

    @Synchronized
    fun blockCurrentForEvent() {
        val id = currentThreadId ?: return
        val thread = requireThread(id)
        check(thread.state == ThreadState.RUNNING) { "Only a running thread can wait for an event." }
        thread.state = ThreadState.WAITING_EVENT
        currentThreadId = null
    }

    @Synchronized
    fun postEvent(type: Int, value: Long = 0L) {
        events.addLast(Event(type, value, ++sequence))
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

    private fun wakeOneReceiver(queueId: Int, queue: QueueRecord) {
        while (queue.waitingReceivers.isNotEmpty()) {
            val thread = threads[queue.waitingReceivers.removeFirst()] ?: continue
            if (thread.state == ThreadState.WAITING_MESSAGE &&
                thread.waitingQueueId == queueId &&
                thread.messageWaitKind == MessageWaitKind.RECEIVE
            ) {
                clearMessageWait(thread)
                makeRunnable(thread)
                break
            }
        }
    }

    private fun wakeOneSender(queueId: Int, queue: QueueRecord) {
        while (queue.waitingSenders.isNotEmpty() && queue.messages.size < queue.capacity) {
            val thread = threads[queue.waitingSenders.removeFirst()] ?: continue
            if (thread.state == ThreadState.WAITING_MESSAGE &&
                thread.waitingQueueId == queueId &&
                thread.messageWaitKind == MessageWaitKind.SEND
            ) {
                val message = thread.pendingMessage ?: continue
                queue.messages.addLast(message)
                clearMessageWait(thread)
                makeRunnable(thread)
                break
            }
        }
    }

    private fun clearMessageWait(thread: ThreadRecord) {
        thread.waitingQueueId = null
        thread.messageWaitKind = null
        thread.pendingMessage = null
    }

    private fun makeRunnable(thread: ThreadRecord) {
        thread.state = ThreadState.RUNNABLE
        thread.wakeSequence = ++sequence
    }

    private fun requireRunningThread(): ThreadRecord {
        val id = checkNotNull(currentThreadId) { "A blocking message operation requires a running thread." }
        val thread = requireThread(id)
        check(thread.state == ThreadState.RUNNING) { "A blocking message operation requires a running thread." }
        return thread
    }

    private fun requireThread(id: Int): ThreadRecord =
        threads[id] ?: throw IllegalArgumentException("Unknown thread id $id.")

    private fun requireQueue(id: Int): QueueRecord =
        queues[id] ?: throw IllegalArgumentException("Unknown message queue id $id.")

    private fun ThreadRecord.snapshot() = ThreadSnapshot(
        id, priority, entryPoint, argument, state, wakeSequence
    )
}
