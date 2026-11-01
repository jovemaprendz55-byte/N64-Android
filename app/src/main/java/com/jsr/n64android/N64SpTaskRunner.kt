package com.jsr.n64android

/**
 * First hardware-facing SP task execution bridge.
 *
 * It performs the existing OSTask bootstrap, creates the RSP against the same
 * bus, and executes a bounded instruction budget. The boot microcode remains
 * responsible for loading/entering task microcode; this bridge does not guess
 * that transition or claim that an arbitrary BREAK is a completed graphics task.
 *
 * DMA timing, SP interrupts, and DP_WAIT completion are not modeled here.
 */
class N64SpTaskRunner(
    private val bus: N64Bus,
    rdramSize: Int
) {
    private val bootstrap = N64SpTaskBootstrap(bus, rdramSize)

    data class Result(
        val bootstrap: N64SpTaskBootstrap.Result,
        val executedInstructions: Int,
        val programCounter: Int,
        val lastInstruction: Int,
        val spStatus: Int,
        val halted: Boolean,
        val broke: Boolean,
        val instructionBudgetExhausted: Boolean
    )

    fun startAndRun(taskAddress: Int, maxInstructions: Int): Result {
        require(maxInstructions > 0) { "RSP instruction budget must be positive." }
        val boot = bootstrap.startFromRdram(taskAddress)
        val rsp = N64RspCore(bus)
        val executed = rsp.run(maxInstructions)
        val status = bus.read32(N64Bus.SP_STATUS_REG)
        val halted = (status and N64Bus.SP_STATUS_HALT) != 0
        val broke = (status and N64Bus.SP_STATUS_BROKE) != 0
        return Result(
            bootstrap = boot,
            executedInstructions = executed,
            programCounter = rsp.pc,
            lastInstruction = rsp.lastInstruction,
            spStatus = status,
            halted = halted,
            broke = broke,
            instructionBudgetExhausted = !halted && executed == maxInstructions
        )
    }
}
