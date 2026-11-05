package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class N64HleR4300ExceptionRegisterUpdaterTest {
    private val updater = N64HleR4300ExceptionRegisterUpdater()

    @Test fun ordinaryExceptionWritesCauseStatusAndEpc() {
        val state = N64HleR4300CpuState(
            0x80001000L,
            cop0 = N64HleCop0EntryState(
                mapOf(12 to 0x00000001L, 13 to 0x0000A0FFL, 14 to 0x11111110L),
                "explicit exception register test state"
            )
        )
        val result = updater.apply(
            N64HleR4300ExceptionContext(
                N64HleR4300ExceptionCode.ARITHMETIC_OVERFLOW,
                0x80001020L
            ),
            state
        )
        assertEquals(0xFFFFFFFF80001020uL.toLong(), state.readCop0(14))
        assertEquals(0x0000A0B3L, state.readCop0(13))
        assertEquals(0x00000003L, state.readCop0(12))
        assertTrue(result.epcWritten)
        assertFalse(result.exlWasAlreadySet)
    }

    @Test fun delaySlotSetsBdAndUsesBranchAddressForEpc() {
        val state = N64HleR4300CpuState(
            0x80001024L,
            cop0 = N64HleCop0EntryState(
                mapOf(12 to 0L, 13 to 0L, 14 to 0x11111110L),
                "explicit delay-slot exception test state"
            )
        )
        updater.apply(
            N64HleR4300ExceptionContext(
                N64HleR4300ExceptionCode.RESERVED_INSTRUCTION,
                0x80001024L,
                inDelaySlot = true,
                branchPc = 0x80001020L
            ),
            state
        )
        assertEquals(0xFFFFFFFF80001020uL.toLong(), state.readCop0(14))
        assertEquals(0xFFFFFFFF80000028uL.toLong(), state.readCop0(13))
        assertEquals(0x2L, state.readCop0(12))
    }

    @Test fun nestedExceptionPreservesEpcWhenExlWasAlreadySet() {
        val state = N64HleR4300CpuState(
            0x80001000L,
            cop0 = N64HleCop0EntryState(
                mapOf(12 to 0x2L, 13 to 0x55L, 14 to 0x80000044L),
                "explicit nested exception test state"
            )
        )
        val result = updater.apply(
            N64HleR4300ExceptionContext(
                N64HleR4300ExceptionCode.BREAKPOINT,
                0x80001000L
            ),
            state
        )
        assertEquals(0xFFFFFFFF80000044uL.toLong(), state.readCop0(14))
        assertEquals(0x25L, state.readCop0(13))
        assertFalse(result.epcWritten)
        assertTrue(result.exlWasAlreadySet)
    }

    @Test fun badVirtualAddressIsWrittenOnlyWhenProvided() {
        val state = N64HleR4300CpuState(
            0x80001000L,
            cop0 = N64HleCop0EntryState(
                mapOf(8 to 0x1234L, 12 to 0L, 13 to 0L, 14 to 0L),
                "explicit bad address test state"
            )
        )
        updater.apply(
            N64HleR4300ExceptionContext(
                N64HleR4300ExceptionCode.ADDRESS_LOAD,
                0x80001000L,
                badVirtualAddress = 0xDEADBEEFL
            ),
            state
        )
        assertEquals(0xFFFFFFFFDEADBEEFuL.toLong(), state.readCop0(8))
    }

    @Test fun unknownRequiredStatusOrCauseFailsBeforeAnyMutation() {
        val missingStatus = N64HleR4300CpuState(
            0x80001000L,
            cop0 = N64HleCop0EntryState(mapOf(13 to 0x1234L, 14 to 0x5678L), "partial")
        )
        assertThrows(IllegalStateException::class.java) {
            updater.apply(
                N64HleR4300ExceptionContext(N64HleR4300ExceptionCode.SYSCALL, 0x80001000L),
                missingStatus
            )
        }
        assertEquals(0x1234L, missingStatus.readCop0(13))
        assertEquals(0x5678L, missingStatus.readCop0(14))
        assertFalse(missingStatus.isCop0Known(12))

        val missingCause = N64HleR4300CpuState(
            0x80001000L,
            cop0 = N64HleCop0EntryState(mapOf(12 to 0L, 14 to 0x5678L), "partial")
        )
        assertThrows(IllegalStateException::class.java) {
            updater.apply(
                N64HleR4300ExceptionContext(N64HleR4300ExceptionCode.SYSCALL, 0x80001000L),
                missingCause
            )
        }
        assertEquals(0L, missingCause.readCop0(12))
        assertEquals(0x5678L, missingCause.readCop0(14))
    }
}
