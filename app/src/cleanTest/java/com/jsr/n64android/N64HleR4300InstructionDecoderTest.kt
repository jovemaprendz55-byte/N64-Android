package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class N64HleR4300InstructionDecoderTest {
    private val decoder = N64HleR4300InstructionDecoder()

    @Test
    fun decodesRTypeRegisterFieldsAndAdduFunction() {
        // ADDU t0, t1, t2: rd=8, rs=9, rt=10, funct=0x21.
        val word = (9 shl 21) or (10 shl 16) or (8 shl 11) or 0x21
        val decoded = decoder.decode(word)

        assertEquals(0, decoded.opcode)
        assertEquals(9, decoded.rs)
        assertEquals(10, decoded.rt)
        assertEquals(8, decoded.rd)
        assertEquals(0, decoded.shiftAmount)
        assertEquals(0x21, decoded.functionCode)
        assertEquals(N64HleR4300SpecialOperation.ADDU, decoded.specialOperation)
        assertTrue(decoded.isSpecial)
    }

    @Test
    fun decodesSignedAndUnsignedImmediateViews() {
        val negative = decoder.decode((9 shl 21) or (8 shl 16) or 0xFF80)
        assertEquals(0xFF80, negative.immediateUnsigned)
        assertEquals(-128, negative.immediateSigned)
        assertEquals(9, negative.rs)
        assertEquals(8, negative.rt)

        val positive = decoder.decode((9 shl 21) or (8 shl 16) or 0x7FFF)
        assertEquals(0x7FFF, positive.immediateUnsigned)
        assertEquals(32767, positive.immediateSigned)
    }

    @Test
    fun extractsJumpIndexWithoutApplyingPcRegionRules() {
        val index = 0x02ABCDE
        val decoded = decoder.decode((2 shl 26) or index)

        assertEquals(2, decoded.opcode)
        assertEquals(index, decoded.jumpIndex)
        assertFalse(decoded.isSpecial)
        assertNull(decoded.specialOperation)
    }

    @Test
    fun recognizesMipsIiiDoublewordSpecialFunctions() {
        val decoded = decoder.decode((0x3C))
        assertEquals(N64HleR4300SpecialOperation.DSLL32, decoded.specialOperation)
        assertEquals(N64HleR4300SpecialOperation.DDIVU,
            N64HleR4300SpecialOperation.fromFunctionCode(0x1F))
    }

    @Test
    fun leavesUnknownSpecialFunctionUnclassified() {
        val decoded = decoder.decode(0x00000001)
        assertTrue(decoded.isSpecial)
        assertNull(decoded.specialOperation)
        assertEquals(1, decoded.functionCode)
    }

    @Test
    fun doesNotAssignSpecialFunctionToOtherOpcodes() {
        val decoded = decoder.decode((0x10 shl 26) or 0x20)

        assertEquals(0x10, decoded.opcode)
        assertFalse(decoded.isSpecial)
        assertNull(decoded.specialOperation)
    }
}
