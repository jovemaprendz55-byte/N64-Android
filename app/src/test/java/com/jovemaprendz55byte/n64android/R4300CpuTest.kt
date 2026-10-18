package com.jovemaprendz55byte.n64android

import org.junit.Assert.assertEquals
import org.junit.Test

class R4300CpuTest {
    @Test fun executesImmediateArithmeticAndKeepsZeroRegisterConstant() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x09, 0, 1, 10),       // ADDIU r1, r0, 10
            iType(0x0D, 1, 2, 0x20),     // ORI r2, r1, 0x20
            iType(0x09, 0, 0, 99)        // ADDIU r0, r0, 99 (discarded)
        ))
        cpu.step()
        cpu.step()
        cpu.step()
        assertEquals(10L, cpu.register(1))
        assertEquals(42L, cpu.register(2))
        assertEquals(0L, cpu.register(0))
        assertEquals(12L, cpu.pc)
    }

    @Test fun executesLuiAndBigEndianLoadStore() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x0F, 0, 1, 0x1234),    // LUI r1, 0x1234
            iType(0x2B, 0, 1, 0x100),     // SW r1, 0x100(r0)
            iType(0x23, 0, 2, 0x100)      // LW r2, 0x100(r0)
        ))
        repeat(3) { cpu.step() }
        assertEquals(0x12340000L, cpu.register(1))
        assertEquals(0x12340000L, cpu.register(2))
        assertEquals(0x12340000, cpu.readWord(0x100))
    }

    @Test fun branchAppliesAfterOneDelaySlot() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x09, 0, 1, 1),         // 0x00 ADDIU r1, r0, 1
            iType(0x04, 1, 1, 2),         // 0x04 BEQ r1, r1, +2 -> 0x10
            iType(0x09, 0, 2, 7),         // 0x08 delay slot: ADDIU r2, r0, 7
            iType(0x09, 0, 3, 99),        // 0x0C skipped
            iType(0x09, 0, 4, 5)          // 0x10 target
        ))
        cpu.step()
        cpu.step()
        assertEquals(8L, cpu.pc)
        cpu.step()
        assertEquals(0x10L, cpu.pc)
        cpu.step()
        assertEquals(7L, cpu.register(2))
        assertEquals(0L, cpu.register(3))
        assertEquals(5L, cpu.register(4))
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
