package com.jsr.n64android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class N64SpTaskMicrocodeTrackerTest {
    @Test
    fun requiresMatchingImemDmaBeforeAcceptingExecutedInstruction() {
        val tracker = N64SpTaskMicrocodeTracker(taskDescriptor())
        assertFalse(tracker.onInstructionExecuted(0, 0x12345678))
        assertFalse(tracker.taskMicrocodeEntryObserved)

        assertTrue(tracker.onSpDmaCompleted(matchingTransfer()))
        assertTrue(tracker.onInstructionExecuted(0, 0x12345678))
        assertTrue(tracker.taskMicrocodeEntryObserved)
        assertFalse(tracker.onInstructionExecuted(4, 0x87654321))
    }

    @Test
    fun rejectsDmaWithWrongAddressSizeDirectionOrImemOffset() {
        val tracker = N64SpTaskMicrocodeTracker(taskDescriptor())
        assertFalse(tracker.onSpDmaCompleted(matchingTransfer().copy(dramStartAddress = 0x500)))
        assertFalse(tracker.onSpDmaCompleted(matchingTransfer().copy(transferredBytesOverride = 0)))
        assertFalse(tracker.onSpDmaCompleted(matchingTransfer().copy(toSp = false)))
        assertFalse(tracker.onSpDmaCompleted(matchingTransfer().copy(spStartAddress = 0)))
        assertFalse(tracker.taskMicrocodeEntryObserved)
    }

    @Test
    fun requiresExecutedPcInsideAlignedLoadedCodeRange() {
        val tracker = N64SpTaskMicrocodeTracker(taskDescriptor())
        tracker.onSpDmaCompleted(matchingTransfer())

        assertFalse(tracker.onInstructionExecuted(0x100, 0))
        assertFalse(tracker.onInstructionExecuted(2, 0))
        assertTrue(tracker.onInstructionExecuted(0x20, 0xDEADBEEF.toInt()))
    }

    private fun taskDescriptor() = N64SpTaskDescriptor(
        type = 1,
        flags = 0,
        ucodeBoot = 0x1000,
        ucodeBootSize = 0x100,
        ucode = 0x4000,
        ucodeSize = 0x80,
        ucodeData = 0x5000,
        ucodeDataSize = 0x40,
        dramStack = 0,
        dramStackSize = 0,
        outputBuffer = 0,
        outputBufferSize = 0,
        data = 0,
        dataSize = 0,
        yieldData = 0,
        yieldDataSize = 0
    )

    private fun matchingTransfer() = N64Bus.SpDmaTransfer(
        toSp = true,
        dramStartAddress = 0x4000,
        spStartAddress = 0x1000,
        blockLength = 0x80,
        blockCount = 1,
        dramSkip = 0
    )
}
