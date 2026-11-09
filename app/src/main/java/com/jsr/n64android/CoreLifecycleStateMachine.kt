package com.jsr.n64android

/**
 * Pure state model for serializing Core stop/shutdown requests.
 *
 * This class deliberately performs no JNI, Android, EGL, or threading work.
 * Callers must execute native STOP on a worker and GL teardown on the GL thread.
 */
internal class CoreLifecycleStateMachine {
    enum class Request {
        MANUAL_STOP,
        ACTIVITY_PAUSE,
        ACTIVITY_DESTROY,
        ROM_REPLACEMENT,
        SCREEN_CHANGE
    }

    enum class Phase {
        IDLE,
        STOP_REQUESTED,
        EXECUTE_RETURNED,
        GL_TEARDOWN_QUEUED,
        SHUTDOWN_COMPLETE,
        STOP_REJECTED,
        EXECUTE_RETURN_TIMEOUT,
        SURFACE_UNAVAILABLE,
        GL_TEARDOWN_TIMEOUT
    }

    data class Snapshot(
        val generation: Long,
        val phase: Phase,
        val request: Request?
    )

    private val lock = Any()
    private var generation = 0L
    private var phase = Phase.IDLE
    private var request: Request? = null

    /**
     * Joins an in-flight request instead of creating a second STOP operation.
     * A new generation starts only after the previous request reached a terminal state.
     */
    fun begin(request: Request): Snapshot = synchronized(lock) {
        if (isInFlight(phase)) return@synchronized snapshotLocked()
        generation += 1
        this.request = request
        phase = Phase.STOP_REQUESTED
        snapshotLocked()
    }

    fun markStopRejected(token: Long): Boolean =
        transition(token, Phase.STOP_REQUESTED, Phase.STOP_REJECTED)

    fun markExecuteReturned(token: Long): Boolean =
        transition(token, Phase.STOP_REQUESTED, Phase.EXECUTE_RETURNED)

    fun markExecuteReturnTimeout(token: Long): Boolean =
        transition(token, Phase.STOP_REQUESTED, Phase.EXECUTE_RETURN_TIMEOUT)

    fun markGlTeardownQueued(token: Long): Boolean =
        transition(token, Phase.EXECUTE_RETURNED, Phase.GL_TEARDOWN_QUEUED)

    fun markSurfaceUnavailable(token: Long): Boolean = synchronized(lock) {
        if (token != generation ||
            (phase != Phase.EXECUTE_RETURNED && phase != Phase.GL_TEARDOWN_QUEUED)
        ) return@synchronized false
        phase = Phase.SURFACE_UNAVAILABLE
        true
    }

    fun markGlTeardownTimeout(token: Long): Boolean =
        transition(token, Phase.GL_TEARDOWN_QUEUED, Phase.GL_TEARDOWN_TIMEOUT)

    fun markShutdownComplete(token: Long): Boolean =
        transition(token, Phase.GL_TEARDOWN_QUEUED, Phase.SHUTDOWN_COMPLETE)

    fun snapshot(): Snapshot = synchronized(lock) { snapshotLocked() }

    private fun transition(token: Long, expected: Phase, next: Phase): Boolean =
        synchronized(lock) {
            if (token != generation || phase != expected) return@synchronized false
            phase = next
            true
        }

    private fun snapshotLocked() = Snapshot(generation, phase, request)

    private fun isInFlight(value: Phase): Boolean = when (value) {
        Phase.STOP_REQUESTED,
        Phase.EXECUTE_RETURNED,
        Phase.GL_TEARDOWN_QUEUED,
        // A timeout or lost EGL surface is not proof that native work stopped.
        // Keep the generation latched until an explicit recovery path is added.
        Phase.EXECUTE_RETURN_TIMEOUT,
        Phase.SURFACE_UNAVAILABLE,
        Phase.GL_TEARDOWN_TIMEOUT -> true
        else -> false
    }
}
