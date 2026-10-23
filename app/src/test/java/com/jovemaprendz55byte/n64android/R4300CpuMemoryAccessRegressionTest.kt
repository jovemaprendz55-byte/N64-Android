package com.jovemaprendz55byte.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Regression tests for integer loads/stores through the CPU memory bus. */
class R4300CpuMemoryAccessRegressionTest {

    @Test
    fun byteHalfwordAndWordStoresRoundTripInBigEndianOrder() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x09, 0, 1, 0x100),  // r1 = data base
            iType(0x0D, 0, 2, 0x80A5), // r2 = 0x80A5
            iType(0x28, 1, 2, 0),      // SB r2, 0(r1)
            iType(0x29, 1, 2, 2),      // SH r2, 2(r1)
            iType(0x2B, 1, 2, 4),      // SW r2, 4(r1)
            iType(0x24, 1, 3, 0),      // LBU r3, 0(r1)
            iType(0x25, 1, 4, 2),      // LHU r4, 2(r1)
            iType(0x23, 1, 5, 4)       // LW r5, 4(r1)
        ))

        repeat(8) { cpu.step() }

        assertEquals(0xA5L, cpu.register(3))
        assertEquals(0x80A5L, cpu.register(4))
        assertEquals(0x80A5L, cpu.register(5))
        assertEquals(0xA5.toByte(), cpu.readByte(0x100))
        assertEquals(0x80.toByte(), cpu.readByte(0x102))
        assertEquals(0xA5.toByte(), cpu.readByte(0x103))
        assertEquals(0x000080A5, cpu.readWord(0x104))
    }

    @Test
    fun halfwordAndWordLoadsRejectMisalignedAddresses() {
        val halfwordCpu = R4300Cpu()
        halfwordCpu.loadBytes(0, words(
            iType(0x09, 0, 1, 0x101), // r1 = misaligned address
            iType(0x21, 1, 2, 0)      // LH r2, 0(r1)
        ))
        halfwordCpu.loadBytes(0x100, byteArrayOf(0x12, 0x34, 0x56, 0x78))
        halfwordCpu.step()
        assertThrows(IllegalArgumentException::class.java) { halfwordCpu.step() }

        val wordCpu = R4300Cpu()
        wordCpu.loadBytes(0, words(
            iType(0x09, 0, 1, 0x102), // r1 = misaligned address
            iType(0x23, 1, 2, 0)      // LW r2, 0(r1)
        ))
        wordCpu.loadBytes(0x100, byteArrayOf(0x12, 0x34, 0x56, 0x78))
        wordCpu.step()
        assertThrows(IllegalArgumentException::class.java) { wordCpu.step() }
    }

    private fun iType(op: Int, rs: Int, rt: Int, imm: Int): Int =
        (op shl 26) or (rs shl 21) or (rt shl 16) or (imm and 0xFFFF)

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
