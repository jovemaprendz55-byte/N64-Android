package com.jsr.n64android

/**
 * Bridges the GoldenEye/libultra thread context to the emulator's richer
 * architectural CPU snapshot. State absent from OSThread (TLB, Count phase,
 * RCP scheduling state, etc.) is preserved from the supplied template.
 *
 * GoldenEye's libultra context uses paired even/odd FPRs (FR=0). FR=1 is
 * rejected rather than silently reinterpreting the 16 serialized FP pairs.
 */
object N64HleGuestOsThreadContextMapper {
    private const val STATUS_FR_MASK = 1L shl 26

    fun toCpuSnapshot(
        thread: N64HleGuestOsThread,
        template: R4300Cpu.ContextSnapshot
    ): R4300Cpu.ContextSnapshot {
        require(template.gpr.size == 32)
        require(template.cop0.size == 32)
        require(template.fpr.size == 32)
        require(template.fcr.size == 32)

        val context = thread.context
        val status = context.status.toUInt().toLong()
        require((status and STATUS_FR_MASK) == 0L) {
            "GoldenEye OSThread FP pairs require Status.FR=0."
        }

        val gpr = template.gpr.toMutableList()
        SAVED_GPRS.forEach { register -> gpr[register] = context.gpr[register] }
        gpr[0] = 0L
        // K0/K1 are kernel scratch registers and are not part of the libultra context.
        gpr[26] = 0L
        gpr[27] = 0L

        val cop0 = template.cop0.toMutableList()
        cop0[8] = context.badVaddr.toUInt().toLong()
        cop0[12] = status
        cop0[13] = context.cause.toUInt().toLong()
        cop0[14] = context.pc.toLong()

        val fpr = template.fpr.toMutableList()
        val fcr = template.fcr.toMutableList()
        if (thread.fpUsed != 0) {
            context.fpPairs.forEachIndexed { pairIndex, pair ->
                fpr[pairIndex * 2] = (pair ushr 32) and 0xFFFFFFFFL
                fpr[pairIndex * 2 + 1] = pair and 0xFFFFFFFFL
            }
            fcr[31] = context.fpcsr.toUInt().toLong()
        }

        return template.copy(
            gpr = gpr,
            hi = context.hi,
            lo = context.lo,
            cop0 = cop0,
            fpr = fpr,
            fcr = fcr,
            pc = context.pc.toLong(),
            // The libultra context stores a resume PC, not an in-flight branch delay slot.
            delayedTarget = null,
            pendingDelaySlotBranchAddress = null
        )
    }

    fun fromCpuSnapshot(
        snapshot: R4300Cpu.ContextSnapshot,
        original: N64HleGuestOsThread
    ): N64HleGuestOsThread {
        require(snapshot.gpr.size == 32)
        require(snapshot.cop0.size == 32)
        require(snapshot.fpr.size == 32)
        require(snapshot.fcr.size == 32)

        val status = snapshot.cop0[12]
        require((status and STATUS_FR_MASK) == 0L) {
            "GoldenEye OSThread FP pairs require Status.FR=0."
        }
        val pc = snapshot.pc.toGuestPc()

        val gpr = original.context.gpr.copyOf()
        SAVED_GPRS.forEach { register -> gpr[register] = snapshot.gpr[register] }
        gpr[0] = 0L
        // Preserve the original raw values for ABI-omitted kernel scratch registers.
        gpr[26] = 0L
        gpr[27] = 0L

        val fpPairs = original.context.fpPairs.copyOf()
        var fpcsr = original.context.fpcsr
        if (original.fpUsed != 0) {
            for (pairIndex in 0 until 16) {
                val even = snapshot.fpr[pairIndex * 2] and 0xFFFFFFFFL
                val odd = snapshot.fpr[pairIndex * 2 + 1] and 0xFFFFFFFFL
                fpPairs[pairIndex] = (even shl 32) or odd
            }
            fpcsr = snapshot.fcr[31].toInt()
        }

        val context = original.context.copy(
            gpr = gpr,
            lo = snapshot.lo,
            hi = snapshot.hi,
            status = status.toInt(),
            pc = pc,
            cause = snapshot.cop0[13].toInt(),
            badVaddr = snapshot.cop0[8].toInt(),
            fpcsr = fpcsr,
            fpPairs = fpPairs
        )
        return original.copy(context = context)
    }

    private fun Long.toGuestPc(): Int {
        val low = toInt()
        require(this == low.toLong() || this == (low.toLong() and 0xFFFFFFFFL)) {
            "Guest resume PC does not fit in 32 bits."
        }
        require((low and 3) == 0) { "Guest resume PC must be word-aligned." }
        return low
    }

    private val SAVED_GPRS = (1..25).toList() + (28..31).toList()
}
