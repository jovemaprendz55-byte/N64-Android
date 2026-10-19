package com.jovemaprendz55byte.n64android

import org.junit.Assert.assertEquals
import org.junit.Test

class R4300CpuTest {
    @Test fun executesImmediateArithmeticAndKeepsZeroRegisterConstant() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(iType(0x09, 0, 1, 10), iType(0x0D, 1, 2, 0x20), iType(0x09, 0, 0, 99)))
        repeat(3) { cpu.step() }
        assertEquals(10L, cpu.register(1))
        assertEquals(42L, cpu.register(2))
        assertEquals(0L, cpu.register(0))
        assertEquals(12L, cpu.pc)
    }

    @Test fun executesLuiAndBigEndianLoadStore() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(iType(0x0F, 0, 1, 0x1234), iType(0x2B, 0, 1, 0x100), iType(0x23, 0, 2, 0x100)))
        repeat(3) { cpu.step() }
        assertEquals(0x12340000L, cpu.register(1))
        assertEquals(0x12340000L, cpu.register(2))
        assertEquals(0x12340000, cpu.readWord(0x100))
    }

    @Test fun signedBranchesAndZeroComparisonsRespectDelaySlots() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x09, 0, 1, -1),    // r1 = -1
            iType(0x01, 1, 0, 2),     // BLTZ -> address 16
            iType(0x09, 0, 2, 10),    // delay slot executes
            iType(0x09, 0, 3, 30),    // skipped
            iType(0x09, 0, 4, 40),    // branch target
            iType(0x09, 0, 1, 0),     // r1 = 0
            iType(0x01, 1, 1, 2),     // BGEZ -> address 36
            iType(0x09, 0, 5, 50),    // delay slot executes
            iType(0x09, 0, 6, 60),    // skipped
            iType(0x09, 0, 7, 70),    // branch target
            iType(0x06, 1, 0, 2),     // BLEZ with zero -> address 52
            iType(0x09, 0, 8, 80),    // delay slot executes
            iType(0x09, 0, 9, 90),    // skipped
            iType(0x07, 1, 0, 2),     // BGTZ with zero is not taken
            iType(0x09, 0, 10, 100),
            iType(0x09, 0, 11, 110)
        ))
        repeat(16) { cpu.step() }
        assertEquals(10L, cpu.register(2))
        assertEquals(0L, cpu.register(3))
        assertEquals(40L, cpu.register(4))
        assertEquals(50L, cpu.register(5))
        assertEquals(0L, cpu.register(6))
        assertEquals(70L, cpu.register(7))
        assertEquals(80L, cpu.register(8))
        assertEquals(0L, cpu.register(9))
        assertEquals(100L, cpu.register(10))
        assertEquals(110L, cpu.register(11))
    }

    @Test fun beqAndBneHandleForwardAndBackwardOffsetsWithDelaySlots() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x09, 0, 1, 5),       // 0x00: r1 = 5
            iType(0x09, 0, 2, 5),       // 0x04: r2 = 5
            iType(0x04, 1, 2, 2),       // 0x08: BEQ target 0x14
            iType(0x09, 0, 3, 30),      // 0x0C: delay slot executes
            iType(0x09, 0, 4, 40),      // 0x10: skipped
            iType(0x05, 1, 2, -2),      // 0x14: BNE not taken
            iType(0x09, 0, 5, 50),      // 0x18: sequential after not-taken branch
            iType(0x04, 1, 2, -2),      // 0x1C: BEQ backwards to 0x18
            iType(0x09, 0, 6, 60),      // 0x20: delay slot executes
            iType(0x09, 0, 7, 70)       // 0x24: target 0x18 already passed
        ))

        repeat(4) { cpu.step() }
        assertEquals(30L, cpu.register(3))
        assertEquals(0L, cpu.register(4))
        assertEquals(0x14L, cpu.pc)

        cpu.step() // BNE is not taken
        cpu.step() // sequential instruction
        assertEquals(50L, cpu.register(5))
        assertEquals(0x1CL, cpu.pc)

        cpu.step() // backward BEQ
        cpu.step() // delay slot, then target
        assertEquals(60L, cpu.register(6))
        assertEquals(0x18L, cpu.pc)
    }

    @Test fun branchAppliesAfterOneDelaySlot() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(iType(0x09, 0, 1, 1), iType(0x04, 1, 1, 2), iType(0x09, 0, 2, 7), iType(0x09, 0, 3, 99), iType(0x09, 0, 4, 5)))
        cpu.step(); cpu.step()
        assertEquals(8L, cpu.pc)
        cpu.step()
        assertEquals(0x10L, cpu.pc)
        cpu.step()
        assertEquals(7L, cpu.register(2))
        assertEquals(0L, cpu.register(3))
        assertEquals(5L, cpu.register(4))
    }

    @Test fun supportsSignedAndUnsignedByteAndHalfwordLoads() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(iType(0x20, 0, 1, 0x100), iType(0x24, 0, 2, 0x100), iType(0x21, 0, 3, 0x102), iType(0x25, 0, 4, 0x102)))
        cpu.loadBytes(0x100, byteArrayOf(0x80.toByte(), 0x00, 0xFF.toByte(), 0xFE.toByte()))
        repeat(4) { cpu.step() }
        assertEquals(-128L, cpu.register(1))
        assertEquals(128L, cpu.register(2))
        assertEquals(-2L, cpu.register(3))
        assertEquals(65534L, cpu.register(4))
    }

    @Test fun supportsDoublewordArithmeticShiftsAndLoadStore() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(iType(0x19, 0, 1, 0x1234), iType(0x3F, 0, 1, 0x108), iType(0x37, 0, 2, 0x108), rType(0, 2, 3, 1, 0x38)))
        repeat(4) { cpu.step() }
        assertEquals(0x1234L, cpu.register(1))
        assertEquals(0x1234L, cpu.register(2))
        assertEquals(0x2468L, cpu.register(3))
        assertEquals(0x1234L, cpu.readLong(0x108))
    }

    @Test fun doublewordMemoryUsesBigEndianOrder() {
        val cpu = R4300Cpu()
        cpu.writeLong(0x80, 0x0123456789ABCDEFL)
        assertEquals(0x01234567, cpu.readWord(0x80))
        assertEquals(0x0123456789ABCDEFL, cpu.readLong(0x80))
    }

    @Test fun sltiAndDsubuProduceExpectedResults() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x09, 0, 1, -5),
            iType(0x0A, 1, 2, -2),
            rType(0, 1, 3, 0, 0x2F)
        ))
        repeat(3) { cpu.step() }
        assertEquals(1L, cpu.register(2))
        assertEquals(5L, cpu.register(3))
    }

    @Test fun daddAndDsubPerformSigned64BitArithmetic() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x19, 0, 1, 12),
            iType(0x19, 0, 2, -5),
            rType(1, 2, 3, 0, 0x2C), // DADD: 12 + -5 = 7
            rType(1, 2, 4, 0, 0x2E)  // DSUB: 12 - -5 = 17
        ))
        repeat(4) { cpu.step() }
        assertEquals(7L, cpu.register(3))
        assertEquals(17L, cpu.register(4))
    }

    @Test fun jalWritesReturnAddressAndTakesEffectAfterDelaySlot() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            jType(0x03, 4),                 // JAL target 0x10
            iType(0x09, 0, 1, 7),          // delay slot
            iType(0x09, 0, 2, 99),         // skipped
            iType(0x09, 0, 3, 99),         // skipped
            iType(0x09, 0, 4, 5)           // target
        ))
        cpu.step()
        assertEquals(8L, cpu.register(31))
        assertEquals(4L, cpu.pc)
        cpu.step()
        assertEquals(0x10L, cpu.pc)
        cpu.step()
        assertEquals(7L, cpu.register(1))
        assertEquals(0L, cpu.register(2))
        assertEquals(5L, cpu.register(4))
    }

    @Test fun bgtzBranchesOnlyWhenRegisterIsPositive() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x09, 0, 1, -1),
            iType(0x07, 1, 0, 2),
            iType(0x09, 0, 2, 3),
            iType(0x09, 0, 3, 4)
        ))
        repeat(4) { cpu.step() }
        assertEquals(3L, cpu.register(2))
        assertEquals(4L, cpu.register(3))
    }

    @Test fun multiplyAndMoveHiLoInstructionsWork() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x09, 0, 1, -7),
            iType(0x09, 0, 2, 3),
            rType(1, 2, 0, 0, 0x18), // MULT: -7 * 3
            rType(0, 0, 3, 0, 0x12), // MFLO
            rType(0, 0, 4, 0, 0x10), // MFHI
            rType(1, 2, 0, 0, 0x1A), // DIV: -7 / 3
            rType(0, 0, 5, 0, 0x12), // MFLO
            rType(0, 0, 6, 0, 0x10)  // MFHI
        ))
        repeat(8) { cpu.step() }
        assertEquals(-21L, cpu.register(3))
        assertEquals(-1L, cpu.register(4))
        assertEquals(-2L, cpu.register(5))
        assertEquals(-1L, cpu.register(6))
    }

    @Test fun signedDoublewordMultiplySplitsProductAcrossHiLo() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x19, 0, 1, -7),
            iType(0x19, 0, 2, 3),
            rType(1, 2, 0, 0, 0x1C), // DMULT: -7 * 3 = -21
            rType(0, 0, 3, 0, 0x12), // MFLO
            rType(0, 0, 4, 0, 0x10)  // MFHI
        ))
        repeat(5) { cpu.step() }
        assertEquals(-21L, cpu.register(3))
        assertEquals(-1L, cpu.register(4))
    }

    @Test fun unsignedDoublewordMultiplyUsesUnsignedOperands() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x0F, 0, 1, 0xFFFF),
            iType(0x0D, 1, 1, 0xFFFF), // r1 = 0xFFFFFFFFFFFFFFFF
            iType(0x09, 0, 2, 2),
            rType(1, 2, 0, 0, 0x1D), // DMULTU: (2^64 - 1) * 2
            rType(0, 0, 3, 0, 0x12), // MFLO = ...FFFE
            rType(0, 0, 4, 0, 0x10)  // MFHI = 1
        ))
        repeat(6) { cpu.step() }
        assertEquals(-2L, cpu.register(3))
        assertEquals(1L, cpu.register(4))
    }

    @Test fun unsignedDivideUsesUnsigned32BitOperands() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x0F, 0, 1, 0xFFFF),
            iType(0x0D, 1, 1, 0xFFFF),
            iType(0x09, 0, 2, 2),
            rType(1, 2, 0, 0, 0x1B),
            rType(0, 0, 3, 0, 0x12),
            rType(0, 0, 4, 0, 0x10)
        ))
        repeat(6) { cpu.step() }
        assertEquals(2147483647L, cpu.register(3))
        assertEquals(1L, cpu.register(4))
    }

    @Test fun jalrWithZeroDestinationDoesNotOverwriteReturnRegister() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x09, 0, 1, 16),
            rType(1, 0, 0, 0, 0x09), // JALR rd=$zero, target in r1
            iType(0x09, 0, 2, 7),    // delay slot
            iType(0x09, 0, 3, 99),   // skipped
            iType(0x09, 0, 4, 5)     // target
        ))
        repeat(5) { cpu.step() }
        assertEquals(0L, cpu.register(31))
        assertEquals(7L, cpu.register(2))
        assertEquals(0L, cpu.register(3))
        assertEquals(5L, cpu.register(4))
    }

    @Test fun jalrWritesLinkRegisterAndExecutesDelaySlotBeforeTarget() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x09, 0, 1, 40),      // r1 = target address 0x28
            rType(1, 0, 5, 0, 0x09),    // JALR r5, r1
            iType(0x09, 0, 6, 66),      // delay slot must execute
            iType(0x09, 0, 7, 99)       // must be skipped
        ))
        cpu.loadBytes(40, words(iType(0x09, 0, 8, 88)))

        cpu.step() // set target register
        cpu.step() // JALR
        assertEquals(12L, cpu.register(5))
        cpu.step() // delay slot, then jump
        assertEquals(66L, cpu.register(6))
        assertEquals(40L, cpu.pc)
        cpu.step() // target
        assertEquals(0L, cpu.register(7))
        assertEquals(88L, cpu.register(8))
    }

    @Test fun jrExecutesDelaySlotBeforeRegisterTarget() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x09, 0, 1, 24),      // r1 = target address 0x18
            rType(1, 0, 0, 0, 0x08),    // JR r1
            iType(0x09, 0, 2, 22),      // delay slot must execute
            iType(0x09, 0, 3, 99)       // must be skipped
        ))
        cpu.loadBytes(24, words(iType(0x09, 0, 4, 44)))

        cpu.step()
        cpu.step()
        cpu.step()
        assertEquals(22L, cpu.register(2))
        assertEquals(24L, cpu.pc)
        cpu.step()
        assertEquals(0L, cpu.register(3))
        assertEquals(44L, cpu.register(4))
    }

    @Test fun branchAndLinkInstructionsSetReturnAddressAndHonorDelaySlots() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x09, 0, 1, -1),      // r1 = -1
            iType(0x01, 1, 0x10, 2),    // BLTZAL -> address 0x10
            iType(0x09, 0, 2, 20),      // delay slot
            iType(0x09, 0, 3, 99),      // skipped
            iType(0x09, 0, 4, 40),      // first target
            iType(0x09, 0, 1, 1),       // r1 = 1
            iType(0x01, 1, 0x11, 2),    // BGEZAL -> address 0x24
            iType(0x09, 0, 5, 50),      // delay slot
            iType(0x09, 0, 6, 99),      // skipped
            iType(0x09, 0, 7, 70)       // second target
        ))

        cpu.step() // r1 = -1
        cpu.step() // BLTZAL
        assertEquals(12L, cpu.register(31))
        cpu.step() // delay slot, then target
        cpu.step() // first target
        cpu.step() // r1 = 1
        cpu.step() // BGEZAL
        assertEquals(32L, cpu.register(31))
        cpu.step() // delay slot, then target
        cpu.step() // second target

        assertEquals(20L, cpu.register(2))
        assertEquals(0L, cpu.register(3))
        assertEquals(40L, cpu.register(4))
        assertEquals(50L, cpu.register(5))
        assertEquals(0L, cpu.register(6))
        assertEquals(70L, cpu.register(7))
    }

    @Test fun subuWrapsAt32BitsWithoutSignedOverflowException() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x0F, 0, 1, 0x8000), // r1 = sign-extended 0x80000000
            iType(0x09, 0, 2, 1),      // r2 = 1
            rType(1, 2, 3, 0, 0x23),   // SUBU: 0x80000000 - 1 = 0x7FFFFFFF
            iType(0x09, 0, 4, 0),      // r4 = 0
            rType(4, 2, 5, 0, 0x23)    // SUBU: 0 - 1 = 0xFFFFFFFF, sign-extended
        ))
        repeat(5) { cpu.step() }
        assertEquals(2147483647L, cpu.register(3))
        assertEquals(-1L, cpu.register(5))
    }

    @Test fun addAndSubUseSigned32BitResults() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x09, 0, 1, 12),
            iType(0x09, 0, 2, -5),
            rType(1, 2, 3, 0, 0x20),
            rType(1, 2, 4, 0, 0x22)
        ))
        repeat(4) { cpu.step() }
        assertEquals(7L, cpu.register(3))
        assertEquals(17L, cpu.register(4))
    }

    @Test fun addiPerformsSigned32BitAdditionAndSignExtendsResult() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x08, 0, 1, 32767),   // ADDI: 0 + 32767
            iType(0x08, 1, 2, -1),      // ADDI: 32767 - 1
            iType(0x0F, 0, 3, 0x8000),  // LUI result sign-extends to 64 bits
            iType(0x08, 3, 4, 1)         // ADDI: -2147483648 + 1
        ))
        repeat(4) { cpu.step() }
        assertEquals(32767L, cpu.register(1))
        assertEquals(32766L, cpu.register(2))
        assertEquals(-2147483647L, cpu.register(4))
    }

    @Test fun addiRejectsSigned32BitOverflow() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x0F, 0, 1, 0x7FFF),
            iType(0x0D, 1, 1, 0xFFFF),
            iType(0x08, 1, 2, 1)
        ))
        cpu.step()
        cpu.step()
        try {
            cpu.step()
            throw AssertionError("Expected ADDI signed overflow to be detected")
        } catch (_: ArithmeticException) {
            // The simplified CPU reports overflow instead of modeling a MIPS exception.
        }
        assertEquals(0L, cpu.register(2))
    }

    @Test fun addi64PerformsSignedAddition() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(iType(0x18, 0, 1, -9)))
        cpu.step()
        assertEquals(-9L, cpu.register(1))
    }

    @Test fun luiSignExtendsIts32BitResultOnR4300() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(iType(0x0F, 0, 1, 0xFFFF)))
        cpu.step()
        assertEquals(-65536L, cpu.register(1))
    }

    @Test fun logicalImmediatesZeroExtendTheirSixteenBitOperands() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x0F, 0, 1, 0xFFFF), // LUI sign-extends to -65536
            iType(0x0D, 1, 1, 0xFFFF), // ORI produces -1
            iType(0x0C, 1, 2, 0x00FF), // ANDI zero-extends immediate
            iType(0x0D, 0, 3, 0xFFFF), // ORI zero-extends immediate to 65535
            iType(0x0E, 1, 4, 0x00FF), // XORI: -1 xor 255 = -256
            iType(0x0C, 1, 5, 0xFFFF)  // ANDI masks to 65535
        ))
        repeat(6) { cpu.step() }
        assertEquals(-1L, cpu.register(1))
        assertEquals(255L, cpu.register(2))
        assertEquals(65535L, cpu.register(3))
        assertEquals(-256L, cpu.register(4))
        assertEquals(65535L, cpu.register(5))
    }

    @Test fun sltAndSltuDistinguishSignedAndUnsigned64BitValues() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x19, 0, 1, -1),       // r1 = 0xFFFFFFFFFFFFFFFF
            iType(0x19, 0, 2, 1),        // r2 = 1
            rType(1, 2, 3, 0, 0x2A),     // SLT: -1 < 1
            rType(1, 2, 4, 0, 0x2B),     // SLTU: unsigned max < 1 is false
            rType(2, 1, 5, 0, 0x2B)      // SLTU: 1 < unsigned max
        ))
        repeat(5) { cpu.step() }
        assertEquals(1L, cpu.register(3))
        assertEquals(0L, cpu.register(4))
        assertEquals(1L, cpu.register(5))
    }

    @Test fun sltiuComparesAgainstSignExtendedImmediateAsUnsigned() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x09, 0, 1, 1),
            iType(0x0B, 1, 2, -1),
            iType(0x0B, 0, 3, -1)
        ))
        repeat(3) { cpu.step() }
        assertEquals(1L, cpu.register(2))
        assertEquals(1L, cpu.register(3))
    }


    @Test fun unalignedWordLoadsMergeBigEndianBytesCorrectly() {
        val expectedLwl = intArrayOf(0x11223344, 0x223344DD, 0x3344CCDD, 0x44BBCCDD)
        val expectedLwr = intArrayOf(0xAABBCC11.toInt(), 0xAABB1122.toInt(), 0xAA112233.toInt(), 0x11223344)
        for (offset in 0..3) {
            val leftCpu = R4300Cpu()
            leftCpu.loadBytes(0, words(
                iType(0x09, 0, 1, 0x100),
                iType(0x0F, 0, 2, 0xAABB),
                iType(0x0D, 2, 2, 0xCCDD),
                iType(0x22, 1, 2, offset)
            ))
            leftCpu.loadBytes(0x100, byteArrayOf(0x11, 0x22, 0x33, 0x44))
            repeat(4) { leftCpu.step() }
            assertEquals("LWL offset $offset", expectedLwl[offset].toLong(), leftCpu.register(2).toInt().toLong())

            val rightCpu = R4300Cpu()
            rightCpu.loadBytes(0, words(
                iType(0x09, 0, 1, 0x100),
                iType(0x0F, 0, 2, 0xAABB),
                iType(0x0D, 2, 2, 0xCCDD),
                iType(0x26, 1, 2, offset)
            ))
            rightCpu.loadBytes(0x100, byteArrayOf(0x11, 0x22, 0x33, 0x44))
            repeat(4) { rightCpu.step() }
            assertEquals("LWR offset $offset", expectedLwr[offset].toLong(), rightCpu.register(2).toInt().toLong())
        }
    }

    @Test fun unalignedWordStoresMergeBigEndianBytesCorrectly() {
        val expectedSwl = intArrayOf(0x11223344, 0xAA112233.toInt(), 0xAABB1122.toInt(), 0xAABBCC11.toInt())
        val expectedSwr = intArrayOf(0x44BBCCDD, 0x3344CCDD, 0x223344DD, 0x11223344)
        for (offset in 0..3) {
            val leftCpu = R4300Cpu()
            leftCpu.loadBytes(0, words(
                iType(0x09, 0, 1, 0x100),
                iType(0x0F, 0, 2, 0x1122),
                iType(0x0D, 2, 2, 0x3344),
                iType(0x2A, 1, 2, offset)
            ))
            leftCpu.loadBytes(0x100, byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte()))
            repeat(4) { leftCpu.step() }
            assertEquals("SWL offset $offset", expectedSwl[offset], leftCpu.readWord(0x100))

            val rightCpu = R4300Cpu()
            rightCpu.loadBytes(0, words(
                iType(0x09, 0, 1, 0x100),
                iType(0x0F, 0, 2, 0x1122),
                iType(0x0D, 2, 2, 0x3344),
                iType(0x2E, 1, 2, offset)
            ))
            rightCpu.loadBytes(0x100, byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte()))
            repeat(4) { rightCpu.step() }
            assertEquals("SWR offset $offset", expectedSwr[offset], rightCpu.readWord(0x100))
        }
    }

    @Test fun immediateDoublewordShift32InstructionsHandleBoundaryAmounts() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x0F, 0, 1, 0xFFFF),   // r1 = sign-extended 0xFFFF0000
            iType(0x0D, 1, 1, 0xFFFF),   // r1 = -1
            rType(0, 1, 2, 0, 0x3C),     // DSLL32: -1 << 32
            rType(0, 1, 3, 31, 0x3E),    // DSRL32: unsigned -1 >>> 63
            rType(0, 1, 4, 31, 0x3F),    // DSRA32: signed -1 >> 63
            iType(0x19, 0, 6, 1),        // r6 = 1
            rType(0, 6, 5, 31, 0x3C)     // DSLL32: 1 << 63
        ))
        repeat(7) { cpu.step() }

        assertEquals(-0x1_0000_0000L, cpu.register(2))
        assertEquals(1L, cpu.register(3))
        assertEquals(-1L, cpu.register(4))
        assertEquals(Long.MIN_VALUE, cpu.register(5))
    }

    @Test fun variableWordAndDoublewordShiftsUseRegisterShiftAmounts() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x09, 0, 1, 3),      // r1 = 3
            iType(0x09, 0, 2, 4),      // r2 = shift amount 4
            rType(2, 1, 3, 0, 0x04),   // SLLV: 3 << 4 = 48
            iType(0x09, 0, 4, -1),     // r4 = -1
            iType(0x09, 0, 5, 1),      // r5 = shift amount 1
            rType(5, 4, 6, 0, 0x06),   // SRLV: 0xFFFFFFFF >>> 1
            iType(0x09, 0, 7, -8),     // r7 = -8
            iType(0x09, 0, 8, 2),      // r8 = shift amount 2
            rType(8, 7, 9, 0, 0x07),   // SRAV: -8 >> 2
            iType(0x19, 0, 10, -16),   // r10 = -16 (64-bit)
            rType(2, 1, 11, 0, 0x14),  // DSLLV: 3 << 4
            rType(8, 10, 12, 0, 0x16), // DSRLV: logical 64-bit shift
            rType(8, 10, 13, 0, 0x17)  // DSRAV: arithmetic 64-bit shift
        ))
        repeat(13) { cpu.step() }
        assertEquals(48L, cpu.register(3))
        assertEquals(0x7FFFFFFFL, cpu.register(6))
        assertEquals(-2L, cpu.register(9))
        assertEquals(48L, cpu.register(11))
        assertEquals(0x3FFFFFFFFFFFFFFCL, cpu.register(12))
        assertEquals(-4L, cpu.register(13))
    }

    @Test fun registerLogicalInstructionsProduceExpected64BitResults() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x0F, 0, 1, 0x1234),    // r1 = 0x12340000
            iType(0x0D, 1, 1, 0x5678),    // r1 = 0x12345678
            iType(0x0F, 0, 2, 0xFFFF),    // r2 = sign-extended 0xFFFF0000
            iType(0x0D, 2, 2, 0x00FF),    // r2 = 0xFFFFFFFFFFFF00FF
            rType(1, 2, 3, 0, 0x24),     // AND
            rType(1, 2, 4, 0, 0x25),     // OR
            rType(1, 2, 5, 0, 0x26),     // XOR
            rType(1, 2, 6, 0, 0x27)      // NOR
        ))
        repeat(8) { cpu.step() }

        assertEquals(0x12340078L, cpu.register(3))
        assertEquals(0xFFFF_FFFF_FFFF_56FFUL.toLong(), cpu.register(4))
        assertEquals(0xFFFF_FFFF_EDCB_5687UL.toLong(), cpu.register(5))
        assertEquals(0xA900L, cpu.register(6))
    }

    @Test fun movzMovnAndSyncRespectConditions() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x09, 0, 1, 11),       // r1 = 11
            iType(0x09, 0, 3, 22),       // r3 = 22
            rType(2, 1, 4, 0, 0x0A),     // MOVZ: zero condition, copy r1 to r4
            rType(3, 1, 5, 0, 0x0A),     // MOVZ: nonzero condition, leave r5
            rType(3, 1, 6, 0, 0x0B),     // MOVN: nonzero condition, copy r1 to r6
            rType(2, 3, 7, 0, 0x0B),     // MOVN: zero condition, leave r7
            rType(0, 0, 0, 0, 0x0F)      // SYNC
        ))
        repeat(7) { cpu.step() }
        assertEquals(11L, cpu.register(4))
        assertEquals(0L, cpu.register(5))
        assertEquals(11L, cpu.register(6))
        assertEquals(0L, cpu.register(7))
        assertEquals(28L, cpu.pc)
    }

    @Test fun signedDoublewordDivideWritesQuotientToLoAndRemainderToHi() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x19, 0, 1, -20),
            iType(0x19, 0, 2, 3),
            rType(1, 2, 0, 0, 0x1E), // DDIV: -20 / 3 = -6 remainder -2
            rType(0, 0, 3, 0, 0x12), // MFLO
            rType(0, 0, 4, 0, 0x10)  // MFHI
        ))
        repeat(5) { cpu.step() }
        assertEquals(-6L, cpu.register(3))
        assertEquals(-2L, cpu.register(4))
    }

    @Test fun unsignedDoublewordDivideUsesFullUnsigned64BitRange() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x0F, 0, 1, 0xFFFF),
            iType(0x0D, 1, 1, 0xFFFF), // r1 = 0xFFFFFFFFFFFFFFFF
            iType(0x19, 0, 2, 2),
            rType(1, 2, 0, 0, 0x1F), // DDIVU: (2^64 - 1) / 2
            rType(0, 0, 3, 0, 0x12), // MFLO
            rType(0, 0, 4, 0, 0x10)  // MFHI
        ))
        repeat(6) { cpu.step() }
        assertEquals(0x7FFFFFFFFFFFFFFFL, cpu.register(3))
        assertEquals(1L, cpu.register(4))
    }

    @Test fun doublewordDivideByZeroLeavesHiLoUnchangedInSimplifiedCore() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x19, 0, 1, 42),
            iType(0x19, 0, 2, 7),
            rType(1, 2, 0, 0, 0x11), // MTHI
            rType(1, 2, 0, 0, 0x13), // MTLO
            rType(1, 0, 0, 0, 0x1E), // DDIV by zero
            rType(0, 0, 3, 0, 0x12), // MFLO
            rType(0, 0, 4, 0, 0x10)  // MFHI
        ))
        repeat(7) { cpu.step() }
        assertEquals(42L, cpu.register(3))
        assertEquals(42L, cpu.register(4))
    }

    @Test fun doublewordUnalignedLoadsMergeBigEndianBytesAtEveryOffset() {
        val original = 0xAABBCCDDEEFF0011UL.toLong()
        for (offset in 0..7) {
            val left = R4300Cpu()
            left.loadBytes(0x100, byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88.toByte()))
            left.writeLong(0x108, original)
            left.loadBytes(0, words(
                iType(0x09, 0, 1, 0x100),
                iType(0x37, 0, 2, 0x108),
                iType(0x1A, 1, 2, offset)
            ))
            repeat(3) { left.step() }
            val word = left.readLong(0x100)
            val shift = offset * 8
            val mask = if (shift == 0) 0L else (-1L ushr (64 - shift))
            assertEquals("LDL offset $offset", (word shl shift) or (original and mask), left.register(2))

            val right = R4300Cpu()
            right.loadBytes(0x100, byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88.toByte()))
            right.writeLong(0x108, original)
            right.loadBytes(0, words(
                iType(0x09, 0, 1, 0x100),
                iType(0x37, 0, 2, 0x108),
                iType(0x1B, 1, 2, offset)
            ))
            repeat(3) { right.step() }
            val preserved = if (offset == 7) 0L else (-1L shl ((offset + 1) * 8))
            assertEquals("LDR offset $offset", (original and preserved) or
                (right.readLong(0x100) ushr ((7 - offset) * 8)), right.register(2))
        }
    }

    @Test fun doublewordUnalignedStoresMergeBigEndianBytesAtEveryOffset() {
        val original = 0xAABBCCDDEEFF0011UL.toLong()
        val value = 0x1122334455667788L
        for (offset in 0..7) {
            val left = R4300Cpu()
            left.writeLong(0x100, original)
            left.writeLong(0x108, value)
            left.loadBytes(0, words(
                iType(0x09, 0, 1, 0x100),
                iType(0x37, 0, 2, 0x108),
                iType(0x2C, 1, 2, offset)
            ))
            repeat(3) { left.step() }
            val shift = offset * 8
            val mask = if (shift == 0) 0L else (-1L shl (64 - shift))
            assertEquals("SDL offset $offset", (original and mask) or (value ushr shift), left.readLong(0x100))

            val right = R4300Cpu()
            right.writeLong(0x100, original)
            right.writeLong(0x108, value)
            right.loadBytes(0, words(
                iType(0x09, 0, 1, 0x100),
                iType(0x37, 0, 2, 0x108),
                iType(0x2D, 1, 2, offset)
            ))
            repeat(3) { right.step() }
            val rightMask = if (offset == 7) 0L else (1L shl ((7 - offset) * 8)) - 1L
            assertEquals("SDR offset $offset", (original and rightMask) or (value shl ((7 - offset) * 8)), right.readLong(0x100))
        }
    }


    @Test fun unalignedDoublewordLoadsMatchByteLevelBigEndianReference() {
        val memoryBytes = byteArrayOf(0x10, 0x21, 0x32, 0x43, 0x54, 0x65, 0x76, 0x07)
        val original = 0xA1B2C3D4E5F60718UL.toLong()
        val originalBytes = longToBytes(original)

        for (offset in 0..7) {
            val left = R4300Cpu()
            left.loadBytes(0x100, memoryBytes)
            left.writeLong(0x108, original)
            left.loadBytes(0, words(
                iType(0x37, 0, 2, 0x108),
                iType(0x1A, 0, 2, 0x100 + offset)
            ))
            repeat(2) { left.step() }
            val expectedLeft = originalBytes.copyOf()
            for (i in 0 until 8 - offset) expectedLeft[i] = memoryBytes[offset + i]
            assertEquals("LDL byte reference offset $offset", bytesToLong(expectedLeft), left.register(2))

            val right = R4300Cpu()
            right.loadBytes(0x100, memoryBytes)
            right.writeLong(0x108, original)
            right.loadBytes(0, words(
                iType(0x37, 0, 2, 0x108),
                iType(0x1B, 0, 2, 0x100 + offset)
            ))
            repeat(2) { right.step() }
            val expectedRight = originalBytes.copyOf()
            for (i in 0..offset) expectedRight[7 - offset + i] = memoryBytes[i]
            assertEquals("LDR byte reference offset $offset", bytesToLong(expectedRight), right.register(2))
        }
    }

    @Test fun unalignedDoublewordStoresMatchByteLevelBigEndianReference() {
        val initialBytes = byteArrayOf(0xA1.toByte(), 0xB2.toByte(), 0xC3.toByte(), 0xD4.toByte(),
            0xE5.toByte(), 0xF6.toByte(), 0x07, 0x18)
        val value = 0x1021324354657607L
        val valueBytes = longToBytes(value)

        for (offset in 0..7) {
            val left = R4300Cpu()
            left.loadBytes(0x100, initialBytes)
            left.writeLong(0x108, value)
            left.loadBytes(0, words(
                iType(0x37, 0, 2, 0x108),
                iType(0x2C, 0, 2, 0x100 + offset)
            ))
            repeat(2) { left.step() }
            val expectedLeft = initialBytes.copyOf()
            for (i in offset..7) expectedLeft[i] = valueBytes[i - offset]
            for (i in 0..7) {
                assertEquals("SDL offset $offset byte $i", expectedLeft[i], left.readByte(0x100 + i))
            }

            val right = R4300Cpu()
            right.loadBytes(0x100, initialBytes)
            right.writeLong(0x108, value)
            right.loadBytes(0, words(
                iType(0x37, 0, 2, 0x108),
                iType(0x2D, 0, 2, 0x100 + offset)
            ))
            repeat(2) { right.step() }
            val expectedRight = initialBytes.copyOf()
            for (i in 0..offset) expectedRight[i] = valueBytes[7 - offset + i]
            for (i in 0..7) {
                assertEquals("SDR offset $offset byte $i", expectedRight[i], right.readByte(0x100 + i))
            }
        }
    }

    @Test fun effectiveAddressDoesNotWrapHighAddressesIntoLowRam() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x0F, 0, 1, 1),       // r1 = 0x0000000000010000
            rType(0, 1, 1, 0, 0x3C),    // DSLL32 r1, r1, 0 => 0x0000000100000000
            iType(0x19, 1, 1, 0x100),   // r1 = 0x0000000100000100
            iType(0x23, 1, 2, 0)        // LW must not alias address 0x100
        ))
        repeat(3) { cpu.step() }
        try {
            cpu.step()
            throw AssertionError("Expected high effective address to be rejected")
        } catch (_: IllegalArgumentException) {
            // The simplified RAM rejects addresses it cannot represent.
        }
        assertEquals(0L, cpu.register(2))
    }

    @Test fun multAndMultuSplitProductsIntoHiAndLo() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x09, 0, 1, -1),       // r1 = -1 (32-bit signed -1)
            iType(0x09, 0, 2, 2),        // r2 = 2
            rType(1, 2, 0, 0, 0x18),      // MULT: signed -1 * 2 = -2
            rType(0, 0, 3, 0, 0x10),      // MFHI r3
            rType(0, 0, 4, 0, 0x12),      // MFLO r4
            rType(1, 2, 0, 0, 0x19),      // MULTU: 0xFFFFFFFF * 2 = 0x1FFFFFFFE
            rType(0, 0, 5, 0, 0x10),      // MFHI r5
            rType(0, 0, 6, 0, 0x12)       // MFLO r6
        ))

        repeat(8) { cpu.step() }

        assertEquals(-1L, cpu.register(3))
        assertEquals(-2L, cpu.register(4))
        assertEquals(1L, cpu.register(5))
        assertEquals(-2L, cpu.register(6))
        assertEquals(1L, cpu.hi)
        assertEquals(-2L, cpu.lo)
    }

    @Test fun divAndDivuProduceExpectedQuotientsAndRemainders() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x09, 0, 1, -7),       // r1 = -7
            iType(0x09, 0, 2, 3),        // r2 = 3
            rType(1, 2, 0, 0, 0x1A),      // DIV: -7 / 3 => quotient -2, remainder -1
            rType(0, 0, 3, 0, 0x10),      // MFHI r3
            rType(0, 0, 4, 0, 0x12),      // MFLO r4
            iType(0x09, 0, 1, -1),       // r1 = 0xFFFFFFFF as a 32-bit word
            iType(0x09, 0, 2, 2),        // r2 = 2
            rType(1, 2, 0, 0, 0x1B),      // DIVU: 0xFFFFFFFF / 2 => quotient 0x7FFFFFFF, remainder 1
            rType(0, 0, 5, 0, 0x10),      // MFHI r5
            rType(0, 0, 6, 0, 0x12)       // MFLO r6
        ))

        repeat(10) { cpu.step() }

        assertEquals(-1L, cpu.register(3))
        assertEquals(-2L, cpu.register(4))
        assertEquals(1L, cpu.register(5))
        assertEquals(0x7FFFFFFFL, cpu.register(6))
        assertEquals(1L, cpu.hi)
        assertEquals(0x7FFFFFFFL, cpu.lo)
    }

    private fun longToBytes(value: Long): ByteArray =
        ByteArray(8) { index -> (value ushr (56 - index * 8)).toByte() }

    private fun bytesToLong(bytes: ByteArray): Long {
        var value = 0L
        for (byte in bytes) value = (value shl 8) or (byte.toLong() and 0xFFL)
        return value
    }


    @Test fun resetClearsCpuStatePreservesRamAndSetsEntryPoint() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x19, 0, 1, 12),
            rType(1, 0, 0, 0, 0x11), // MTHI r1
            rType(1, 0, 0, 0, 0x13)  // MTLO r1
        ))
        cpu.loadBytes(0x100, words(iType(0x19, 0, 2, 7)))
        repeat(3) { cpu.step() }
        assertEquals(12L, cpu.register(1))
        assertEquals(12L, cpu.hi)
        assertEquals(12L, cpu.lo)

        cpu.reset(0x100L)
        assertEquals(0x100L, cpu.pc)
        assertEquals(0L, cpu.register(1))
        assertEquals(0L, cpu.register(2))
        assertEquals(0L, cpu.hi)
        assertEquals(0L, cpu.lo)

        cpu.step()
        assertEquals(7L, cpu.register(2))
        assertEquals(0x104L, cpu.pc)
    }

    @Test fun resetRejectsUnalignedOrOutOfRangeEntryPoints() {
        val cpu = R4300Cpu(memorySize = 0x200)
        try {
            cpu.reset(2L)
            throw AssertionError("Expected unaligned entry point to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
        try {
            cpu.reset(0x200L)
            throw AssertionError("Expected out-of-range entry point to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    @Test fun instructionFetchRejectsHighPcInsteadOfWrappingIntoLowRam() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(iType(0x09, 0, 2, 77)))
        cpu.pc = 0x1_0000_0000L
        try {
            cpu.step()
            throw AssertionError("Expected high PC to be rejected")
        } catch (_: IllegalArgumentException) {
            // The simplified core must not truncate the PC to a 32-bit RAM index.
        }
        assertEquals(0L, cpu.register(2))
    }

    @Test fun instructionFetchRejectsUnalignedPc() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(iType(0x09, 0, 2, 77)))
        cpu.pc = 2L
        try {
            cpu.step()
            throw AssertionError("Expected unaligned PC to be rejected")
        } catch (_: IllegalArgumentException) {
            // Instructions are four-byte aligned in this simplified core.
        }
        assertEquals(0L, cpu.register(2))
    }

    @Test fun dadduAndDsubuHandleSigned64BitBoundaryValues() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x0F, 0, 1, 0xFFFF), // r1 = -65536
            iType(0x0D, 1, 1, 0xFFFF), // r1 = -1
            iType(0x19, 0, 2, 1),      // r2 = 1
            rType(1, 2, 3, 0, 0x2D),   // DADDU: -1 + 1 = 0
            rType(3, 2, 4, 0, 0x2F)    // DSUBU: 0 - 1 = -1
        ))
        repeat(5) { cpu.step() }
        assertEquals(-1L, cpu.register(1))
        assertEquals(0L, cpu.register(3))
        assertEquals(-1L, cpu.register(4))
    }

    @Test fun addiuWrapsSigned32BitResultWithoutOverflowException() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x0F, 0, 1, 0x7FFF), // r1 = 0x7FFF0000
            iType(0x0D, 1, 1, 0xFFFF), // r1 = 0x7FFFFFFF
            iType(0x09, 0, 2, 1),      // r2 = 1
            iType(0x09, 0, 3, -1),     // r3 = -1
            iType(0x09, 1, 4, 1),      // ADDIU wraps to signed 32-bit minimum
            iType(0x09, 4, 5, -1)      // ADDIU wraps back to signed 32-bit maximum
        ))
        repeat(6) { cpu.step() }
        assertEquals(-2147483648L, cpu.register(4))
        assertEquals(2147483647L, cpu.register(5))
    }

    @Test fun sltiAndSltiuUseSignedExtendedImmediateWithDifferentComparisons() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x19, 0, 1, 1),       // r1 = 1
            iType(0x0A, 1, 2, -1),      // SLTI: 1 < -1 is false
            iType(0x0B, 1, 3, -1),      // SLTIU: 1 < unsigned 0xFFFFFFFFFFFFFFFF
            iType(0x19, 0, 4, -1),      // r4 = unsigned 64-bit maximum
            iType(0x0A, 4, 5, 1),       // SLTI: -1 < 1 is true
            iType(0x0B, 4, 6, 1)        // SLTIU: unsigned max < 1 is false
        ))
        repeat(6) { cpu.step() }
        assertEquals(0L, cpu.register(2))
        assertEquals(1L, cpu.register(3))
        assertEquals(1L, cpu.register(5))
        assertEquals(0L, cpu.register(6))
    }

    @Test fun dsubRejectsSigned64BitOverflow() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x0F, 0, 3, 0x8000), // r3 = sign-extended 0x80000000
            rType(0, 3, 3, 0, 0x3C),   // DSLL32: r3 = Long.MIN_VALUE
            iType(0x19, 0, 2, 1),      // r2 = 1
            rType(3, 2, 4, 0, 0x2E)    // DSUB: Long.MIN_VALUE - 1 underflows
        ))
        repeat(3) { cpu.step() }
        try {
            cpu.step()
            throw AssertionError("Expected DSUB signed overflow to be detected")
        } catch (_: ArithmeticException) {
            // The simplified core detects overflow but does not model a MIPS exception.
        }
        assertEquals(0L, cpu.register(4))
    }

    @Test fun daddAndDaddiRejectSigned64BitOverflow() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x0F, 0, 1, 0xFFFF), // r1 = -65536
            iType(0x0D, 1, 1, 0xFFFF), // r1 = -1
            rType(0, 1, 2, 1, 0x3A),   // DSRL r2, r1, 1 => Long.MAX_VALUE
            iType(0x19, 0, 3, 1),      // r3 = 1
            rType(2, 3, 4, 0, 0x2C),   // DADD Long.MAX_VALUE + 1 overflows
            iType(0x18, 2, 5, 1)       // DADDI Long.MAX_VALUE + 1 overflows
        ))
        repeat(4) { cpu.step() }

        try {
            cpu.step()
            throw AssertionError("Expected DADD signed overflow to be detected")
        } catch (_: ArithmeticException) {
            // The simplified CPU reports overflow instead of modeling a MIPS exception.
        }
        try {
            cpu.step()
            throw AssertionError("Expected DADDI signed overflow to be detected")
        } catch (_: ArithmeticException) {
            // The simplified CPU reports overflow instead of modeling a MIPS exception.
        }
        assertEquals(0L, cpu.register(4))
        assertEquals(0L, cpu.register(5))
    }

    @Test fun daddiuWrapsAcrossSigned64BitBoundaries() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x0F, 0, 1, 0xFFFF), // LUI sign-extends to -65536
            iType(0x0D, 1, 1, 0xFFFF), // r1 = -1
            rType(0, 1, 2, 1, 0x3A),   // DSRL r2, r1, 1 => Long.MAX_VALUE
            iType(0x19, 2, 3, 1),      // DADDIU wraps Long.MAX_VALUE + 1
            iType(0x19, 3, 4, -1)      // DADDIU wraps Long.MIN_VALUE - 1
        ))
        repeat(5) { cpu.step() }
        assertEquals(Long.MAX_VALUE, cpu.register(2))
        assertEquals(Long.MIN_VALUE, cpu.register(3))
        assertEquals(Long.MAX_VALUE, cpu.register(4))
    }

    private fun iType(op: Int, rs: Int, rt: Int, imm: Int): Int =
        (op shl 26) or (rs shl 21) or (rt shl 16) or (imm and 0xFFFF)

    private fun jType(op: Int, targetWordAddress: Int): Int =
        (op shl 26) or (targetWordAddress and 0x03FFFFFF)

    private fun rType(rs: Int, rt: Int, rd: Int, shamt: Int, funct: Int): Int =
        (rs shl 21) or (rt shl 16) or (rd shl 11) or (shamt shl 6) or funct

    private fun words(vararg instructions: Int): ByteArray {
        val bytes = ByteArray(instructions.size * 4)
        instructions.forEachIndexed { index, instruction ->
            val offset = index * 4
            bytes[offset] = (instruction ushr 24).toByte()
            bytes[offset + 1] = (instruction ushr 16).toByte()
            bytes[offset + 2] = (instruction ushr 8).toByte()
            bytes[offset + 3] = instruction.toByte()
        }
        return bytes
    }
}
