package com.jovemaprendz55byte.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RspCoreTest {
    @Test fun emulatorCorePreservesCompletedInstructionCountWhenRspInstructionFails() {
        val core = N64EmulatorCore()
        core.memoryBus.writeWord(N64SpMemory.IMEM_BASE, iType(0x09, 0, 1, 9))
        core.memoryBus.writeWord(N64SpMemory.IMEM_BASE + 4, iType(0x2B, 0, 1, 0))
        core.memoryBus.writeWord(N64SpMemory.IMEM_BASE + 8, 0xFC000000.toInt()) // Unsupported opcode.

        val result = core.executeRspFromImem(entryPoint = 0, instructionBudget = 10)

        assertEquals(2, result.instructionsExecuted)
        assertEquals(8, result.programCounter) // Point to the unsupported instruction, not the next PC.
        assertTrue(result.errorMessage != null)
        assertTrue(result.errorMessage!!.contains("Unsupported scalar RSP instruction"))
        assertEquals(9, core.memoryBus.readWord(N64SpMemory.DMEM_BASE))
    }

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


    @Test fun cop2TransfersUseByteElementsIncludingOddOffsets() {
        val bus = N64MemoryBus()
        val rsp = RspCore(bus)
        bus.writeWord(N64SpMemory.IMEM_BASE, iType(0x09, 0, 1, 0x1234))
        bus.writeWord(N64SpMemory.IMEM_BASE + 4, cop2(4, 1, 3, 5)) // MTC2 v3 byte 5 = 0x12, byte 6 = 0x34
        bus.writeWord(N64SpMemory.IMEM_BASE + 8, cop2(0, 2, 3, 5)) // MFC2 reads bytes 5 and 6
        bus.writeWord(N64SpMemory.IMEM_BASE + 12, iType(0x2B, 0, 2, 0))

        rsp.start()
        repeat(4) { assertTrue(rsp.step()) }

        assertEquals(0x1234, rsp.register(2))
        assertEquals(0x1234, bus.readWord(N64SpMemory.DMEM_BASE))
    }

    @Test fun cop2Element15WrapsMfc2ReadToByteZeroAndSignExtends() {
        val bus = N64MemoryBus()
        val rsp = RspCore(bus)
        bus.writeWord(N64SpMemory.IMEM_BASE, iType(0x09, 0, 1, 0xABCD))
        bus.writeWord(N64SpMemory.IMEM_BASE + 4, cop2(4, 1, 3, 15)) // byte 15 gets 0xAB only
        bus.writeWord(N64SpMemory.IMEM_BASE + 8, iType(0x09, 0, 1, 0x1234))
        bus.writeWord(N64SpMemory.IMEM_BASE + 12, cop2(4, 1, 3, 0)) // bytes 0..1 = 0x12, 0x34
        bus.writeWord(N64SpMemory.IMEM_BASE + 16, cop2(0, 2, 3, 15)) // bytes 15 and 0 => 0xAB12
        bus.writeWord(N64SpMemory.IMEM_BASE + 20, iType(0x2B, 0, 2, 0))

        rsp.start()
        repeat(6) { assertTrue("RSP stopped at instruction $it", rsp.step()) }

        assertEquals(0xFFFF_AB12.toInt(), rsp.register(2))
        assertEquals(0xFFFF_AB12.toInt(), bus.readWord(N64SpMemory.DMEM_BASE))
    }

    @Test fun vectorAddAndBitwiseLogicOperateOnLanes() {
        val bus = N64MemoryBus()
        val rsp = RspCore(bus)
        bus.writeWord(N64SpMemory.IMEM_BASE, iType(0x09, 0, 1, 3)) // r1 = 3
        bus.writeWord(N64SpMemory.IMEM_BASE + 4, iType(0x09, 0, 2, 5)) // r2 = 5
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

        assertEquals(8, bus.readWord(N64SpMemory.DMEM_BASE))
        assertEquals(8, rsp.register(4))
        assertEquals(6, bus.readWord(N64SpMemory.DMEM_BASE + 4))
        assertEquals(6, rsp.register(6))
    }

    @Test fun vectorAddSaturatesSignedHalfwordOverflow() {
        val bus = N64MemoryBus()
        val rsp = RspCore(bus)
        bus.writeWord(N64SpMemory.IMEM_BASE, iType(0x09, 0, 1, 0x7FFF)) // r1 = 32767, transferred as 0x7FFF
        bus.writeWord(N64SpMemory.IMEM_BASE + 4, cop2(4, 1, 1, 0)) // v1[0] = 32767
        bus.writeWord(N64SpMemory.IMEM_BASE + 8, iType(0x09, 0, 2, -32768)) // r2 = -32768, transferred as 0x8000
        bus.writeWord(N64SpMemory.IMEM_BASE + 12, cop2(4, 2, 1, 2)) // v1[1] = -32768
        bus.writeWord(N64SpMemory.IMEM_BASE + 16, iType(0x09, 0, 3, 1)) // r3 = 1
        bus.writeWord(N64SpMemory.IMEM_BASE + 20, cop2(4, 3, 2, 0)) // v2[0] = 1
        bus.writeWord(N64SpMemory.IMEM_BASE + 24, iType(0x09, 0, 4, -1)) // r4 = -1, transferred as 0xFFFF
        bus.writeWord(N64SpMemory.IMEM_BASE + 28, cop2(4, 4, 2, 2)) // v2[1] = -1
        bus.writeWord(N64SpMemory.IMEM_BASE + 32, vectorAlu(2, 1, 3, 0x10)) // VADD with signed saturation
        bus.writeWord(N64SpMemory.IMEM_BASE + 36, cop2(0, 5, 3, 0))
        bus.writeWord(N64SpMemory.IMEM_BASE + 40, iType(0x2B, 0, 5, 0))
        bus.writeWord(N64SpMemory.IMEM_BASE + 44, cop2(0, 6, 3, 2))
        bus.writeWord(N64SpMemory.IMEM_BASE + 48, iType(0x2B, 0, 6, 4))

        rsp.start()
        repeat(13) { index ->
            assertTrue("RSP stopped at instruction $index (PC=${rsp.pc})", rsp.step())
        }

        assertEquals(0x00007FFF, bus.readWord(N64SpMemory.DMEM_BASE))
        assertEquals(0xFFFF8000.toInt(), bus.readWord(N64SpMemory.DMEM_BASE + 4))
    }

    @Test fun vectorSubtractSaturatesSignedHalfwordOverflow() {
        val bus = N64MemoryBus()
        val rsp = RspCore(bus)
        bus.writeWord(N64SpMemory.IMEM_BASE, iType(0x09, 0, 1, 32767)) // r1 = 32767
        bus.writeWord(N64SpMemory.IMEM_BASE + 4, cop2(4, 1, 1, 0)) // v1[0] = 32767
        bus.writeWord(N64SpMemory.IMEM_BASE + 8, iType(0x09, 0, 2, -32768)) // r2 = -32768
        bus.writeWord(N64SpMemory.IMEM_BASE + 12, cop2(4, 2, 1, 2)) // v1[1] = -32768
        bus.writeWord(N64SpMemory.IMEM_BASE + 16, iType(0x09, 0, 3, -1)) // r3 = -1
        bus.writeWord(N64SpMemory.IMEM_BASE + 20, cop2(4, 3, 2, 0)) // v2[0] = -1
        bus.writeWord(N64SpMemory.IMEM_BASE + 24, iType(0x09, 0, 4, 1)) // r4 = 1
        bus.writeWord(N64SpMemory.IMEM_BASE + 28, cop2(4, 4, 2, 2)) // v2[1] = 1
        bus.writeWord(N64SpMemory.IMEM_BASE + 32, vectorAlu(2, 1, 3, 0x11)) // VSUB with signed saturation
        bus.writeWord(N64SpMemory.IMEM_BASE + 36, cop2(0, 5, 3, 0))
        bus.writeWord(N64SpMemory.IMEM_BASE + 40, iType(0x2B, 0, 5, 0))
        bus.writeWord(N64SpMemory.IMEM_BASE + 44, cop2(0, 6, 3, 2))
        bus.writeWord(N64SpMemory.IMEM_BASE + 48, iType(0x2B, 0, 6, 4))

        rsp.start()
        repeat(13) { index ->
            assertTrue("RSP stopped at instruction $index (PC=${rsp.pc})", rsp.step())
        }

        assertEquals(0x00007FFF, bus.readWord(N64SpMemory.DMEM_BASE))
        assertEquals(0xFFFF8000.toInt(), bus.readWord(N64SpMemory.DMEM_BASE + 4))
    }

    @Test fun vectorAndOrOperationsProduceExpectedLaneValues() {
        val bus = N64MemoryBus()
        val rsp = RspCore(bus)
        bus.writeWord(N64SpMemory.IMEM_BASE, iType(0x09, 0, 1, 0x00F3))
        bus.writeWord(N64SpMemory.IMEM_BASE + 4, iType(0x09, 0, 2, 0x0F3F))
        bus.writeWord(N64SpMemory.IMEM_BASE + 8, cop2(4, 1, 1, 0)) // v1[0]
        bus.writeWord(N64SpMemory.IMEM_BASE + 12, cop2(4, 2, 2, 0)) // v2[0]
        bus.writeWord(N64SpMemory.IMEM_BASE + 16, vectorAlu(2, 1, 3, 0x28)) // VAND
        bus.writeWord(N64SpMemory.IMEM_BASE + 20, cop2(0, 4, 3, 0))
        bus.writeWord(N64SpMemory.IMEM_BASE + 24, iType(0x2B, 0, 4, 0))
        bus.writeWord(N64SpMemory.IMEM_BASE + 28, vectorAlu(2, 1, 5, 0x2A)) // VOR
        bus.writeWord(N64SpMemory.IMEM_BASE + 32, cop2(0, 6, 5, 0))
        bus.writeWord(N64SpMemory.IMEM_BASE + 36, iType(0x2B, 0, 6, 4))

        rsp.start()
        repeat(10) { assertTrue("RSP stopped at instruction $it", rsp.step()) }

        assertEquals(0x0033, bus.readWord(N64SpMemory.DMEM_BASE))
        assertEquals(0x0FFF, bus.readWord(N64SpMemory.DMEM_BASE + 4))
    }

    @Test fun vectorBitwiseComplementOperationsPreserveSixteenBitResults() {
        val bus = N64MemoryBus()
        val rsp = RspCore(bus)
        bus.writeWord(N64SpMemory.IMEM_BASE, iType(0x09, 0, 1, 0x00F3))
        bus.writeWord(N64SpMemory.IMEM_BASE + 4, iType(0x09, 0, 2, 0x0F3F))
        bus.writeWord(N64SpMemory.IMEM_BASE + 8, cop2(4, 1, 1, 0)) // v1[0] = 0x00F3
        bus.writeWord(N64SpMemory.IMEM_BASE + 12, cop2(4, 2, 2, 0)) // v2[0] = 0x0F3F
        val operations = listOf(
            Triple(0x29, 3, 0), // VNAND: ~(0x00F3 & 0x0F3F) = 0xFFCC
            Triple(0x2B, 4, 4), // VNOR: ~(0x00F3 | 0x0F3F) = 0xF000
            Triple(0x2D, 5, 8)  // VNXOR: ~(0x00F3 ^ 0x0F3F) = 0xF033
        )
        var pc = 16
        for ((funct, vd, offset) in operations) {
            bus.writeWord(N64SpMemory.IMEM_BASE + pc, vectorAlu(2, 1, vd, funct))
            bus.writeWord(N64SpMemory.IMEM_BASE + pc + 4, cop2(0, 6, vd, 0))
            bus.writeWord(N64SpMemory.IMEM_BASE + pc + 8, iType(0x2B, 0, 6, offset))
            pc += 12
        }

        rsp.start()
        repeat(13) { index ->
            assertTrue("RSP stopped at instruction $index (PC=${rsp.pc})", rsp.step())
        }

        assertEquals(0xFFFFFFCC.toInt(), bus.readWord(N64SpMemory.DMEM_BASE))
        assertEquals(0xFFFFF000.toInt(), bus.readWord(N64SpMemory.DMEM_BASE + 4))
        assertEquals(0xFFFFF033.toInt(), bus.readWord(N64SpMemory.DMEM_BASE + 8))
    }

    @Test fun vectorAluUsesSelectedVectorElementForAllDestinationLanes() {
        val bus = N64MemoryBus()
        val rsp = RspCore(bus)
        bus.writeWord(N64SpMemory.IMEM_BASE, iType(0x09, 0, 1, 0x10))
        bus.writeWord(N64SpMemory.IMEM_BASE + 4, iType(0x09, 0, 2, 0x111))
        bus.writeWord(N64SpMemory.IMEM_BASE + 8, iType(0x09, 0, 3, 0x22))
        bus.writeWord(N64SpMemory.IMEM_BASE + 12, cop2(4, 1, 1, 8)) // v1 lane 4 = 0x10
        bus.writeWord(N64SpMemory.IMEM_BASE + 16, cop2(4, 2, 2, 8)) // v2 lane 4 = 0x111
        bus.writeWord(N64SpMemory.IMEM_BASE + 20, cop2(4, 3, 2, 8)) // v2 lane 4 = 0x22
        bus.writeWord(N64SpMemory.IMEM_BASE + 24, vectorAlu(2, 1, 3, 0x2A, element = 2))
        bus.writeWord(N64SpMemory.IMEM_BASE + 28, cop2(0, 4, 3, 8)) // read destination lane 4
        bus.writeWord(N64SpMemory.IMEM_BASE + 32, iType(0x2B, 0, 4, 0))

        rsp.start()
        repeat(7) { index ->
            assertTrue("RSP stopped before vector operation at instruction $index (PC=${rsp.pc})", rsp.step())
        }
        assertTrue("RSP stopped while reading selected result (PC=${rsp.pc})", rsp.step())
        assertTrue("RSP stopped while storing selected result (PC=${rsp.pc})", rsp.step())

        assertEquals(0x32, bus.readWord(N64SpMemory.DMEM_BASE))
    }


    @Test fun vectorElementSelectorsCoverAllEightHalfwordSwizzles() {
        val expected = arrayOf(
            intArrayOf(0, 1, 2, 3, 4, 5, 6, 7),
            intArrayOf(0, 0, 2, 2, 4, 4, 6, 6),
            intArrayOf(0, 0, 0, 0, 4, 4, 4, 4),
            intArrayOf(0, 0, 0, 0, 0, 0, 0, 0),
            intArrayOf(1, 1, 3, 3, 5, 5, 7, 7),
            intArrayOf(1, 1, 1, 1, 5, 5, 5, 5),
            intArrayOf(2, 2, 2, 2, 6, 6, 6, 6),
            intArrayOf(3, 3, 3, 3, 7, 7, 7, 7),
            intArrayOf(0, 0, 0, 0, 0, 0, 0, 0),
            intArrayOf(1, 1, 1, 1, 1, 1, 1, 1),
            intArrayOf(2, 2, 2, 2, 2, 2, 2, 2),
            intArrayOf(3, 3, 3, 3, 3, 3, 3, 3),
            intArrayOf(4, 4, 4, 4, 4, 4, 4, 4),
            intArrayOf(5, 5, 5, 5, 5, 5, 5, 5),
            intArrayOf(6, 6, 6, 6, 6, 6, 6, 6),
            intArrayOf(7, 7, 7, 7, 7, 7, 7, 7)
        )

        for (selector in 0..15) {
            val bus = N64MemoryBus()
            val rsp = RspCore(bus)
            var pc = 0

            // Initialize vt lanes with distinct values 0x10..0x17.
            for (lane in 0 until 8) {
                bus.writeWord(N64SpMemory.IMEM_BASE + pc, iType(0x09, 0, 1, 0x10 + lane))
                pc += 4
                bus.writeWord(N64SpMemory.IMEM_BASE + pc, cop2(4, 1, 2, lane * 2))
                pc += 4
            }
            // vs is zero, so VOR copies the selected vt lane into every vd lane.
            bus.writeWord(N64SpMemory.IMEM_BASE + pc, vectorAlu(2, 0, 3, 0x2A, element = selector))
            pc += 4
            for (lane in 0 until 8) {
                bus.writeWord(N64SpMemory.IMEM_BASE + pc, cop2(0, 4, 3, lane * 2))
                pc += 4
                bus.writeWord(N64SpMemory.IMEM_BASE + pc, iType(0x2B, 0, 4, lane * 4))
                pc += 4
            }

            rsp.start()
            repeat(pc / 4) { index ->
                assertTrue("Selector $selector stopped at instruction $index (PC=${rsp.pc})", rsp.step())
            }
            for (lane in 0 until 8) {
                assertEquals(
                    "selector=$selector lane=$lane",
                    0x10 + expected[selector][lane],
                    bus.readWord(N64SpMemory.DMEM_BASE + lane * 4)
                )
            }
        }
    }

    private fun vectorAlu(vt: Int, vs: Int, vd: Int, funct: Int, element: Int = 0): Int =
        (0x12 shl 26) or ((0x10 or element) shl 21) or (vt shl 16) or
            (vs shl 11) or (vd shl 6) or (funct and 63)

    private fun cop0(rs: Int, rt: Int, rd: Int): Int =
        (0x10 shl 26) or (rs shl 21) or (rt shl 16) or (rd shl 11)

    private fun cop2(rs: Int, rt: Int, vd: Int, element: Int): Int =
        (0x12 shl 26) or (rs shl 21) or (rt shl 16) or (vd shl 11) or (element shl 7)

    private fun iType(op: Int, rs: Int, rt: Int, imm: Int): Int =
        (op shl 26) or (rs shl 21) or (rt shl 16) or (imm and 0xFFFF)
}
