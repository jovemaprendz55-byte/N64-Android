package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class R4300CpuTest {
    @Test fun executesAddiuAndKeepsZeroRegisterImmutable() {
        val memory = N64Memory(64)
        memory.write32(0, iType(0x09, 0, 1, 7))
        memory.write32(4, iType(0x09, 1, 2, -3))
        memory.write32(8, iType(0x09, 0, 0, 99))
        val cpu = R4300Cpu(memory)
        cpu.setProgramCounter(0)

        cpu.step()
        cpu.step()
        cpu.step()

        assertEquals(7L, cpu.gpr[1])
        assertEquals(4L, cpu.gpr[2])
        assertEquals(0L, cpu.gpr[0])
        assertEquals(12L, cpu.pc)
    }

    @Test fun branchExecutesDelaySlotBeforeTakingTarget() {
        val memory = N64Memory(64)
        memory.write32(0, iType(0x09, 0, 1, 5))
        memory.write32(4, iType(0x04, 1, 1, 2))
        memory.write32(8, iType(0x09, 0, 2, 8))
        memory.write32(12, iType(0x09, 0, 3, 9))
        memory.write32(16, iType(0x09, 0, 4, 10))

        val cpu = R4300Cpu(memory)
        cpu.setProgramCounter(0)
        repeat(3) { cpu.step() }
        assertEquals(8L, cpu.gpr[2])
        assertEquals(0L, cpu.gpr[3])
        assertEquals(16L, cpu.pc)
        cpu.step()
        assertEquals(10L, cpu.gpr[4])
    }

    @Test fun loadsAndStoresBigEndianWords() {
        val memory = N64Memory(64)
        memory.write32(0, iType(0x09, 0, 1, 32))
        memory.write32(4, iType(0x0F, 0, 2, 0x1234))
        memory.write32(8, iType(0x0D, 2, 2, 0x5678))
        memory.write32(12, iType(0x2B, 1, 2, 0))
        memory.write32(16, iType(0x23, 1, 3, 0))
        val cpu = R4300Cpu(memory)
        cpu.setProgramCounter(0)
        repeat(5) { cpu.step() }
        assertEquals(0x12345678L, cpu.gpr[3])
        assertEquals(0x12345678, memory.read32(32))
    }

    @Test fun rejectsUnalignedInstructionAndWordAccesses() {
        val memory = N64Memory(64)
        memory.write32(0, iType(0x23, 1, 2, 0))
        val cpu = R4300Cpu(memory)
        assertThrows(IllegalArgumentException::class.java) { cpu.setProgramCounter(2) }
        cpu.setProgramCounter(0)
        cpu.gpr[1] = 1
        assertThrows(IllegalArgumentException::class.java) { cpu.step() }
    }

    private fun iType(op: Int, rs: Int, rt: Int, imm: Int): Int =
        (op shl 26) or (rs shl 21) or (rt shl 16) or (imm and 0xFFFF)
}
