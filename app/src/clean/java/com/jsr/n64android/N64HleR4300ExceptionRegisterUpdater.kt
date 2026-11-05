package com.jsr.n64android

/**
 * Applies the architecturally defined Status.EXL, Cause.BD/Cause.ExcCode,
 * EPC and optional BadVAddr fields for a supplied exception context.
 *
 * Vector selection, interrupt masking, nested exception policy beyond EXL,
 * and instruction-stepper delivery are intentionally outside this component.
 */
class N64HleR4300ExceptionRegisterUpdater {
    fun apply(
        context: N64HleR4300ExceptionContext,
        state: N64HleR4300CpuState
    ): N64HleR4300ExceptionRegisterUpdate {
        // Preflight every required source before changing any register.
        val status = state.readCop0(COP0_STATUS).toInt()
        val cause = state.readCop0(COP0_CAUSE).toInt()
        val exlWasSet = (status and STATUS_EXL) != 0

        val updatedCause = (cause and CAUSE_EXCCODE_MASK.inv() and CAUSE_BD_MASK.inv()) or
            ((context.code.causeExcCode shl CAUSE_EXCCODE_SHIFT) and CAUSE_EXCCODE_MASK) or
            if (context.branchDelayFlag) CAUSE_BD_MASK else 0
        val updatedStatus = status or STATUS_EXL

        // All calculations and required reads are complete; mutations begin here.
        state.writeCop0(COP0_CAUSE, updatedCause.toLong())
        state.writeCop0(COP0_STATUS, updatedStatus.toLong())
        if (!exlWasSet) {
            state.writeCop0(COP0_EPC, context.exceptionProgramCounter)
        }
        context.badVirtualAddress?.let {
            state.writeCop0(COP0_BAD_VADDR, it)
        }

        return N64HleR4300ExceptionRegisterUpdate(
            exceptionProgramCounter = context.exceptionProgramCounter,
            exlWasAlreadySet = exlWasSet,
            epcWritten = !exlWasSet,
            cause = state.readCop0(COP0_CAUSE),
            status = state.readCop0(COP0_STATUS)
        )
    }

    private companion object {
        const val COP0_BAD_VADDR = 8
        const val COP0_STATUS = 12
        const val COP0_CAUSE = 13
        const val COP0_EPC = 14
        const val STATUS_EXL = 1 shl 1
        const val CAUSE_BD_MASK = Int.MIN_VALUE
        const val CAUSE_EXCCODE_SHIFT = 2
        const val CAUSE_EXCCODE_MASK = 0x1F shl CAUSE_EXCCODE_SHIFT
    }
}

/** Outcome of updating COP0 exception fields; it does not imply vector dispatch. */
data class N64HleR4300ExceptionRegisterUpdate(
    val exceptionProgramCounter: Long,
    val exlWasAlreadySet: Boolean,
    val epcWritten: Boolean,
    val cause: Long,
    val status: Long
)
