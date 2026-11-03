package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Test

class R4300Cop1ArithmeticTest {
    private fun cop1(fmt: Int, ft: Int, fs: Int, fd: Int, fn: Int): Int =
        (0x11 shl 26) or (fmt shl 21) or (ft shl 16) or (fs shl 11) or (fd shl 6) or fn

    @Test fun singlePrecisionAddAndMultiplyUseRawFprBits() {
        val memory = N64Memory(64)
        memory.write32(0, cop1(0x10, 2, 1, 3, 0x00)) // ADD.S f3, f1, f2
        memory.write32(4, cop1(0x10, 2, 3, 4, 0x02)) // MUL.S f4, f3, f2
        val cpu = R4300Cpu(memory)
        cpu.cop0[12] = 1L shl 29
        cpu.fpr[1] = java.lang.Float.floatToRawIntBits(1.5f).toLong()
        cpu.fpr[2] = java.lang.Float.floatToRawIntBits(2.0f).toLong()
        cpu.setProgramCounter(0)

        cpu.step()
        assertEquals(3.5f, java.lang.Float.intBitsToFloat(cpu.fpr[3].toInt()), 0.0f)
        cpu.step()
        assertEquals(7.0f, java.lang.Float.intBitsToFloat(cpu.fpr[4].toInt()), 0.0f)
    }

    @Test fun doublePrecisionSubtractAndDivide() {
        val memory = N64Memory(64)
        memory.write32(0, cop1(0x11, 2, 1, 3, 0x01)) // SUB.D f3, f1, f2
        memory.write32(4, cop1(0x11, 2, 3, 4, 0x03)) // DIV.D f4, f3, f2
        val cpu = R4300Cpu(memory)
        cpu.cop0[12] = 1L shl 29
        cpu.fpr[1] = java.lang.Double.doubleToRawLongBits(9.0)
        cpu.fpr[2] = java.lang.Double.doubleToRawLongBits(3.0)
        cpu.setProgramCounter(0)

        cpu.step()
        assertEquals(6.0, java.lang.Double.longBitsToDouble(cpu.fpr[3]), 0.0)
        cpu.step()
        assertEquals(2.0, java.lang.Double.longBitsToDouble(cpu.fpr[4]), 0.0)
    }

    @Test fun cop1ArithmeticRequiresCu1() {
        val memory = N64Memory(64)
        memory.write32(0, cop1(0x10, 2, 1, 3, 0x00))
        val cpu = R4300Cpu(memory)
        cpu.fpr[1] = java.lang.Float.floatToRawIntBits(1.0f).toLong()
        cpu.fpr[2] = java.lang.Float.floatToRawIntBits(2.0f).toLong()
        cpu.setProgramCounter(0)

        cpu.step()

        assertEquals(11L, (cpu.cop0[13] ushr 2) and 0x1FL)
        assertEquals(1L, (cpu.cop0[13] ushr 28) and 0x3L)
        assertEquals(0L, cpu.fpr[3])
    }
}
