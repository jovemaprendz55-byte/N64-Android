package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Test

class R4300Cop1ArithmeticTest {
    private fun cop1(fmt: Int, ft: Int, fs: Int, fd: Int, fn: Int): Int =
        (0x11 shl 26) or (fmt shl 21) or (ft shl 16) or (fs shl 11) or (fd shl 6) or fn

    @Test fun cvtSingleFromDoubleAndCvtDoubleFromSingleConvertFormats() {
        val memory = N64Memory(64)
        memory.write32(0, cop1(0x11, 0, 1, 2, 0x20)) // CVT.S.D f2, f1
        memory.write32(4, cop1(0x10, 0, 3, 4, 0x21)) // CVT.D.S f4, f3
        val cpu = R4300Cpu(memory)
        cpu.cop0[12] = (1L shl 29) or (1L shl 26)
        cpu.fpr[1] = java.lang.Double.doubleToRawLongBits(1.25)
        cpu.fpr[3] = java.lang.Float.floatToRawIntBits(2.5f).toLong()
        cpu.setProgramCounter(0)

        cpu.step()
        assertEquals(1.25f, java.lang.Float.intBitsToFloat(cpu.fpr[2].toInt()), 0.0f)
        cpu.step()
        assertEquals(2.5, java.lang.Double.longBitsToDouble(cpu.fpr[4]), 0.0)
    }

    @Test fun cvtWordUsesFcr31RoundingModeAndRoundWordUsesTiesToEven() {
        val memory = N64Memory(64)
        memory.write32(0, cop1(0x10, 0, 1, 2, 0x24)) // CVT.W.S f2, f1
        memory.write32(4, cop1(0x10, 0, 3, 4, 0x0C)) // ROUND.W.S f4, f3
        val cpu = R4300Cpu(memory)
        cpu.cop0[12] = 1L shl 29
        cpu.fcr[31] = 2L // CVT.W uses round toward +infinity.
        cpu.fpr[1] = java.lang.Float.floatToRawIntBits(1.2f).toLong()
        cpu.fpr[3] = java.lang.Float.floatToRawIntBits(2.5f).toLong()
        cpu.setProgramCounter(0)

        cpu.step()
        assertEquals(2L, cpu.fpr[2])
        cpu.step()
        assertEquals(2L, cpu.fpr[4]) // nearest-even: 2.5 -> 2.
    }

    @Test fun truncWordRoundsTowardZeroForNegativeValues() {
        val memory = N64Memory(64)
        memory.write32(0, cop1(0x10, 0, 1, 2, 0x0D)) // TRUNC.W.S f2, f1
        val cpu = R4300Cpu(memory)
        cpu.cop0[12] = 1L shl 29
        cpu.fpr[1] = java.lang.Float.floatToRawIntBits(-1.9f).toLong()
        cpu.setProgramCounter(0)

        cpu.step()

        assertEquals(-1L, cpu.fpr[2].toInt().toLong())
    }

    @Test fun cvtLongAndCvtSingleFromWordUseSignedIntegerFormats() {
        val memory = N64Memory(64)
        memory.write32(0, cop1(0x10, 0, 1, 2, 0x25)) // CVT.L.S f2, f1
        memory.write32(4, cop1(0x14, 0, 3, 4, 0x20)) // CVT.S.W f4, f3
        val cpu = R4300Cpu(memory)
        cpu.cop0[12] = (1L shl 29) or (1L shl 26)
        cpu.fpr[1] = java.lang.Float.floatToRawIntBits(123.75f).toLong()
        cpu.fpr[3] = (-7).toLong() and 0xFFFFFFFFL
        cpu.setProgramCounter(0)

        cpu.step()
        assertEquals(124L, cpu.fpr[2])
        cpu.step()
        assertEquals(-7.0f, java.lang.Float.intBitsToFloat(cpu.fpr[4].toInt()), 0.0f)
    }

    @Test fun cvtDoubleFromLongUsesFr0RegisterPair() {
        val memory = N64Memory(64)
        memory.write32(0, cop1(0x15, 0, 2, 4, 0x21)) // CVT.D.L f4, f2
        val cpu = R4300Cpu(memory)
        cpu.cop0[12] = 1L shl 29 // FR=0, CU1=1
        val source = 1234567890123L
        cpu.fpr[2] = (source ushr 32) and 0xFFFFFFFFL
        cpu.fpr[3] = source and 0xFFFFFFFFL
        cpu.setProgramCounter(0)

        cpu.step()

        val resultBits = ((cpu.fpr[4] and 0xFFFFFFFFL) shl 32) or (cpu.fpr[5] and 0xFFFFFFFFL)
        assertEquals(source.toDouble(), java.lang.Double.longBitsToDouble(resultBits), 0.0)
    }

