package com.jsr.n64android

/**
 * Session-local ledger for the CPU cycle units currently modeled by R4300Cpu.
 *
 * These are deterministic interpreter estimates (currently two base cycles per
 * completed CPU step), not calibrated VR4300/RCP hardware clocks. Device clock
 * ratios must be established separately before VI or AI consumes this value.
 */
class N64CpuCycleClock {
    var totalCycles: Long = 0L
        private set

    fun advance(cycles: Int) {
        require(cycles >= 0) { "CPU cycle advance cannot be negative." }
        totalCycles = Math.addExact(totalCycles, cycles.toLong())
    }
}
