package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test

class N64HleR4300ExecutionSessionTest {
    @Test
    fun runsOnlyRequestedBudgetAndCanContinueInNextChunk() {
        val memory = N64HleGuestAddressSpace()
        memory.write32(0x80000400L, 0x2402002AL) // ADDIU r2, r0, 42
        memory.write32(0x80000404L, 0x24430008L) // ADDIU r3, r2, 8
        val state = N64HleR4300CpuState(0x80000400L)
        val session = N64HleR4300ExecutionSession(
            state,
            N64HleR4300InstructionStepper(memorySpace = memory)
        )

        val first = session.runChunk(1)
        assertEquals(1, first.executedInstructions)
        assertEquals(1L, first.totalExecutedInstructions)
        assertEquals(0x80000404L, first.programCounter)
        assertEquals(42L, state.readGpr(2))
        assertNotNull(first.lastInstruction)

        val second = session.runChunk(1)
        assertEquals(1, second.executedInstructions)
        assertEquals(2L, second.totalExecutedInstructions)
        assertEquals(0x80000408L, second.programCounter)
        assertEquals(50L, state.readGpr(3))
        assertFalse(second.hasPendingDelaySlot)
    }

    @Test
    fun rejectsZeroAndOversizedBudgetsWithoutExecuting() {
        val memory = N64HleGuestAddressSpace()
        val state = N64HleR4300CpuState(0x80000400L)
        val session = N64HleR4300ExecutionSession(
            state,
            N64HleR4300InstructionStepper(memorySpace = memory)
        )

        assertThrows(IllegalArgumentException::class.java) { session.runChunk(0) }
        assertThrows(IllegalArgumentException::class.java) {
            session.runChunk(N64HleR4300ExecutionSession.MAX_INSTRUCTIONS_PER_CHUNK + 1)
        }
        assertEquals(0L, session.totalExecutedInstructions)
        assertEquals(0x80000400L, state.programCounter)
    }

    @Test
    fun failedFetchDoesNotIncrementExecutedInstructionCount() {
        val memory = N64HleGuestAddressSpace()
        val state = N64HleR4300CpuState(0xA1000000L) // KSEG1 address outside configured 8 MiB RDRAM
        val session = N64HleR4300ExecutionSession(
            state,
            N64HleR4300InstructionStepper(memorySpace = memory)
        )

        assertThrows(IllegalArgumentException::class.java) { session.runChunk(1) }
        assertEquals(0L, session.totalExecutedInstructions)
        assertEquals(0x80000400L, state.programCounter)
    }
}
