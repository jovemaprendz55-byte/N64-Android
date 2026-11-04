package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class N64HleGuestThreadSchedulerTest {
    private data class Fixture(
        val memory: N64HleGuestMemory,
        val codec: N64HleGuestOsThreadCodec,
        val queues: N64HleGuestThreadQueue,
        val scheduler: N64HleGuestThreadScheduler,
        val runQueueAddress: Long,
        val activeQueueAddress: Long,
        val runningThreadAddress: Long,
        val waitQueueAddress: Long,
        val tailAddress: Long
    )

    private fun fixture(): Fixture {
        val ram = N64Memory(8 * 1024 * 1024)
        val guest = N64HleGuestMemory(ram, ram.size)
        val codec = N64HleGuestOsThreadCodec(guest)
        val queues = N64HleGuestThreadQueue(guest)
        val runQueue = 0x80000100L
        val activeQueue = 0x80000104L
        val runningThread = 0x80000108L
        val waitQueue = 0x8000010CL
        val tail = 0x80000200L
        codec.write(tail, N64HleGuestOsThread(priority = -1))
        guest.write32(runQueue, tail.toInt())
        guest.write32(activeQueue, tail.toInt())
        guest.write32(waitQueue, tail.toInt())
        guest.write32(runningThread, 0)
        val scheduler = N64HleGuestThreadScheduler(
            guest, codec, queues, runQueue, activeQueue, runningThread, tail
        )
        return Fixture(
            guest, codec, queues, scheduler,
            runQueue, activeQueue, runningThread, waitQueue, tail
        )
    }

    private fun createThread(f: Fixture, address: Long, priority: Int, state: Int =
        N64HleGuestOsThreadLayout.STATE_STOPPED, queue: Long = 0L) {
        f.codec.write(address, N64HleGuestOsThread(priority = priority, state = state, queue = queue))
    }

    @Test
    fun registerCreatedThreadPrependsToActiveQueue() {
        val f = fixture()
        val first = 0x80001000L
        val second = 0x80001200L
        createThread(f, first, 10)
        createThread(f, second, 20)

        f.scheduler.registerCreatedThread(first)
        f.scheduler.registerCreatedThread(second)

        assertEquals(second, f.memory.read32(f.activeQueueAddress).toUInt().toLong())
        assertEquals(first, f.codec.read(second).threadListNext)
        assertEquals(f.tailAddress, f.codec.read(first).threadListNext)
    }

    @Test
    fun startThreadDispatchesFirstThreadAndPreemptsOnlyForHigherPriority() {
        val f = fixture()
        val low = 0x80001000L
        val high = 0x80001200L
        createThread(f, low, 10)
        createThread(f, high, 20)

        val first = f.scheduler.startThread(low)
        assertEquals(low, first.selectedThread)
        assertEquals(N64HleGuestOsThreadLayout.STATE_RUNNING, f.codec.read(low).state)

        val second = f.scheduler.startThread(high)
        assertTrue(second.switched)
        assertEquals(high, second.selectedThread)
        assertEquals(N64HleGuestOsThreadLayout.STATE_RUNNABLE, f.codec.read(low).state)
        assertEquals(N64HleGuestOsThreadLayout.STATE_RUNNING, f.codec.read(high).state)
        assertEquals(low, f.queues.head(f.runQueueAddress))
    }

    @Test
    fun yieldUsesPriorityQueueAndKeepsFifoForEqualPriority() {
        val f = fixture()
        val first = 0x80001000L
        val second = 0x80001200L
        createThread(f, first, 30)
        createThread(f, second, 30)
        f.scheduler.startThread(first)
        f.scheduler.startThread(second)

        val decision = f.scheduler.yieldThread()

        assertEquals(second, decision.selectedThread)
        assertEquals(N64HleGuestOsThreadLayout.STATE_RUNNABLE, f.codec.read(first).state)
        assertEquals(N64HleGuestOsThreadLayout.STATE_RUNNING, f.codec.read(second).state)
        assertEquals(first, f.queues.head(f.runQueueAddress))
    }

    @Test
    fun stopRemovesRunnableThreadAndStopsCurrentThread() {
        val f = fixture()
        val running = 0x80001000L
        val ready = 0x80001200L
        createThread(f, running, 10)
        createThread(f, ready, 10)
        f.scheduler.startThread(running)
        f.scheduler.startThread(ready)

        val removed = f.scheduler.stopThread(ready)
        assertFalse(removed.switched)
        assertEquals(N64HleGuestOsThreadLayout.STATE_STOPPED, f.codec.read(ready).state)
        assertEquals(f.tailAddress, f.queues.head(f.runQueueAddress))

        val stopped = f.scheduler.stopThread()
        assertEquals(N64HleGuestThreadScheduler.NULL_POINTER, stopped.selectedThread)
        assertEquals(N64HleGuestOsThreadLayout.STATE_STOPPED, f.codec.read(running).state)
        assertEquals(0L, f.memory.read32(f.runningThreadAddress).toUInt().toLong())
    }

    @Test
    fun stoppedThreadWithWaitQueueIsMovedThroughThatQueueToRunQueue() {
        val f = fixture()
        val thread = 0x80001000L
        createThread(f, thread, 40, queue = f.waitQueueAddress)

        val decision = f.scheduler.startThread(thread)

        assertEquals(thread, decision.selectedThread)
        assertEquals(N64HleGuestOsThreadLayout.STATE_RUNNING, f.codec.read(thread).state)
        assertEquals(f.runQueueAddress, f.codec.read(thread).queue)
        assertEquals(f.tailAddress, f.queues.head(f.waitQueueAddress))
    }

    @Test
    fun waitingThreadCanBeMadeRunnableAndDispatched() {
        val f = fixture()
        val thread = 0x80001000L
        createThread(f, thread, 15, state = N64HleGuestOsThreadLayout.STATE_WAITING)

        val decision = f.scheduler.startThread(thread)

        assertEquals(thread, decision.selectedThread)
        assertEquals(N64HleGuestOsThreadLayout.STATE_RUNNING, f.codec.read(thread).state)
    }
}
