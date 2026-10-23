package com.jovemaprendz55byte.n64android

import org.junit.Assert.assertEquals
import org.junit.Test

/** Regression tests for R4300 integer multiply/divide and HI/LO transfers. */
class R4300CpuArithmeticRegressionTest {

    @Test
    fun signedMultiplyAndDivideProduceExpectedHiLoValues() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x09, 0, 1, -7),      // ADDIU r1, r0, -7
            iType(0x09, 0, 2, 3),       // ADDIU r2, r0, 3
            rType(1, 2, 0, 0, 0x18),    // MULT r1, r2
            rType(0, 0, 3, 0, 0x12),    // MFLO r3 -> -21
            rType(0, 0, 4, 0, 0x10),    // MFHI r4 -> -1 (sign-extended 32-bit HI)
            rType(1, 2, 0, 0, 0x1A),    // DIV r1, r2
            rType(0, 0, 5, 0, 0x12),    // MFLO r5 -> -2 (truncated toward zero)
            rType(0, 0, 6, 0, 0x10)     // MFHI r6 -> -1
        ))

        repeat(8) { cpu.step() }

        assertEquals(-21L, cpu.register(3))
        assertEquals(-1L, cpu.register(4))
        assertEquals(-2L, cpu.register(5))
        assertEquals(-1L, cpu.register(6))
    }

    @Test
    fun unsignedDivideTreatsNegativeRegisterBitsAsUnsigned32BitValue() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x09, 0, 1, -7),      // r1 low 32 bits = 0xFFFFFFF9
            iType(0x09, 0, 2, 3),
            rType(1, 2, 0, 0, 0x1B),    // DIVU r1, r2
            rType(0, 0, 3, 0, 0x12),    // MFLO r3
            rType(0, 0, 4, 0, 0x10)     // MFHI r4
        ))

        repeat(5) { cpu.step() }

        assertEquals(1_431_655_763L, cpu.register(3))
        assertEquals(0L, cpu.register(4))
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
