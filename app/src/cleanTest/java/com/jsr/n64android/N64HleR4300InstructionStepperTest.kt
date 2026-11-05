package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class N64HleR4300InstructionStepperTest {
    private val stepper = N64HleR4300InstructionStepper()

    private fun r(rs: Int, rt: Int, rd: Int, funct: Int): Int =
        (rs shl 21) or (rt shl 16) or (rd shl 11) or funct

    @Test fun ordinarySpecialInstructionExecutesAndAdvancesPcByFour() {
        val state = N64HleR4300CpuState(0x80001000L, mapOf(1 to 20L, 2 to 22L))
        stepper.step(r(1, 2, 3, 0x21), state) // ADDU
        assertEquals(42L, state.readGpr(3))
        assertEquals(0x80001004L, state.programCounter)
        assertFalse(stepper.hasPendingDelaySlot())
    }

    @Test fun jalThenSpecialDelaySlotThenJumpTarget() {
        val state = N64HleR4300CpuState(0x80001000L)
        stepper.step((0x03 shl 26) or 0x800, state) // JAL 0x80002000
        assertEquals(0x80001004L, state.programCounter)
        assertEquals(0x80001008L, state.readGpr(31))
        assertTrue(stepper.hasPendingDelaySlot())

        stepper.step(r(0, 0, 2, 0x00), state) // SLL $2,$0,0 (NOP)
        assertEquals(0x80002000L, state.programCounter)
        assertFalse(stepper.hasPendingDelaySlot())
    }

    @Test fun notTakenBranchStillExecutesOneDelaySlot() {
        val state = N64HleR4300CpuState(0x80001000L, mapOf(1 to 5L, 2 to 5L))
        stepper.step((0x05 shl 26) or (1 shl 21) or (2 shl 16) or 4, state) // BNE not taken
        assertEquals(0x80001004L, state.programCounter)
        stepper.step(r(1, 2, 3, 0x21), state) // ADDU in delay slot
        assertEquals(0x80001008L, state.programCounter)
        assertEquals(10L, state.readGpr(3))
    }

    @Test fun unsupportedInstructionDoesNotAdvancePc() {
        val state = N64HleR4300CpuState(0x80001000L)
        assertThrows(IllegalArgumentException::class.java) {
            stepper.step(0xC4010000.toInt(), state) // LWC1 is not implemented yet
        }
        assertEquals(0x80001000L, state.programCounter)
    }

    @Test fun controlTransferInDelaySlotIsRejectedWithoutLosingPendingSlot() {
        val state = N64HleR4300CpuState(0x80001000L, mapOf(1 to 0x80003000L))
        stepper.step((0x02 shl 26) or 0x800, state) // J
        assertThrows(IllegalStateException::class.java) {
            stepper.step((1 shl 21) or 0x08, state) // JR in delay slot
        }
        assertEquals(0x80001004L, state.programCounter)
        assertTrue(stepper.hasPendingDelaySlot())
    }

    @Test fun unsupportedDelaySlotInstructionDoesNotCompleteTransfer() {
        val state = N64HleR4300CpuState(0x80001000L)
        stepper.step((0x02 shl 26) or 0x800, state)
        assertThrows(IllegalArgumentException::class.java) {
            stepper.step(0xC4010000.toInt(), state)
        }
        assertEquals(0x80001004L, state.programCounter)
        assertTrue(stepper.hasPendingDelaySlot())
    }

    @Test fun goldenEyeTakenBneExecutesDelaySlotThenBranchesToNegativeTarget() {
        val state = N64HleR4300CpuState(
            0xFFFFFFFF8000041CUL.toLong(),
            mapOf(9 to 0x0000000000031078L)
        )
        val goldenEyeBne = 0x1520FFFC

        stepper.step(goldenEyeBne, state)
        assertEquals(0xFFFFFFFF80000420UL.toLong(), state.programCounter)
        assertTrue(stepper.hasPendingDelaySlot())

        stepper.step(0x00000000, state) // NOP in the delay slot
        assertEquals(0xFFFFFFFF80000410UL.toLong(), state.programCounter)
        assertFalse(stepper.hasPendingDelaySlot())
    }


    @Test fun bgezalTakenWritesReturnAddressBeforeDelaySlotAndBranches() {
        val state = N64HleR4300CpuState(0x80001000L, mapOf(1 to 0L))
        val bgezal = (0x01 shl 26) or (1 shl 21) or (0x11 shl 16) or 4

        stepper.step(bgezal, state)
        assertEquals(0x80001004L, state.programCounter)
        assertEquals(0x80001008L, state.readGpr(31))
        assertTrue(stepper.hasPendingDelaySlot())

        stepper.step(0, state) // NOP delay slot
        assertEquals(0x80001014L, state.programCounter)
        assertFalse(stepper.hasPendingDelaySlot())
    }

    @Test fun bltzalNotTakenDoesNotWriteReturnAddressAndFallsThrough() {
        val state = N64HleR4300CpuState(0x80001000L, mapOf(1 to 1L))
        val bltzal = (0x01 shl 26) or (1 shl 21) or (0x10 shl 16) or 4

        stepper.step(bltzal, state)
        assertFalse(state.isGprKnown(31))
        assertEquals(0x80001004L, state.programCounter)
        assertTrue(stepper.hasPendingDelaySlot())

        stepper.step(0, state) // NOP delay slot
        assertEquals(0x80001008L, state.programCounter)
        assertFalse(stepper.hasPendingDelaySlot())
        assertFalse(state.isGprKnown(31))
    }

    @Test fun blezAndBgtzUseSignedRegisterValuesAndExecuteDelaySlots() {
        val blezState = N64HleR4300CpuState(0x80001000L, mapOf(1 to Long.MIN_VALUE))
        val blez = (0x06 shl 26) or (1 shl 21) or 2
        stepper.step(blez, blezState)
        stepper.step(0, blezState)
        assertEquals(0x8000100CL, blezState.programCounter)

        val bgtzState = N64HleR4300CpuState(0x80002000L, mapOf(1 to Long.MAX_VALUE))
        val bgtz = (0x07 shl 26) or (1 shl 21) or 3
        stepper.step(bgtz, bgtzState)
        stepper.step(0, bgtzState)
        assertEquals(0x80002010L, bgtzState.programCounter)
    }

}
