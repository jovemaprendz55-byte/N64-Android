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


    @Test
    fun blockedReceiverWakesWhenMessageArrives() {
        val os = N64HleOsKernel()
        os.createMessageQueue(9, 1)
        os.createThread(1, 1, 0x80001000L)
        os.createThread(2, 2, 0x80002000L)
        os.startThread(1)
        os.startThread(2)

        assertEquals(1, os.dispatch()?.id)
        assertNull(os.receiveMessageBlocking(9))
        assertEquals(N64HleOsKernel.ThreadState.WAITING_MESSAGE, os.threadSnapshot(1).state)
        assertEquals(1, os.messageQueueSnapshot(9).waitingReceivers)
        assertEquals(2, os.dispatch()?.id)

        assertTrue(os.sendMessage(9, 0x77L))
        assertEquals(N64HleOsKernel.ThreadState.RUNNABLE, os.threadSnapshot(1).state)
        assertEquals(0, os.messageQueueSnapshot(9).waitingReceivers)
        assertEquals(1, os.dispatch()?.id)
        assertEquals(0x77L, os.receiveMessageBlocking(9))
    }

    @Test
    fun blockedSenderIsQueuedWhenReceiverFreesCapacity() {
        val os = N64HleOsKernel()
        os.createMessageQueue(5, 1)
        assertTrue(os.sendMessage(5, 0x11L))
        os.createThread(1, 1, 0x80001000L)
        os.createThread(2, 2, 0x80002000L)
        os.startThread(1)
        os.startThread(2)

        assertEquals(1, os.dispatch()?.id)
        assertFalse(os.sendMessageBlocking(5, 0x22L))
        assertEquals(N64HleOsKernel.ThreadState.WAITING_MESSAGE, os.threadSnapshot(1).state)
        assertEquals(1, os.messageQueueSnapshot(5).waitingSenders)
        assertEquals(2, os.dispatch()?.id)

        assertEquals(0x11L, os.receiveMessageBlocking(5))
        assertEquals(listOf(0x22L), os.messageQueueSnapshot(5).queuedMessages)
        assertEquals(0, os.messageQueueSnapshot(5).waitingSenders)
        assertEquals(N64HleOsKernel.ThreadState.RUNNABLE, os.threadSnapshot(1).state)
        assertEquals(1, os.dispatch()?.id)
        assertEquals(0x22L, os.receiveMessageBlocking(5))
    }

    @Test
    fun nonBlockingSenderCannotBypassAnOlderBlockedSender() {
        val os = N64HleOsKernel()
        os.createMessageQueue(6, 1)
        assertTrue(os.sendMessage(6, 1L))
        os.createThread(1, 1, 0x80001000L)
        os.createThread(2, 2, 0x80002000L)
        os.startThread(1)
        os.startThread(2)

        assertEquals(1, os.dispatch()?.id)
        assertFalse(os.sendMessageBlocking(6, 2L))
        assertEquals(2, os.dispatch()?.id)
        assertEquals(1L, os.receiveMessage(6))
        assertEquals(listOf(2L), os.messageQueueSnapshot(6).queuedMessages)
        assertFalse(os.sendMessage(6, 3L))
    }


    @Test
    fun diagnosticSnapshotListsThreadsAndQueuesWithoutChangingSchedulerState() {
        val os = N64HleOsKernel()
        os.createThread(2, 4, 0x80002000L)
        os.createThread(1, 2, 0x80001000L)
        os.createMessageQueue(7, 3)
        os.sendMessage(7, 0x55L)
        os.startThread(2)
        val before = os.currentThread()
        val report = os.diagnosticSnapshot()

        assertNull(before)
        assertEquals(listOf(1, 2), os.allThreadSnapshots().map { it.id })
        assertTrue(report.contains("THREAD id=1 priority=2 state=STOPPED"))
        assertTrue(report.contains("QUEUE id=7 capacity=3 messages=1"))
        assertNull(os.currentThread())
        assertEquals(N64HleOsKernel.ThreadState.RUNNABLE, os.threadSnapshot(2).state)
    }


    @Test fun priorityChangeAffectsNextDispatchWithoutResettingWakeOrder() {
        val os = N64HleOsKernel()
        os.createThread(1, 10, 0x80001000L)
        os.createThread(2, 5, 0x80002000L)
        os.startThread(1)
        os.startThread(2)
        os.setThreadPriority(1, 1)
        assertEquals(1, os.dispatch()?.id)
    }

    @Test fun typedEventOnlyWakesMatchingWaiter() {
        val os = N64HleOsKernel()
        os.createThread(1, 1, 0x80001000L)
        os.createThread(2, 2, 0x80002000L)
        os.startThread(1)
        os.startThread(2)
        assertEquals(1, os.dispatch()?.id)
        os.blockCurrentForEvent(0x08)
        assertEquals(2, os.dispatch()?.id)
        os.blockCurrentForEvent(0x10)
        os.postEvent(0x08, 0x55L)
        assertEquals(N64HleOsKernel.ThreadState.RUNNABLE, os.threadSnapshot(1).state)
        assertEquals(N64HleOsKernel.ThreadState.WAITING_EVENT, os.threadSnapshot(2).state)
    }
\
}
