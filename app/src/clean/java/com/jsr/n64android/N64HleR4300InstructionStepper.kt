package com.jsr.n64android

/**
 * Integrates the implemented SPECIAL integer subset with classic control flow.
 *
 * Instructions may be supplied directly with step(), or fetched from an
 * explicitly configured guest RDRAM space with stepFromMemory(). Architectural
 * exception delivery is not implemented. Supported SPECIAL, immediate and
 * aligned RDRAM load/store operations share one delay-slot sequencing path.
 */
class N64HleR4300InstructionStepper(
    private val decoder: N64HleR4300InstructionDecoder = N64HleR4300InstructionDecoder(),
    private val specialExecutor: N64HleR4300SpecialExecutor = N64HleR4300SpecialExecutor(),
    private val controlPlanner: N64HleR4300ControlTransferPlanner = N64HleR4300ControlTransferPlanner(),
    private val controlSequencer: N64HleR4300ControlFlowSequencer = N64HleR4300ControlFlowSequencer(),
    private val immediateExecutor: N64HleR4300ImmediateExecutor = N64HleR4300ImmediateExecutor(),
    private val memorySpace: N64HleGuestAddressSpace? = null,
    private val memoryExecutor: N64HleR4300MemoryExecutor? =
        memorySpace?.let { N64HleR4300MemoryExecutor(it) }
) {
    fun hasPendingDelaySlot(): Boolean = controlSequencer.hasPendingDelaySlot()

    /**
     * Fetches one big-endian instruction word from the configured guest RDRAM
     * at the current PC, then executes it through the same validated step path.
     */
    fun stepFromMemory(state: N64HleR4300CpuState): N64HleR4300DecodedInstruction {
        val guestMemory = checkNotNull(memorySpace) {
            "Instruction fetch requires an explicitly configured RDRAM address space"
        }
        val instructionWord = guestMemory.read32(state.programCounter).toInt()
        return step(instructionWord, state)
    }

    /**
     * Executes one supplied instruction word.
     *
     * Returns the decoded instruction. Unsupported instructions fail without
     * advancing PC. Delay-slot control transfers are rejected because the
     * R4300's corresponding exception/BD/EPC behavior is not yet modeled.
     */
    fun step(instructionWord: Int, state: N64HleR4300CpuState): N64HleR4300DecodedInstruction {
        val instruction = decoder.decode(instructionWord)

        if (controlSequencer.hasPendingDelaySlot()) {
            check(!isControlTransfer(instruction)) {
                "Control transfer in a delay slot requires exception handling, which is not implemented"
            }
            executeNonControl(instruction, state)
            controlSequencer.completeDelaySlot(state)
            return instruction
        }

        if (isControlTransfer(instruction)) {
            val plan = controlPlanner.plan(instruction, state)
            controlSequencer.schedule(state, plan)
            return instruction
        }

        executeNonControl(instruction, state)
        state.setProgramCounter(state.programCounter + 4L)
        return instruction
    }

    private fun executeNonControl(
        instruction: N64HleR4300DecodedInstruction,
        state: N64HleR4300CpuState
    ) {
        when {
            instruction.isSpecial -> specialExecutor.execute(instruction, state)
            immediateExecutor.supports(instruction.opcode) -> immediateExecutor.execute(instruction, state)
            memoryExecutor?.supports(instruction.opcode) == true -> memoryExecutor.execute(instruction, state)
            else -> immediateExecutor.execute(instruction, state)
        }
    }

    private fun isControlTransfer(instruction: N64HleR4300DecodedInstruction): Boolean {
        if (instruction.isSpecial) {
            return instruction.specialOperation == N64HleR4300SpecialOperation.JR ||
                instruction.specialOperation == N64HleR4300SpecialOperation.JALR
        }
        return instruction.opcode in setOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07)
    }
}
