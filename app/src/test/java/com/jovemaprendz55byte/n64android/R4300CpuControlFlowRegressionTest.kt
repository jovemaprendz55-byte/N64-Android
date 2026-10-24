package com.jovemaprendz55byte.n64android

import org.junit.Assert.assertEquals
import org.junit.Test

/** Additional control-flow regression tests for R4300 branches and indirect jumps. */
class R4300CpuControlFlowRegressionTest {

    @Test
    fun bltzLikelyTakenExecutesDelaySlotAndBranchesToTarget() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x09, 0, 1, -1),      // r1 = -1
            iType(0x01, 1, 0x02, 2),   // BLTZL r1, +2 -> address 16
            iType(0x09, 0, 2, 5),      // taken likely-branch delay slot executes
            iType(0x09, 0, 3, 7),      // skipped
            iType(0x09, 0, 4, 9)       // target
        ))

        repeat(4) { cpu.step() }

        assertEquals(5L, cpu.register(2))
        assertEquals(0L, cpu.register(3))
        assertEquals(9L, cpu.register(4))
        assertEquals(20L, cpu.pc)
    }

    @Test
    fun jrExecutesDelaySlotBeforeJumpingToRegisterTarget() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x09, 0, 1, 16),      // r1 = target address 16
            rType(1, 0, 0, 0, 0x08),   // JR r1
            iType(0x09, 0, 2, 5),      // delay slot
            iType(0x09, 0, 3, 7),      // skipped
            iType(0x09, 0, 4, 9)       // target
        ))

        repeat(4) { cpu.step() }

        assertEquals(5L, cpu.register(2))
        assertEquals(0L, cpu.register(3))
        assertEquals(9L, cpu.register(4))
        assertEquals(20L, cpu.pc)
    }

    @Test
    fun jalrWritesLinkRegisterAndExecutesDelaySlotBeforeJumping() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x09, 0, 1, 16),      // r1 = target address 16
            rType(1, 0, 5, 0, 0x09),   // JALR r5, r1
            iType(0x09, 0, 2, 5),      // delay slot
            iType(0x09, 0, 3, 7),      // skipped
            iType(0x09, 0, 4, 9)       // target
        ))

        repeat(4) { cpu.step() }

        assertEquals(12L, cpu.register(5))
        assertEquals(5L, cpu.register(2))
        assertEquals(0L, cpu.register(3))
        assertEquals(9L, cpu.register(4))
        assertEquals(20L, cpu.pc)
    }

    private fun iType(op: Int, rs: Int, rt: Int, imm: Int): Int =
        (op shl 26) or (rs shl 21) or (rt shl 16) or (imm and 0xFFFF)

    private fun rType(rs: Int, rt: Int, rd: Int, shamt: Int, funct: Int): Int =
        (rs shl 21) or (rt shl 16) or (rd shl 11) or (shamt shl 6) or funct

    private fun words(vararg instructions: Int): ByteArray {
        val bytes = ByteArray(instructions.size * 4)
        instructions.forEachIndexed { index, instruction ->
            val offset = index * 4
            bytes[offset] = (instruction ushr 24).toByte()
            bytes[offset + 1] = (instruction ushr 16).toByte()
            bytes[offset + 2] = (instruction ushr 8).toByte()
            bytes[offset + 3] = instruction.toByte()
        }
        return bytes
    }
}
