package com.jsr.n64android

/**
 * Describes a classic R4300 control transfer without executing its delay slot.
 *
 * The caller must execute delaySlotAddress before installing continuationAddress.
 * Link writes are returned as data so the CPU stepper can apply them at the
 * architectural point before the delay-slot instruction executes.
 */
data class N64HleR4300ControlTransferPlan(
    val instructionAddress: Long,
    val delaySlotAddress: Long,
    val continuationAddress: Long,
    val taken: Boolean,
    val linkRegister: Int? = null,
    val linkValue: Long? = null
)

/** Plans the control-flow effects of a supported branch or jump instruction. */
class N64HleR4300ControlTransferPlanner {

    fun plan(
        instruction: N64HleR4300DecodedInstruction,
        state: N64HleR4300CpuState
    ): N64HleR4300ControlTransferPlan {
        val pc = state.programCounter
        val delayPc = pc + 4L
        val afterDelay = pc + 8L

        if (instruction.isSpecial) {
            return when (instruction.specialOperation) {
                N64HleR4300SpecialOperation.JR -> {
                    val target = state.readGpr(instruction.rs)
                    requireAlignedTarget(target)
                    N64HleR4300ControlTransferPlan(pc, delayPc, target, true)
                }
                N64HleR4300SpecialOperation.JALR -> {
                    val target = state.readGpr(instruction.rs)
                    requireAlignedTarget(target)
                    N64HleR4300ControlTransferPlan(
                        pc, delayPc, target, true,
                        linkRegister = instruction.rd,
                        linkValue = afterDelay
                    )
                }
                else -> throw UnsupportedOperationException("SPECIAL instruction is not a supported control transfer")
            }
        }

        return when (instruction.opcode) {
            0x02, 0x03 -> {
                val pcPlus4 = delayPc.toInt()
                val target32 = (pcPlus4 and 0xF0000000.toInt()) or (instruction.jumpIndex shl 2)
                val target = target32.toLong()
                N64HleR4300ControlTransferPlan(
                    pc, delayPc, target, true,
                    linkRegister = if (instruction.opcode == 0x03) 31 else null,
                    linkValue = if (instruction.opcode == 0x03) afterDelay else null
                )
            }
            0x04, 0x05 -> {
                val left = state.readGpr(instruction.rs)
                val right = state.readGpr(instruction.rt)
                val taken = if (instruction.opcode == 0x04) left == right else left != right
                branchPlan(pc, delayPc, afterDelay, instruction.immediateSigned, taken)
            }
            0x06, 0x07 -> {
                require(instruction.rt == 0) { "BLEZ/BGTZ require rt=0" }
                val value = state.readGpr(instruction.rs)
                val taken = if (instruction.opcode == 0x06) value <= 0L else value > 0L
                branchPlan(pc, delayPc, afterDelay, instruction.immediateSigned, taken)
            }
            0x01 -> {
                val value = state.readGpr(instruction.rs)
                val isLink = instruction.rt == 0x10 || instruction.rt == 0x11
                val taken = when (instruction.rt) {
                    0x00, 0x10 -> value < 0L // BLTZ / BLTZAL
                    0x01, 0x11 -> value >= 0L // BGEZ / BGEZAL
                    else -> throw UnsupportedOperationException(
                        "REGIMM rt=0x${instruction.rt.toString(16)} is not supported"
                    )
                }
                branchPlan(
                    pc, delayPc, afterDelay, instruction.immediateSigned, taken,
                    linkRegister = if (isLink && taken) 31 else null
                )
            }
            else -> throw UnsupportedOperationException(
                "Opcode 0x${instruction.opcode.toString(16)} is not a supported control transfer"
            )
        }
    }

    private fun branchPlan(
        pc: Long,
        delayPc: Long,
        afterDelay: Long,
        immediate: Int,
        taken: Boolean,
        linkRegister: Int? = null
    ): N64HleR4300ControlTransferPlan {
        val target = delayPc + (immediate.toLong() shl 2)
        return N64HleR4300ControlTransferPlan(
            pc, delayPc, if (taken) target else afterDelay, taken,
            linkRegister = linkRegister,
            linkValue = if (linkRegister != null) afterDelay else null
        )
    }

    private fun requireAlignedTarget(target: Long) {
        require((target and 3L) == 0L) { "R4300 jump target must be word-aligned" }
    }
}
