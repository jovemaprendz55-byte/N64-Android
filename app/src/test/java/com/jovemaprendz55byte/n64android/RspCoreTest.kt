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

    @Test fun supportsSignedImmediateCompareXorAndSignedHalfwordLoad() {
        val bus = N64MemoryBus()
        val rsp = RspCore(bus)
        bus.writeHalf(N64SpMemory.DMEM_BASE + 16, 0xFFFE.toShort())
        bus.writeWord(N64SpMemory.IMEM_BASE, iType(0x09, 0, 1, -1)) // r1 = -1
        bus.writeWord(N64SpMemory.IMEM_BASE + 4, iType(0x0A, 1, 2, 0)) // slti r2,r1,0 => 1
        bus.writeWord(N64SpMemory.IMEM_BASE + 8, iType(0x0E, 1, 3, 0x00FF)) // xor r3 => -256
        bus.writeWord(N64SpMemory.IMEM_BASE + 12, iType(0x21, 0, 4, 16)) // lh r4,16(r0)
        bus.writeWord(N64SpMemory.IMEM_BASE + 16, iType(0x2B, 0, 3, 0)) // sw r3,0(r0)

        rsp.start()
        repeat(5) { rsp.step() }

        assertEquals(1, rsp.register(2))
        assertEquals(-256, rsp.register(3))
        assertEquals(-2, rsp.register(4))
        assertEquals(-256, bus.readWord(N64SpMemory.DMEM_BASE))
    }

    @Test fun emulatorCoreRunsRspAgainstSharedSpMemoryAndCountsBreak() {
        val core = N64EmulatorCore()
        core.memoryBus.writeWord(N64SpMemory.IMEM_BASE, iType(0x09, 0, 1, 9))
        core.memoryBus.writeWord(N64SpMemory.IMEM_BASE + 4, iType(0x2B, 0, 1, 0))
        core.memoryBus.writeWord(N64SpMemory.IMEM_BASE + 8, 0x0000000D) // BREAK

        val result = core.executeRspFromImem(entryPoint = 0, instructionBudget = 10)

        assertEquals(3, result.instructionsExecuted)
        assertTrue(result.halted)
        assertEquals(8, result.programCounter)
        assertEquals(9, core.memoryBus.readWord(N64SpMemory.DMEM_BASE))
        val status = core.memoryBus.readWord(N64MemoryBus.SP_STATUS)
        assertEquals(1, status and 1) // HALT
        assertEquals(2, status and 2) // BROKE
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


    @Test fun cop0CanStartDmaFromRdramIntoDmem() {
        val bus = N64MemoryBus()
        val rsp = RspCore(bus)
        val payload = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x08)
        bus.loadRdram(0x200, payload)

        // Set SP_MEM_ADDR=0x100, SP_DRAM_ADDR=0x200, then SP_RD_LEN=7 (one 8-byte block).
        bus.writeWord(N64SpMemory.IMEM_BASE, iType(0x09, 0, 1, 0x100))
        bus.writeWord(N64SpMemory.IMEM_BASE + 4, cop0(4, 1, 0))
        bus.writeWord(N64SpMemory.IMEM_BASE + 8, iType(0x09, 0, 2, 0x200))
        bus.writeWord(N64SpMemory.IMEM_BASE + 12, cop0(4, 2, 1))
        bus.writeWord(N64SpMemory.IMEM_BASE + 16, iType(0x09, 0, 3, 7))
        bus.writeWord(N64SpMemory.IMEM_BASE + 20, cop0(4, 3, 2))

        rsp.start()
        repeat(6) { assertTrue(rsp.step()) }

        for (i in payload.indices) {
            assertEquals(payload[i], bus.readByte(N64SpMemory.DMEM_BASE + 0x100 + i))
        }
    }

    @Test fun cop0CanReadSpStatusIntoScalarRegister() {
        val bus = N64MemoryBus()
        val rsp = RspCore(bus)
        bus.writeWord(N64SpMemory.IMEM_BASE, cop0(0, 1, 4))
        rsp.start()
        assertTrue(rsp.step())
        assertEquals(0, rsp.register(1) and 1) // start cleared HALT
    }


    @Test fun cop2MovesScalarValuesToAndFromVectorLanes() {
        val bus = N64MemoryBus()
        val rsp = RspCore(bus)
        bus.writeWord(N64SpMemory.IMEM_BASE, iType(0x09, 0, 1, 0x1234)) // r1 = 0x1234
        bus.writeWord(N64SpMemory.IMEM_BASE + 4, cop2(4, 1, 3, 4)) // MTC2 r1, v3[e=4]
        bus.writeWord(N64SpMemory.IMEM_BASE + 8, cop2(0, 2, 3, 4)) // MFC2 r2, v3[e=4]
        bus.writeWord(N64SpMemory.IMEM_BASE + 12, iType(0x2B, 0, 2, 0)) // store result

        rsp.start()
        repeat(4) { assertTrue(rsp.step()) }

        assertEquals(0x1234, bus.readWord(N64SpMemory.DMEM_BASE))
        assertEquals(0x1234, rsp.register(2))
    }


    @Test fun vectorAddAndBitwiseLogicOperateOnLanes() {
        val bus = N64MemoryBus()
        val rsp = RspCore(bus)
        bus.writeWord(N64SpMemory.IMEM_BASE, iType(0x09, 0, 1, 3)) // r1 = 3
        bus.writeWord(N64SpMemory.IMEM_BASE + 4, iType(0x09, 0, 2, 4)) // r2 = 4
        bus.writeWord(N64SpMemory.IMEM_BASE + 8, cop2(4, 1, 1, 0)) // v1[0] = 3
        bus.writeWord(N64SpMemory.IMEM_BASE + 12, cop2(4, 2, 2, 0)) // v2[0] = 4
        bus.writeWord(N64SpMemory.IMEM_BASE + 16, vectorAlu(2, 1, 3, 0x10)) // v3 = v1 + v2
        bus.writeWord(N64SpMemory.IMEM_BASE + 20, cop2(0, 4, 3, 0)) // r4 = v3[0]
        bus.writeWord(N64SpMemory.IMEM_BASE + 24, iType(0x2B, 0, 4, 0))
        bus.writeWord(N64SpMemory.IMEM_BASE + 28, vectorAlu(2, 1, 5, 0x2C)) // v5 = v1 XOR v2
        bus.writeWord(N64SpMemory.IMEM_BASE + 32, cop2(0, 6, 5, 0)) // r6 = v5[0]
        bus.writeWord(N64SpMemory.IMEM_BASE + 36, iType(0x2B, 0, 6, 4))

        rsp.start()
        repeat(10) { assertTrue(rsp.step()) }

        assertEquals(7, bus.readWord(N64SpMemory.DMEM_BASE))
        assertEquals(7, rsp.register(4))
        assertEquals(7, bus.readWord(N64SpMemory.DMEM_BASE + 4))
        assertEquals(7, rsp.register(6))
    }

    private fun vectorAlu(vt: Int, vs: Int, vd: Int, funct: Int): Int =
        (0x12 shl 26) or (0x10 shl 21) or (vt shl 16) or
            (vs shl 11) or (vd shl 6) or (funct and 63)

    private fun cop0(rs: Int, rt: Int, rd: Int): Int =
        (0x10 shl 26) or (rs shl 21) or (rt shl 16) or (rd shl 11)

    private fun cop2(rs: Int, rt: Int, vd: Int, element: Int): Int =
        (0x12 shl 26) or (rs shl 21) or (rt shl 16) or (vd shl 11) or (element shl 7)

    private fun iType(op: Int, rs: Int, rt: Int, imm: Int): Int =
        (op shl 26) or (rs shl 21) or (rt shl 16) or (imm and 0xFFFF)
}
