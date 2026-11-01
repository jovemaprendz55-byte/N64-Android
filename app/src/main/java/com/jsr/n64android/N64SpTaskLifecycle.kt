package com.jsr.n64android

/**
 * Deterministic state model for one SP task.
 *
 * This class does not perform DMA, run the RSP, or drive MI/DPC registers.
 * The hardware-facing scheduler must call these transitions only after the
 * corresponding operation has actually completed.
 */
class N64SpTaskLifecycle {
    enum class State {
        IDLE,
        PREPARING,
        BOOT_MICROCODE_RUNNING,
        TASK_MICROCODE_RUNNING,
        WAITING_FOR_DP,
        COMPLETED,
        FAILED
    }

    var state: State = State.IDLE
        private set
    var failureReason: String? = null
        private set
    var taskFlags: Int = 0
        private set

    val requiresDpWait: Boolean
        get() = (taskFlags and OS_TASK_DP_WAIT) != 0

    fun begin(flags: Int) {
        check(state == State.IDLE || state == State.COMPLETED || state == State.FAILED) {
            "Cannot begin an SP task while lifecycle is $state."
        }
        taskFlags = flags
        failureReason = null
        state = State.PREPARING
    }

    /** Called after descriptor/boot microcode transfers and SP PC setup succeed. */
    fun startBootMicrocode() {
        check(state == State.PREPARING) { "Boot microcode can start only after preparation." }
        state = State.BOOT_MICROCODE_RUNNING
    }

    /** Called when execution has entered the task microcode, not merely when PC is nonzero. */
    fun startTaskMicrocode() {
        check(state == State.BOOT_MICROCODE_RUNNING) {
            "Task microcode can start only after boot microcode."
        }
        state = State.TASK_MICROCODE_RUNNING
    }

    /** Called after the task microcode reaches its architectural completion point. */
    fun onTaskBreak() {
        check(state == State.TASK_MICROCODE_RUNNING) {
            "Task BREAK is valid only while task microcode is running."
        }
        state = if (requiresDpWait) State.WAITING_FOR_DP else State.COMPLETED
    }

    /** Called only after the DPC/RDP command stream has actually drained. */
    fun onDpDrainCompleted() {
        check(state == State.WAITING_FOR_DP) { "No SP task is waiting for the DP." }
        state = State.COMPLETED
    }

    fun fail(reason: String) {
        require(reason.isNotBlank()) { "Failure reason cannot be blank." }
        check(state != State.IDLE && state != State.COMPLETED) {
            "Cannot fail an SP task while lifecycle is $state."
        }
        failureReason = reason
        state = State.FAILED
    }

    fun reset() {
        state = State.IDLE
        failureReason = null
        taskFlags = 0
    }

    companion object {
        /** OSTask flag defined by the N64 SDK sptask.h ABI. */
        const val OS_TASK_DP_WAIT = 0x0002
    }
}
