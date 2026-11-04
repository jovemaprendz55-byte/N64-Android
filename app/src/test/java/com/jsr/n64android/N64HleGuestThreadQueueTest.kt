package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class N64HleGuestThreadQueueTest {
    private data class Fixture(
        val guest: N64HleGuestMemory,
        val codec: N64HleGuestOsThreadCodec,
        val queue: N64HleGuestThreadQueue,
        val queueAddress: Long,
        val tailAddress: Long
    )

    private fun fixture(): Fixture {
        val ram = N64Memory(8 * 1024 * 1024)
        val guest = N64HleGuestMemory(ram, ram.size)
        val codec = N64HleGuestOsThreadCodec(guest)
        val queueAddress = 0x80000100L
        val tailAddress = 0x80000200L
        codec.write(
            tailAddress,
            N64HleGuestOsThread(priority = -1, state = N64HleGuestOsThreadLayout.STATE_STOPPED)
        )
        guest.write32(queueAddress, tailAddress.toInt())
        return Fixture(guest, codec, N64HleGuestThreadQueue(guest), queueAddress, tailAddress)
    }

    @Test
    fun enqueuesByDescendingPriorityAndKeepsFifoOrderForEqualPriority() {
        val f = fixture()
        val low = 0x80001000L
        val firstEqual = 0x80001200L
        val high = 0x80001400L
        val secondEqual = 0x80001600L
        listOf(
            low to 10,
            firstEqual to 50,
            high to 100,
            secondEqual to 50
        ).forEach { (address, priority) ->
            f.codec.write(address, N64HleGuestOsThread(priority = priority))
            f.queue.enqueue(f.queueAddress, address)
        }

        assertEquals(high, f.queue.head(f.queueAddress))
        assertEquals(firstEqual, f.codec.read(high).next)
        assertEquals(secondEqual, f.codec.read(firstEqual).next)
        assertEquals(low, f.codec.read(secondEqual).next)
        assertEquals(f.tailAddress, f.codec.read(low).next)
        listOf(low, firstEqual, high, secondEqual).forEach { address ->
            assertEquals(f.queueAddress, f.codec.read(address).queue)
        }
    }

    @Test
    fun dequeueAndPopMirrorLibultraLinkUpdates() {
        val f = fixture()
        val first = 0x80001000L
        val second = 0x80001200L
        val third = 0x80001400L
        f.codec.write(first, N64HleGuestOsThread(priority = 30))
        f.codec.write(second, N64HleGuestOsThread(priority = 20))
        f.codec.write(third, N64HleGuestOsThread(priority = 10))
        f.queue.enqueue(f.queueAddress, first)
        f.queue.enqueue(f.queueAddress, second)
        f.queue.enqueue(f.queueAddress, third)

        assertTrue(f.queue.dequeue(f.queueAddress, second))
        assertEquals(third, f.codec.read(first).next)
        assertEquals(f.queueAddress, f.codec.read(second).queue) // libultra does not clear it.

        assertEquals(first, f.queue.pop(f.queueAddress))
        assertEquals(third, f.queue.head(f.queueAddress))
        assertEquals(third, f.queue.pop(f.queueAddress))
        assertEquals(f.tailAddress, f.queue.head(f.queueAddress))
        assertEquals(f.tailAddress, f.queue.pop(f.queueAddress))
        assertEquals(0L, f.queue.head(f.queueAddress))
        assertEquals(0L, f.queue.pop(f.queueAddress))
        assertFalse(f.queue.dequeue(f.queueAddress, second))
    }

    @Test
    fun rejectsDuplicateThreadsInvalidPriorityAndQueueCyclesBeforeInsertion() {
        val f = fixture()
        val first = 0x80001000L
        val second = 0x80001200L
        f.codec.write(first, N64HleGuestOsThread(priority = 50))
        f.codec.write(second, N64HleGuestOsThread(priority = 40))
        f.queue.enqueue(f.queueAddress, first)
        assertThrows(IllegalStateException::class.java) {
            f.queue.enqueue(f.queueAddress, first)
        }
        assertThrows(IllegalArgumentException::class.java) {
            f.codec.write(second, N64HleGuestOsThread(priority = -1))
            f.queue.enqueue(f.queueAddress, second)
        }
        f.codec.write(second, N64HleGuestOsThread(priority = 40))

        f.guest.write32(first, first.toInt()) // Corrupt self-cycle in the current queue.
        assertThrows(IllegalStateException::class.java) {
            f.queue.enqueue(f.queueAddress, second)
        }
        assertEquals(first, f.queue.head(f.queueAddress))
        assertEquals(0L, f.codec.read(second).next)
    }

    @Test
    fun rejectsInvalidQueueAndThreadPointers() {
        val f = fixture()
        val thread = 0x80001000L
        f.codec.write(thread, N64HleGuestOsThread(priority = 10))
        assertThrows(IllegalArgumentException::class.java) {
            f.queue.enqueue(0x80000002L, thread)
        }
        assertThrows(IllegalArgumentException::class.java) {
            f.queue.enqueue(f.queueAddress, 0x00001000L)
        }
    }
}
