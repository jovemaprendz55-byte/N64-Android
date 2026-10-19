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
        bus.writeWord(N64SpMemory.IMEM_BASE + 16, v(2, 1, 7, 0x29)) // VNAND
        bus.writeWord(N64SpMemory.IMEM_BASE + 20, v(2, 1, 8, 0x2D)) // VNXOR
        bus.writeWord(N64SpMemory.IMEM_BASE + 24, v(2, 1, 9, 0x2E)) // VANDC
        bus.writeWord(N64SpMemory.IMEM_BASE + 28, v(2, 1, 10, 0x2F)) // VORC
        val rsp = N64RspCpu(bus)
        for (lane in 0..7) {
            rsp.setVectorLane(1, lane, (lane * 100).toShort())
            rsp.setVectorLane(2, lane, (lane * 10).toShort())
        }
        repeat(8) { rsp.step() }
        for (lane in 0..7) {
            assertEquals((lane * 110).toShort(), rsp.vectorLane(3, lane))
            assertEquals((lane * 90).toShort(), rsp.vectorLane(4, lane))
            assertEquals(((lane * 100) and (lane * 10)).toShort(), rsp.vectorLane(5, lane))
            assertEquals(((lane * 100) xor (lane * 10)).toShort(), rsp.vectorLane(6, lane))
            assertEquals(((lane * 100) and (lane * 10)).inv().toShort(), rsp.vectorLane(7, lane))
            assertEquals(((lane * 100) xor (lane * 10)).inv().toShort(), rsp.vectorLane(8, lane))
            assertEquals(((lane * 100) and (lane * 10).inv()).toShort(), rsp.vectorLane(9, lane))
            assertEquals(((lane * 100) or (lane * 10).inv()).toShort(), rsp.vectorLane(10, lane))
        }
    }

    @Test fun vectorAbsoluteWrapsMinimumSignedValue() {
        val bus = N64MemoryBus()
        fun v(vt: Int, vs: Int, vd: Int, funct: Int) =
            (18 shl 26) or (16 shl 21) or (vt shl 16) or
                (vs shl 11) or (vd shl 6) or funct
        bus.writeWord(N64SpMemory.IMEM_BASE, v(2, 1, 3, 0x13)) // VABS

        val rsp = N64RspCpu(bus)
        val signs = shortArrayOf(-1, -1, 0, 1, -1, 1, 0, -1)
        val values = shortArrayOf(Short.MIN_VALUE, Short.MAX_VALUE, Short.MIN_VALUE, Short.MIN_VALUE,
            -123, -123, 456, 0)
        for (lane in 0..7) {
            rsp.setVectorLane(1, lane, signs[lane])
            rsp.setVectorLane(2, lane, values[lane])
        }

        rsp.step()

        val expected = shortArrayOf(
            Short.MIN_VALUE, (-Short.MAX_VALUE).toShort(), 0, Short.MIN_VALUE,
            123.toShort(), (-123).toShort(), 0, 0
        )
        for (lane in 0..7) {
            assertEquals("VABS lane $lane", expected[lane], rsp.vectorLane(3, lane))
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

    @Test fun vectorAddCarryAndSubtractCarryWrapUnsignedLanes() {
        val bus = N64MemoryBus()
        fun v(vt: Int, vs: Int, vd: Int, funct: Int) =
            (18 shl 26) or (16 shl 21) or (vt shl 16) or
                (vs shl 11) or (vd shl 6) or funct
        bus.writeWord(N64SpMemory.IMEM_BASE, v(2, 1, 3, 0x14)) // VADDC
        bus.writeWord(N64SpMemory.IMEM_BASE + 4, v(2, 1, 4, 0x15)) // VSUBC
        val rsp = N64RspCpu(bus)
        val left = shortArrayOf(0xFFFF.toShort(), 0x8000.toShort(), 10, 0, 0xFFFF.toShort(), 1, 7, 0x1234)
        val right = shortArrayOf(1, 0x8000.toShort(), 20, 0, 2, 2, 7, 0x1234)
        for (lane in 0..7) {
            rsp.setVectorLane(1, lane, left[lane])
            rsp.setVectorLane(2, lane, right[lane])
        }

        rsp.step()
        rsp.step()

        val addExpected = intArrayOf(0, 0, 30, 0, 1, 3, 14, 0x2468)
        val subExpected = intArrayOf(0xFFFE, 0, 0xFFF6, 0, 0xFFFD, 0xFFFF, 0, 0)
        for (lane in 0..7) {
            assertEquals(addExpected[lane].toShort(), rsp.vectorLane(3, lane))
            assertEquals(subExpected[lane].toShort(), rsp.vectorLane(4, lane))
        }
    }

    @Test fun vectorAddAndSubtractConsumeAndClearCarryFlags() {
        val bus = N64MemoryBus()
        fun v(vt: Int, vs: Int, vd: Int, funct: Int) =
            (18 shl 26) or (16 shl 21) or (vt shl 16) or
                (vs shl 11) or (vd shl 6) or funct

        bus.writeWord(N64SpMemory.IMEM_BASE, v(2, 1, 3, 0x14)) // VADDC
        bus.writeWord(N64SpMemory.IMEM_BASE + 4, v(5, 4, 6, 0x10)) // VADD consumes carry
        bus.writeWord(N64SpMemory.IMEM_BASE + 8, v(5, 4, 7, 0x10)) // VADD after clear
        bus.writeWord(N64SpMemory.IMEM_BASE + 12, v(2, 13, 8, 0x15)) // VSUBC
        bus.writeWord(N64SpMemory.IMEM_BASE + 16, v(10, 9, 11, 0x11)) // VSUB consumes borrow
        bus.writeWord(N64SpMemory.IMEM_BASE + 20, v(10, 9, 12, 0x11)) // VSUB after clear

        val rsp = N64RspCpu(bus)
        rsp.setVectorLane(1, 0, 0xFFFF.toShort())
        rsp.setVectorLane(2, 0, 1)
        rsp.setVectorLane(4, 0, 0)
        rsp.setVectorLane(5, 0, 0)
        rsp.setVectorLane(13, 0, 0)
        rsp.setVectorLane(9, 0, 5)
        rsp.setVectorLane(10, 0, 3)

        repeat(6) { rsp.step() }

        assertEquals(0, rsp.vectorLane(3, 0).toInt()) // unsigned sum wraps
        assertEquals(1, rsp.vectorLane(6, 0).toInt()) // carry-in consumed
        assertEquals(0, rsp.vectorLane(7, 0).toInt()) // carry flag cleared
        assertEquals((-1).toShort(), rsp.vectorLane(8, 0)) // unsigned subtraction wraps
        assertEquals(1, rsp.vectorLane(11, 0).toInt()) // borrow-in consumed
        assertEquals(2, rsp.vectorLane(12, 0).toInt()) // borrow flag cleared
    }

    @Test fun vectorFractionalMultiplyProducesSignedAndUnsignedResults() {
        val bus = N64MemoryBus()
        fun v(vt: Int, vs: Int, vd: Int, funct: Int) =
            (18 shl 26) or (16 shl 21) or (vt shl 16) or
                (vs shl 11) or (vd shl 6) or funct
        bus.writeWord(N64SpMemory.IMEM_BASE, v(2, 1, 3, 0x00)) // VMULF
        bus.writeWord(N64SpMemory.IMEM_BASE + 4, v(2, 1, 4, 0x01)) // VMULU

        val left = shortArrayOf(0x4000, 0x4000, (-0x4000).toShort(), Short.MIN_VALUE, 0, 1, 100, -100)
        val right = shortArrayOf(0x4000, (-0x4000).toShort(), 0x4000, Short.MIN_VALUE, 123, 1, 200, 200)
        val rsp = N64RspCpu(bus)
        for (lane in 0..7) {
            rsp.setVectorLane(1, lane, left[lane])
            rsp.setVectorLane(2, lane, right[lane])
        }

        rsp.step()
        rsp.step()

        val signedExpected = shortArrayOf(0x2000, (-0x2000).toShort(), (-0x2000).toShort(), Short.MAX_VALUE, 0, 0, 1, (-1).toShort())
        val unsignedExpected = shortArrayOf(0x2000, 0xE000.toShort(), 0xE000.toShort(), 0xFFFF.toShort(), 0, 0, 1, 0xFFFF.toShort())
        for (lane in 0..7) {
            assertEquals(signedExpected[lane], rsp.vectorLane(3, lane))
            assertEquals(unsignedExpected[lane], rsp.vectorLane(4, lane))
        }
    }

    @Test fun vectorMultiplyHighWritesAccumulatorSlices() {
        val bus = N64MemoryBus()
        fun v(vt: Int, vs: Int, vd: Int, funct: Int) =
            (18 shl 26) or (16 shl 21) or (vt shl 16) or
                (vs shl 11) or (vd shl 6) or funct
        bus.writeWord(N64SpMemory.IMEM_BASE, v(2, 1, 3, 0x07)) // VMUDH
        bus.writeWord(N64SpMemory.IMEM_BASE + 4, (18 shl 26) or ((16 or 8) shl 21) or (6 shl 6) or 0x1D) // VSAR high
        bus.writeWord(N64SpMemory.IMEM_BASE + 8, (18 shl 26) or ((16 or 9) shl 21) or (7 shl 6) or 0x1D) // VSAR middle
        bus.writeWord(N64SpMemory.IMEM_BASE + 12, (18 shl 26) or ((16 or 10) shl 21) or (8 shl 6) or 0x1D) // VSAR low

        val rsp = N64RspCpu(bus)
        rsp.setVectorLane(1, 0, 0x4000)
        rsp.setVectorLane(2, 0, 0x4000)
        rsp.setVectorLane(1, 1, Short.MIN_VALUE)
        rsp.setVectorLane(2, 1, Short.MAX_VALUE)
        repeat(4) { rsp.step() }

        assertEquals(0x1000.toShort(), rsp.vectorLane(3, 0))
        assertEquals((-16384).toShort(), rsp.vectorLane(3, 1))
        assertEquals(0x1000.toShort(), rsp.vectorLane(6, 0))
        assertEquals(0.toShort(), rsp.vectorLane(7, 0))
        assertEquals(0.toShort(), rsp.vectorLane(8, 0))
        assertEquals((-16384).toShort(), rsp.vectorLane(6, 1))
    }

    @Test fun vectorMultiplyAccumulateUpdatesAccumulatorAndSaturatesResults() {
        val bus = N64MemoryBus()
        fun v(vt: Int, vs: Int, vd: Int, funct: Int) =
            (18 shl 26) or (16 shl 21) or (vt shl 16) or
                (vs shl 11) or (vd shl 6) or funct
        bus.writeWord(N64SpMemory.IMEM_BASE, v(2, 1, 3, 0x00)) // VMULF initializes accumulator
        bus.writeWord(N64SpMemory.IMEM_BASE + 4, v(2, 1, 4, 0x08)) // VMACF adds product
        bus.writeWord(N64SpMemory.IMEM_BASE + 8, v(2, 1, 5, 0x09)) // VMACU adds product

        val rsp = N64RspCpu(bus)
        for (lane in 0..7) {
            rsp.setVectorLane(1, lane, 0x4000)
            rsp.setVectorLane(2, lane, 0x4000)
        }
        rsp.setVectorLane(1, 1, Short.MAX_VALUE)
        rsp.setVectorLane(2, 1, Short.MAX_VALUE)

        repeat(3) { rsp.step() }

        assertEquals(0x2000.toShort(), rsp.vectorLane(3, 0))
        assertEquals(0x4000.toShort(), rsp.vectorLane(4, 0))
        assertEquals(0x6000.toShort(), rsp.vectorLane(5, 0))
        assertEquals(Short.MAX_VALUE, rsp.vectorLane(4, 1))
        assertEquals(0x7FFA.toShort(), rsp.vectorLane(5, 1))
    }

    @Test fun vectorAccumulatorReadSelectsHighMiddleAndLowSlices() {
        val bus = N64MemoryBus()
        fun v(element: Int, vt: Int, vs: Int, vd: Int, funct: Int) =
            (18 shl 26) or ((16 or element) shl 21) or (vt shl 16) or
                (vs shl 11) or (vd shl 6) or funct
        bus.writeWord(N64SpMemory.IMEM_BASE, v(0, 2, 1, 3, 0x00)) // VMULF
        bus.writeWord(N64SpMemory.IMEM_BASE + 4, v(10, 0, 0, 4, 0x1D)) // VSAR low
        bus.writeWord(N64SpMemory.IMEM_BASE + 8, v(9, 0, 0, 5, 0x1D)) // VSAR middle
        bus.writeWord(N64SpMemory.IMEM_BASE + 12, v(8, 0, 0, 6, 0x1D)) // VSAR high

        val rsp = N64RspCpu(bus)
        rsp.setVectorLane(1, 0, 0x4000)
        rsp.setVectorLane(2, 0, 0x4000)
        repeat(4) { rsp.step() }

        assertEquals(0x8000.toShort(), rsp.vectorLane(4, 0))
        assertEquals(0x2000.toShort(), rsp.vectorLane(5, 0))
        assertEquals(0x0000.toShort(), rsp.vectorLane(6, 0))
    }

    @Test fun vectorComparisonsSelectExpectedSignedLanes() {
        val bus = N64MemoryBus()
        fun v(vt: Int, vs: Int, vd: Int, funct: Int) =
            (18 shl 26) or (16 shl 21) or (vt shl 16) or
                (vs shl 11) or (vd shl 6) or funct

        bus.writeWord(N64SpMemory.IMEM_BASE, v(2, 1, 3, 0x20)) // VLT
        bus.writeWord(N64SpMemory.IMEM_BASE + 4, v(2, 1, 4, 0x21)) // VEQ
        bus.writeWord(N64SpMemory.IMEM_BASE + 8, v(2, 1, 5, 0x22)) // VNE
        bus.writeWord(N64SpMemory.IMEM_BASE + 12, v(2, 1, 6, 0x23)) // VGE

        val left = shortArrayOf(-5, 4, 7, -2, 3, 0, -1, 9)
        val right = shortArrayOf(2, 4, 1, -3, 8, 0, -1, -9)
        val rsp = N64RspCpu(bus)
        for (lane in 0..7) {
            rsp.setVectorLane(1, lane, left[lane])
            rsp.setVectorLane(2, lane, right[lane])
        }

        repeat(4) { rsp.step() }

        for (lane in 0..7) {
            assertEquals(if (left[lane] < right[lane]) left[lane] else right[lane], rsp.vectorLane(3, lane))
            assertEquals(if (left[lane] == right[lane]) left[lane] else right[lane], rsp.vectorLane(4, lane))
            assertEquals(if (left[lane] != right[lane]) left[lane] else right[lane], rsp.vectorLane(5, lane))
            assertEquals(if (left[lane] >= right[lane]) left[lane] else right[lane], rsp.vectorLane(6, lane))
        }
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
        bus.writeWord(N64SpMemory.IMEM_BASE, 0x4A00003F)
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

    @Test fun conditionalBranchAndLinkWritesReturnAddressOnlyWhenTaken() {
        val bus = N64MemoryBus()
        bus.writeWord(N64SpMemory.IMEM_BASE, i(9, 0, 1, -1)) // r1 = -1
        bus.writeWord(N64SpMemory.IMEM_BASE + 4, i(1, 1, 16, 2)) // BLTZAL r1, target
        bus.writeWord(N64SpMemory.IMEM_BASE + 8, i(9, 0, 2, 22)) // delay slot
        bus.writeWord(N64SpMemory.IMEM_BASE + 12, i(9, 0, 3, 33)) // skipped
        bus.writeWord(N64SpMemory.IMEM_BASE + 16, i(9, 0, 4, 44)) // target

        val rsp = N64RspCpu(bus)
        repeat(4) { rsp.step() }

        assertEquals(12, rsp.register(31))
        assertEquals(22, rsp.register(2))
        assertEquals(0, rsp.register(3))
        assertEquals(44, rsp.register(4))
    }

    @Test fun conditionalBranchAndLinkDoesNotLinkWhenConditionIsFalse() {
        val bus = N64MemoryBus()
        bus.writeWord(N64SpMemory.IMEM_BASE, i(9, 0, 1, -1)) // r1 = -1
        bus.writeWord(N64SpMemory.IMEM_BASE + 4, i(1, 1, 17, 2)) // BGEZAL r1, not taken
        bus.writeWord(N64SpMemory.IMEM_BASE + 8, i(9, 0, 2, 22)) // delay slot
        bus.writeWord(N64SpMemory.IMEM_BASE + 12, i(9, 0, 3, 33))
        val rsp = N64RspCpu(bus)
        repeat(4) { rsp.step() }

        assertEquals(0, rsp.register(31))
        assertEquals(22, rsp.register(2))
        assertEquals(33, rsp.register(3))
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

    @Test fun rejectsUnsupportedVectorElementSelectorExplicitly() {
        val bus = N64MemoryBus()
        // COP2 with element selector 1; only selector 0 is implemented for now.
        val instruction = (18 shl 26) or (17 shl 21) or (2 shl 16) or
            (1 shl 11) or (3 shl 6) or 0x28
        bus.writeWord(N64SpMemory.IMEM_BASE, instruction)
        val rsp = N64RspCpu(bus)
        rsp.setVectorLane(1, 0, 0x1234.toShort())

        val error = org.junit.Assert.assertThrows(
            IllegalArgumentException::class.java
        ) { rsp.step() }

        org.junit.Assert.assertTrue(error.message!!.contains("Unsupported scalar RSP instruction"))
        assertEquals(0x1234.toShort(), rsp.vectorLane(1, 0))
    }

    @Test fun supportsAddiAddAndSubInstructions() {
        val bus = N64MemoryBus()
        bus.writeWord(N64SpMemory.IMEM_BASE, i(8, 0, 1, 7)) // ADDI r1,r0,7
        bus.writeWord(N64SpMemory.IMEM_BASE + 4, i(8, 0, 2, -10)) // ADDI r2,r0,-10
        bus.writeWord(N64SpMemory.IMEM_BASE + 8, r(1, 2, 3, 0, 32)) // ADD r3,r1,r2
        bus.writeWord(N64SpMemory.IMEM_BASE + 12, r(1, 2, 4, 0, 34)) // SUB r4,r1,r2
        bus.writeWord(N64SpMemory.IMEM_BASE + 16, i(8, 0, 5, 0x7FFF)) // ADDI r5,r0,32767
        bus.writeWord(N64SpMemory.IMEM_BASE + 20, i(8, 5, 5, 1)) // ADDI r5,r5,1

        val rsp = N64RspCpu(bus)
        repeat(6) { rsp.step() }

        assertEquals(7, rsp.register(1))
        assertEquals(-10, rsp.register(2))
        assertEquals(-3, rsp.register(3))
        assertEquals(17, rsp.register(4))
        assertEquals(32768, rsp.register(5))
    }

    @Test fun supportsVectorByteLoadAndStoreWithSignedOffset() {
        val bus = N64MemoryBus()
        // LBV/SBV use opcode 50/58, sub-opcode 0, element in bits 10..7.
        fun vectorMemory(op: Int, base: Int, vt: Int, element: Int, offset: Int) =
            (op shl 26) or (base shl 21) or (vt shl 16) or
                (element shl 7) or (offset and 0x7F)

        bus.writeByte(N64SpMemory.DMEM_BASE + 0x20, 0xAB.toByte())
        bus.writeByte(N64SpMemory.DMEM_BASE + 0x1F, 0xCD.toByte())
        bus.writeWord(N64SpMemory.IMEM_BASE, i(9, 0, 1, 0x20)) // base = 0x20
        bus.writeWord(N64SpMemory.IMEM_BASE + 4, vectorMemory(50, 1, 2, 0, 0)) // LBV v2[0]
        bus.writeWord(N64SpMemory.IMEM_BASE + 8, vectorMemory(50, 1, 2, 1, -1)) // LBV v2[1], -1(base)
        bus.writeWord(N64SpMemory.IMEM_BASE + 12, vectorMemory(58, 1, 2, 0, 2)) // SBV v2[0], 2(base)

        val rsp = N64RspCpu(bus)
        repeat(4) { rsp.step() }

        assertEquals(0xABCD, rsp.vectorLane(2, 0).toInt() and 0xFFFF)
        assertEquals(0xAB, bus.readByte(N64SpMemory.DMEM_BASE + 0x22).toInt() and 0xFF)
    }

    @Test fun supportsVectorHalfwordLoadAndStore() {
        val bus = N64MemoryBus()
        fun vectorMemory(op: Int, base: Int, vt: Int, subop: Int, element: Int, offset: Int) =
            (op shl 26) or (base shl 21) or (vt shl 16) or
                (subop shl 11) or (element shl 7) or (offset and 0x7F)

        bus.writeByte(N64SpMemory.DMEM_BASE + 0x30, 0x12.toByte())
        bus.writeByte(N64SpMemory.DMEM_BASE + 0x31, 0x34.toByte())
        bus.writeWord(N64SpMemory.IMEM_BASE, i(9, 0, 1, 0x30))
        bus.writeWord(N64SpMemory.IMEM_BASE + 4, vectorMemory(50, 1, 2, 1, 2, 0)) // LSV v2[2]
        bus.writeWord(N64SpMemory.IMEM_BASE + 8, vectorMemory(58, 1, 2, 1, 2, 2)) // SSV v2[2], 4(base)

        val rsp = N64RspCpu(bus)
        repeat(3) { rsp.step() }

        assertEquals(0x1234, (rsp.vectorLane(2, 1).toInt() and 0xFFFF))
        assertEquals(0x12, bus.readByte(N64SpMemory.DMEM_BASE + 0x34).toInt() and 0xFF)
        assertEquals(0x34, bus.readByte(N64SpMemory.DMEM_BASE + 0x35).toInt() and 0xFF)
    }

    @Test fun supportsVectorWordLoadAndStoreWithElementWrap() {
        val bus = N64MemoryBus()
        fun vectorMemory(op: Int, base: Int, vt: Int, subop: Int, element: Int, offset: Int) =
            (op shl 26) or (base shl 21) or (vt shl 16) or
                (subop shl 11) or (element shl 7) or (offset and 0x7F)

        bus.writeByte(N64SpMemory.DMEM_BASE + 0x40, 0x12.toByte())
        bus.writeByte(N64SpMemory.DMEM_BASE + 0x41, 0x34.toByte())
        bus.writeByte(N64SpMemory.DMEM_BASE + 0x42, 0x56.toByte())
        bus.writeByte(N64SpMemory.DMEM_BASE + 0x43, 0x78.toByte())
        bus.writeWord(N64SpMemory.IMEM_BASE, i(9, 0, 1, 0x40))
        bus.writeWord(N64SpMemory.IMEM_BASE + 4, vectorMemory(50, 1, 2, 2, 14, 0)) // LLV v2[14]
        bus.writeWord(N64SpMemory.IMEM_BASE + 8, vectorMemory(58, 1, 2, 2, 14, 1)) // SLV v2[14], 4(base)

        val rsp = N64RspCpu(bus)
        repeat(3) { rsp.step() }

        assertEquals(0x1234, rsp.vectorLane(2, 7).toInt() and 0xFFFF)
        assertEquals(0x5678, rsp.vectorLane(2, 0).toInt() and 0xFFFF)
        assertEquals(0x12, bus.readByte(N64SpMemory.DMEM_BASE + 0x44).toInt() and 0xFF)
        assertEquals(0x34, bus.readByte(N64SpMemory.DMEM_BASE + 0x45).toInt() and 0xFF)
        assertEquals(0x56, bus.readByte(N64SpMemory.DMEM_BASE + 0x46).toInt() and 0xFF)
        assertEquals(0x78, bus.readByte(N64SpMemory.DMEM_BASE + 0x47).toInt() and 0xFF)
    }

    @Test fun supportsVectorDoublewordLoadAndStore() {
        val bus = N64MemoryBus()
        fun vectorMemory(op: Int, base: Int, vt: Int, subop: Int, element: Int, offset: Int) =
            (op shl 26) or (base shl 21) or (vt shl 16) or
                (subop shl 11) or (element shl 7) or (offset and 0x7F)

        val expected = intArrayOf(0x10, 0x21, 0x32, 0x43, 0x54, 0x65, 0x76, 0x87)
        for (index in expected.indices) {
            bus.writeByte(N64SpMemory.DMEM_BASE + 0x50 + index, expected[index].toByte())
        }
        bus.writeWord(N64SpMemory.IMEM_BASE, i(9, 0, 1, 0x50))
        bus.writeWord(N64SpMemory.IMEM_BASE + 4, vectorMemory(50, 1, 2, 3, 0, 0)) // LDV v2[0]
        bus.writeWord(N64SpMemory.IMEM_BASE + 8, vectorMemory(58, 1, 2, 3, 0, 1)) // SDV v2[0], 8(base)

        val rsp = N64RspCpu(bus)
        repeat(3) { rsp.step() }

        assertEquals(0x1021, rsp.vectorLane(2, 0).toInt() and 0xFFFF)
        assertEquals(0x3243, rsp.vectorLane(2, 1).toInt() and 0xFFFF)
        assertEquals(0x5465, rsp.vectorLane(2, 2).toInt() and 0xFFFF)
        assertEquals(0x7687, rsp.vectorLane(2, 3).toInt() and 0xFFFF)
        for (index in expected.indices) {
            assertEquals(
                expected[index],
                bus.readByte(N64SpMemory.DMEM_BASE + 0x58 + index).toInt() and 0xFF
            )
        }
    }


    @Test fun supportsMfc2AndMtc2AtOddVectorElementOffsets() {
        val bus = N64MemoryBus()
        fun cop2Move(rs: Int, rt: Int, vd: Int, element: Int) =
            (18 shl 26) or (rs shl 21) or (rt shl 16) or
                (vd shl 11) or (element shl 7)

        val rsp = N64RspCpu(bus)
        rsp.setVectorLane(2, 1, 0x1122.toShort())
        rsp.setVectorLane(2, 2, 0x3344.toShort())
        bus.writeWord(N64SpMemory.IMEM_BASE, i(9, 0, 1, 0xFEDC))
        bus.writeWord(N64SpMemory.IMEM_BASE + 4, cop2Move(4, 1, 2, 3)) // MTC2 r1,v2[e3]
        bus.writeWord(N64SpMemory.IMEM_BASE + 8, cop2Move(0, 3, 2, 3)) // MFC2 r3,v2[e3]

        repeat(3) { rsp.step() }

        assertEquals(0x11FE, rsp.vectorLane(2, 1).toInt() and 0xFFFF)
        assertEquals(0xDC44, rsp.vectorLane(2, 2).toInt() and 0xFFFF)
        assertEquals(-292, rsp.register(3)) // 0xFEDC sign-extends from 16 bits
    }

    @Test fun supportsQuadwordLoadAndStoreAcrossVectorElementWrap() {
        val bus = N64MemoryBus()
        fun vectorMemory(op: Int, base: Int, vt: Int, subop: Int, element: Int, offset: Int) =
            (op shl 26) or (base shl 21) or (vt shl 16) or
                (subop shl 11) or (element shl 7) or (offset and 0x7F)

        // Give each vector byte a distinct value so SQV's element wrap is visible.
        val rsp = N64RspCpu(bus)
        for (lane in 0..7) {
            rsp.setVectorLane(2, lane, (((0x10 + lane) shl 8) or (0x80 + lane)).toShort())
        }
        bus.writeByte(N64SpMemory.DMEM_BASE + 0x6C, 0xA1.toByte())
        bus.writeByte(N64SpMemory.DMEM_BASE + 0x6D, 0xB2.toByte())
        bus.writeByte(N64SpMemory.DMEM_BASE + 0x6E, 0xC3.toByte())
        bus.writeByte(N64SpMemory.DMEM_BASE + 0x6F, 0xD4.toByte())
        bus.writeWord(N64SpMemory.IMEM_BASE, i(9, 0, 1, 0x6C))
        bus.writeWord(N64SpMemory.IMEM_BASE + 4, vectorMemory(50, 1, 2, 4, 4, 0)) // LQV v2[e4],0(r1)
        bus.writeWord(N64SpMemory.IMEM_BASE + 8, i(9, 0, 1, 0x68))
        bus.writeWord(N64SpMemory.IMEM_BASE + 12, vectorMemory(58, 1, 2, 4, 12, 0)) // SQV v2[e12],0(r1)

        repeat(4) { rsp.step() }

        // LQV stops at the aligned boundary and updates only bytes e4..e7.
        assertEquals(0xA1B2, rsp.vectorLane(2, 2).toInt() and 0xFFFF)
        assertEquals(0xC3D4, rsp.vectorLane(2, 3).toInt() and 0xFFFF)
        assertEquals(0x1484, rsp.vectorLane(2, 4).toInt() and 0xFFFF)

        // SQV writes bytes e12..e15, then wraps the vector element to e0..e3.
        val expectedBytes = intArrayOf(0x16, 0x86, 0x17, 0x87, 0x10, 0x80, 0x11, 0x81)
        for (index in expectedBytes.indices) {
            assertEquals(
                expectedBytes[index],
                bus.readByte(N64SpMemory.DMEM_BASE + 0x68 + index).toInt() and 0xFF
            )
        }
    }

    @Test fun dmemEffectiveAddressesWrapAtFourKilobytes() {
        val bus = N64MemoryBus()
        bus.writeWord(N64SpMemory.IMEM_BASE, i(9, 0, 1, 0x0FFF)) // r1 = 4095
        bus.writeWord(N64SpMemory.IMEM_BASE + 4, i(9, 0, 2, 0x005A)) // r2 = 0x5A
        bus.writeWord(N64SpMemory.IMEM_BASE + 8, i(40, 1, 2, 1)) // SB r2,1(r1) wraps to DMEM[0]
        bus.writeWord(N64SpMemory.IMEM_BASE + 12, i(36, 0, 3, 0)) // LBU r3,0(r0)
        val rsp = N64RspCpu(bus)

        repeat(4) { rsp.step() }

        assertEquals(0x5A, rsp.register(3))
        assertEquals(0x5A, bus.readByte(N64SpMemory.DMEM_BASE).toInt() and 0xFF)
    }


    @Test fun mtc2Element15DoesNotWrapButMfc2SecondByteDoes() {
        val bus = N64MemoryBus()
        fun cop2Move(rs: Int, rt: Int, vd: Int, element: Int) =
            (18 shl 26) or (rs shl 21) or (rt shl 16) or
                (vd shl 11) or (element shl 7)

        val rsp = N64RspCpu(bus)
        rsp.setVectorLane(2, 7, 0x7788.toShort())
        rsp.setVectorLane(2, 0, 0x99AA.toShort())
        bus.writeWord(N64SpMemory.IMEM_BASE, i(9, 0, 1, 0xFEDC))
        bus.writeWord(N64SpMemory.IMEM_BASE + 4, cop2Move(4, 1, 2, 15)) // MTC2 r1,v2[e15]
        bus.writeWord(N64SpMemory.IMEM_BASE + 8, cop2Move(0, 3, 2, 15)) // MFC2 r3,v2[e15]

        repeat(3) { rsp.step() }

        assertEquals(0x77FE, rsp.vectorLane(2, 7).toInt() and 0xFFFF)
        assertEquals(0x99AA, rsp.vectorLane(2, 0).toInt() and 0xFFFF)
        assertEquals(0xFE99.toShort().toInt(), rsp.register(3))
    }

    @Test fun supportsCfc2AndCtc2ForVcoVccAndVce() {
        val bus = N64MemoryBus()
        fun cop2Move(rs: Int, rt: Int, control: Int) =
            (18 shl 26) or (rs shl 21) or (rt shl 16) or (control shl 11)

        bus.writeWord(N64SpMemory.IMEM_BASE, i(9, 0, 1, 0xFFFF)) // r1 = -1
        bus.writeWord(N64SpMemory.IMEM_BASE + 4, i(13, 0, 2, 0x8123)) // r2 = 0x8123
        bus.writeWord(N64SpMemory.IMEM_BASE + 8, i(13, 0, 3, 0xABCD)) // r3 = 0xABCD
        bus.writeWord(N64SpMemory.IMEM_BASE + 12, cop2Move(6, 1, 0)) // CTC2 VCO
        bus.writeWord(N64SpMemory.IMEM_BASE + 16, cop2Move(6, 2, 1)) // CTC2 VCC
        bus.writeWord(N64SpMemory.IMEM_BASE + 20, cop2Move(6, 3, 2)) // CTC2 VCE
        bus.writeWord(N64SpMemory.IMEM_BASE + 24, cop2Move(2, 4, 0)) // CFC2 VCO
        bus.writeWord(N64SpMemory.IMEM_BASE + 28, cop2Move(2, 5, 1)) // CFC2 VCC
        bus.writeWord(N64SpMemory.IMEM_BASE + 32, cop2Move(2, 6, 2)) // CFC2 VCE

        val rsp = N64RspCpu(bus)
        repeat(9) { rsp.step() }

        assertEquals(-1, rsp.register(4))
        assertEquals(0x8123.toShort().toInt(), rsp.register(5))
        assertEquals(0xCD, rsp.register(6))
    }


    @Test fun vectorComparisonsUpdateVccAndVmrGConsumesIt() {
        val bus = N64MemoryBus()
        fun v(vt: Int, vs: Int, vd: Int, funct: Int) =
            (18 shl 26) or (16 shl 21) or (vt shl 16) or
                (vs shl 11) or (vd shl 6) or funct
        fun cop2Move(rs: Int, rt: Int, control: Int) =
            (18 shl 26) or (rs shl 21) or (rt shl 16) or (control shl 11)

        // VCO has both halves set for lane 0, and neither half set for lane 1.
        bus.writeWord(N64SpMemory.IMEM_BASE, i(13, 0, 1, 0x0101))
        bus.writeWord(N64SpMemory.IMEM_BASE + 4, cop2Move(6, 1, 0))
        bus.writeWord(N64SpMemory.IMEM_BASE + 8, v(2, 1, 10, 0x20)) // VLT
        bus.writeWord(N64SpMemory.IMEM_BASE + 12, cop2Move(2, 2, 1)) // CFC2 VCC
        bus.writeWord(N64SpMemory.IMEM_BASE + 16, cop2Move(6, 1, 0))
        bus.writeWord(N64SpMemory.IMEM_BASE + 20, v(2, 1, 11, 0x21)) // VEQ
        bus.writeWord(N64SpMemory.IMEM_BASE + 24, cop2Move(2, 3, 1))
        bus.writeWord(N64SpMemory.IMEM_BASE + 28, cop2Move(6, 1, 0))
        bus.writeWord(N64SpMemory.IMEM_BASE + 32, v(2, 1, 12, 0x22)) // VNE
        bus.writeWord(N64SpMemory.IMEM_BASE + 36, cop2Move(2, 4, 1))
        bus.writeWord(N64SpMemory.IMEM_BASE + 40, cop2Move(6, 1, 0))
        bus.writeWord(N64SpMemory.IMEM_BASE + 44, v(2, 1, 13, 0x23)) // VGE
        bus.writeWord(N64SpMemory.IMEM_BASE + 48, cop2Move(2, 5, 1))
        bus.writeWord(N64SpMemory.IMEM_BASE + 52, v(2, 1, 14, 0x27)) // VMRG

        val rsp = N64RspCpu(bus)
        val left = shortArrayOf(5, 7, 1, 9, 4, 2, 0, -3)
        val right = shortArrayOf(5, 7, 4, 2, 4, 8, 0, -3)
        for (lane in 0..7) {
            rsp.setVectorLane(1, lane, left[lane])
            rsp.setVectorLane(2, lane, right[lane])
        }

        repeat(14) { rsp.step() }

        assertEquals(0x0025, rsp.register(2)) // VLT: lane 0 equal with both flags; lanes 2 and 5 less
        assertEquals(0x00D2, rsp.register(3)) // VEQ: equal lanes without VCO_HI
        assertEquals(0x002D, rsp.register(4)) // VNE: unequal lanes or VCO_HI
        assertEquals(0x00DA, rsp.register(5)) // VGE: greater/equal, except equal with both flags
        for (lane in 0..7) {
            val selected = (rsp.register(5) and (1 shl lane)) != 0
            assertEquals(if (selected) left[lane] else right[lane], rsp.vectorLane(14, lane))
        }
    }


    @Test fun unalignedWordLoadsMergeBigEndianBytesCorrectly() {
        val bus = N64MemoryBus()
        bus.writeWord(N64SpMemory.DMEM_BASE, 0x11223344)
        bus.writeWord(N64SpMemory.DMEM_BASE + 4, 0x55667788)
        // Set r1 to DMEM base; r2 starts with a known merge pattern.
        bus.writeWord(N64SpMemory.IMEM_BASE, i(9, 0, 1, 0)) // ADDIU r1, r0, 0
        bus.writeWord(N64SpMemory.IMEM_BASE + 4, i(15, 0, 2, 0xAABB)) // LUI r2, 0xAABB
        bus.writeWord(N64SpMemory.IMEM_BASE + 8, i(34, 1, 2, 0)) // LWL r2, 0(r1)
        bus.writeWord(N64SpMemory.IMEM_BASE + 12, i(15, 0, 3, 0xAABB)) // LUI r3, 0xAABB
        bus.writeWord(N64SpMemory.IMEM_BASE + 16, i(38, 1, 3, 3)) // LWR r3, 3(r1)
        val rsp = N64RspCpu(bus)
        repeat(5) { rsp.step() }
        assertEquals(0x11223344, rsp.register(2))
        assertEquals(0x11223344, rsp.register(3))
    }

    @Test fun unalignedWordStoresWriteOnlySelectedBigEndianBytes() {
        val bus = N64MemoryBus()
        bus.writeWord(N64SpMemory.DMEM_BASE, 0x00000000)
        bus.writeWord(N64SpMemory.DMEM_BASE + 4, 0x00000000)
        // r1 is zero (DMEM base); r2 contains 0xAABBCCDD.
        bus.writeWord(N64SpMemory.IMEM_BASE, i(15, 0, 2, 0xAABB))
        bus.writeWord(N64SpMemory.IMEM_BASE + 4, i(13, 2, 2, 0xCCDD))
        bus.writeWord(N64SpMemory.IMEM_BASE + 8, i(42, 1, 2, 1)) // SWL r2, 1(r1)
        bus.writeWord(N64SpMemory.IMEM_BASE + 12, i(46, 1, 2, 6)) // SWR r2, 6(r1)
        val rsp = N64RspCpu(bus)
        repeat(4) { rsp.step() }
        assertEquals(0x00AABBCC, bus.readWord(N64SpMemory.DMEM_BASE))
        assertEquals(0xBBCCDD00.toInt(), bus.readWord(N64SpMemory.DMEM_BASE + 4))
    }


    @Test fun unalignedWordLoadsHandleEveryBigEndianOffset() {
        val memoryWord: Int = 0x11223344
        val initial: Int = 0xAABBCCDD.toInt()
        for (offset in 0..3) {
            run {
                val bus = N64MemoryBus()
                bus.writeWord(N64SpMemory.DMEM_BASE, memoryWord)
                bus.writeWord(N64SpMemory.IMEM_BASE, i(15, 0, 1, 0xAABB))
                bus.writeWord(N64SpMemory.IMEM_BASE + 4, i(13, 1, 1, 0xCCDD))
                bus.writeWord(N64SpMemory.IMEM_BASE + 8, i(34, 0, 1, offset)) // LWL
                val rsp = N64RspCpu(bus)
                repeat(3) { rsp.step() }
                val preserve: Int = if (offset == 0) 0 else (1 shl (offset * 8)) - 1
                val expected: Int = (initial and preserve) or (memoryWord shl (offset * 8))
                assertEquals("LWL offset $offset", expected, rsp.register(1))
            }
            run {
                val bus = N64MemoryBus()
                bus.writeWord(N64SpMemory.DMEM_BASE, memoryWord)
                bus.writeWord(N64SpMemory.IMEM_BASE, i(15, 0, 1, 0xAABB))
                bus.writeWord(N64SpMemory.IMEM_BASE + 4, i(13, 1, 1, 0xCCDD))
                bus.writeWord(N64SpMemory.IMEM_BASE + 8, i(38, 0, 1, offset)) // LWR
                val rsp = N64RspCpu(bus)
                repeat(3) { rsp.step() }
                val preserve: Int = when (offset) {
                    0 -> 0xFFFF_FF00.toInt()
                    1 -> 0xFFFF_0000.toInt()
                    2 -> 0xFF00_0000.toInt()
                    else -> 0
                }
                val expected: Int = (initial and preserve) or (memoryWord ushr ((3 - offset) * 8))
                assertEquals("LWR offset $offset", expected, rsp.register(1))
            }
        }
    }

    @Test fun unalignedWordStoresHandleEveryBigEndianOffset() {
        val value = 0xAABBCCDD.toInt()
        for (offset in 0..3) {
            run {
                val bus = N64MemoryBus()
                bus.writeWord(N64SpMemory.DMEM_BASE, 0x11223344)
                bus.writeWord(N64SpMemory.IMEM_BASE, i(15, 0, 2, 0xAABB))
                bus.writeWord(N64SpMemory.IMEM_BASE + 4, i(13, 2, 2, 0xCCDD))
                bus.writeWord(N64SpMemory.IMEM_BASE + 8, i(42, 0, 2, offset)) // SWL
                val rsp = N64RspCpu(bus)
                repeat(3) { rsp.step() }
                val expectedBytes = byteArrayOf(0x11, 0x22, 0x33, 0x44)
                for (index in offset..3) expectedBytes[index] = (value ushr (24 - (index - offset) * 8)).toByte()
                val actual = bus.readWord(N64SpMemory.DMEM_BASE)
                val expected = ((expectedBytes[0].toInt() and 0xFF) shl 24) or
                    ((expectedBytes[1].toInt() and 0xFF) shl 16) or
                    ((expectedBytes[2].toInt() and 0xFF) shl 8) or
                    (expectedBytes[3].toInt() and 0xFF)
                assertEquals("SWL offset $offset", expected, actual)
            }
            run {
                val bus = N64MemoryBus()
                bus.writeWord(N64SpMemory.DMEM_BASE, 0x11223344)
                bus.writeWord(N64SpMemory.IMEM_BASE, i(15, 0, 2, 0xAABB))
                bus.writeWord(N64SpMemory.IMEM_BASE + 4, i(13, 2, 2, 0xCCDD))
                bus.writeWord(N64SpMemory.IMEM_BASE + 8, i(46, 0, 2, offset)) // SWR
                val rsp = N64RspCpu(bus)
                repeat(3) { rsp.step() }
                val expectedBytes = byteArrayOf(0x11, 0x22, 0x33, 0x44)
                for (index in 0..offset) expectedBytes[index] = (value ushr ((offset - index) * 8)).toByte()
                val actual = bus.readWord(N64SpMemory.DMEM_BASE)
                val expected = ((expectedBytes[0].toInt() and 0xFF) shl 24) or
                    ((expectedBytes[1].toInt() and 0xFF) shl 16) or
                    ((expectedBytes[2].toInt() and 0xFF) shl 8) or
                    (expectedBytes[3].toInt() and 0xFF)
                assertEquals("SWR offset $offset", expected, actual)
            }
        }
    }

}
