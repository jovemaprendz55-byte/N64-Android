package com.jsr.n64android

/**
 * Coordinates the explicit SP task lifecycle with the bus's synchronous DPC Full Sync event.
 *
 * The caller must report boot/task microcode transitions and task BREAK only when proven by
 * the RSP scheduler. This class deliberately does not infer a task boundary from PC or BREAK.
 */
class N64SpTaskDpCoordinator(
    private val bus: N64Bus,
    val lifecycle: N64SpTaskLifecycle = N64SpTaskLifecycle()
) {
    private var dpDrainObservedForTask = false

    init {
        bus.setDpDrainListener(::onDpDrainCompleted)
    }

    fun begin(flags: Int) {
        lifecycle.begin(flags)
        dpDrainObservedForTask = false
    }

    fun startBootMicrocode() = lifecycle.startBootMicrocode()

    fun startTaskMicrocode() = lifecycle.startTaskMicrocode()

    /** Call only when the scheduler has identified BREAK as the task microcode's completion. */
    fun onTaskBreak() {
        lifecycle.onTaskBreak()
        if (lifecycle.state == N64SpTaskLifecycle.State.WAITING_FOR_DP && dpDrainObservedForTask) {
            lifecycle.onDpDrainCompleted()
        }
    }

    private fun onDpDrainCompleted() {
        when (lifecycle.state) {
            N64SpTaskLifecycle.State.TASK_MICROCODE_RUNNING -> dpDrainObservedForTask = true
            N64SpTaskLifecycle.State.WAITING_FOR_DP -> lifecycle.onDpDrainCompleted()
            else -> Unit
        }
    }
}
