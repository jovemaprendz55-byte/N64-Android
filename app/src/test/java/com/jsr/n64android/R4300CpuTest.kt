package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class R4300CpuTest {
    @Test fun daddSigned64BitOverflowRaisesExceptionButDadduWraps() {
        val memory = N64Memory(64)
        memory.write32(0, rType(1, 2, 3, 0x2C)) // dadd $v1, $at, $v0
        val cpu = R4300Cpu(memory)
        cpu.gpr[1] = Long.MAX_VALUE
        cpu.gpr[2] = 1L
        cpu.gpr[3] = 99L
        cpu.setProgramCounter(0)

        cpu.step()

        assertEquals(12L, (cpu.cop0[13] ushr 2) and 0x1FL)
        assertEquals(99L, cpu.gpr[3])
        assertEquals(0xFFFFFFFF80000180UL.toLong(), cpu.pc)

        val wrappingMemory = N64Memory(64)
        wrappingMemory.write32(0, rType(1, 2, 3, 0x2D)) // daddu does not trap
        val wrappingCpu = R4300Cpu(wrappingMemory)
        wrappingCpu.gpr[1] = Long.MAX_VALUE
        wrappingCpu.gpr[2] = 1L
        wrappingCpu.setProgramCounter(0)
        wrappingCpu.step()
        assertEquals(Long.MIN_VALUE, wrappingCpu.gpr[3])
        assertEquals(0L, wrappingCpu.cop0[12] and 0x2L)
    }

    @Test fun dsubSigned64BitOverflowRaisesException() {
        val memory = N64Memory(64)
        memory.write32(0, rType(1, 2, 3, 0x2E)) // dsub $v1, $at, $v0
        val cpu = R4300Cpu(memory)
        cpu.gpr[1] = Long.MIN_VALUE
        cpu.gpr[2] = 1L
        cpu.gpr[3] = 55L
        cpu.setProgramCounter(0)

        cpu.step()

        assertEquals(12L, (cpu.cop0[13] ushr 2) and 0x1FL)
        assertEquals(55L, cpu.gpr[3])
        assertEquals(0xFFFFFFFF80000180UL.toLong(), cpu.pc)
    }

    @Test fun addiOverflowRaisesOverflowExceptionWithoutWritingDestination() {
        val memory = N64Memory(64)
        memory.write32(0, iType(0x08, 1, 2, 1)) // addi $v0, $at, 1
        val cpu = R4300Cpu(memory)
        cpu.gpr[1] = Int.MAX_VALUE.toLong()
        cpu.gpr[2] = 123L
        cpu.setProgramCounter(0)

        cpu.step()

        assertEquals(12L, (cpu.cop0[13] ushr 2) and 0x1FL) // ExcCode = Overflow
        assertEquals(0L, cpu.cop0[14]) // EPC points to ADDI
        assertEquals(123L, cpu.gpr[2]) // Destination is unchanged
        assertEquals(0xFFFFFFFF80000180UL.toLong(), cpu.pc)
        assertEquals(0x2L, cpu.cop0[12] and 0x2L) // EXL set
    }

    @Test fun addSignedOverflowRaisesExceptionButAdduWraps() {
        val memory = N64Memory(64)
        memory.write32(0, rType(1, 2, 3, 0x20)) // add $v1, $at, $v0
        val cpu = R4300Cpu(memory)
        cpu.gpr[1] = Int.MAX_VALUE.toLong()
        cpu.gpr[2] = 1L
        cpu.gpr[3] = 99L
        cpu.setProgramCounter(0)

        cpu.step()

        assertEquals(12L, (cpu.cop0[13] ushr 2) and 0x1FL)
        assertEquals(99L, cpu.gpr[3])
        assertEquals(0xFFFFFFFF80000180UL.toLong(), cpu.pc)

        val wrappingMemory = N64Memory(64)
        wrappingMemory.write32(0, rType(1, 2, 3, 0x21)) // addu does not trap
        val wrappingCpu = R4300Cpu(wrappingMemory)
        wrappingCpu.gpr[1] = Int.MAX_VALUE.toLong()
        wrappingCpu.gpr[2] = 1L
        wrappingCpu.setProgramCounter(0)
        wrappingCpu.step()
        assertEquals(Int.MIN_VALUE.toLong(), wrappingCpu.gpr[3])
        assertEquals(0L, wrappingCpu.cop0[12] and 0x2L)
    }

    @Test fun subSignedOverflowRaisesOverflowException() {
        val memory = N64Memory(64)
        memory.write32(0, rType(1, 2, 3, 0x22)) // sub $v1, $at, $v0
        val cpu = R4300Cpu(memory)
        cpu.gpr[1] = Int.MIN_VALUE.toLong()
        cpu.gpr[2] = 1L
        cpu.gpr[3] = 77L
        cpu.setProgramCounter(0)

        cpu.step()

        assertEquals(12L, (cpu.cop0[13] ushr 2) and 0x1FL)
        assertEquals(77L, cpu.gpr[3])
        assertEquals(0xFFFFFFFF80000180UL.toLong(), cpu.pc)
    }

    @Test fun executesAddiuAndKeepsZeroRegisterImmutable() {
        val memory = N64Memory(64)
        memory.write32(0, iType(0x09, 0, 1, 7))
        memory.write32(4, iType(0x09, 1, 2, -3))
        memory.write32(8, iType(0x09, 0, 0, 99))
        val cpu = R4300Cpu(memory)
        cpu.setProgramCounter(0)
        repeat(3) { cpu.step() }
        assertEquals(7L, cpu.gpr[1])
        assertEquals(4L, cpu.gpr[2])
        assertEquals(0L, cpu.gpr[0])
        assertEquals(12L, cpu.pc)
    }

    @Test fun branchExecutesDelaySlotBeforeTakingTarget() {
        val memory = N64Memory(64)
        memory.write32(0, iType(0x09, 0, 1, 5))
        memory.write32(4, iType(0x04, 1, 1, 2))
        memory.write32(8, iType(0x09, 0, 2, 8))
        memory.write32(12, iType(0x09, 0, 3, 9))
        memory.write32(16, iType(0x09, 0, 4, 10))
        val cpu = R4300Cpu(memory)
        cpu.setProgramCounter(0)
        repeat(3) { cpu.step() }
        assertEquals(8L, cpu.gpr[2])
        assertEquals(0L, cpu.gpr[3])
        assertEquals(16L, cpu.pc)
        cpu.step()
        assertEquals(10L, cpu.gpr[4])
    }

    @Test fun loadsAndStoresBigEndianWords() {
        val memory = N64Memory(64)
        memory.write32(0, iType(0x09, 0, 1, 32))
        memory.write32(4, iType(0x0F, 0, 2, 0x1234))
        memory.write32(8, iType(0x0D, 2, 2, 0x5678))
        memory.write32(12, iType(0x2B, 1, 2, 0))
        memory.write32(16, iType(0x23, 1, 3, 0))
        val cpu = R4300Cpu(memory)
        cpu.setProgramCounter(0)
        repeat(5) { cpu.step() }
        assertEquals(0x12345678L, cpu.gpr[3])
        assertEquals(0x12345678, memory.read32(32))
    }

    @Test fun mapsSignExtendedKseg0AndKseg1Addresses() {
        val memory = N64Memory(64)
        memory.write32(0, iType(0x09, 0, 1, 11))
        val cpu = R4300Cpu(memory)
        cpu.setProgramCounter(-0x80000000L)
        cpu.step()
        assertEquals(11L, cpu.gpr[1])
        assertEquals(-0x7FFFFFFCL, cpu.pc)
        cpu.setProgramCounter(-0x60000000L)
        cpu.step()
        assertEquals(11L, cpu.gpr[1])
    }

    @Test fun sltuComparesFullUnsigned64BitRegisters() {
        val memory = N64Memory(16)
        memory.write32(0, rType(1, 2, 3, 0x2B))
        val cpu = R4300Cpu(memory)
        cpu.setProgramCounter(0)
        cpu.gpr[1] = 0L
        cpu.gpr[2] = Long.MIN_VALUE
        cpu.step()
        assertEquals(1L, cpu.gpr[3])
    }

    @Test fun immediateComparisonsUseSignedAndUnsignedSemantics() {
        val memory = N64Memory(64)
        memory.write32(0, iType(0x0A, 1, 2, -1)) // slti $v0, $t0, -1
        memory.write32(4, iType(0x0B, 1, 3, -1)) // sltiu $v1, $t0, 0xffffffffffffffff
        val cpu = R4300Cpu(memory)
        cpu.setProgramCounter(0)
        cpu.gpr[1] = -2L
        cpu.step()
        cpu.step()
        assertEquals(1L, cpu.gpr[2])
        assertEquals(1L, cpu.gpr[3])
    }

    @Test fun blezAndBltzTakeBranchesAfterDelaySlot() {
        val memory = N64Memory(64)
        memory.write32(0, iType(0x06, 1, 0, 2)) // blez $t0, target at 12
        memory.write32(4, iType(0x09, 0, 2, 7)) // delay slot
        memory.write32(8, iType(0x09, 0, 3, 8)) // skipped
        memory.write32(12, iType(0x01, 1, 0, 1)) // bltz $t0, target at 20
        memory.write32(16, iType(0x09, 0, 4, 9)) // delay slot
        memory.write32(20, iType(0x09, 0, 5, 10))
        val cpu = R4300Cpu(memory)
        cpu.setProgramCounter(0)
        cpu.gpr[1] = -1L
        repeat(4) { cpu.step() }
        assertEquals(7L, cpu.gpr[2])
        assertEquals(0L, cpu.gpr[3])
        assertEquals(9L, cpu.gpr[4])
        assertEquals(20L, cpu.pc)
        cpu.step()
        assertEquals(10L, cpu.gpr[5])
    }

    @Test fun doublewordArithmeticAndShiftsPreserveHighBits() {
        val memory = N64Memory(64)
        memory.write32(0, rType(1, 2, 3, 0x2D)) // daddu $v1, $t0, $t1
        memory.write32(4, rType(0, 3, 4, 0x38) or (4 shl 6)) // dsll $a0, $v1, 4
        memory.write32(8, iType(0x19, 4, 5, 1)) // daddiu $a1, $a0, 1
        val cpu = R4300Cpu(memory)
        cpu.setProgramCounter(0)
        cpu.gpr[1] = 0x0000000100000000L
        cpu.gpr[2] = 3L
        repeat(3) { cpu.step() }
        assertEquals(0x0000000100000003L, cpu.gpr[3])
        assertEquals(0x0000001000000030L, cpu.gpr[4])
        assertEquals(0x0000001000000031L, cpu.gpr[5])
    }

    @Test fun doublewordLogicalRightShiftFillsWithZeros() {
        val memory = N64Memory(16)
        memory.write32(0, rType(0, 1, 2, 0x3A) or (8 shl 6)) // dsrl $v0, $t0, 8
        val cpu = R4300Cpu(memory)
        cpu.setProgramCounter(0)
        cpu.gpr[1] = 0x8000000000000000UL.toLong()
        cpu.step()
        assertEquals(0x0080000000000000L, cpu.gpr[2])
    }

    @Test fun variableAndThirtyTwoBitDoublewordShiftsUseCorrectEncodings() {
        val memory = N64Memory(32)
        memory.write32(0, rType(1, 6, 3, 0x14)) // dsllv $v1, $a2, $t0
        memory.write32(4, rType(0, 2, 4, 0x3E) or (1 shl 6)) // dsrl32 $a0, $t1, 1
        memory.write32(8, rType(0, 2, 5, 0x3F) or (1 shl 6)) // dsra32 $a1, $t1, 1
        val cpu = R4300Cpu(memory)
        cpu.setProgramCounter(0)
        cpu.gpr[1] = 36L
        cpu.gpr[2] = Long.MIN_VALUE
        cpu.gpr[6] = 8L
        repeat(3) { cpu.step() }
        assertEquals(0x8000000000L, cpu.gpr[3])
        assertEquals(0x40000000L, cpu.gpr[4])
        assertEquals(-0x40000000L, cpu.gpr[5])
    }

    @Test fun movzAndMovnOnlyWriteWhenTheirConditionsMatch() {
        val memory = N64Memory(32)
        memory.write32(0, rType(1, 2, 3, 0x0A)) // movz $v1, $t0, $t1
        memory.write32(4, rType(4, 5, 6, 0x0B)) // movn $a2, $a0, $a1
        memory.write32(8, rType(1, 5, 7, 0x0A)) // movz $a3, $t0, $a1 (must not write)
        val cpu = R4300Cpu(memory)
        cpu.setProgramCounter(0)
        cpu.gpr[1] = 0x123456789ABCDEFL
        cpu.gpr[2] = 0L
        cpu.gpr[4] = -0x1234L
        cpu.gpr[5] = 1L
        cpu.gpr[7] = 99L
        repeat(3) { cpu.step() }
        assertEquals(0x123456789ABCDEFL, cpu.gpr[3])
        assertEquals(-0x1234L, cpu.gpr[6])
        assertEquals(99L, cpu.gpr[7])
    }

    @Test fun signedMultiplyAndDivideUpdateHiLoAndMoveResults() {
        val memory = N64Memory(64)
        memory.write32(0, rType(1, 2, 0, 0x18)) // mult $t0, $t1
        memory.write32(4, rType(0, 0, 3, 0x12)) // mflo $v1
        memory.write32(8, rType(0, 0, 4, 0x10)) // mfhi $a0
        memory.write32(12, rType(1, 2, 0, 0x1A)) // div $t0, $t1
        memory.write32(16, rType(0, 0, 5, 0x12)) // mflo $a1
        memory.write32(20, rType(0, 0, 6, 0x10)) // mfhi $a2
        val cpu = R4300Cpu(memory)
        cpu.setProgramCounter(0)
        cpu.gpr[1] = -17L
        cpu.gpr[2] = 5L
        repeat(6) { cpu.step() }
        assertEquals(-85L, cpu.gpr[3])
        assertEquals(-1L, cpu.gpr[4])
        assertEquals(-3L, cpu.gpr[5])
        assertEquals(-2L, cpu.gpr[6])
        assertEquals(-2L, cpu.hi)
        assertEquals(-3L, cpu.lo)
    }

    @Test fun unsignedMultiplyAndDivideKeepResultsUnsigned() {
        val memory = N64Memory(32)
        memory.write32(0, rType(1, 2, 0, 0x19)) // multu $t0, $t1
        memory.write32(4, rType(0, 0, 3, 0x12)) // mflo $v1
        memory.write32(8, rType(0, 0, 4, 0x10)) // mfhi $a0
        memory.write32(12, rType(1, 2, 0, 0x1B)) // divu $t0, $t1
        memory.write32(16, rType(0, 0, 5, 0x12)) // mflo $a1
        memory.write32(20, rType(0, 0, 6, 0x10)) // mfhi $a2
        val cpu = R4300Cpu(memory)
        cpu.setProgramCounter(0)
        cpu.gpr[1] = 0xFFFFFFFFL
        cpu.gpr[2] = 2L
        repeat(6) { cpu.step() }
        assertEquals(0xFFFFFFFEL, cpu.gpr[3])
        assertEquals(1L, cpu.gpr[4])
        assertEquals(0x7FFFFFFFL, cpu.gpr[5])
        assertEquals(1L, cpu.gpr[6])
    }

    @Test fun signedDoublewordMultiplyAndDivideUpdateHiLo() {
        val memory = N64Memory(64)
        memory.write32(0, rType(1, 2, 0, 0x1C)) // dmult
        memory.write32(4, rType(0, 0, 3, 0x12)) // mflo
        memory.write32(8, rType(0, 0, 4, 0x10)) // mfhi
        memory.write32(12, rType(1, 2, 0, 0x1E)) // ddiv
        memory.write32(16, rType(0, 0, 5, 0x12)) // mflo
        memory.write32(20, rType(0, 0, 6, 0x10)) // mfhi
        val cpu = R4300Cpu(memory)
        cpu.setProgramCounter(0)
        cpu.gpr[1] = Long.MIN_VALUE
        cpu.gpr[2] = 2L
        repeat(3) { cpu.step() }
        assertEquals(0L, cpu.gpr[3])
        assertEquals(-1L, cpu.gpr[4])
        cpu.gpr[1] = -17L
        cpu.gpr[2] = 5L
        repeat(3) { cpu.step() }
        assertEquals(-3L, cpu.gpr[5])
        assertEquals(-2L, cpu.gpr[6])
    }

    @Test fun unsignedDoublewordMultiplyAndDivideHandleHighBit() {
        val memory = N64Memory(64)
        memory.write32(0, rType(1, 2, 0, 0x1D)) // dmultu
        memory.write32(4, rType(0, 0, 3, 0x12)) // mflo
        memory.write32(8, rType(0, 0, 4, 0x10)) // mfhi
        memory.write32(12, rType(1, 2, 0, 0x1F)) // ddivu
        memory.write32(16, rType(0, 0, 5, 0x12)) // mflo
        memory.write32(20, rType(0, 0, 6, 0x10)) // mfhi
        val cpu = R4300Cpu(memory)
        cpu.setProgramCounter(0)
        cpu.gpr[1] = -1L // unsigned 2^64-1
        cpu.gpr[2] = 2L
        repeat(6) { cpu.step() }
        assertEquals(-2L, cpu.gpr[3]) // low 64 bits of (2^64-1)*2
        assertEquals(1L, cpu.gpr[4])
        assertEquals(0x7FFFFFFFFFFFFFFFL, cpu.gpr[5])
        assertEquals(1L, cpu.gpr[6])
    }

    @Test fun cop0MoveFromAndMoveToTransferSignExtended32BitValues() {
        val memory = N64Memory(32)
        memory.write32(0, (0x10 shl 26) or (0x04 shl 21) or (1 shl 16) or (12 shl 11)) // mtc0 $t0, Status
        memory.write32(4, (0x10 shl 26) or (2 shl 16) or (12 shl 11)) // mfc0 $v0, Status
        val cpu = R4300Cpu(memory)
        cpu.setProgramCounter(0)
        cpu.gpr[1] = 0xFFFFFFFF80000001UL.toLong()
        cpu.step()
        assertEquals(-0x7FFFFFFFL, cpu.cop0[12])
        cpu.step()
        assertEquals(-0x7FFFFFFFL, cpu.gpr[2])
    }

    @Test fun resetClearsCop0RegisterStorage() {
        val memory = N64Memory(16)
        val cpu = R4300Cpu(memory)
        cpu.cop0[12] = 0x1234L
        cpu.reset()
        assertEquals(0L, cpu.cop0[12])
        assertEquals(R4300Cpu.RESET_VECTOR, cpu.pc)
    }

    @Test fun eretReturnsToEpcAndClearsExl() {
        val memory = N64Memory(16)
        memory.write32(0, (0x10 shl 26) or (0x10 shl 21) or 0x18) // eret
        val cpu = R4300Cpu(memory)
        cpu.setProgramCounter(0)
        cpu.cop0[12] = 0x2L
        cpu.cop0[14] = 0x100L
        cpu.step()
        assertEquals(0x100L, cpu.pc)
        assertEquals(0L, cpu.cop0[12] and 0x2L)
    }

    @Test fun eretUsesErrorEpcAndClearsErlWhenErrorLevelIsSet() {
        val memory = N64Memory(16)
        memory.write32(0, (0x10 shl 26) or (0x10 shl 21) or 0x18) // eret
        val cpu = R4300Cpu(memory)
        cpu.setProgramCounter(0)
        cpu.cop0[12] = 0x4L
        cpu.cop0[30] = 0x200L
        cpu.step()
        assertEquals(0x200L, cpu.pc)
        assertEquals(0L, cpu.cop0[12] and 0x4L)
    }

    @Test fun loadsAndStoresDoublewordsAndZeroExtendsLwu() {
        val memory = N64Memory(64)
        memory.write32(0, iType(0x3F, 1, 2, 0)) // sd $t1, 0($t0)
        memory.write32(4, iType(0x37, 1, 3, 0)) // ld $v1, 0($t0)
        memory.write32(8, iType(0x27, 1, 4, 0)) // lwu $a0, 0($t0)
        val cpu = R4300Cpu(memory)
        cpu.setProgramCounter(0)
        cpu.gpr[1] = 32L
        cpu.gpr[2] = 0xFEDCBA9876543210UL.toLong()
        repeat(3) { cpu.step() }
        assertEquals(0xFEDCBA98.toInt(), memory.read32(32))
        assertEquals(0x76543210, memory.read32(36))
        assertEquals(0xFEDCBA9876543210UL.toLong(), cpu.gpr[3])
        assertEquals(0xFEDCBA98L, cpu.gpr[4])
    }

    @Test fun resetVectorReportsMissingPifRomInsteadOfOutOfBoundsRam() {
        val cpu = R4300Cpu(N64Bus())
        val error = assertThrows(UnsupportedOperationException::class.java) { cpu.step() }
        assertTrue(error.message!!.contains("PIF boot ROM is not loaded"))
    }

    @Test fun busReadsPifRomWhenExplicitlyProvided() {
        val pif = ByteArray(N64Bus.PIF_ROM_SIZE)
        pif[0] = 0x12
        pif[1] = 0x34
        pif[2] = 0x56
        pif[3] = 0x78
        val bus = N64Bus(pifRom = pif)
        assertEquals(0x12345678, bus.read32(N64Bus.PIF_ROM_BASE))
    }

    @Test fun busMapsCartridgeRomAtPhysicalBase() {
        val cart = byteArrayOf(0x80.toByte(), 0x37, 0x12, 0x40, 0x11, 0x22, 0x33, 0x44)
        val bus = N64Bus(cartridgeRom = cart)
        assertEquals(0x80371240.toInt(), bus.read32(N64Bus.CARTRIDGE_ROM_BASE))
        assertEquals(0x11223344, bus.read32(N64Bus.CARTRIDGE_ROM_BASE + 4))
    }

    @Test fun cpuCanFetchAndExecuteInstructionFromMappedCartridgeWindow() {
        // addiu $t0, $zero, 42 — instruction bytes are stored big-endian in the ROM.
        val cartridge = byteArrayOf(0x24, 0x08, 0x00, 0x2A)
        val bus = N64Bus(cartridgeRom = cartridge)
        val cpu = R4300Cpu(bus)
        // KSEG1 virtual address B0000000 maps to physical cartridge address 10000000.
        cpu.setProgramCounter(0xFFFFFFFFB0000000UL.toLong())
        cpu.step()
        assertEquals(42L, cpu.gpr[8])
        assertEquals(0xFFFFFFFFB0000004UL.toLong(), cpu.pc)
    }

    @Test fun piDmaCopiesCartridgeBytesIntoRdram() {
        val bus = N64Bus(cartridgeRom = byteArrayOf(0x80.toByte(), 0x37, 0x12, 0x40, 1, 2, 3, 4))
        bus.write32(N64Bus.PI_DRAM_ADDR_REG, 0x100)
        bus.write32(N64Bus.PI_CART_ADDR_REG, N64Bus.CARTRIDGE_ROM_BASE)
        // PI_RD_LEN is length minus one, so 3 requests four bytes.
        bus.write32(N64Bus.PI_RD_LEN_REG, 3)
        assertEquals(0x80371240.toInt(), bus.read32(0x100))
        assertEquals(0x104, bus.read32(N64Bus.PI_DRAM_ADDR_REG))
        assertEquals(N64Bus.CARTRIDGE_ROM_BASE + 4, bus.read32(N64Bus.PI_CART_ADDR_REG))
    }

    @Test fun piDmaRejectsReadsOutsideCartridgeImage() {
        val bus = N64Bus(cartridgeRom = byteArrayOf(1, 2, 3, 4))
        bus.write32(N64Bus.PI_DRAM_ADDR_REG, 0)
        bus.write32(N64Bus.PI_CART_ADDR_REG, N64Bus.CARTRIDGE_ROM_BASE + 2)
        assertThrows(IllegalArgumentException::class.java) {
            bus.write32(N64Bus.PI_RD_LEN_REG, 2)
        }
    }

    @Test fun cartridgeRomIsReadOnlyAndOutOfRangeReadsFailClearly() {
        val bus = N64Bus(cartridgeRom = byteArrayOf(1, 2, 3, 4))
        assertThrows(UnsupportedOperationException::class.java) {
            bus.write8(N64Bus.CARTRIDGE_ROM_BASE, 0)
        }
        val error = assertThrows(UnsupportedOperationException::class.java) {
            bus.read8(N64Bus.CARTRIDGE_ROM_BASE + 4)
        }
        assertTrue(error.message!!.contains("beyond the loaded ROM size"))
    }

    @Test fun rejectsUnalignedInstructionFetchButRaisesArchitecturalExceptionForWordLoad() {
        val memory = N64Memory(64)
        memory.write32(0, iType(0x23, 1, 2, 0))
        val cpu = R4300Cpu(memory)
        assertThrows(IllegalArgumentException::class.java) { cpu.setProgramCounter(2) }
        cpu.setProgramCounter(0)
        cpu.gpr[1] = 1
        cpu.step()
        assertEquals(4L, (cpu.cop0[13] ushr 2) and 0x1FL) // AdEL
        assertEquals(1L, cpu.cop0[8]) // BadVAddr
        assertEquals(0xFFFFFFFF80000180UL.toLong(), cpu.pc)
    }

    @Test fun syscallSetsCauseEpcExlAndJumpsToGeneralExceptionVector() {
        val memory = N64Memory(0x200)
        memory.write32(0, rType(0, 0, 0, 0x0C)) // syscall
        val cpu = R4300Cpu(memory)
        cpu.setProgramCounter(0)
        cpu.step()
        assertEquals(0xFFFFFFFF80000180UL.toLong(), cpu.pc)
        assertEquals(0L, cpu.cop0[14]) // EPC
        assertEquals(8L, (cpu.cop0[13] ushr 2) and 0x1FL) // ExcCode = Syscall
        assertEquals(0x2L, cpu.cop0[12] and 0x2L) // EXL set
    }

    @Test fun breakUsesBootVectorWhenBevIsSetAndPreservesEpcWhenAlreadyExl() {
        val memory = N64Memory(0x400)
        memory.write32(0, rType(0, 0, 0, 0x0D)) // break
        memory.write32(4, rType(0, 0, 0, 0x0D)) // break again while EXL is already set
        val cpu = R4300Cpu(memory)
        cpu.setProgramCounter(0)
        cpu.cop0[12] = 1L shl 22 // BEV
        cpu.cop0[14] = 0x44L
        cpu.step()
        assertEquals(0xFFFFFFFFBFC00380UL.toLong(), cpu.pc)
        assertEquals(0L, cpu.cop0[14])
        assertEquals(9L, (cpu.cop0[13] ushr 2) and 0x1FL) // ExcCode = Break
        cpu.setProgramCounter(4)
        cpu.cop0[12] = (1L shl 22) or 0x2L
        cpu.cop0[14] = 0x44L
        cpu.step()
        assertEquals(0x44L, cpu.cop0[14]) // EPC remains unchanged while EXL is set
    }

    @Test fun syscallInBranchDelaySlotSetsCauseBdAndBranchEpc() {
        val memory = N64Memory(0x200)
        memory.write32(0, iType(0x04, 0, 0, 2)) // beq $zero, $zero, target
        memory.write32(4, rType(0, 0, 0, 0x0C)) // syscall in delay slot
        val cpu = R4300Cpu(memory)
        cpu.setProgramCounter(0)
        cpu.step()
        cpu.step()
        assertEquals(0L, cpu.cop0[14]) // EPC points to branch address zero
        assertEquals(1L, (cpu.cop0[13] ushr 31) and 1L) // Cause.BD
        assertEquals(8L, (cpu.cop0[13] ushr 2) and 0x1FL) // Syscall ExcCode
        assertEquals(0xFFFFFFFF80000180UL.toLong(), cpu.pc)
    }


    @Test fun cpuUsesTlbForInstructionFetchAndDataLoadWhenEnabled() {
        val memory = N64Memory(0x3000)
        memory.write32(0x1000, iType(0x09, 0, 8, 42)) // addiu $t0, $zero, 42
        memory.write32(0x1004, iType(0x23, 9, 2, 0)) // lw $v0, 0($t1)
        memory.write32(0x2000, 0x12345678)
        val cpu = R4300Cpu(memory)
        cpu.tlb.write(0, R4300Tlb.Entry(
            virtualPagePairBase = 0x00400000L,
            asid = 5,
            evenPage = R4300Tlb.Page(1, valid = true, dirty = true),
            oddPage = R4300Tlb.Page(2, valid = true, dirty = true)
        ))
        cpu.cop0[10] = 5L // EntryHi ASID
        cpu.tlbEnabled = true
        cpu.setProgramCounter(0x00400000L)
        cpu.gpr[9] = 0x00401000L
        cpu.step()
        cpu.step()
        assertEquals(42L, cpu.gpr[8])
        assertEquals(0x12345678L, cpu.gpr[2])
        assertEquals(0x00400008L, cpu.pc)
    }

    @Test fun cop0TlbwiTlbrAndTlbpRoundTripEntry() {
        val memory = N64Memory(64)
        memory.write32(0, cop0Co(0x02)) // TLBWI
        memory.write32(4, cop0Co(0x01)) // TLBR
        memory.write32(8, cop0Co(0x08)) // TLBP
        val cpu = R4300Cpu(memory)
        cpu.setProgramCounter(0)
        cpu.cop0[0] = 3L // Index
        cpu.cop0[10] = 0x00400005L // EntryHi: VPN2 + ASID 5
        cpu.cop0[2] = (1L shl 6) or 0x6L // PFN 1, valid, dirty
        cpu.cop0[3] = (2L shl 6) or 0x2L // PFN 2, valid, read-only
        cpu.step() // TLBWI
        cpu.cop0[10] = 0L
        cpu.cop0[2] = 0L
        cpu.cop0[3] = 0L
        cpu.step() // TLBR
        assertEquals(0x00400005L, cpu.cop0[10])
        assertEquals((1L shl 6) or 0x6L, cpu.cop0[2])
        assertEquals((2L shl 6) or 0x2L, cpu.cop0[3])
        cpu.cop0[10] = 0x00400005L
        cpu.step() // TLBP
        assertEquals(3L, cpu.cop0[0])
    }

    @Test fun cpuTlbTranslationRejectsWritesToCleanPages() {
        val memory = N64Memory(0x3000)
        memory.write32(0, iType(0x2B, 1, 2, 0)) // sw $v0, 0($t0)
        val cpu = R4300Cpu(memory)
        cpu.tlb.write(0, R4300Tlb.Entry(
            virtualPagePairBase = 0x00400000L,
            asid = 1,
            evenPage = R4300Tlb.Page(1, valid = true, dirty = false),
            oddPage = R4300Tlb.Page(2, valid = true, dirty = true)
        ))
        cpu.cop0[10] = 1L
        cpu.tlbEnabled = true
        cpu.setProgramCounter(0)
        cpu.gpr[1] = 0x00400000L
        cpu.gpr[2] = 0xCAFEBABEL
        // Fetch the test instruction through KSEG0, which bypasses the TLB.
        // This isolates the expected MODIFIED fault to the data store under test.
        cpu.setProgramCounter(0xFFFFFFFF80000000UL.toLong())
        cpu.step()
        assertEquals(0x00400000L, cpu.cop0[8]) // BadVAddr
        assertEquals(1L shl 2, cpu.cop0[13] and (0x1FL shl 2)) // Cause.Mod
        assertEquals(0xFFFFFFFF80000180UL.toLong(), cpu.pc)
        assertEquals(0, memory.read32(0x1000))
    }

    @Test fun tlbMissUpdatesCop0FaultRegistersAndUsesRefillVector() {
        val memory = N64Memory(4096)
        val cpu = R4300Cpu(memory)
        cpu.tlbEnabled = true
        cpu.setProgramCounter(0x00400000L)

        cpu.step()

        assertEquals(0x00400000L, cpu.cop0[8]) // BadVAddr
        assertEquals(0x00400000L, cpu.cop0[10] and 0xFFFFE000L) // EntryHi.VPN2
        assertEquals(2L shl 2, cpu.cop0[13] and (0x1FL shl 2)) // Cause.TLBL
        assertEquals(0x00400000L, cpu.cop0[14]) // EPC
        assertEquals(0xFFFFFFFF80000000UL.toLong(), cpu.pc)
        assertEquals(0x2L, cpu.cop0[12] and 0x2L) // EXL
    }

    @Test fun tlbStoreMissUsesStoreExceptionCodeAndBootRefillVector() {
        val memory = N64Memory(4096)
        val cpu = R4300Cpu(memory)
        memory.write32(0, iType(0x0F, 0, 8, 0x0040)) // lui $t0, 0x0040
        memory.write32(4, iType(0x2B, 8, 0, 0)) // sw $zero, 0($t0)
        cpu.cop0[12] = 1L shl 22 // Status.BEV
        cpu.tlbEnabled = true
        // Execute the test program from KSEG0 so instruction fetch bypasses the TLB.
        cpu.setProgramCounter(0xFFFFFFFF80000000UL.toLong())

        cpu.step()
        cpu.step()

        assertEquals(0x00400000L, cpu.cop0[8])
        assertEquals(3L shl 2, cpu.cop0[13] and (0x1FL shl 2)) // Cause.TLBS
        assertEquals(0xFFFFFFFFBFC00200UL.toLong(), cpu.pc)
    }


    @Test fun tlbLoadMissUsesLoadExceptionCodeAndRefillVector() {
        val memory = N64Memory(4096)
        memory.write32(0, iType(0x0F, 0, 8, 0x0040)) // lui $t0, 0x0040
        memory.write32(4, iType(0x23, 8, 2, 0)) // lw $v0, 0($t0)
        val cpu = R4300Cpu(memory)
        cpu.tlbEnabled = true
        cpu.setProgramCounter(0xFFFFFFFF80000000UL.toLong())

        cpu.step()
        cpu.step()

        assertEquals(0x00400000L, cpu.cop0[8]) // BadVAddr
        assertEquals(2L shl 2, cpu.cop0[13] and (0x1FL shl 2)) // Cause.TLBL
        assertEquals(0xFFFFFFFF80000004UL.toLong(), cpu.cop0[14]) // EPC is the faulting load
        assertEquals(0xFFFFFFFF80000000UL.toLong(), cpu.pc) // TLB refill vector
    }

    @Test fun invalidTlbLoadUsesGeneralExceptionVector() {
        val memory = N64Memory(4096)
        memory.write32(0, iType(0x23, 8, 2, 0)) // lw $v0, 0($t0)
        val cpu = R4300Cpu(memory)
        cpu.tlb.write(0, R4300Tlb.Entry(
            virtualPagePairBase = 0x00400000L,
            asid = 0,
            evenPage = R4300Tlb.Page(1, valid = false, dirty = true),
            oddPage = R4300Tlb.Page(2, valid = true, dirty = true)
        ))
        cpu.tlbEnabled = true
        cpu.gpr[8] = 0x00400000L
        cpu.setProgramCounter(0xFFFFFFFF80000000UL.toLong())

        cpu.step()

        assertEquals(0x00400000L, cpu.cop0[8]) // BadVAddr
        assertEquals(2L shl 2, cpu.cop0[13] and (0x1FL shl 2)) // Cause.TLBL
        assertEquals(0xFFFFFFFF80000000UL.toLong(), cpu.cop0[14]) // EPC
        assertEquals(0xFFFFFFFF80000180UL.toLong(), cpu.pc) // Invalid entry uses general vector
    }

    @Test fun tlbLoadMissInBranchDelaySlotSetsBdAndBranchEpc() {
        val memory = N64Memory(4096)
        memory.write32(0, iType(0x04, 0, 0, 2)) // beq $zero, $zero, target
        memory.write32(4, iType(0x23, 8, 2, 0)) // lw $v0, 0($t0) in delay slot
        val cpu = R4300Cpu(memory)
        cpu.tlbEnabled = true
        cpu.gpr[8] = 0x00400000L
        cpu.setProgramCounter(0xFFFFFFFF80000000UL.toLong())

        cpu.step()
        cpu.step()

        assertEquals(0x00400000L, cpu.cop0[8]) // BadVAddr
        assertEquals(0xFFFFFFFF80000000UL.toLong(), cpu.cop0[14]) // EPC points to branch in KSEG0
        assertEquals(1L, (cpu.cop0[13] ushr 31) and 1L) // Cause.BD
        assertEquals(2L shl 2, cpu.cop0[13] and (0x1FL shl 2)) // Cause.TLBL
        assertEquals(0xFFFFFFFF80000000UL.toLong(), cpu.pc) // TLB refill vector
    }

    @Test fun eretWithExlSetReturnsToEpcAndClearsExl() {
        val memory = N64Memory(64)
        memory.write32(0, cop0Co(0x18)) // ERET
        val cpu = R4300Cpu(memory)
        cpu.setProgramCounter(0)
        cpu.cop0[12] = 0x2L // Status.EXL
        cpu.cop0[14] = 0x20L // EPC

        cpu.step()

        assertEquals(0x20L, cpu.pc)
        assertEquals(0L, cpu.cop0[12] and 0x2L) // EXL cleared
    }

    @Test fun eretWithErlSetReturnsToErrorEpcAndClearsErl() {
        val memory = N64Memory(64)
        memory.write32(0, cop0Co(0x18)) // ERET
        val cpu = R4300Cpu(memory)
        cpu.setProgramCounter(0)
        cpu.cop0[12] = 0x6L // Status.ERL and Status.EXL
        cpu.cop0[30] = 0x24L // ErrorEPC
        cpu.cop0[14] = 0x30L // EPC must not win while ERL is set

        cpu.step()

        assertEquals(0x24L, cpu.pc)
        assertEquals(0L, cpu.cop0[12] and 0x4L) // ERL cleared
        assertEquals(0x2L, cpu.cop0[12] and 0x2L) // EXL remains unchanged
    }

    @Test fun tlbMissDuringInstructionFetchUsesLoadMissRefillVector() {
        val memory = N64Memory(4096)
        val cpu = R4300Cpu(memory)
        cpu.tlbEnabled = true
        cpu.setProgramCounter(0x00400000L)

        cpu.step()

        assertEquals(0x00400000L, cpu.cop0[8]) // BadVAddr
        assertEquals(2L shl 2, cpu.cop0[13] and (0x1FL shl 2)) // Cause.TLBL
        assertEquals(0x00400000L, cpu.cop0[14]) // EPC is the faulting fetch
        assertEquals(0xFFFFFFFF80000000UL.toLong(), cpu.pc) // TLB refill vector
        assertEquals(0x2L, cpu.cop0[12] and 0x2L) // EXL
    }

    @Test fun invalidTlbStoreUsesStoreExceptionCodeAndGeneralVector() {
        val memory = N64Memory(4096)
        memory.write32(0, iType(0x2B, 8, 2, 0)) // sw $v0, 0($t0)
        val cpu = R4300Cpu(memory)
        cpu.tlb.write(0, R4300Tlb.Entry(
            virtualPagePairBase = 0x00400000L,
            asid = 0,
            evenPage = R4300Tlb.Page(1, valid = false, dirty = true),
            oddPage = R4300Tlb.Page(2, valid = true, dirty = true)
        ))
        cpu.tlbEnabled = true
        cpu.gpr[8] = 0x00400000L
        cpu.gpr[2] = 0x12345678L
        cpu.setProgramCounter(0xFFFFFFFF80000000UL.toLong())

        cpu.step()

        assertEquals(0x00400000L, cpu.cop0[8]) // BadVAddr
        assertEquals(3L shl 2, cpu.cop0[13] and (0x1FL shl 2)) // Cause.TLBS
        assertEquals(0xFFFFFFFF80000000UL.toLong(), cpu.cop0[14]) // EPC
        assertEquals(0xFFFFFFFF80000180UL.toLong(), cpu.pc) // Invalid entry: general vector
        assertEquals(0x2L, cpu.cop0[12] and 0x2L) // EXL
    }

    @Test fun tlbMissPreservesAsidAndUpdatesContextBadVpn2() {
        val memory = N64Memory(4096)
        val cpu = R4300Cpu(memory)
        val faultAddress = 0x00412000L
        val asid = 0x5AL
        val contextPteBase = 0xA5800000L
        cpu.tlbEnabled = true
        cpu.cop0[10] = asid
        cpu.cop0[4] = contextPteBase
        cpu.setProgramCounter(faultAddress)

        cpu.step()

        assertEquals(asid, cpu.cop0[10] and 0xFFL) // EntryHi.ASID survives VPN2 update
        assertEquals(faultAddress and 0xFFFFE000L, cpu.cop0[10] and 0xFFFFE000L)
        assertEquals(
            contextPteBase or ((faultAddress ushr 9) and 0x007FFFF0L),
            cpu.cop0[4]
        ) // Context.BadVPN2 changes while PTEBase is preserved
    }

    @Test fun tlbFaultWhileExlSetPreservesEpcAndUsesGeneralVector() {
        val memory = N64Memory(4096)
        val cpu = R4300Cpu(memory)
        cpu.tlbEnabled = true
        cpu.cop0[12] = 0x2L // Status.EXL is already set
        cpu.cop0[14] = 0x12345678L // EPC must not be overwritten
        cpu.setProgramCounter(0x00400000L)

        cpu.step()

        assertEquals(0x12345678L, cpu.cop0[14]) // EPC preserved while EXL is set
        assertEquals(2L shl 2, cpu.cop0[13] and (0x1FL shl 2)) // Cause.TLBL
        assertEquals(0xFFFFFFFF80000180UL.toLong(), cpu.pc) // No refill vector with EXL set
        assertEquals(0x2L, cpu.cop0[12] and 0x2L) // EXL remains set
    }

    @Test fun tlbExceptionPreservesPendingInterruptBitsAndClearsStaleBd() {
        val memory = N64Memory(4096)
        val cpu = R4300Cpu(memory)
        val pendingInterruptBits = 0x0000A500L // Cause.IP and other unrelated bits
        cpu.cop0[13] = pendingInterruptBits or (1L shl 31) or (9L shl 2)
        cpu.tlbEnabled = true
        cpu.setProgramCounter(0x00400000L)

        cpu.step()

        assertEquals(pendingInterruptBits, cpu.cop0[13] and 0x0000FF00L)
        assertEquals(0L, (cpu.cop0[13] ushr 31) and 1L) // Not a delay-slot exception
        assertEquals(2L shl 2, cpu.cop0[13] and (0x1FL shl 2)) // ExcCode replaced with TLBL
    }

    @Test fun tlbEntryLoIgnoresReservedBitsAbove20BitPfn() {
        val memory = N64Memory(64)
        memory.write32(0, cop0Co(0x02)) // TLBWI
        memory.write32(4, cop0Co(0x01)) // TLBR
        val cpu = R4300Cpu(memory)
        cpu.setProgramCounter(0)
        cpu.cop0[0] = 9L
        cpu.cop0[10] = 0x00800003L
        val evenEntryLo = (0xABCDE1L shl 6) or 0x6L
        val oddEntryLo = (0xFEDCBAL shl 6) or 0x6L
        cpu.cop0[2] = evenEntryLo
        cpu.cop0[3] = oddEntryLo

        cpu.step() // TLBWI
        cpu.cop0[2] = 0L
        cpu.cop0[3] = 0L
        cpu.step() // TLBR

        assertEquals(((0xABCDE1L and 0xFFFFFL) shl 6) or 0x6L, cpu.cop0[2])
        assertEquals(((0xFEDCBAL and 0xFFFFFL) shl 6) or 0x6L, cpu.cop0[3])
    }

    private fun cop0Co(funct: Int): Int = (0x10 shl 26) or (0x10 shl 21) or funct

    private fun iType(op: Int, rs: Int, rt: Int, imm: Int): Int =
        (op shl 26) or (rs shl 21) or (rt shl 16) or (imm and 0xFFFF)

    private fun rType(rs: Int, rt: Int, rd: Int, funct: Int): Int =
        (rs shl 21) or (rt shl 16) or (rd shl 11) or funct

    @Test fun tlbpSetsProbeFailureBitAndClearsIndexOnMiss() {
        val memory = N64Memory(64)
        memory.write32(0, cop0Co(0x08)) // TLBP
        val cpu = R4300Cpu(memory)
        cpu.setProgramCounter(0)
        cpu.cop0[0] = 7L
        cpu.cop0[10] = 0x00400005L // No matching VPN2/ASID entry

        cpu.step()

        assertEquals(1L shl 31, cpu.cop0[0])
    }


    @Test fun cpuTlbpUsesBothGlobalBitsWhenMatchingDifferentAsid() {
        val memory = N64Memory(64)
        memory.write32(0, cop0Co(0x08)) // TLBP
        val cpu = R4300Cpu(memory)
        cpu.tlb.write(6, R4300Tlb.Entry(
            virtualPagePairBase = 0x00400000L,
            asid = 7,
            evenPage = R4300Tlb.Page(1, valid = true, dirty = true, global = true),
            oddPage = R4300Tlb.Page(2, valid = true, dirty = true, global = true)
        ))
        cpu.cop0[10] = 0x00400005L // ASID 5 differs, but both G bits allow a match.
        cpu.setProgramCounter(0)

        cpu.step()
        assertEquals(6L, cpu.cop0[0]) // Hit returns the matching index with P cleared.

        cpu.tlb.write(6, R4300Tlb.Entry(
            virtualPagePairBase = 0x00400000L,
            asid = 7,
            evenPage = R4300Tlb.Page(1, valid = true, dirty = true, global = true),
            oddPage = R4300Tlb.Page(2, valid = true, dirty = true, global = false)
        ))
        cpu.cop0[0] = 3L
        cpu.setProgramCounter(0)
        cpu.step()
        assertEquals(1L shl 31, cpu.cop0[0]) // One G bit is insufficient; probe misses.
    }


    @Test fun tlbwiAndTlbrUseOnlyFiveBitIndexField() {
        val memory = N64Memory(64)
        memory.write32(0, cop0Co(0x02)) // TLBWI
        memory.write32(4, cop0Co(0x01)) // TLBR
        val cpu = R4300Cpu(memory)
        cpu.setProgramCounter(0)
        cpu.cop0[0] = (1L shl 31) or 0x20L or 5L // P and reserved bit must not affect index.
        cpu.cop0[10] = 0x00600009L
        cpu.cop0[2] = (3L shl 6) or 0x6L
        cpu.cop0[3] = (4L shl 6) or 0x6L

        cpu.step() // TLBWI must write entry 5, not throw for index 37.
        cpu.cop0[0] = 0x20L or 5L
        cpu.cop0[10] = 0L
        cpu.cop0[2] = 0L
        cpu.cop0[3] = 0L
        cpu.step() // TLBR must read entry 5 despite reserved Index bits.

        assertEquals(0x00600009L, cpu.cop0[10])
        assertEquals((3L shl 6) or 0x6L, cpu.cop0[2])
        assertEquals((4L shl 6) or 0x6L, cpu.cop0[3])
    }


    @Test fun ldlMergesBigEndianLeftDoublewordWithoutAlignmentFault() {
        val memory = N64Memory(64)
        memory.write32(0, iType(0x1A, 1, 2, 0)) // ldl $v0, 0($at)
        memory.write32(32, 0x11223344)
        memory.write32(36, 0x55667788)
        val cpu = R4300Cpu(memory)
        cpu.gpr[1] = 33L
        cpu.gpr[2] = 0xAABBCCDDEEFF0011UL.toLong()
        cpu.setProgramCounter(0)

        cpu.step()

        assertEquals(0x2233445566778811L, cpu.gpr[2])
        assertEquals(4L, cpu.pc)
        assertEquals(0L, cpu.cop0[13])
    }

    @Test fun ldrMergesBigEndianRightDoublewordWithoutAlignmentFault() {
        val memory = N64Memory(64)
        memory.write32(0, iType(0x1B, 1, 2, 0)) // ldr $v0, 0($at)
        memory.write32(32, 0x11223344)
        memory.write32(36, 0x55667788)
        val cpu = R4300Cpu(memory)
        cpu.gpr[1] = 33L
        cpu.gpr[2] = 0xAABBCCDDEEFF0011UL.toLong()
        cpu.setProgramCounter(0)

        cpu.step()

        assertEquals(0xAABBCCDDEEFF1122UL.toLong(), cpu.gpr[2])
        assertEquals(4L, cpu.pc)
        assertEquals(0L, cpu.cop0[13])
    }

    @Test fun sdlMergesBigEndianLeftDoublewordIntoMemory() {
        val memory = N64Memory(64)
        memory.write32(0, iType(0x2C, 1, 2, 0)) // sdl $v0, 0($at)
        memory.write32(32, 0xAABBCCDD.toInt())
        memory.write32(36, 0xEEFF0011.toInt())
        val cpu = R4300Cpu(memory)
        cpu.gpr[1] = 33L
        cpu.gpr[2] = 0x1122334455667788L
        cpu.setProgramCounter(0)

        cpu.step()

        assertEquals(0x1122CCDDL, memory.read32(32).toLong() and 0xFFFFFFFFL)
        assertEquals(0xEEFF0011L, memory.read32(36).toLong() and 0xFFFFFFFFL)
        assertEquals(4L, cpu.pc)
    }

    @Test fun sdrMergesBigEndianRightDoublewordIntoMemory() {
        val memory = N64Memory(64)
        memory.write32(0, iType(0x2D, 1, 2, 0)) // sdr $v0, 0($at)
        memory.write32(32, 0xAABBCCDD.toInt())
        memory.write32(36, 0xEEFF0011.toInt())
        val cpu = R4300Cpu(memory)
        cpu.gpr[1] = 33L
        cpu.gpr[2] = 0x1122334455667788L
        cpu.setProgramCounter(0)

        cpu.step()

        assertEquals(0xAA223344L, memory.read32(32).toLong() and 0xFFFFFFFFL)
        assertEquals(0x55667788L, memory.read32(36).toLong() and 0xFFFFFFFFL)
        assertEquals(4L, cpu.pc)
    }

    @Test fun lwlMergesBigEndianLeftBytesWithoutAlignmentFault() {
        val memory = N64Memory(64)
        memory.write32(0, iType(0x22, 1, 2, 0)) // lwl $v0, 0($at)
        memory.write32(32, 0x11223344)
        val cpu = R4300Cpu(memory)
        cpu.gpr[1] = 33L // Offset 1 within the aligned word at address 32.
        cpu.gpr[2] = 0xAABBCCDDL
        cpu.setProgramCounter(0)

        cpu.step()

        assertEquals(0x223344DDL, cpu.gpr[2])
        assertEquals(4L, cpu.pc)
        assertEquals(0L, cpu.cop0[13])
    }

    @Test fun lwrMergesBigEndianRightBytesWithoutAlignmentFault() {
        val memory = N64Memory(64)
        memory.write32(0, iType(0x26, 1, 2, 0)) // lwr $v0, 0($at)
        memory.write32(32, 0x11223344)
        val cpu = R4300Cpu(memory)
        cpu.gpr[1] = 33L
        cpu.gpr[2] = 0xAABBCCDDL
        cpu.setProgramCounter(0)

        cpu.step()

        assertEquals(0xAABB1122.toInt().toLong(), cpu.gpr[2])
        assertEquals(4L, cpu.pc)
        assertEquals(0L, cpu.cop0[13])
    }

    @Test fun ldlAndLdrMergeBytesCorrectlyAtEveryDoublewordOffset() {
        val memoryBytes = intArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88)
        val originalRegister = intArrayOf(0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF, 0x00, 0x11)

        fun packWord(bytes: IntArray, start: Int): Int =
            (bytes[start] shl 24) or (bytes[start + 1] shl 16) or
                (bytes[start + 2] shl 8) or bytes[start + 3]

        for (offset in 0..7) {
            val leftMemory = N64Memory(64)
            leftMemory.write32(0, iType(0x1A, 1, 2, 0)) // ldl $v0, 0($at)
            leftMemory.write32(32, packWord(memoryBytes, 0))
            leftMemory.write32(36, packWord(memoryBytes, 4))
            val leftCpu = R4300Cpu(leftMemory)
            leftCpu.gpr[1] = (32 + offset).toLong()
            leftCpu.gpr[2] = 0xAABBCCDDEEFF0011UL.toLong()
            leftCpu.setProgramCounter(0)
            leftCpu.step()

            val expectedLeft = originalRegister.copyOf()
            for (i in 0 until 8 - offset) expectedLeft[i] = memoryBytes[offset + i]
            val expectedLeftValue = (expectedLeft[0].toLong() shl 56) or
                (expectedLeft[1].toLong() shl 48) or (expectedLeft[2].toLong() shl 40) or
                (expectedLeft[3].toLong() shl 32) or (expectedLeft[4].toLong() shl 24) or
                (expectedLeft[5].toLong() shl 16) or (expectedLeft[6].toLong() shl 8) or
                expectedLeft[7].toLong()
            assertEquals("LDL offset $offset", expectedLeftValue, leftCpu.gpr[2])

            val rightMemory = N64Memory(64)
            rightMemory.write32(0, iType(0x1B, 1, 2, 0)) // ldr $v0, 0($at)
            rightMemory.write32(32, packWord(memoryBytes, 0))
            rightMemory.write32(36, packWord(memoryBytes, 4))
            val rightCpu = R4300Cpu(rightMemory)
            rightCpu.gpr[1] = (32 + offset).toLong()
            rightCpu.gpr[2] = 0xAABBCCDDEEFF0011UL.toLong()
            rightCpu.setProgramCounter(0)
            rightCpu.step()

            val expectedRight = originalRegister.copyOf()
            for (i in 0..offset) expectedRight[7 - offset + i] = memoryBytes[i]
            val expectedRightValue = (expectedRight[0].toLong() shl 56) or
                (expectedRight[1].toLong() shl 48) or (expectedRight[2].toLong() shl 40) or
                (expectedRight[3].toLong() shl 32) or (expectedRight[4].toLong() shl 24) or
                (expectedRight[5].toLong() shl 16) or (expectedRight[6].toLong() shl 8) or
                expectedRight[7].toLong()
            assertEquals("LDR offset $offset", expectedRightValue, rightCpu.gpr[2])
        }
    }

    @Test fun lwlAndLwrMergeBytesCorrectlyAtEveryWordOffset() {
        val memoryBytes = intArrayOf(0x11, 0x22, 0x33, 0x44)
        val originalRegister = intArrayOf(0xAA, 0xBB, 0xCC, 0xDD)

        fun packWord(bytes: IntArray): Int =
            (bytes[0] shl 24) or (bytes[1] shl 16) or (bytes[2] shl 8) or bytes[3]

        fun signedWord(bytes: IntArray): Long = packWord(bytes).toLong()

        for (offset in 0..3) {
            val leftMemory = N64Memory(64)
            leftMemory.write32(0, iType(0x22, 1, 2, 0)) // lwl $v0, 0($at)
            leftMemory.write32(32, packWord(memoryBytes))
            val leftCpu = R4300Cpu(leftMemory)
            leftCpu.gpr[1] = (32 + offset).toLong()
            leftCpu.gpr[2] = 0xAABBCCDDL
            leftCpu.setProgramCounter(0)
            leftCpu.step()

            val expectedLeft = originalRegister.copyOf()
            for (i in 0 until 4 - offset) expectedLeft[i] = memoryBytes[offset + i]
            assertEquals("LWL offset $offset", signedWord(expectedLeft).toInt().toLong(), leftCpu.gpr[2])

            val rightMemory = N64Memory(64)
            rightMemory.write32(0, iType(0x26, 1, 2, 0)) // lwr $v0, 0($at)
            rightMemory.write32(32, packWord(memoryBytes))
            val rightCpu = R4300Cpu(rightMemory)
            rightCpu.gpr[1] = (32 + offset).toLong()
            rightCpu.gpr[2] = 0xAABBCCDDL
            rightCpu.setProgramCounter(0)
            rightCpu.step()

            val expectedRight = originalRegister.copyOf()
            for (i in 0..offset) expectedRight[3 - offset + i] = memoryBytes[i]
            assertEquals("LWR offset $offset", signedWord(expectedRight).toInt().toLong(), rightCpu.gpr[2])
        }
    }

    @Test fun swlAndSwrStoreBytesCorrectlyAtEveryWordOffset() {
        val registerBytes = intArrayOf(0x11, 0x22, 0x33, 0x44)
        val initialBytes = intArrayOf(0xAA, 0xBB, 0xCC, 0xDD)

        fun packWord(bytes: IntArray): Int =
            (bytes[0] shl 24) or (bytes[1] shl 16) or (bytes[2] shl 8) or bytes[3]

        fun assertStore(opcode: Int, offset: Int, expected: IntArray, label: String) {
            val memory = N64Memory(64)
            memory.write32(0, iType(opcode, 1, 2, 0))
            memory.write32(32, packWord(initialBytes))
            val cpu = R4300Cpu(memory)
            cpu.gpr[1] = (32 + offset).toLong()
            cpu.gpr[2] = 0x11223344L
            cpu.setProgramCounter(0)
            cpu.step()
            assertEquals("$label offset $offset", packWord(expected), memory.read32(32))
        }

        for (offset in 0..3) {
            val expectedLeft = initialBytes.copyOf()
            for (i in 0..offset) expectedLeft[i] = registerBytes[i]
            assertStore(0x2A, offset, expectedLeft, "SWL")

            val expectedRight = initialBytes.copyOf()
            for (i in offset..3) expectedRight[i] = registerBytes[i]
            assertStore(0x2E, offset, expectedRight, "SWR")
        }
    }

    @Test fun sdlAndSdrPreserveBytesCorrectlyAtEveryDoublewordOffset() {
        val registerBytes = intArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88)
        val initialBytes = intArrayOf(0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF, 0x00, 0x11)

        fun packWord(bytes: IntArray, start: Int): Int =
            (bytes[start] shl 24) or (bytes[start + 1] shl 16) or
                (bytes[start + 2] shl 8) or bytes[start + 3]

        for (offset in 0..7) {
            val leftMemory = N64Memory(64)
            leftMemory.write32(0, iType(0x2C, 1, 2, 0)) // sdl $v0, 0($at)
            leftMemory.write32(32, packWord(initialBytes, 0))
            leftMemory.write32(36, packWord(initialBytes, 4))
            val leftCpu = R4300Cpu(leftMemory)
            leftCpu.gpr[1] = (32 + offset).toLong()
            leftCpu.gpr[2] = 0x1122334455667788L
            leftCpu.setProgramCounter(0)
            leftCpu.step()

            val expectedLeft = initialBytes.copyOf()
            for (i in 0..offset) expectedLeft[i] = registerBytes[i]
            assertEquals("SDL offset $offset high word", packWord(expectedLeft, 0), leftMemory.read32(32))
            assertEquals("SDL offset $offset low word", packWord(expectedLeft, 4), leftMemory.read32(36))

            val rightMemory = N64Memory(64)
            rightMemory.write32(0, iType(0x2D, 1, 2, 0)) // sdr $v0, 0($at)
            rightMemory.write32(32, packWord(initialBytes, 0))
            rightMemory.write32(36, packWord(initialBytes, 4))
            val rightCpu = R4300Cpu(rightMemory)
            rightCpu.gpr[1] = (32 + offset).toLong()
            rightCpu.gpr[2] = 0x1122334455667788L
            rightCpu.setProgramCounter(0)
            rightCpu.step()

            val expectedRight = initialBytes.copyOf()
            for (i in offset..7) expectedRight[i] = registerBytes[i]
            assertEquals("SDR offset $offset high word", packWord(expectedRight, 0), rightMemory.read32(32))
            assertEquals("SDR offset $offset low word", packWord(expectedRight, 4), rightMemory.read32(36))
        }
    }

    @Test fun sdlAtOffsetZeroStoresOnlyTheMostSignificantByte() {
        val memory = N64Memory(64)
        memory.write32(0, iType(0x2C, 1, 2, 0)) // sdl $v0, 0($at)
        memory.write32(32, 0xAABBCCDD.toInt())
        memory.write32(36, 0xEEFF0011.toInt())
        val cpu = R4300Cpu(memory)
        cpu.gpr[1] = 32L
        cpu.gpr[2] = 0x1122334455667788L
        cpu.setProgramCounter(0)

        cpu.step()

        assertEquals(0x11BBCCDDL, memory.read32(32).toLong() and 0xFFFFFFFFL)
        assertEquals(0xEEFF0011L, memory.read32(36).toLong() and 0xFFFFFFFFL)
    }

    @Test fun sdlAtOffsetSevenStoresTheEntireDoubleword() {
        val memory = N64Memory(64)
        memory.write32(0, iType(0x2C, 1, 2, 0)) // sdl $v0, 0($at)
        memory.write32(32, 0xAABBCCDD.toInt())
        memory.write32(36, 0xEEFF0011.toInt())
        val cpu = R4300Cpu(memory)
        cpu.gpr[1] = 39L
        cpu.gpr[2] = 0x1122334455667788L
        cpu.setProgramCounter(0)

        cpu.step()

        assertEquals(0x11223344L, memory.read32(32).toLong() and 0xFFFFFFFFL)
        assertEquals(0x55667788L, memory.read32(36).toLong() and 0xFFFFFFFFL)
    }

    @Test fun sdrAtOffsetZeroStoresTheEntireDoubleword() {
        val memory = N64Memory(64)
        memory.write32(0, iType(0x2D, 1, 2, 0)) // sdr $v0, 0($at)
        memory.write32(32, 0xAABBCCDD.toInt())
        memory.write32(36, 0xEEFF0011.toInt())
        val cpu = R4300Cpu(memory)
        cpu.gpr[1] = 32L
        cpu.gpr[2] = 0x1122334455667788L
        cpu.setProgramCounter(0)

        cpu.step()

        assertEquals(0x11223344L, memory.read32(32).toLong() and 0xFFFFFFFFL)
        assertEquals(0x55667788L, memory.read32(36).toLong() and 0xFFFFFFFFL)
    }

    @Test fun sdrAtOffsetSevenStoresOnlyTheLeastSignificantByte() {
        val memory = N64Memory(64)
        memory.write32(0, iType(0x2D, 1, 2, 0)) // sdr $v0, 0($at)
        memory.write32(32, 0xAABBCCDD.toInt())
        memory.write32(36, 0xEEFF0011.toInt())
        val cpu = R4300Cpu(memory)
        cpu.gpr[1] = 39L
        cpu.gpr[2] = 0x1122334455667788L
        cpu.setProgramCounter(0)

        cpu.step()

        assertEquals(0xAABBCCDDL, memory.read32(32).toLong() and 0xFFFFFFFFL)
        assertEquals(0xEEFF0088L, memory.read32(36).toLong() and 0xFFFFFFFFL)
    }

    @Test fun swlAtOffsetZeroStoresOnlyTheMostSignificantByte() {
        val memory = N64Memory(64)
        memory.write32(0, iType(0x2A, 1, 2, 0)) // swl $v0, 0($at)
        memory.write32(32, 0xAABBCCDD.toInt())
        val cpu = R4300Cpu(memory)
        cpu.gpr[1] = 32L
        cpu.gpr[2] = 0x11223344L
        cpu.setProgramCounter(0)

        cpu.step()

        assertEquals(0x11BBCCDDL, memory.read32(32).toLong() and 0xFFFFFFFFL)
    }

    @Test fun swlAtOffsetThreeStoresTheEntireWord() {
        val memory = N64Memory(64)
        memory.write32(0, iType(0x2A, 1, 2, 0)) // swl $v0, 0($at)
        memory.write32(32, 0xAABBCCDD.toInt())
        val cpu = R4300Cpu(memory)
        cpu.gpr[1] = 35L
        cpu.gpr[2] = 0x11223344L
        cpu.setProgramCounter(0)

        cpu.step()

        assertEquals(0x11223344L, memory.read32(32).toLong() and 0xFFFFFFFFL)
    }

    @Test fun swlMergesBigEndianLeftBytesIntoMemory() {
        val memory = N64Memory(64)
        memory.write32(0, iType(0x2A, 1, 2, 0)) // swl $v0, 0($at)
        memory.write32(32, 0xAABBCCDD.toInt())
        val cpu = R4300Cpu(memory)
        cpu.gpr[1] = 33L
        cpu.gpr[2] = 0x11223344L
        cpu.setProgramCounter(0)

        cpu.step()

        assertEquals(0x1122CCDDL, memory.read32(32).toLong() and 0xFFFFFFFFL)
        assertEquals(4L, cpu.pc)
    }

    @Test fun swrMergesBigEndianRightBytesIntoMemory() {
        val memory = N64Memory(64)
        memory.write32(0, iType(0x2E, 1, 2, 0)) // swr $v0, 0($at)
        memory.write32(32, 0xAABBCCDD.toInt())
        val cpu = R4300Cpu(memory)
        cpu.gpr[1] = 33L
        cpu.gpr[2] = 0x11223344L
        cpu.setProgramCounter(0)

        cpu.step()

        assertEquals(0xAA223344L, memory.read32(32).toLong() and 0xFFFFFFFFL)
        assertEquals(4L, cpu.pc)
    }

    @Test fun unalignedWordLoadRaisesAddressErrorAndPreservesDestination() {
        val memory = N64Memory(64)
        memory.write32(0, iType(0x23, 1, 2, 0)) // lw $v0, 0($at)
        val cpu = R4300Cpu(memory)
        cpu.gpr[1] = 1L
        cpu.gpr[2] = 0x1234L
        cpu.setProgramCounter(0)

        cpu.step()

        assertEquals(4L, (cpu.cop0[13] ushr 2) and 0x1FL) // AdEL
        assertEquals(1L, cpu.cop0[8]) // BadVAddr
        assertEquals(0x1234L, cpu.gpr[2])
        assertEquals(0xFFFFFFFF80000180UL.toLong(), cpu.pc)
    }

    @Test fun unalignedWordStoreRaisesAddressStoreErrorWithoutWritingMemory() {
        val memory = N64Memory(64)
        memory.write32(0, iType(0x2B, 1, 2, 0)) // sw $v0, 0($at)
        memory.write32(4, 0x11223344)
        val cpu = R4300Cpu(memory)
        cpu.gpr[1] = 5L
        cpu.gpr[2] = 0x55667788L
        cpu.setProgramCounter(0)

        cpu.step()

        assertEquals(5L, (cpu.cop0[13] ushr 2) and 0x1FL) // AdES
        assertEquals(5L, cpu.cop0[8]) // BadVAddr
        assertEquals(0x11223344L, memory.read32(4).toLong() and 0xFFFFFFFFL)
        assertEquals(0xFFFFFFFF80000180UL.toLong(), cpu.pc)
    }

    @Test fun daddiSigned64BitOverflowRaisesExceptionWithoutWritingDestination() {
        val memory = N64Memory(64)
        memory.write32(0, iType(0x18, 1, 2, 1)) // daddi $v0, $at, 1
        val cpu = R4300Cpu(memory)
        cpu.gpr[1] = Long.MAX_VALUE
        cpu.gpr[2] = 123L
        cpu.setProgramCounter(0)

        cpu.step()

        assertEquals(12L, (cpu.cop0[13] ushr 2) and 0x1FL)
        assertEquals(0L, cpu.cop0[14])
        assertEquals(123L, cpu.gpr[2])
        assertEquals(0xFFFFFFFF80000180UL.toLong(), cpu.pc)
    }

    @Test fun daddiuWrapsOnSigned64BitOverflowWithoutException() {
        val memory = N64Memory(64)
        memory.write32(0, iType(0x19, 1, 2, 1)) // daddiu $v0, $at, 1
        val cpu = R4300Cpu(memory)
        cpu.gpr[1] = Long.MAX_VALUE
        cpu.setProgramCounter(0)

        cpu.step()

        assertEquals(Long.MIN_VALUE, cpu.gpr[2])
        assertEquals(0L, cpu.cop0[12] and 0x2L)
        assertEquals(4L, cpu.pc)
    }


    @Test fun tlbLoadMissUpdatesCop0MetadataAndUsesRefillVector() {
        val memory = N64Memory(64)
        memory.write32(0, iType(0x23, 1, 2, 0)) // lw $v0, 0($at)
        val cpu = R4300Cpu(memory)
        cpu.tlbEnabled = true
        cpu.tlb.write(0, R4300Tlb.Entry(
            virtualPagePairBase = 0L,
            asid = 0,
            evenPage = R4300Tlb.Page(0, valid = true, dirty = true, global = true),
            oddPage = R4300Tlb.Page(1, valid = true, dirty = true, global = true)
        ))
        val faultAddress = 0x00401234L
        cpu.gpr[1] = faultAddress
        cpu.cop0[10] = 0x5AL // Preserve current ASID while recording VPN2.
        cpu.setProgramCounter(0)

        cpu.step()

        assertEquals(faultAddress, cpu.cop0[8]) // BadVAddr
        assertEquals((faultAddress and R4300Tlb.PAGE_PAIR_MASK) or 0x5AL, cpu.cop0[10]) // EntryHi
        assertEquals((faultAddress ushr 9) and 0x007FFFF0L, cpu.cop0[4] and 0x007FFFF0L) // Context.BadVPN2
        assertEquals(2L, (cpu.cop0[13] ushr 2) and 0x1FL) // TLBL
        assertEquals(0L, cpu.cop0[14]) // EPC
        assertEquals(0x2L, cpu.cop0[12] and 0x2L) // EXL
        assertEquals(0xFFFFFFFF80000000UL.toLong(), cpu.pc) // TLB refill vector
    }

    @Test fun tlbStoreToCleanPageRaisesModifiedAndUsesGeneralVector() {
        val memory = N64Memory(64)
        memory.write32(0, iType(0x2B, 1, 2, 0)) // sw $v0, 0($at)
        val cpu = R4300Cpu(memory)
        cpu.tlbEnabled = true
        cpu.tlb.write(0, R4300Tlb.Entry(
            virtualPagePairBase = 0L,
            asid = 0,
            evenPage = R4300Tlb.Page(0, valid = true, dirty = true, global = true),
            oddPage = R4300Tlb.Page(1, valid = true, dirty = true, global = true)
        ))
        val faultAddress = 0x00401234L
        cpu.tlb.write(1, R4300Tlb.Entry(
            virtualPagePairBase = faultAddress and R4300Tlb.PAGE_PAIR_MASK,
            asid = 0,
            evenPage = R4300Tlb.Page(2, valid = true, dirty = false, global = true),
            oddPage = R4300Tlb.Page(3, valid = true, dirty = false, global = true)
        ))
        cpu.gpr[1] = faultAddress
        cpu.gpr[2] = 0x12345678L
        cpu.setProgramCounter(0)

        cpu.step()

        assertEquals(faultAddress, cpu.cop0[8])
        assertEquals(1L, (cpu.cop0[13] ushr 2) and 0x1FL) // TLB Modified
        assertEquals(0L, cpu.cop0[14])
        assertEquals(0xFFFFFFFF80000180UL.toLong(), cpu.pc)
        assertEquals(0L, memory.read32(8).toLong()) // No store occurred at mapped frame 2.
    }

    @Test fun tlbFaultInBranchDelaySlotSetsCauseBdAndBranchAddressAsEpc() {
        val memory = N64Memory(64)
        memory.write32(0, iType(0x04, 0, 0, 2)) // beq $zero, $zero, target
        memory.write32(4, iType(0x23, 1, 2, 0)) // lw $v0, 0($at) in delay slot
        val cpu = R4300Cpu(memory)
        cpu.tlbEnabled = true
        cpu.tlb.write(0, R4300Tlb.Entry(
            virtualPagePairBase = 0L,
            asid = 0,
            evenPage = R4300Tlb.Page(0, valid = true, dirty = true, global = true),
            oddPage = R4300Tlb.Page(1, valid = true, dirty = true, global = true)
        ))
        cpu.gpr[1] = 0x00401234L
        cpu.setProgramCounter(0)

        cpu.step() // Branch establishes a delay slot.
        cpu.step() // The data access faults in the delay slot.

        assertEquals(0L, cpu.cop0[14]) // EPC identifies the branch, not the delay-slot load.
        assertEquals(1L, (cpu.cop0[13] ushr 31) and 1L) // Cause.BD
        assertEquals(2L, (cpu.cop0[13] ushr 2) and 0x1FL) // TLBL
        assertEquals(0xFFFFFFFF80000000UL.toLong(), cpu.pc)
    }


    @Test fun tlbInvalidLoadUsesGeneralVectorInsteadOfRefillVector() {
        val memory = N64Memory(64)
        memory.write32(0, iType(0x23, 1, 2, 0)) // lw $v0, 0($at)
        val cpu = R4300Cpu(memory)
        cpu.tlbEnabled = true
        cpu.tlb.write(0, R4300Tlb.Entry(
            virtualPagePairBase = 0L,
            asid = 0,
            evenPage = R4300Tlb.Page(0, valid = true, dirty = true, global = true),
            oddPage = R4300Tlb.Page(1, valid = true, dirty = true, global = true)
        ))
        val faultAddress = 0x00401234L
        cpu.tlb.write(1, R4300Tlb.Entry(
            virtualPagePairBase = faultAddress and R4300Tlb.PAGE_PAIR_MASK,
            asid = 0,
            evenPage = R4300Tlb.Page(2, valid = true, dirty = true, global = true),
            oddPage = R4300Tlb.Page(3, valid = false, dirty = true, global = true)
        ))
        cpu.gpr[1] = faultAddress
        cpu.gpr[2] = 0xCAFEBABEL
        cpu.setProgramCounter(0)

        cpu.step()

        assertEquals(faultAddress, cpu.cop0[8]) // BadVAddr
        assertEquals(2L, (cpu.cop0[13] ushr 2) and 0x1FL) // TLBL
        assertEquals(0L, cpu.cop0[14]) // EPC
        assertEquals(0xFFFFFFFF80000180UL.toLong(), cpu.pc)
        assertEquals(0xCAFEBABEL, cpu.gpr[2]) // Faulting load must not modify the target register.
    }


    @Test fun tlbStoreMissUsesStoreMissCodeAndRefillVector() {
        val memory = N64Memory(64)
        memory.write32(0, iType(0x2B, 1, 2, 0)) // sw $v0, 0($at)
        val cpu = R4300Cpu(memory)
        cpu.tlbEnabled = true
        cpu.tlb.write(0, R4300Tlb.Entry(
            virtualPagePairBase = 0L,
            asid = 0,
            evenPage = R4300Tlb.Page(0, valid = true, dirty = true, global = true),
            oddPage = R4300Tlb.Page(1, valid = true, dirty = true, global = true)
        ))
        val faultAddress = 0x00601234L
        cpu.gpr[1] = faultAddress
        cpu.gpr[2] = 0x12345678L
        cpu.setProgramCounter(0)

        cpu.step()

        assertEquals(faultAddress, cpu.cop0[8]) // BadVAddr
        assertEquals(3L, (cpu.cop0[13] ushr 2) and 0x1FL) // TLBS
        assertEquals(0L, cpu.cop0[14]) // EPC
        assertEquals(0xFFFFFFFF80000000UL.toLong(), cpu.pc) // Refill vector
        assertEquals(0L, memory.read32(8).toLong()) // Faulting store did not write memory.
    }


    @Test fun tlbInstructionFetchMissUsesLoadMissExceptionAndRefillVector() {
        val memory = N64Memory(64)
        val cpu = R4300Cpu(memory)
        cpu.tlbEnabled = true
        val faultAddress = 0x00400000L
        cpu.setProgramCounter(faultAddress)

        cpu.step()

        assertEquals(faultAddress, cpu.cop0[8]) // BadVAddr records the instruction address.
        assertEquals(2L, (cpu.cop0[13] ushr 2) and 0x1FL) // Instruction-fetch miss is TLBL.
        assertEquals(faultAddress, cpu.cop0[14]) // EPC points to the faulting instruction.
        assertEquals(0xFFFFFFFF80000000UL.toLong(), cpu.pc) // TLB refill vector.
        assertEquals(0x2L, cpu.cop0[12] and 0x2L) // EXL is set.
    }


    @Test fun tlbInstructionFetchInvalidPageUsesGeneralExceptionVector() {
        val memory = N64Memory(64)
        val cpu = R4300Cpu(memory)
        cpu.tlbEnabled = true
        val faultAddress = 0x00400000L
        cpu.tlb.write(0, R4300Tlb.Entry(
            virtualPagePairBase = faultAddress and R4300Tlb.PAGE_PAIR_MASK,
            asid = 0,
            evenPage = R4300Tlb.Page(4, valid = false, dirty = true, global = true),
            oddPage = R4300Tlb.Page(5, valid = true, dirty = true, global = true)
        ))
        cpu.setProgramCounter(faultAddress)

        cpu.step()

        assertEquals(faultAddress, cpu.cop0[8]) // BadVAddr records the invalid fetch address.
        assertEquals(2L, (cpu.cop0[13] ushr 2) and 0x1FL) // Invalid instruction fetch is TLBL.
        assertEquals(faultAddress, cpu.cop0[14]) // EPC points to the faulting instruction.
        assertEquals(0xFFFFFFFF80000180UL.toLong(), cpu.pc) // Invalid entries use the general vector.
        assertEquals(0x2L, cpu.cop0[12] and 0x2L) // EXL is set.
    }


    @Test fun tlbMissWhileExlIsSetPreservesPreviousEpcAndUsesGeneralVector() {
        val memory = N64Memory(64)
        val cpu = R4300Cpu(memory)
        cpu.tlbEnabled = true
        val faultAddress = 0x00400000L
        val previousEpc = 0x12345678L
        cpu.cop0[12] = 0x2L // EXL already set.
        cpu.cop0[14] = previousEpc
        cpu.setProgramCounter(faultAddress)

        cpu.step()

        assertEquals(faultAddress, cpu.cop0[8]) // BadVAddr still records the new fault.
        assertEquals(previousEpc, cpu.cop0[14]) // EPC is not overwritten while EXL is set.
        assertEquals(2L, (cpu.cop0[13] ushr 2) and 0x1FL) // TLBL.
        assertEquals(0xFFFFFFFF80000180UL.toLong(), cpu.pc) // Nested exception uses general vector.
        assertEquals(0x2L, cpu.cop0[12] and 0x2L) // EXL remains set.
    }


    @Test fun tlbInvalidStoreUsesStoreExceptionCodeAndGeneralVector() {
        val memory = N64Memory(64)
        memory.write32(0, iType(0x2B, 1, 2, 0)) // sw $v0, 0($at)
        val cpu = R4300Cpu(memory)
        cpu.tlbEnabled = true
        // Map the instruction-fetch page so execution reaches the store.
        cpu.tlb.write(0, R4300Tlb.Entry(
            virtualPagePairBase = 0L,
            asid = 0,
            evenPage = R4300Tlb.Page(0, valid = true, dirty = true, global = true),
            oddPage = R4300Tlb.Page(1, valid = true, dirty = true, global = true)
        ))
        val faultAddress = 0x00401234L
        cpu.tlb.write(1, R4300Tlb.Entry(
            virtualPagePairBase = faultAddress and R4300Tlb.PAGE_PAIR_MASK,
            asid = 0,
            evenPage = R4300Tlb.Page(4, valid = true, dirty = true, global = true),
            oddPage = R4300Tlb.Page(5, valid = false, dirty = true, global = true)
        ))
        cpu.gpr[1] = faultAddress
        cpu.gpr[2] = 0x12345678L
        cpu.setProgramCounter(0)

        cpu.step()

        assertEquals(faultAddress, cpu.cop0[8]) // BadVAddr.
        assertEquals(3L, (cpu.cop0[13] ushr 2) and 0x1FL) // Invalid store reports TLBS.
        assertEquals(0L, cpu.cop0[14]) // EPC points to the store instruction.
        assertEquals(0xFFFFFFFF80000180UL.toLong(), cpu.pc) // Invalid page uses general vector.
        assertEquals(0x2L, cpu.cop0[12] and 0x2L) // EXL set.
        assertEquals(0L, memory.read32(8).toLong()) // Faulting store did not write memory.
    }


    @Test fun tlbMissAndInvalidPageUseBootVectorsWhenBevIsSet() {
        val missMemory = N64Memory(64)
        val missCpu = R4300Cpu(missMemory)
        missCpu.tlbEnabled = true
        missCpu.cop0[12] = 1L shl 22 // Status.BEV selects boot exception vectors.
        val missAddress = 0x00400000L
        missCpu.setProgramCounter(missAddress)

        missCpu.step()

        assertEquals(missAddress, missCpu.cop0[8]) // BadVAddr.
        assertEquals(0xFFFFFFFFBFC00200UL.toLong(), missCpu.pc) // Boot TLB refill vector.

        val invalidMemory = N64Memory(64)
        val invalidCpu = R4300Cpu(invalidMemory)
        invalidCpu.tlbEnabled = true
        invalidCpu.cop0[12] = 1L shl 22
        val invalidAddress = 0x00600000L
        invalidCpu.tlb.write(0, R4300Tlb.Entry(
            virtualPagePairBase = invalidAddress and R4300Tlb.PAGE_PAIR_MASK,
            asid = 0,
            evenPage = R4300Tlb.Page(4, valid = false, dirty = true, global = true),
            oddPage = R4300Tlb.Page(5, valid = true, dirty = true, global = true)
        ))
        invalidCpu.setProgramCounter(invalidAddress)

        invalidCpu.step()

        assertEquals(invalidAddress, invalidCpu.cop0[8]) // BadVAddr.
        assertEquals(0xFFFFFFFFBFC00380UL.toLong(), invalidCpu.pc) // Boot general vector.
    }



    @Test fun tlbMissWithBevAndExlSetUsesBootGeneralVectorAndPreservesEpc() {
        val memory = N64Memory(64)
        val cpu = R4300Cpu(memory)
        cpu.tlbEnabled = true
        val faultAddress = 0x00800000L
        val previousEpc = 0x12345678L
        cpu.cop0[12] = (1L shl 22) or 0x2L // BEV and EXL already set.
        cpu.cop0[14] = previousEpc
        cpu.setProgramCounter(faultAddress)

        cpu.step()

        assertEquals(faultAddress, cpu.cop0[8]) // BadVAddr records the new fault.
        assertEquals(previousEpc, cpu.cop0[14]) // Nested exception preserves EPC.
        assertEquals(2L, (cpu.cop0[13] ushr 2) and 0x1FL) // TLBL.
        assertEquals(0xFFFFFFFFBFC00380UL.toLong(), cpu.pc) // BEV + EXL uses boot general vector.
        assertEquals((1L shl 22) or 0x2L, cpu.cop0[12] and ((1L shl 22) or 0x2L))
    }



    @Test fun tlbMissInDelaySlotWithBevUsesBootRefillVectorAndSetsBranchMetadata() {
        val memory = N64Memory(64)
        memory.write32(0, iType(0x04, 0, 0, 2)) // beq $zero, $zero, target
        memory.write32(4, iType(0x23, 1, 2, 0)) // lw $v0, 0($at) in delay slot
        val cpu = R4300Cpu(memory)
        cpu.tlbEnabled = true
        cpu.cop0[12] = 1L shl 22 // BEV selects boot exception vectors.
        cpu.tlb.write(0, R4300Tlb.Entry(
            virtualPagePairBase = 0L,
            asid = 0,
            evenPage = R4300Tlb.Page(0, valid = true, dirty = true, global = true),
            oddPage = R4300Tlb.Page(1, valid = true, dirty = true, global = true)
        ))
        val faultAddress = 0x00401234L
        cpu.gpr[1] = faultAddress
        cpu.setProgramCounter(0)

        cpu.step() // Execute branch and establish the delay slot.
        cpu.step() // Load faults in the delay slot.

        assertEquals(faultAddress, cpu.cop0[8]) // BadVAddr.
        assertEquals(0L, cpu.cop0[14]) // EPC identifies the branch instruction.
        assertEquals(1L, (cpu.cop0[13] ushr 31) and 1L) // Cause.BD.
        assertEquals(2L, (cpu.cop0[13] ushr 2) and 0x1FL) // TLBL.
        assertEquals(0xFFFFFFFFBFC00200UL.toLong(), cpu.pc) // Boot TLB refill vector.
        assertEquals((1L shl 22) or 0x2L, cpu.cop0[12] and ((1L shl 22) or 0x2L))
    }


    @Test fun tlbInvalidPageInDelaySlotWithBevUsesBootGeneralVectorAndSetsBranchMetadata() {
        val memory = N64Memory(64)
        memory.write32(0, iType(0x04, 0, 0, 2)) // beq $zero, $zero, target
        memory.write32(4, iType(0x23, 1, 2, 0)) // lw $v0, 0($at) in delay slot
        val cpu = R4300Cpu(memory)
        cpu.tlbEnabled = true
        cpu.cop0[12] = 1L shl 22 // BEV selects boot exception vectors.
        cpu.tlb.write(0, R4300Tlb.Entry(
            virtualPagePairBase = 0L,
            asid = 0,
            evenPage = R4300Tlb.Page(0, valid = true, dirty = true, global = true),
            oddPage = R4300Tlb.Page(1, valid = true, dirty = true, global = true)
        ))
        val faultAddress = 0x00401234L
        cpu.tlb.write(1, R4300Tlb.Entry(
            virtualPagePairBase = faultAddress and R4300Tlb.PAGE_PAIR_MASK,
            asid = 0,
            evenPage = R4300Tlb.Page(2, valid = true, dirty = true, global = true),
            oddPage = R4300Tlb.Page(3, valid = false, dirty = true, global = true)
        ))
        cpu.gpr[1] = faultAddress
        cpu.setProgramCounter(0)

        cpu.step() // Execute branch and establish the delay slot.
        cpu.step() // Load hits an invalid TLB page in the delay slot.

        assertEquals(faultAddress, cpu.cop0[8]) // BadVAddr.
        assertEquals(0L, cpu.cop0[14]) // EPC identifies the branch instruction.
        assertEquals(1L, (cpu.cop0[13] ushr 31) and 1L) // Cause.BD.
        assertEquals(2L, (cpu.cop0[13] ushr 2) and 0x1FL) // TLBL.
        assertEquals(0xFFFFFFFFBFC00380UL.toLong(), cpu.pc) // Boot general vector, not refill.
        assertEquals((1L shl 22) or 0x2L, cpu.cop0[12] and ((1L shl 22) or 0x2L))
    }


    @Test fun tlbInvalidStoreInDelaySlotWithBevUsesBootGeneralVectorAndSetsBranchMetadata() {
        val memory = N64Memory(64)
        memory.write32(0, iType(0x04, 0, 0, 2)) // beq $zero, $zero, target
        memory.write32(4, iType(0x2B, 1, 2, 0)) // sw $v0, 0($at) in delay slot
        val cpu = R4300Cpu(memory)
        cpu.tlbEnabled = true
        cpu.cop0[12] = 1L shl 22 // BEV selects boot exception vectors.
        cpu.tlb.write(0, R4300Tlb.Entry(
            virtualPagePairBase = 0L,
            asid = 0,
            evenPage = R4300Tlb.Page(0, valid = true, dirty = true, global = true),
            oddPage = R4300Tlb.Page(1, valid = true, dirty = true, global = true)
        ))
        val faultAddress = 0x00401234L
        cpu.tlb.write(1, R4300Tlb.Entry(
            virtualPagePairBase = faultAddress and R4300Tlb.PAGE_PAIR_MASK,
            asid = 0,
            evenPage = R4300Tlb.Page(2, valid = true, dirty = true, global = true),
            oddPage = R4300Tlb.Page(3, valid = false, dirty = true, global = true)
        ))
        cpu.gpr[1] = faultAddress
        cpu.gpr[2] = 0x12345678L
        cpu.setProgramCounter(0)

        cpu.step() // Execute branch and establish the delay slot.
        cpu.step() // Store hits an invalid TLB page in the delay slot.

        assertEquals(faultAddress, cpu.cop0[8]) // BadVAddr.
        assertEquals(0L, cpu.cop0[14]) // EPC identifies the branch instruction.
        assertEquals(1L, (cpu.cop0[13] ushr 31) and 1L) // Cause.BD.
        assertEquals(3L, (cpu.cop0[13] ushr 2) and 0x1FL) // TLBS.
        assertEquals(0xFFFFFFFFBFC00380UL.toLong(), cpu.pc) // Boot general vector.
        assertEquals((1L shl 22) or 0x2L, cpu.cop0[12] and ((1L shl 22) or 0x2L))
        assertEquals(0L, memory.read32(8).toLong()) // Faulting store must not modify memory.
    }


    @Test fun tlbMissStoreInDelaySlotWithBevUsesBootRefillVectorAndSetsBranchMetadata() {
        val memory = N64Memory(64)
        memory.write32(0, iType(0x04, 0, 0, 2)) // beq $zero, $zero, target
        memory.write32(4, iType(0x2B, 1, 2, 0)) // sw $v0, 0($at) in delay slot
        val cpu = R4300Cpu(memory)
        cpu.tlbEnabled = true
        cpu.cop0[12] = 1L shl 22 // BEV selects boot exception vectors.
        cpu.tlb.write(0, R4300Tlb.Entry(
            virtualPagePairBase = 0L,
            asid = 0,
            evenPage = R4300Tlb.Page(0, valid = true, dirty = true, global = true),
            oddPage = R4300Tlb.Page(1, valid = true, dirty = true, global = true)
        ))
        val faultAddress = 0x00801234L
        cpu.gpr[1] = faultAddress
        cpu.gpr[2] = 0x12345678L
        cpu.setProgramCounter(0)

        cpu.step() // Execute branch and establish the delay slot.
        cpu.step() // Store misses in the TLB in the delay slot.

        assertEquals(faultAddress, cpu.cop0[8]) // BadVAddr.
        assertEquals(0L, cpu.cop0[14]) // EPC identifies the branch instruction.
        assertEquals(1L, (cpu.cop0[13] ushr 31) and 1L) // Cause.BD.
        assertEquals(3L, (cpu.cop0[13] ushr 2) and 0x1FL) // TLBS.
        assertEquals(0xFFFFFFFFBFC00200UL.toLong(), cpu.pc) // Boot TLB refill vector.
        assertEquals((1L shl 22) or 0x2L, cpu.cop0[12] and ((1L shl 22) or 0x2L))
        assertEquals(0L, memory.read32(8).toLong()) // Faulting store must not modify memory.
    }


    @Test fun tlbModifiedStoreInDelaySlotWithBevUsesBootGeneralVectorAndSetsBranchMetadata() {
        val memory = N64Memory(64)
        memory.write32(0, iType(0x04, 0, 0, 2)) // beq $zero, $zero, target
        memory.write32(4, iType(0x2B, 1, 2, 0)) // sw $v0, 0($at) in delay slot
        val cpu = R4300Cpu(memory)
        cpu.tlbEnabled = true
        cpu.cop0[12] = 1L shl 22 // BEV selects boot exception vectors.
        cpu.tlb.write(0, R4300Tlb.Entry(
            virtualPagePairBase = 0L,
            asid = 0,
            evenPage = R4300Tlb.Page(0, valid = true, dirty = true, global = true),
            oddPage = R4300Tlb.Page(1, valid = true, dirty = true, global = true)
        ))
        val faultAddress = 0x00401234L
        cpu.tlb.write(1, R4300Tlb.Entry(
            virtualPagePairBase = faultAddress and R4300Tlb.PAGE_PAIR_MASK,
            asid = 0,
            evenPage = R4300Tlb.Page(2, valid = true, dirty = true, global = true),
            oddPage = R4300Tlb.Page(3, valid = true, dirty = false, global = true)
        ))
        cpu.gpr[1] = faultAddress
        cpu.gpr[2] = 0x12345678L
        cpu.setProgramCounter(0)

        cpu.step() // Execute branch and establish the delay slot.
        cpu.step() // Store triggers TLB Modified in the delay slot.

        assertEquals(faultAddress, cpu.cop0[8]) // BadVAddr.
        assertEquals(0L, cpu.cop0[14]) // EPC identifies the branch instruction.
        assertEquals(1L, (cpu.cop0[13] ushr 31) and 1L) // Cause.BD.
        assertEquals(1L, (cpu.cop0[13] ushr 2) and 0x1FL) // TLB Modified.
        assertEquals(0xFFFFFFFFBFC00380UL.toLong(), cpu.pc) // Modified uses boot general vector.
        assertEquals((1L shl 22) or 0x2L, cpu.cop0[12] and ((1L shl 22) or 0x2L))
        assertEquals(0L, memory.read32(8).toLong()) // Faulting store must not modify memory.
    }


    @Test fun tlbInvalidInstructionFetchInDelaySlotWithBevUsesBootGeneralVectorAndSetsBranchMetadata() {
        val memory = N64Memory(8192)
        memory.write32(0x0FFC, iType(0x04, 0, 0, 2)) // Branch at the last word of the even page.
        val cpu = R4300Cpu(memory)
        cpu.tlbEnabled = true
        cpu.cop0[12] = 1L shl 22 // BEV selects boot exception vectors.
        cpu.tlb.write(0, R4300Tlb.Entry(
            virtualPagePairBase = 0L,
            asid = 0,
            evenPage = R4300Tlb.Page(0, valid = true, dirty = true, global = true),
            oddPage = R4300Tlb.Page(1, valid = false, dirty = true, global = true)
        ))
        cpu.setProgramCounter(0x0FFCL)

        cpu.step() // Branch executes; the delay-slot fetch is on the invalid odd page.
        cpu.step() // Instruction fetch faults before the delay-slot instruction can execute.

        assertEquals(0x1000L, cpu.cop0[8]) // BadVAddr is the delay-slot fetch address.
        assertEquals(0x0FFCL, cpu.cop0[14]) // EPC identifies the branch instruction.
        assertEquals(1L, (cpu.cop0[13] ushr 31) and 1L) // Cause.BD.
        assertEquals(2L, (cpu.cop0[13] ushr 2) and 0x1FL) // TLBL.
        assertEquals(0xFFFFFFFFBFC00380UL.toLong(), cpu.pc) // Invalid page uses boot general vector.
        assertEquals((1L shl 22) or 0x2L, cpu.cop0[12] and ((1L shl 22) or 0x2L))
    }


    @Test fun tlbMissInstructionFetchInDelaySlotWithBevUsesBootRefillVectorAndSetsBranchMetadata() {
        val memory = N64Memory(8192)
        memory.write32(0x1FFC, iType(0x04, 0, 0, 2)) // Branch at the last word of the mapped pair.
        val cpu = R4300Cpu(memory)
        cpu.tlbEnabled = true
        cpu.cop0[12] = 1L shl 22 // BEV selects boot exception vectors.
        cpu.tlb.write(0, R4300Tlb.Entry(
            virtualPagePairBase = 0L,
            asid = 0,
            evenPage = R4300Tlb.Page(0, valid = true, dirty = true, global = true),
            oddPage = R4300Tlb.Page(1, valid = true, dirty = true, global = true)
        ))
        cpu.setProgramCounter(0x1FFCL)

        cpu.step() // Branch executes; its delay-slot address belongs to an unmapped pair.
        cpu.step() // Instruction fetch misses in the TLB during the delay slot.

        assertEquals(0x2000L, cpu.cop0[8]) // BadVAddr is the delay-slot fetch address.
        assertEquals(0x1FFCL, cpu.cop0[14]) // EPC identifies the branch instruction.
        assertEquals(1L, (cpu.cop0[13] ushr 31) and 1L) // Cause.BD.
        assertEquals(2L, (cpu.cop0[13] ushr 2) and 0x1FL) // TLBL.
        assertEquals(0xFFFFFFFFBFC00200UL.toLong(), cpu.pc) // TLB miss uses boot refill vector.
        assertEquals((1L shl 22) or 0x2L, cpu.cop0[12] and ((1L shl 22) or 0x2L))
    }



    @Test fun tlbMissInstructionFetchInDelaySlotWithBevAndExlUsesBootGeneralVectorAndPreservesEpc() {
        val memory = N64Memory(8192)
        memory.write32(0x1FFC, iType(0x04, 0, 0, 2)) // Branch at the last word of the mapped pair.
        val cpu = R4300Cpu(memory)
        cpu.tlbEnabled = true
        val previousEpc = 0x12345678L
        cpu.cop0[12] = (1L shl 22) or 0x2L // BEV and EXL are already set.
        cpu.cop0[14] = previousEpc
        cpu.tlb.write(0, R4300Tlb.Entry(
            virtualPagePairBase = 0L,
            asid = 0,
            evenPage = R4300Tlb.Page(0, valid = true, dirty = true, global = true),
            oddPage = R4300Tlb.Page(1, valid = true, dirty = true, global = true)
        ))
        cpu.setProgramCounter(0x1FFCL)

        cpu.step() // Branch executes while EXL is already set.
        cpu.step() // Delay-slot instruction fetch misses in the TLB.

        assertEquals(0x2000L, cpu.cop0[8]) // BadVAddr is the delay-slot fetch address.
        assertEquals(previousEpc, cpu.cop0[14]) // Nested exception preserves the original EPC.
        assertEquals(1L, (cpu.cop0[13] ushr 31) and 1L) // Cause.BD still identifies the delay slot.
        assertEquals(2L, (cpu.cop0[13] ushr 2) and 0x1FL) // TLBL.
        assertEquals(0xFFFFFFFFBFC00380UL.toLong(), cpu.pc) // BEV + EXL selects boot general vector.
        assertEquals((1L shl 22) or 0x2L, cpu.cop0[12] and ((1L shl 22) or 0x2L))
    }



    @Test fun executesInstructionFetchedThroughValidOddTlbPage() {
        val memory = N64Memory(8192)
        memory.write32(0x1000, iType(0x09, 0, 2, 42)) // addiu $v0, $zero, 42 at physical odd-page frame.
        val cpu = R4300Cpu(memory)
        cpu.tlbEnabled = true
        cpu.tlb.write(0, R4300Tlb.Entry(
            virtualPagePairBase = 0x00400000L,
            asid = 0,
            evenPage = R4300Tlb.Page(0, valid = true, dirty = true, global = true),
            oddPage = R4300Tlb.Page(1, valid = true, dirty = true, global = true)
        ))
        cpu.setProgramCounter(0x00401000L)

        cpu.step()

        assertEquals(42L, cpu.gpr[2]) // Instruction was fetched and executed from the odd mapped page.
        assertEquals(0x00401004L, cpu.pc) // PC advances in the virtual address space.
        assertEquals(0L, cpu.cop0[13] and (0x1FL shl 2)) // No exception was raised.
    }



    @Test fun loadsWordThroughValidOddTlbPage() {
        val memory = N64Memory(8192)
        memory.write32(0, iType(0x23, 1, 2, 0)) // lw $v0, 0($at)
        memory.write32(0x1000, 0x12345678)
        val cpu = R4300Cpu(memory)
        cpu.tlbEnabled = true
        cpu.tlb.write(0, R4300Tlb.Entry(
            virtualPagePairBase = 0L,
            asid = 0,
            evenPage = R4300Tlb.Page(0, valid = true, dirty = true, global = true),
            oddPage = R4300Tlb.Page(1, valid = true, dirty = true, global = true)
        ))
        cpu.tlb.write(1, R4300Tlb.Entry(
            virtualPagePairBase = 0x00400000L,
            asid = 0,
            evenPage = R4300Tlb.Page(2, valid = true, dirty = true, global = true),
            oddPage = R4300Tlb.Page(1, valid = true, dirty = true, global = true)
        ))
        cpu.gpr[1] = 0x00401000L
        cpu.setProgramCounter(0)

        cpu.step()

        assertEquals(0x12345678L, cpu.gpr[2]) // Odd virtual page maps to physical frame 1.
        assertEquals(4L, cpu.pc)
        assertEquals(0L, cpu.cop0[13] and (0x1FL shl 2)) // No exception was raised.
    }


    @Test fun storesWordThroughValidOddTlbPage() {
        val memory = N64Memory(8192)
        memory.write32(0, iType(0x2B, 1, 2, 0)) // sw $v0, 0($at)
        memory.write32(0x1000, 0)
        val cpu = R4300Cpu(memory)
        cpu.tlbEnabled = true
        cpu.tlb.write(0, R4300Tlb.Entry(
            virtualPagePairBase = 0L,
            asid = 0,
            evenPage = R4300Tlb.Page(0, valid = true, dirty = true, global = true),
            oddPage = R4300Tlb.Page(1, valid = true, dirty = true, global = true)
        ))
        cpu.tlb.write(1, R4300Tlb.Entry(
            virtualPagePairBase = 0x00400000L,
            asid = 0,
            evenPage = R4300Tlb.Page(2, valid = true, dirty = true, global = true),
            oddPage = R4300Tlb.Page(1, valid = true, dirty = true, global = true)
        ))
        cpu.gpr[1] = 0x00401000L
        cpu.gpr[2] = 0x89ABCDEFu.toLong()
        cpu.setProgramCounter(0)

        cpu.step()

        assertEquals(0x89ABCDEFu.toLong(), memory.read32(0x1000).toUInt().toLong())
        assertEquals(4L, cpu.pc)
        assertEquals(0L, cpu.cop0[13] and (0x1FL shl 2)) // No exception was raised.
    }


    @Test fun loadsWordSignExtendsNegativeValueThroughValidOddTlbPage() {
        val memory = N64Memory(8192)
        memory.write32(0, iType(0x23, 1, 2, 0)) // lw $v0, 0($at)
        memory.write32(0x1000, 0x80000001.toInt())
        val cpu = R4300Cpu(memory)
        cpu.tlbEnabled = true
        cpu.tlb.write(0, R4300Tlb.Entry(
            virtualPagePairBase = 0x00400000L,
            asid = 0,
            evenPage = R4300Tlb.Page(0, valid = true, dirty = true, global = true),
            oddPage = R4300Tlb.Page(1, valid = true, dirty = true, global = true)
        ))
        cpu.gpr[1] = 0x00401000L
        cpu.setProgramCounter(0x00400000L) // Fetch LW through the mapped even page.

        cpu.step()

        assertEquals(0xFFFFFFFF80000001UL.toLong(), cpu.gpr[2])
        assertEquals(0x00400004L, cpu.pc)
        assertEquals(0L, cpu.cop0[13] and (0x1FL shl 2)) // No exception was raised.
    }


    @Test fun loadsHalfwordZeroExtendsThroughValidOddTlbPage() {
        val memory = N64Memory(8192)
        memory.write32(0, iType(0x25, 1, 2, 0)) // lhu $v0, 0($at)
        memory.write16(0x1000, 0xF234)
        val cpu = R4300Cpu(memory)
        cpu.tlbEnabled = true
        cpu.tlb.write(0, R4300Tlb.Entry(
            virtualPagePairBase = 0x00400000L,
            asid = 0,
            evenPage = R4300Tlb.Page(0, valid = true, dirty = true, global = true),
            oddPage = R4300Tlb.Page(1, valid = true, dirty = true, global = true)
        ))
        cpu.gpr[1] = 0x00401000L
        cpu.setProgramCounter(0x00400000L)

        cpu.step()

        assertEquals(0xF234L, cpu.gpr[2])
        assertEquals(0x00400004L, cpu.pc)
        assertEquals(0L, cpu.cop0[13] and (0x1FL shl 2)) // No exception was raised.
    }



    @Test fun loadsByteZeroExtendsThroughValidOddTlbPage() {
        val memory = N64Memory(8192)
        memory.write32(0, iType(0x24, 1, 2, 0)) // lbu $v0, 0($at)
        memory.write8(0x1000, 0xE7)
        val cpu = R4300Cpu(memory)
        cpu.tlbEnabled = true
        cpu.tlb.write(0, R4300Tlb.Entry(
            virtualPagePairBase = 0x00400000L,
            asid = 0,
            evenPage = R4300Tlb.Page(0, valid = true, dirty = true, global = true),
            oddPage = R4300Tlb.Page(1, valid = true, dirty = true, global = true)
        ))
        cpu.gpr[1] = 0x00401000L
        cpu.setProgramCounter(0x00400000L)

        cpu.step()

        assertEquals(0xE7L, cpu.gpr[2])
        assertEquals(0x00400004L, cpu.pc)
        assertEquals(0L, cpu.cop0[13] and (0x1FL shl 2)) // No exception was raised.
    }


    @Test fun loadsByteSignExtendsNegativeValueThroughValidOddTlbPage() {
        val memory = N64Memory(8192)
        memory.write32(0, iType(0x20, 1, 2, 0)) // lb $v0, 0($at)
        memory.write8(0x1000, 0xE7)
        val cpu = R4300Cpu(memory)
        cpu.tlbEnabled = true
        cpu.tlb.write(0, R4300Tlb.Entry(
            virtualPagePairBase = 0x00400000L,
            asid = 0,
            evenPage = R4300Tlb.Page(0, valid = true, dirty = true, global = true),
            oddPage = R4300Tlb.Page(1, valid = true, dirty = true, global = true)
        ))
        cpu.gpr[1] = 0x00401000L
        cpu.setProgramCounter(0x00400000L)

        cpu.step()

        assertEquals(0xFFFFFFFFFFFFFFE7UL.toLong(), cpu.gpr[2])
        assertEquals(0x00400004L, cpu.pc)
        assertEquals(0L, cpu.cop0[13] and (0x1FL shl 2)) // No exception was raised.
    }


    @Test fun storesByteThroughValidOddTlbPageWithoutChangingNeighborBytes() {
        val memory = N64Memory(8192)
        memory.write32(0, iType(0x28, 1, 2, 1)) // sb $v0, 1($at)
        memory.write32(0x1000, 0x12345678)
        val cpu = R4300Cpu(memory)
        cpu.tlbEnabled = true
        cpu.tlb.write(0, R4300Tlb.Entry(
            virtualPagePairBase = 0L,
            asid = 0,
            evenPage = R4300Tlb.Page(0, valid = true, dirty = true, global = true),
            oddPage = R4300Tlb.Page(1, valid = true, dirty = true, global = true)
        ))
        cpu.tlb.write(1, R4300Tlb.Entry(
            virtualPagePairBase = 0x00400000L,
            asid = 0,
            evenPage = R4300Tlb.Page(2, valid = true, dirty = true, global = true),
            oddPage = R4300Tlb.Page(1, valid = true, dirty = true, global = true)
        ))
        cpu.gpr[1] = 0x00401000L
        cpu.gpr[2] = 0x1ABL
        cpu.setProgramCounter(0)

        cpu.step()

        assertEquals(0x12AB5678L, memory.read32(0x1000).toLong() and 0xFFFFFFFFL)
        assertEquals(4L, cpu.pc)
        assertEquals(0L, cpu.cop0[13] and (0x1FL shl 2)) // No exception was raised.
    }


    @Test fun byteStoreToInvalidOddTlbPageRaisesStoreExceptionWithoutWritingMemory() {
        val memory = N64Memory(8192)
        memory.write32(0, iType(0x28, 1, 2, 1)) // sb $v0, 1($at)
        memory.write32(0x1000, 0x12345678)
        val cpu = R4300Cpu(memory)
        cpu.tlbEnabled = true
        cpu.tlb.write(0, R4300Tlb.Entry(
            virtualPagePairBase = 0L,
            asid = 0,
            evenPage = R4300Tlb.Page(0, valid = true, dirty = true, global = true),
            oddPage = R4300Tlb.Page(1, valid = true, dirty = true, global = true)
        ))
        cpu.tlb.write(1, R4300Tlb.Entry(
            virtualPagePairBase = 0x00400000L,
            asid = 0,
            evenPage = R4300Tlb.Page(2, valid = true, dirty = true, global = true),
            oddPage = R4300Tlb.Page(1, valid = false, dirty = true, global = true)
        ))
        val faultAddress = 0x00401001L
        cpu.gpr[1] = 0x00401000L
        cpu.gpr[2] = 0xABL
        cpu.setProgramCounter(0)

        cpu.step()

        assertEquals(faultAddress, cpu.cop0[8]) // BadVAddr.
        assertEquals(3L, (cpu.cop0[13] ushr 2) and 0x1FL) // Invalid store reports TLBS.
        assertEquals(0xFFFFFFFF80000180UL.toLong(), cpu.pc) // General exception vector.
        assertEquals(0x12345678L, memory.read32(0x1000).toLong() and 0xFFFFFFFFL)
    }


    @Test fun byteStoreToCleanOddTlbPageRaisesModifiedWithoutChangingMemory() {
        val memory = N64Memory(16384)
        memory.write32(0, iType(0x28, 1, 2, 1)) // sb $v0, 1($at)
        memory.write32(0x3000, 0x12345678)
        val cpu = R4300Cpu(memory)
        cpu.tlbEnabled = true
        cpu.tlb.write(0, R4300Tlb.Entry(
            virtualPagePairBase = 0L,
            asid = 0,
            evenPage = R4300Tlb.Page(0, valid = true, dirty = true, global = true),
            oddPage = R4300Tlb.Page(1, valid = true, dirty = true, global = true)
        ))
        cpu.tlb.write(1, R4300Tlb.Entry(
            virtualPagePairBase = 0x00400000L,
            asid = 0,
            evenPage = R4300Tlb.Page(2, valid = true, dirty = true, global = true),
            oddPage = R4300Tlb.Page(3, valid = true, dirty = false, global = true)
        ))
        val faultAddress = 0x00401001L
        cpu.gpr[1] = 0x00401000L
        cpu.gpr[2] = 0xABL
        cpu.setProgramCounter(0)

        cpu.step()

        assertEquals(faultAddress, cpu.cop0[8]) // BadVAddr.
        assertEquals(1L, (cpu.cop0[13] ushr 2) and 0x1FL) // TLB Modified.
        assertEquals(0xFFFFFFFF80000180UL.toLong(), cpu.pc) // General exception vector.
        assertEquals(0x12345678L, memory.read32(0x3000).toLong() and 0xFFFFFFFFL)
    }


    @Test fun storesHalfwordThroughValidOddTlbPageWithoutChangingNeighborBytes() {
        val memory = N64Memory(8192)
        memory.write32(0, iType(0x29, 1, 2, 2)) // sh $v0, 2($at)
        memory.write32(0x1000, 0x12345678)
        val cpu = R4300Cpu(memory)
        cpu.tlbEnabled = true
        cpu.tlb.write(0, R4300Tlb.Entry(
            virtualPagePairBase = 0L,
            asid = 0,
            evenPage = R4300Tlb.Page(0, valid = true, dirty = true, global = true),
            oddPage = R4300Tlb.Page(1, valid = true, dirty = true, global = true)
        ))
        cpu.tlb.write(1, R4300Tlb.Entry(
            virtualPagePairBase = 0x00400000L,
            asid = 0,
            evenPage = R4300Tlb.Page(2, valid = true, dirty = true, global = true),
            oddPage = R4300Tlb.Page(1, valid = true, dirty = true, global = true)
        ))
        cpu.gpr[1] = 0x00401000L
        cpu.gpr[2] = 0xABCDL
        cpu.setProgramCounter(0)

        cpu.step()

        assertEquals(0x1234ABCDL, memory.read32(0x1000).toLong() and 0xFFFFFFFFL)
        assertEquals(4L, cpu.pc)
        assertEquals(0L, cpu.cop0[13] and (0x1FL shl 2)) // No exception was raised.
    }


    @Test fun halfwordStoreToInvalidOddTlbPageRaisesStoreExceptionWithoutWritingMemory() {
        val memory = N64Memory(8192)
        memory.write32(0, iType(0x29, 1, 2, 2)) // sh $v0, 2($at)
        memory.write32(0x1000, 0x12345678)
        val cpu = R4300Cpu(memory)
        cpu.tlbEnabled = true
        cpu.tlb.write(0, R4300Tlb.Entry(
            virtualPagePairBase = 0L,
            asid = 0,
            evenPage = R4300Tlb.Page(0, valid = true, dirty = true, global = true),
            oddPage = R4300Tlb.Page(1, valid = true, dirty = true, global = true)
        ))
        cpu.tlb.write(1, R4300Tlb.Entry(
            virtualPagePairBase = 0x00400000L,
            asid = 0,
            evenPage = R4300Tlb.Page(2, valid = true, dirty = true, global = true),
            oddPage = R4300Tlb.Page(1, valid = false, dirty = true, global = true)
        ))
        val faultAddress = 0x00401002L
        cpu.gpr[1] = 0x00401000L
        cpu.gpr[2] = 0xABCDL
        cpu.setProgramCounter(0)

        cpu.step()

        assertEquals(faultAddress, cpu.cop0[8]) // BadVAddr.
        assertEquals(3L, (cpu.cop0[13] ushr 2) and 0x1FL) // Invalid store reports TLBS.
        assertEquals(0xFFFFFFFF80000180UL.toLong(), cpu.pc) // General exception vector.
        assertEquals(0x12345678L, memory.read32(0x1000).toLong() and 0xFFFFFFFFL)
    }


    @Test fun halfwordStoreToCleanOddTlbPageRaisesModifiedWithoutChangingMemory() {
        val memory = N64Memory(16384)
        memory.write32(0, iType(0x29, 1, 2, 2)) // sh $v0, 2($at)
        memory.write32(0x3000, 0x12345678)
        val cpu = R4300Cpu(memory)
        cpu.tlbEnabled = true
        cpu.tlb.write(0, R4300Tlb.Entry(
            virtualPagePairBase = 0L,
            asid = 0,
            evenPage = R4300Tlb.Page(0, valid = true, dirty = true, global = true),
            oddPage = R4300Tlb.Page(1, valid = true, dirty = true, global = true)
        ))
        cpu.tlb.write(1, R4300Tlb.Entry(
            virtualPagePairBase = 0x00400000L,
            asid = 0,
            evenPage = R4300Tlb.Page(2, valid = true, dirty = true, global = true),
            oddPage = R4300Tlb.Page(3, valid = true, dirty = false, global = true)
        ))
        val faultAddress = 0x00401002L
        cpu.gpr[1] = 0x00401000L
        cpu.gpr[2] = 0xABCDL
        cpu.setProgramCounter(0)

        cpu.step()

        assertEquals(faultAddress, cpu.cop0[8]) // BadVAddr.
        assertEquals(1L, (cpu.cop0[13] ushr 2) and 0x1FL) // TLB Modified.
        assertEquals(0xFFFFFFFF80000180UL.toLong(), cpu.pc) // General exception vector.
        assertEquals(0x12345678L, memory.read32(0x3000).toLong() and 0xFFFFFFFFL)
    }


    @Test fun halfwordStoreToOddAddressRaisesAddressStoreErrorWithoutWritingMemory() {
        val memory = N64Memory(64)
        memory.write32(0, iType(0x29, 1, 2, 1)) // sh $v0, 1($at) is misaligned.
        memory.write32(16, 0x12345678)
        val cpu = R4300Cpu(memory)
        cpu.gpr[1] = 16L
        cpu.gpr[2] = 0xABCDL
        cpu.setProgramCounter(0)

        cpu.step()

        assertEquals(17L, cpu.cop0[8]) // BadVAddr records the unaligned address.
        assertEquals(5L, (cpu.cop0[13] ushr 2) and 0x1FL) // AdES.
        assertEquals(0xFFFFFFFF80000180UL.toLong(), cpu.pc)
        assertEquals(0x12345678L, memory.read32(16).toLong() and 0xFFFFFFFFL)
    }


    @Test fun misalignedHalfwordStoreRaisesAddressErrorBeforeTlbTranslation() {
        val memory = N64Memory(8192)
        memory.write32(0, iType(0x29, 1, 2, 1)) // sh $v0, 1($at)
        memory.write32(0x1000, 0x12345678)
        val cpu = R4300Cpu(memory)
        cpu.tlbEnabled = true
        cpu.tlb.write(0, R4300Tlb.Entry(
            virtualPagePairBase = 0x00400000L,
            asid = 0,
            evenPage = R4300Tlb.Page(0, valid = true, dirty = true, global = true),
            oddPage = R4300Tlb.Page(1, valid = true, dirty = true, global = true)
        ))
        cpu.gpr[1] = 0x00801000L // The effective address is misaligned and unmapped.
        cpu.gpr[2] = 0xABCDL
        cpu.setProgramCounter(0x00400000L) // Fetch the instruction through the mapped page.

        cpu.step()

        assertEquals(0x00801001L, cpu.cop0[8]) // BadVAddr records the odd effective address.
        assertEquals(5L, (cpu.cop0[13] ushr 2) and 0x1FL) // AdES takes priority over a TLB miss.
        assertEquals(0xFFFFFFFF80000180UL.toLong(), cpu.pc)
        assertEquals(0x12345678L, memory.read32(0x1000).toLong() and 0xFFFFFFFFL)
    }


    @Test fun loadsHalfwordSignExtendsNegativeValueThroughValidOddTlbPage() {
        val memory = N64Memory(8192)
        memory.write32(0, iType(0x21, 1, 2, 0)) // lh $v0, 0($at)
        memory.write16(0x1000, 0xF234)
        val cpu = R4300Cpu(memory)
        cpu.tlbEnabled = true
        cpu.tlb.write(0, R4300Tlb.Entry(
            virtualPagePairBase = 0x00400000L,
            asid = 0,
            evenPage = R4300Tlb.Page(0, valid = true, dirty = true, global = true),
            oddPage = R4300Tlb.Page(1, valid = true, dirty = true, global = true)
        ))
        cpu.gpr[1] = 0x00401000L
        cpu.setProgramCounter(0x00400000L)

        cpu.step()

        assertEquals(0xFFFFFFFFFFFFF234UL.toLong(), cpu.gpr[2])
        assertEquals(0x00400004L, cpu.pc)
        assertEquals(0L, cpu.cop0[13] and (0x1FL shl 2)) // No exception was raised.
    }


    @Test fun halfwordLoadFromInvalidOddTlbPageRaisesLoadExceptionWithoutChangingDestination() {
        val memory = N64Memory(8192)
        memory.write32(0, iType(0x21, 1, 2, 0)) // lh $v0, 0($at)
        memory.write16(0x1000, 0xF234)
        val cpu = R4300Cpu(memory)
        cpu.tlbEnabled = true
        cpu.tlb.write(0, R4300Tlb.Entry(
            virtualPagePairBase = 0L,
            asid = 0,
            evenPage = R4300Tlb.Page(0, valid = true, dirty = true, global = true),
            oddPage = R4300Tlb.Page(1, valid = true, dirty = true, global = true)
        ))
        cpu.tlb.write(1, R4300Tlb.Entry(
            virtualPagePairBase = 0x00400000L,
            asid = 0,
            evenPage = R4300Tlb.Page(2, valid = true, dirty = true, global = true),
            oddPage = R4300Tlb.Page(1, valid = false, dirty = true, global = true)
        ))
        cpu.gpr[1] = 0x00401000L
        cpu.gpr[2] = 0x13579BDFL
        cpu.setProgramCounter(0)

        cpu.step()

        assertEquals(0x00401000L, cpu.cop0[8]) // BadVAddr.
        assertEquals(2L, (cpu.cop0[13] ushr 2) and 0x1FL) // TLBL.
        assertEquals(0xFFFFFFFF80000180UL.toLong(), cpu.pc) // General exception vector.
        assertEquals(0x13579BDFL, cpu.gpr[2]) // Destination remains unchanged.
    }


    @Test fun halfwordLoadMissRaisesTlbRefillExceptionWithoutChangingDestination() {
        val memory = N64Memory(64)
        memory.write32(0, iType(0x21, 1, 2, 0)) // lh $v0, 0($at)
        val cpu = R4300Cpu(memory)
        cpu.tlbEnabled = true
        cpu.tlb.write(0, R4300Tlb.Entry(
            virtualPagePairBase = 0L,
            asid = 0,
            evenPage = R4300Tlb.Page(0, valid = true, dirty = true, global = true),
            oddPage = R4300Tlb.Page(1, valid = true, dirty = true, global = true)
        ))
        val faultAddress = 0x00401234L
        cpu.gpr[1] = faultAddress
        cpu.gpr[2] = 0x13579BDFL
        cpu.setProgramCounter(0)

        cpu.step()

        assertEquals(faultAddress, cpu.cop0[8]) // BadVAddr.
        assertEquals(2L, (cpu.cop0[13] ushr 2) and 0x1FL) // TLBL.
        assertEquals(0xFFFFFFFF80000000UL.toLong(), cpu.pc) // TLB refill vector.
        assertEquals(0x13579BDFL, cpu.gpr[2]) // Destination remains unchanged.
    }


    @Test fun misalignedHalfwordLoadRaisesAddressErrorBeforeTlbTranslationAndPreservesDestination() {
        val memory = N64Memory(8192)
        memory.write32(0, iType(0x21, 1, 2, 1)) // lh $v0, 1($at): unaligned.
        memory.write32(0x1000, 0x12345678)
        val cpu = R4300Cpu(memory)
        cpu.tlbEnabled = true
        cpu.tlb.write(0, R4300Tlb.Entry(
            virtualPagePairBase = 0x00400000L,
            asid = 0,
            evenPage = R4300Tlb.Page(0, valid = true, dirty = true, global = true),
            oddPage = R4300Tlb.Page(1, valid = true, dirty = true, global = true)
        ))
        cpu.gpr[1] = 0x00801000L // Effective address is unaligned and unmapped.
        cpu.gpr[2] = 0x13579BDFL
        cpu.setProgramCounter(0x00400000L) // Fetch through the mapped instruction page.

        cpu.step()

        assertEquals(0x00801001L, cpu.cop0[8]) // BadVAddr keeps the effective address.
        assertEquals(4L, (cpu.cop0[13] ushr 2) and 0x1FL) // AdEL precedes the TLB miss.
        assertEquals(0xFFFFFFFF80000180UL.toLong(), cpu.pc)
        assertEquals(0x13579BDFL, cpu.gpr[2]) // Faulting load must not modify its destination.
        assertEquals(0x12345678L, memory.read32(0x1000).toLong() and 0xFFFFFFFFL)
    }


    @Test fun signedByteLoadFromInvalidOddTlbPageRaisesLoadExceptionWithoutChangingDestination() {
        val memory = N64Memory(8192)
        memory.write32(0, iType(0x20, 1, 2, 0)) // lb $v0, 0($at)
        memory.write8(0x1000, 0xE7)
        val cpu = R4300Cpu(memory)
        cpu.tlbEnabled = true
        cpu.tlb.write(0, R4300Tlb.Entry(
            virtualPagePairBase = 0L,
            asid = 0,
            evenPage = R4300Tlb.Page(0, valid = true, dirty = true, global = true),
            oddPage = R4300Tlb.Page(1, valid = true, dirty = true, global = true)
        ))
        cpu.tlb.write(1, R4300Tlb.Entry(
            virtualPagePairBase = 0x00400000L,
            asid = 0,
            evenPage = R4300Tlb.Page(2, valid = true, dirty = true, global = true),
            oddPage = R4300Tlb.Page(1, valid = false, dirty = true, global = true)
        ))
        val faultAddress = 0x00401000L
        cpu.gpr[1] = faultAddress
        cpu.gpr[2] = 0x13579BDFL
        cpu.setProgramCounter(0)

        cpu.step()

        assertEquals(faultAddress, cpu.cop0[8]) // BadVAddr.
        assertEquals(2L, (cpu.cop0[13] ushr 2) and 0x1FL) // TLBL.
        assertEquals(0xFFFFFFFF80000180UL.toLong(), cpu.pc) // General exception vector.
        assertEquals(0x13579BDFL, cpu.gpr[2]) // Faulting load preserves destination.
    }


    @Test fun unsignedByteLoadFromInvalidOddTlbPageRaisesLoadExceptionWithoutChangingDestination() {
        val memory = N64Memory(8192)
        memory.write32(0, iType(0x24, 1, 2, 0)) // lbu $v0, 0($at)
        memory.write8(0x1000, 0xE7)
        val cpu = R4300Cpu(memory)
        cpu.tlbEnabled = true
        cpu.tlb.write(0, R4300Tlb.Entry(
            virtualPagePairBase = 0L,
            asid = 0,
            evenPage = R4300Tlb.Page(0, valid = true, dirty = true, global = true),
            oddPage = R4300Tlb.Page(1, valid = true, dirty = true, global = true)
        ))
        cpu.tlb.write(1, R4300Tlb.Entry(
            virtualPagePairBase = 0x00400000L,
            asid = 0,
            evenPage = R4300Tlb.Page(2, valid = true, dirty = true, global = true),
            oddPage = R4300Tlb.Page(1, valid = false, dirty = true, global = true)
        ))
        val faultAddress = 0x00401000L
        cpu.gpr[1] = faultAddress
        cpu.gpr[2] = 0x2468ACE0L
        cpu.setProgramCounter(0)

        cpu.step()

        assertEquals(faultAddress, cpu.cop0[8]) // BadVAddr.
        assertEquals(2L, (cpu.cop0[13] ushr 2) and 0x1FL) // TLBL.
        assertEquals(0xFFFFFFFF80000180UL.toLong(), cpu.pc) // General exception vector.
        assertEquals(0x2468ACE0L, cpu.gpr[2]) // Faulting load preserves destination.
    }


    @Test fun wordLoadFromInvalidOddTlbPageRaisesLoadExceptionWithoutChangingDestination() {
        val memory = N64Memory(8192)
        memory.write32(0, iType(0x23, 1, 2, 0)) // lw $v0, 0($at)
        memory.write32(0x1000, 0x89ABCDEF.toInt())
        val cpu = R4300Cpu(memory)
        cpu.tlbEnabled = true
        cpu.tlb.write(0, R4300Tlb.Entry(
            virtualPagePairBase = 0L,
            asid = 0,
            evenPage = R4300Tlb.Page(0, valid = true, dirty = true, global = true),
            oddPage = R4300Tlb.Page(1, valid = true, dirty = true, global = true)
        ))
        cpu.tlb.write(1, R4300Tlb.Entry(
            virtualPagePairBase = 0x00400000L,
            asid = 0,
            evenPage = R4300Tlb.Page(2, valid = true, dirty = true, global = true),
            oddPage = R4300Tlb.Page(1, valid = false, dirty = true, global = true)
        ))
        val faultAddress = 0x00401000L
        cpu.gpr[1] = faultAddress
        cpu.gpr[2] = 0x13579BDFL
        cpu.setProgramCounter(0)

        cpu.step()

        assertEquals(faultAddress, cpu.cop0[8]) // BadVAddr.
        assertEquals(2L, (cpu.cop0[13] ushr 2) and 0x1FL) // TLBL.
        assertEquals(0xFFFFFFFF80000180UL.toLong(), cpu.pc) // General exception vector.
        assertEquals(0x13579BDFL, cpu.gpr[2]) // Faulting load preserves destination.
    }


    @Test fun wordLoadMissRaisesTlbRefillExceptionWithoutChangingDestination() {
        val memory = N64Memory(64)
        memory.write32(0, iType(0x23, 1, 2, 0)) // lw $v0, 0($at)
        val cpu = R4300Cpu(memory)
        cpu.tlbEnabled = true
        cpu.tlb.write(0, R4300Tlb.Entry(
            virtualPagePairBase = 0L,
            asid = 0,
            evenPage = R4300Tlb.Page(0, valid = true, dirty = true, global = true),
            oddPage = R4300Tlb.Page(1, valid = true, dirty = true, global = true)
        ))
        val faultAddress = 0x00401234L
        cpu.gpr[1] = faultAddress
        cpu.gpr[2] = 0x13579BDFL
        cpu.setProgramCounter(0)

        cpu.step()

        assertEquals(faultAddress, cpu.cop0[8]) // BadVAddr.
        assertEquals(2L, (cpu.cop0[13] ushr 2) and 0x1FL) // TLBL.
        assertEquals(0xFFFFFFFF80000000UL.toLong(), cpu.pc) // TLB refill vector.
        assertEquals(0x13579BDFL, cpu.gpr[2]) // Destination remains unchanged.
    }


    @Test fun misalignedWordLoadRaisesAddressErrorBeforeTlbTranslationAndPreservesDestination() {
        val memory = N64Memory(8192)
        memory.write32(0, iType(0x23, 1, 2, 1)) // lw $v0, 1($at): unaligned.
        memory.write32(0x1000, 0x12345678)
        val cpu = R4300Cpu(memory)
        cpu.tlbEnabled = true
        cpu.tlb.write(0, R4300Tlb.Entry(
            virtualPagePairBase = 0x00400000L,
            asid = 0,
            evenPage = R4300Tlb.Page(0, valid = true, dirty = true, global = true),
            oddPage = R4300Tlb.Page(1, valid = true, dirty = true, global = true)
        ))
        cpu.gpr[1] = 0x00801000L // Effective address is unaligned and unmapped.
        cpu.gpr[2] = 0x13579BDFL
        cpu.setProgramCounter(0x00400000L) // Fetch through the mapped instruction page.

        cpu.step()

        assertEquals(0x00801001L, cpu.cop0[8]) // BadVAddr keeps the odd effective address.
        assertEquals(4L, (cpu.cop0[13] ushr 2) and 0x1FL) // AdEL precedes the TLB miss.
        assertEquals(0xFFFFFFFF80000180UL.toLong(), cpu.pc)
        assertEquals(0x13579BDFL, cpu.gpr[2]) // Faulting load preserves destination.
        assertEquals(0x12345678L, memory.read32(0x1000).toLong() and 0xFFFFFFFFL)
    }


    @Test fun misalignedWordStoreRaisesAddressErrorBeforeTlbTranslationAndPreservesMemory() {
        val memory = N64Memory(8192)
        memory.write32(0, iType(0x2B, 1, 2, 1)) // sw $v0, 1($at): unaligned.
        memory.write32(0x1000, 0x12345678)
        val cpu = R4300Cpu(memory)
        cpu.tlbEnabled = true
        cpu.tlb.write(0, R4300Tlb.Entry(
            virtualPagePairBase = 0x00400000L,
            asid = 0,
            evenPage = R4300Tlb.Page(0, valid = true, dirty = true, global = true),
            oddPage = R4300Tlb.Page(1, valid = true, dirty = true, global = true)
        ))
        cpu.gpr[1] = 0x00801000L // Effective address is unaligned and unmapped.
        cpu.gpr[2] = 0xCAFEBABEL
        cpu.setProgramCounter(0x00400000L) // Fetch through the mapped instruction page.

        cpu.step()

        assertEquals(0x00801001L, cpu.cop0[8]) // BadVAddr records the effective address.
        assertEquals(5L, (cpu.cop0[13] ushr 2) and 0x1FL) // AdES precedes the TLB miss.
        assertEquals(0xFFFFFFFF80000180UL.toLong(), cpu.pc)
        assertEquals(0xCAFEBABEL, cpu.gpr[2]) // Source register is preserved.
        assertEquals(0x12345678L, memory.read32(0x1000).toLong() and 0xFFFFFFFFL)
    }


    @Test fun misalignedWordLoadInBranchDelaySlotSetsBadVAddrEpcAndCauseBd() {
        val memory = N64Memory(64)
        memory.write32(0, iType(0x04, 0, 0, 2)) // beq $zero, $zero, target
        memory.write32(4, iType(0x23, 1, 2, 0)) // lw $v0, 0($at) in the delay slot.
        val cpu = R4300Cpu(memory)
        cpu.gpr[1] = 1L // Misaligned address.
        cpu.gpr[2] = 0x13579BDFL
        cpu.setProgramCounter(0)

        cpu.step() // Execute branch and schedule the delay slot.
        cpu.step() // The delay-slot load raises AdEL.

        assertEquals(1L, cpu.cop0[8]) // BadVAddr is the effective load address.
        assertEquals(0L, cpu.cop0[14]) // EPC points back to the branch.
        assertEquals(1L, (cpu.cop0[13] ushr 31) and 1L) // Cause.BD.
        assertEquals(4L, (cpu.cop0[13] ushr 2) and 0x1FL) // AdEL.
        assertEquals(0xFFFFFFFF80000180UL.toLong(), cpu.pc)
        assertEquals(0x13579BDFL, cpu.gpr[2]) // Destination remains unchanged.
    }

}
