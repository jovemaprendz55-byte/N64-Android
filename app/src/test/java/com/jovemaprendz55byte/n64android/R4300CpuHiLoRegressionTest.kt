package com.jovemaprendz55byte.n64android

import org.junit.Assert.assertEquals
import org.junit.Test

/** Regression tests for R4300 integer multiply/divide and HI/LO transfers. */
class R4300CpuHiLoRegressionTest {

    @Test
    fun signedMultiplySplitsTwoComplementProductAcrossHiAndLo() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x09, 0, 1, -2),       // ADDIU r1, r0, -2
            iType(0x09, 0, 2, 3),        // ADDIU r2, r0, 3
            rType(1, 2, 0, 0, 0x18),     // MULT r1, r2
            rType(0, 0, 3, 0, 0x10),     // MFHI r3
            rType(0, 0, 4, 0, 0x12)      // MFLO r4
        ))

        repeat(5) { cpu.step() }

        assertEquals(-1L, cpu.register(3))
        assertEquals(-6L, cpu.register(4))
    }

    @Test
    fun unsignedMultiplyProducesExpectedLowWordAndHighWord() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x0D, 0, 1, 0xFFFF),    // ORI r1, r0, 0xFFFF
            iType(0x0D, 0, 2, 0xFFFF),    // ORI r2, r0, 0xFFFF
            rType(1, 2, 0, 0, 0x19),      // MULTU r1, r2
            rType(0, 0, 3, 0, 0x10),      // MFHI r3
            rType(0, 0, 4, 0, 0x12)       // MFLO r4
        ))

        repeat(5) { cpu.step() }

        assertEquals(0L, cpu.register(3))
        assertEquals(0xFFFE0001L, cpu.register(4))
    }

    @Test
    fun signedAndUnsignedDivisionSetQuotientAndRemainder() {
        val signedCpu = R4300Cpu()
        signedCpu.loadBytes(0, words(
            iType(0x09, 0, 1, -7),       // ADDIU r1, r0, -7
            iType(0x09, 0, 2, 3),        // ADDIU r2, r0, 3
            rType(1, 2, 0, 0, 0x1A),     // DIV r1, r2
            rType(0, 0, 3, 0, 0x10),     // MFHI r3
            rType(0, 0, 4, 0, 0x12)      // MFLO r4
        ))
        repeat(5) { signedCpu.step() }
        assertEquals(-1L, signedCpu.register(3))
        assertEquals(-2L, signedCpu.register(4))

        val unsignedCpu = R4300Cpu()
        unsignedCpu.loadBytes(0, words(
            iType(0x0D, 0, 1, -1),       // r1 = 0x00000000FFFFFFFF
            iType(0x09, 0, 2, 2),        // ADDIU r2, r0, 2
            rType(1, 2, 0, 0, 0x1B),     // DIVU r1, r2
            rType(0, 0, 3, 0, 0x10),     // MFHI r3
            rType(0, 0, 4, 0, 0x12)      // MFLO r4
        ))
        repeat(5) { unsignedCpu.step() }
        assertEquals(1L, unsignedCpu.register(3))
        assertEquals(2147483647L, unsignedCpu.register(4))
    }

    @Test
    fun moveToHiAndLoCanBeReadBack() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x09, 0, 1, -123),     // r1 = -123
            iType(0x09, 0, 2, 456),      // r2 = 456
            rType(1, 0, 0, 0, 0x11),     // MTHI r1
            rType(2, 0, 0, 0, 0x13),     // MTLO r2
            rType(0, 0, 3, 0, 0x10),     // MFHI r3
            rType(0, 0, 4, 0, 0x12)      // MFLO r4
        ))

        repeat(6) { cpu.step() }

        assertEquals(-123L, cpu.register(3))
        assertEquals(456L, cpu.register(4))
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
