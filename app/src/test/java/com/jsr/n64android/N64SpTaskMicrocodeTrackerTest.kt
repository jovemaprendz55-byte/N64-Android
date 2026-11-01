package com.jsr.n64android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class N64SpTaskMicrocodeTrackerTest {
    @Test
    fun requiresFullMatchingImemCoverageBeforeAcceptingExecutedInstruction() {
        val tracker = N64SpTaskMicrocodeTracker(taskDescriptor())
        assertFalse(tracker.onInstructionExecuted(0))
        assertFalse(tracker.onSpDmaCompleted(fragment(0, 0x20)))
        assertFalse(tracker.onInstructionExecuted(0))
        assertTrue(tracker.onSpDmaCompleted(fragment(0x20, 0x60)))
        assertTrue(tracker.onInstructionExecuted(0))
        assertTrue(tracker.taskMicrocodeEntryObserved)
        assertFalse(tracker.onInstructionExecuted(4))
    }

    @Test
    fun acceptsFragmentsLoadedOutOfOrderButNotWithGaps() {
        val tracker = N64SpTaskMicrocodeTracker(taskDescriptor())
        assertFalse(tracker.onSpDmaCompleted(fragment(0x40, 0x40)))
        assertFalse(tracker.onInstructionExecuted(0))
        assertTrue(tracker.onSpDmaCompleted(fragment(0, 0x40)))
        assertTrue(tracker.onInstructionExecuted(0))
    }

    @Test
    fun rejectsWrongSourceAndLaterWrongDmaInvalidatesOverwrittenBytes() {
        val tracker = N64SpTaskMicrocodeTracker(taskDescriptor())
        assertFalse(tracker.onSpDmaCompleted(fragment(0, 0x80).copy(dramStartAddress = 0x4100)))
        assertFalse(tracker.onInstructionExecuted(0))
        assertTrue(tracker.onSpDmaCompleted(fragment(0, 0x80)))
        assertTrue(tracker.onInstructionExecuted(0))

        val notYetEntered = N64SpTaskMicrocodeTracker(taskDescriptor())
        assertTrue(notYetEntered.onSpDmaCompleted(fragment(0, 0x80)))
        assertFalse(notYetEntered.onSpDmaCompleted(fragment(0x20, 0x20).copy(dramStartAddress = 0x4020 + 1)))
        assertFalse(notYetEntered.onInstructionExecuted(0))
        assertTrue(notYetEntered.onSpDmaCompleted(fragment(0x20, 0x20)))
        assertTrue(notYetEntered.onInstructionExecuted(0))
    }

    @Test
    fun ignoresDmasToDmemOrFromSpAndRejectsInvalidPc() {
        val tracker = N64SpTaskMicrocodeTracker(taskDescriptor())
        assertFalse(tracker.onSpDmaCompleted(fragment(0, 0x80).copy(spStartAddress = 0)))
        assertFalse(tracker.onSpDmaCompleted(fragment(0, 0x80).copy(toSp = false)))
        assertFalse(tracker.onInstructionExecuted(0))

        assertTrue(tracker.onSpDmaCompleted(fragment(0, 0x80)))
        assertFalse(tracker.onInstructionExecuted(0x80))
        assertFalse(tracker.onInstructionExecuted(2))
        assertTrue(tracker.onInstructionExecuted(0x20))
    }

    @Test
    fun rejectsInvalidDescriptorSize() {
        val tracker = N64SpTaskMicrocodeTracker(taskDescriptor().copy(ucodeSize = 0x1001))
        assertFalse(tracker.onSpDmaCompleted(fragment(0, 0x80)))
        assertFalse(tracker.onInstructionExecuted(0))
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

    private fun fragment(offset: Int, size: Int) = N64Bus.SpDmaTransfer(
        toSp = true,
        dramStartAddress = 0x4000 + offset,
        spStartAddress = 0x1000 + offset,
        blockLength = size,
        blockCount = 1,
        dramSkip = 0
    )
}
