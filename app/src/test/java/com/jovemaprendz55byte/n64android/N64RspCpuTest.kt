package com.jovemaprendz55byte.n64android

import org.junit.Assert.assertEquals
import org.junit.Test

class N64RspCpuTest {
    private fun i(op: Int, rs: Int, rt: Int, imm: Int) =
        (op shl 26) or (rs shl 21) or (rt shl 16) or (imm and 0xFFFF)

    private fun r(rs: Int, rt: Int, rd: Int, shamt: Int, funct: Int) =
        (rs shl 21) or (rt shl 16) or (rd shl 11) or (shamt shl 6) or funct

    @Test fun executesScalarInstructionsAndLoadsStoresDmem() {
        val bus = N64MemoryBus()
        bus.writeWord(N64SpMemory.IMEM_BASE, i(15, 0, 1, 0x1234))
        bus.writeWord(N64SpMemory.IMEM_BASE + 4, i(13, 1, 1, 0x5678))
        bus.writeWord(N64SpMemory.IMEM_BASE + 8, i(43, 0, 1, 0))
        bus.writeWord(N64SpMemory.IMEM_BASE + 12, i(35, 0, 2, 0))

        val rsp = N64RspCpu(bus)
        repeat(4) { rsp.step() }

        assertEquals(0x12345678, rsp.register(1))
        assertEquals(0x12345678, rsp.register(2))
        assertEquals(0x12345678, bus.readWord(N64SpMemory.DMEM_BASE))
        assertEquals(0, rsp.register(0))
    }

    @Test fun branchUsesOneInstructionDelaySlot() {
        val bus = N64MemoryBus()
        bus.writeWord(N64SpMemory.IMEM_BASE, i(9, 0, 1, 7))
        bus.writeWord(N64SpMemory.IMEM_BASE + 4, i(4, 1, 1, 2))
        bus.writeWord(N64SpMemory.IMEM_BASE + 8, i(9, 0, 2, 9))
        bus.writeWord(N64SpMemory.IMEM_BASE + 12, i(9, 0, 3, 3))
        bus.writeWord(N64SpMemory.IMEM_BASE + 16, i(9, 0, 4, 4))

        val rsp = N64RspCpu(bus)
        repeat(4) { rsp.step() }
        assertEquals(9, rsp.register(2))
        assertEquals(0, rsp.register(3))
        assertEquals(4, rsp.register(4))
    }

    @Test fun supportsSignedAndUnsignedByteLoadsAndHalfwordStores() {
        val bus = N64MemoryBus()
        bus.writeWord(N64SpMemory.DMEM_BASE, 0x80FF1234.toInt())
        bus.writeWord(N64SpMemory.IMEM_BASE, i(32, 0, 1, 0)) // LB r1,0(r0)
        bus.writeWord(N64SpMemory.IMEM_BASE + 4, i(36, 0, 2, 1)) // LBU r2,1(r0)
        bus.writeWord(N64SpMemory.IMEM_BASE + 8, i(9, 0, 3, 0xABCD.toShort().toInt()))
        bus.writeWord(N64SpMemory.IMEM_BASE + 12, i(41, 0, 3, 4)) // SH r3,4(r0)
        bus.writeWord(N64SpMemory.IMEM_BASE + 16, i(37, 0, 4, 4)) // LHU r4,4(r0)

        val rsp = N64RspCpu(bus)
        repeat(5) { rsp.step() }

        assertEquals(-128, rsp.register(1))
        assertEquals(0xFF, rsp.register(2))
        assertEquals(0xABCD, rsp.register(4))
        assertEquals(0xABCD.toShort(), bus.readHalf(N64SpMemory.DMEM_BASE + 4))
    }

    @Test fun supportsUnsignedComparisonAndVariableShift() {
        val bus = N64MemoryBus()
        bus.writeWord(N64SpMemory.IMEM_BASE, i(15, 0, 1, 0x8000))
        bus.writeWord(N64SpMemory.IMEM_BASE + 4, i(9, 0, 2, 1))
        bus.writeWord(N64SpMemory.IMEM_BASE + 8, r(1, 2, 3, 0, 43)) // SLTU
        bus.writeWord(N64SpMemory.IMEM_BASE + 12, r(2, 1, 4, 0, 6)) // SRLV r4,r1,r2

        val rsp = N64RspCpu(bus)
        repeat(4) { rsp.step() }
        assertEquals(0, rsp.register(3))
        assertEquals(0x40000000, rsp.register(4))
    }

    @Test fun rejectsUnsupportedVectorInstructionInsteadOfSilentlyIgnoringIt() {
        val bus = N64MemoryBus()
        bus.writeWord(N64SpMemory.IMEM_BASE, 0x4A000000)
        val rsp = N64RspCpu(bus)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { rsp.step() }
    }
}
