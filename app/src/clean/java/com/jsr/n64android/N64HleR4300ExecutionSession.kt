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

    /** Immutable diagnostic view; it never changes guest CPU/device state. */
    data class DiagnosticsSnapshot(
        val totalExecutedInstructions: Long,
        val lastSuccessfulInstructionPc: Long?,
        val lastSuccessfulInstruction: N64HleR4300DecodedInstruction?,
        val lastAttemptedInstructionPc: Long?,
        val failureType: String?,
        val failureMessage: String?
    )

    var totalExecutedInstructions: Long = 0L
        private set

    private var lastSuccessfulInstructionPc: Long? = null
    private var lastSuccessfulInstruction: N64HleR4300DecodedInstruction? = null
    private var lastAttemptedInstructionPc: Long? = null
    private var failureType: String? = null
    private var failureMessage: String? = null

    fun diagnostics(): DiagnosticsSnapshot = DiagnosticsSnapshot(
        totalExecutedInstructions = totalExecutedInstructions,
        lastSuccessfulInstructionPc = lastSuccessfulInstructionPc,
        lastSuccessfulInstruction = lastSuccessfulInstruction,
        lastAttemptedInstructionPc = lastAttemptedInstructionPc,
        failureType = failureType,
        failureMessage = failureMessage
    )

    /**
     * Executes at most [instructionBudget] sequential instructions.
     * The hard cap prevents accidental unbounded execution in callers.
     *
     * On a failed step, records the attempted PC and exception summary, then
     * rethrows the original exception. No exception is converted to success.
     */
    fun runChunk(instructionBudget: Int): ChunkResult {
        require(instructionBudget in 1..MAX_INSTRUCTIONS_PER_CHUNK) {
            "Instruction budget must be between 1 and $MAX_INSTRUCTIONS_PER_CHUNK"
        }
        var executed = 0
        var last: N64HleR4300DecodedInstruction? = null
        while (executed < instructionBudget) {
            val attemptedPc = cpuState.programCounter
            lastAttemptedInstructionPc = attemptedPc
            try {
                last = stepper.stepFromMemory(cpuState)
            } catch (error: Exception) {
                failureType = error.javaClass.name
                failureMessage = error.message
                throw error
            }
            lastSuccessfulInstructionPc = attemptedPc
            lastSuccessfulInstruction = last
            failureType = null
            failureMessage = null
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