    @Test fun cvtLongUsesFcr31RoundingMode() {
        val memory = N64Memory(64)
        memory.write32(0, cop1(0x10, 0, 1, 2, 0x25)) // CVT.L.S f2, f1
        val cpu = R4300Cpu(memory)
        cpu.cop0[12] = (1L shl 29) or (1L shl 26)
        cpu.fcr[31] = 3L // toward -infinity
        cpu.fpr[1] = java.lang.Float.floatToRawIntBits(-2.2f).toLong()
        cpu.setProgramCounter(0)

        cpu.step()

        assertEquals(-3L, cpu.fpr[2])
    }

    @Test fun singlePrecisionAddAndMultiplyUseRawFprBits() {
        val memory = N64Memory(64)
        memory.write32(0, cop1(0x10, 2, 1, 3, 0x00)) // ADD.S f3, f1, f2
        memory.write32(4, cop1(0x10, 2, 3, 4, 0x02)) // MUL.S f4, f3, f2
        val cpu = R4300Cpu(memory)
        cpu.cop0[12] = 1L shl 29
        cpu.fpr[1] = java.lang.Float.floatToRawIntBits(1.5f).toLong()
        cpu.fpr[2] = java.lang.Float.floatToRawIntBits(2.0f).toLong()
        cpu.setProgramCounter(0)

        cpu.step()
        assertEquals(3.5f, java.lang.Float.intBitsToFloat(cpu.fpr[3].toInt()), 0.0f)
        cpu.step()
        assertEquals(7.0f, java.lang.Float.intBitsToFloat(cpu.fpr[4].toInt()), 0.0f)
    }

    @Test fun doublePrecisionSubtractAndDivide() {
        val memory = N64Memory(64)
        memory.write32(0, cop1(0x11, 2, 1, 3, 0x01)) // SUB.D f3, f1, f2
        memory.write32(4, cop1(0x11, 2, 3, 4, 0x03)) // DIV.D f4, f3, f2
        val cpu = R4300Cpu(memory)
        cpu.cop0[12] = (1L shl 29) or (1L shl 26)
        cpu.fpr[1] = java.lang.Double.doubleToRawLongBits(9.0)
        cpu.fpr[2] = java.lang.Double.doubleToRawLongBits(3.0)
        cpu.setProgramCounter(0)

        cpu.step()
        assertEquals(6.0, java.lang.Double.longBitsToDouble(cpu.fpr[3]), 0.0)
        cpu.step()
        assertEquals(2.0, java.lang.Double.longBitsToDouble(cpu.fpr[4]), 0.0)
    }

    @Test fun doublePrecisionUsesEvenOddPairWhenFr0() {
        val memory = N64Memory(64)
        memory.write32(0, cop1(0x11, 4, 2, 6, 0x00)) // ADD.D f6, f2, f4
        val cpu = R4300Cpu(memory)
        cpu.cop0[12] = 1L shl 29 // CU1=1, FR=0
        val nine = java.lang.Double.doubleToRawLongBits(9.0)
        val three = java.lang.Double.doubleToRawLongBits(3.0)
        cpu.fpr[2] = (nine ushr 32) and 0xFFFFFFFFL
        cpu.fpr[3] = nine and 0xFFFFFFFFL
        cpu.fpr[4] = (three ushr 32) and 0xFFFFFFFFL
        cpu.fpr[5] = three and 0xFFFFFFFFL
        cpu.setProgramCounter(0)

        cpu.step()

        val result = ((cpu.fpr[6] and 0xFFFFFFFFL) shl 32) or (cpu.fpr[7] and 0xFFFFFFFFL)
        assertEquals(12.0, java.lang.Double.longBitsToDouble(result), 0.0)
    }

    @Test fun doublePrecisionOddRegisterIsReservedWhenFr0() {
        val memory = N64Memory(64)
        memory.write32(0, cop1(0x11, 2, 1, 4, 0x00)) // ADD.D with odd fs in FR=0
        val cpu = R4300Cpu(memory)
        cpu.cop0[12] = 1L shl 29
        cpu.setProgramCounter(0)

        cpu.step()

        assertEquals(10L, (cpu.cop0[13] ushr 2) and 0x1FL) // Reserved Instruction
    }

    @Test fun ctc1MasksReservedFcr31BitsAndKeepsArchitecturalFields() {
        val memory = N64Memory(64)
        memory.write32(0, (0x11 shl 26) or (0x06 shl 21) or (1 shl 16) or (31 shl 11)) // CTC1 rt, FCR31
        val cpu = R4300Cpu(memory)
        cpu.cop0[12] = 1L shl 29
        cpu.gpr[1] = -1L
        cpu.setProgramCounter(0)

        cpu.step()

        assertEquals(0x0183FFFFL, cpu.fcr[31])
        assertEquals(3L, cpu.fcr[31] and 0x3L) // RM
        assertEquals(0x7CL, cpu.fcr[31] and (0x1FL shl 2)) // Flags
        assertEquals(0xF80L, cpu.fcr[31] and (0x1FL shl 7)) // Enables
        assertEquals(0x3F000L, cpu.fcr[31] and (0x3FL shl 12)) // Cause
        assertEquals(1L shl 23, cpu.fcr[31] and (1L shl 23)) // FCC0
        assertEquals(1L shl 24, cpu.fcr[31] and (1L shl 24)) // FS
    }

