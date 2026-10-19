package com.jovemaprendz55byte.n64android

import org.junit.Assert.assertEquals
import org.junit.Test

class N64RspCpuTest {
    private fun i(op: Int, rs: Int, rt: Int, imm: Int) =
        (op shl 26) or (rs shl 21) or (rt shl 16) or (imm and 0xFFFF)

    private fun r(rs: Int, rt: Int, rd: Int, shamt: Int, funct: Int) =
        (rs shl 21) or (rt shl 16) or (rd shl 11) or (shamt shl 6) or funct

    private fun cop0(rs: Int, rt: Int, rd: Int) =
        (16 shl 26) or (rs shl 21) or (rt shl 16) or (rd shl 11)

    @Test fun executesBasicVectorArithmeticAndLogic() {
        val bus = N64MemoryBus()
        // COP2 vector instruction: element selector 0, vt=2, vs=1, vd=3.
        fun v(vt: Int, vs: Int, vd: Int, funct: Int) =
            (18 shl 26) or (16 shl 21) or (vt shl 16) or
                (vs shl 11) or (vd shl 6) or funct
        bus.writeWord(N64SpMemory.IMEM_BASE, v(2, 1, 3, 0x10)) // VADD
        bus.writeWord(N64SpMemory.IMEM_BASE + 4, v(2, 1, 4, 0x11)) // VSUB
        bus.writeWord(N64SpMemory.IMEM_BASE + 8, v(2, 1, 5, 0x28)) // VAND
        bus.writeWord(N64SpMemory.IMEM_BASE + 12, v(2, 1, 6, 0x2C)) // VXOR
        val rsp = N64RspCpu(bus)
        for (lane in 0..7) {
            rsp.setVectorLane(1, lane, (lane * 100).toShort())
            rsp.setVectorLane(2, lane, (lane * 10).toShort())
        }
        repeat(4) { rsp.step() }
        for (lane in 0..7) {
            assertEquals((lane * 110).toShort(), rsp.vectorLane(3, lane))
            assertEquals((lane * 90).toShort(), rsp.vectorLane(4, lane))
            assertEquals(((lane * 100) and (lane * 10)).toShort(), rsp.vectorLane(5, lane))
            assertEquals(((lane * 100) xor (lane * 10)).toShort(), rsp.vectorLane(6, lane))
        }
    }

    @Test fun vectorAddAndSubtractSaturateSignedLanes() {
        val bus = N64MemoryBus()
        fun v(vt: Int, vs: Int, vd: Int, funct: Int) =
            (18 shl 26) or (16 shl 21) or (vt shl 16) or
                (vs shl 11) or (vd shl 6) or funct
        bus.writeWord(N64SpMemory.IMEM_BASE, v(2, 1, 3, 0x10))
        bus.writeWord(N64SpMemory.IMEM_BASE + 4, v(2, 1, 4, 0x11))
        val rsp = N64RspCpu(bus)
        rsp.setVectorLane(1, 0, Short.MAX_VALUE)
        rsp.setVectorLane(2, 0, 1)
        rsp.setVectorLane(1, 1, Short.MIN_VALUE)
        rsp.setVectorLane(2, 1, 1)
        repeat(2) { rsp.step() }
        assertEquals(Short.MAX_VALUE, rsp.vectorLane(3, 0))
        assertEquals(Short.MIN_VALUE, rsp.vectorLane(4, 1))
    }

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
    @Test fun runHonorsSpHaltAndBreakStatus() {
        val bus = N64MemoryBus()
        bus.writeWord(N64SpMemory.IMEM_BASE, i(9, 0, 1, 42)) // ADDIU r1,r0,42
        bus.writeWord(N64SpMemory.IMEM_BASE + 4, 13) // BREAK
        bus.writeWord(N64SpMemory.IMEM_BASE + 8, i(9, 0, 2, 99)) // Must not run

        val rsp = N64RspCpu(bus)
        assertEquals(0, rsp.run(10)) // SP starts halted
        assertEquals(1, bus.readWord(N64MemoryBus.SP_STATUS) and 1)

        bus.writeWord(N64MemoryBus.SP_STATUS, 1) // Clear HALT
        assertEquals(2, rsp.run(10))
        assertEquals(42, rsp.register(1))
        assertEquals(0, rsp.register(2))
        assertEquals(3, bus.readWord(N64MemoryBus.SP_STATUS) and 3) // HALT | BROKE
        assertEquals(0, rsp.run(10)) // Remains halted after BREAK
    }

