package com.jsr.n64android

/** Exception code and BadVAddr metadata, independent of the current CPU PC. */
data class N64HleR4300MemoryExceptionClassification(
    val code: N64HleR4300ExceptionCode,
    val badVirtualAddress: Long
)

/**
 * Converts typed data-memory faults into R4300 exception metadata.
 *
 * Instruction-fetch faults are intentionally handled elsewhere: this class
 * never guesses that a fetch failure is an instruction bus error.
 */
class N64HleR4300MemoryExceptionClassifier {
    fun classify(
        fault: N64HleR4300MemoryAccessFault
    ): N64HleR4300MemoryExceptionClassification {
        val code = when (fault.memoryFault) {
            is N64HleGuestAddressAlignmentFault ->
                if (fault.accessKind == N64HleR4300MemoryAccessKind.LOAD) {
                    N64HleR4300ExceptionCode.ADDRESS_LOAD
                } else {
                    N64HleR4300ExceptionCode.ADDRESS_STORE
                }

            is N64HleGuestAddressMappingFault ->
                N64HleR4300ExceptionCode.DATA_BUS_ERROR
        }
        return N64HleR4300MemoryExceptionClassification(
            code = code,
            badVirtualAddress = fault.memoryFault.virtualAddress
        )
    }
}
