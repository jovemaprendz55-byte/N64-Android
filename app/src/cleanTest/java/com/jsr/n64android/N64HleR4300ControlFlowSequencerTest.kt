package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class N64HleR4300ControlFlowSequencerTest {
    private val decoder = N64HleR4300InstructionDecoder()
    private val planner = N64HleR4300ControlTransferPlanner()
    private val sequencer = N64HleR4300ControlFlowSequencer()

    @Test fun jalLinkIsWrittenBeforeDelaySlotAndTargetAfterCompletion() {
        val state = N64HleR4300CpuState(0x80001000L)
        val plan = planner.plan(decoder.decode((0x03 shl 26) or 0x800), state)
        sequencer.schedule(state, plan)
        assertEquals(0x80001004L, state.programCounter)
        assertEquals(0x80001008L, state.readGpr(31))
        assertTrue(sequencer.hasPendingDelaySlot())
        assertEquals(0x80001000L, sequencer.pendingBranchAddress())
        assertEquals(0x80001004L, sequencer.pendingDelaySlotAddress())

        // The delay-slot instruction executes while PC is still PC+4.
        state.writeGpr(2, 55L)
        sequencer.completeDelaySlot(state)
        assertEquals(0x80002000L, state.programCounter)
        assertEquals(55L, state.readGpr(2))
        assertFalse(sequencer.hasPendingDelaySlot())
        assertEquals(null, sequencer.pendingBranchAddress())
        assertEquals(null, sequencer.pendingDelaySlotAddress())
    }

    @Test fun notTakenBranchStillSchedulesAndCompletesDelaySlot() {
        val state = N64HleR4300CpuState(0x80001000L, mapOf(1 to 4L, 2 to 4L))
        val plan = planner.plan(decoder.decode((0x05 shl 26) or (1 shl 21) or (2 shl 16) or 1), state)
        sequencer.schedule(state, plan)
        assertEquals(0x80001004L, state.programCounter)
        assertEquals(0x80001000L, sequencer.pendingBranchAddress())
        sequencer.completeDelaySlot(state)
        assertEquals(0x80001008L, state.programCounter)
        assertEquals(null, sequencer.pendingBranchAddress())
    }

    @Test fun takenBneExecutesDelaySlotBeforeInstallingNegativeBranchTarget() {
        val state = N64HleR4300CpuState(
            0xFFFFFFFF8000041CUL.toLong(),
            mapOf(9 to 0x0000000000031078L)
        )
        val instruction = decoder.decode(0x1520FFFC)
        val plan = planner.plan(instruction, state)

        sequencer.schedule(state, plan)
        assertEquals(0xFFFFFFFF80000420UL.toLong(), state.programCounter)
        assertTrue(sequencer.hasPendingDelaySlot())

        // The actual delay-slot instruction runs at 0x80000420.
        sequencer.completeDelaySlot(state)
        assertEquals(0xFFFFFFFF80000410UL.toLong(), state.programCounter)
        assertFalse(sequencer.hasPendingDelaySlot())
    }

    @Test fun invalidPlanDoesNotChangePcOrLinkRegister() {
        val state = N64HleR4300CpuState(0x80001000L)
        val invalid = N64HleR4300ControlTransferPlan(
            instructionAddress = 0x80001000L,
            delaySlotAddress = 0x80001004L,
            continuationAddress = 0x80001002L,
            taken = true,
            linkRegister = 31,
            linkValue = 0x80001008L
        )
        assertThrows(IllegalArgumentException::class.java) { sequencer.schedule(state, invalid) }
        assertEquals(0x80001000L, state.programCounter)
        assertFalse(state.isGprKnown(31))
        assertFalse(sequencer.hasPendingDelaySlot())
    }

    @Test fun nestedTransferAndCompletionWithoutPendingSlotAreRejected() {
        val state = N64HleR4300CpuState(0x80001000L, mapOf(1 to 1L, 2 to 1L))
        val plan = planner.plan(decoder.decode((0x04 shl 26) or (1 shl 21) or (2 shl 16) or 1), state)
        sequencer.schedule(state, plan)
        assertThrows(IllegalStateException::class.java) { sequencer.schedule(state, plan) }
        sequencer.completeDelaySlot(state)
        assertThrows(IllegalStateException::class.java) { sequencer.completeDelaySlot(state) }
    }
}
