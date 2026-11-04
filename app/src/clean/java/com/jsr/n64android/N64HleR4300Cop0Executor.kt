package com.jsr.n64android

/**
 * Executes the basic MFC0/MTC0 register-transfer forms.
 *
 * COP0 values are explicit 32-bit state. Register-specific side effects,
 * privilege checks, TLB operations and exception delivery are not modeled.
 */
class N64HleR4300Cop0Executor {
    fun supports(instruction: N64HleR4300DecodedInstruction): Boolean =
        instruction.opcode == OP_COP0 && instruction.rs in setOf(RS_MFC0, RS_MTC0)

    fun execute(instruction: N64HleR4300DecodedInstruction, state: N64HleR4300CpuState) {
        require(instruction.opcode == OP_COP0) { "Instruction is not COP0" }
        when (instruction.rs) {
            RS_MFC0 -> {
                val value = state.readCop0(instruction.rd)
                state.writeGpr(instruction.rt, value.toInt().toLong())
            }
            RS_MTC0 -> {
                val value = state.readGpr(instruction.rt)
                state.writeCop0(instruction.rd, value)
            }
            else -> throw UnsupportedOperationException(
                "COP0 transfer format rs=0x${instruction.rs.toString(16)} is not implemented"
            )
        }
    }

    private companion object {
        const val OP_COP0 = 0x10
        const val RS_MFC0 = 0x00
        const val RS_MTC0 = 0x04
    }
}
