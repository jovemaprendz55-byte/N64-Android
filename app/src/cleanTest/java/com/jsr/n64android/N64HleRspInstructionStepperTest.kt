package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class N64HleRspInstructionStepperTest {
    private fun put(memory: N64HleGuestAddressSpace, pc: Int, instruction: Int) {
        memory.write32(0xA4001000L + pc, instruction.toLong() and 0xFFFF_FFFFL)
    }

    @Test
    fun executesScalarImmediateAndSpecialArithmeticFromImem() {
        val memory = N64HleGuestAddressSpace()
        put(memory, 0, (0x09 shl 26) or (1 shl 16) or 0x1234) // ADDIU r1,r0,0x1234
        put(memory, 4, (0x00 shl 26) or (1 shl 21) or (1 shl 16) or (2 shl 11) or 0x21) // ADDU r2,r1,r1
        val state = N64HleRspCpuState()
        val stepper = N64HleRspInstructionStepper(memory)

        stepper.step(state)
        stepper.step(state)

        assertEquals(0x1234, state.readGpr(1))
        assertEquals(0x2468, state.readGpr(2))
        assertEquals(8, state.programCounter)
        assertEquals(0, state.readGpr(0))
    }

    @Test
    fun branchExecutesOneDelaySlotBeforeChangingPc() {
        val memory = N64HleGuestAddressSpace()
        put(memory, 0, (0x09 shl 26) or (1 shl 16) or 1) // ADDIU r1,r0,1
        put(memory, 4, (0x04 shl 26) or (1 shl 21) or (1 shl 16) or 2) // BEQ r1,r1,+2
        put(memory, 8, (0x09 shl 26) or (2 shl 16) or 7) // delay slot: ADDIU r2,r0,7
        val state = N64HleRspCpuState()
        val stepper = N64HleRspInstructionStepper(memory)

        stepper.step(state)
        stepper.step(state)
        assertEquals(8, state.programCounter)
        stepper.step(state)

        assertEquals(7, state.readGpr(2))
        assertEquals(16, state.programCounter)
    }

    @Test
    fun scalarLoadAndStoreUseSpDmem() {
        val memory = N64HleGuestAddressSpace()
        put(memory, 0, (0x2B shl 26) or (1 shl 21) or (2 shl 16) or 0x20) // SW r2,0x20(r1)
        put(memory, 4, (0x23 shl 26) or (1 shl 21) or (3 shl 16) or 0x20) // LW r3,0x20(r1)
        val state = N64HleRspCpuState()
        state.writeGpr(1, 0x40)
        state.writeGpr(2, 0x89ABCDEF.toInt())
        val stepper = N64HleRspInstructionStepper(memory)

        stepper.step(state)
        stepper.step(state)

        assertEquals(0x89ABCDEF.toInt(), state.readGpr(3))
        assertEquals(0x89ABCDEF.toInt(), memory.read32(0xA4000060L).toInt())
    }

    @Test
    fun breakHaltsSpAndRaisesMiSpInterrupt() {
        val memory = N64HleGuestAddressSpace()
        put(memory, 0, 0x0000000D) // BREAK
        memory.write32(0xA4040010L, 1L shl 8) // enable interrupt-on-break
        memory.write32(0xA4040010L, 1L) // release SP HALT
        val state = N64HleRspCpuState()
        val stepper = N64HleRspInstructionStepper(memory)

        stepper.step(state)

        assertTrue(state.halted)
        assertEquals(71L, memory.read32(0xA4040010L)) // HALT | BROKE | INTR | INTR_BREAK
        assertEquals(1L, memory.read32(0xA4300008L)) // MI_INTR.SP
        assertThrows(IllegalStateException::class.java) { stepper.step(state) }
    }

    @Test
    fun breakWithoutInterruptOnBreakHaltsWithoutRaisingMiInterrupt() {
        val memory = N64HleGuestAddressSpace()
        put(memory, 0, 0x0000000D) // BREAK
        memory.write32(0xA4040010L, 1L) // release SP HALT
        val state = N64HleRspCpuState()
        N64HleRspInstructionStepper(memory).step(state)

        assertTrue(state.halted)
        assertEquals(3L, memory.read32(0xA4040010L)) // HALT | BROKE
        assertEquals(0L, memory.read32(0xA4300008L))
    }

    @Test
    fun unsupportedDelaySlotInstructionDoesNotDiscardPendingBranch() {
        val memory = N64HleGuestAddressSpace()
        put(memory, 0, (0x04 shl 26) or 2) // BEQ r0,r0,+2
        put(memory, 4, 0xFC000000.toInt()) // unsupported opcode in delay slot
        val state = N64HleRspCpuState()
        val stepper = N64HleRspInstructionStepper(memory)

        stepper.step(state)
        assertThrows(N64HleRspUnsupportedInstructionException::class.java) { stepper.step(state) }
        assertEquals(4, state.programCounter)

        put(memory, 4, 0) // replace failed delay-slot instruction with NOP
        stepper.step(state)
        assertEquals(12, state.programCounter)
    }

    @Test
    fun jumpAndLinkWritesReturnAddressAndTransfersAfterDelaySlot() {
        val memory = N64HleGuestAddressSpace()
        put(memory, 0, (0x03 shl 26) or 0x004) // JAL 0x10
        put(memory, 4, (0x09 shl 26) or (4 shl 16) or 9) // delay slot
        val state = N64HleRspCpuState()
        val stepper = N64HleRspInstructionStepper(memory)

        stepper.step(state)
        assertEquals(8, state.readGpr(31))
        stepper.step(state)

        assertEquals(9, state.readGpr(4))
        assertEquals(16, state.programCounter)
        assertFalse(state.halted)
    }

    @Test
    fun variableShiftsAndHiLoMultiplyDivideAreImplemented() {
        val memory = N64HleGuestAddressSpace()
        put(memory, 0, (0x00 shl 26) or (1 shl 21) or (2 shl 16) or (3 shl 11) or 0x04) // SLLV r3,r2,r1
        put(memory, 4, (0x00 shl 26) or (1 shl 21) or (2 shl 16) or 0x19) // MULTU r1,r2
        put(memory, 8, (0x00 shl 26) or (4 shl 11) or 0x10) // MFHI r4
        put(memory, 12, (0x00 shl 26) or (5 shl 11) or 0x12) // MFLO r5
        put(memory, 16, (0x00 shl 26) or (1 shl 21) or (2 shl 16) or 0x1B) // DIVU r1,r2
        put(memory, 20, (0x00 shl 26) or (6 shl 11) or 0x12) // MFLO r6
        put(memory, 24, (0x00 shl 26) or (7 shl 11) or 0x10) // MFHI r7
        val state = N64HleRspCpuState()
        state.writeGpr(1, 40)
        state.writeGpr(2, 3)
        val stepper = N64HleRspInstructionStepper(memory)

        repeat(7) { stepper.step(state) }

        assertEquals(768, state.readGpr(3))
        assertEquals(0, state.readGpr(4))
        assertEquals(120, state.readGpr(5))
        assertEquals(13, state.readGpr(6))
        assertEquals(1, state.readGpr(7))
    }

    @Test
    fun signedDivideByZeroUsesR4300CompatibleRspResultConvention() {
        val memory = N64HleGuestAddressSpace()
        put(memory, 0, (0x00 shl 26) or (1 shl 21) or 0x1A) // DIV r1,r0
        put(memory, 4, (0x00 shl 26) or (2 shl 11) or 0x12) // MFLO r2
        put(memory, 8, (0x00 shl 26) or (3 shl 11) or 0x10) // MFHI r3
        val state = N64HleRspCpuState()
        state.writeGpr(1, -9)
        val stepper = N64HleRspInstructionStepper(memory)

        repeat(3) { stepper.step(state) }

        assertEquals(1, state.readGpr(2))
        assertEquals(-9, state.readGpr(3))
    }


    @Test
    fun regimmBranchesAndJalrRespectDelaySlots() {
        val memory = N64HleGuestAddressSpace()
        put(memory, 0, (0x01 shl 26) or (1 shl 16) or 2) // BGEZ r0,+2
        put(memory, 4, (0x09 shl 26) or (2 shl 16) or 5) // delay slot
        put(memory, 16, (0x00 shl 26) or (3 shl 21) or (4 shl 11) or 0x09) // JALR r4,r3
        put(memory, 20, (0x09 shl 26) or (5 shl 16) or 6) // delay slot
        val state = N64HleRspCpuState()
        state.writeGpr(3, 0x30)
        val stepper = N64HleRspInstructionStepper(memory)

        stepper.step(state)
        stepper.step(state)
        assertEquals(5, state.readGpr(2))
        assertEquals(12, state.programCounter)
        state.programCounter = 16
        stepper.step(state)
        assertEquals(24, state.readGpr(4))
        stepper.step(state)

        assertEquals(6, state.readGpr(5))
        assertEquals(0x30, state.programCounter)
    }


    @Test
    fun decodesAndExecutesRspVectorArithmetic() {
        val memory = N64HleGuestAddressSpace()
        val vectors = N64HleRspVectorState()
        val stepper = N64HleRspInstructionStepper(memory, vectors)
        fun encode(pc: Int, vd: Int, vs: Int, vt: Int, function: Int) {
            put(memory, pc, (0x12 shl 26) or (16 shl 21) or (vt shl 16) or (vs shl 11) or (vd shl 6) or function)
        }
        vectors.writeElement(1, 0, 100)
        vectors.writeElement(2, 0, 25)
        encode(0, 3, 1, 2, 0x10)
        val addState = N64HleRspCpuState()
        stepper.step(addState)
        assertEquals(125, vectors.readSignedElement(3, 0))

        vectors.writeElement(1, 0, 100)
        vectors.writeElement(2, 0, 25)
        encode(4, 4, 1, 2, 0x11)
        val subState = N64HleRspCpuState().also { it.programCounter = 4 }
        stepper.step(subState)
        assertEquals(75, vectors.readSignedElement(4, 0))

        vectors.writeElement(1, 0, 0x4000)
        vectors.writeElement(2, 0, 0x4000)
        encode(8, 5, 1, 2, 0x00)
        val mulState = N64HleRspCpuState().also { it.programCounter = 8 }
        stepper.step(mulState)
        assertEquals(0x2000, vectors.readElement(5, 0))
    }

    @Test
    fun decodesRspVectorAbsoluteWithSignedSelectorLanes() {
        val memory = N64HleGuestAddressSpace()
        val vectors = N64HleRspVectorState()
        for (lane in 0 until 8) {
            vectors.writeElement(1, lane, when (lane % 3) {
                0 -> -1
                1 -> 0
                else -> 1
            })
            vectors.writeElement(2, lane, 100 + lane)
        }
        put(memory, 0, (0x12 shl 26) or (16 shl 21) or (2 shl 16) or
            (1 shl 11) or (3 shl 6) or 0x13) // VABS v3,v1,v2[e=0]
        N64HleRspInstructionStepper(memory, vectors).step(N64HleRspCpuState())

        for (lane in 0 until 8) {
            val expected = when (lane % 3) {
                0 -> -100
                1 -> 0
                else -> 100
            }
            assertEquals(expected, vectors.readSignedElement(3, lane))
        }
    }

    @Test
    fun decodesRspVectorAddCarryAndSubtractCarry() {
        val memory = N64HleGuestAddressSpace()
        val vectors = N64HleRspVectorState()
        fun putVector(pc: Int, vd: Int, function: Int) {
            put(memory, pc, (0x12 shl 26) or (16 shl 21) or (2 shl 16) or
                (1 shl 11) or (vd shl 6) or function)
        }
        vectors.writeElement(1, 0, 0xFFFF)
        vectors.writeElement(2, 0, 1)
        putVector(0, 3, 0x14) // VADDC
        val addState = N64HleRspCpuState()
        val stepper = N64HleRspInstructionStepper(memory, vectors)
        stepper.step(addState)
        assertEquals(0, vectors.readElement(3, 0))
        assertEquals(1, vectors.vco)

        vectors.writeElement(1, 0, 0)
        vectors.writeElement(2, 0, 1)
        putVector(4, 4, 0x15) // VSUBC
        val subState = N64HleRspCpuState().also { it.programCounter = 4 }
        stepper.step(subState)
        assertEquals(0xFFFF, vectors.readElement(4, 0))
        assertEquals(0x0101, vectors.vco)
    }

    @Test
    fun vectorElementSelectorBroadcastsSelectedVtLaneToAllLanes() {
        val memory = N64HleGuestAddressSpace()
        val vectors = N64HleRspVectorState()
        for (lane in 0 until 8) {
            vectors.writeElement(1, lane, 10 + lane)
            vectors.writeElement(2, lane, 100 + lane)
        }
        val selectedElement = 3
        val instruction = (0x12 shl 26) or ((16 + selectedElement) shl 21) or
            (2 shl 16) or (1 shl 11) or (3 shl 6) or 0x10
        put(memory, 0, instruction)
        N64HleRspInstructionStepper(memory, vectors).step(N64HleRspCpuState())
        for (lane in 0 until 8) {
            assertEquals(113 + lane, vectors.readSignedElement(3, lane))
        }
    }

    @Test
    fun unsupportedVectorElementPermutationIsRejectedExplicitly() {
        val memory = N64HleGuestAddressSpace()
        val instruction = (0x12 shl 26) or (24 shl 21) or (2 shl 16) or
            (1 shl 11) or (3 shl 6) or 0x10
        put(memory, 0, instruction)
        assertThrows(N64HleRspUnsupportedInstructionException::class.java) {
            N64HleRspInstructionStepper(memory).step(N64HleRspCpuState())
        }
    }

    @Test
    fun decodesRspVectorBitwiseOperations() {
        val memory = N64HleGuestAddressSpace()
        val vectors = N64HleRspVectorState()
        val stepper = N64HleRspInstructionStepper(memory, vectors)
        vectors.writeElement(1, 0, 0xF0F0)
        vectors.writeElement(2, 0, 0x0FF0)
        val functions = listOf(0x28, 0x2A, 0x2B, 0x2C)
        val destinations = listOf(3, 4, 5, 6)
        functions.forEachIndexed { index, function ->
            put(memory, index * 4, (0x12 shl 26) or (16 shl 21) or (2 shl 16) or (1 shl 11) or
                (destinations[index] shl 6) or function)
            val state = N64HleRspCpuState().also { it.programCounter = index * 4 }
            stepper.step(state)
        }
        assertEquals(0x00F0, vectors.readElement(3, 0))
        assertEquals(0xFFF0, vectors.readElement(4, 0))
        assertEquals(0x000F, vectors.readElement(5, 0))
        assertEquals(0xFF00, vectors.readElement(6, 0))
    }


    @Test
    fun lqvAndSqvTransferOnlyBytesRemainingInAlignedMemoryBlock() {
        val memory = N64HleGuestAddressSpace()
        val vectors = N64HleRspVectorState()
        val stepper = N64HleRspInstructionStepper(memory, vectors)
        val address = 0x23
        for (index in 0 until 16) memory.write8(0xA4000000L + address + index, 0x40 + index)

        // LQV v2[e=2], 0(r1): address 0x23 leaves 13 bytes in its 16-byte block.
        put(memory, 0, (0x32 shl 26) or (1 shl 21) or (2 shl 16) or (2 shl 7))
        val loadState = N64HleRspCpuState().also { it.writeGpr(1, address) }
        stepper.step(loadState)
        for (index in 0 until 13) {
            assertEquals(0x40 + index, vectors.readByte(2, 2 + index))
        }

        // SQV writes those same 13 bytes back and leaves the following block untouched.
        for (index in 0 until 13) vectors.writeByte(2, 2 + index, 0x70 + index)
        put(memory, 4, (0x3A shl 26) or (1 shl 21) or (2 shl 16) or (2 shl 7))
        val storeState = N64HleRspCpuState().also {
            it.writeGpr(1, address)
            it.programCounter = 4
        }
        stepper.step(storeState)
        for (index in 0 until 13) {
            assertEquals(0x70 + index, memory.read8(0xA4000000L + address + index))
        }
        assertEquals(0x4D, memory.read8(0xA4000000L + address + 13))
    }


    @Test
    fun lqvWrapsVectorByteIndexAtRegisterBoundary() {
        val memory = N64HleGuestAddressSpace()
        val vectors = N64HleRspVectorState()
        for (index in 0 until 13) memory.write8(0xA4000023L + index, 0x20 + index)
        put(memory, 0, (0x32 shl 26) or (1 shl 21) or (3 shl 16) or (10 shl 7))
        val state = N64HleRspCpuState().also { it.writeGpr(1, 0x23) }
        N64HleRspInstructionStepper(memory, vectors).step(state)

        for (index in 0 until 13) {
            assertEquals(0x20 + index, vectors.readByte(3, (10 + index) and 15))
        }
    }

    @Test
    fun decodesRspVectorAccumulatorSlicesWithVSAW() {
        val memory = N64HleGuestAddressSpace()
        val vectors = N64HleRspVectorState()
        for (lane in 0 until 8) {
            vectors.writeAccumulatorLane(lane, 0x1234_5678_9ABCL + lane)
        }
        val stepper = N64HleRspInstructionStepper(memory, vectors)
        fun putVsaw(pc: Int, destination: Int, slice: Int) {
            put(memory, pc, (0x12 shl 26) or ((16 + slice) shl 21) or
                (destination shl 6) or 0x1D)
        }
        putVsaw(0, 3, 8) // VSAW ACC_HI
        putVsaw(4, 4, 9) // VSAW ACC_MID
        putVsaw(8, 5, 10) // VSAW ACC_LO
        val state = N64HleRspCpuState()
        repeat(3) { stepper.step(state) }
        for (lane in 0 until 8) {
            val acc = 0x1234_5678_9ABCL + lane
            assertEquals(((acc ushr 32) and 0xFFFF).toInt(), vectors.readElement(3, lane))
            assertEquals(((acc ushr 16) and 0xFFFF).toInt(), vectors.readElement(4, lane))
            assertEquals((acc and 0xFFFF).toInt(), vectors.readElement(5, lane))
        }
    }

    @Test
    fun decodesRspVectorMergeUsingVccLowAndPreservesFlags() {
        val memory = N64HleGuestAddressSpace()
        val vectors = N64HleRspVectorState()
        for (lane in 0 until 8) {
            vectors.writeElement(1, lane, 0x1100 + lane)
            vectors.writeElement(2, lane, 0x2200 + lane)
        }
        vectors.setFlags(0xA55A, 0b0101_0101, 0xA5)
        put(memory, 0, (0x12 shl 26) or (16 shl 21) or (2 shl 16) or
            (1 shl 11) or (3 shl 6) or 0x27) // VMRG v3,v1,v2[e=0]
        N64HleRspInstructionStepper(memory, vectors).step(N64HleRspCpuState())
        for (lane in 0 until 8) {
            val expected = if ((0b0101_0101 and (1 shl lane)) != 0) 0x1100 + lane else 0x2200
            assertEquals(expected, vectors.readElement(3, lane))
        }
        assertEquals(0xA55A, vectors.vco)
        assertEquals(0b0101_0101, vectors.vcc)
        assertEquals(0xA5, vectors.vce)
    }

    @Test
    fun cop2TransfersMoveVectorElementsAndControlFlags() {
        val memory = N64HleGuestAddressSpace()
        val vectors = N64HleRspVectorState()
        val stepper = N64HleRspInstructionStepper(memory, vectors)
        fun cop2(pc: Int, operation: Int, rt: Int, rd: Int, element: Int) {
            put(memory, pc, (0x12 shl 26) or (operation shl 21) or (rt shl 16) or
                (rd shl 11) or (element shl 7))
        }
        vectors.writeElement(3, 2, 0x8123)
        cop2(0, 0x00, 1, 3, 4) // MFC2 r1,v3[e=4]
        val mfc = N64HleRspCpuState()
        stepper.step(mfc)
        assertEquals(0xFFFF8123.toInt(), mfc.readGpr(1))

        val mtc = N64HleRspCpuState().also { it.writeGpr(2, 0x1234) }
        cop2(4, 0x04, 2, 4, 2) // MTC2 r2,v4[e=2]
        mtc.programCounter = 4
        stepper.step(mtc)
        assertEquals(0x1234, vectors.readElement(4, 1))

        val flags = N64HleRspCpuState().also { it.writeGpr(5, 0xA55A) }
        cop2(8, 0x06, 5, 0, 0) // CTC2 r5,VCO
        flags.programCounter = 8
        stepper.step(flags)
        cop2(12, 0x02, 6, 0, 0) // CFC2 r6,VCO
        flags.programCounter = 12
        stepper.step(flags)
        assertEquals(0xA55A, vectors.vco)
        assertEquals(0xFFFFA55A.toInt(), flags.readGpr(6))
    }
}
