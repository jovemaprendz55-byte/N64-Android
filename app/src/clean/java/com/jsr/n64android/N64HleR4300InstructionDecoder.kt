package com.jsr.n64android

/** R4300 SPECIAL-function identifiers recognized by the clean decoder. */
enum class N64HleR4300SpecialOperation(val functionCode: Int) {
    SLL(0x00), SRL(0x02), SRA(0x03),
    SLLV(0x04), SRLV(0x06), SRAV(0x07),
    JR(0x08), JALR(0x09), SYSCALL(0x0C), BREAK(0x0D), SYNC(0x0F),
    MFHI(0x10), MTHI(0x11), MFLO(0x12), MTLO(0x13),
    MULT(0x18), MULTU(0x19), DIV(0x1A), DIVU(0x1B),
    DMULT(0x1C), DMULTU(0x1D), DDIV(0x1E), DDIVU(0x1F),
    ADD(0x20), ADDU(0x21), SUB(0x22), SUBU(0x23),
    AND(0x24), OR(0x25), XOR(0x26), NOR(0x27),
    SLT(0x2A), SLTU(0x2B), DADD(0x2C), DADDU(0x2D),
    DSUB(0x2E), DSUBU(0x2F),
    TGE(0x30), TGEU(0x31), TLT(0x32), TLTU(0x33), TEQ(0x34), TNE(0x36),
    DSLL(0x38), DSRL(0x3A), DSRA(0x3B),
    DSLL32(0x3C), DSRL32(0x3E), DSRA32(0x3F);

    companion object {
        private val byCode = entries.associateBy { it.functionCode }
        fun fromFunctionCode(code: Int): N64HleR4300SpecialOperation? = byCode[code]
    }
}

/**
 * Pure bit-field decoder for one 32-bit MIPS/R4300 instruction word.
 * It does not execute the instruction or imply that its operation is
 * implemented by the CPU core.
 */
data class N64HleR4300DecodedInstruction(
    val rawWord: Int,
    val opcode: Int,
    val rs: Int,
    val rt: Int,
    val rd: Int,
    val shiftAmount: Int,
    val functionCode: Int,
    val immediateUnsigned: Int,
    val immediateSigned: Int,
    val jumpIndex: Int,
    val specialOperation: N64HleR4300SpecialOperation?
) {
    val isSpecial: Boolean get() = opcode == 0
}

/** Stateless instruction-field decoder. */
class N64HleR4300InstructionDecoder {
    fun decode(word: Int): N64HleR4300DecodedInstruction {
        val opcode = (word ushr 26) and 0x3F
        val functionCode = word and 0x3F
        return N64HleR4300DecodedInstruction(
            rawWord = word,
            opcode = opcode,
            rs = (word ushr 21) and 0x1F,
            rt = (word ushr 16) and 0x1F,
            rd = (word ushr 11) and 0x1F,
            shiftAmount = (word ushr 6) and 0x1F,
            functionCode = functionCode,
            immediateUnsigned = word and 0xFFFF,
            immediateSigned = (word and 0xFFFF).toShort().toInt(),
            jumpIndex = word and 0x03FF_FFFF,
            specialOperation = if (opcode == 0) {
                N64HleR4300SpecialOperation.fromFunctionCode(functionCode)
            } else {
                null
            }
        )
    }
}
