package com.jsr.n64android

/**
 * Bounded driver for RSP microcode execution.
 *
 * The host controls how much work is performed per call. BREAK halts the
 * session cleanly; unsupported instructions and memory faults are recorded
 * and rethrown so callers never mistake a failed microcode step for success.
 */
class N64HleRspExecutionSession(
    val cpuState: N64HleRspCpuState,
    private val stepper: N64HleRspInstructionStepper
) {
    enum class StopReason { BUDGET_EXHAUSTED, HALTED }

    data class ChunkResult(
        val executedInstructions: Int,
        val totalExecutedInstructions: Long,
        val programCounter: Int,
        val halted: Boolean,
        val stopReason: StopReason,
        val lastInstruction: Int?
    )

    data class DiagnosticsSnapshot(
        val totalExecutedInstructions: Long,
        val lastSuccessfulInstructionPc: Int?,
        val lastSuccessfulInstruction: Int?,
        val lastAttemptedInstructionPc: Int?,
        val failureType: String?,
        val failureMessage: String?
    )

    var totalExecutedInstructions: Long = 0L
        private set

    private var lastSuccessfulInstructionPc: Int? = null
    private var lastSuccessfulInstruction: Int? = null
    private var lastAttemptedInstructionPc: Int? = null
    private var failureType: String? = null
    private var failureMessage: String? = null

    fun diagnostics() = DiagnosticsSnapshot(
        totalExecutedInstructions,
        lastSuccessfulInstructionPc,
        lastSuccessfulInstruction,
        lastAttemptedInstructionPc,
        failureType,
        failureMessage
    )

    fun runChunk(instructionBudget: Int): ChunkResult {
        require(instructionBudget in 1..MAX_INSTRUCTIONS_PER_CHUNK) {
            "Instruction budget must be between 1 and $MAX_INSTRUCTIONS_PER_CHUNK"
        }
        var executed = 0
        var last: Int? = null
        while (executed < instructionBudget && !cpuState.halted) {
            val attemptedPc = cpuState.programCounter
            lastAttemptedInstructionPc = attemptedPc
            try {
                last = stepper.step(cpuState)
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
        val halted = cpuState.halted
        return ChunkResult(
            executedInstructions = executed,
            totalExecutedInstructions = totalExecutedInstructions,
            programCounter = cpuState.programCounter,
            halted = halted,
            stopReason = if (halted) StopReason.HALTED else StopReason.BUDGET_EXHAUSTED,
            lastInstruction = last
        )
    }

    companion object {
        const val MAX_INSTRUCTIONS_PER_CHUNK = 4096
    }
}
