package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Test

class N64HleGuestOsThreadLayoutTest {
    @Test
    fun matchesPublicLibultraOsthreadOffsetsAndSizes() {
        assertEquals(0x1C, N64HleGuestOsThreadLayout.THREAD_CONTEXT)
        assertEquals(0xF8, N64HleGuestOsThreadLayout.CONTEXT_SR)
        assertEquals(0xFC, N64HleGuestOsThreadLayout.CONTEXT_PC)
        assertEquals(0x110, N64HleGuestOsThreadLayout.CONTEXT_FP0)
        assertEquals(0x188, N64HleGuestOsThreadLayout.CONTEXT_FP30)
        assertEquals(0x190, N64HleGuestOsThreadLayout.CONTEXT_SIZE)
        assertEquals(0x1AC, N64HleGuestOsThreadLayout.THREAD_SIZE)
    }

    @Test
    fun exposesLibultraThreadStatesAndPriorityRange() {
        assertEquals(1, N64HleGuestOsThreadLayout.STATE_STOPPED)
        assertEquals(2, N64HleGuestOsThreadLayout.STATE_RUNNABLE)
        assertEquals(4, N64HleGuestOsThreadLayout.STATE_RUNNING)
        assertEquals(8, N64HleGuestOsThreadLayout.STATE_WAITING)
        assertEquals(0, N64HleGuestOsThreadLayout.PRIORITY_IDLE)
        assertEquals(127, N64HleGuestOsThreadLayout.PRIORITY_APP_MAX)
    }
}
