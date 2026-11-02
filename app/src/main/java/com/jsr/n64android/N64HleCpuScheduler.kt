package com.jsr.n64android

/**
 * Connects the host-side HLE thread dispatcher to architectural R4300 contexts.
 *
 * A context must be explicitly registered for every guest thread before it is
 * started. This avoids inventing a guest stack layout or pretending that the
 * current host-side thread model already implements the libultra OSThread ABI.
 * Scheduling happens only at instruction boundaries, between CPU steps.
 */
class N64HleCpuScheduler(
    private val kernel: N64HleOsKernel,
    private val cpu: R4300Cpu
) {
    private val contexts = linkedMapOf<Int, R4300Cpu.ContextSnapshot>()
    private var activeThreadId: Int? = null

    /** Registers the complete initial architectural state for a stopped thread. */
    @Synchronized
    fun registerInitialContext(threadId: Int, context: R4300Cpu.ContextSnapshot) {
        val thread = kernel.threadSnapshot(threadId)
        require(thread.state == N64HleOsKernel.ThreadState.STOPPED) {
            "An initial CPU context can only be registered for a stopped thread."
        }
        require(context.pc == thread.entryPoint) {
            "Initial CPU context PC must match the thread entry point."
        }
        require(threadId !in contexts) { "CPU context for thread $threadId is already registered." }
        contexts[threadId] = context
    }

    /** Starts a registered stopped thread without inventing guest ABI memory. */
    @Synchronized
    fun startThread(threadId: Int) {
        check(threadId in contexts) { "Register an initial R4300 context before starting thread $threadId." }
        kernel.startThread(threadId)
    }

    /**
     * Executes a bounded cooperative quantum for the active guest thread.
     * The caller regains control after [maxInstructions] or when an HLE call
     * blocks/terminates/yields the current thread. No preemption timer is implied.
     */
    @Synchronized
    fun runQuantum(maxInstructions: Int): Int {
        require(maxInstructions >= 0) { "Instruction budget must be non-negative." }
        check(activeThreadId != null) { "Dispatch a runnable thread before executing a CPU quantum." }
        var executed = 0
        while (executed < maxInstructions) {
            val current = kernel.currentThread()
            if (current == null || current.id != activeThreadId ||
                current.state != N64HleOsKernel.ThreadState.RUNNING
            ) break
            cpu.step()
            executed++
            val after = kernel.currentThread()
            if (after == null || after.id != activeThreadId ||
                after.state != N64HleOsKernel.ThreadState.RUNNING
            ) break
        }
        return executed
    }

    /** Selects the next runnable thread and restores its saved R4300 state. */
    @Synchronized
    fun dispatch(): N64HleOsKernel.ThreadSnapshot? {
        saveActiveContext()
        val next = kernel.dispatch()
        activate(next)
        return next
    }

    /** Yields the active thread and switches to the next runnable guest context. */
    @Synchronized
    fun yieldCurrent(): N64HleOsKernel.ThreadSnapshot? {
        saveActiveContext()
        val next = kernel.yieldCurrent()
        activate(next)
        return next
    }

    /** Terminates the active thread and dispatches another runnable context. */
    @Synchronized
    fun terminateCurrent(): N64HleOsKernel.ThreadSnapshot? {
        saveActiveContext()
        kernel.terminateCurrent()
        val next = kernel.dispatch()
        activate(next)
        return next
    }

    /** Blocks the active thread for an event, then activates another runnable thread. */
    @Synchronized
    fun blockCurrentForEvent(): N64HleOsKernel.ThreadSnapshot? {
        saveActiveContext()
        kernel.blockCurrentForEvent()
        val next = kernel.dispatch()
        activate(next)
        return next
    }

    /** Performs a blocking queue send and switches away if the thread was parked. */
    @Synchronized
    fun sendMessageBlocking(queueId: Int, message: Long): Boolean {
        saveActiveContext()
        val sentImmediately = kernel.sendMessageBlocking(queueId, message)
        if (!sentImmediately) activate(kernel.dispatch())
        else restoreActiveContextAfterImmediateOperation()
        return sentImmediately
    }

    /** Performs a blocking queue receive and switches away if the thread was parked. */
    @Synchronized
    fun receiveMessageBlocking(queueId: Int): Long? {
        saveActiveContext()
        val message = kernel.receiveMessageBlocking(queueId)
        if (message == null) activate(kernel.dispatch())
        else restoreActiveContextAfterImmediateOperation()
        return message
    }

    @Synchronized
    fun activeThreadId(): Int? = activeThreadId

    @Synchronized
    fun savedContext(threadId: Int): R4300Cpu.ContextSnapshot? = contexts[threadId]

    private fun saveActiveContext() {
        val id = activeThreadId ?: return
        contexts[id] = cpu.snapshotContext()
    }

    private fun activate(next: N64HleOsKernel.ThreadSnapshot?) {
        if (next == null) {
            activeThreadId = null
            return
        }
        val context = contexts[next.id]
            ?: throw IllegalStateException("No R4300 context registered for runnable thread ${next.id}.")
        cpu.restoreContext(context)
        activeThreadId = next.id
    }

    private fun restoreActiveContextAfterImmediateOperation() {
        val id = activeThreadId ?: return
        val context = contexts[id] ?: return
        // The operation did not deschedule the current thread. Restore the state
        // captured at its boundary so this path has the same explicit invariant.
        cpu.restoreContext(context)
    }
}
