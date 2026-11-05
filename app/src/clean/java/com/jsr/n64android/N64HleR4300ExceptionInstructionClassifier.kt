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
        if (!instruction.isSpecial) return null

        return when (instruction.specialOperation) {
            null -> N64HleR4300ExceptionCode.RESERVED_INSTRUCTION
            N64HleR4300SpecialOperation.SYSCALL -> N64HleR4300ExceptionCode.SYSCALL
            N64HleR4300SpecialOperation.BREAK -> N64HleR4300ExceptionCode.BREAKPOINT
            else -> null
        }
    }
}