    @Test fun divideByZeroSetsFcr31CauseAndStickyFlagWhenDisabled() {
        val memory = N64Memory(64)
        memory.write32(0, cop1(0x10, 2, 1, 3, 0x03)) // DIV.S f3, f1, f2
        val cpu = R4300Cpu(memory)
        cpu.cop0[12] = 1L shl 29
        cpu.fpr[1] = java.lang.Float.floatToRawIntBits(1.0f).toLong()
        cpu.fpr[2] = 0L
        cpu.setProgramCounter(0)

        cpu.step()

        assertEquals(1L shl 15, cpu.fcr[31] and (1L shl 12..17))
        assertEquals(1L shl 5, cpu.fcr[31] and (0x1FL shl 2))
        assertEquals(Float.POSITIVE_INFINITY, java.lang.Float.intBitsToFloat(cpu.fpr[3].toInt()), 0.0f)
        assertEquals(0L, (cpu.cop0[13] ushr 2) and 0x1FL)
    }

    @Test fun enabledDivideByZeroRaisesFloatingPointExceptionWithoutWritingDestination() {
        val memory = N64Memory(64)
        memory.write32(0, cop1(0x10, 2, 1, 3, 0x03)) // DIV.S f3, f1, f2
        val cpu = R4300Cpu(memory)
        cpu.cop0[12] = 1L shl 29
        cpu.fcr[31] = 1L shl 10 // Enable divide-by-zero.
        cpu.fpr[1] = java.lang.Float.floatToRawIntBits(1.0f).toLong()
        cpu.fpr[2] = 0L
        cpu.fpr[3] = 0x12345678L
        cpu.setProgramCounter(0)

        cpu.step()

        assertEquals(1L shl 15, cpu.fcr[31] and (1L shl 12..17))
        assertEquals(1L shl 5, cpu.fcr[31] and (0x1FL shl 2))
        assertEquals(15L, (cpu.cop0[13] ushr 2) and 0x1FL) // Floating-point exception.
        assertEquals(0x12345678L, cpu.fpr[3])
    }

    @Test fun cop1ArithmeticRequiresCu1() {
        val memory = N64Memory(64)
        memory.write32(0, cop1(0x10, 2, 1, 3, 0x00))
        val cpu = R4300Cpu(memory)
        cpu.fpr[1] = java.lang.Float.floatToRawIntBits(1.0f).toLong()
        cpu.fpr[2] = java.lang.Float.floatToRawIntBits(2.0f).toLong()
        cpu.setProgramCounter(0)

        cpu.step()

        assertEquals(11L, (cpu.cop0[13] ushr 2) and 0x1FL)
        assertEquals(1L, (cpu.cop0[13] ushr 28) and 0x3L)
        assertEquals(0L, cpu.fpr[3])
    }
    @Test fun singlePrecisionSquareRootRequiresZeroFtAndProducesCorrectResult() {
        val memory = N64Memory(64)
        memory.write32(0, cop1(0x10, 0, 1, 3, 0x04)) // SQRT.S f3, f1
        val cpu = R4300Cpu(memory)
        cpu.cop0[12] = 1L shl 29
        cpu.fpr[1] = java.lang.Float.floatToRawIntBits(9.0f).toLong()
        cpu.setProgramCounter(0)

        cpu.step()

        assertEquals(3.0f, java.lang.Float.intBitsToFloat(cpu.fpr[3].toInt()), 0.0f)
        assertEquals(0L, (cpu.cop0[13] ushr 2) and 0x1FL)
    }

