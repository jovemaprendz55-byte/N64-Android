package com.jsr.n64android

/**
 * Applies a planned control transfer and tracks its one required delay slot.
 *
 * This is a sequencing primitive, not a complete CPU stepper. The caller must
 * execute exactly one guest instruction at the delay-slot PC, then call
 * completeDelaySlot. Exceptions raised by that instruction are not modeled here.
 */
class N64HleR4300ControlFlowSequencer {

    private data class Pending(
        val delaySlotAddress: Long,
        val continuationAddress: Long
    )

    private var pending: Pending? = null

    fun hasPendingDelaySlot(): Boolean = pending != null

    /** Applies link state and moves PC to the delay slot after full preflight. */
    fun schedule(
        state: N64HleR4300CpuState,
        plan: N64HleR4300ControlTransferPlan
    ) {
        check(pending == null) { "A control transfer is already waiting for its delay slot" }
        require(state.programCounter == plan.instructionAddress) {
            "Control transfer plan does not match current PC"
        }
        require(plan.delaySlotAddress == plan.instructionAddress + 4L) {
            "Control transfer delay slot must be instruction PC + 4"
        }
        require((plan.continuationAddress and 3L) == 0L) {
            "Control transfer continuation must be word-aligned"
        }
        require((plan.linkRegister == null) == (plan.linkValue == null)) {
            "Link register and link value must be supplied together"
        }
        plan.linkRegister?.let { require(it in 0..31) { "Link register must be in 0..31" } }

        // All validation is complete before architectural state is mutated.
        if (plan.linkRegister != null) {
            state.writeGpr(plan.linkRegister, plan.linkValue!!)
        }
        state.setProgramCounter(plan.delaySlotAddress)
        pending = Pending(plan.delaySlotAddress, plan.continuationAddress)
    }

    /** Completes the delay slot and installs the transfer's final PC. */
    fun completeDelaySlot(state: N64HleR4300CpuState) {
        val transfer = pending
            ?: throw IllegalStateException("No control transfer is waiting for a delay slot")
        check(state.programCounter == transfer.delaySlotAddress) {
            "Current PC is not the pending delay-slot address"
        }
        state.setProgramCounter(transfer.continuationAddress)
        pending = null
    }
}
