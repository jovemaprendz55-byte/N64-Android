package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class R4300CpuTest {
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

    @Test fun rejectsUnalignedInstructionAndWordAccesses() {
        val memory = N64Memory(64)
        memory.write32(0, iType(0x23, 1, 2, 0))
        val cpu = R4300Cpu(memory)
        assertThrows(IllegalArgumentException::class.java) { cpu.setProgramCounter(2) }
        cpu.setProgramCounter(0)
        cpu.gpr[1] = 1
        assertThrows(IllegalArgumentException::class.java) { cpu.step() }
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

    private fun cop0Co(funct: Int): Int = (0x10 shl 26) or (0x10 shl 21) or funct

    private fun iType(op: Int, rs: Int, rt: Int, imm: Int): Int =
        (op shl 26) or (rs shl 21) or (rt shl 16) or (imm and 0xFFFF)

    private fun rType(rs: Int, rt: Int, rd: Int, funct: Int): Int =
        (rs shl 21) or (rt shl 16) or (rd shl 11) or funct
}
