package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class N64HleCpuSchedulerTest {
    @Test
    fun yieldSavesOutgoingR4300ContextAndRestoresNextThread() {
        val memory = N64Memory(0x100)
        val cpu = R4300Cpu(memory)
        val kernel = N64HleOsKernel()
        val scheduler = N64HleCpuScheduler(kernel, cpu)
        val template = cpu.snapshotContext()

        kernel.createThread(1, 5, 0x1000L)
        kernel.createThread(2, 5, 0x2000L)

        val firstRegisters = template.gpr.toMutableList().also { it[8] = 0x111L }
        val secondRegisters = template.gpr.toMutableList().also { it[8] = 0x222L }
        scheduler.registerInitialContext(1, template.copy(gpr = firstRegisters, pc = 0x1000L))
        scheduler.registerInitialContext(2, template.copy(gpr = secondRegisters, pc = 0x2000L))

        kernel.startThread(1)
        kernel.startThread(2)

        assertEquals(1, scheduler.dispatch()?.id)
        assertEquals(0x1000L, cpu.pc)
        assertEquals(0x111L, cpu.gpr[8])

        // Simulate guest execution changing the active thread's architectural state.
        cpu.gpr[8] = 0xAAAAL
        cpu.setProgramCounter(0x1010L)

        assertEquals(2, scheduler.yieldCurrent()?.id)
        assertEquals(0x2000L, cpu.pc)
        assertEquals(0x222L, cpu.gpr[8])

        cpu.gpr[8] = 0xBBBBL
        cpu.setProgramCounter(0x2010L)

        assertEquals(1, scheduler.yieldCurrent()?.id)
        assertEquals(0x1010L, cpu.pc)
        assertEquals(0xAAAAL, cpu.gpr[8])
        assertNotNull(scheduler.savedContext(2))
        assertEquals(0x2010L, scheduler.savedContext(2)?.pc)
        assertEquals(1, scheduler.activeThreadId())
    }

    @Test
    fun boundedQuantumExecutesGuestInstructionsAndPreservesContext() {
        val memory = N64Memory(0x1000)
        val cpu = R4300Cpu(memory)
        val kernel = N64HleOsKernel()
        val scheduler = N64HleCpuScheduler(kernel, cpu)
        val entry = 0xFFFFFFFF80000000UL.toLong()
        memory.write32(0, (0x09 shl 26) or (8 shl 16) or 42) // addiu t0,zero,42
        memory.write32(4, (0x09 shl 26) or (9 shl 16) or 43) // addiu t1,zero,43
        memory.write32(8, 0) // nop

        kernel.createThread(4, 1, entry)
        scheduler.registerInitialContext(4, cpu.snapshotContext().copy(pc = entry))
        scheduler.startThread(4)

        assertEquals(4, scheduler.dispatch()?.id)
        assertEquals(2, scheduler.runQuantum(2))
        assertEquals(42L, cpu.gpr[8])
        assertEquals(43L, cpu.gpr[9])
        assertEquals(entry + 8L, cpu.pc)
        assertEquals(0, scheduler.runQuantum(0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun initialContextMustStartAtThreadEntryPoint() {
        val cpu = R4300Cpu(N64Memory(0x100))
        val kernel = N64HleOsKernel()
        kernel.createThread(7, 1, 0x7000L)
        N64HleCpuScheduler(kernel, cpu)
            .registerInitialContext(7, cpu.snapshotContext().copy(pc = 0x8000L))
    }
}
