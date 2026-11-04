package com.jsr.n64android

/**
 * Isolated VI timing/interrupt state composed from the half-line counter and
 * interrupt latch. It does not model clock conversion or MI register wiring.
 */
internal class N64ViInterruptController(
    initialCurrent: Int = 0
) {
    private val counter = N64ViHalfLineCounter(initialCurrent)
    private val interruptLatch = N64ViInterruptLatch()

    val current: Int
        get() = counter.current

    val interruptPending: Boolean
        get() = interruptLatch.pending

    fun advanceScanline(viVSync: Int, viIntr: Int): Int {
        val sampledCurrent = counter.advanceScanline(viVSync)
        interruptLatch.sampleCurrent(sampledCurrent, viIntr)
        return sampledCurrent
    }

    fun acknowledgeInterrupt() {
        interruptLatch.acknowledge()
    }
}
