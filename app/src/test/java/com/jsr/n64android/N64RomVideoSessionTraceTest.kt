package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class N64RomVideoSessionTraceTest {
    @Test fun sessionCapturesRealCpuBranchAndDelaySlotLoop() {
        val memory = N64Memory(64)
        memory.write32(0, 0x1000FFFF) // BEQ $zero, $zero, -1: branch back to itself
        memory.write32(4, 0) // Architectural delay slot
        val cpu = R4300Cpu(memory).apply { setProgramCounter(0L) }
        val bus = N64Bus()

        val session = N64RomVideoSession(
            bus = bus,
            cpu = cpu,
            instructionLimit = 6L,
            batchSize = 6,
            framePollInterval = 1_000L,
            rspPollInterval = 1_000L,
            progressPollInterval = 1_000L,
            onFrame = {}
        )

        val result = session.execute()
        val loop = session.diagnosticRepeatedInstructionLoop()

        assertEquals(N64ExecutionSession.State.COMPLETED, result.state)
        assertEquals(6L, session.executedInstructionCount())
        assertNotNull(loop)
        assertEquals(2, loop?.period)
        assertEquals(0L, loop?.pattern?.get(0)?.pc)
        assertEquals(4L, loop?.pattern?.get(1)?.pc)
    }
}
