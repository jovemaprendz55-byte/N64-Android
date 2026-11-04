package com.jsr.n64android

/**
 * Isolated model of the VI interrupt line latch.
 *
 * A sampled VI_CURRENT == VI_INTR match asserts the pending line.
 * Acknowledge clears only the pending line; it does not alter VI_CURRENT.
 * Sampling cadence and MI register wiring remain the caller's responsibility.
 */
internal class N64ViInterruptLatch {
    var pending: Boolean = false
        private set

    fun sampleCurrent(currentHalfLine: Int, interruptHalfLine: Int): Boolean {
        require(currentHalfLine >= 0) { "currentHalfLine must be non-negative" }
        require(interruptHalfLine >= 0) { "interruptHalfLine must be non-negative" }
        if (currentHalfLine == interruptHalfLine) {
            pending = true
        }
        return pending
    }

    fun acknowledge() {
        pending = false
    }
}
