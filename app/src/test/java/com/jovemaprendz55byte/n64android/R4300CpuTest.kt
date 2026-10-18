package com.jovemaprendz55byte.n64android

import org.junit.Assert.assertEquals
import org.junit.Test

class R4300CpuTest {
    @Test fun executesImmediateArithmeticAndKeepsZeroRegisterConstant() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x09, 0, 1, 10),
            iType(0x0D, 1, 2, 0x20),
            iType(0x09, 0, 0, 99)
        ))
        repeat(3) { cpu.step() }
        assertEquals(10L, cpu.register(1))
        assertEquals(42L, cpu.register(2))
        assertEquals(0L, cpu.register(0))
        assertEquals(12L, cpu.pc)
    }

    @Test fun executesLuiAndBigEndianLoadStore() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x0F, 0, 1, 0x1234),
            iType(0x2B, 0, 1, 0x100),
            iType(0x23, 0, 2, 0x100)
        ))
        repeat(3) { cpu.step() }
        assertEquals(0x12340000L, cpu.register(1))
        assertEquals(0x12340000L, cpu.register(2))
        assertEquals(0x12340000, cpu.readWord(0x100))
    }

    @Test fun branchAppliesAfterOneDelaySlot() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x09, 0, 1, 1),
            iType(0x04, 1, 1, 2),
            iType(0x09, 0, 2, 7),
            iType(0x09, 0, 3, 99),
            iType(0x09, 0, 4, 5)
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

    @Test fun supportsSignedAndUnsignedByteAndHalfwordLoads() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x20, 0, 1, 0x100),
            iType(0x24, 0, 2, 0x100),
            iType(0x21, 0, 3, 0x102),
            iType(0x25, 0, 4, 0x102)
        ))
        cpu.loadBytes(0x100, byteArrayOf(0x80.toByte(), 0x00, 0xFF.toByte(), 0xFE.toByte()))
        repeat(4) { cpu.step() }
        assertEquals(-128L, cpu.register(1))
        assertEquals(128L, cpu.register(2))
        assertEquals(-2L, cpu.register(3))
        assertEquals(65534L, cpu.register(4))
    }

    @Test fun supportsDoublewordArithmeticShiftsAndLoadStore() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x19, 0, 1, 0x1234),
            iType(0x3F, 0, 1, 0x108),
            iType(0x37, 0, 2, 0x108),
            rType(0, 2, 3, 1, 0x38)
        ))
        repeat(4) { cpu.step() }
        assertEquals(0x1234L, cpu.register(1))
        assertEquals(0x1234L, cpu.register(2))
        assertEquals(0x2468L, cpu.register(3))
        assertEquals(0x1234L, cpu.readLong(0x108))
    }

    @Test fun doublewordMemoryUsesBigEndianOrder() {
        val cpu = R4300Cpu()
        cpu.writeLong(0x80, 0x0123456789ABCDEFL)
        assertEquals(0x01234567, cpu.readWord(0x80))
        assertEquals(0x0123456789ABCDEFL, cpu.readLong(0x80))
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
