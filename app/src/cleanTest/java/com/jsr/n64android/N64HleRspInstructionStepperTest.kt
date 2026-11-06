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

        assertEquals(24, state.readGpr(3))
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

}
