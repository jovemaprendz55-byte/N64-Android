package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class N64HleR4300ExceptionInstructionClassifierTest {
    private val decoder = N64HleR4300InstructionDecoder()
    private val classifier = N64HleR4300ExceptionInstructionClassifier()

    @Test fun syscallIsClassified() {
        assertEquals(N64HleR4300ExceptionCode.SYSCALL, classifier.classify(decoder.decode(0x0C)))
    }

    @Test fun breakIsClassified() {
        assertEquals(N64HleR4300ExceptionCode.BREAKPOINT, classifier.classify(decoder.decode(0x0D)))
    }

    @Test fun unknownSpecialFunctionIsReservedInstruction() {
        assertEquals(N64HleR4300ExceptionCode.RESERVED_INSTRUCTION, classifier.classify(decoder.decode(0x01)))
    }

    @Test fun knownButUnimplementedOperationIsNotMisclassified() {
        assertNull(classifier.classify(decoder.decode(0x30))) // TGE
    }

    @Test fun signedAndUnsignedTrapConditionsAreEvaluated() {
        val state = N64HleR4300CpuState(0x80000000L, mapOf(1 to -1L, 2 to 1L))
        fun instruction(function: Int) = decoder.decode((1 shl 21) or (2 shl 16) or function)

        assertNull(classifier.classify(instruction(0x30), state)) // TGE signed: -1 >= 1 is false
        assertNull(classifier.classify(instruction(0x33), state)) // TLTU unsigned: -1 < 1 is false
        assertEquals(N64HleR4300ExceptionCode.TRAP, classifier.classify(instruction(0x36), state)) // TNE
        assertEquals(N64HleR4300ExceptionCode.TRAP, classifier.classify(instruction(0x31), state)) // TGEU true
        assertEquals(N64HleR4300ExceptionCode.TRAP, classifier.classify(instruction(0x32), state)) // TLT true
        assertNull(classifier.classify(instruction(0x34), state)) // TEQ false
    }

    @Test fun equalRegistersTriggerEqualAndGreaterEqualTraps() {
        val state = N64HleR4300CpuState(0x80000000L, mapOf(1 to 42L, 2 to 42L))
        fun instruction(function: Int) = decoder.decode((1 shl 21) or (2 shl 16) or function)

        assertEquals(N64HleR4300ExceptionCode.TRAP, classifier.classify(instruction(0x31), state))
        assertEquals(N64HleR4300ExceptionCode.TRAP, classifier.classify(instruction(0x34), state))
        assertNull(classifier.classify(instruction(0x36), state))
    }

    @Test fun regimmTrapImmediateConditionsUseSignExtendedImmediate() {
        val state = N64HleR4300CpuState(0x80000000L, mapOf(1 to -1L))
        fun instruction(rt: Int, immediate: Int) =
            decoder.decode((0x01 shl 26) or (1 shl 21) or (rt shl 16) or (immediate and 0xFFFF))

        assertNull(classifier.classify(instruction(0x08, 1), state))
        assertEquals(N64HleR4300ExceptionCode.TRAP, classifier.classify(instruction(0x09, 1), state)) // TGEIU unsigned max >= 1
        assertEquals(N64HleR4300ExceptionCode.TRAP, classifier.classify(instruction(0x0A, 1), state)) // TLTI signed -1 < 1
        assertNull(classifier.classify(instruction(0x0B, 1), state)) // TLTIU unsigned max < 1 is false
        assertNull(classifier.classify(instruction(0x0C, 1), state)) // TEQI -1 == 1
        assertEquals(N64HleR4300ExceptionCode.TRAP, classifier.classify(instruction(0x0E, 1), state)) // TNEI
    }

    @Test fun reservedRegimmRtEncodingsAreReservedInstructions() {
        for (rt in listOf(0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x0D, 0x0F, 0x12, 0x1F)) {
            val word = (0x01 shl 26) or (rt shl 16)
            assertEquals(
                "REGIMM rt=0x${rt.toString(16)}",
                N64HleR4300ExceptionCode.RESERVED_INSTRUCTION,
                classifier.classify(decoder.decode(word))
            )
        }
    }

    @Test fun definedRegimmBranchesAndTrapImmediateFormsAreNotReserved() {
        for (rt in listOf(0x00, 0x01, 0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0E, 0x10, 0x11)) {
            val word = (0x01 shl 26) or (rt shl 16)
            assertNull("REGIMM rt=0x${rt.toString(16)}", classifier.classify(decoder.decode(word)))
        }
    }

    @Test fun nonSpecialInstructionIsNotClassifiedHere() {
        assertNull(classifier.classify(decoder.decode(0xFC000000.toInt())))
    }
}
