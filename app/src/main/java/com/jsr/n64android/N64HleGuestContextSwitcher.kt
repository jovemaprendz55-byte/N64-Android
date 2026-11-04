package com.jsr.n64android

/**
 * Connects guest OSThread contexts to the live R4300 snapshot at scheduler boundaries.
 *
 * This is the context save/restore primitive for future libultra call gates.
 * It does not discover ROM function addresses or intercept guest instructions.
 */
class N64HleGuestContextSwitcher(
    private val cpu: R4300Cpu,
    private val codec: N64HleGuestOsThreadCodec,
    private val scheduler: N64HleGuestThreadScheduler
) {
    fun startThread(threadAddress: Long, resumePc: Long): N64HleGuestThreadScheduler.DispatchDecision {
        validateResumePc(resumePc)
        val decision = scheduler.startThread(threadAddress)
        applyDecision(decision, resumePc)
        return decision
    }

    fun yieldCurrent(resumePc: Long): N64HleGuestThreadScheduler.DispatchDecision {
        validateResumePc(resumePc)
        val decision = scheduler.yieldThread()
        applyDecision(decision, resumePc)
        return decision
    }

    fun stopCurrent(resumePc: Long): N64HleGuestThreadScheduler.DispatchDecision {
        validateResumePc(resumePc)
        val decision = scheduler.stopThread()
        applyDecision(decision, resumePc)
        return decision
    }

    fun blockCurrent(waitQueueAddress: Long, resumePc: Long): N64HleGuestThreadScheduler.DispatchDecision {
        validateResumePc(resumePc)
        val decision = scheduler.blockRunningThread(waitQueueAddress)
        applyDecision(decision, resumePc)
        return decision
    }

    private fun applyDecision(
        decision: N64HleGuestThreadScheduler.DispatchDecision,
        resumePc: Long
    ) {
        val previous = decision.previousRunningThread
        val selected = decision.selectedThread

        if (decision.switched) {
            if (previous != N64HleGuestThreadScheduler.NULL_POINTER) {
                saveCurrentContext(previous, resumePc)
            }
            if (selected != N64HleGuestThreadScheduler.NULL_POINTER) {
                restoreGuestContext(selected)
            }
        } else {
            // No scheduler switch: the current thread simply returns from the HLE call.
            if (selected != N64HleGuestThreadScheduler.NULL_POINTER) {
                cpu.setProgramCounter(resumePc)
            }
        }
    }

    private fun saveCurrentContext(threadAddress: Long, resumePc: Long) {
        val original = codec.read(threadAddress)
        val live = cpu.snapshotContext().copy(
            pc = resumePc,
            delayedTarget = null,
            pendingDelaySlotBranchAddress = null
        )
        codec.write(
            threadAddress,
            N64HleGuestOsThreadContextMapper.fromCpuSnapshot(live, original)
        )
    }

    private fun restoreGuestContext(threadAddress: Long) {
        val guestThread = codec.read(threadAddress)
        val mapped = N64HleGuestOsThreadContextMapper.toCpuSnapshot(
            guestThread,
            cpu.snapshotContext()
        )
        cpu.restoreContext(mapped)
    }

    private fun validateResumePc(pc: Long) {
        require(pc and 3L == 0L) { "Guest continuation PC must be word-aligned." }
        val low = pc.toInt()
        require(pc == low.toLong() || pc == (low.toLong() and 0xFFFFFFFFL)) {
            "Guest continuation PC must fit in 32 bits."
        }
    }
}
