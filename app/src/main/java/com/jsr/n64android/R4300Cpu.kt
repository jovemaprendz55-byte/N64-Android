package com.jsr.n64android

/**
 * First CPU milestone: architectural state and reset behavior only.
 * Instruction execution, COP0, exceptions and TLB are later milestones.
 */
class R4300Cpu {
    val gpr: LongArray = LongArray(32)
    var pc: Long = RESET_VECTOR
        private set

    fun reset() {
        gpr.fill(0L)
        pc = RESET_VECTOR
    }

    fun setProgramCounter(address: Long) {
        pc = address
    }

    fun advanceProgramCounter() {
        pc += 4L
        gpr[0] = 0L
    }

    companion object {
        const val RESET_VECTOR: Long = 0xFFFFFFFFBFC00000uL.toLong()
    }
}
