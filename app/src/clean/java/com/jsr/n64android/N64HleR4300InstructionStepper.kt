package com.jsr.n64android

/**
 * Integrates the implemented SPECIAL integer subset with classic control flow.
 *
 * Instructions may be supplied directly with step(), or fetched from an
 * explicitly configured guest RDRAM space with stepFromMemory(). Typed data-memory
 * faults enter COP0, redirect PC to the BEV-selected general vector, and are
 * signaled to the host. Supported instruction subsets share one delay-slot path.
 */
class N64HleR4300InstructionStepper(
    private val decoder: N64HleR4300InstructionDecoder = N64HleR4300InstructionDecoder(),
    private val specialExecutor: N64HleR4300SpecialExecutor = N64HleR4300SpecialExecutor(),
    private val controlPlanner: N64HleR4300ControlTransferPlanner = N64HleR4300ControlTransferPlanner(),
    private val controlSequencer: N64HleR4300ControlFlowSequencer = N64HleR4300ControlFlowSequencer(),
    private val immediateExecutor: N64HleR4300ImmediateExecutor = N64HleR4300ImmediateExecutor(),
    private val memorySpace: N64HleGuestAddressSpace? = null,
    private val memoryExecutor: N64HleR4300MemoryExecutor? =
        memorySpace?.let { N64HleR4300MemoryExecutor(it) },
    private val cop0Executor: N64HleR4300Cop0Executor = N64HleR4300Cop0Executor(),
    private val memoryExceptionClassifier: N64HleR4300MemoryExceptionClassifier =
        N64HleR4300MemoryExceptionClassifier(),
    private val exceptionRegisterUpdater: N64HleR4300ExceptionRegisterUpdater =
        N64HleR4300ExceptionRegisterUpdater(),
    private val exceptionVectorResolver: N64HleR4300ExceptionVectorResolver =
        N64HleR4300ExceptionVectorResolver(),
    private val exceptionInstructionClassifier: N64HleR4300ExceptionInstructionClassifier =
        N64HleR4300ExceptionInstructionClassifier()
) {
    fun hasPendingDelaySlot(): Boolean = controlSequencer.hasPendingDelaySlot()

    /**
     * Non-throwing host API. Architectural exceptions are returned as outcomes;
     * unsupported instructions are reported without advancing PC or completing
     * a pending delay slot. The legacy step() API remains available to callers
     * that intentionally use exceptions for control flow.
     */
    fun stepWithResult(
        instructionWord: Int,
        state: N64HleR4300CpuState
    ): N64HleR4300StepResult = try {
        N64HleR4300StepResult.Executed(step(instructionWord, state))
    } catch (signal: N64HleR4300ArchitecturalException) {
        if (signal.context.code == N64HleR4300ExceptionCode.RESERVED_INSTRUCTION) {
            N64HleR4300StepResult.ReservedEncoding(signal)
        } else {
            N64HleR4300StepResult.ArchitecturalException(signal)
        }
    } catch (unsupported: N64HleR4300UnsupportedInstructionException) {
        when (unsupported.classification) {
            N64HleR4300UnsupportedInstructionClass.VALID_BUT_NOT_IMPLEMENTED ->
                N64HleR4300StepResult.ValidButNotImplemented(unsupported.instruction, unsupported.message.orEmpty())
            N64HleR4300UnsupportedInstructionClass.UNCLASSIFIED_ENCODING ->
                N64HleR4300StepResult.UnclassifiedEncoding(unsupported.instruction, unsupported.message.orEmpty())
        }
    }

    /** Fetches from guest memory and returns the same typed outcomes as stepWithResult(). */
    fun stepFromMemoryWithResult(state: N64HleR4300CpuState): N64HleR4300StepResult = try {
        N64HleR4300StepResult.Executed(stepFromMemory(state))
    } catch (signal: N64HleR4300ArchitecturalException) {
        if (signal.context.code == N64HleR4300ExceptionCode.RESERVED_INSTRUCTION) {
            N64HleR4300StepResult.ReservedEncoding(signal)
        } else {
            N64HleR4300StepResult.ArchitecturalException(signal)
        }
    } catch (unsupported: N64HleR4300UnsupportedInstructionException) {
        when (unsupported.classification) {
            N64HleR4300UnsupportedInstructionClass.VALID_BUT_NOT_IMPLEMENTED ->
                N64HleR4300StepResult.ValidButNotImplemented(unsupported.instruction, unsupported.message.orEmpty())
            N64HleR4300UnsupportedInstructionClass.UNCLASSIFIED_ENCODING ->
                N64HleR4300StepResult.UnclassifiedEncoding(unsupported.instruction, unsupported.message.orEmpty())
        }
    }

    /**
     * Fetches one big-endian instruction word from the configured guest RDRAM
     * at the current PC, then executes it through the same validated step path.
     */
    fun stepFromMemory(state: N64HleR4300CpuState): N64HleR4300DecodedInstruction {
        val guestMemory = checkNotNull(memorySpace) {
            "Instruction fetch requires an explicitly configured RDRAM address space"
        }
        val instructionWord = try {
            guestMemory.read32(state.programCounter).toInt()
        } catch (fault: N64HleGuestMemoryFault) {
            enterInstructionFetchException(fault, state)
        }
        return step(instructionWord, state)
    }

    /**
     * Executes one supplied instruction word.
     *
     * Returns the decoded instruction on success. Typed data-memory faults
     * update COP0, redirect PC to the general exception vector, and throw
     * N64HleR4300ArchitecturalException. Delay-slot control transfers remain unsupported.
     */
    fun step(instructionWord: Int, state: N64HleR4300CpuState): N64HleR4300DecodedInstruction {
        val instruction = decoder.decode(instructionWord)

        // Classify architecturally exception-causing SPECIAL encodings before
        // executor dispatch (RI, SYSCALL and BREAK). Unimplemented valid
        // instructions remain distinct and are not mislabeled as RI.
        exceptionInstructionClassifier.classify(instruction, state)?.let { code ->
            enterSimpleArchitecturalException(code, state)
        }

        if (controlSequencer.hasPendingDelaySlot()) {
            check(!isControlTransfer(instruction)) {
                "Control transfer in a delay slot requires exception handling, which is not implemented"
            }
            executeNonControlWithExceptionEntry(instruction, state)
            controlSequencer.completeDelaySlot(state)
            return instruction
        }

        if (isControlTransfer(instruction)) {
            val plan = controlPlanner.plan(instruction, state)
            controlSequencer.schedule(state, plan)
            return instruction
        }

        executeNonControlWithExceptionEntry(instruction, state)
        state.setProgramCounter(state.programCounter + 4L)
        return instruction
    }


    private fun enterSimpleArchitecturalException(
        code: N64HleR4300ExceptionCode,
        state: N64HleR4300CpuState,
        cause: Throwable = IllegalStateException("Reserved instruction")
    ): Nothing {
        val inDelaySlot = controlSequencer.hasPendingDelaySlot()
        val context = N64HleR4300ExceptionContext(
            code = code,
            faultingPc = state.programCounter,
            inDelaySlot = inDelaySlot,
            branchPc = if (inDelaySlot) controlSequencer.pendingBranchAddress() else null
        )
        val status = state.readCop0(COP0_STATUS)
        val vectorAddress = exceptionVectorResolver.resolveGeneralVector(status)
        val update = exceptionRegisterUpdater.apply(context, state)
        if (inDelaySlot) controlSequencer.cancelPendingDelaySlot()
        state.setProgramCounter(vectorAddress)
        throw N64HleR4300ArchitecturalException(
            context,
            update,
            vectorAddress,
            cause
        )
    }

    /**
     * Instruction fetch has its own architectural classification: an
     * unaligned PC raises AdEL, while an unmapped instruction address raises IBE.
     * It is intentionally not represented as a data LOAD fault.
     */
    private fun enterInstructionFetchException(
        fault: N64HleGuestMemoryFault,
        state: N64HleR4300CpuState
    ): Nothing {
        val code = when (fault) {
            is N64HleGuestAddressAlignmentFault -> N64HleR4300ExceptionCode.ADDRESS_LOAD
            is N64HleGuestAddressMappingFault -> N64HleR4300ExceptionCode.INSTRUCTION_BUS_ERROR
        }
        val inDelaySlot = controlSequencer.hasPendingDelaySlot()
        val context = N64HleR4300ExceptionContext(
            code = code,
            faultingPc = state.programCounter,
            inDelaySlot = inDelaySlot,
            branchPc = if (inDelaySlot) controlSequencer.pendingBranchAddress() else null,
            badVirtualAddress = fault.virtualAddress
        )
        val status = state.readCop0(COP0_STATUS)
        val vectorAddress = exceptionVectorResolver.resolveGeneralVector(status)
        val update = exceptionRegisterUpdater.apply(context, state)
        if (inDelaySlot) controlSequencer.cancelPendingDelaySlot()
        state.setProgramCounter(vectorAddress)
        throw N64HleR4300ArchitecturalException(context, update, vectorAddress, fault)
    }

    /**
     * Enters COP0 for typed data-memory faults only. Unknown required COP0
     * inputs fail before mutation. The general vector is resolved from Status.BEV;
     * PC redirection happens only after COP0 update succeeds.
     */
    private fun executeNonControlWithExceptionEntry(
        instruction: N64HleR4300DecodedInstruction,
        state: N64HleR4300CpuState
    ) {
        try {
            executeNonControl(instruction, state)
        } catch (fault: N64HleR4300MemoryAccessFault) {
            val classification = memoryExceptionClassifier.classify(fault)
            val inDelaySlot = controlSequencer.hasPendingDelaySlot()
            val context = N64HleR4300ExceptionContext(
                code = classification.code,
                faultingPc = state.programCounter,
                inDelaySlot = inDelaySlot,
                branchPc = if (inDelaySlot) controlSequencer.pendingBranchAddress() else null,
                badVirtualAddress = classification.badVirtualAddress
            )
            // Read Status before mutation: it is both required by the updater and
            // the source of BEV. Unknown Status must leave CPU and delay metadata intact.
            val status = state.readCop0(COP0_STATUS)
            val vectorAddress = exceptionVectorResolver.resolveGeneralVector(status)
            val update = exceptionRegisterUpdater.apply(context, state)
            if (inDelaySlot) controlSequencer.cancelPendingDelaySlot()
            state.setProgramCounter(vectorAddress)
            throw N64HleR4300ArchitecturalException(context, update, vectorAddress, fault)
        } catch (overflow: N64HleR4300ArithmeticOverflow) {
            enterSimpleArchitecturalException(
                N64HleR4300ExceptionCode.ARITHMETIC_OVERFLOW,
                state,
                overflow
            )
        }
    }

    private fun executeNonControl(
        instruction: N64HleR4300DecodedInstruction,
        state: N64HleR4300CpuState
    ) {
        // A REGIMM trap-immediate whose condition is false completes normally
        // without writing a register; the classifier already handled true traps.
        if (instruction.opcode == 0x01 && instruction.rt in REGIMM_TRAP_IMMEDIATE_RT) return

        when {
            instruction.isSpecial -> {
                if (specialExecutor.supports(instruction)) {
                    specialExecutor.execute(instruction, state)
                } else {
                    throw unsupportedInstruction(instruction, instruction.specialOperation != null)
                }
            }
            instruction.opcode == OP_COP0 -> {
                if (cop0Executor.supports(instruction)) {
                    cop0Executor.execute(instruction, state)
                } else {
                    val knownValidCop0 = instruction.rs in COP0_VALID_UNIMPLEMENTED_RS ||
                        (instruction.rs == COP0_RS_CO && instruction.functionCode in COP0_VALID_UNIMPLEMENTED_CO)
                    throw unsupportedInstruction(instruction, knownValidCop0)
                }
            }
            immediateExecutor.supports(instruction.opcode) ->
                immediateExecutor.execute(instruction, state)
            memoryExecutor?.supports(instruction.opcode) == true ->
                memoryExecutor.execute(instruction, state)
            instruction.opcode in KNOWN_MEMORY_OPCODES ->
                throw unsupportedInstruction(instruction, valid = true)
            instruction.opcode in COP1_MEMORY_OPCODES ->
                throw unsupportedInstruction(instruction, valid = true)
            else -> throw unsupportedInstruction(instruction, valid = false)
        }
    }

    private companion object {
        const val COP0_STATUS = 12
        const val OP_COP0 = 0x10
        const val COP0_RS_CO = 0x10
        val COP0_VALID_UNIMPLEMENTED_RS = setOf(0x01, 0x05)
        val COP0_VALID_UNIMPLEMENTED_CO = setOf(0x01, 0x02, 0x06, 0x08, 0x18, 0x20)
        val COP1_MEMORY_OPCODES = setOf(0x31, 0x39)
        // Integer memory forms already defined by this clean R4300 subset.
        // If no memory bus is injected, they are valid but cannot be executed.
        val KNOWN_MEMORY_OPCODES = setOf(
            0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27,
            0x28, 0x29, 0x2A, 0x2B, 0x2C, 0x2D, 0x2E,
            0x1A, 0x1B, 0x37, 0x3F
        )
        val REGIMM_TRAP_IMMEDIATE_RT = setOf(0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0E)
        val REGIMM_BRANCH_RT = setOf(0x00, 0x01, 0x10, 0x11)
    }


    private fun unsupportedInstruction(
        instruction: N64HleR4300DecodedInstruction,
        valid: Boolean
    ): N64HleR4300UnsupportedInstructionException =
        N64HleR4300UnsupportedInstructionException(
            instruction,
            if (valid) N64HleR4300UnsupportedInstructionClass.VALID_BUT_NOT_IMPLEMENTED
            else N64HleR4300UnsupportedInstructionClass.UNCLASSIFIED_ENCODING
        )

    private fun isControlTransfer(instruction: N64HleR4300DecodedInstruction): Boolean {
        if (instruction.isSpecial) {
            return instruction.specialOperation == N64HleR4300SpecialOperation.JR ||
                instruction.specialOperation == N64HleR4300SpecialOperation.JALR
        }
        return instruction.opcode == 0x01 && instruction.rt in REGIMM_BRANCH_RT ||
            instruction.opcode in setOf(0x02, 0x03, 0x04, 0x05, 0x06, 0x07)
    }
}