    @Test fun runStopsAtInstructionBudgetWithoutSettingBroke() {
        val bus = N64MemoryBus()
        bus.writeWord(N64SpMemory.IMEM_BASE, i(9, 0, 1, 1))
        bus.writeWord(N64SpMemory.IMEM_BASE + 4, i(9, 1, 1, 1))
        val rsp = N64RspCpu(bus)
        bus.writeWord(N64MemoryBus.SP_STATUS, 1)

        assertEquals(1, rsp.run(1))
        assertEquals(1, rsp.register(1))
        assertEquals(0, bus.readWord(N64MemoryBus.SP_STATUS) and 3)
        assertEquals(1, rsp.run(1))
        assertEquals(2, rsp.register(1))
    }

    @Test fun cop0TransfersReadAndWriteSpRegisters() {
        val bus = N64MemoryBus()
        val rsp = N64RspCpu(bus)
        // Set SP_MEM_ADDR to 0x100, then read it back with MFC0.
        bus.writeWord(N64SpMemory.IMEM_BASE, i(9, 0, 1, 0x100))
        bus.writeWord(N64SpMemory.IMEM_BASE + 4, cop0(4, 1, 0)) // MTC0 r1, SP_MEM_ADDR
        bus.writeWord(N64SpMemory.IMEM_BASE + 8, cop0(0, 2, 0)) // MFC0 r2, SP_MEM_ADDR

        rsp.step()
        rsp.step()
        rsp.step()

        assertEquals(0x100, bus.readWord(N64MemoryBus.SP_MEM_ADDR))
        assertEquals(0x100, rsp.register(2))
    }

    @Test fun cop0StatusWriteCanHaltRspRunLoop() {
        val bus = N64MemoryBus()
        val rsp = N64RspCpu(bus)
        bus.writeWord(N64SpMemory.IMEM_BASE, i(9, 0, 1, 2)) // Set HALT command
        bus.writeWord(N64SpMemory.IMEM_BASE + 4, cop0(4, 1, 4)) // MTC0 r1, SP_STATUS
        bus.writeWord(N64SpMemory.IMEM_BASE + 8, i(9, 0, 2, 99)) // Must not execute
        bus.writeWord(N64MemoryBus.SP_STATUS, 1) // Start RSP

        assertEquals(2, rsp.run(10))
        assertEquals(0, rsp.register(2))
        assertEquals(1, bus.readWord(N64MemoryBus.SP_STATUS) and 1)
    }


    @Test fun supportsSignedMultiplyAndDivideThroughHiLo() {
        val bus = N64MemoryBus()
        bus.writeWord(N64SpMemory.IMEM_BASE, i(9, 0, 1, -7)) // ADDIU r1,r0,-7
        bus.writeWord(N64SpMemory.IMEM_BASE + 4, i(9, 0, 2, 3)) // ADDIU r2,r0,3
        bus.writeWord(N64SpMemory.IMEM_BASE + 8, r(1, 2, 0, 0, 24)) // MULT
        bus.writeWord(N64SpMemory.IMEM_BASE + 12, r(0, 0, 3, 0, 16)) // MFHI r3
        bus.writeWord(N64SpMemory.IMEM_BASE + 16, r(0, 0, 4, 0, 18)) // MFLO r4
        bus.writeWord(N64SpMemory.IMEM_BASE + 20, r(1, 2, 0, 0, 26)) // DIV
        bus.writeWord(N64SpMemory.IMEM_BASE + 24, r(0, 0, 5, 0, 18)) // MFLO r5
        bus.writeWord(N64SpMemory.IMEM_BASE + 28, r(0, 0, 6, 0, 16)) // MFHI r6

        val rsp = N64RspCpu(bus)
        repeat(8) { rsp.step() }

        assertEquals(-1, rsp.register(3))
        assertEquals(-21, rsp.register(4))
        assertEquals(-2, rsp.register(5))
        assertEquals(-1, rsp.register(6))
    }

