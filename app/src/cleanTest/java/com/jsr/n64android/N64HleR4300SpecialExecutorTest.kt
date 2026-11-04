package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class N64HleR4300SpecialExecutorTest {
    private val decoder = N64HleR4300InstructionDecoder()
    private val executor = N64HleR4300SpecialExecutor()

    private fun r(rs: Int, rt: Int, rd: Int, shamt: Int = 0, funct: Int): N64HleR4300DecodedInstruction =
        decoder.decode((rs shl 21) or (rt shl 16) or (rd shl 11) or (shamt shl 6) or funct)

    private fun state(a: Long, b: Long) = N64HleR4300CpuState(
        programCounter = 0x80001000L,
        initialGprValues = mapOf(1 to a, 2 to b)
    )

    @Test fun adduWrapsAsSignedExtendedWordWithoutOverflowException() {
        val s = state(0x7FFFFFFFL, 1L)
        executor.execute(r(1, 2, 3, funct = 0x21), s)
        assertEquals(0xFFFFFFFF80000000UL.toLong(), s.readGpr(3))
    }

    @Test fun subuSignExtendsWordResult() {
        val s = state(0L, 1L)
        executor.execute(r(1, 2, 3, funct = 0x23), s)
        assertEquals(-1L, s.readGpr(3))
    }

    @Test fun logicalOperationsUseAllSixtyFourBits() {
        val s = state(0xFFFF0000FFFF0000UL.toLong(), 0x00FF00FF00FF00FFL)
        executor.execute(r(1, 2, 3, funct = 0x24), s)
        assertEquals(0x00FF000000FF0000L, s.readGpr(3))
        executor.execute(r(1, 2, 4, funct = 0x27), s)
        assertEquals(0x0000FF000000FF00L, s.readGpr(4))
    }

    @Test fun signedAndUnsignedComparisonsDifferAtSignBit() {
        val s = state(Long.MIN_VALUE, 1L)
        executor.execute(r(1, 2, 3, funct = 0x2A), s)
        executor.execute(r(1, 2, 4, funct = 0x2B), s)
        assertEquals(1L, s.readGpr(3))
        assertEquals(0L, s.readGpr(4))
    }

    @Test fun doublewordArithmeticWrapsAcrossSixtyFourBits() {
        val s = state(Long.MAX_VALUE, 1L)
        executor.execute(r(1, 2, 3, funct = 0x2D), s)
        assertEquals(Long.MIN_VALUE, s.readGpr(3))
        executor.execute(r(1, 2, 4, funct = 0x2F), s)
        assertEquals(Long.MAX_VALUE - 1L, s.readGpr(4))
    }

    @Test fun wordShiftsSignExtendTheirThirtyTwoBitResult() {
        val s = state(0L, 0x80000000L)
        executor.execute(r(0, 2, 3, 1, 0x00), s)
        assertEquals(0xFFFFFFFF00000000UL.toLong(), s.readGpr(3))
        executor.execute(r(0, 2, 4, 1, 0x02), s)
        assertEquals(0x40000000L, s.readGpr(4))
        executor.execute(r(0, 2, 5, 1, 0x03), s)
        assertEquals(0xFFFFFFFFC0000000UL.toLong(), s.readGpr(5))
    }

    @Test fun variableWordShiftMasksAmountToFiveBits() {
        val s = state(0x100000021L, 1L)
        executor.execute(r(1, 2, 3, funct = 0x04), s)
        assertEquals(2L, s.readGpr(3))
    }

    @Test fun doublewordShiftUsesSixBitRange() {
        val s = state(0L, 1L)
        executor.execute(r(0, 2, 3, 0, 0x38), s)
        assertEquals(1L, s.readGpr(3))
        executor.execute(r(0, 2, 4, 0, 0x3C), s)
        assertEquals(0x100000000L, s.readGpr(4))
    }

    @Test fun writesToZeroRegisterAreDiscarded() {
        val s = state(10L, 20L)
        executor.execute(r(1, 2, 0, funct = 0x21), s)
        assertEquals(0L, s.readGpr(0))
        assertEquals(true, s.isGprKnown(0))
    }

    @Test fun unknownOperandFailsWithoutChangingDestinationOrPc() {
        val s = N64HleR4300CpuState(0x80001000L, initialGprValues = mapOf(2 to 7L, 3 to 99L))
        assertThrows(IllegalStateException::class.java) {
            executor.execute(r(1, 2, 3, funct = 0x21), s)
        }
        assertEquals(99L, s.readGpr(3))
        assertEquals(0x80001000L, s.programCounter)
    }

    @Test fun unsupportedInstructionDoesNotMutateState() {
        val s = state(4L, 5L)
        assertThrows(UnsupportedOperationException::class.java) {
            executor.execute(r(1, 2, 3, funct = 0x08), s) // JR
        }
        assertEquals(false, s.isGprKnown(3))
    }

    @Test fun nonSpecialInstructionIsRejected() {
        val s = state(4L, 5L)
        assertThrows(IllegalArgumentException::class.java) {
            executor.execute(decoder.decode(0x08000000), s)
        }
    }
}
