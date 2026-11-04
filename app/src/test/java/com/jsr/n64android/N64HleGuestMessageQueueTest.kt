package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class N64HleGuestMessageQueueTest {
    private data class Fixture(
        val memory: N64HleGuestMemory,
        val codec: N64HleGuestOsMesgQueueCodec,
        val messages: N64HleGuestMessageQueue,
        val scheduler: N64HleGuestThreadScheduler,
        val queues: N64HleGuestThreadQueue,
        val queueAddress: Long,
        val bufferAddress: Long,
        val runQueueAddress: Long,
        val runningThreadAddress: Long,
        val tailAddress: Long
    )

    private fun fixture(capacity: Int = 3): Fixture {
        val ram = N64Memory(8 * 1024 * 1024)
        val guest = N64HleGuestMemory(ram, ram.size)
        val threadCodec = N64HleGuestOsThreadCodec(guest)
        val queues = N64HleGuestThreadQueue(guest)
        val runQueue = 0x80000100L
        val activeQueue = 0x80000104L
        val runningThread = 0x80000108L
        val tail = 0x80000200L
        val queueAddress = 0x80000300L
        val bufferAddress = 0x80000400L
        threadCodec.write(tail, N64HleGuestOsThread(priority = -1))
        guest.write32(runQueue, tail.toInt())
        guest.write32(activeQueue, tail.toInt())
        guest.write32(runningThread, 0)
        val scheduler = N64HleGuestThreadScheduler(
            guest, threadCodec, queues, runQueue, activeQueue, runningThread, tail
        )
        val queueCodec = N64HleGuestOsMesgQueueCodec(guest)
        queueCodec.create(queueAddress, bufferAddress, capacity, tail)
        val messages = N64HleGuestMessageQueue(guest, queueCodec, queues, scheduler, tail)
        return Fixture(
            guest, queueCodec, messages, scheduler, queues,
            queueAddress, bufferAddress, runQueue, runningThread, tail
        )
    }

    private fun startThread(f: Fixture, address: Long, priority: Int = 20) {
        val threadCodec = N64HleGuestOsThreadCodec(f.memory)
        threadCodec.write(address, N64HleGuestOsThread(priority = priority))
        f.scheduler.startThread(address)
    }

    @Test
    fun createInitializesSixWordLibultraQueueLayout() {
        val f = fixture(4)
        val queue = f.codec.read(f.queueAddress)
        assertEquals(f.tailAddress, queue.mtQueue)
        assertEquals(f.tailAddress, queue.fullQueue)
        assertEquals(0, queue.validCount)
        assertEquals(0, queue.first)
        assertEquals(4, queue.msgCount)
        assertEquals(f.bufferAddress, queue.msgBuffer)
    }

    @Test
    fun sendAndReceiveUseFifoRingBufferAndNonblockingEmptyResult() {
        val f = fixture(2)
        assertEquals(N64HleGuestMessageQueue.Status.SENT, f.messages.send(f.queueAddress, 0x111L).status)
        assertEquals(N64HleGuestMessageQueue.Status.SENT, f.messages.send(f.queueAddress, 0x222L).status)
        assertEquals(N64HleGuestMessageQueue.Status.WOULD_BLOCK, f.messages.send(f.queueAddress, 0x333L).status)
        assertEquals(0x111L, f.messages.receive(f.queueAddress).message)
        assertEquals(N64HleGuestMessageQueue.Status.SENT, f.messages.send(f.queueAddress, 0x333L).status)
        assertEquals(0x222L, f.messages.receive(f.queueAddress).message)
        assertEquals(0x333L, f.messages.receive(f.queueAddress).message)
        val empty = f.messages.receive(f.queueAddress)
        assertEquals(N64HleGuestMessageQueue.Status.WOULD_BLOCK, empty.status)
        assertNull(empty.message)
    }

    @Test
    fun jamPlacesMessageAtFrontOfRing() {
        val f = fixture(3)
        f.messages.send(f.queueAddress, 0x11L)
        f.messages.send(f.queueAddress, 0x22L)
        assertEquals(N64HleGuestMessageQueue.Status.JAMMED, f.messages.jam(f.queueAddress, 0x33L).status)
        assertEquals(0x33L, f.messages.receive(f.queueAddress).message)
        assertEquals(0x11L, f.messages.receive(f.queueAddress).message)
        assertEquals(0x22L, f.messages.receive(f.queueAddress).message)
    }

    @Test
    fun blockingReceiverWaitsOnMtQueueAndIsWokenBySend() {
        val f = fixture()
        val receiver = 0x80001000L
        startThread(f, receiver)
        val blocked = f.messages.receive(f.queueAddress, blocking = true)
        assertEquals(N64HleGuestMessageQueue.Status.BLOCKED, blocked.status)
        assertEquals(receiver, f.queues.head(f.queueAddress + N64HleGuestOsMesgQueueLayout.MT_QUEUE))
        assertEquals(0L, f.memory.read32(f.runningThreadAddress).toUInt().toLong())

        val sent = f.messages.send(f.queueAddress, 0xCAFE)
        assertEquals(N64HleGuestMessageQueue.Status.SENT, sent.status)
        assertEquals(receiver, sent.dispatch?.selectedThread)
        assertEquals(N64HleGuestOsThreadLayout.STATE_RUNNING,
            N64HleGuestOsThreadCodec(f.memory).read(receiver).state)
        assertEquals(0xCAFEL, f.messages.receive(f.queueAddress).message)
    }

    @Test
    fun blockingSenderWaitsOnFullQueueAndIsWokenWhenReceiverFreesSlot() {
        val f = fixture(1)
        f.messages.send(f.queueAddress, 0x111L)
        val sender = 0x80001000L
        startThread(f, sender)
        val blocked = f.messages.send(f.queueAddress, 0x222L, blocking = true)
        assertEquals(N64HleGuestMessageQueue.Status.BLOCKED, blocked.status)
        assertEquals(sender, f.queues.head(f.queueAddress + N64HleGuestOsMesgQueueLayout.FULL_QUEUE))

        assertEquals(0x111L, f.messages.receive(f.queueAddress).message)
        assertEquals(sender, f.memory.read32(f.runningThreadAddress).toUInt().toLong())
        // The guest continuation resumes after wake and retries the send.
        assertEquals(N64HleGuestMessageQueue.Status.SENT, f.messages.send(f.queueAddress, 0x222L).status)
        assertEquals(0x222L, f.messages.receive(f.queueAddress).message)
    }
}
