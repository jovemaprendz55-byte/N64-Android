package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class N64HleRspExecutionSessionTest {
    private fun put(memory: N64HleGuestAddressSpace, pc: Int, instruction: Int) {
        memory.write32(0xA4001000L + pc, instruction.toLong() and 0xFFFF_FFFFL)
    }

    @Test
    fun runsOnlyTheRequestedBudgetAndResumesAtNextInstruction() {
        val memory = N64HleGuestAddressSpace()
        put(memory, 0, (0x09 shl 26) or (1 shl 16) or 7) // ADDIU r1,r0,7
        put(memory, 4, (0x09 shl 26) or (2 shl 16) or 9) // ADDIU r2,r0,9
        val cpu = N64HleRspCpuState()
        val session = N64HleRspExecutionSession(cpu, N64HleRspInstructionStepper(memory))

        val first = session.runChunk(1)
        assertEquals(1, first.executedInstructions)
        assertEquals(1L, first.totalExecutedInstructions)
        assertEquals(4, first.programCounter)
        assertEquals(N64HleRspExecutionSession.StopReason.BUDGET_EXHAUSTED, first.stopReason)
        assertEquals(7, cpu.readGpr(1))
        assertEquals(0, cpu.readGpr(2))

        val second = session.runChunk(1)
        assertEquals(2L, second.totalExecutedInstructions)
        assertEquals(8, second.programCounter)
        assertEquals(9, cpu.readGpr(2))
    }

    @Test
    fun breakStopsChunkImmediatelyAndReportsHalted() {
        val memory = N64HleGuestAddressSpace()
        put(memory, 0, (0x09 shl 26) or (1 shl 16) or 3)
        put(memory, 4, 0x0000000D) // BREAK
        put(memory, 8, (0x09 shl 26) or (2 shl 16) or 5)
        val cpu = N64HleRspCpuState()
        val session = N64HleRspExecutionSession(cpu, N64HleRspInstructionStepper(memory))

        val result = session.runChunk(20)

        assertEquals(2, result.executedInstructions)
        assertEquals(2L, result.totalExecutedInstructions)
        assertTrue(result.halted)
        assertEquals(N64HleRspExecutionSession.StopReason.HALTED, result.stopReason)
        assertEquals(3, cpu.readGpr(1))
        assertEquals(0, cpu.readGpr(2))
        assertFalse(result.lastInstruction == null)
        assertEquals(4, session.diagnostics().lastSuccessfulInstructionPc)
    }

    @Test
    fun unsupportedInstructionIsRecordedAndRethrownWithoutCountingIt() {
        val memory = N64HleGuestAddressSpace()
        put(memory, 0, 0xFC000000.toInt())
        val session = N64HleRspExecutionSession(
            N64HleRspCpuState(),
            N64HleRspInstructionStepper(memory)
        )

        assertThrows(N64HleRspUnsupportedInstructionException::class.java) {
            session.runChunk(1)
        }
        val diagnostics = session.diagnostics()
        assertEquals(0L, diagnostics.totalExecutedInstructions)
        assertEquals(0, diagnostics.lastAttemptedInstructionPc)
        assertEquals(N64HleRspUnsupportedInstructionException::class.java.name, diagnostics.failureType)
        assertTrue(diagnostics.failureMessage!!.contains("Unsupported RSP instruction"))
    }

    @Test
    fun rejectsUnboundedOrZeroInstructionBudgets() {
        val memory = N64HleGuestAddressSpace()
        val session = N64HleRspExecutionSession(
            N64HleRspCpuState(),
            N64HleRspInstructionStepper(memory)
        )
        assertThrows(IllegalArgumentException::class.java) { session.runChunk(0) }
        assertThrows(IllegalArgumentException::class.java) {
            session.runChunk(N64HleRspExecutionSession.MAX_INSTRUCTIONS_PER_CHUNK + 1)
        }
    }
}
