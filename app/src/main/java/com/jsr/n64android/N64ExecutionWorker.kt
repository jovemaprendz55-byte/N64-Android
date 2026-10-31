package com.jsr.n64android

/**
 * Owns the worker thread for one bounded execution session.
 *
 * Cancellation is cooperative and takes effect between CPU instructions.
 * Completion is reported on the worker thread; Android callers must marshal UI
 * updates to the main thread themselves.
 */
class N64ExecutionWorker(
    private val session: N64ExecutionSession,
    private val onComplete: (N64ExecutionSession.Snapshot) -> Unit
) {
    private val lock = Any()
    private var workerThread: Thread? = null

    fun start(): Boolean = synchronized(lock) {
        if (workerThread != null) return@synchronized false

        val thread = Thread({
            try {
                session.execute()
            } catch (_: Throwable) {
                // The session preserves FAILED and its failure message in its snapshot.
            }
            onComplete(session.snapshot())
        }, "N64-CPU-Worker")

        workerThread = thread
        thread.start()
        true
    }

    fun requestStop() {
        session.requestStop()
    }

    fun isAlive(): Boolean = synchronized(lock) {
        workerThread?.isAlive == true
    }
}
