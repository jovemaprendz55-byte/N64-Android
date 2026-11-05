package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class N64HleR4300ExceptionContextTest {
    @Test fun ordinaryExceptionUsesFaultingInstructionAsEpc() {
        val context = N64HleR4300ExceptionContext(
            code = N64HleR4300ExceptionCode.ARITHMETIC_OVERFLOW,
            faultingPc = 0xFFFFFFFF80001020UL.toLong()
        )
        assertEquals(12, context.code.causeExcCode)
        assertEquals(0xFFFFFFFF80001020UL.toLong(), context.exceptionProgramCounter)
        assertFalse(context.branchDelayFlag)
    }

    @Test fun delaySlotExceptionUsesBranchAddressAsEpcAndSetsBdFact() {
        val context = N64HleR4300ExceptionContext(
            code = N64HleR4300ExceptionCode.RESERVED_INSTRUCTION,
            faultingPc = 0xFFFFFFFF80001024UL.toLong(),
            inDelaySlot = true,
            branchPc = 0xFFFFFFFF80001020UL.toLong()
        )
        assertEquals(10, context.code.causeExcCode)
        assertEquals(0xFFFFFFFF80001020UL.toLong(), context.exceptionProgramCounter)
        assertTrue(context.branchDelayFlag)
    }

    @Test fun delaySlotRequiresExplicitBranchAddress() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            N64HleR4300ExceptionContext(
                N64HleR4300ExceptionCode.BREAKPOINT,
                0x80001004L,
                inDelaySlot = true
            )
        }
    }

    @Test fun delaySlotMustImmediatelyFollowBranch() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            N64HleR4300ExceptionContext(
                N64HleR4300ExceptionCode.BREAKPOINT,
                0x80001008L,
                inDelaySlot = true,
                branchPc = 0x80001000L
            )
        }
    }

    @Test fun nonDelayExceptionRejectsBranchMetadata() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            N64HleR4300ExceptionContext(
                N64HleR4300ExceptionCode.SYSCALL,
                0x80001000L,
                branchPc = 0x80000FFCL
            )
        }
    }

    @Test fun optionalFaultMetadataIsPreserved() {
        val context = N64HleR4300ExceptionContext(
            code = N64HleR4300ExceptionCode.COPROCESSOR_UNUSABLE,
            faultingPc = 0x80001000L,
            coprocessorNumber = 2
        )
        assertEquals(2, context.coprocessorNumber)
        assertEquals(null, context.badVirtualAddress)
    }
}
