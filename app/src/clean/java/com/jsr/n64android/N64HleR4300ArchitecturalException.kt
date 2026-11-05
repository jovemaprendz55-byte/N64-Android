package com.jsr.n64android

/**
 * Host-visible signal that a guest data-memory exception was entered in COP0.
 *
 * The stepper records exception metadata and redirects guest PC to the
 * resolved general vector. The original memory fault stays available as the cause.
 */
class N64HleR4300ArchitecturalException(
    val context: N64HleR4300ExceptionContext,
    val registerUpdate: N64HleR4300ExceptionRegisterUpdate,
    val vectorAddress: Long,
    cause: N64HleR4300MemoryAccessFault
) : RuntimeException(
    "R4300 ${context.code.name} exception at 0x${context.faultingPc.toString(16)}",
    cause
)