    @Test fun malformedUnarySinglePrecisionRaisesReservedInstructionWithoutWritingDestination() {
        val memory = N64Memory(64)
        memory.write32(0, cop1(0x10, 2, 1, 3, 0x04)) // Invalid SQRT.S: ft must be zero
        val cpu = R4300Cpu(memory)
        cpu.cop0[12] = 1L shl 29
        cpu.fpr[1] = java.lang.Float.floatToRawIntBits(9.0f).toLong()
        cpu.fpr[2] = java.lang.Float.floatToRawIntBits(4.0f).toLong()
        cpu.fpr[3] = 0x12345678L
        cpu.setProgramCounter(0)

        cpu.step()

        assertEquals(10L, (cpu.cop0[13] ushr 2) and 0x1FL)
        assertEquals(0x12345678L, cpu.fpr[3])
    }
    @Test fun doublePrecisionOddDestinationRegisterIsReservedWhenFr0WithoutChangingPair() {
        val memory = N64Memory(64)
        memory.write32(0, cop1(0x11, 4, 2, 5, 0x00)) // ADD.D with odd fd in FR=0
        val cpu = R4300Cpu(memory)
        cpu.cop0[12] = 1L shl 29
        cpu.fpr[2] = 0x3FF00000L
        cpu.fpr[3] = 0L
        cpu.fpr[4] = 0x40000000L
        cpu.fpr[5] = 0x12345678L
        cpu.setProgramCounter(0)

        cpu.step()

        assertEquals(10L, (cpu.cop0[13] ushr 2) and 0x1FL)
        assertEquals(0x40000000L, cpu.fpr[4])
        assertEquals(0x12345678L, cpu.fpr[5])
    }
    @Test fun singlePrecisionCompareLessThanSetsFcc0() {
        val memory = N64Memory(64)
        memory.write32(0, cop1(0x10, 2, 1, 0, 0x3C)) // C.LT.S f1, f2
        val cpu = R4300Cpu(memory)
        cpu.cop0[12] = 1L shl 29
        cpu.fpr[1] = java.lang.Float.floatToRawIntBits(1.0f).toLong()
        cpu.fpr[2] = java.lang.Float.floatToRawIntBits(2.0f).toLong()
        cpu.setProgramCounter(0)

        cpu.step()

        assertEquals(1L shl 23, cpu.fcr[31] and (1L shl 23))
    }

    @Test fun unorderedSinglePrecisionCompareSetsFcc0OnlyForUnorderedCondition() {
        val memory = N64Memory(64)
        memory.write32(0, cop1(0x10, 2, 1, 0, 0x31)) // C.UN.S f1, f2
        val cpu = R4300Cpu(memory)
        cpu.cop0[12] = 1L shl 29
        cpu.fpr[1] = java.lang.Float.floatToRawIntBits(Float.NaN).toLong()
        cpu.fpr[2] = java.lang.Float.floatToRawIntBits(2.0f).toLong()
        cpu.setProgramCounter(0)

        cpu.step()

        assertEquals(1L shl 23, cpu.fcr[31] and (1L shl 23))
    }

    @Test fun equalDoubleCompareInFr0UsesPairedRegisters() {
        val memory = N64Memory(64)
        memory.write32(0, cop1(0x11, 4, 2, 0, 0x32)) // C.EQ.D f2, f4
        val cpu = R4300Cpu(memory)
        cpu.cop0[12] = 1L shl 29
        val value = java.lang.Double.doubleToRawLongBits(5.0)
        cpu.fpr[2] = (value ushr 32) and 0xFFFFFFFFL
        cpu.fpr[3] = value and 0xFFFFFFFFL
        cpu.fpr[4] = (value ushr 32) and 0xFFFFFFFFL
        cpu.fpr[5] = value and 0xFFFFFFFFL
        cpu.setProgramCounter(0)

        cpu.step()

        assertEquals(1L shl 23, cpu.fcr[31] and (1L shl 23))
    }
    @Test fun compareResultDrivesBc1tBranchAfterDelaySlot() {
        val memory = N64Memory(64)
        memory.write32(0, cop1(0x10, 2, 1, 0, 0x3C)) // C.LT.S f1, f2
        memory.write32(4, (0x11 shl 26) or (0x08 shl 21) or (1 shl 16) or 2) // BC1T +2
        memory.write32(8, 0) // delay-slot NOP
        val cpu = R4300Cpu(memory)
        cpu.cop0[12] = 1L shl 29
        cpu.fpr[1] = java.lang.Float.floatToRawIntBits(1.0f).toLong()
        cpu.fpr[2] = java.lang.Float.floatToRawIntBits(2.0f).toLong()
        cpu.setProgramCounter(0)

        cpu.step()
        cpu.step()
        assertEquals(8L, cpu.pc)
        cpu.step()

        assertEquals(16L, cpu.pc)
    }

    @Test fun orderedLessThanCompareClearsFcc0WhenFalse() {
        val memory = N64Memory(64)
        memory.write32(0, cop1(0x10, 2, 1, 0, 0x3C)) // C.LT.S f1, f2
        val cpu = R4300Cpu(memory)
        cpu.cop0[12] = 1L shl 29
        cpu.fcr[31] = 1L shl 23
        cpu.fpr[1] = java.lang.Float.floatToRawIntBits(3.0f).toLong()
        cpu.fpr[2] = java.lang.Float.floatToRawIntBits(2.0f).toLong()
        cpu.setProgramCounter(0)

        cpu.step()

        assertEquals(0L, cpu.fcr[31] and (1L shl 23))
    }
}
