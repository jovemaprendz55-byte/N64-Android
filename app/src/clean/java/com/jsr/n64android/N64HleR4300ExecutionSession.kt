package com.jsr.n64android

/**
 * Explicit, bounded driver for the clean R4300 stepper.
 *
 * This class never starts a background loop. The caller chooses a finite
 * instruction budget for each runChunk() call and observes the returned
 * architectural snapshot. Unsupported instructions and memory faults remain
 * visible to the caller as exceptions.
 */
class N64HleR4300ExecutionSession(
    val cpuState: N64HleR4300CpuState,
    private val stepper: N64HleR4300InstructionStepper
) {
    data class ChunkResult(
        val executedInstructions: Int,
        val totalExecutedInstructions: Long,
        val programCounter: Long,
        val hasPendingDelaySlot: Boolean,
        val lastInstruction: N64HleR4300DecodedInstruction?
    )

    var totalExecutedInstructions: Long = 0L
        private set

    /**
     * Executes at most [instructionBudget] sequential instructions.
     * The hard cap prevents accidental unbounded execution in callers.
     */
    fun runChunk(instructionBudget: Int): ChunkResult {
        require(instructionBudget in 1..MAX_INSTRUCTIONS_PER_CHUNK) {
            "Instruction budget must be between 1 and $MAX_INSTRUCTIONS_PER_CHUNK"
        }
        var executed = 0
        var last: N64HleR4300DecodedInstruction? = null
        while (executed < instructionBudget) {
            last = stepper.stepFromMemory(cpuState)
            executed++
            totalExecutedInstructions++
        }
        return ChunkResult(
            executedInstructions = executed,
            totalExecutedInstructions = totalExecutedInstructions,
            programCounter = cpuState.programCounter,
            hasPendingDelaySlot = stepper.hasPendingDelaySlot(),
            lastInstruction = last
        )
    }

    companion object {
        const val MAX_INSTRUCTIONS_PER_CHUNK = 4096
    }
}
