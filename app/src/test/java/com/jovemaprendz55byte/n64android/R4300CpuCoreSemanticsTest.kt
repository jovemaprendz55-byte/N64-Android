package com.jovemaprendz55byte.n64android

import org.junit.Assert.assertEquals
import org.junit.Test

/** Focused regression coverage for R4300 integer operations and load extension. */
class R4300CpuCoreSemanticsTest {

    @Test
    fun logicalImmediatesZeroExtendAndPreserveExpectedBits() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x09, 0, 1, -1),        // ADDIU r1, r0, -1
            iType(0x0C, 1, 2, 0xF0F0),   // ANDI r2, r1, 0xF0F0
            iType(0x0D, 0, 3, 0x8001),   // ORI r3, r0, 0x8001
            iType(0x0E, 3, 4, 0x00FF)    // XORI r4, r3, 0x00FF
        ))

        repeat(4) { cpu.step() }

        assertEquals(-1L, cpu.register(1))
        assertEquals(0xF0F0L, cpu.register(2))
        assertEquals(0x8001L, cpu.register(3))
        assertEquals(0x80FEL, cpu.register(4))
    }

    @Test
    fun byteHalfwordAndWordLoadsApplySignedAndUnsignedExtension() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x09, 0, 1, 0x100),     // base = 0x100
            iType(0x20, 1, 2, 0),         // LB r2, 0(r1)
            iType(0x24, 1, 3, 0),         // LBU r3, 0(r1)
            iType(0x21, 1, 4, 2),         // LH r4, 2(r1)
            iType(0x25, 1, 5, 2),         // LHU r5, 2(r1)
            iType(0x23, 1, 6, 4),         // LW r6, 4(r1)
            iType(0x27, 1, 7, 4)          // LWU r7, 4(r1)
        ))
        cpu.loadBytes(0x100, byteArrayOf(
            0x80.toByte(), 0x7F, 0x80.toByte(), 0x01,
            0x80.toByte(), 0x00, 0x00, 0x01
        ))

        repeat(7) { cpu.step() }

        assertEquals(-128L, cpu.register(2))
        assertEquals(128L, cpu.register(3))
        assertEquals(-32767L, cpu.register(4))
        assertEquals(32769L, cpu.register(5))
        assertEquals(-2147483647L, cpu.register(6))
        assertEquals(2147483649L, cpu.register(7))
    }

    @Test
    fun writesToRegisterZeroAreDiscarded() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x09, 0, 0, 123),       // ADDIU r0, r0, 123
            iType(0x0D, 0, 0, 0xFFFF),    // ORI r0, r0, 0xFFFF
            rType(0, 0, 0, 0, 0x21)       // ADDU r0, r0, r0
        ))

        repeat(3) { cpu.step() }

        assertEquals(0L, cpu.register(0))
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
