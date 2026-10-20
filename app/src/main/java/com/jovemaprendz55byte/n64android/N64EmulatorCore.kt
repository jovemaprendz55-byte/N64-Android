package com.jovemaprendz55byte.n64android

/**
 * Experimental orchestration layer for the partial R4300 core.
 *
 * It can map a normalized cartridge and execute a bounded instruction slice
 * from an explicitly supplied entry point. It does not implement PIF boot,
 * device initialization, frame timing, graphics, audio, or game compatibility.
 */
class N64EmulatorCore(
    /** Shared input state for the Android touch overlay and PIF Joybus reads. */
    val controllerPort: N64ControllerPort = N64ControllerPort()
) {
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

    internal val memoryBus = N64MemoryBus(controllerPort = controllerPort)
    private val cpu = R4300Cpu(memoryBus)
    private val rsp = RspCore(memoryBus)
    private var romLoaded = false
    private var executionStarted = false

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
        var executed = 0
        return try {
            while (executed < instructionBudget && !rsp.halted && !memoryBus.isSpHalted()) {
                val continued = rsp.step()
                // A BREAK is still an executed instruction; a thrown decode/memory
                // error is not counted, while earlier completed instructions are.
                executed++
                if (!continued) break
            }
            RspExecutionResult(executed, rsp.pc, rsp.halted)
        } catch (error: Exception) {
            RspExecutionResult(
                instructionsExecuted = executed,
                programCounter = rsp.lastInstructionAddress,
                halted = rsp.halted,
                errorMessage = error.message ?: error::class.java.simpleName
            )
        }
    }

    /** Loads a canonical Z64 big-endian cartridge image into the CPU bus. */
    fun loadRom(normalizedRom: ByteArray) {
        cpu.loadCartridgeRom(normalizedRom)
        romLoaded = true
        executionStarted = false
    }

    /**
     * Starts a persistent CPU session at an explicit address. Call [executeSlice]
     * repeatedly to continue execution without resetting registers or branch state.
     *
     * This bypasses the real PIF boot sequence and is intended for development
     * until hardware initialization and a complete boot path are implemented.
     */
    fun startExecution(entryPoint: Long) {
        require(romLoaded) { "Load a normalized ROM before execution" }
        require(entryPoint % 4L == 0L) { "Entry point must be 4-byte aligned" }
        cpu.resetExecutionState(entryPoint)
        executionStarted = true
    }

    /**
     * Starts a diagnostic-only direct entry into cartridge code with a usable
     * RDRAM stack. This is not a replacement for the PIF boot sequence.
     */
    fun startCartridgeDiagnosticExecution(entryPoint: Long) {
        require(romLoaded) { "Load a normalized ROM before execution" }
        require(entryPoint % 4L == 0L) { "Entry point must be 4-byte aligned" }
        cpu.resetForCartridgeDiagnostic(entryPoint)
        executionStarted = true
    }

    /**
     * Executes a bounded slice in the current session, preserving CPU state
     * between calls. A CPU exception is returned as a diagnostic stop result.
     */
    fun executeSlice(instructionBudget: Int): ExecutionResult {
        require(romLoaded) { "Load a normalized ROM before execution" }
        require(executionStarted) { "Call startExecution before running a slice" }
        require(instructionBudget > 0) { "Instruction budget must be positive" }

        var executed = 0
        while (executed < instructionBudget) {
            // step() advances PC before decoding/executing; preserve the address
            // of this instruction so diagnostics identify the actual failure.
            val instructionAddress = cpu.pc
            try {
                cpu.step()
                executed++
            } catch (error: Exception) {
                executionStarted = false
                return ExecutionResult(
                    instructionsExecuted = executed,
                    programCounter = instructionAddress,
                    stopReason = StopReason.EXECUTION_ERROR,
                    errorMessage = (error.message ?: error::class.java.simpleName) +
                        "\\nEstado da CPU: " + cpu.diagnosticSnapshot()
                )
            }
        }

        return ExecutionResult(
            instructionsExecuted = executed,
            programCounter = cpu.pc,
            stopReason = StopReason.INSTRUCTION_BUDGET_REACHED
        )
    }

    /** Copies the current VI framebuffer into [destination], if its format is supported. */
    fun renderVideoFrame(destination: N64VideoFrameBuffer, height: Int = destination.height): Boolean =
        memoryBus.renderVideoFrame(destination, height)

    /**
     * Runs at most [instructionBudget] instructions from [entryPoint], resetting
     * the CPU first. Prefer startExecution + executeSlice for a persistent loop.
     */
    fun executeFrom(entryPoint: Long, instructionBudget: Int): ExecutionResult {
        require(instructionBudget > 0) { "Instruction budget must be positive" }
        startExecution(entryPoint)
        return executeSlice(instructionBudget)
    }
}
