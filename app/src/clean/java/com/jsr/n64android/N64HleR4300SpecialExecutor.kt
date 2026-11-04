package com.jsr.n64android

/**
 * Executes the supported, side-effect-limited R4300 SPECIAL integer operations.
 *
 * This class deliberately does not advance PC and does not implement control
 * transfers, delay slots, exceptions, HI/LO arithmetic, or memory operations.
 * The caller owns instruction sequencing. Unsupported operations fail before
 * reading or mutating architectural state.
 */
class N64HleR4300SpecialExecutor {

    fun execute(
        instruction: N64HleR4300DecodedInstruction,
        state: N64HleR4300CpuState
    ) {
        require(instruction.isSpecial) { "Instruction is not SPECIAL" }
        val operation = instruction.specialOperation
            ?: throw UnsupportedOperationException("Unknown SPECIAL function 0x${instruction.functionCode.toString(16)}")

        val result = when (operation) {
            N64HleR4300SpecialOperation.ADDU ->
                wordResult(state.readGpr(instruction.rs) + state.readGpr(instruction.rt))
            N64HleR4300SpecialOperation.SUBU ->
                wordResult(state.readGpr(instruction.rs) - state.readGpr(instruction.rt))
            N64HleR4300SpecialOperation.AND ->
                state.readGpr(instruction.rs) and state.readGpr(instruction.rt)
            N64HleR4300SpecialOperation.OR ->
                state.readGpr(instruction.rs) or state.readGpr(instruction.rt)
            N64HleR4300SpecialOperation.XOR ->
                state.readGpr(instruction.rs) xor state.readGpr(instruction.rt)
            N64HleR4300SpecialOperation.NOR ->
                (state.readGpr(instruction.rs) or state.readGpr(instruction.rt)).inv()
            N64HleR4300SpecialOperation.SLT ->
                if (state.readGpr(instruction.rs) < state.readGpr(instruction.rt)) 1L else 0L
            N64HleR4300SpecialOperation.SLTU ->
                if (java.lang.Long.compareUnsigned(
                        state.readGpr(instruction.rs), state.readGpr(instruction.rt)
                    ) < 0) 1L else 0L
            N64HleR4300SpecialOperation.DADDU ->
                state.readGpr(instruction.rs) + state.readGpr(instruction.rt)
            N64HleR4300SpecialOperation.DSUBU ->
                state.readGpr(instruction.rs) - state.readGpr(instruction.rt)

            N64HleR4300SpecialOperation.SLL ->
                wordResult(state.readGpr(instruction.rt).toInt() shl instruction.shiftAmount)
            N64HleR4300SpecialOperation.SRL ->
                wordResult(state.readGpr(instruction.rt).toInt() ushr instruction.shiftAmount)
            N64HleR4300SpecialOperation.SRA ->
                wordResult(state.readGpr(instruction.rt).toInt() shr instruction.shiftAmount)
            N64HleR4300SpecialOperation.SLLV ->
                wordResult(state.readGpr(instruction.rt).toInt() shl (state.readGpr(instruction.rs).toInt() and 31))
            N64HleR4300SpecialOperation.SRLV ->
                wordResult(state.readGpr(instruction.rt).toInt() ushr (state.readGpr(instruction.rs).toInt() and 31))
            N64HleR4300SpecialOperation.SRAV ->
                wordResult(state.readGpr(instruction.rt).toInt() shr (state.readGpr(instruction.rs).toInt() and 31))

            N64HleR4300SpecialOperation.DSLL ->
                state.readGpr(instruction.rt) shl instruction.shiftAmount
            N64HleR4300SpecialOperation.DSRL ->
                state.readGpr(instruction.rt) ushr instruction.shiftAmount
            N64HleR4300SpecialOperation.DSRA ->
                state.readGpr(instruction.rt) shr instruction.shiftAmount
            N64HleR4300SpecialOperation.DSLL32 ->
                state.readGpr(instruction.rt) shl (instruction.shiftAmount + 32)
            N64HleR4300SpecialOperation.DSRL32 ->
                state.readGpr(instruction.rt) ushr (instruction.shiftAmount + 32)
            N64HleR4300SpecialOperation.DSRA32 ->
                state.readGpr(instruction.rt) shr (instruction.shiftAmount + 32)

            else -> throw UnsupportedOperationException(
                "SPECIAL operation $operation is not implemented by this executor"
            )
        }

        // All source reads and the calculation complete before the sole state write.
        state.writeGpr(instruction.rd, result)
    }

    private fun wordResult(value: Int): Long = value.toLong()
}