    @Test fun supportsUnsignedMultiplyAndDivideThroughHiLo() {
        val bus = N64MemoryBus()
        bus.writeWord(N64SpMemory.IMEM_BASE, i(15, 0, 1, 0x8000)) // r1 = 0x80000000
        bus.writeWord(N64SpMemory.IMEM_BASE + 4, i(9, 0, 2, 2))
        bus.writeWord(N64SpMemory.IMEM_BASE + 8, r(1, 2, 0, 0, 25)) // MULTU
        bus.writeWord(N64SpMemory.IMEM_BASE + 12, r(0, 0, 3, 0, 16)) // MFHI r3
        bus.writeWord(N64SpMemory.IMEM_BASE + 16, r(0, 0, 4, 0, 18)) // MFLO r4
        bus.writeWord(N64SpMemory.IMEM_BASE + 20, r(1, 2, 0, 0, 27)) // DIVU
        bus.writeWord(N64SpMemory.IMEM_BASE + 24, r(0, 0, 5, 0, 18)) // MFLO r5
        bus.writeWord(N64SpMemory.IMEM_BASE + 28, r(0, 0, 6, 0, 16)) // MFHI r6

        val rsp = N64RspCpu(bus)
        repeat(8) { rsp.step() }

        assertEquals(1, rsp.register(3))
        assertEquals(0, rsp.register(4))
        assertEquals(0x40000000, rsp.register(5))
        assertEquals(0, rsp.register(6))
    }

    @Test fun supportsSignedAndUnsignedImmediateComparisons() {
        val bus = N64MemoryBus()
        bus.writeWord(N64SpMemory.IMEM_BASE, i(9, 0, 1, -1)) // r1 = -1
        bus.writeWord(N64SpMemory.IMEM_BASE + 4, i(10, 1, 2, 1)) // SLTI r2,r1,1
        bus.writeWord(N64SpMemory.IMEM_BASE + 8, i(11, 1, 3, 1)) // SLTIU r3,r1,1
        bus.writeWord(N64SpMemory.IMEM_BASE + 12, i(11, 0, 4, -1)) // SLTIU r4,r0,0xFFFF

        val rsp = N64RspCpu(bus)
        repeat(4) { rsp.step() }

        assertEquals(1, rsp.register(2))
        assertEquals(0, rsp.register(3))
        assertEquals(1, rsp.register(4))
    }

    @Test fun jalrExecutesDelaySlotAndStoresReturnAddress() {
        val bus = N64MemoryBus()
        bus.writeWord(N64SpMemory.IMEM_BASE, i(9, 0, 1, 16)) // target address
        bus.writeWord(N64SpMemory.IMEM_BASE + 4, r(1, 0, 5, 0, 9)) // JALR r5,r1
        bus.writeWord(N64SpMemory.IMEM_BASE + 8, i(9, 0, 2, 22)) // delay slot
        bus.writeWord(N64SpMemory.IMEM_BASE + 12, i(9, 0, 3, 33)) // skipped
        bus.writeWord(N64SpMemory.IMEM_BASE + 16, i(9, 0, 4, 44)) // target

        val rsp = N64RspCpu(bus)
        repeat(4) { rsp.step() }

        assertEquals(12, rsp.register(5))
        assertEquals(22, rsp.register(2))
        assertEquals(0, rsp.register(3))
        assertEquals(44, rsp.register(4))
    }

    @Test fun vectorAbsoluteUsesVsSignAndVtMagnitude() {
        val bus = N64MemoryBus()
        fun v(vt: Int, vs: Int, vd: Int, funct: Int) =
            (18 shl 26) or (16 shl 21) or (vt shl 16) or
                (vs shl 11) or (vd shl 6) or funct
        bus.writeWord(N64SpMemory.IMEM_BASE, v(2, 1, 3, 0x13)) // VABS
        val rsp = N64RspCpu(bus)
        val signs = shortArrayOf((-1).toShort(), 1, 0, (-2).toShort(), 2, (-1).toShort(), 1, 0)
        val values = shortArrayOf(7, 7, 7, (-9).toShort(), (-9).toShort(), Short.MIN_VALUE, Short.MIN_VALUE, 0)
        for (lane in 0..7) {
            rsp.setVectorLane(1, lane, signs[lane])
            rsp.setVectorLane(2, lane, values[lane])
        }

        rsp.step()

        assertEquals((-7).toShort(), rsp.vectorLane(3, 0))
        assertEquals(7.toShort(), rsp.vectorLane(3, 1))
        assertEquals(0.toShort(), rsp.vectorLane(3, 2))
        assertEquals(9.toShort(), rsp.vectorLane(3, 3))
        assertEquals((-9).toShort(), rsp.vectorLane(3, 4))
        assertEquals(Short.MIN_VALUE, rsp.vectorLane(3, 5))
        assertEquals(Short.MIN_VALUE, rsp.vectorLane(3, 6))
        assertEquals(0.toShort(), rsp.vectorLane(3, 7))
    }
}
