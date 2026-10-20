package com.jovemaprendz55byte.n64android

/**
 * Experimental orchestration layer for the partial R4300 core.
 *
 * It can map a normalized cartridge and execute a bounded instruction slice
 * from an explicitly supplied entry point. It does not implement PIF boot,
 * device initialization, frame timing, graphics, audio, or game compatibility.
 */
class N64EmulatorCore {
    enum class StopReason {
        INSTRUCTION_BUDGET_REACHED,
        EXECUTION_ERROR
    }

    data class ExecutionResult(
        val instructionsExecuted: Int,
        val programCounter: Long,
        val stopReason: StopReason,
        val errorMessage: String? = null
    )

    internal val memoryBus = N64MemoryBus()
    private val cpu = R4300Cpu(memoryBus)
    private val rsp = RspCore(memoryBus)
    private var romLoaded = false

    data class RspExecutionResult(
        val instructionsExecuted: Int,
        val programCounter: Int,
        val halted: Boolean,
        val errorMessage: String? = null
    )

    /**
     * Runs a bounded diagnostic slice of the RSP from an explicitly supplied
     * IMEM offset. This shares SP memory/registers with the CPU bus, but is not
     * yet scheduled automatically by the R4300 CPU or a full RSP task system.
     */
    fun executeRspFromImem(entryPoint: Int, instructionBudget: Int): RspExecutionResult {
        require(instructionBudget > 0) { "Instruction budget must be positive" }
        rsp.start(entryPoint)
        return try {
            val executed = rsp.run(instructionBudget)
            RspExecutionResult(executed, rsp.pc, rsp.halted)
        } catch (error: Exception) {
            RspExecutionResult(
                instructionsExecuted = 0,
                programCounter = rsp.pc,
                halted = rsp.halted,
                errorMessage = error.message ?: error::class.java.simpleName
            )
        }
    }

    /** Loads a canonical Z64 big-endian cartridge image into the CPU bus. */
    fun loadRom(normalizedRom: ByteArray) {
        cpu.loadCartridgeRom(normalizedRom)
        romLoaded = true
    }

    /**
     * Runs at most [instructionBudget] instructions from [entryPoint].
     *
     * The entry point is explicit because the real N64 reset/PIF boot sequence
     * is not implemented. Errors are returned as a result instead of escaping
     * the runner, so callers can show diagnostics without crashing the UI.
     */
    fun executeFrom(entryPoint: Long, instructionBudget: Int): ExecutionResult {
        require(romLoaded) { "Load a normalized ROM before execution" }
        require(entryPoint % 4L == 0L) { "Entry point must be 4-byte aligned" }
        require(instructionBudget > 0) { "Instruction budget must be positive" }

        cpu.resetExecutionState(entryPoint)
        var executed = 0
        while (executed < instructionBudget) {
            try {
                cpu.step()
                executed++
            } catch (error: Exception) {
                return ExecutionResult(
                    instructionsExecuted = executed,
                    programCounter = cpu.pc,
                    stopReason = StopReason.EXECUTION_ERROR,
                    errorMessage = error.message ?: error::class.java.simpleName
                )
            }
        }

        return ExecutionResult(
            instructionsExecuted = executed,
            programCounter = cpu.pc,
            stopReason = StopReason.INSTRUCTION_BUDGET_REACHED
        )
    }
}
