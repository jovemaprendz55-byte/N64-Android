package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Test

class N64HleR4300ExceptionVectorResolverTest {
    private val resolver = N64HleR4300ExceptionVectorResolver()

    @Test fun bevClearSelectsGeneralExceptionVector() {
        assertEquals(
            N64HleR4300ExceptionVectorResolver.GENERAL_EXCEPTION_VECTOR,
            resolver.resolveGeneralVector(0L)
        )
    }

    @Test fun bevSetSelectsBootExceptionVector() {
        assertEquals(
            N64HleR4300ExceptionVectorResolver.BOOT_EXCEPTION_VECTOR,
            resolver.resolveGeneralVector(N64HleR4300ExceptionVectorResolver.STATUS_BEV.toLong())
        )
    }

    @Test fun unrelatedStatusBitsDoNotChangeVectorSelection() {
        val status = 0x00000003L // IE and EXL set; BEV clear.
        assertEquals(
            N64HleR4300ExceptionVectorResolver.GENERAL_EXCEPTION_VECTOR,
            resolver.resolveGeneralVector(status)
        )
        assertEquals(
            N64HleR4300ExceptionVectorResolver.BOOT_EXCEPTION_VECTOR,
            resolver.resolveGeneralVector(status or N64HleR4300ExceptionVectorResolver.STATUS_BEV.toLong())
        )
    }

    @Test fun resolverDoesNotMutateStatusOrCpuState() {
        val status = 0x00400002L
        val state = N64HleR4300CpuState(
            0x80001000L,
            cop0 = N64HleCop0EntryState(mapOf(12 to status), "vector resolver purity test")
        )
        val beforePc = state.programCounter
        val beforeStatus = state.readCop0(12)

        resolver.resolveGeneralVector(state.readCop0(12))

        assertEquals(beforePc, state.programCounter)
        assertEquals(beforeStatus, state.readCop0(12))
    }
}
