package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class N64HleR4300MemoryExecutorTest {
    private val memory = N64HleGuestAddressSpace()
    private val executor = N64HleR4300MemoryExecutor(memory)

    private fun i(op: Int, rs: Int, rt: Int, imm: Int): Int =
        (op shl 26) or (rs shl 21) or (rt shl 16) or (imm and 0xFFFF)

    @Test fun signedAndUnsignedByteLoadsDiffer() {
        memory.write8(0x80000100L, 0x80)
        val state = N64HleR4300CpuState(0x80001000L, mapOf(1 to 0x80000100L))
        executor.execute(N64HleR4300InstructionDecoder().decode(i(0x20, 1, 2, 0)), state)
        executor.execute(N64HleR4300InstructionDecoder().decode(i(0x24, 1, 3, 0)), state)
        assertEquals(-128L, state.readGpr(2))
        assertEquals(128L, state.readGpr(3))
    }

    @Test fun signedAndUnsignedHalfwordLoadsDiffer() {
        memory.write16(0x80000100L, 0x8001)
        val state = N64HleR4300CpuState(0x80001000L, mapOf(1 to 0x80000100L))
        executor.execute(N64HleR4300InstructionDecoder().decode(i(0x21, 1, 2, 0)), state)
        executor.execute(N64HleR4300InstructionDecoder().decode(i(0x25, 1, 3, 0)), state)
        assertEquals(-32767L, state.readGpr(2))
        assertEquals(32769L, state.readGpr(3))
    }

    @Test fun wordLoadsSignExtendOrZeroExtend() {
        memory.write32(0x80000100L, 0x80000001L)
        val state = N64HleR4300CpuState(0x80001000L, mapOf(1 to 0x80000100L))
        executor.execute(N64HleR4300InstructionDecoder().decode(i(0x23, 1, 2, 0)), state)
        executor.execute(N64HleR4300InstructionDecoder().decode(i(0x27, 1, 3, 0)), state)
        assertEquals(0xFFFF_FFFF_8000_0001UL.toLong(), state.readGpr(2))
        assertEquals(0x8000_0001L, state.readGpr(3))
    }

    @Test fun doublewordLoadAndStorePreserveAllBits() {
        val state = N64HleR4300CpuState(
            0x80001000L,
            mapOf(1 to 0x80000100L, 2 to 0xFEDC_BA98_7654_3210UL.toLong())
        )
        executor.execute(N64HleR4300InstructionDecoder().decode(i(0x3F, 1, 2, 0)), state)
        executor.execute(N64HleR4300InstructionDecoder().decode(i(0x37, 1, 3, 0)), state)
        assertEquals(0xFEDC_BA98_7654_3210UL.toLong(), state.readGpr(3))
    }

    @Test fun signedOffsetIsAppliedToBaseAddress() {
        memory.write8(0x800000FFL, 0x5A)
        val state = N64HleR4300CpuState(0x80001000L, mapOf(1 to 0x80000100L))
        executor.execute(N64HleR4300InstructionDecoder().decode(i(0x24, 1, 2, -1)), state)
        assertEquals(0x5AL, state.readGpr(2))
    }

    @Test fun failedLoadDoesNotModifyDestinationRegister() {
        val state = N64HleR4300CpuState(0x80001000L, mapOf(1 to 0x80800000L))
        assertThrows(IllegalArgumentException::class.java) {
            executor.execute(N64HleR4300InstructionDecoder().decode(i(0x23, 1, 2, 0)), state)
        }
        assertEquals(false, state.isGprKnown(2))
    }

    @Test fun failedStoreDoesNotChangeMemory() {
        val state = N64HleR4300CpuState(
            0x80001000L,
            mapOf(1 to 0x80800000L, 2 to 0x12345678L)
        )
        memory.write32(0x80000000L, 0xAABBCCDDL)
        assertThrows(IllegalArgumentException::class.java) {
            executor.execute(N64HleR4300InstructionDecoder().decode(i(0x2B, 1, 2, 0)), state)
        }
        assertEquals(0xAABBCCDDL, memory.read32(0x80000000L))
    }
    @Test fun stepperExecutesLoadAndStoreAndAdvancesPc() {
        val sharedMemory = N64HleGuestAddressSpace()
        val stepper = N64HleR4300InstructionStepper(memorySpace = sharedMemory)
        val state = N64HleR4300CpuState(
            0x80001000L,
            mapOf(1 to 0x80000100L, 2 to 0x12345678L)
        )
        stepper.step(i(0x2B, 1, 2, 0), state) // SW
        assertEquals(0x80001004L, state.programCounter)
        stepper.step(i(0x23, 1, 3, 0), state) // LW
        assertEquals(0x12345678L, state.readGpr(3))
        assertEquals(0x80001008L, state.programCounter)
    }

    @Test fun memoryInstructionExecutesInsideDelaySlot() {
        val sharedMemory = N64HleGuestAddressSpace()
        val stepper = N64HleR4300InstructionStepper(memorySpace = sharedMemory)
        val state = N64HleR4300CpuState(
            0x80001000L,
            mapOf(1 to 0x80000100L, 2 to 0xCAFEBABEL)
        )
        stepper.step((0x02 shl 26) or 0x800, state) // J
        stepper.step(i(0x2B, 1, 2, 0), state) // SW in delay slot
        assertEquals(0xCAFEBABEL, sharedMemory.read32(0x80000100L))
        assertEquals(0x80002000L, state.programCounter)
        assertEquals(false, stepper.hasPendingDelaySlot())
    }

}
