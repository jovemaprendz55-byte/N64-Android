package com.jsr.n64android

/**
 * Executes the initial non-control R4300 immediate-integer subset.
 *
 * ADDI/DADDI signed overflow raises a typed architectural signal before mutation;
 * the instruction stepper converts it into the R4300 overflow exception. ADDIU
 * and DADDIU wrap without trapping. Logical immediates
 * are zero-extended; arithmetic/comparison immediates are sign-extended.
 */
class N64HleR4300ImmediateExecutor {
    fun supports(opcode: Int): Boolean = opcode in setOf(
        OP_ADDI, OP_ADDIU, OP_DADDI, OP_DADDIU, OP_ANDI, OP_ORI, OP_XORI, OP_SLTI, OP_SLTIU, OP_LUI
    )

    fun execute(instruction: N64HleR4300DecodedInstruction, state: N64HleR4300CpuState) {
        require(!instruction.isSpecial) { "Instruction is SPECIAL, not immediate" }
        val opcode = instruction.opcode
        require(supports(opcode)) { "Immediate opcode 0x${opcode.toString(16)} is not implemented" }

        val result = when (opcode) {
            OP_ADDI -> {
                val left = state.readGpr(instruction.rs).toInt()
                val immediate = instruction.immediateSigned
                val sum = left.toLong() + immediate.toLong()
                if (sum !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                    throw N64HleR4300ArithmeticOverflow("ADDI")
                }
                sum.toInt().toLong()
            }
            OP_ADDIU -> (state.readGpr(instruction.rs).toInt() + instruction.immediateSigned).toLong()
            OP_DADDI -> {
                val left = state.readGpr(instruction.rs)
                val immediate = instruction.immediateSigned.toLong()
                val sum = left + immediate
                if (((left xor sum) and (immediate xor sum)) < 0L) {
                    throw N64HleR4300ArithmeticOverflow("DADDI")
                }
                sum
            }
            OP_DADDIU -> state.readGpr(instruction.rs) + instruction.immediateSigned.toLong()
            OP_ANDI -> state.readGpr(instruction.rs) and instruction.immediateUnsigned.toLong()
            OP_ORI -> state.readGpr(instruction.rs) or instruction.immediateUnsigned.toLong()
            OP_XORI -> state.readGpr(instruction.rs) xor instruction.immediateUnsigned.toLong()
            OP_SLTI -> if (state.readGpr(instruction.rs) < instruction.immediateSigned.toLong()) 1L else 0L
            OP_SLTIU -> if (
                java.lang.Long.compareUnsigned(
                    state.readGpr(instruction.rs), instruction.immediateSigned.toLong()
                ) < 0
            ) 1L else 0L
            OP_LUI -> instruction.immediateUnsigned.toLong().shl(16).toInt().toLong()
            else -> error("Unreachable immediate opcode")
        }
        state.writeGpr(instruction.rt, result)
    }

    private companion object {
        const val OP_ADDI = 0x08
        const val OP_ADDIU = 0x09
        const val OP_DADDI = 0x18
        const val OP_DADDIU = 0x19
        const val OP_SLTI = 0x0A
        const val OP_SLTIU = 0x0B
        const val OP_ANDI = 0x0C
        const val OP_ORI = 0x0D
        const val OP_XORI = 0x0E
        const val OP_LUI = 0x0F
    }
}
