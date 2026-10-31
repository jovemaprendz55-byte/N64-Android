package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Test

class N64CpuExecutionSessionTest {

    @Test
    fun executesRealR4300InstructionsThroughBoundedSession() {
        val memory = N64Memory(64)
        // addiu $t0, $zero, 42; addiu $t1, $t0, 1
        memory.write32(0, (0x09 shl 26) or (8 shl 16) or 42)
        memory.write32(4, (0x09 shl 26) or (8 shl 21) or (9 shl 16) or 1)
        val cpu = R4300Cpu(memory)
        cpu.setProgramCounter(0L)
        val session = N64CpuExecutionSession(cpu, instructionLimit = 2, batchSize = 1)

        val result = session.execute()

        assertEquals(N64ExecutionSession.State.COMPLETED, result.state)
        assertEquals(2L, result.executedInstructions)
        assertEquals(42L, cpu.gpr[8])
        assertEquals(43L, cpu.gpr[9])
        assertEquals(8L, cpu.pc)
    }

    @Test
    fun observerCanRequestCooperativeStopAfterInstruction() {
        val memory = N64Memory(64)
        memory.write32(0, (0x09 shl 26) or (8 shl 16) or 42)
        memory.write32(4, (0x09 shl 26) or (8 shl 21) or (9 shl 16) or 1)
        val cpu = R4300Cpu(memory)
        cpu.setProgramCounter(0L)
        var observed = 0
        val session = N64CpuExecutionSession(
            cpu = cpu,
            instructionLimit = 100,
            batchSize = 1,
            afterInstruction = {
                observed++
                observed == 1
            }
        )

        val result = session.execute()

        assertEquals(N64ExecutionSession.State.CANCELLED, result.state)
        assertEquals(1L, result.executedInstructions)
        assertEquals(1, observed)
        assertEquals(4L, cpu.pc)
        assertEquals(42L, cpu.gpr[8])
    }
    @Test
    fun beforeAndAfterObserversBracketEachRealInstruction() {
        val memory = N64Memory(64)
        memory.write32(0, (0x09 shl 26) or (8 shl 16) or 42)
        memory.write32(4, (0x09 shl 26) or (8 shl 21) or (9 shl 16) or 1)
        val cpu = R4300Cpu(memory)
        cpu.setProgramCounter(0L)
        val events = ArrayList<String>()
        val session = N64CpuExecutionSession(
            cpu = cpu,
            instructionLimit = 2,
            batchSize = 1,
            beforeInstruction = { observedCpu ->
                events.add("before:${observedCpu.pc}:${observedCpu.gpr[8]}")
            },
            afterInstruction = { observedCpu ->
                events.add("after:${observedCpu.pc}:${observedCpu.gpr[8]}")
                false
            }
        )

        val result = session.execute()

        assertEquals(N64ExecutionSession.State.COMPLETED, result.state)
        assertEquals(
            "Observed event sequence: $events",
            listOf("before:0:0", "after:4:42", "before:4:42", "after:8:43"),
            events
        )
    }
}
