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
        val leftShiftState = state(0L, 0x40000000L)
        executor.execute(r(0, 2, 3, 1, 0x00), leftShiftState)
        assertEquals(0xFFFFFFFF80000000UL.toLong(), leftShiftState.readGpr(3))

        val logicalRightShiftState = state(0L, 0x80000000L)
        executor.execute(r(0, 2, 4, 1, 0x02), logicalRightShiftState)
        assertEquals(0x40000000L, logicalRightShiftState.readGpr(4))

        val arithmeticRightShiftState = state(0L, 0x80000000L)
        executor.execute(r(0, 2, 5, 1, 0x03), arithmeticRightShiftState)
        assertEquals(0xFFFFFFFFC0000000UL.toLong(), arithmeticRightShiftState.readGpr(5))
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

    @Test fun moveFromHiAndLoCopiesTheirKnownValuesToGprs() {
        val s = N64HleR4300CpuState(
            programCounter = 0x80001000L,
            initialHi = 0x1122334455667788L,
            initialLo = 0x7766554433221100L
        )
        executor.execute(r(0, 0, 3, funct = 0x10), s) // MFHI
        executor.execute(r(0, 0, 4, funct = 0x12), s) // MFLO
        assertEquals(0x1122334455667788L, s.readGpr(3))
        assertEquals(0x7766554433221100L, s.readGpr(4))
    }

    @Test fun moveToHiAndLoCopiesGprValues() {
        val s = state(0x123456789ABCDEFL, 0x0FEDCBA987654321L)
        executor.execute(r(1, 0, 0, funct = 0x11), s) // MTHI
        executor.execute(r(2, 0, 0, funct = 0x13), s) // MTLO
        assertEquals(0x123456789ABCDEFL, s.readHi())
        assertEquals(0x0FEDCBA987654321L, s.readLo())
    }

    @Test fun moveFromUnknownHiFailsWithoutChangingDestination() {
        val s = state(1L, 2L)
        s.writeGpr(3, 99L)
        assertThrows(IllegalStateException::class.java) {
            executor.execute(r(0, 0, 3, funct = 0x10), s)
        }
        assertEquals(99L, s.readGpr(3))
        assertEquals(false, s.isHiKnown())
    }

    @Test fun moveToHiWithUnknownSourceDoesNotChangeHi() {
        val s = N64HleR4300CpuState(0x80001000L, initialHi = 7L)
        assertThrows(IllegalStateException::class.java) {
            executor.execute(r(1, 0, 0, funct = 0x11), s)
        }
        assertEquals(7L, s.readHi())
    }

    @Test fun signedWordMultiplyWritesSignExtendedHiAndLo() {
        val s = state(-2L, 3L)
        executor.execute(r(1, 2, 0, funct = 0x18), s) // MULT
        assertEquals(-1L, s.readHi())
        assertEquals(-6L, s.readLo())
    }

    @Test fun unsignedWordMultiplyUsesLowWordsAndSignExtendsHiLo() {
        val s = state(0xFFFFFFFFL.toInt().toLong(), 2L)
        executor.execute(r(1, 2, 0, funct = 0x19), s) // MULTU
        assertEquals(1L, s.readHi())
        assertEquals(-2L, s.readLo())
    }

    @Test fun wordMultiplyUsesLowThirtyTwoBitsOfEachGpr() {
        val signed = N64HleR4300CpuState(
            programCounter = 0x80001000L,
            initialGprValues = mapOf(1 to 0x0000000180000000L, 2 to 2L)
        )
        executor.execute(r(1, 2, 0, funct = 0x18), signed) // MULT
        assertEquals(-1L, signed.readHi())
        assertEquals(0L, signed.readLo())

        val unsigned = N64HleR4300CpuState(
            programCounter = 0x80001000L,
            initialGprValues = mapOf(1 to 0x0000000180000000L, 2 to 2L)
        )
        executor.execute(r(1, 2, 0, funct = 0x19), unsigned) // MULTU
        assertEquals(1L, unsigned.readHi())
        assertEquals(0L, unsigned.readLo())
    }

    @Test fun signedDoublewordMultiplySplits128BitProduct() {
        val s = state(Long.MIN_VALUE, 2L)
        executor.execute(r(1, 2, 0, funct = 0x1C), s) // DMULT
        assertEquals(-1L, s.readHi())
        assertEquals(0L, s.readLo())
    }

    @Test fun unsignedDoublewordMultiplySplits128BitProduct() {
        val s = state(-1L, 2L)
        executor.execute(r(1, 2, 0, funct = 0x1D), s) // DMULTU
        assertEquals(1L, s.readHi())
        assertEquals(-2L, s.readLo())
    }

    @Test fun signedWordDivisionTruncatesTowardZeroAndStoresRemainderInHi() {
        val s = state(-7L, 3L)
        executor.execute(r(1, 2, 0, funct = 0x1A), s) // DIV
        assertEquals(-1L, s.readHi())
        assertEquals(-2L, s.readLo())
    }

    @Test fun unsignedWordDivisionUsesLowWordsAndUnsignedInterpretation() {
        val s = state(0x00000000FFFFFFFFL, 2L)
        executor.execute(r(1, 2, 0, funct = 0x1B), s) // DIVU
        assertEquals(1L, s.readHi())
        assertEquals(0x7FFFFFFFL, s.readLo())
    }

    @Test fun doublewordSignedDivisionHandlesNegativeRemainder() {
        val s = state(-17L, 5L)
        executor.execute(r(1, 2, 0, funct = 0x1E), s) // DDIV
        assertEquals(-2L, s.readHi())
        assertEquals(-3L, s.readLo())
    }

    @Test fun doublewordUnsignedDivisionUsesFullUnsignedRange() {
        val s = state(-1L, 2L)
        executor.execute(r(1, 2, 0, funct = 0x1F), s) // DDIVU
        assertEquals(1L, s.readHi())
        assertEquals(Long.MAX_VALUE, s.readLo())
    }

    @Test fun wordDivisionByZeroPreservesPreviouslyKnownHiLo() {
        val s = N64HleR4300CpuState(0x80001000L, mapOf(1 to 10L, 2 to 0L), initialHi = 7L, initialLo = 9L)
        assertThrows(IllegalStateException::class.java) {
            executor.execute(r(1, 2, 0, funct = 0x1A), s)
        }
        assertEquals(7L, s.readHi())
        assertEquals(9L, s.readLo())
    }

    @Test fun signedWordDivisionOverflowPreservesPreviouslyKnownHiLo() {
        val s = N64HleR4300CpuState(
            0x80001000L,
            mapOf(1 to Int.MIN_VALUE.toLong(), 2 to -1L),
            initialHi = 7L,
            initialLo = 9L
        )
        assertThrows(IllegalStateException::class.java) {
            executor.execute(r(1, 2, 0, funct = 0x1A), s)
        }
        assertEquals(7L, s.readHi())
        assertEquals(9L, s.readLo())
    }

    @Test fun doublewordDivisionByZeroPreservesPreviouslyKnownHiLo() {
        val s = N64HleR4300CpuState(0x80001000L, mapOf(1 to 10L, 2 to 0L), initialHi = 7L, initialLo = 9L)
        assertThrows(IllegalStateException::class.java) {
            executor.execute(r(1, 2, 0, funct = 0x1E), s)
        }
        assertEquals(7L, s.readHi())
        assertEquals(9L, s.readLo())
    }

    @Test fun doublewordSignedDivisionOverflowPreservesPreviouslyKnownHiLo() {
        val s = N64HleR4300CpuState(
            0x80001000L,
            mapOf(1 to Long.MIN_VALUE, 2 to -1L),
            initialHi = 7L,
            initialLo = 9L
        )
        assertThrows(IllegalStateException::class.java) {
            executor.execute(r(1, 2, 0, funct = 0x1E), s)
        }
        assertEquals(7L, s.readHi())
        assertEquals(9L, s.readLo())
    }

    @Test fun addAndSubExecuteSignedWordArithmetic() {
        val addState = state(20L, 22L)
        executor.execute(r(1, 2, 3, funct = 0x20), addState) // ADD
        assertEquals(42L, addState.readGpr(3))

        val subState = state(-20L, 5L)
        executor.execute(r(1, 2, 3, funct = 0x22), subState) // SUB
        assertEquals(-25L, subState.readGpr(3))
    }

    @Test fun addWordOverflowIsRejectedWithoutMutation() {
        val s = state(Int.MAX_VALUE.toLong(), 1L)
        assertThrows(N64HleR4300ArithmeticOverflow::class.java) {
            executor.execute(r(1, 2, 3, funct = 0x20), s)
        }
        assertEquals(false, s.isGprKnown(3))
        assertEquals(0x80001000L, s.programCounter)
    }

    @Test fun subWordOverflowIsRejectedWithoutMutation() {
        val s = state(Int.MIN_VALUE.toLong(), 1L)
        assertThrows(N64HleR4300ArithmeticOverflow::class.java) {
            executor.execute(r(1, 2, 3, funct = 0x22), s)
        }
        assertEquals(false, s.isGprKnown(3))
    }

    @Test fun daddAndDsubExecuteSignedDoublewordArithmetic() {
        val addState = state(100L, 23L)
        executor.execute(r(1, 2, 3, funct = 0x2C), addState) // DADD
        assertEquals(123L, addState.readGpr(3))

        val subState = state(-100L, 23L)
        executor.execute(r(1, 2, 3, funct = 0x2E), subState) // DSUB
        assertEquals(-123L, subState.readGpr(3))
    }

    @Test fun doublewordSignedOverflowIsRejectedWithoutMutation() {
        val addState = state(Long.MAX_VALUE, 1L)
        assertThrows(N64HleR4300ArithmeticOverflow::class.java) {
            executor.execute(r(1, 2, 3, funct = 0x2C), addState)
        }
        assertEquals(false, addState.isGprKnown(3))

        val subState = state(Long.MIN_VALUE, 1L)
        assertThrows(N64HleR4300ArithmeticOverflow::class.java) {
            executor.execute(r(1, 2, 3, funct = 0x2E), subState)
        }
        assertEquals(false, subState.isGprKnown(3))
    }

}
