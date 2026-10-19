package com.jovemaprendz55byte.n64android

import org.junit.Assert.assertEquals
import org.junit.Test

class N64RspCpuTest {
    private fun i(op: Int, rs: Int, rt: Int, imm: Int) =
        (op shl 26) or (rs shl 21) or (rt shl 16) or (imm and 0xFFFF)

    @Test fun executesScalarInstructionsAndLoadsStoresDmem() {
        val bus = N64MemoryBus()
        bus.writeWord(N64SpMemory.IMEM_BASE, i(15, 0, 1, 0x1234)) // LUI r1,1234
        bus.writeWord(N64SpMemory.IMEM_BASE + 4, i(13, 1, 1, 0x5678)) // ORI r1,r1,5678
        bus.writeWord(N64SpMemory.IMEM_BASE + 8, i(43, 0, 1, 0)) // SW r1,0(r0)
        bus.writeWord(N64SpMemory.IMEM_BASE + 12, i(35, 0, 2, 0)) // LW r2,0(r0)

        val rsp = N64RspCpu(bus)
        repeat(4) { rsp.step() }

        assertEquals(0x12345678, rsp.register(1))
        assertEquals(0x12345678, rsp.register(2))
        assertEquals(0x12345678, bus.readWord(N64SpMemory.DMEM_BASE))
        assertEquals(0, rsp.register(0))
    }

    @Test fun branchUsesOneInstructionDelaySlot() {
        val bus = N64MemoryBus()
        bus.writeWord(N64SpMemory.IMEM_BASE, i(9, 0, 1, 7)) // ADDIU r1,r0,7
        bus.writeWord(N64SpMemory.IMEM_BASE + 4, i(4, 1, 1, 2)) // BEQ r1,r1,+2
        bus.writeWord(N64SpMemory.IMEM_BASE + 8, i(9, 0, 2, 9)) // delay slot
        bus.writeWord(N64SpMemory.IMEM_BASE + 12, i(9, 0, 3, 3)) // skipped
        bus.writeWord(N64SpMemory.IMEM_BASE + 16, i(9, 0, 4, 4)) // target

        val rsp = N64RspCpu(bus)
        repeat(4) { rsp.step() }
        assertEquals(9, rsp.register(2))
        assertEquals(0, rsp.register(3))
        assertEquals(4, rsp.register(4))
    }

    @Test fun rejectsUnsupportedVectorInstructionInsteadOfSilentlyIgnoringIt() {
        val bus = N64MemoryBus()
        bus.writeWord(N64SpMemory.IMEM_BASE, 0x4A000000)
        val rsp = N64RspCpu(bus)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { rsp.step() }
    }
}
