package com.jsr.n64android

/**
 * Evidence tracker for the boot-microcode -> task-microcode handoff.
 *
 * It accepts fragmented RDRAM->IMEM loads only when every byte written into the
 * OSTask ucode range comes from the corresponding contiguous OSTask RDRAM byte.
 * Later IMEM writes invalidate or replace the affected evidence. An executed
 * RSP instruction in the fully covered range is still required for handoff.
 */
class N64SpTaskMicrocodeTracker(
    private val descriptor: N64SpTaskDescriptor
) {
    private val loadedBytes = BooleanArray(descriptor.ucodeSize.coerceIn(0, N64Bus.SP_MEM_SIZE))
    private val ucodeBase = descriptor.ucode and RDRAM_ADDRESS_MASK

    var taskMicrocodeEntryObserved: Boolean = false
        private set

    /**
     * Records a completed DMA. Returns true once the entire descriptor ucode range
     * is simultaneously backed by matching RDRAM bytes in IMEM[0 until ucodeSize].
     */
    fun onSpDmaCompleted(transfer: N64Bus.SpDmaTransfer): Boolean {
        if (taskMicrocodeEntryObserved) return false
        if (descriptor.ucodeSize !in 1..N64Bus.SP_MEM_SIZE) return false
        if (!transfer.toSp || (transfer.spStartAddress and IMEM_SELECT) == 0) return false
        if (transfer.blockLength <= 0 || transfer.blockCount <= 0) return false

        val startOffset = transfer.spStartAddress and SP_OFFSET_MASK
        for (block in 0 until transfer.blockCount) {
            val dramBlockStart = transfer.dramStartAddress.toLong() +
                block.toLong() * (transfer.blockLength.toLong() + transfer.dramSkip.toLong())
            for (byteInBlock in 0 until transfer.blockLength) {
                val imemOffset = (startOffset + block * transfer.blockLength + byteInBlock) and SP_OFFSET_MASK
                if (imemOffset >= descriptor.ucodeSize) continue

                val actualDramAddress = dramBlockStart + byteInBlock
                val expectedDramAddress = ucodeBase.toLong() + imemOffset
                loadedBytes[imemOffset] = actualDramAddress == expectedDramAddress
            }
        }
        return loadedBytes.all { it }
    }

    /** Returns true only on the first executed instruction inside fully loaded task code. */
    fun onInstructionExecuted(programCounter: Int): Boolean {
        if (taskMicrocodeEntryObserved || descriptor.ucodeSize !in 1..N64Bus.SP_MEM_SIZE) return false
        if (!loadedBytes.all { it }) return false
        if (programCounter < 0 || programCounter >= descriptor.ucodeSize) return false
        if ((programCounter and 3) != 0) return false
        taskMicrocodeEntryObserved = true
        return true
    }

    companion object {
        private const val IMEM_SELECT = 0x1000
        private const val SP_OFFSET_MASK = 0x0FFF
        private const val RDRAM_ADDRESS_MASK = 0x00FFFFFF
    }
}
