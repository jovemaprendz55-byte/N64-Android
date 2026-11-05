package com.jsr.n64android

/**
 * Identifies only exception-causing SPECIAL encodings whose meaning is
 * unambiguous from the instruction word alone.
 *
 * A null result means "not classified here", not "instruction is supported".
 * In particular, valid but unimplemented operations and non-SPECIAL opcodes
 * are deliberately left to later capability/privilege-aware handling.
 */
class N64HleR4300ExceptionInstructionClassifier {
    fun classify(
        instruction: N64HleR4300DecodedInstruction
    ): N64HleR4300ExceptionCode? {
        if (!instruction.isSpecial) {
            // REGIMM only defines four branch forms and six trap-immediate forms.
            // Every other rt encoding in this opcode space is reserved.
            if (instruction.opcode == OP_REGIMM && instruction.rt !in REGIMM_VALID_RT) {
                return N64HleR4300ExceptionCode.RESERVED_INSTRUCTION
            }
            if (instruction.opcode == OP_COP0) {
                return classifyCop0Encoding(instruction)
            }
            return null
        }

        return when (instruction.specialOperation) {
            null -> N64HleR4300ExceptionCode.RESERVED_INSTRUCTION
            N64HleR4300SpecialOperation.SYSCALL -> N64HleR4300ExceptionCode.SYSCALL
            N64HleR4300SpecialOperation.BREAK -> N64HleR4300ExceptionCode.BREAKPOINT
            else -> null
        }
    }

    /**
     * Evaluates conditional SPECIAL trap instructions against the current
     * architectural GPR values. A false condition is a normal instruction.
     */
    fun classify(
        instruction: N64HleR4300DecodedInstruction,
        state: N64HleR4300CpuState
    ): N64HleR4300ExceptionCode? {
        classify(instruction)?.let { return it }

        // REGIMM trap-immediate operations use the sign-extended 16-bit
        // immediate as the second operand; they are exceptions, not branches.
        if (instruction.opcode == OP_REGIMM) {
            val left = state.readGpr(instruction.rs)
            val right = instruction.immediateSigned.toLong()
            val condition = when (instruction.rt) {
                RT_TGEI -> left >= right
                RT_TGEIU -> java.lang.Long.compareUnsigned(left, right) >= 0
                RT_TLTI -> left < right
                RT_TLTIU -> java.lang.Long.compareUnsigned(left, right) < 0
                RT_TEQI -> left == right
                RT_TNEI -> left != right
                else -> return null
            }
            return if (condition) N64HleR4300ExceptionCode.TRAP else null
        }

        if (!instruction.isSpecial) return null

        val operation = instruction.specialOperation ?: return null
        if (operation !in TRAP_OPERATIONS) return null
        val left = state.readGpr(instruction.rs)
        val right = state.readGpr(instruction.rt)
        val condition = when (operation) {
            N64HleR4300SpecialOperation.TGE -> left >= right
            N64HleR4300SpecialOperation.TGEU ->
                java.lang.Long.compareUnsigned(left, right) >= 0
            N64HleR4300SpecialOperation.TLT -> left < right
            N64HleR4300SpecialOperation.TLTU ->
                java.lang.Long.compareUnsigned(left, right) < 0
            N64HleR4300SpecialOperation.TEQ -> left == right
            N64HleR4300SpecialOperation.TNE -> left != right
            else -> false
        }
        return if (condition) N64HleR4300ExceptionCode.TRAP else null
    }

    private fun classifyCop0Encoding(
        instruction: N64HleR4300DecodedInstruction
    ): N64HleR4300ExceptionCode? {
        // MFC0/DMFC0/MTC0/DMTC0 are architecturally defined transfer formats.
        // Only MFC0/MTC0 are currently executed; the 64-bit forms remain valid
        // but unimplemented and therefore must not be reported as RI.
        if (instruction.rs in COP0_TRANSFER_RS) return null

        // COP0 CO format contains TLB operations, ERET and WAIT. Preserve these
        // known-valid operations for later execution/privilege handling.
        if (instruction.rs == COP0_RS_CO) {
            return if (instruction.functionCode in COP0_KNOWN_CO_FUNCTIONS) {
                null
            } else {
                N64HleR4300ExceptionCode.RESERVED_INSTRUCTION
            }
        }

        // Other rs formats in the COP0 opcode space are reserved on R4300.
        return N64HleR4300ExceptionCode.RESERVED_INSTRUCTION
    }

    private companion object {
        const val OP_REGIMM = 0x01
        const val OP_COP0 = 0x10
        const val COP0_RS_CO = 0x10
        val COP0_TRANSFER_RS = setOf(0x00, 0x01, 0x04, 0x05)
        // TLBR, TLBWI, TLBWR, TLBP, ERET, WAIT.
        val COP0_KNOWN_CO_FUNCTIONS = setOf(0x01, 0x02, 0x06, 0x08, 0x18, 0x20)
        const val RT_TGEI = 0x08
        const val RT_TGEIU = 0x09
        const val RT_TLTI = 0x0A
        const val RT_TLTIU = 0x0B
        const val RT_TEQI = 0x0C
        const val RT_TNEI = 0x0E

        val REGIMM_VALID_RT = setOf(
            0x00, 0x01, 0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0E, 0x10, 0x11
        )

        val TRAP_OPERATIONS = setOf(
            N64HleR4300SpecialOperation.TGE,
            N64HleR4300SpecialOperation.TGEU,
            N64HleR4300SpecialOperation.TLT,
            N64HleR4300SpecialOperation.TLTU,
            N64HleR4300SpecialOperation.TEQ,
            N64HleR4300SpecialOperation.TNE
        )
    }
}
