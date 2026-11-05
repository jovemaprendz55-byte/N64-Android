package com.jsr.n64android

/**
 * Architectural exception categories used by the R4300/MIPS exception path.
 *
 * This is descriptive metadata only: it does not mutate COP0, choose a vector,
 * or claim that the corresponding exception has been delivered to the guest.
 */
enum class N64HleR4300ExceptionCode(val causeExcCode: Int) {
    INTERRUPT(0),
    TLB_MODIFICATION(1),
    TLB_LOAD(2),
    TLB_STORE(3),
    ADDRESS_LOAD(4),
    ADDRESS_STORE(5),
    INSTRUCTION_BUS_ERROR(6),
    DATA_BUS_ERROR(7),
    SYSCALL(8),
    BREAKPOINT(9),
    RESERVED_INSTRUCTION(10),
    COPROCESSOR_UNUSABLE(11),
    ARITHMETIC_OVERFLOW(12),
    TRAP(13),
    FLOATING_POINT(15),
    WATCH(23)
}

/**
 * Immutable facts needed to prepare an exception entry.
 *
 * For an exception in a branch delay slot, EPC identifies the branch/jump
 * instruction and Cause.BD is true. For all other exceptions EPC identifies
 * the faulting instruction. Branch-delay metadata must be explicit; this
 * class never infers it from PC history.
 */
data class N64HleR4300ExceptionContext(
    val code: N64HleR4300ExceptionCode,
    val faultingPc: Long,
    val inDelaySlot: Boolean = false,
    val branchPc: Long? = null,
    val badVirtualAddress: Long? = null,
    val coprocessorNumber: Int? = null
) {
    val exceptionProgramCounter: Long

    init {
        require((faultingPc and 3L) == 0L) {
            "Faulting instruction PC must be word-aligned"
        }
        if (inDelaySlot) {
            requireNotNull(branchPc) {
                "A delay-slot exception requires the address of its branch instruction"
            }
            require((branchPc and 3L) == 0L) {
                "Branch instruction PC must be word-aligned"
            }
            require(faultingPc == branchPc + 4L) {
                "Delay-slot faulting PC must immediately follow the branch instruction"
            }
        } else {
            require(branchPc == null) {
                "Branch PC must be absent when the exception is not in a delay slot"
            }
        }
        require(coprocessorNumber == null || coprocessorNumber in 0..3) {
            "Coprocessor number must be in 0..3"
        }
        exceptionProgramCounter = if (inDelaySlot) branchPc!! else faultingPc
    }

    /** Corresponds to the Cause.BD flag; no Cause register is written here. */
    val branchDelayFlag: Boolean
        get() = inDelaySlot
}
