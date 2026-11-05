package com.jsr.n64android

/**
 * Host-visible signal that a guest data-memory exception was entered in COP0.
 *
 * The stepper records exception metadata but deliberately does not choose an
 * exception vector or redirect the guest PC. The original memory fault stays
 * available as the cause for diagnostics.
 */
class N64HleR4300ArchitecturalException(
    val context: N64HleR4300ExceptionContext,
    val registerUpdate: N64HleR4300ExceptionRegisterUpdate,
    cause: N64HleR4300MemoryAccessFault
) : RuntimeException(
    "R4300 ${context.code.name} exception at 0x${context.faultingPc.toString(16)}",
    cause
)
