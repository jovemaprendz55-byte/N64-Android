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

    @Test fun nonSpecialInstructionIsNotClassifiedHere() {
        assertNull(classifier.classify(decoder.decode(0xFC000000.toInt())))
    }
}
