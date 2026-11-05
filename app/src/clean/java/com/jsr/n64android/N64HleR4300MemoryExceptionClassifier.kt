package com.jsr.n64android

/**
 * Converts typed data-memory faults into R4300 exception metadata.
 *
 * Instruction-fetch faults are intentionally not accepted here: they need a
 * separate fetch path and must not be guessed to be instruction bus errors.
 */
class N64HleR4300MemoryExceptionClassifier {
    fun classify(fault: N64HleR4300MemoryAccessFault): N64HleR4300ExceptionContext {
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
        return N64HleR4300ExceptionContext(
            code = code,
            faultingPc = 0L,
            badVirtualAddress = fault.memoryFault.virtualAddress
        )
    }
}
