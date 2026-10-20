package com.jovemaprendz55byte.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RspVectorLogicTest {
    @Test fun nandNorAndXnorInvertTheExpectedSixteenBitLaneResults() {
        val bus = N64MemoryBus()
        val rsp = RspCore(bus)

        bus.writeWord(N64SpMemory.IMEM_BASE, iType(0x09, 0, 1, 3)) // r1 = 3
        bus.writeWord(N64SpMemory.IMEM_BASE + 4, iType(0x09, 0, 2, 5)) // r2 = 5
        bus.writeWord(N64SpMemory.IMEM_BASE + 8, cop2(4, 1, 1)) // v1[0] = 3
        bus.writeWord(N64SpMemory.IMEM_BASE + 12, cop2(4, 2, 2)) // v2[0] = 5

        bus.writeWord(N64SpMemory.IMEM_BASE + 16, vectorAlu(2, 1, 3, 0x29)) // VNAND
        bus.writeWord(N64SpMemory.IMEM_BASE + 20, cop2(0, 4, 3))
        bus.writeWord(N64SpMemory.IMEM_BASE + 24, iType(0x2B, 0, 4, 0))

        bus.writeWord(N64SpMemory.IMEM_BASE + 28, vectorAlu(2, 1, 5, 0x2B)) // VNOR
        bus.writeWord(N64SpMemory.IMEM_BASE + 32, cop2(0, 6, 5))
        bus.writeWord(N64SpMemory.IMEM_BASE + 36, iType(0x2B, 0, 6, 4))

        bus.writeWord(N64SpMemory.IMEM_BASE + 40, vectorAlu(2, 1, 7, 0x2D)) // VNXOR
        bus.writeWord(N64SpMemory.IMEM_BASE + 44, cop2(0, 8, 7))
        bus.writeWord(N64SpMemory.IMEM_BASE + 48, iType(0x2B, 0, 8, 8))

        rsp.start()
        repeat(13) { assertTrue("RSP stopped at instruction $it", rsp.step()) }

        assertEquals(-2, bus.readWord(N64SpMemory.DMEM_BASE)) // 16-bit ~ (3 & 5)
        assertEquals(-8, bus.readWord(N64SpMemory.DMEM_BASE + 4)) // 16-bit ~ (3 | 5)
        assertEquals(-7, bus.readWord(N64SpMemory.DMEM_BASE + 8)) // 16-bit ~ (3 ^ 5)
    }

    private fun vectorAlu(vt: Int, vs: Int, vd: Int, funct: Int): Int =
        (0x12 shl 26) or (0x10 shl 21) or (vt shl 16) or
            (vs shl 11) or (vd shl 6) or (funct and 63)

    private fun cop2(rs: Int, rt: Int, vd: Int): Int =
        (0x12 shl 26) or (rs shl 21) or (rt shl 16) or (vd shl 11)

    private fun iType(op: Int, rs: Int, rt: Int, imm: Int): Int =
        (op shl 26) or (rs shl 21) or (rt shl 16) or (imm and 0xFFFF)
}
