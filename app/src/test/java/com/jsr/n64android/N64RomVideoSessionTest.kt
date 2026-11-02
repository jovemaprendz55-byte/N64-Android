package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class N64RomVideoSessionTest {

    @Test
    fun publishesChangedViFrameDuringRealCpuExecution() {
        val memory = N64Memory(1024 * 1024)
        val bus = N64Bus(ram = memory)
        // A real R4300 jump with a delay-slot NOP, looping at KSEG0.
        memory.write32(0, 0x08000000)
        memory.write32(4, 0)
        val cpu = R4300Cpu(bus)
        cpu.setProgramCounter(0xFFFFFFFF80000000UL.toLong())

        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_STATUS_OFFSET, 2)
        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_ORIGIN_OFFSET, 0x100)
        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_WIDTH_OFFSET, 2)
        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_H_START_OFFSET, (0x20 shl 16) or 0x22)
        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_X_SCALE_OFFSET, 0x400)
        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_V_START_OFFSET, (0x20 shl 16) or 0x24)
        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_Y_SCALE_OFFSET, 0x400)
        memory.write16(0x100, 0xF801)
        memory.write16(0x102, 0x07C1)

        val frames = mutableListOf<N64VideoFrame>()
        val session = N64RomVideoSession(
            bus = bus,
            cpu = cpu,
            instructionLimit = 8,
            batchSize = 2,
            framePollInterval = 2,
            onFrame = frames::add
        )

        val result = session.execute()

        assertEquals(N64ExecutionSession.State.COMPLETED, result.state)
        assertEquals(8L, result.executedInstructions)
        assertEquals(1L, session.publishedFrameCount())
        assertEquals(1, frames.size)
        assertNotNull(frames.single())
        assertEquals(2, frames.single().width)
        assertEquals(2, frames.single().height)
        assertEquals(0xFFFF0000.toInt(), frames.single().argbPixels[0])
        assertEquals(0xFF00FF00.toInt(), frames.single().argbPixels[1])
        assertTrue(session.executedInstructionCount() >= session.publishedFrameCount())
    }

    @Test
    fun advancesViAndDeliversScanlineInterruptDuringCpuExecution() {
        val memory = N64Memory(0x2000)
        memory.write32(0, 0x08000000) // J 0 keeps the CPU inside installed RDRAM.
        memory.write32(4, 0) // Branch delay slot.
        val bus = N64Bus(ram = memory)
        bus.write32(N64Bus.MI_INTR_MASK_REG, N64Bus.MI_INTR_MASK_VI_SET)
        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_INTR_OFFSET, 1)
        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_V_SYNC_OFFSET, 525)

        val cpu = R4300Cpu(bus)
        val startPc = 0xFFFFFFFF80000000UL.toLong()
        cpu.setProgramCounter(startPc)
        cpu.cop0[12] = 0x401L // IE=1 and IM[2]=1.

        val session = N64RomVideoSession(
            bus = bus,
            cpu = cpu,
            instructionLimit = N64Bus.VI_INSTRUCTIONS_PER_SCANLINE + 1L,
            batchSize = 128,
            rspPollInterval = 100_000,
            framePollInterval = 100_000,
            onFrame = {}
        )

        val result = session.execute()

        assertEquals(N64ExecutionSession.State.COMPLETED, result.state)
        assertEquals(N64Bus.VI_INSTRUCTIONS_PER_SCANLINE + 1L, result.executedInstructions)
        assertEquals(0, cpu.lastExceptionCode)
        assertEquals(0xFFFFFFFF80000180UL.toLong(), cpu.pc)
        assertTrue((cpu.cop0[13] and (1L shl 10)) != 0L)
        assertEquals(N64Bus.MI_INTR_VI, bus.read32(N64Bus.MI_INTR_REG) and N64Bus.MI_INTR_VI)
    }

    @Test
    fun reportsCpuProgressEvenWhenNoViFrameExists() {
        val memory = N64Memory(64)
        memory.write32(0, 0x08000000)
        memory.write32(4, 0)
        val bus = N64Bus(ram = memory)
        val cpu = R4300Cpu(bus)
        cpu.setProgramCounter(0xFFFFFFFF80000000UL.toLong())
        val progress = mutableListOf<Long>()
        val session = N64RomVideoSession(
            bus = bus,
            cpu = cpu,
            instructionLimit = 10,
            batchSize = 1,
            progressPollInterval = 2,
            framePollInterval = 100,
            onProgress = progress::add,
            onFrame = {}
        )

        val result = session.execute()

        assertEquals(N64ExecutionSession.State.COMPLETED, result.state)
        assertEquals(listOf(2L, 4L, 6L, 8L, 10L), progress)
        assertEquals(0L, session.publishedFrameCount())
    }

    @Test
    fun cooperativeStopStillWorksWhilePollingFrames() {
        val memory = N64Memory(64)
        memory.write32(0, 0x08000000)
        memory.write32(4, 0)
        val bus = N64Bus(ram = memory)
        val cpu = R4300Cpu(bus)
        cpu.setProgramCounter(0xFFFFFFFF80000000UL.toLong())
        val session = N64RomVideoSession(
            bus = bus,
            cpu = cpu,
            instructionLimit = 100,
            batchSize = 1,
            framePollInterval = 1,
            onFrame = {}
        )
        // A frame is not required for the execution controller to remain cancellable.
        session.requestStop()
        val result = session.execute()
        assertEquals(N64ExecutionSession.State.CANCELLED, result.state)
        assertEquals(0L, result.executedInstructions)
    }
    @Test
    fun schedulerStatusPollDoesNotAppearAsCpuMmioAccess() {
        val memory = N64Memory(1024)
        val bus = N64Bus(ram = memory)
        memory.write32(0, 0) // R4300 NOP
        val cpu = R4300Cpu(bus)
        cpu.setProgramCounter(0xFFFFFFFF80000000UL.toLong())

        val session = N64RomVideoSession(
            bus = bus,
            cpu = cpu,
            instructionLimit = 1,
            rspPollInterval = 1,
            framePollInterval = 100,
            onFrame = {}
        )

        val result = session.execute()

        assertEquals(N64ExecutionSession.State.COMPLETED, result.state)
        assertTrue(bus.diagnosticMmioHistory.none { it.startsWith("R SP ") })
    }

    @Test
    fun pumpsRspWhenCpuHasReleasedSpAndObservesBreak() {
        val memory = N64Memory(1024)
        val bus = N64Bus(ram = memory)
        memory.write32(0, 0) // R4300 NOP
        val cpu = R4300Cpu(bus)
        cpu.setProgramCounter(0xFFFFFFFF80000000UL.toLong())

        // RSP BREAK at IMEM[0]; release HALT as the CPU would.
        bus.write32(N64Bus.SP_IMEM_BASE, 0x0000000D)
        bus.write32(N64Bus.SP_PC_REG, 0)
        bus.write32(
            N64Bus.SP_STATUS_REG,
            N64Bus.SP_STATUS_CMD_CLR_BROKE or N64Bus.SP_STATUS_CMD_CLR_HALT
        )

        val session = N64RomVideoSession(
            bus = bus,
            cpu = cpu,
            instructionLimit = 1,
            rspPollInterval = 1,
            rspInstructionQuantum = 1,
            framePollInterval = 100,
            onFrame = {}
        )

        val result = session.execute()

        assertEquals(N64ExecutionSession.State.COMPLETED, result.state)
        assertEquals(1L, session.executedRspInstructionCount())
        assertEquals(0, session.lastRspInstructionAddress())
        assertEquals(0x0000000D, session.lastRspInstructionWord())
        assertTrue((bus.read32(N64Bus.SP_STATUS_REG) and N64Bus.SP_STATUS_BROKE) != 0)
        assertEquals(null, session.rspFailureMessage())
    }

}
