package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class N64HleGuestContextSwitcherTest {
    private data class Fixture(
        val ram: N64Memory,
        val guest: N64HleGuestMemory,
        val cpu: R4300Cpu,
        val codec: N64HleGuestOsThreadCodec,
        val queues: N64HleGuestThreadQueue,
        val scheduler: N64HleGuestThreadScheduler,
        val switcher: N64HleGuestContextSwitcher,
        val runQueueAddress: Long,
        val runningThreadAddress: Long,
        val waitQueueAddress: Long,
        val tailAddress: Long
    )

    private fun fixture(): Fixture {
        val ram = N64Memory(8 * 1024 * 1024)
        val guest = N64HleGuestMemory(ram, ram.size)
        val cpu = R4300Cpu(ram)
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
        val switcher = N64HleGuestContextSwitcher(cpu, codec, scheduler)
        return Fixture(
            ram, guest, cpu, codec, queues, scheduler, switcher,
            runQueue, runningThread, waitQueue, tail
        )
    }

    private fun createThread(f: Fixture, address: Long, priority: Int, pc: Int, registerValue: Long) {
        val gpr = LongArray(32)
        gpr[16] = registerValue
        gpr[31] = 0xFFFFFFFF8000F000UL.toLong()
        f.codec.write(
            address,
            N64HleGuestOsThread(
                priority = priority,
                context = N64HleGuestOsThreadContext(gpr = gpr, pc = pc)
            )
        )
    }

    @Test
    fun higherPriorityPreemptionSavesCurrentContextAndRestoresNewThread() {
        val f = fixture()
        val low = 0x80001000L
        val high = 0x80001200L
        createThread(f, low, 10, 0x80002000.toInt(), 0x1111)
        createThread(f, high, 20, 0x80003000.toInt(), 0x2222)

        val first = f.switcher.startThread(low, 0xFFFFFFFF80004000UL.toLong())
        assertEquals(low, first.selectedThread)
        assertEquals(0xFFFFFFFF80002000UL.toLong(), f.cpu.pc)
        assertEquals(0x1111L, f.cpu.gpr[16])

        f.cpu.gpr[16] = 0xABCD
        f.cpu.gpr[4] = 0x4444
        val second = f.switcher.startThread(high, 0xFFFFFFFF80005000UL.toLong())

        assertTrue(second.switched)
        assertEquals(high, second.selectedThread)
        assertEquals(0xFFFFFFFF80003000UL.toLong(), f.cpu.pc)
        assertEquals(0x2222L, f.cpu.gpr[16])
        val savedLow = f.codec.read(low)
        assertEquals(0xABCDL, savedLow.context.gpr[16])
        assertEquals(0x4444L, savedLow.context.gpr[4])
        assertEquals(0x80005000, savedLow.context.pc)
    }

    @Test
    fun stoppingCurrentThreadRestoresPreviouslyRunnableThread() {
        val f = fixture()
        val low = 0x80001000L
        val high = 0x80001200L
        createThread(f, low, 10, 0x80002000.toInt(), 0x1111)
        createThread(f, high, 20, 0x80003000.toInt(), 0x2222)
        f.switcher.startThread(low, 0xFFFFFFFF80004000UL.toLong())
        f.cpu.gpr[16] = 0xAAAA
        f.switcher.startThread(high, 0xFFFFFFFF80005000UL.toLong())

        val stopped = f.switcher.stopCurrent(0xFFFFFFFF80006000UL.toLong())

        assertEquals(low, stopped.selectedThread)
        assertEquals(N64HleGuestOsThreadLayout.STATE_STOPPED, f.codec.read(high).state)
        assertEquals(0xFFFFFFFF80005000UL.toLong(), f.cpu.pc)
        assertEquals(0xAAAA, f.cpu.gpr[16])
        assertEquals(0x80006000, f.codec.read(high).context.pc)
    }

    @Test
    fun blockedThreadContextResumesAtSuppliedGuestContinuation() {
        val f = fixture()
        val thread = 0x80001000L
        createThread(f, thread, 15, 0x80002000.toInt(), 0x3333)
        f.switcher.startThread(thread, 0xFFFFFFFF80004000UL.toLong())
        f.cpu.gpr[4] = 0x12345678

        val blocked = f.switcher.blockCurrent(
            f.waitQueueAddress,
            0xFFFFFFFF80007000UL.toLong()
        )

        assertEquals(0L, blocked.selectedThread)
        assertEquals(N64HleGuestOsThreadLayout.STATE_WAITING, f.codec.read(thread).state)
        assertEquals(0x80007000, f.codec.read(thread).context.pc)
        assertEquals(0x12345678L, f.codec.read(thread).context.gpr[4])

        val resumed = f.switcher.startThread(thread, 0xFFFFFFFF80008000UL.toLong())
        assertEquals(thread, resumed.selectedThread)
        assertEquals(0xFFFFFFFF80007000UL.toLong(), f.cpu.pc)
        assertEquals(0x12345678L, f.cpu.gpr[4])
    }
}
