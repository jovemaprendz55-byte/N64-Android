package com.jsr.n64android

/**
 * Integrates the implemented SPECIAL integer subset with classic control flow.
 *
 * The caller supplies the instruction word fetched at the current PC. This
 * stepper does not fetch memory or implement architectural exception delivery.
 * Supported SPECIAL and immediate integer operations are sequenced with
 * exactly one delay-slot instruction.
 */
class N64HleR4300InstructionStepper(
    private val decoder: N64HleR4300InstructionDecoder = N64HleR4300InstructionDecoder(),
    private val specialExecutor: N64HleR4300SpecialExecutor = N64HleR4300SpecialExecutor(),
    private val controlPlanner: N64HleR4300ControlTransferPlanner = N64HleR4300ControlTransferPlanner(),
    private val controlSequencer: N64HleR4300ControlFlowSequencer = N64HleR4300ControlFlowSequencer(),
    private val immediateExecutor: N64HleR4300ImmediateExecutor = N64HleR4300ImmediateExecutor(),
    memorySpace: N64HleGuestAddressSpace? = null,
    private val memoryExecutor: N64HleR4300MemoryExecutor? =
        memorySpace?.let { N64HleR4300MemoryExecutor(it) }
) {
    fun hasPendingDelaySlot(): Boolean = controlSequencer.hasPendingDelaySlot()

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
