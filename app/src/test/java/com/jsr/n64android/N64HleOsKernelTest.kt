package com.jsr.n64android

import org.junit.Assert.*
import org.junit.Test

class N64HleOsKernelTest {
    @Test
    fun dispatchUsesLowerNumericPriorityAndFifoForTies() {
        val os = N64HleOsKernel()
        os.createThread(1, 10, 0x80001000L)
        os.createThread(2, 5, 0x80002000L)
        os.createThread(3, 5, 0x80003000L)
        os.startThread(1)
        os.startThread(2)
        os.startThread(3)

        assertEquals(2, os.dispatch()?.id)
        assertEquals(3, os.yieldCurrent()?.id)
        assertEquals(2, os.yieldCurrent()?.id)
    }

    @Test
    fun stoppedThreadStartsAndTerminatedThreadIsNotDispatchedAgain() {
        val os = N64HleOsKernel()
        os.createThread(7, 1, 0x80004000L, 0x55L)
        assertEquals(N64HleOsKernel.ThreadState.STOPPED, os.threadSnapshot(7).state)
        os.startThread(7)
        assertEquals(7, os.dispatch()?.id)
        os.terminateCurrent()
        assertEquals(N64HleOsKernel.ThreadState.TERMINATED, os.threadSnapshot(7).state)
        assertNull(os.dispatch())
    }

    @Test
    fun boundedMessageQueuePreservesFifoAndRejectsOverflow() {
        val os = N64HleOsKernel()
        os.createMessageQueue(4, 2)
        assertTrue(os.sendMessage(4, 0x11L))
        assertTrue(os.sendMessage(4, 0x22L))
        assertFalse(os.sendMessage(4, 0x33L))
        assertEquals(0x11L, os.receiveMessage(4))
        assertEquals(0x22L, os.receiveMessage(4))
        assertNull(os.receiveMessage(4))
    }

    @Test
    fun eventWakesOneWaitingThreadAndRemainsPollable() {
        val os = N64HleOsKernel()
        os.createThread(1, 3, 0x80001000L)
        os.createThread(2, 4, 0x80002000L)
        os.startThread(1)
        os.startThread(2)
        assertEquals(1, os.dispatch()?.id)
        os.blockCurrentForEvent()
        assertEquals(N64HleOsKernel.ThreadState.WAITING_EVENT, os.threadSnapshot(1).state)
        assertEquals(2, os.dispatch()?.id)

        os.postEvent(0x08, 0x1234L)
        assertEquals(N64HleOsKernel.ThreadState.RUNNABLE, os.threadSnapshot(1).state)
        assertEquals(N64HleOsKernel.Event(0x08, 0x1234L, 3L), os.pollEvent())
        assertNull(os.pollEvent())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnalignedThreadEntryPoint() {
        N64HleOsKernel().createThread(1, 1, 0x80001002L)
    }
}
