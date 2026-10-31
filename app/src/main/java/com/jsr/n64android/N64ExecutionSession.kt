package com.jsr.n64android

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Bounded, single-use instruction runner for a future continuous emulation session.
 *
 * The caller owns the worker thread and supplies one CPU instruction as [stepInstruction].
 * This class deliberately has no Android dependencies and does not implement hardware timing,
 * frame pacing, audio, or device scheduling.
 */
interface N64ExecutionController {
    fun execute(): N64ExecutionSession.Snapshot
    fun requestStop()
    fun snapshot(): N64ExecutionSession.Snapshot
}

class N64ExecutionSession(
    private val instructionLimit: Long,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
    private val afterInstruction: (() -> Boolean)? = null,
    private val stepInstruction: () -> Unit
) : N64ExecutionController {
    enum class State {
        READY,
        RUNNING,
        STOP_REQUESTED,
        COMPLETED,
        CANCELLED,
        FAILED
    }

    data class Snapshot(
        val state: State,
        val executedInstructions: Long,
        val failureMessage: String? = null
    )

    private val stopRequested = AtomicBoolean(false)
    private val executed = AtomicLong(0L)
    private val state = AtomicReference(State.READY)
    @Volatile private var failureMessage: String? = null

    init {
        require(instructionLimit > 0L) { "Instruction limit must be positive." }
        require(batchSize > 0) { "Batch size must be positive." }
    }

    /** Requests cooperative cancellation; the current instruction, if any, may finish first. */
    override fun requestStop() {
        stopRequested.set(true)
        state.compareAndSet(State.RUNNING, State.STOP_REQUESTED)
    }

    /**
     * Executes at most [instructionLimit] instructions, yielding between bounded batches.
     * This method is single-use and should be called from a dedicated worker thread.
     */
    override fun execute(): Snapshot {
        check(state.compareAndSet(State.READY, State.RUNNING)) {
            "Execution session can only be executed once."
        }

        try {
            while (!stopRequested.get() && executed.get() < instructionLimit) {
                var inBatch = 0
                while (
                    inBatch < batchSize &&
                    !stopRequested.get() &&
                    executed.get() < instructionLimit
                ) {
                    stepInstruction()
                    executed.incrementAndGet()
                    inBatch++
                    if (afterInstruction?.invoke() == true) requestStop()
                }
                if (!stopRequested.get() && executed.get() < instructionLimit) {
                    Thread.yield()
                }
            }

            state.set(if (stopRequested.get()) State.CANCELLED else State.COMPLETED)
        } catch (error: Throwable) {
            failureMessage = "${error.javaClass.simpleName}: ${error.message ?: "no details"}"
            state.set(State.FAILED)
            throw error
        }

        return snapshot()
    }

    override fun snapshot(): Snapshot = Snapshot(
        state = state.get(),
        executedInstructions = executed.get(),
        failureMessage = failureMessage
    )

    companion object {
        const val DEFAULT_BATCH_SIZE = 2_048
    }
}
