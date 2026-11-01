package com.jsr.n64android

/**
 * Conservative evidence tracker for the boot-microcode -> task-microcode handoff.
 *
 * It requires one completed RDRAM->IMEM DMA that exactly matches the OSTask
 * ucode address and size, followed by an executed RSP instruction fetched
 * from the loaded IMEM range. A partial/chunked load is intentionally not
 * accepted yet; callers must not infer a task transition from PC alone.
 */
class N64SpTaskMicrocodeTracker(
    private val descriptor: N64SpTaskDescriptor
) {
    private var matchingMicrocodeDmaObserved = false
    var taskMicrocodeEntryObserved: Boolean = false
        private set

    fun onSpDmaCompleted(transfer: N64Bus.SpDmaTransfer): Boolean {
        if (taskMicrocodeEntryObserved || matchingMicrocodeDmaObserved) return false
        val size = descriptor.ucodeSize
        if (size !in 1..N64Bus.SP_MEM_SIZE) return false
        val matches = transfer.toSp &&
            transfer.spStartAddress == IMEM_DMA_BASE &&
            transfer.dramStartAddress == (descriptor.ucode and RDRAM_ADDRESS_MASK) &&
            transfer.transferredBytes == size &&
            transfer.blockLength > 0 &&
            transfer.blockCount > 0
        if (matches) matchingMicrocodeDmaObserved = true
        return matches
    }

    /** Returns true only on the first executed instruction inside the loaded task code. */
    fun onInstructionExecuted(programCounter: Int, instruction: Int): Boolean {
        if (!matchingMicrocodeDmaObserved || taskMicrocodeEntryObserved) return false
        if (programCounter < 0 || programCounter >= descriptor.ucodeSize) return false
        if ((programCounter and 3) != 0) return false
        taskMicrocodeEntryObserved = true
        return true
    }

    companion object {
        private const val IMEM_DMA_BASE = 0x1000
        private const val RDRAM_ADDRESS_MASK = 0x00FFFFFF
    }
}
