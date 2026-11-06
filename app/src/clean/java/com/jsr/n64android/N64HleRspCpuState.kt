package com.jsr.n64android

/**
 * Scalar RSP state for the clean HLE runtime.
 *
 * The scalar register file is 32-bit and GPR0 is hard-wired to zero.
 * Vector registers and COP0 are deliberately separate future components.
 */
class N64HleRspCpuState(initialProgramCounter: Int = 0) {
    private val gpr = IntArray(32)
    var hi: Int = 0
        internal set
    var lo: Int = 0
        internal set
    var programCounter: Int = initialProgramCounter and PC_MASK
        internal set
    var halted: Boolean = false
        internal set
    private var pendingBranchTarget: Int? = null

    init {
        require((initialProgramCounter and 3) == 0) { "RSP PC must be word-aligned" }
    }

    fun readGpr(index: Int): Int {
        require(index in 0..31)
        return if (index == 0) 0 else gpr[index]
    }

    fun writeGpr(index: Int, value: Int) {
        require(index in 0..31)
        if (index != 0) gpr[index] = value
    }

    internal fun writeHi(value: Int) { hi = value }
    internal fun writeLo(value: Int) { lo = value }

    internal fun pendingBranchTarget(): Int? = pendingBranchTarget

    internal fun clearPendingBranchTarget() {
        pendingBranchTarget = null
    }

    internal fun scheduleBranch(target: Int) {
        check(pendingBranchTarget == null) { "RSP control transfer in a delay slot is unsupported" }
        pendingBranchTarget = target and PC_MASK
    }

    internal fun hasPendingBranch(): Boolean = pendingBranchTarget != null

    companion object {
        const val PC_MASK = 0x0FFC
    }
}
