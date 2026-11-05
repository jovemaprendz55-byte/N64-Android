package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class N64HleR4300ImmediateExecutorTest {
    private val stepper = N64HleR4300InstructionStepper()

    private fun i(op: Int, rs: Int, rt: Int, imm: Int): Int =
        (op shl 26) or (rs shl 21) or (rt shl 16) or (imm and 0xFFFF)

    @Test fun addiuUsesSignedImmediateAndSignExtendsWordResult() {
        val state = N64HleR4300CpuState(0x80001000L, mapOf(1 to 10L))
        stepper.step(i(0x09, 1, 2, 0xFFFF), state)
        assertEquals(9L, state.readGpr(2))
        assertEquals(0x80001004L, state.programCounter)
    }

    @Test fun addiRejectsSignedOverflowWithoutMutatingStateOrPc() {
        val state = N64HleR4300CpuState(0x80001000L, mapOf(1 to Int.MAX_VALUE.toLong()))
        assertThrows(N64HleR4300ArchitecturalException::class.java) {
            stepper.step(i(0x08, 1, 2, 1), state)
        }
        assertEquals(false, state.isGprKnown(2))
        assertEquals(0x80001000L, state.programCounter)
    }

    @Test fun logicalImmediatesAreZeroExtended() {
        val state = N64HleR4300CpuState(0x80001000L, mapOf(1 to 0x1234_FFFF_FFFF_0000L))
        stepper.step(i(0x0C, 1, 2, 0xFFFF), state) // ANDI
        assertEquals(0L, state.readGpr(2))
        stepper.step(i(0x0D, 1, 3, 0x8001), state) // ORI
        assertEquals(0x1234_FFFF_FFFF_8001L, state.readGpr(3))
        stepper.step(i(0x0E, 1, 4, 0x00FF), state) // XORI
        assertEquals(0x1234_FFFF_FFFF_00FFL, state.readGpr(4))
    }

    @Test fun sltiAndSltiuUseSignedAndUnsignedComparisons() {
        val state = N64HleR4300CpuState(
            0x80001000L,
            mapOf(1 to -1L, 2 to Long.MAX_VALUE)
        )
        stepper.step(i(0x0A, 1, 3, 1), state)
        assertEquals(1L, state.readGpr(3))
        stepper.step(i(0x0B, 2, 4, -1), state)
        assertEquals(1L, state.readGpr(4))
    }

    @Test fun luiSignExtendsTheThirtyTwoBitResult() {
        val state = N64HleR4300CpuState(0x80001000L)
        stepper.step(i(0x0F, 0, 5, 0x8001), state)
        assertEquals(0xFFFF_FFFF_8001_0000UL.toLong(), state.readGpr(5))
    }

    @Test fun immediateInstructionCanExecuteInDelaySlot() {
        val state = N64HleR4300CpuState(0x80001000L, mapOf(1 to 7L))
        stepper.step((0x02 shl 26) or 0x800, state) // J 0x80002000
        stepper.step(i(0x09, 1, 2, 5), state) // ADDIU in delay slot
        assertEquals(12L, state.readGpr(2))
        assertEquals(0x80002000L, state.programCounter)
        assertEquals(false, stepper.hasPendingDelaySlot())
    }
    @Test fun daddiuUsesSignExtendedImmediateAcrossSixtyFourBits() {
        val state = N64HleR4300CpuState(0x80001000L, mapOf(1 to 0x1_0000_0000L))
        stepper.step(i(0x19, 1, 2, -1), state)
        assertEquals(0xFFFF_FFFFL, state.readGpr(2))
        assertEquals(0x80001004L, state.programCounter)
    }

    @Test fun daddiExecutesAndRejectsSignedOverflowWithoutMutation() {
        val normal = N64HleR4300CpuState(0x80001000L, mapOf(1 to 100L))
        stepper.step(i(0x18, 1, 2, -7), normal)
        assertEquals(93L, normal.readGpr(2))

        val overflow = N64HleR4300CpuState(0x80001000L, mapOf(1 to Long.MAX_VALUE))
        assertThrows(N64HleR4300ArchitecturalException::class.java) {
            stepper.step(i(0x18, 1, 2, 1), overflow)
        }
        assertEquals(false, overflow.isGprKnown(2))
        assertEquals(0x80001000L, overflow.programCounter)
    }

}
