package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class N64RspCoreTest {
    private fun addiu(rt: Int, rs: Int, imm: Int) =
        (0x09 shl 26) or (rs shl 21) or (rt shl 16) or (imm and 0xFFFF)

    private fun setup(vararg instructions: Int): Pair<N64Bus, N64RspCore> {
        val bus = N64Bus()
        bus.write32(N64Bus.SP_STATUS_REG, N64Bus.SP_STATUS_CMD_CLR_HALT)
        instructions.forEachIndexed { index, word ->
            bus.write32(N64Bus.SP_IMEM_BASE + index * 4, word)
        }
        return bus to N64RspCore(bus).also { it.reset(0) }
    }

    @Test
    fun executesScalarImmediateArithmeticAndStopsOnBreak() {
        val (bus, rsp) = setup(
            addiu(8, 0, 42),
            addiu(9, 8, 1),
            (0x2B shl 26) or (0 shl 21) or (9 shl 16),
            (0x23 shl 26) or (0 shl 21) or (10 shl 16),
            0x0000000D
        )

        assertEquals(5, rsp.run(20))
        assertEquals(42, rsp.gpr[8])
        assertEquals(43, rsp.gpr[9])
        assertEquals(43, rsp.gpr[10])
        assertEquals(43, bus.read32(N64Bus.SP_DMEM_BASE))
        assertTrue(rsp.halted)
        assertEquals(5L, rsp.executedInstructions)
    }

    @Test
    fun branchExecutesDelaySlotAndSkipsFallthrough() {
        val (bus, rsp) = setup(
            addiu(8, 0, 1),
            (0x04 shl 26) or (8 shl 21) or (8 shl 16) or 2,
            addiu(9, 0, 7),
            addiu(10, 0, 99),
            addiu(11, 0, 10)
        )

        assertEquals(4, rsp.run(4))
        assertEquals(7, rsp.gpr[9])
        assertEquals(0, rsp.gpr[10])
        assertEquals(10, rsp.gpr[11])
        assertEquals(20, rsp.pc)
        assertFalse(rsp.halted)
    }

    @Test
    fun executionIsBoundedByInstructionBudget() {
        val (_, rsp) = setup(addiu(8, 0, 1), addiu(9, 0, 2), addiu(10, 0, 3))

        assertEquals(2, rsp.run(2))
        assertEquals(8, rsp.pc)
        assertEquals(2L, rsp.executedInstructions)
    }
}
