package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class N64HleGuestOsThreadCodecTest {
    @Test
    fun roundTripsThreadHeaderAndRawCpuContext() {
        val ram = N64Memory(8 * 1024 * 1024)
        val guest = N64HleGuestMemory(ram, ram.size)
        val codec = N64HleGuestOsThreadCodec(guest)
        val address = 0x80001000L
        val gpr = LongArray(32) { index -> 0x100000000L + index }
        val fpPairs = LongArray(16) { index -> 0xABCDEF0000000000UL.toLong() + index }
        val expected = N64HleGuestOsThread(
            next = 0x80002000L,
            priority = 150,
            queue = 0xA0003000L,
            threadListNext = 0x80004000L,
            state = N64HleGuestOsThreadLayout.STATE_RUNNABLE,
            flags = 0xA55A,
            id = -7,
            fpUsed = 1,
            profile = 0x80005000L,
            context = N64HleGuestOsThreadContext(
                gpr = gpr,
                lo = 0x1122334455667788L,
                hi = 0x8877665544332211UL.toLong(),
                status = 0x34000000,
                pc = 0x80123450.toInt(),
                cause = 0x80000400.toInt(),
                badVaddr = 0x7F000123,
                rcp = 0x0000000F,
                fpcsr = 0x01000C00,
                fpPairs = fpPairs
            )
        )

        codec.write(address, expected)

        assertEquals(0x80005000.toInt(), ram.read32(0x101C))
        assertEquals(0x80123450.toInt(), ram.read32(0x111C))
        val actual = codec.read(address)
        assertEquals(expected.next, actual.next)
        assertEquals(expected.priority, actual.priority)
        assertEquals(expected.queue, actual.queue)
        assertEquals(expected.threadListNext, actual.threadListNext)
        assertEquals(expected.state, actual.state)
        assertEquals(expected.flags, actual.flags)
        assertEquals(expected.id, actual.id)
        assertEquals(expected.fpUsed, actual.fpUsed)
        assertEquals(expected.profile, actual.profile)
        assertEquals(expected.context.lo, actual.context.lo)
        assertEquals(expected.context.hi, actual.context.hi)
        assertEquals(expected.context.status, actual.context.status)
        assertEquals(expected.context.pc, actual.context.pc)
        assertEquals(expected.context.cause, actual.context.cause)
        assertEquals(expected.context.badVaddr, actual.context.badVaddr)
        assertEquals(expected.context.rcp, actual.context.rcp)
        assertEquals(expected.context.fpcsr, actual.context.fpcsr)
        for (register in 1..31) {
            val expectedValue = if (register == 26 || register == 27) 0L else gpr[register]
            assertEquals("GPR $register", expectedValue, actual.context.gpr[register])
        }
        fpPairs.indices.forEach { index ->
            assertEquals("FP pair $index", fpPairs[index], actual.context.fpPairs[index])
        }
    }

    @Test
    fun supportsSignExtendedGuestPointersOnReadAndWrite() {
        val ram = N64Memory(8 * 1024 * 1024)
        val codec = N64HleGuestOsThreadCodec(N64HleGuestMemory(ram, ram.size))
        val address = 0xFFFFFFFF80001000UL.toLong()
        codec.write(address, N64HleGuestOsThread(next = 0xFFFFFFFF80002000UL.toLong()))
        assertEquals(0x80002000L, codec.read(address).next)
    }

    @Test
    fun validatesEntireThreadRangeBeforeReadingOrWriting() {
        val ram = N64Memory(0x2000)
        val codec = N64HleGuestOsThreadCodec(N64HleGuestMemory(ram, ram.size))
        assertThrows(IllegalArgumentException::class.java) { codec.read(0x80001F00L) }
        assertThrows(IllegalArgumentException::class.java) {
            codec.write(0x80001F00L, N64HleGuestOsThread())
        }
    }

    @Test
    fun rejectsInvalidStateFlagsAndPointersBeforeWriting() {
        val ram = N64Memory(8 * 1024 * 1024)
        val codec = N64HleGuestOsThreadCodec(N64HleGuestMemory(ram, ram.size))
        assertThrows(IllegalArgumentException::class.java) {
            codec.write(0x80001000L, N64HleGuestOsThread(state = 3))
        }
        assertThrows(IllegalArgumentException::class.java) {
            codec.write(0x80001000L, N64HleGuestOsThread(flags = 0x10000))
        }
        assertThrows(IllegalArgumentException::class.java) {
            codec.write(0x80001000L, N64HleGuestOsThread(next = 0x1_8000_2000L))
        }
        assertEquals(0, ram.read32(0x1000))
    }
}
