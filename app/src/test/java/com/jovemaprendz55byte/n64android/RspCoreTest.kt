package com.jovemaprendz55byte.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RspCoreTest {
    @Test fun executesScalarArithmeticAndStoresResultInDmem() {
        val bus = N64MemoryBus()
        val rsp = RspCore(bus)
        bus.writeWord(N64SpMemory.IMEM_BASE, iType(0x09, 0, 1, 5))
        bus.writeWord(N64SpMemory.IMEM_BASE + 4, iType(0x09, 1, 2, 7))
        bus.writeWord(N64SpMemory.IMEM_BASE + 8, iType(0x2B, 0, 2, 0))
        bus.writeWord(N64SpMemory.IMEM_BASE + 12, 0x0000000D) // BREAK

        rsp.start()
        assertTrue(rsp.step())
        assertTrue(rsp.step())
        assertTrue(rsp.step())
        assertEquals(12, bus.readWord(N64SpMemory.DMEM_BASE))
        assertFalse(rsp.step())

        val status = bus.readWord(N64MemoryBus.SP_STATUS)
        assertEquals(1, status and 1) // HALT
        assertEquals(2, status and 2) // BROKE
        assertEquals(12, rsp.register(2))
    }

    @Test fun conditionalBranchExecutesDelaySlotAndSkipsUntakenInstructions() {
        val bus = N64MemoryBus()
        val rsp = RspCore(bus)
        bus.writeWord(N64SpMemory.IMEM_BASE, iType(0x09, 0, 1, 1)) // r1 = 1
        bus.writeWord(N64SpMemory.IMEM_BASE + 4, iType(0x04, 1, 1, 2)) // branch to PC 16
        bus.writeWord(N64SpMemory.IMEM_BASE + 8, iType(0x09, 0, 2, 7)) // delay slot
        bus.writeWord(N64SpMemory.IMEM_BASE + 12, iType(0x09, 0, 2, 99)) // skipped
        bus.writeWord(N64SpMemory.IMEM_BASE + 16, iType(0x2B, 0, 2, 0)) // store r2
        bus.writeWord(N64SpMemory.IMEM_BASE + 20, 0x0000000D) // BREAK

        rsp.start()
        repeat(5) { rsp.step() }

        assertEquals(7, bus.readWord(N64SpMemory.DMEM_BASE))
        assertEquals(7, rsp.register(2))
        assertEquals(20, rsp.pc)
    }

    @Test fun registerZeroCannotBeChanged() {
        val bus = N64MemoryBus()
        val rsp = RspCore(bus)
        bus.writeWord(N64SpMemory.IMEM_BASE, iType(0x09, 0, 0, 123))
        rsp.start()
        rsp.step()
        assertEquals(0, rsp.register(0))
    }

    @Test fun startRejectsUnalignedOrOutOfRangeEntryPoint() {
        val rsp = RspCore(N64MemoryBus())
        try {
            rsp.start(2)
            throw AssertionError("Expected unaligned entry point to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
        try {
            rsp.start(0x1000)
            throw AssertionError("Expected out-of-range entry point to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    private fun iType(op: Int, rs: Int, rt: Int, imm: Int): Int =
        (op shl 26) or (rs shl 21) or (rt shl 16) or (imm and 0xFFFF)
}
