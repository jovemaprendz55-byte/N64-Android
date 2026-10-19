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
}
