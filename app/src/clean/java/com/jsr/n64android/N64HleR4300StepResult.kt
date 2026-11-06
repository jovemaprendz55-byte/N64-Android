package com.jsr.n64android

/** Classification for a decoded instruction that has no active executor. */
enum class N64HleR4300UnsupportedInstructionClass {
    VALID_BUT_NOT_IMPLEMENTED,
    UNCLASSIFIED_ENCODING
}

/**
 * Kept as an IllegalArgumentException subtype for source compatibility with
 * existing callers while exposing an explicit architectural-capability reason.
 */
class N64HleR4300UnsupportedInstructionException(
    val instruction: N64HleR4300DecodedInstruction,
    val classification: N64HleR4300UnsupportedInstructionClass
) : IllegalArgumentException(
    "${classification.name}: R4300 instruction 0x${instruction.rawWord.toUInt().toString(16)} has no clean executor"
)

/** Typed, non-mutating result API for one CPU step. */
sealed interface N64HleR4300StepResult {
    data class Executed(val instruction: N64HleR4300DecodedInstruction) : N64HleR4300StepResult
    data class ArchitecturalException(val exception: N64HleR4300ArchitecturalException) : N64HleR4300StepResult
    data class ReservedEncoding(val exception: N64HleR4300ArchitecturalException) : N64HleR4300StepResult
    data class ValidButNotImplemented(
        val instruction: N64HleR4300DecodedInstruction,
        val diagnostic: String
    ) : N64HleR4300StepResult
    data class UnclassifiedEncoding(
        val instruction: N64HleR4300DecodedInstruction,
        val diagnostic: String
    ) : N64HleR4300StepResult
}
