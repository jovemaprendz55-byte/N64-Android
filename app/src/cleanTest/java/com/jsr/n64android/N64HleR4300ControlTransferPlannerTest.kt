package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class N64HleR4300ControlTransferPlannerTest {
    private val decoder = N64HleR4300InstructionDecoder()
    private val planner = N64HleR4300ControlTransferPlanner()

    private fun i(op: Int, rs: Int, rt: Int, imm: Int): N64HleR4300DecodedInstruction =
        decoder.decode((op shl 26) or (rs shl 21) or (rt shl 16) or (imm and 0xFFFF))

    @Test fun beqTakenUsesPcRelativeTargetAfterDelaySlot() {
        val state = N64HleR4300CpuState(0x80001000L, mapOf(1 to 7L, 2 to 7L))
        val plan = planner.plan(i(0x04, 1, 2, 3), state)
        assertEquals(0x80001004L, plan.delaySlotAddress)
        assertEquals(0x80001010L, plan.continuationAddress)
        assertEquals(true, plan.taken)
        assertEquals(0x80001000L, state.programCounter)
    }

    @Test fun bneNotTakenStillPreservesDelaySlot() {
        val state = N64HleR4300CpuState(0x80001000L, mapOf(1 to 7L, 2 to 7L))
        val plan = planner.plan(i(0x05, 1, 2, -2), state)
        assertEquals(0x80001004L, plan.delaySlotAddress)
        assertEquals(0x80001008L, plan.continuationAddress)
        assertEquals(false, plan.taken)
    }

    @Test fun blezAndBgtzUseSignedComparison() {
        val negative = N64HleR4300CpuState(0x80001000L, mapOf(1 to Long.MIN_VALUE))
        val positive = N64HleR4300CpuState(0x80001000L, mapOf(1 to 1L))
        assertEquals(true, planner.plan(i(0x06, 1, 0, 0), negative).taken)
        assertEquals(true, planner.plan(i(0x07, 1, 0, 0), positive).taken)
    }

    @Test fun jumpUsesUpperBitsOfPcPlusFourAndSignExtendsAddress() {
        val state = N64HleR4300CpuState(0x0FFFFFFCL)
        val plan = planner.plan(decoder.decode((0x02 shl 26) or 0x00001234), state)
        assertEquals(0x10000000L, plan.delaySlotAddress)
        assertEquals(0x100048D0L, plan.continuationAddress)
    }

    @Test fun jalReturnsLinkRegisterAndPcPlusEightWithoutMutatingState() {
        val state = N64HleR4300CpuState(0x80001000L)
        val plan = planner.plan(decoder.decode((0x03 shl 26) or 0x00000400), state)
        assertEquals(31, plan.linkRegister)
        assertEquals(0x80001008L, plan.linkValue)
        assertEquals(false, state.isGprKnown(31))
    }

    @Test fun jrUsesRegisterTargetAndRejectsMisalignment() {
        val state = N64HleR4300CpuState(0x80001000L, mapOf(4 to 0xFFFFFFFF80002000UL.toLong()))
        assertEquals(0xFFFFFFFF80002000UL.toLong(), planner.plan(
            decoder.decode((4 shl 21) or 0x08), state
        ).continuationAddress)
        val bad = N64HleR4300CpuState(0x80001000L, mapOf(4 to 0x80002002L))
        assertThrows(IllegalArgumentException::class.java) {
            planner.plan(decoder.decode((4 shl 21) or 0x08), bad)
        }
    }

    @Test fun jalrReturnsEncodedDestinationAndLink() {
        val state = N64HleR4300CpuState(0x80001000L, mapOf(4 to 0x80002000L))
        val plan = planner.plan(decoder.decode((4 shl 21) or (5 shl 11) or 0x09), state)
        assertEquals(0x80002000L, plan.continuationAddress)
        assertEquals(5, plan.linkRegister)
        assertEquals(0x80001008L, plan.linkValue)
    }

    @Test fun blezRejectsNonzeroRtAndUnsupportedOpcodesFail() {
        val state = N64HleR4300CpuState(0x80001000L, mapOf(1 to 0L))
        assertThrows(IllegalArgumentException::class.java) {
            planner.plan(i(0x06, 1, 1, 0), state)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            planner.plan(i(0x08, 1, 2, 1), state)
        }
    }

    @Test fun bltzAndBgezUseSignedRegisterValue() {
        val negative = N64HleR4300CpuState(0x80001000L, mapOf(1 to -1L))
        val nonNegative = N64HleR4300CpuState(0x80001000L, mapOf(1 to 0L))
        assertEquals(true, planner.plan(i(0x01, 1, 0x00, 1), negative).taken)
        assertEquals(true, planner.plan(i(0x01, 1, 0x01, 1), nonNegative).taken)
    }

    @Test fun branchAndLinkWritesLinkOnlyWhenConditionIsTaken() {
        val taken = N64HleR4300CpuState(0x80001000L, mapOf(1 to 0L))
        val notTaken = N64HleR4300CpuState(0x80001000L, mapOf(1 to -1L))
        val takenPlan = planner.plan(i(0x01, 1, 0x11, 2), taken) // BGEZAL
        val notTakenPlan = planner.plan(i(0x01, 1, 0x11, 2), notTaken)
        assertEquals(31, takenPlan.linkRegister)
        assertEquals(0x80001008L, takenPlan.linkValue)
        assertEquals(null, notTakenPlan.linkRegister)
        assertEquals(null, notTakenPlan.linkValue)
    }


    @Test fun jumpPreservesSignExtendedPcRepresentation() {
        val state = N64HleR4300CpuState(0xFFFFFFFF80001000UL.toLong())
        val plan = planner.plan(decoder.decode((0x02 shl 26) or 0x800), state)
        assertEquals(0xFFFFFFFF80002000UL.toLong(), plan.continuationAddress)
    }

}
