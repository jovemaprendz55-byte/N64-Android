package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class N64HleR4300InstructionStepperTest {
    private val stepper = N64HleR4300InstructionStepper()

    private fun r(rs: Int, rt: Int, rd: Int, funct: Int): Int =
        (rs shl 21) or (rt shl 16) or (rd shl 11) or funct

    @Test fun ordinarySpecialInstructionExecutesAndAdvancesPcByFour() {
        val state = N64HleR4300CpuState(0x80001000L, mapOf(1 to 20L, 2 to 22L))
        stepper.step(r(1, 2, 3, 0x21), state) // ADDU
        assertEquals(42L, state.readGpr(3))
        assertEquals(0x80001004L, state.programCounter)
        assertFalse(stepper.hasPendingDelaySlot())
    }

    @Test fun jalThenSpecialDelaySlotThenJumpTarget() {
        val state = N64HleR4300CpuState(0x80001000L)
        stepper.step((0x03 shl 26) or 0x800, state) // JAL 0x80002000
        assertEquals(0x80001004L, state.programCounter)
        assertEquals(0x80001008L, state.readGpr(31))
        assertTrue(stepper.hasPendingDelaySlot())

        stepper.step(r(0, 0, 2, 0x00), state) // SLL $2,$0,0 (NOP)
        assertEquals(0x80002000L, state.programCounter)
        assertFalse(stepper.hasPendingDelaySlot())
    }

    @Test fun notTakenBranchStillExecutesOneDelaySlot() {
        val state = N64HleR4300CpuState(0x80001000L, mapOf(1 to 5L, 2 to 5L))
        stepper.step((0x05 shl 26) or (1 shl 21) or (2 shl 16) or 4, state) // BNE not taken
        assertEquals(0x80001004L, state.programCounter)
        stepper.step(r(1, 2, 3, 0x21), state) // ADDU in delay slot
        assertEquals(0x80001008L, state.programCounter)
        assertEquals(10L, state.readGpr(3))
    }

    @Test fun unknownSpecialFunctionEntersReservedInstructionException() {
        val state = exceptionState(0x80001000L, emptyMap())
        val signal = assertThrows(N64HleR4300ArchitecturalException::class.java) {
            stepper.step(r(1, 2, 3, 0x01), state)
        }

        assertEquals(N64HleR4300ExceptionCode.RESERVED_INSTRUCTION, signal.context.code)
        assertEquals(0x80001000L, signal.context.exceptionProgramCounter)
        assertEquals(0x28L, state.readCop0(13))
        assertEquals(0x2L, state.readCop0(12))
        assertEquals(N64HleR4300ExceptionVectorResolver.GENERAL_EXCEPTION_VECTOR, state.programCounter)
    }

    @Test fun unknownSpecialFunctionInDelaySlotSetsBdAndBranchEpc() {
        val state = exceptionState(0x80001000L, emptyMap())
        stepper.step((0x02 shl 26) or 0x800, state)

        val signal = assertThrows(N64HleR4300ArchitecturalException::class.java) {
            stepper.step(r(1, 2, 3, 0x01), state)
        }

        assertEquals(N64HleR4300ExceptionCode.RESERVED_INSTRUCTION, signal.context.code)
        assertTrue(signal.context.inDelaySlot)
        assertEquals(0x80001000L, signal.context.exceptionProgramCounter)
        assertEquals(0xFFFFFFFF80000028UL.toLong(), state.readCop0(13))
        assertFalse(stepper.hasPendingDelaySlot())
        assertEquals(N64HleR4300ExceptionVectorResolver.GENERAL_EXCEPTION_VECTOR, state.programCounter)
    }

    @Test fun unsupportedInstructionDoesNotAdvancePc() {
        val state = N64HleR4300CpuState(0x80001000L)
        assertThrows(IllegalArgumentException::class.java) {
            stepper.step(0xC4010000.toInt(), state) // LWC1 is not implemented yet
        }
        assertEquals(0x80001000L, state.programCounter)
    }

    @Test fun controlTransferInDelaySlotIsRejectedWithoutLosingPendingSlot() {
        val state = N64HleR4300CpuState(0x80001000L, mapOf(1 to 0x80003000L))
        stepper.step((0x02 shl 26) or 0x800, state) // J
        assertThrows(IllegalStateException::class.java) {
            stepper.step((1 shl 21) or 0x08, state) // JR in delay slot
        }
        assertEquals(0x80001004L, state.programCounter)
        assertTrue(stepper.hasPendingDelaySlot())
    }

    @Test fun unsupportedDelaySlotInstructionDoesNotCompleteTransfer() {
        val state = N64HleR4300CpuState(0x80001000L)
        stepper.step((0x02 shl 26) or 0x800, state)
        assertThrows(IllegalArgumentException::class.java) {
            stepper.step(0xC4010000.toInt(), state)
        }
        assertEquals(0x80001004L, state.programCounter)
        assertTrue(stepper.hasPendingDelaySlot())
    }

    @Test fun goldenEyeTakenBneExecutesDelaySlotThenBranchesToNegativeTarget() {
        val state = N64HleR4300CpuState(
            0xFFFFFFFF8000041CUL.toLong(),
            mapOf(9 to 0x0000000000031078L)
        )
        val goldenEyeBne = 0x1520FFFC

        stepper.step(goldenEyeBne, state)
        assertEquals(0xFFFFFFFF80000420UL.toLong(), state.programCounter)
        assertTrue(stepper.hasPendingDelaySlot())

        stepper.step(0x00000000, state) // NOP in the delay slot
        assertEquals(0xFFFFFFFF80000410UL.toLong(), state.programCounter)
        assertFalse(stepper.hasPendingDelaySlot())
    }


    @Test fun bgezalTakenWritesReturnAddressBeforeDelaySlotAndBranches() {
        val state = N64HleR4300CpuState(0x80001000L, mapOf(1 to 0L))
        val bgezal = (0x01 shl 26) or (1 shl 21) or (0x11 shl 16) or 4

        stepper.step(bgezal, state)
        assertEquals(0x80001004L, state.programCounter)
        assertEquals(0x80001008L, state.readGpr(31))
        assertTrue(stepper.hasPendingDelaySlot())

        stepper.step(0, state) // NOP delay slot
        assertEquals(0x80001014L, state.programCounter)
        assertFalse(stepper.hasPendingDelaySlot())
    }

    @Test fun bltzalNotTakenDoesNotWriteReturnAddressAndFallsThrough() {
        val state = N64HleR4300CpuState(0x80001000L, mapOf(1 to 1L))
        val bltzal = (0x01 shl 26) or (1 shl 21) or (0x10 shl 16) or 4

        stepper.step(bltzal, state)
        assertFalse(state.isGprKnown(31))
        assertEquals(0x80001004L, state.programCounter)
        assertTrue(stepper.hasPendingDelaySlot())

        stepper.step(0, state) // NOP delay slot
        assertEquals(0x80001008L, state.programCounter)
        assertFalse(stepper.hasPendingDelaySlot())
        assertFalse(state.isGprKnown(31))
    }

    @Test fun blezAndBgtzUseSignedRegisterValuesAndExecuteDelaySlots() {
        val blezState = N64HleR4300CpuState(0x80001000L, mapOf(1 to Long.MIN_VALUE))
        val blez = (0x06 shl 26) or (1 shl 21) or 2
        stepper.step(blez, blezState)
        stepper.step(0, blezState)
        assertEquals(0x8000100CL, blezState.programCounter)

        val bgtzState = N64HleR4300CpuState(0x80002000L, mapOf(1 to Long.MAX_VALUE))
        val bgtz = (0x07 shl 26) or (1 shl 21) or 3
        stepper.step(bgtz, bgtzState)
        stepper.step(0, bgtzState)
        assertEquals(0x80002010L, bgtzState.programCounter)
    }


    @Test fun jrUsesRegisterTargetAfterExecutingDelaySlot() {
        val state = N64HleR4300CpuState(0x80001000L, mapOf(5 to 0x80003400L))
        val jr = (5 shl 21) or 0x08

        stepper.step(jr, state)
        assertEquals(0x80001004L, state.programCounter)
        assertTrue(stepper.hasPendingDelaySlot())

        stepper.step(0, state)
        assertEquals(0x80003400L, state.programCounter)
        assertFalse(stepper.hasPendingDelaySlot())
    }

    @Test fun jalrWritesSelectedLinkRegisterAndTransfersAfterDelaySlot() {
        val state = N64HleR4300CpuState(0xFFFFFFFF80001000UL.toLong(), mapOf(5 to 0xFFFFFFFF80004000UL.toLong()))
        val jalr = (5 shl 21) or (7 shl 11) or 0x09

        stepper.step(jalr, state)
        assertEquals(0xFFFFFFFF80001004UL.toLong(), state.programCounter)
        assertEquals(0xFFFFFFFF80001008UL.toLong(), state.readGpr(7))
        assertTrue(stepper.hasPendingDelaySlot())

        stepper.step(0, state)
        assertEquals(0xFFFFFFFF80004000UL.toLong(), state.programCounter)
        assertFalse(stepper.hasPendingDelaySlot())
    }

    @Test fun jrRejectsUnalignedTargetWithoutChangingCpuState() {
        val state = N64HleR4300CpuState(0x80001000L, mapOf(5 to 0x80003402L))
        val jr = (5 shl 21) or 0x08

        assertThrows(IllegalArgumentException::class.java) {
            stepper.step(jr, state)
        }
        assertEquals(0x80001000L, state.programCounter)
        assertFalse(stepper.hasPendingDelaySlot())
    }


    @Test fun jUsesRegionFromPcPlusFourAndCompletesAfterDelaySlot() {
        val state = N64HleR4300CpuState(0x0FFFFFFCL)
        val j = (0x02 shl 26) or 0x00001234

        stepper.step(j, state)
        assertEquals(0x10000000L, state.programCounter)
        assertTrue(stepper.hasPendingDelaySlot())

        stepper.step(0, state)
        assertEquals(0x100048D0L, state.programCounter)
        assertFalse(stepper.hasPendingDelaySlot())
    }

    @Test fun jalWritesRaBeforeDelaySlotAndPreservesSignExtendedTarget() {
        val state = N64HleR4300CpuState(0xFFFFFFFF80001000UL.toLong())
        val jal = (0x03 shl 26) or 0x00000800

        stepper.step(jal, state)
        assertEquals(0xFFFFFFFF80001004UL.toLong(), state.programCounter)
        assertEquals(0xFFFFFFFF80001008UL.toLong(), state.readGpr(31))
        assertTrue(stepper.hasPendingDelaySlot())

        stepper.step(0, state)
        assertEquals(0xFFFFFFFF80002000UL.toLong(), state.programCounter)
        assertFalse(stepper.hasPendingDelaySlot())
    }

    private fun i(op: Int, rs: Int, rt: Int, imm: Int): Int =
        (op shl 26) or (rs shl 21) or (rt shl 16) or (imm and 0xFFFF)

    private fun exceptionState(pc: Long, gprs: Map<Int, Long>): N64HleR4300CpuState =
        N64HleR4300CpuState(
            pc,
            gprs,
            cop0 = N64HleCop0EntryState(
                mapOf(8 to 0L, 12 to 0L, 13 to 0L, 14 to 0L),
                "explicit stepper memory-exception test state"
            )
        )

    @Test fun alignedLoadFaultEntersAdelInCop0WithoutAdvancingPc() {
        val memory = N64HleGuestAddressSpace()
        val stepperWithMemory = N64HleR4300InstructionStepper(memorySpace = memory)
        val state = exceptionState(0x80001000L, mapOf(1 to 0x80000101L))

        val signal = assertThrows(N64HleR4300ArchitecturalException::class.java) {
            stepperWithMemory.step(i(0x23, 1, 2, 0), state)
        }

        assertEquals(N64HleR4300ExceptionCode.ADDRESS_LOAD, signal.context.code)
        assertEquals(0x80001000L, signal.context.exceptionProgramCounter)
        assertEquals(0xFFFFFFFF80000101UL.toLong(), state.readCop0(8))
        assertEquals(0x10L, state.readCop0(13))
        assertEquals(0x2L, state.readCop0(12))
        assertEquals(N64HleR4300ExceptionVectorResolver.GENERAL_EXCEPTION_VECTOR, signal.vectorAddress)
        assertEquals(N64HleR4300ExceptionVectorResolver.GENERAL_EXCEPTION_VECTOR, state.programCounter)
        assertFalse(stepperWithMemory.hasPendingDelaySlot())
    }

    @Test fun misalignedStoreFaultEntersAdesInCop0() {
        val memory = N64HleGuestAddressSpace()
        val stepperWithMemory = N64HleR4300InstructionStepper(memorySpace = memory)
        val state = exceptionState(0x80001100L, mapOf(1 to 0x80000102L, 2 to 0x12345678L))

        val signal = assertThrows(N64HleR4300ArchitecturalException::class.java) {
            stepperWithMemory.step(i(0x2B, 1, 2, 0), state)
        }

        assertEquals(N64HleR4300ExceptionCode.ADDRESS_STORE, signal.context.code)
        assertEquals(0x14L, state.readCop0(13))
        assertEquals(0xFFFFFFFF80000102UL.toLong(), state.readCop0(8))
        assertEquals(N64HleR4300ExceptionVectorResolver.GENERAL_EXCEPTION_VECTOR, signal.vectorAddress)
        assertEquals(N64HleR4300ExceptionVectorResolver.GENERAL_EXCEPTION_VECTOR, state.programCounter)
    }

    @Test fun delaySlotLoadFaultSetsBdUsesBranchEpcAndCancelsPendingSlot() {
        val memory = N64HleGuestAddressSpace()
        val stepperWithMemory = N64HleR4300InstructionStepper(memorySpace = memory)
        val state = exceptionState(0x80001000L, mapOf(1 to 0x80000101L))
        stepperWithMemory.step((0x02 shl 26) or 0x800, state)

        val signal = assertThrows(N64HleR4300ArchitecturalException::class.java) {
            stepperWithMemory.step(i(0x23, 1, 2, 0), state)
        }

        assertEquals(N64HleR4300ExceptionCode.ADDRESS_LOAD, signal.context.code)
        assertTrue(signal.context.inDelaySlot)
        assertEquals(0x80001000L, signal.context.exceptionProgramCounter)
        assertEquals(0x80000101L, signal.context.badVirtualAddress)
        assertEquals(0xFFFFFFFF80000010UL.toLong(), state.readCop0(13))
        assertEquals(0x2L, state.readCop0(12))
        assertEquals(N64HleR4300ExceptionVectorResolver.GENERAL_EXCEPTION_VECTOR, signal.vectorAddress)
        assertEquals(N64HleR4300ExceptionVectorResolver.GENERAL_EXCEPTION_VECTOR, state.programCounter)
        assertFalse(stepperWithMemory.hasPendingDelaySlot())
    }

    @Test fun unknownCop0PreflightLeavesPcAndPendingDelaySlotUntouched() {
        val memory = N64HleGuestAddressSpace()
        val stepperWithMemory = N64HleR4300InstructionStepper(memorySpace = memory)
        val state = N64HleR4300CpuState(
            0x80001000L,
            mapOf(1 to 0x80000101L),
            cop0 = N64HleCop0EntryState(mapOf(13 to 0L), "Status intentionally unknown")
        )
        stepperWithMemory.step((0x02 shl 26) or 0x800, state)

        assertThrows(IllegalStateException::class.java) {
            stepperWithMemory.step(i(0x23, 1, 2, 0), state)
        }

        assertEquals(0x80001004L, state.programCounter)
        assertTrue(stepperWithMemory.hasPendingDelaySlot())
        assertEquals(0L, state.readCop0(13))
        assertFalse(state.isCop0Known(12))
    }

    @Test fun unmappedDataLoadEntersDataBusError() {
        val memory = N64HleGuestAddressSpace()
        val stepperWithMemory = N64HleR4300InstructionStepper(memorySpace = memory)
        val state = exceptionState(0x80001200L, mapOf(1 to 0x80800000L))

        val signal = assertThrows(N64HleR4300ArchitecturalException::class.java) {
            stepperWithMemory.step(i(0x23, 1, 2, 0), state)
        }

        assertEquals(N64HleR4300ExceptionCode.DATA_BUS_ERROR, signal.context.code)
        assertEquals(0xFFFFFFFF80800000UL.toLong(), state.readCop0(8))
        assertEquals(0x1CL, state.readCop0(13))
        assertEquals(N64HleR4300ExceptionVectorResolver.GENERAL_EXCEPTION_VECTOR, signal.vectorAddress)
        assertEquals(N64HleR4300ExceptionVectorResolver.GENERAL_EXCEPTION_VECTOR, state.programCounter)
    }


    @Test fun bevSelectsBootExceptionVectorAndRedirectsPc() {
        val memory = N64HleGuestAddressSpace()
        val stepperWithMemory = N64HleR4300InstructionStepper(memorySpace = memory)
        val state = N64HleR4300CpuState(
            0x80001300L,
            mapOf(1 to 0x80000101L),
            cop0 = N64HleCop0EntryState(
                mapOf(
                    8 to 0L,
                    12 to N64HleR4300ExceptionVectorResolver.STATUS_BEV.toLong(),
                    13 to 0L,
                    14 to 0L
                ),
                "BEV exception dispatch test"
            )
        )

        val signal = assertThrows(N64HleR4300ArchitecturalException::class.java) {
            stepperWithMemory.step(i(0x23, 1, 2, 0), state)
        }

        assertEquals(N64HleR4300ExceptionVectorResolver.BOOT_EXCEPTION_VECTOR, signal.vectorAddress)
        assertEquals(N64HleR4300ExceptionVectorResolver.BOOT_EXCEPTION_VECTOR, state.programCounter)
        assertEquals(0xFFFFFFFF80001300UL.toLong(), state.readCop0(14))
    }

    @Test fun nestedExceptionWithExlAlreadySetUsesBevVectorAndPreservesEpc() {
        val memory = N64HleGuestAddressSpace()
        val stepperWithMemory = N64HleR4300InstructionStepper(memorySpace = memory)
        val originalEpc = 0xFFFFFFFF80007770UL.toLong()
        val state = N64HleR4300CpuState(
            0x80001400L,
            mapOf(1 to 0x80000101L),
            cop0 = N64HleCop0EntryState(
                mapOf(8 to 0L, 12 to 0x2L, 13 to 0L, 14 to originalEpc),
                "nested exception dispatch test"
            )
        )

        val signal = assertThrows(N64HleR4300ArchitecturalException::class.java) {
            stepperWithMemory.step(i(0x23, 1, 2, 0), state)
        }

        assertEquals(N64HleR4300ExceptionVectorResolver.GENERAL_EXCEPTION_VECTOR, signal.vectorAddress)
        assertEquals(N64HleR4300ExceptionVectorResolver.GENERAL_EXCEPTION_VECTOR, state.programCounter)
        assertEquals(originalEpc, state.readCop0(14))
        assertTrue(signal.registerUpdate.exlWasAlreadySet)
        assertFalse(signal.registerUpdate.epcWritten)
    }

    @Test fun instructionFetchFaultIsNotConvertedIntoDataException() {
        val memory = N64HleGuestAddressSpace()
        val stepperWithMemory = N64HleR4300InstructionStepper(memorySpace = memory)
        val state = exceptionState(0x80800000L, emptyMap())

        val signal = assertThrows(N64HleR4300ArchitecturalException::class.java) {
            stepperWithMemory.stepFromMemory(state)
        }

        assertEquals(N64HleR4300ExceptionCode.INSTRUCTION_BUS_ERROR, signal.context.code)
        assertEquals(0x80800000L, signal.context.badVirtualAddress)
        assertEquals(0x80800000L, signal.context.exceptionProgramCounter)
        assertEquals(0x18L, state.readCop0(13))
        assertEquals(0x2L, state.readCop0(12))
        assertEquals(N64HleR4300ExceptionVectorResolver.GENERAL_EXCEPTION_VECTOR, state.programCounter)
    }

    @Test fun instructionFetchFaultInDelaySlotSetsBdAndUsesBranchPcForEpc() {
        val memory = N64HleGuestAddressSpace(rdramSize = 4)
        val stepperWithMemory = N64HleR4300InstructionStepper(memorySpace = memory)
        val state = exceptionState(0x80000000L, emptyMap())

        stepperWithMemory.step((0x02 shl 26) or 0x800, state) // J; next word lies outside mapped RDRAM
        assertEquals(0x80000004L, state.programCounter)
        assertTrue(stepperWithMemory.hasPendingDelaySlot())

        val signal = assertThrows(N64HleR4300ArchitecturalException::class.java) {
            stepperWithMemory.stepFromMemory(state)
        }

        assertEquals(N64HleR4300ExceptionCode.INSTRUCTION_BUS_ERROR, signal.context.code)
        assertTrue(signal.context.inDelaySlot)
        assertEquals(0x80000004L, signal.context.faultingPc)
        assertEquals(0x80000000L, signal.context.exceptionProgramCounter)
        assertEquals(0x80000004L, signal.context.badVirtualAddress)
        assertEquals(0xFFFFFFFF80000000UL.toLong(), state.readCop0(14))
        assertEquals(0xFFFFFFFF80000004UL.toLong(), state.readCop0(8))
        assertEquals(0xFFFFFFFF80000018UL.toLong(), state.readCop0(13))
        assertEquals(0x2L, state.readCop0(12))
        assertEquals(N64HleR4300ExceptionVectorResolver.GENERAL_EXCEPTION_VECTOR, state.programCounter)
        assertFalse(stepperWithMemory.hasPendingDelaySlot())
    }

    @Test fun addiOverflowEntersArithmeticExceptionWithoutWritingDestination() {
        val state = exceptionState(0x80001000L, mapOf(1 to Int.MAX_VALUE.toLong()))
        val signal = assertThrows(N64HleR4300ArchitecturalException::class.java) {
            stepper.step(i(0x08, 1, 2, 1), state)
        }
        assertEquals(N64HleR4300ExceptionCode.ARITHMETIC_OVERFLOW, signal.context.code)
        assertEquals(0x30L, state.readCop0(13))
        assertEquals(0xFFFFFFFF80001000UL.toLong(), state.readCop0(14))
        assertEquals(0x2L, state.readCop0(12))
        assertFalse(state.isGprKnown(2))
        assertEquals(N64HleR4300ExceptionVectorResolver.GENERAL_EXCEPTION_VECTOR, state.programCounter)
    }

    @Test fun signedOverflowInDelaySlotSetsBdAndBranchEpc() {
        val state = exceptionState(0x80001000L, mapOf(1 to Int.MAX_VALUE.toLong(), 2 to 1L))
        stepper.step((0x02 shl 26) or 0x800, state)
        val signal = assertThrows(N64HleR4300ArchitecturalException::class.java) {
            stepper.step(r(1, 2, 3, 0x20), state) // ADD overflow in delay slot
        }
        assertEquals(N64HleR4300ExceptionCode.ARITHMETIC_OVERFLOW, signal.context.code)
        assertTrue(signal.context.inDelaySlot)
        assertEquals(0xFFFFFFFF80001000UL.toLong(), state.readCop0(14))
        assertEquals(0xFFFFFFFF80000030UL.toLong(), state.readCop0(13))
        assertFalse(state.isGprKnown(3))
        assertFalse(stepper.hasPendingDelaySlot())
        assertEquals(N64HleR4300ExceptionVectorResolver.GENERAL_EXCEPTION_VECTOR, state.programCounter)
    }

}
