package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class N64HleR4300MemoryExecutorTest {
    private val memory = N64HleGuestAddressSpace()
    private val executor = N64HleR4300MemoryExecutor(memory)


    private fun exceptionReadyState(pc: Long, gprs: Map<Int, Long>): N64HleR4300CpuState =
        N64HleR4300CpuState(
            pc,
            gprs,
            cop0 = N64HleCop0EntryState(
                mapOf(8 to 0L, 12 to 0L, 13 to 0L, 14 to 0L),
                "explicit memory stepper exception test state"
            )
        )

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
    @Test fun stackStoreCanWriteToInitialSpImemAddress() {
        val state = N64HleR4300CpuState(
            0x80001000L,
            mapOf(1 to 0xFFFFFFFFA4001FF0UL.toLong(), 2 to 0x12345678L)
        )
        executor.execute(N64HleR4300InstructionDecoder().decode(i(0x2B, 1, 2, -4)), state)
        assertEquals(0x12345678L, memory.read32(0xA4001FEC))
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

    @Test fun stepperFetchesBigEndianInstructionsFromGuestRdram() {
        val sharedMemory = N64HleGuestAddressSpace()
        val stepper = N64HleR4300InstructionStepper(memorySpace = sharedMemory)
        val state = N64HleR4300CpuState(0x80001000L, mapOf(1 to 9L))
        sharedMemory.write32(0x80001000L, i(0x09, 1, 2, 7).toLong() and 0xFFFF_FFFFL)
        stepper.stepFromMemory(state)
        assertEquals(16L, state.readGpr(2))
        assertEquals(0x80001004L, state.programCounter)
    }

    @Test fun stepperFetchesBranchDelaySlotFromMemory() {
        val sharedMemory = N64HleGuestAddressSpace()
        val stepper = N64HleR4300InstructionStepper(memorySpace = sharedMemory)
        val state = N64HleR4300CpuState(0x80001000L, mapOf(1 to 4L))
        sharedMemory.write32(0x80001000L, ((0x02 shl 26) or 0x800).toLong())
        sharedMemory.write32(0x80001004L, i(0x09, 1, 2, 3).toLong())
        stepper.stepFromMemory(state)
        assertEquals(0x80001004L, state.programCounter)
        stepper.stepFromMemory(state)
        assertEquals(7L, state.readGpr(2))
        assertEquals(0x80002000L, state.programCounter)
    }

    @Test fun bigEndianLwlMergesMemoryIntoMostSignificantWordBytes() {
        memory.write32(0x80000100L, 0xAABBCCDDL)
        val state = N64HleR4300CpuState(
            0x80001000L,
            mapOf(1 to 0x80000101L, 2 to 0x11223344L)
        )
        executor.execute(N64HleR4300InstructionDecoder().decode(i(0x22, 1, 2, 0)), state)
        assertEquals(0xFFFFFFFFBBCCDD44UL.toLong(), state.readGpr(2))
    }

    @Test fun bigEndianLwrMergesMemoryIntoLeastSignificantWordBytes() {
        memory.write32(0x80000100L, 0xAABBCCDDL)
        val state = N64HleR4300CpuState(
            0x80001000L,
            mapOf(1 to 0x80000101L, 2 to 0x11223344L)
        )
        executor.execute(N64HleR4300InstructionDecoder().decode(i(0x26, 1, 2, 0)), state)
        assertEquals(0x1122AABBL, state.readGpr(2))
    }

    @Test fun bigEndianSwlAndSwrPreserveUnwrittenMemoryBytes() {
        memory.write32(0x80000100L, 0x11223344L)
        val state = N64HleR4300CpuState(
            0x80001000L,
            mapOf(1 to 0x80000101L, 2 to 0xAABBCCDDL)
        )
        executor.execute(N64HleR4300InstructionDecoder().decode(i(0x2A, 1, 2, 0)), state)
        assertEquals(0x11AABBCCL, memory.read32(0x80000100L))

        memory.write32(0x80000100L, 0x11223344L)
        executor.execute(N64HleR4300InstructionDecoder().decode(i(0x2E, 1, 2, 0)), state)
        assertEquals(0xCCDD3344L, memory.read32(0x80000100L))
    }

    @Test fun partialWordLoadRequiresPreviouslyKnownDestinationBytes() {
        memory.write32(0x80000100L, 0xAABBCCDDL)
        val state = N64HleR4300CpuState(0x80001000L, mapOf(1 to 0x80000101L))
        assertThrows(IllegalStateException::class.java) {
            executor.execute(N64HleR4300InstructionDecoder().decode(i(0x22, 1, 2, 0)), state)
        }
        assertEquals(false, state.isGprKnown(2))
    }

    @Test fun wordMergeOperationsCoverEveryByteOffset() {
        val expectedLwl = longArrayOf(0xFFFFFFFFAABBCCDDUL.toLong(), 0xFFFFFFFFBBCCDD44UL.toLong(), 0xFFFFFFFFCCDD3344UL.toLong(), 0xFFFFFFFFDD223344UL.toLong())
        val expectedLwr = longArrayOf(0x112233AAL, 0x1122AABBL, 0x11AABBCCL, 0xFFFFFFFFAABBCCDDUL.toLong())
        val expectedSwl = longArrayOf(0xAABBCCDDL, 0x11AABBCCL, 0x1122AABBL, 0x112233AAL)
        val expectedSwr = longArrayOf(0xDD223344L, 0xCCDD3344L, 0xBBCCDD44L, 0xAABBCCDDL)

        for (offset in 0..3) {
            memory.write32(0x80000100L, 0xAABBCCDDL)
            val loadLeft = N64HleR4300CpuState(
                0x80001000L,
                mapOf(1 to 0x80000100L + offset, 2 to 0x11223344L)
            )
            executor.execute(N64HleR4300InstructionDecoder().decode(i(0x22, 1, 2, 0)), loadLeft)
            assertEquals(expectedLwl[offset], loadLeft.readGpr(2))

            memory.write32(0x80000100L, 0xAABBCCDDL)
            val loadRight = N64HleR4300CpuState(
                0x80001000L,
                mapOf(1 to 0x80000100L + offset, 2 to 0x11223344L)
            )
            executor.execute(N64HleR4300InstructionDecoder().decode(i(0x26, 1, 2, 0)), loadRight)
            assertEquals(expectedLwr[offset], loadRight.readGpr(2))

            memory.write32(0x80000100L, 0x11223344L)
            val storeLeft = N64HleR4300CpuState(
                0x80001000L,
                mapOf(1 to 0x80000100L + offset, 2 to 0xAABBCCDDL)
            )
            executor.execute(N64HleR4300InstructionDecoder().decode(i(0x2A, 1, 2, 0)), storeLeft)
            assertEquals(expectedSwl[offset], memory.read32(0x80000100L))

            memory.write32(0x80000100L, 0x11223344L)
            val storeRight = N64HleR4300CpuState(
                0x80001000L,
                mapOf(1 to 0x80000100L + offset, 2 to 0xAABBCCDDL)
            )
            executor.execute(N64HleR4300InstructionDecoder().decode(i(0x2E, 1, 2, 0)), storeRight)
            assertEquals(expectedSwr[offset], memory.read32(0x80000100L))
        }
    }

    @Test fun doublewordMergeOperationsCoverEveryByteOffset() {
        val memoryWord = 0x1122334455667788UL.toLong()
        val oldRegister = 0xAABBCCDDEEFF0022UL.toLong()
        val oldMemory = oldRegister
        val expectedLdl = longArrayOf(
            memoryWord,
            0x2233445566778822UL.toLong(),
            0x3344556677880022UL.toLong(),
            0x4455667788FF0022UL.toLong(),
            0x55667788EEFF0022UL.toLong(),
            0x667788DDEEFF0022UL.toLong(),
            0x7788CCDDEEFF0022UL.toLong(),
            0x88BBCCDDEEFF0022UL.toLong()
        )
        val expectedLdr = longArrayOf(
            0xAABBCCDDEEFF0011UL.toLong(),
            0xAABBCCDDEEFF1122UL.toLong(),
            0xAABBCCDDEE112233UL.toLong(),
            0xAABBCCDD11223344UL.toLong(),
            0xAABBCC1122334455UL.toLong(),
            0xAABB112233445566UL.toLong(),
            0xAA11223344556677UL.toLong(),
            memoryWord
        )
        val expectedSdl = longArrayOf(
            memoryWord,
            0xAA11223344556677UL.toLong(),
            0xAABB112233445566UL.toLong(),
            0xAABBCC1122334455UL.toLong(),
            0xAABBCCDD11223344UL.toLong(),
            0xAABBCCDDEE112233UL.toLong(),
            0xAABBCCDDEEFF1122UL.toLong(),
            0xAABBCCDDEEFF0011UL.toLong()
        )
        val expectedSdr = longArrayOf(
            0x88BBCCDDEEFF0022UL.toLong(),
            0x7788CCDDEEFF0022UL.toLong(),
            0x667788DDEEFF0022UL.toLong(),
            0x55667788EEFF0022UL.toLong(),
            0x4455667788FF0022UL.toLong(),
            0x3344556677880022UL.toLong(),
            0x2233445566778822UL.toLong(),
            memoryWord
        )

        for (offset in 0..7) {
            memory.write64(0x80000100L, memoryWord)
            val loadLeft = N64HleR4300CpuState(
                0x80001000L,
                mapOf(1 to 0x80000100L + offset, 2 to oldRegister)
            )
            executor.execute(N64HleR4300InstructionDecoder().decode(i(0x1A, 1, 2, 0)), loadLeft)
            assertEquals(expectedLdl[offset], loadLeft.readGpr(2))

            memory.write64(0x80000100L, memoryWord)
            val loadRight = N64HleR4300CpuState(
                0x80001000L,
                mapOf(1 to 0x80000100L + offset, 2 to oldRegister)
            )
            executor.execute(N64HleR4300InstructionDecoder().decode(i(0x1B, 1, 2, 0)), loadRight)
            assertEquals(expectedLdr[offset], loadRight.readGpr(2))

            memory.write64(0x80000100L, oldMemory)
            val storeLeft = N64HleR4300CpuState(
                0x80001000L,
                mapOf(1 to 0x80000100L + offset, 2 to memoryWord)
            )
            executor.execute(N64HleR4300InstructionDecoder().decode(i(0x2C, 1, 2, 0)), storeLeft)
            assertEquals(expectedSdl[offset], memory.read64(0x80000100L))

            memory.write64(0x80000100L, oldMemory)
            val storeRight = N64HleR4300CpuState(
                0x80001000L,
                mapOf(1 to 0x80000100L + offset, 2 to memoryWord)
            )
            executor.execute(N64HleR4300InstructionDecoder().decode(i(0x2D, 1, 2, 0)), storeRight)
            assertEquals(expectedSdr[offset], memory.read64(0x80000100L))
        }
    }


    @Test fun stepperRejectsMisalignedWordLoadWithoutChangingPcOrDestination() {
        val sharedMemory = N64HleGuestAddressSpace()
        val stepper = N64HleR4300InstructionStepper(memorySpace = sharedMemory)
        val state = exceptionReadyState(
            0x80001000L,
            mapOf(1 to 0x80000101L)
        )

        assertThrows(N64HleR4300ArchitecturalException::class.java) {
            stepper.step(i(0x23, 1, 2, 0), state) // LW from unaligned address
        }
        assertEquals(0x80001000L, state.programCounter)
        assertEquals(false, state.isGprKnown(2))
    }


    @Test fun stepperRejectsMisalignedHalfwordLoadAndStoreWithoutSideEffects() {
        val loadMemory = N64HleGuestAddressSpace()
        loadMemory.write16(0x80000100L, 0xAABB)
        val loadStepper = N64HleR4300InstructionStepper(memorySpace = loadMemory)
        val loadState = exceptionReadyState(
            0x80001000L,
            mapOf(1 to 0x80000101L)
        )

        assertThrows(N64HleR4300ArchitecturalException::class.java) {
            loadStepper.step(i(0x21, 1, 2, 0), loadState) // LH from odd address
        }
        assertEquals(0x80001000L, loadState.programCounter)
        assertEquals(false, loadState.isGprKnown(2))

        val storeMemory = N64HleGuestAddressSpace()
        storeMemory.write16(0x80000100L, 0x1234)
        val storeStepper = N64HleR4300InstructionStepper(memorySpace = storeMemory)
        val storeState = exceptionReadyState(
            0x80002000L,
            mapOf(1 to 0x80000101L, 2 to 0xABCDL)
        )

        assertThrows(N64HleR4300ArchitecturalException::class.java) {
            storeStepper.step(i(0x29, 1, 2, 0), storeState) // SH to odd address
        }
        assertEquals(0x80002000L, storeState.programCounter)
        assertEquals(0x1234, storeMemory.read16(0x80000100L))
    }



    @Test fun stepperRejectsMisalignedWordStoreWithoutChangingPcOrMemory() {
        val sharedMemory = N64HleGuestAddressSpace()
        sharedMemory.write32(0x80000100L, 0xAABBCCDDL)
        val stepper = N64HleR4300InstructionStepper(memorySpace = sharedMemory)
        val state = exceptionReadyState(
            0x80002000L,
            mapOf(1 to 0x80000101L, 2 to 0x12345678L)
        )

        assertThrows(N64HleR4300ArchitecturalException::class.java) {
            stepper.step(i(0x2B, 1, 2, 0), state) // SW to unaligned address
        }
        assertEquals(0x80002000L, state.programCounter)
        assertEquals(0xAABBCCDDL, sharedMemory.read32(0x80000100L))
    }


    @Test fun stepperRejectsMisalignedDoublewordLoadAndStoreWithoutSideEffects() {
        val loadMemory = N64HleGuestAddressSpace()
        loadMemory.write64(0x80000100L, 0x1122334455667788L)
        val loadStepper = N64HleR4300InstructionStepper(memorySpace = loadMemory)
        val loadState = exceptionReadyState(
            0x80003000L,
            mapOf(1 to 0x80000104L)
        )

        assertThrows(N64HleR4300ArchitecturalException::class.java) {
            loadStepper.step(i(0x37, 1, 2, 0), loadState) // LD from address not aligned to 8
        }
        assertEquals(0x80003000L, loadState.programCounter)
        assertEquals(false, loadState.isGprKnown(2))
        assertEquals(0x1122334455667788L, loadMemory.read64(0x80000100L))

        val storeMemory = N64HleGuestAddressSpace()
        storeMemory.write64(0x80000100L, 0xAABBCCDDEEFF0011UL.toLong())
        val storeStepper = N64HleR4300InstructionStepper(memorySpace = storeMemory)
        val storeState = exceptionReadyState(
            0x80004000L,
            mapOf(1 to 0x80000104L, 2 to 0x123456789ABCDEF0L)
        )

        assertThrows(N64HleR4300ArchitecturalException::class.java) {
            storeStepper.step(i(0x3F, 1, 2, 0), storeState) // SD to address not aligned to 8
        }
        assertEquals(0x80004000L, storeState.programCounter)
        assertEquals(0xAABBCCDDEEFF0011UL.toLong(), storeMemory.read64(0x80000100L))
    }


    @Test fun stepperRejectsUnmappedLoadAndStoreWithoutSideEffects() {
        val loadMemory = N64HleGuestAddressSpace()
        loadMemory.write32(0x80000000L, 0xAABBCCDDL)
        val loadStepper = N64HleR4300InstructionStepper(memorySpace = loadMemory)
        val loadState = exceptionReadyState(
            0x80005000L,
            mapOf(1 to 0x80800000L)
        )

        assertThrows(N64HleR4300ArchitecturalException::class.java) {
            loadStepper.step(i(0x23, 1, 2, 0), loadState) // LW beyond mapped RDRAM
        }
        assertEquals(0x80005000L, loadState.programCounter)
        assertEquals(false, loadState.isGprKnown(2))
        assertEquals(0xAABBCCDDL, loadMemory.read32(0x80000000L))

        val storeMemory = N64HleGuestAddressSpace()
        storeMemory.write32(0x80000000L, 0x11223344L)
        val storeStepper = N64HleR4300InstructionStepper(memorySpace = storeMemory)
        val storeState = exceptionReadyState(
            0x80006000L,
            mapOf(1 to 0x80800000L, 2 to 0x55667788L)
        )

        assertThrows(N64HleR4300ArchitecturalException::class.java) {
            storeStepper.step(i(0x2B, 1, 2, 0), storeState) // SW beyond mapped RDRAM
        }
        assertEquals(0x80006000L, storeState.programCounter)
        assertEquals(0x11223344L, storeMemory.read32(0x80000000L))
    }


    @Test fun memoryFaultRetainsLoadDirectionAndOriginalAlignmentFault() {
        val state = exceptionReadyState(
            0x80001000L,
            mapOf(1 to 0x80000101L)
        )

        val fault = assertThrows(N64HleR4300MemoryAccessFault::class.java) {
            executor.execute(N64HleR4300InstructionDecoder().decode(i(0x23, 1, 2, 0)), state)
        }

        assertEquals(N64HleR4300MemoryAccessKind.LOAD, fault.accessKind)
        assertEquals(0x80000101L, fault.memoryFault.virtualAddress)
        assertTrue(fault.memoryFault is N64HleGuestAddressAlignmentFault)
        assertEquals(false, state.isGprKnown(2))
    }

    @Test fun memoryFaultRetainsStoreDirectionAndOriginalMappingFault() {
        val state = exceptionReadyState(
            0x80001000L,
            mapOf(1 to 0x80800000L, 2 to 0x12345678L)
        )

        val fault = assertThrows(N64HleR4300MemoryAccessFault::class.java) {
            executor.execute(N64HleR4300InstructionDecoder().decode(i(0x2B, 1, 2, 0)), state)
        }

        assertEquals(N64HleR4300MemoryAccessKind.STORE, fault.accessKind)
        assertEquals(0x80800000L, fault.memoryFault.virtualAddress)
        assertTrue(fault.memoryFault is N64HleGuestAddressMappingFault)
    }
}
