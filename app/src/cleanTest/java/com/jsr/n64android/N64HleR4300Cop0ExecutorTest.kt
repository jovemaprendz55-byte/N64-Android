package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class N64HleR4300Cop0ExecutorTest {
    private val stepper = N64HleR4300InstructionStepper()

    private fun cop0(rs: Int, rt: Int, rd: Int): Int =
        (0x10 shl 26) or (rs shl 21) or (rt shl 16) or (rd shl 11)

    @Test fun mfc0ReadsKnownCop0RegisterAndSignExtendsWord() {
        val state = N64HleR4300CpuState(
            0x80001000L,
            cop0 = N64HleCop0EntryState(mapOf(12 to 0x80000001L), "explicit test state")
        )
        stepper.step(cop0(0, 2, 12), state) // MFC0 $2,$12
        assertEquals(0xFFFFFFFF80000001UL.toLong(), state.readGpr(2))
        assertEquals(0x80001004L, state.programCounter)
    }

    @Test fun mtc0WritesLowThirtyTwoBitsFromGpr() {
        val state = N64HleR4300CpuState(
            0x80001000L,
            initialGprValues = mapOf(1 to 0x1234567880000002UL.toLong())
        )
        stepper.step(cop0(4, 1, 12), state) // MTC0 $1,$12
        assertEquals(0xFFFFFFFF80000002UL.toLong(), state.readCop0(12))
        assertEquals(0xFFFFFFFF80000002UL.toLong(), state.copyKnownCop0()[12])
    }

    @Test fun mfc0UnknownRegisterFailsWithoutChangingGprOrPc() {
        val state = N64HleR4300CpuState(
            0x80001000L,
            initialGprValues = mapOf(2 to 99L)
        )
        assertThrows(IllegalStateException::class.java) {
            stepper.step(cop0(0, 2, 12), state)
        }
        assertEquals(99L, state.readGpr(2))
        assertEquals(0x80001000L, state.programCounter)
    }

    @Test fun mtc0UnknownSourceFailsWithoutChangingCop0() {
        val state = N64HleR4300CpuState(
            0x80001000L,
            cop0 = N64HleCop0EntryState(mapOf(12 to 7L), "explicit test state")
        )
        assertThrows(IllegalStateException::class.java) {
            stepper.step(cop0(4, 1, 12), state)
        }
        assertEquals(7L, state.readCop0(12))
        assertEquals(0x80001000L, state.programCounter)
    }

    @Test fun mfc0CanExecuteInDelaySlot() {
        val state = N64HleR4300CpuState(
            0x80001000L,
            cop0 = N64HleCop0EntryState(mapOf(12 to 0x1234L), "explicit test state")
        )
        stepper.step((0x02 shl 26) or 0x800, state)
        stepper.step(cop0(0, 2, 12), state)
        assertEquals(0x1234L, state.readGpr(2))
        assertEquals(0x80002000L, state.programCounter)
        assertEquals(false, stepper.hasPendingDelaySlot())
    }
}
