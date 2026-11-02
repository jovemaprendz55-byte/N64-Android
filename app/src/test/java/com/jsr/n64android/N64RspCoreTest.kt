package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class N64RspCoreTest {
    private fun addiu(rt: Int, rs: Int, imm: Int) =
        (0x09 shl 26) or (rs shl 21) or (rt shl 16) or (imm and 0xFFFF)

    private fun vectorOp(function: Int, vd: Int, vs: Int, vt: Int, element: Int = 0) =
        (0x12 shl 26) or ((0x10 or element) shl 21) or (vt shl 16) or
            (vs shl 11) or (vd shl 6) or function

    private fun cop2Move(kind: Int, rt: Int, vectorRegister: Int, element: Int = 0) =
        (0x12 shl 26) or (kind shl 21) or (rt shl 16) or
            (vectorRegister shl 11) or (element shl 7)

    private fun vectorMemory(opcode: Int, base: Int, vt: Int, operation: Int, element: Int, offset: Int) =
        (opcode shl 26) or (base shl 21) or (vt shl 16) or (operation shl 11) or
            (element shl 7) or (offset and 0x7F)

    private fun setup(vararg instructions: Int): Pair<N64Bus, N64RspCore> {
        val bus = N64Bus()
        bus.write32(N64Bus.SP_STATUS_REG, N64Bus.SP_STATUS_CMD_CLR_HALT)
        instructions.forEachIndexed { index, word ->
            bus.write32(N64Bus.SP_IMEM_BASE + index * 4, word)
        }
        return bus to N64RspCore(bus).also { it.reset(0) }
    }

    private fun rType(rs: Int, rt: Int, rd: Int, function: Int) =
        (rs shl 21) or (rt shl 16) or (rd shl 11) or function

    @Test
    fun signedMultiplyWritesFullProductToHiLoAndMovesItBack() {
        val (bus, rsp) = setup(
            addiu(8, 0, -7),
            addiu(9, 0, 9),
            rType(8, 9, 0, 0x18), // MULT
            rType(0, 0, 10, 0x12), // MFLO
            rType(0, 0, 11, 0x10) // MFHI
        )

        assertEquals(5, rsp.run(5))
        assertEquals(-63, rsp.lo)
        assertEquals(-1, rsp.hi)
        assertEquals(-63, rsp.gpr[10])
        assertEquals(-1, rsp.gpr[11])
    }

    @Test
    fun unsignedMultiplyWritesUnsignedHighAndLowHalves() {
        val (bus, rsp) = setup(
            (0x0F shl 26) or (8 shl 16) or 0xFFFF, // LUI r8,0xFFFF
            (0x0D shl 26) or (8 shl 21) or (8 shl 16) or 0xFFFF, // ORI r8,r8,FFFF
            addiu(9, 0, 2),
            rType(8, 9, 0, 0x19), // MULTU
            rType(0, 0, 10, 0x12),
            rType(0, 0, 11, 0x10)
        )

        assertEquals(6, rsp.run(6))
        assertEquals(0xFFFFFFFE.toInt(), rsp.lo)
        assertEquals(1, rsp.hi)
        assertEquals(0xFFFFFFFE.toInt(), rsp.gpr[10])
        assertEquals(1, rsp.gpr[11])
    }

    @Test
    fun moveToHiAndLoUpdatesTheSpecialRegisters() {
        val (bus, rsp) = setup(
            addiu(8, 0, 0x1234),
            addiu(9, 0, -2),
            rType(8, 0, 0, 0x11), // MTHI r8
            rType(9, 0, 0, 0x13), // MTLO r9
            rType(0, 0, 10, 0x10), // MFHI r10
            rType(0, 0, 11, 0x12) // MFLO r11
        )

        assertEquals(6, rsp.run(6))
        assertEquals(0x1234, rsp.hi)
        assertEquals(-2, rsp.lo)
        assertEquals(0x1234, rsp.gpr[10])
        assertEquals(-2, rsp.gpr[11])
    }

    @Test
    fun vectorAluSupportsSlidingAndBroadcastElementSelectors() {
        val (bus, rsp) = setup(
            vectorOp(0x10, vd = 3, vs = 1, vt = 2, element = 1),
            vectorOp(0x10, vd = 4, vs = 1, vt = 2, element = 10)
        )
        for (lane in 0 until 8) {
            rsp.vectorUnit.writeLane(1, lane, 100 + lane)
            rsp.vectorUnit.writeLane(2, lane, 10 + lane)
        }

        assertEquals(2, rsp.run(2))
        // Selector 1: [v1,v2,...,v7,v7] from VT.
        for (lane in 0 until 7) {
            assertEquals(100 + lane + 11 + lane, rsp.vectorUnit.readLane(3, lane))
        }
        assertEquals(107 + 17, rsp.vectorUnit.readLane(3, 7))
        // Selector 10 broadcasts VT lane 2 to every destination lane.
        for (lane in 0 until 8) {
            assertEquals(100 + lane + 12, rsp.vectorUnit.readLane(4, lane))
        }
    }

    @Test
    fun decodesVmulfIntoVectorUnit() {
        val (_, rsp) = setup(vectorOp(0x00, vd = 3, vs = 1, vt = 2))
        rsp.vectorUnit.writeLane(1, 0, 0x4000)
        rsp.vectorUnit.writeLane(2, 0, 0x4000)

        assertEquals(1, rsp.run(1))
        assertEquals(8192, rsp.vectorUnit.readLane(3, 0).toShort().toInt())
        assertEquals(536903680L, rsp.vectorUnit.readAccumulator(0))
    }

    @Test
    fun executesScalarImmediateArithmeticAndStopsOnBreak() {
        val (bus, rsp) = setup(
            addiu(8, 0, 42),
            addiu(9, 8, 1),
            (0x2B shl 26) or (0 shl 21) or (9 shl 16),
            (0x23 shl 26) or (0 shl 21) or (10 shl 16),
            0x0000000D
        )

        assertEquals(5, rsp.run(20))
        assertEquals(42, rsp.gpr[8])
        assertEquals(43, rsp.gpr[9])
        assertEquals(43, rsp.gpr[10])
        assertEquals(43, bus.read32(N64Bus.SP_DMEM_BASE))
        assertTrue(rsp.halted)
        assertEquals(N64Bus.SP_STATUS_HALT or N64Bus.SP_STATUS_BROKE, bus.read32(N64Bus.SP_STATUS_REG))
        assertEquals(5L, rsp.executedInstructions)
    }

    @Test
    fun branchExecutesDelaySlotAndSkipsFallthrough() {
        val (bus, rsp) = setup(
            addiu(8, 0, 1),
            (0x04 shl 26) or (8 shl 21) or (8 shl 16) or 2,
            addiu(9, 0, 7),
            addiu(10, 0, 99),
            addiu(11, 0, 10)
        )

        assertEquals(4, rsp.run(4))
        assertEquals(7, rsp.gpr[9])
        assertEquals(0, rsp.gpr[10])
        assertEquals(10, rsp.gpr[11])
        assertEquals(20, rsp.pc)
        assertFalse(rsp.halted)
    }

    @Test
    fun bltzAndBgezUseSignedComparisonAndExecuteDelaySlots() {
        val (bus, rsp) = setup(
            addiu(8, 0, -1),
            (0x01 shl 26) or (8 shl 21) or (0x00 shl 16) or 2, // BLTZ r8,+2
            addiu(9, 0, 7), // delay slot
            addiu(10, 0, 99), // skipped
            addiu(11, 0, 11), // target
            addiu(8, 0, 0),
            (0x01 shl 26) or (8 shl 21) or (0x01 shl 16) or 2, // BGEZ r8,+2
            addiu(12, 0, 12), // delay slot
            addiu(13, 0, 99), // skipped
            addiu(14, 0, 14) // target
        )

        assertEquals(8, rsp.run(8))
        assertEquals(7, rsp.gpr[9])
        assertEquals(0, rsp.gpr[10])
        assertEquals(11, rsp.gpr[11])
        assertEquals(12, rsp.gpr[12])
        assertEquals(0, rsp.gpr[13])
        assertEquals(14, rsp.gpr[14])
        assertEquals(40, rsp.pc)
    }

    @Test
    fun bltzAndBgezDoNotBranchWhenSignedConditionIsFalse() {
        val (_, rsp) = setup(
            addiu(8, 0, 1),
            (0x01 shl 26) or (8 shl 21) or (0x00 shl 16) or 1, // BLTZ not taken
            addiu(9, 0, 9),
            addiu(8, 0, -1),
            (0x01 shl 26) or (8 shl 21) or (0x01 shl 16) or 1, // BGEZ not taken
            addiu(10, 0, 10)
        )

        assertEquals(6, rsp.run(6))
        assertEquals(9, rsp.gpr[9])
        assertEquals(10, rsp.gpr[10])
        assertEquals(24, rsp.pc)
    }

    @Test
    fun executionIsBoundedByInstructionBudget() {
        val (_, rsp) = setup(addiu(8, 0, 1), addiu(9, 0, 2), addiu(10, 0, 3))

        assertEquals(2, rsp.run(2))
        assertEquals(8, rsp.pc)
        assertEquals(2L, rsp.executedInstructions)
    }

    @Test
    fun mfc0AndMtc0AccessSpStatusRegister() {
        val (bus, rsp) = setup(
            addiu(9, 0, N64Bus.SP_STATUS_CMD_SET_HALT),
            (0x10 shl 26) or (8 shl 16) or (4 shl 11),
            (0x10 shl 26) or (4 shl 21) or (9 shl 16) or (4 shl 11)
        )

        assertEquals(3, rsp.run(10))
        assertEquals(0, rsp.gpr[8])
        assertTrue(rsp.halted)
        assertEquals(N64Bus.SP_STATUS_HALT, bus.read32(N64Bus.SP_STATUS_REG))
    }

    @Test
    fun lqvAndLrvCombineToLoadMisalignedSixteenByteWindow() {
        val instructions = listOf(
            addiu(8, 0, 0x108),
            vectorMemory(0x32, 8, 3, 4, 0, 0),
            addiu(8, 8, 0x10),
            vectorMemory(0x32, 8, 3, 5, 0, 0)
        )
        val (bus, rsp) = setup(*instructions.toIntArray())
        for (index in 0 until 16) bus.write8(N64Bus.SP_DMEM_BASE + 0x108 + index, 0x40 + index)

        assertEquals(4, rsp.run(4))
        for (index in 0 until 16) {
            assertEquals(0x40 + index, rsp.vectorUnit.readByte(3, index))
        }
    }

    @Test
    fun lqvHonorsElementAndDoesNotOverwriteRemainingVectorBytes() {
        val (bus, rsp) = setup(
            addiu(8, 0, 0x108),
            vectorMemory(0x32, 8, 4, 4, 4, 0)
        )
        for (index in 0 until 16) {
            bus.write8(N64Bus.SP_DMEM_BASE + 0x108 + index, 0x20 + index)
            rsp.vectorUnit.writeByte(4, index, 0xA0 + index)
        }

        rsp.run(2)
        for (index in 0 until 4) assertEquals(0xA0 + index, rsp.vectorUnit.readByte(4, index))
        for (index in 4 until 12) assertEquals(0x20 + index - 4, rsp.vectorUnit.readByte(4, index))
        for (index in 12 until 16) assertEquals(0xA0 + index, rsp.vectorUnit.readByte(4, index))
    }

    @Test
    fun sqvAndSrvStoreMisalignedWindowWithElementRotation() {
        val (bus, rsp) = setup(
            addiu(8, 0, 0x108),
            vectorMemory(0x3A, 8, 5, 4, 4, 0),
            addiu(8, 8, 0x10),
            vectorMemory(0x3A, 8, 5, 5, 4, 0)
        )
        for (index in 0 until 16) rsp.vectorUnit.writeByte(5, index, 0x60 + index)

        assertEquals(4, rsp.run(4))
        for (index in 0 until 8) {
            assertEquals(0x64 + index, bus.read8(N64Bus.SP_DMEM_BASE + 0x108 + index))
            assertEquals(0x60 + ((12 + index) and 0x0F), bus.read8(N64Bus.SP_DMEM_BASE + 0x110 + index))
        }
    }

    @Test
    fun lrvWithAlignedAddressDoesNotModifyVector() {
        val (bus, rsp) = setup(
            addiu(8, 0, 0x110),
            vectorMemory(0x32, 8, 6, 5, 0, 0)
        )
        for (index in 0 until 16) {
            bus.write8(N64Bus.SP_DMEM_BASE + 0x100 + index, 0x10 + index)
            rsp.vectorUnit.writeByte(6, index, 0xB0 + index)
        }

        rsp.run(2)
        for (index in 0 until 16) assertEquals(0xB0 + index, rsp.vectorUnit.readByte(6, index))
    }


    @Test
    fun lpvLoadsEightWrappedBytesIntoHighByteOfEachLane() {
        val (bus, rsp) = setup(
            addiu(8, 0, 0x108),
            vectorMemory(0x32, 8, 7, 6, 0, 0)
        )
        for (index in 0 until 16) {
            bus.write8(N64Bus.SP_DMEM_BASE + 0x108 + index, 0x30 + index)
            rsp.vectorUnit.writeLane(7, index % 8, 0xFFFF)
        }

        assertEquals(2, rsp.run(2))
        for (lane in 0 until 8) {
            assertEquals((0x30 + lane) shl 8, rsp.vectorUnit.readLane(7, lane))
        }
    }

    @Test
    fun luvRotatesMemoryByElementAndPlacesByteAtBitSeven() {
        val (bus, rsp) = setup(
            addiu(8, 0, 0x108),
            vectorMemory(0x32, 8, 9, 7, 5, 0)
        )
        for (index in 0 until 16) bus.write8(N64Bus.SP_DMEM_BASE + 0x108 + index, 0x10 + index)

        assertEquals(2, rsp.run(2))
        for (lane in 0 until 8) {
            val memoryIndex = (11 + lane) and 0x0F
            assertEquals((0x10 + memoryIndex) shl 7, rsp.vectorUnit.readLane(9, lane))
        }
    }

    @Test
    fun lpvUsesSignedScaledSevenBitOffset() {
        val (bus, rsp) = setup(
            addiu(8, 0, 0x110),
            vectorMemory(0x32, 8, 10, 6, 0, 0x7F)
        )
        for (index in 0 until 8) bus.write8(N64Bus.SP_DMEM_BASE + 0x108 + index, 0x50 + index)

        assertEquals(2, rsp.run(2))
        for (lane in 0 until 8) {
            assertEquals((0x50 + lane) shl 8, rsp.vectorUnit.readLane(10, lane))
        }
    }



    @Test
    fun lhvReadsFromEffectiveAddressWithStrideTwoIntoBitSeven() {
        val (bus, rsp) = setup(
            addiu(8, 0, 0x106),
            vectorMemory(0x32, 8, 15, 8, 0, 0)
        )
        for (index in 0 until 0x20) {
            bus.write8(N64Bus.SP_DMEM_BASE + 0x100 + index, 0x20 + index)
        }
        for (lane in 0 until 8) rsp.vectorUnit.writeLane(15, lane, 0xFFFF)

        assertEquals(2, rsp.run(2))
        for (lane in 0 until 8) {
            assertEquals((0x26 + lane * 2) shl 7, rsp.vectorUnit.readLane(15, lane))
        }
    }

    @Test
    fun lhvWrapsDmemWhenStrideTwoCrossesTheEnd() {
        val (bus, rsp) = setup(
            addiu(8, 0, 0xFF8),
            vectorMemory(0x32, 8, 17, 8, 0, 0)
        )
        for (index in 0 until 8) {
            bus.write8(N64Bus.SP_DMEM_BASE + ((0xFF8 + index * 2) and 0xFFF), 0x70 + index)
        }

        assertEquals(2, rsp.run(2))
        for (lane in 0 until 8) {
            assertEquals((0x70 + lane) shl 7, rsp.vectorUnit.readLane(17, lane))
        }
    }

    @Test
    fun lhvPreservesOddAddressParityFromEffectiveAddress() {
        val (bus, rsp) = setup(
            addiu(8, 0, 0x107),
            vectorMemory(0x32, 8, 18, 8, 0, 0)
        )
        for (lane in 0 until 8) {
            bus.write8(N64Bus.SP_DMEM_BASE + 0x107 + lane * 2, 0x30 + lane)
        }

        assertEquals(2, rsp.run(2))
        for (lane in 0 until 8) {
            assertEquals((0x30 + lane) shl 7, rsp.vectorUnit.readLane(18, lane))
        }
    }

    @Test
    fun lhvAppliesSignedSixteenByteScaledOffset() {
        val (bus, rsp) = setup(
            addiu(8, 0, 0x110),
            vectorMemory(0x32, 8, 19, 8, 0, 0x7F)
        )
        for (lane in 0 until 8) {
            bus.write8(N64Bus.SP_DMEM_BASE + 0x100 + lane * 2, 0x40 + lane)
        }

        assertEquals(2, rsp.run(2))
        for (lane in 0 until 8) {
            assertEquals((0x40 + lane) shl 7, rsp.vectorUnit.readLane(19, lane))
        }
    }

    @Test
    fun shvStoresOddBytesFromVectorIntoAlternatingDmemAddresses() {
        val (bus, rsp) = setup(
            addiu(8, 0, 0x106),
            vectorMemory(0x3A, 8, 16, 8, 0, 0)
        )
        for (index in 0 until 16) bus.write8(N64Bus.SP_DMEM_BASE + 0x100 + index, 0xCC)
        for (lane in 0 until 8) rsp.vectorUnit.writeLane(16, lane, 0x1200 + lane * 0x0100)

        assertEquals(2, rsp.run(2))
        for (lane in 0 until 8) {
            assertEquals((0x1200 + lane * 0x0100 ushr 7) and 0xFF,
                bus.read8(N64Bus.SP_DMEM_BASE + 0x101 + lane * 2))
            assertEquals(0xCC, bus.read8(N64Bus.SP_DMEM_BASE + 0x100 + lane * 2))
        }
    }

    @Test
    fun spvAndSuvUseDifferentPackingAcrossTheEightToSixteenElementBoundary() {
        val (bus, rsp) = setup(
            addiu(8, 0, 0x200),
            addiu(9, 0, 0x210),
            vectorMemory(0x3A, 8, 11, 6, 5, 0),
            vectorMemory(0x3A, 9, 12, 7, 5, 0)
        )
        for (lane in 0 until 8) {
            rsp.vectorUnit.writeLane(11, lane, 0x1200 + lane * 0x0100)
            rsp.vectorUnit.writeLane(12, lane, 0x3400 + lane * 0x0100)
        }

        assertEquals(4, rsp.run(4))
        // SPV: element positions 5..7 use bits 15..8; 8..12 use bits 14..7.
        assertEquals(0x17, bus.read8(N64Bus.SP_DMEM_BASE + 0x200))
        assertEquals(0x19, bus.read8(N64Bus.SP_DMEM_BASE + 0x202))
        assertEquals((0x1200 ushr 7) and 0xFF, bus.read8(N64Bus.SP_DMEM_BASE + 0x203))
        assertEquals((0x1600 ushr 7) and 0xFF, bus.read8(N64Bus.SP_DMEM_BASE + 0x207))

        // SUV swaps the packing rule on the same wrapped element positions.
        assertEquals((0x3900 ushr 7) and 0xFF, bus.read8(N64Bus.SP_DMEM_BASE + 0x210))
        assertEquals((0x3B00 ushr 7) and 0xFF, bus.read8(N64Bus.SP_DMEM_BASE + 0x212))
        assertEquals(0x34, bus.read8(N64Bus.SP_DMEM_BASE + 0x213))
        assertEquals(0x38, bus.read8(N64Bus.SP_DMEM_BASE + 0x217))
    }

    @Test
    fun packedStoresUseSignedScaledOffsetAndWrapDmem() {
        val (bus, rsp) = setup(
            addiu(8, 0, 0x004),
            vectorMemory(0x3A, 8, 13, 6, 0, 0x7F)
        )
        for (lane in 0 until 8) rsp.vectorUnit.writeLane(13, lane, 0x5A00 + lane * 0x0100)

        assertEquals(2, rsp.run(2))
        // 0x004 + (-1 * 8) = 0xFFC; the final four bytes wrap to DMEM offset 0.
        assertEquals(0x5A, bus.read8(N64Bus.SP_DMEM_BASE + 0xFFC))
        assertEquals(0x5B, bus.read8(N64Bus.SP_DMEM_BASE + 0xFFD))
        assertEquals(0x5C, bus.read8(N64Bus.SP_DMEM_BASE + 0xFFE))
        assertEquals(0x5D, bus.read8(N64Bus.SP_DMEM_BASE + 0xFFF))
        assertEquals(0x5E, bus.read8(N64Bus.SP_DMEM_BASE))
        assertEquals(0x5F, bus.read8(N64Bus.SP_DMEM_BASE + 1))
        assertEquals(0x60, bus.read8(N64Bus.SP_DMEM_BASE + 2))
        assertEquals(0x61, bus.read8(N64Bus.SP_DMEM_BASE + 3))
    }

    @Test
    fun packedStoresCoverAllElementWindowBoundaries() {
        val instructions = mutableListOf<Int>()
        for (index in 0 until 4) {
            instructions += addiu(8 + index, 0, 0x300 + index * 0x10)
            instructions += vectorMemory(0x3A, 8 + index, 14, if (index % 2 == 0) 6 else 7,
                listOf(0, 7, 8, 15)[index], 0)
        }
        val (bus, rsp) = setup(*instructions.toIntArray())
        for (lane in 0 until 8) rsp.vectorUnit.writeLane(14, lane, 0x1200 + lane * 0x0100)

        assertEquals(8, rsp.run(8))
        val elements = listOf(0, 7, 8, 15)
        for (caseIndex in elements.indices) {
            val element = elements[caseIndex]
            val operation = if (caseIndex % 2 == 0) 6 else 7
            val address = 0x300 + caseIndex * 0x10
            for (byteIndex in 0 until 8) {
                val vectorElement = (element + byteIndex) and 0x0F
                val lane = vectorElement and 7
                val upperHalf = vectorElement < 8
                val useHighByte = if (operation == 6) upperHalf else !upperHalf
                val laneValue = 0x1200 + lane * 0x0100
                val expected = if (useHighByte) (laneValue ushr 8) and 0xFF
                    else (laneValue ushr 7) and 0xFF
                assertEquals("element=$element byte=$byteIndex", expected,
                    bus.read8(N64Bus.SP_DMEM_BASE + address + byteIndex))
            }
        }
    }

    @Test
    fun diagnosticSnapshotIncludesScalarAndVectorStateWithoutMmioSideEffects() {
        val (bus, rsp) = setup(addiu(8, 0, 7))
        rsp.vectorUnit.writeLane(4, 2, 0x1234)
        val mmioBefore = bus.diagnosticMmioHistory.size

        val snapshot = rsp.diagnosticSnapshot()

        assertTrue(snapshot.contains("RSP GPR[8]=0x00000000"))
        assertTrue(snapshot.contains("V4="))
        assertTrue(snapshot.contains("0x1234"))
        assertEquals(mmioBefore, bus.diagnosticMmioHistory.size)
    }


    @Test
    fun decodesVaddcAndVsubcAndPreservesTheirCarryFlags() {
        val (bus, rsp) = setup(
            vectorOp(0x14, vd = 3, vs = 1, vt = 2),
            vectorOp(0x15, vd = 4, vs = 1, vt = 2)
        )
        rsp.vectorUnit.writeLane(1, 0, 0xFFFF)
        rsp.vectorUnit.writeLane(2, 0, 1)
        rsp.vectorUnit.writeLane(1, 1, 9)
        rsp.vectorUnit.writeLane(2, 1, 4)

        assertEquals(1, rsp.run(1))
        assertEquals(0, rsp.vectorUnit.readLane(3, 0))
        assertEquals(1, rsp.vectorUnit.vcoLow)
        assertEquals(1, rsp.run(1))
        assertEquals(0xFFFE, rsp.vectorUnit.readLane(4, 0))
        assertEquals(0, rsp.vectorUnit.vcoLow)
        assertEquals(3, rsp.vectorUnit.vcoHigh)
    }

    @Test
    fun decodesVmuluThroughCop2VectorInstruction() {
        val (_, rsp) = setup(vectorOp(0x01, vd = 3, vs = 1, vt = 2))
        rsp.vectorUnit.writeLane(1, 0, 0x4000)
        rsp.vectorUnit.writeLane(2, 0, 0x4000)
        rsp.vectorUnit.writeLane(1, 1, -0x4000)
        rsp.vectorUnit.writeLane(2, 1, 0x4000)

        assertEquals(1, rsp.run(1))
        assertEquals(8192, rsp.vectorUnit.readLane(3, 0))
        assertEquals(0, rsp.vectorUnit.readLane(3, 1))
    }

    @Test
    fun decodesVabsThroughCop2VectorInstruction() {
        val (_, rsp) = setup(vectorOp(0x13, vd = 3, vs = 1, vt = 2))
        rsp.vectorUnit.writeLane(1, 0, -1)
        rsp.vectorUnit.writeLane(2, 0, -20)
        rsp.vectorUnit.writeLane(1, 1, 0)
        rsp.vectorUnit.writeLane(2, 1, 100)

        assertEquals(1, rsp.run(1))
        assertEquals(20, rsp.vectorUnit.readLane(3, 0))
        assertEquals(0, rsp.vectorUnit.readLane(3, 1))
    }


    @Test
    fun rspContextSnapshotRestoresScalarVectorAndPendingBranchState() {
        val (_, rsp) = setup(
            addiu(8, 0, 41),
            (0x04 shl 26) or (8 shl 21) or (8 shl 16) or 2,
            addiu(9, 0, 7)
        )
        rsp.vectorUnit.writeLane(3, 2, 0xBEEF)
        rsp.run(2)
        val saved = rsp.snapshotContext()

        rsp.reset(0)
        rsp.vectorUnit.writeLane(3, 2, 0x1111)
        rsp.restoreContext(saved)

        assertEquals(saved.pc, rsp.pc)
        assertEquals(saved.gpr[8], rsp.gpr[8])
        assertEquals(saved.gpr[9], rsp.gpr[9])
        assertEquals(0xBEEF, rsp.vectorUnit.readLane(3, 2))
        assertEquals(saved.executedInstructions, rsp.executedInstructions)
        assertEquals(saved.delayedTarget, rsp.snapshotContext().delayedTarget)
    }

    @Test
    fun rspContextRejectsMisalignedProgramCounterWithoutChangingState() {
        val (_, rsp) = setup(addiu(8, 0, 9))
        val saved = rsp.snapshotContext()
        val invalid = saved.copy(pc = 3)
        val error = runCatching { rsp.restoreContext(invalid) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertEquals(saved.pc, rsp.pc)
    }


    @Test fun executesVsarFromAccumulator() {
        val bus = N64Bus()
        val rsp = N64RspCore(bus)
        rsp.vectorUnit.writeLane(1, 0, 0x4000)
        rsp.vectorUnit.writeLane(2, 0, 0x4000)
        rsp.vectorUnit.multiplyFractional(3, 1, 2)
        // VSAR vd=v4, element=9 (middle accumulator slice), function=0x1D.
        val instruction = (0x12 shl 26) or (9 shl 21) or (3 shl 16) or (4 shl 11) or 0x1D
        bus.write32(N64Bus.SP_IMEM_BASE, instruction)
        rsp.reset(0)
        rsp.step()
        assertEquals(0x2000, rsp.vectorUnit.readLane(4, 0))
    }
\n}\n