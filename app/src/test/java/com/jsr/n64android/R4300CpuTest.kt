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
        repeat(3) { cpu.step() }
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

    @Test fun mapsSignExtendedKseg0AndKseg1Addresses() {
        val memory = N64Memory(64)
        memory.write32(0, iType(0x09, 0, 1, 11))
        val cpu = R4300Cpu(memory)
        cpu.setProgramCounter(-0x80000000L)
        cpu.step()
        assertEquals(11L, cpu.gpr[1])
        assertEquals(-0x7FFFFFFCL, cpu.pc)
        cpu.setProgramCounter(-0x60000000L)
        cpu.step()
        assertEquals(11L, cpu.gpr[1])
    }

    @Test fun sltuComparesFullUnsigned64BitRegisters() {
        val memory = N64Memory(16)
        memory.write32(0, rType(1, 2, 3, 0x2B))
        val cpu = R4300Cpu(memory)
        cpu.setProgramCounter(0)
        cpu.gpr[1] = 0L
        cpu.gpr[2] = Long.MIN_VALUE
        cpu.step()
        assertEquals(1L, cpu.gpr[3])
    }

    @Test fun resetVectorReportsMissingPifRomInsteadOfOutOfBoundsRam() {
        val cpu = R4300Cpu(N64Bus())
        val error = assertThrows(UnsupportedOperationException::class.java) { cpu.step() }
        assertEquals(true, error.message!!.contains("PIF boot ROM is not loaded"))
    }

    @Test fun busReadsPifRomWhenExplicitlyProvided() {
        val pif = ByteArray(N64Bus.PIF_ROM_SIZE)
        pif[0] = 0x12
        pif[1] = 0x34
        pif[2] = 0x56
        pif[3] = 0x78
        val bus = N64Bus(pifRom = pif)
        assertEquals(0x12345678, bus.read32(N64Bus.PIF_ROM_BASE))
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

    private fun rType(rs: Int, rt: Int, rd: Int, funct: Int): Int =
        (rs shl 21) or (rt shl 16) or (rd shl 11) or funct
}
