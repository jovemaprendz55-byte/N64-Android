package com.jovemaprendz55byte.n64android

import org.junit.Assert.assertEquals
import org.junit.Test

class R4300CpuTest {
    @Test fun luiSignExtendsTheResultTo64Bits() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x0F, 0, 1, 0x8006) // LUI r1, 0x8006 -> 0xFFFFFFFF80060000
        ))

        cpu.step()

        assertEquals(0xFFFF_FFFF_8006_0000UL.toLong(), cpu.register(1))
    }

    @Test fun cic6105Ipl3DiagnosticUsesDocumentedNtscHandoffAndImemStub() {
        val cpu = R4300Cpu()
        cpu.resetForCic6105Ipl3Diagnostic(0xFFFF_FFFF_A400_0040UL.toLong(), pal = false)

        assertEquals(0L, cpu.register(19)) // s3: cartridge
        assertEquals(1L, cpu.register(20)) // s4: NTSC
        assertEquals(0L, cpu.register(21)) // s5: cold reset
        assertEquals(0x91L, cpu.register(22)) // s6: CIC-6105 seed
        assertEquals(0L, cpu.register(23)) // s7
        assertEquals(0L, cpu.register(2)) // not an invented IPL3 seed
        assertEquals(0xFFFF_FFFF_A400_0040UL.toLong(), cpu.register(11))
        assertEquals(0xFFFF_FFFF_A400_1FF0UL.toLong(), cpu.register(29))
        assertEquals(0xFFFF_FFFF_A400_1550UL.toLong(), cpu.register(31))
        assertEquals(0x3400_0000L, cpu.readCp0ForTest(12))
        assertEquals(0x7006_E463L, cpu.readCp0ForTest(16))
        assertEquals(0x3C0D_BFC0, cpu.readWord(0x0400_1000))
        assertEquals(0x3C0B_B000, cpu.readWord(0x0400_101C))
        assertEquals(0xFFFF_FFFF_A400_0040UL.toLong(), cpu.pc)
    }

    @Test fun cic6105Ipl3DiagnosticUsesPalTvTypeButSameCicSeed() {
        val cpu = R4300Cpu()
        cpu.resetForCic6105Ipl3Diagnostic(0xFFFF_FFFF_A400_0040UL.toLong(), pal = true)

        assertEquals(0L, cpu.register(20))
        assertEquals(0x91L, cpu.register(22))
        assertEquals(0L, cpu.register(23))
        assertEquals(0xFFFF_FFFF_A400_1550UL.toLong(), cpu.register(31))
    }

    @Test fun tlbRequiredAddressDiagnosticIncludesTheFaultingVirtualAddress() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x0F, 0, 1, 0x4000), // r1 = 0x40000000 (TLB-mapped KUSEG)
            iType(0x23, 1, 2, 0)       // LW from an address requiring TLB translation
        ))

        cpu.step()
        try {
            cpu.step()
            throw AssertionError("Expected TLB translation failure")
        } catch (error: IllegalArgumentException) {
            org.junit.Assert.assertTrue(
                error.message.orEmpty().contains("40000000")
            )
            org.junit.Assert.assertTrue(
                error.message.orEmpty().contains("requires TLB translation")
            )
        }
    }

    @Test fun cpuLoadsAndStoresThroughRiRegisterRegion() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x0F, 0, 1, 0x0470), // r1 = RI register base
            iType(0x09, 0, 2, 0x1234), // r2 = test value
            iType(0x2B, 1, 2, 0), // SW r2, RI_MODE(r1)
            iType(0x23, 1, 3, 0), // LW r3, RI_MODE(r1)
            iType(0x09, 0, 4, 0x0056),
            iType(0x2B, 1, 4, 4), // SW r4, RI_CONFIG(r1)
            iType(0x23, 1, 5, 4) // LW r5, RI_CONFIG(r1)
        ))

        repeat(7) { cpu.step() }

        assertEquals(0x1234L, cpu.register(3))
        assertEquals(0x0056L, cpu.register(5))
        assertEquals(28L, cpu.pc)
    }

    @Test fun cpuCanAccessMiMemoryMappedRegisters() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x0F, 0, 1, 0x0430), // r1 = MI register base
            iType(0x09, 0, 2, 0x0010), // MI_MODE INIT_SET command
            iType(0x2B, 1, 2, 0),      // SW r2, MI_MODE(r1)
            iType(0x23, 1, 3, 0),      // LW r3, MI_MODE(r1)
            iType(0x23, 1, 4, 4)       // LW r4, MI_VERSION(r1)
        ))

        repeat(5) { cpu.step() }

        assertEquals(1L, cpu.register(3))
        assertEquals(0x02020102L, cpu.register(4))
    }

    @Test fun constructorRejectsInvalidRamSizes() {
        for (size in listOf(0, -4, 3)) {
            try {
                R4300Cpu(memorySize = size)
                throw AssertionError("Expected RAM size $size to be rejected")
            } catch (_: IllegalArgumentException) {
                // RAM must have a positive, word-aligned capacity.
            }
        }
    }

    @Test fun branchAndLinkWritesReturnAddressEvenWhenBranchIsNotTaken() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x01, 1, 0x10, 2), // BLTZAL r1, target; r1=0, so not taken, but link is written
            iType(0x09, 0, 2, 7),    // ordinary delay slot still executes
            iType(0x01, 1, 0x12, 2), // BLTZALL r1, target; link is written and delay slot annulled
            iType(0x09, 0, 3, 99),   // annulled delay slot
            iType(0x09, 0, 4, 42)    // execution continues here
        ))

        repeat(4) { cpu.step() }

        assertEquals(16L, cpu.register(31))
        assertEquals(7L, cpu.register(2))
        assertEquals(0L, cpu.register(3))
        assertEquals(42L, cpu.register(4))
        assertEquals(20L, cpu.pc)
    }

    @Test fun constructorAcceptsSmallWordAlignedRamForUnitTests() {
        val cpu = R4300Cpu(memorySize = 0x100)
        cpu.writeWord(0xFC, 0x12345678)
        assertEquals(0x12345678, cpu.readWord(0xFC))
    }
    @Test fun cpuLoadsAndStoresRouteToSeparateSpDmemAndImemRegions() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x0F, 0, 1, 0x0400), // r1 = SP DMEM base
            iType(0x0F, 0, 2, 0xAABB), // r2 = 0xAABB0000
            iType(0x0D, 2, 2, 0xCCDD), // r2 = 0xAABBCCDD
            iType(0x2B, 1, 2, 0),      // SW to DMEM
            iType(0x23, 1, 3, 0),      // LW from DMEM
            iType(0x09, 1, 1, 0x1000), // r1 = SP IMEM base
            iType(0x2B, 1, 2, 0),      // SW to IMEM
            iType(0x23, 1, 4, 0)       // LW from IMEM
        ))

        repeat(8) { cpu.step() }

        assertEquals(0xAABBCCDD.toInt(), cpu.readWord(0x04000000))
        assertEquals(0xAABBCCDD.toInt(), cpu.readWord(0x04001000))
        assertEquals(iType(0x0F, 0, 1, 0x0400), cpu.readWord(0))
        assertEquals(0xAABBCCDD.toInt(), cpu.register(3).toInt())
        assertEquals(0xAABBCCDD.toInt(), cpu.register(4).toInt())
    }

    @Test fun cpuCanReadAndWriteSpMemoryMappedRegisters() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x0F, 0, 1, 0x0404), // r1 = SP register base 0x04040000
            iType(0x09, 0, 2, 0x100),  // r2 = SP_MEM_ADDR value
            iType(0x2B, 1, 2, 0),      // SW r2, SP_MEM_ADDR
            iType(0x23, 1, 3, 0)       // LW r3, SP_MEM_ADDR
        ))

        repeat(4) { cpu.step() }

        assertEquals(0x100L, cpu.register(3))
    }

    @Test fun cpuCanStartSpDmaAndReadTransferredData() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x0F, 0, 1, 0x0404), // r1 = SP register base
            iType(0x09, 0, 2, 0x100),  // r2 = RDRAM source address
            iType(0x2B, 1, 0, 0),      // SP_MEM_ADDR = 0 (DMEM)
            iType(0x2B, 1, 2, 4),      // SP_DRAM_ADDR = 0x100
            iType(0x09, 0, 2, 7),      // DMA length 8 bytes (encoded length 7)
            iType(0x2B, 1, 2, 8),      // start RDRAM-to-SP DMA
            iType(0x0F, 0, 3, 0x0400), // r3 = SP DMEM base
            iType(0x23, 3, 4, 0),       // read first transferred word
            iType(0x23, 3, 5, 4)        // read second transferred word
        ))
        cpu.loadBytes(0x100, byteArrayOf(
            0x12, 0x34, 0x56, 0x78, 0x21, 0x43, 0x65, 0x7F
        ))

        repeat(9) { cpu.step() }

        assertEquals(0x12345678L, cpu.register(4))
        assertEquals(0x2143657FL, cpu.register(5))
        assertEquals(0L, cpu.readWord(N64MemoryBus.SP_DMA_BUSY.toInt()).toLong())
    }

    @Test fun spDmaWriteLengthCopiesMultipleBlocksAndAppliesDramSkip() {
        val cpu = R4300Cpu()
        cpu.writeWord(0x04000000, 0x11112222)
        cpu.writeWord(0x04000004, 0x33334444)
        cpu.writeWord(0x04000008, 0x55556666)
        cpu.writeWord(0x0400000C, 0x77778888)
        cpu.loadBytes(0, words(
            iType(0x0F, 0, 1, 0x0404), // r1 = SP register base
            iType(0x09, 0, 2, 0),      // SP DMEM offset 0
            iType(0x2B, 1, 2, 0),      // SP_MEM_ADDR = 0
            iType(0x09, 0, 2, 0x100),  // RDRAM destination address
            iType(0x2B, 1, 2, 4),      // SP_DRAM_ADDR = 0x100
            iType(0x0F, 0, 2, 0x0100), // upper bits for a 16-byte DRAM skip
            iType(0x0D, 2, 2, 0x1007), // 2 blocks, 8 bytes each, skip 16 bytes
            iType(0x2B, 1, 2, 0x0C)    // SP_WR_LEN: SP-to-RDRAM DMA
        ))

        repeat(8) { cpu.step() }

        assertEquals(0x11112222, cpu.readWord(0x100))
        assertEquals(0x33334444, cpu.readWord(0x104))
        assertEquals(0, cpu.readWord(0x108))
        assertEquals(0, cpu.readWord(0x10C))
        assertEquals(0, cpu.readWord(0x110))
        assertEquals(0, cpu.readWord(0x114))
        assertEquals(0x55556666, cpu.readWord(0x118))
        assertEquals(0x77778888, cpu.readWord(0x11C))
        assertEquals(0, cpu.readWord(N64MemoryBus.SP_DMA_BUSY.toInt()))
    }

    @Test fun spDmaReadLengthWrapsWithinDmemAtTheEndOfItsBank() {
        val cpu = R4300Cpu()
        cpu.writeWord(0x100, 0x11223344)
        cpu.writeWord(0x104, 0x55667788)
        cpu.writeWord(0x108, 0x99AABBCC.toInt())
        cpu.writeWord(0x10C, 0xDDEEFF00.toInt())
        cpu.loadBytes(0, words(
            iType(0x0F, 0, 1, 0x0404), // r1 = SP register base
            iType(0x09, 0, 2, 0x0FF8), // start at last 8 bytes of DMEM
            iType(0x2B, 1, 2, 0),      // SP_MEM_ADDR = 0xFF8
            iType(0x09, 0, 2, 0x100),  // RDRAM source address
            iType(0x2B, 1, 2, 4),      // SP_DRAM_ADDR = 0x100
            iType(0x09, 0, 2, 15),     // encoded DMA length for 16 bytes
            iType(0x2B, 1, 2, 8)       // SP_RD_LEN: RDRAM-to-SP DMA
        ))

        repeat(7) { cpu.step() }

        assertEquals(0x11223344, cpu.readWord(0x04000FF8))
        assertEquals(0x55667788, cpu.readWord(0x04000FFC))
        assertEquals(0x99AABBCC.toInt(), cpu.readWord(0x04000000))
        assertEquals(0xDDEEFF00.toInt(), cpu.readWord(0x04000004))
        assertEquals(0, cpu.readWord(N64MemoryBus.SP_DMA_BUSY.toInt()))
    }

    @Test fun spDmaReadLengthAppliesDramSkipBetweenBlocks() {
        val cpu = R4300Cpu()
        val sourceWords = listOf(
            0x11112222, 0x33334444, 0xAAAAAAAA.toInt(), 0xBBBBBBBB.toInt(),
            0xCCCCCCCC.toInt(), 0xDDDDDDDD.toInt(), 0x55556666, 0x77778888
        )
        sourceWords.forEachIndexed { index, value ->
            cpu.writeWord(0x100 + index * 4, value)
        }
        cpu.loadBytes(0, words(
            iType(0x0F, 0, 1, 0x0404), // r1 = SP register base
            iType(0x09, 0, 2, 0),      // SP DMEM destination offset 0
            iType(0x2B, 1, 2, 0),      // SP_MEM_ADDR = 0
            iType(0x09, 0, 2, 0x100),  // RDRAM source address
            iType(0x2B, 1, 2, 4),      // SP_DRAM_ADDR = 0x100
            iType(0x0F, 0, 2, 0x0100), // upper bits for a 16-byte DRAM skip
            iType(0x0D, 2, 2, 0x1007), // 2 blocks of 8 bytes, skip 16 bytes
            iType(0x2B, 1, 2, 8)       // SP_RD_LEN: RDRAM-to-SP DMA
        ))

        repeat(8) { cpu.step() }

        assertEquals(0x11112222, cpu.readWord(0x04000000))
        assertEquals(0x33334444, cpu.readWord(0x04000004))
        assertEquals(0x55556666, cpu.readWord(0x04000008))
        assertEquals(0x77778888, cpu.readWord(0x0400000C))
        assertEquals(0, cpu.readWord(N64MemoryBus.SP_DMA_BUSY.toInt()))
    }

    @Test fun spDmaReadLengthTargetsImemWhenBankSelectBitIsSet() {
        val cpu = R4300Cpu()
        cpu.writeWord(0x100, 0x12345678)
        cpu.writeWord(0x104, 0x9ABCDEF0.toInt())
        cpu.loadBytes(0, words(
            iType(0x0F, 0, 1, 0x0404), // r1 = SP register base
            iType(0x09, 0, 2, 0x1000), // select IMEM bank
            iType(0x2B, 1, 2, 0),      // SP_MEM_ADDR = IMEM offset 0
            iType(0x09, 0, 2, 0x100),  // RDRAM source address
            iType(0x2B, 1, 2, 4),      // SP_DRAM_ADDR = 0x100
            iType(0x09, 0, 2, 7),      // DMA length = 8 bytes
            iType(0x2B, 1, 2, 8)       // SP_RD_LEN: RDRAM-to-SP DMA
        ))

        repeat(7) { cpu.step() }

        assertEquals(0x12345678, cpu.readWord(0x04001000))
        assertEquals(0x9ABCDEF0.toInt(), cpu.readWord(0x04001004))
        assertEquals(0, cpu.readWord(0x04000000)) // DMEM remains untouched
        assertEquals(0, cpu.readWord(N64MemoryBus.SP_DMA_BUSY.toInt()))
    }

    @Test fun cpuCanCopySpDmemBackToRdramWithSpDmaWriteLength() {
        val cpu = R4300Cpu()
        cpu.writeWord(0x04000000, 0x12345678)
        cpu.writeWord(0x04000004, 0x2143657F)
        cpu.loadBytes(0, words(
            iType(0x0F, 0, 1, 0x0404), // r1 = SP register base
            iType(0x09, 0, 2, 0),      // SP DMEM offset 0
            iType(0x2B, 1, 2, 0),      // SP_MEM_ADDR = 0
            iType(0x09, 0, 2, 0x100),  // RDRAM destination address
            iType(0x2B, 1, 2, 4),      // SP_DRAM_ADDR = 0x100
            iType(0x09, 0, 2, 7),      // encoded DMA length for 8 bytes
            iType(0x2B, 1, 2, 0x0C)    // SP_WR_LEN: SP-to-RDRAM DMA
        ))

        repeat(7) { cpu.step() }

        assertEquals(0x12345678, cpu.readWord(0x100))
        assertEquals(0x2143657F, cpu.readWord(0x104))
        assertEquals(0, cpu.readWord(N64MemoryBus.SP_DMA_BUSY.toInt()))
    }

    @Test fun cpuCanCopySpImemBackToRdramWithSpDmaWriteLength() {
        val cpu = R4300Cpu()
        cpu.writeWord(0x04001000, 0x89ABCDEF.toInt())
        cpu.writeWord(0x04001004, 0x76543210)
        cpu.loadBytes(0, words(
            iType(0x0F, 0, 1, 0x0404), // r1 = SP register base
            iType(0x09, 0, 2, 0x1000), // select IMEM bank
            iType(0x2B, 1, 2, 0),      // SP_MEM_ADDR = IMEM offset 0
            iType(0x09, 0, 2, 0x100),  // RDRAM destination address
            iType(0x2B, 1, 2, 4),      // SP_DRAM_ADDR = 0x100
            iType(0x09, 0, 2, 7),      // encoded DMA length for 8 bytes
            iType(0x2B, 1, 2, 0x0C)    // SP_WR_LEN: SP-to-RDRAM DMA
        ))

        repeat(7) { cpu.step() }

        assertEquals(0x89ABCDEF.toInt(), cpu.readWord(0x100))
        assertEquals(0x76543210, cpu.readWord(0x104))
        assertEquals(0, cpu.readWord(N64MemoryBus.SP_DMA_BUSY.toInt()))
    }

    @Test fun spSemaphoreReadSetsItAndWriteClearsIt() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x0F, 0, 1, 0x0404), // r1 = SP register base
            iType(0x23, 1, 2, 0x1C),   // first read returns 0 and sets semaphore
            iType(0x23, 1, 3, 0x1C),   // second read returns 1
            iType(0x2B, 1, 0, 0x1C),   // write clears semaphore
            iType(0x23, 1, 4, 0x1C)    // read returns 0 again
        ))

        repeat(5) { cpu.step() }

        assertEquals(0L, cpu.register(2))
        assertEquals(1L, cpu.register(3))
        assertEquals(0L, cpu.register(4))
    }

    @Test fun spStatusCommandsClearAndSetHaltFlag() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x0F, 0, 1, 0x0404), // r1 = SP register base
            iType(0x09, 0, 2, 1),      // command bit 0: clear HALT
            iType(0x2B, 1, 2, 0x10),   // write SP_STATUS
            iType(0x23, 1, 3, 0x10),   // HALT should now be clear
            iType(0x09, 0, 2, 2),      // command bit 1: set HALT
            iType(0x2B, 1, 2, 0x10),   // write SP_STATUS
            iType(0x23, 1, 4, 0x10)    // HALT should now be set
        ))

        repeat(7) { cpu.step() }

        assertEquals(0L, cpu.register(3) and 1L)
        assertEquals(1L, cpu.register(4) and 1L)
    }

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

    @Test fun branchLikelyAnnulsDelaySlotWhenNotTaken() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x09, 0, 1, 1),      // r1 = 1
            iType(0x14, 1, 0, 1),      // BEQL r1, r0, +1: not taken; annul next instruction
            iType(0x09, 0, 2, 99),     // annulled delay slot
            iType(0x09, 0, 3, 7)       // continue at branch+8
        ))

        repeat(3) { cpu.step() }

        assertEquals(0L, cpu.register(2))
        assertEquals(7L, cpu.register(3))
        assertEquals(16L, cpu.pc)
    }

    @Test fun branchLikelyExecutesDelaySlotWhenTaken() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x09, 0, 1, 0),      // r1 = 0
            iType(0x14, 1, 0, 1),      // BEQL r1, r0, +1: taken
            iType(0x09, 0, 2, 99),     // delay slot executes
            iType(0x09, 0, 3, 7)       // branch target
        ))

        repeat(4) { cpu.step() }

        assertEquals(99L, cpu.register(2))
        assertEquals(7L, cpu.register(3))
        assertEquals(16L, cpu.pc)
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

    @Test fun swlMergesLeftBytesInBigEndianOrderForEveryAlignment() {
        val expectedWords = listOf(
            0xAABBCCDD.toInt(),
            0x11AABBCC.toInt(),
            0x1122AABB.toInt(),
            0x112233AA.toInt()
        )

        for (offset in 0..3) {
            val cpu = R4300Cpu()
            cpu.writeWord(0x100, 0x11223344)
            cpu.loadBytes(0, words(
                iType(0x09, 0, 1, 0x100), // r1 = destination address
                iType(0x0F, 0, 2, 0xAABB), // r2 = 0xAABB0000
                iType(0x0D, 2, 2, 0xCCDD), // r2 = 0xAABBCCDD
                iType(0x2A, 1, 2, offset)  // SWL at selected byte alignment
            ))

            repeat(4) { cpu.step() }

            assertEquals("SWL offset $offset", expectedWords[offset], cpu.readWord(0x100))
        }
    }

    @Test fun sdlMergesLeftBytesInBigEndianOrderForEveryAlignment() {
        for (offset in 0..7) {
            val cpu = R4300Cpu()
            cpu.writeLong(0x100, 0x1122334455667788L)
            cpu.loadBytes(0, words(
                iType(0x09, 0, 1, 0x100), // r1 = destination address
                iType(0x0F, 0, 2, 0xAABB),
                iType(0x0D, 2, 2, 0xCCDD),
                rType(0, 2, 2, 0, 0x3C), // DSLL32 r2, r2, 0
                iType(0x0F, 0, 3, 0xEEFF),
                iType(0x0D, 3, 3, 0x0011),
                rType(0, 3, 3, 0, 0x3C), // shift low word into high half
                rType(0, 3, 3, 0, 0x3E), // DSRL32 zero-extends low word
                rType(2, 3, 2, 0, 0x25), // OR full 64-bit source value
                iType(0x2C, 1, 2, offset) // SDL at selected byte alignment
            ))

            repeat(10) { cpu.step() }

            val expected = when (offset) {
                0 -> 0xAABBCCDDEEFF0011UL.toLong()
                1 -> 0x11AABBCCDDEEFF00UL.toLong()
                2 -> 0x1122AABBCCDDEEFFUL.toLong()
                3 -> 0x112233AABBCCDDEEUL.toLong()
                4 -> 0x11223344AABBCCDDUL.toLong()
                5 -> 0x1122334455AABBCCUL.toLong()
                6 -> 0x112233445566AABBUL.toLong()
                else -> 0x11223344556677AAUL.toLong()
            }
            assertEquals("SDL offset $offset", expected, cpu.readLong(0x100))
        }
    }

    @Test fun movzAndMovnUseRtAsConditionAndRsAsSource() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x09, 0, 1, 11), // r1 = source value
            iType(0x09, 0, 2, 0),  // r2 = zero
            iType(0x09, 0, 3, 33), // r3 = destination sentinel
            rType(1, 2, 3, 0, 0x0A), // MOVZ r3, r1, r2: move because rt == 0
            iType(0x09, 0, 2, 1),  // r2 = nonzero
            iType(0x09, 0, 4, 44), // r4 = destination sentinel
            rType(1, 2, 4, 0, 0x0B), // MOVN r4, r1, r2: move because rt != 0
            iType(0x09, 0, 5, 55), // r5 = destination sentinel
            rType(1, 0, 5, 0, 0x0B), // MOVN must not move when rt == 0
            iType(0x09, 0, 6, 66), // r6 = destination sentinel
            rType(1, 2, 6, 0, 0x0A)  // MOVZ must not move when rt != 0
        ))
        repeat(11) { cpu.step() }
        assertEquals(11L, cpu.register(3))
        assertEquals(11L, cpu.register(4))
        assertEquals(55L, cpu.register(5))
        assertEquals(66L, cpu.register(6))
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
            rType(1, 2, 4, 0, 0x0A),     // MOVZ: rt is zero, copy rs (r1) to r4
            rType(1, 3, 5, 0, 0x0A),     // MOVZ: rt is nonzero, leave r5
            rType(1, 3, 6, 0, 0x0B),     // MOVN: rt is nonzero, copy rs (r1) to r6
            rType(1, 2, 7, 0, 0x0B),     // MOVN: rt is zero, leave r7
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

    @Test fun divisionByZeroLeavesHiLoUnchangedForDivAndDivu() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x09, 0, 1, 12),       // r1 = 12
            rType(1, 0, 0, 0, 0x11),      // MTHI r1
            rType(1, 0, 0, 0, 0x13),      // MTLO r1
            iType(0x09, 0, 2, 0),        // r2 = 0
            rType(1, 2, 0, 0, 0x1A),      // DIV by zero: simplified core preserves HI/LO
            rType(1, 2, 0, 0, 0x1B),      // DIVU by zero: preserves HI/LO
            rType(0, 0, 3, 0, 0x10),      // MFHI r3
            rType(0, 0, 4, 0, 0x12)       // MFLO r4
        ))

        repeat(8) { cpu.step() }

        assertEquals(12L, cpu.register(3))
        assertEquals(12L, cpu.register(4))
        assertEquals(12L, cpu.hi)
        assertEquals(12L, cpu.lo)
    }

    @Test fun ddivAndDdivuProduceExpected64BitQuotientsAndRemainders() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x09, 0, 1, -7),     // r1 = -7
            iType(0x19, 0, 2, 3),      // r2 = 3
            rType(1, 2, 0, 0, 0x1E),   // DDIV: -7 / 3 => quotient -2, remainder -1
            rType(0, 0, 3, 0, 0x10),  // MFHI r3
            rType(0, 0, 4, 0, 0x12),  // MFLO r4
            rType(1, 2, 0, 0, 0x1F),   // DDIVU: unsigned(-7) / 3
            rType(0, 0, 5, 0, 0x10),  // MFHI r5
            rType(0, 0, 6, 0, 0x12)   // MFLO r6
        ))

        repeat(8) { cpu.step() }

        assertEquals(-1L, cpu.register(3))
        assertEquals(-2L, cpu.register(4))
        assertEquals(0L, cpu.register(5))
        assertEquals(6148914691236517203L, cpu.register(6))
        assertEquals(0L, cpu.hi)
        assertEquals(6148914691236517203L, cpu.lo)
    }

    @Test fun ddivAndDdivuByZeroPreserveHiLo() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x09, 0, 1, -7),     // r1 = -7
            iType(0x09, 0, 2, 3),      // r2 = 3
            rType(1, 2, 0, 0, 0x1E),   // DDIV: HI=-1, LO=-2
            rType(1, 0, 0, 0, 0x1E),   // DDIV by zero should preserve HI/LO
            rType(0, 0, 3, 0, 0x10),   // MFHI r3
            rType(0, 0, 4, 0, 0x12),   // MFLO r4
            iType(0x09, 0, 5, 7),      // r5 = 7
            rType(5, 2, 0, 0, 0x1F),   // DDIVU: HI=1, LO=2
            rType(5, 0, 0, 0, 0x1F),   // DDIVU by zero should preserve HI/LO
            rType(0, 0, 6, 0, 0x10),   // MFHI r6
            rType(0, 0, 7, 0, 0x12)    // MFLO r7
        ))
        repeat(11) { cpu.step() }
        assertEquals(-1L, cpu.register(3))
        assertEquals(-2L, cpu.register(4))
        assertEquals(1L, cpu.register(6))
        assertEquals(2L, cpu.register(7))
        assertEquals(1L, cpu.hi)
        assertEquals(2L, cpu.lo)
    }

    @Test fun ddivSignedOverflowUsesSimplifiedCoreBoundaryResult() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x0F, 0, 1, 0x8000), // r1 = sign-extended 0x80000000
            rType(0, 1, 1, 0, 0x3C),   // DSLL32: r1 = Long.MIN_VALUE
            iType(0x19, 0, 2, -1),     // r2 = -1
            rType(1, 2, 0, 0, 0x1E),   // DDIV boundary case: Long.MIN_VALUE / -1
            rType(0, 0, 3, 0, 0x10),   // MFHI r3
            rType(0, 0, 4, 0, 0x12)    // MFLO r4
        ))

        repeat(6) { cpu.step() }

        assertEquals(0L, cpu.register(3))
        assertEquals(Long.MIN_VALUE, cpu.register(4))
        assertEquals(0L, cpu.hi)
        assertEquals(Long.MIN_VALUE, cpu.lo)
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

    @Test fun cacheAndPrefetchHintsDoNotInterruptInterpretation() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x09, 0, 1, 0x100), // base address
            iType(0x2F, 1, 0, 0),     // CACHE hint
            iType(0x33, 1, 0, 0),     // PREF hint
            iType(0x09, 0, 2, 42)     // normal instruction after hints
        ))
        repeat(4) { cpu.step() }
        assertEquals(0x100L, cpu.register(1))
        assertEquals(42L, cpu.register(2))
        assertEquals(16L, cpu.pc)
    }

    @Test fun lwuZeroExtendsWordWhileLwSignExtendsIt() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x0F, 0, 1, 0xFEDC), // r1 = 0xFFFFFFFFFEDC0000
            iType(0x0D, 1, 1, 0xBA98), // r1 = 0xFFFFFFFFFEDCBA98
            iType(0x2B, 0, 1, 0x100), // store word at 0x100
            iType(0x23, 0, 2, 0x100), // LW sign-extends 32-bit value
            iType(0x27, 0, 3, 0x100)  // LWU zero-extends 32-bit value
        ))
        repeat(5) { cpu.step() }
        assertEquals(-19088744L, cpu.register(2))
        assertEquals(0xFEDCBA98L, cpu.register(3))
    }

    @Test fun kseg0VirtualDataAddressesAliasPhysicalRam() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x0F, 0, 1, 0x8000), // r1 = sign-extended KSEG0 base
            iType(0x0D, 1, 1, 0x100),  // r1 = KSEG0 + 0x100
            iType(0x0F, 0, 2, 0x1234), // r2 = 0x12340000
            iType(0x2B, 1, 2, 0),      // SW through virtual address
            iType(0x23, 1, 3, 0)       // LW through same virtual address
        ))

        repeat(5) { cpu.step() }

        assertEquals(0x12340000L, cpu.register(3))
        assertEquals(0x12340000, cpu.readWord(0x100))
    }

    @Test fun kseg1VirtualDataAddressesAliasPhysicalRam() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x0F, 0, 1, 0xA000), // r1 = sign-extended KSEG1 base
            iType(0x0D, 1, 1, 0x100),  // r1 = KSEG1 + 0x100 -> physical 0x100
            iType(0x0F, 0, 2, 0x5678), // r2 = 0x56780000
            iType(0x2B, 1, 2, 0),      // SW through uncached virtual address
            iType(0x23, 0, 3, 0x100)   // LW through physical address
        ))

        repeat(5) { cpu.step() }

        assertEquals(0x56780000L, cpu.register(3))
        assertEquals(0x56780000, cpu.readWord(0x100))
    }

    @Test fun kseg1VirtualDataAddressesReachSpDmemThroughPhysicalBus() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x0F, 0, 1, 0xA400), // r1 = sign-extended KSEG1 SP DMEM base
            iType(0x09, 0, 2, 0x1234), // r2 = 0x1234
            iType(0x2B, 1, 2, 0),      // SW to 0xA4000000 -> physical SP DMEM
            iType(0x23, 1, 3, 0)       // LW through the same virtual address
        ))

        repeat(4) { cpu.step() }

        assertEquals(0x1234L, cpu.register(3))
    }

    @Test fun defaultRamCoversEightMibRdramRange() {
        val cpu = R4300Cpu()
        val lastWordAddress = R4300Cpu.DEFAULT_RDRAM_SIZE - 4

        cpu.writeWord(lastWordAddress, 0x12345678)

        assertEquals(0x12345678, cpu.readWord(lastWordAddress))
    }

    @Test fun loadsNormalizedCartridgeAndRunsOnlyWithinInstructionBudget() {
        val rom = ByteArray(0x1000)
        rom[0] = 0x80.toByte()
        rom[1] = 0x37
        rom[2] = 0x12
        rom[3] = 0x40
        // ADDIU r1, r0, 42 at ROM offset 0x40.
        rom[0x40] = 0x24
        rom[0x41] = 0x01
        rom[0x42] = 0x00
        rom[0x43] = 0x2A

        val cpu = R4300Cpu()
        cpu.loadCartridgeRom(rom)
        cpu.pc = 0xFFFF_FFFF_B000_0040UL.toLong()
        assertEquals(1, cpu.run(maxInstructions = 1))
        assertEquals(42L, cpu.register(1))
        assertEquals(0xFFFF_FFFF_B000_0044UL.toLong(), cpu.pc)
    }

    @Test fun cartridgeRomMustBeNormalizedAndHaveValidSize() {
        val cpu = R4300Cpu()
        val invalid = ByteArray(0x1000)
        invalid[0] = 0x37
        invalid[1] = 0x80.toByte()
        invalid[2] = 0x40
        invalid[3] = 0x12
        try {
            cpu.loadCartridgeRom(invalid)
            throw AssertionError("Expected swapped ROM to be rejected")
        } catch (_: IllegalArgumentException) {
            // Caller must normalize to Z64 first.
        }
    }

    @Test fun kseg0ProgramCounterFetchesFromTranslatedPhysicalRam() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(iType(0x09, 0, 1, 42)))
        cpu.pc = 0xFFFF_FFFF_8000_0000UL.toLong()

        cpu.step()

        assertEquals(42L, cpu.register(1))
        assertEquals(0xFFFF_FFFF_8000_0004UL.toLong(), cpu.pc)
    }


    @Test fun jumpUsesLow32BitPcAndSignExtendsTarget() {
        val cpu = R4300Cpu()
        val jumpToIndexFour = (0x02 shl 26) or 4
        cpu.loadBytes(0, words(jumpToIndexFour, 0)) // J 0x10; delay-slot NOP
        cpu.pc = 0xFFFF_FFFF_8000_0000UL.toLong()

        cpu.step()
        cpu.step()

        assertEquals(0xFFFF_FFFF_8000_0010UL.toLong(), cpu.pc)
    }

    @Test fun jumpAndLinkUsesCorrectSignExtendedTargetAndReturnAddress() {
        val cpu = R4300Cpu()
        val jumpAndLinkToIndexFour = (0x03 shl 26) or 4
        cpu.loadBytes(0, words(jumpAndLinkToIndexFour, 0)) // JAL 0x10; delay-slot NOP
        cpu.pc = 0xFFFF_FFFF_8000_0000UL.toLong()

        cpu.step()
        assertEquals(0xFFFF_FFFF_8000_0008UL.toLong(), cpu.register(31))
        cpu.step()

        assertEquals(0xFFFF_FFFF_8000_0010UL.toLong(), cpu.pc)
    }

    @Test fun mfc0AndMtc0TransferValuesThroughWritableCop0Registers() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x09, 0, 1, 42),       // ADDIU r1, r0, 42
            (0x10 shl 26) or (0x04 shl 21) or (1 shl 16) or (14 shl 11), // MTC0 r1, EPC
            (0x10 shl 26) or (2 shl 16) or (14 shl 11),                 // MFC0 r2, EPC
        ))
        repeat(3) { cpu.step() }
        assertEquals(42L, cpu.register(2))
        assertEquals(12L, cpu.pc)
    }

    @Test fun mfc0SignExtendsNegative32BitCop0Values() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x0F, 0, 1, 0x8000), // LUI r1, 0x8000 -> 0xFFFFFFFF80000000
            (0x10 shl 26) or (0x04 shl 21) or (1 shl 16) or (14 shl 11), // MTC0 r1, EPC
            (0x10 shl 26) or (2 shl 16) or (14 shl 11) // MFC0 r2, EPC
        ))

        repeat(3) { cpu.step() }

        assertEquals(-2147483648L, cpu.register(1))
        assertEquals(-2147483648L, cpu.register(2))
        assertEquals(12L, cpu.pc)
    }

    @Test fun mtc0CauseOnlyWritesSoftwareInterruptPendingBits() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x09, 0, 1, -1), // ADDIU r1, r0, -1
            (0x10 shl 26) or (0x04 shl 21) or (1 shl 16) or (13 shl 11), // MTC0 r1, Cause
            (0x10 shl 26) or (2 shl 16) or (13 shl 11) // MFC0 r2, Cause
        ))

        repeat(3) { cpu.step() }

        assertEquals(0x300L, cpu.register(2))
    }

    @Test fun mtc0CauseCanSetAndClearEachSoftwareInterruptPendingBit() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x09, 0, 1, -1), // Set both writable software interrupt pending bits.
            (0x10 shl 26) or (0x04 shl 21) or (1 shl 16) or (13 shl 11), // MTC0 r1, Cause
            iType(0x09, 0, 1, 0x100),
            (0x10 shl 26) or (0x04 shl 21) or (1 shl 16) or (13 shl 11), // MTC0 r1, Cause
            (0x10 shl 26) or (2 shl 16) or (13 shl 11), // MFC0 r2, Cause: IP0 only
            iType(0x09, 0, 1, 0x200),
            (0x10 shl 26) or (0x04 shl 21) or (1 shl 16) or (13 shl 11), // MTC0 r1, Cause
            (0x10 shl 26) or (3 shl 16) or (13 shl 11), // MFC0 r3, Cause: IP1 only
            iType(0x09, 0, 1, 0),
            (0x10 shl 26) or (0x04 shl 21) or (1 shl 16) or (13 shl 11), // Clear both bits
            (0x10 shl 26) or (4 shl 16) or (13 shl 11) // MFC0 r4, Cause
        ))

        repeat(11) { cpu.step() }

        assertEquals(0x100L, cpu.register(2))
        assertEquals(0x200L, cpu.register(3))
        assertEquals(0L, cpu.register(4))
    }

    @Test fun eretJumpsToEpcAndClearsExceptionLevelForSimplifiedCore() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x09, 0, 1, 0x40), // r1 = return address 0x40
            (0x10 shl 26) or (0x04 shl 21) or (1 shl 16) or (14 shl 11), // MTC0 r1, EPC
            iType(0x09, 0, 2, 2), // Status.EXL = 1
            (0x10 shl 26) or (0x04 shl 21) or (2 shl 16) or (12 shl 11), // MTC0 r2, Status
            0x42000018, // ERET
        ))
        cpu.loadBytes(0x40, words(iType(0x09, 0, 3, 99)))

        repeat(5) { cpu.step() }
        assertEquals(0x40L, cpu.pc)

        cpu.step()
        assertEquals(99L, cpu.register(3))
        assertEquals(0x44L, cpu.pc)
    }


    @Test fun rspBreakSetsBrokeAndHaltAndStatusCommandsClearThemIndependently() {
        val bus = N64MemoryBus()

        bus.markSpBroke()
        val brokeStatus = bus.readWord(N64MemoryBus.SP_STATUS)
        assertEquals(1, brokeStatus and 0x1) // HALT
        assertEquals(0x2, brokeStatus and 0x2) // BROKE

        bus.writeWord(N64MemoryBus.SP_STATUS, 1 shl 2) // clear BROKE only
        val clearedBrokeStatus = bus.readWord(N64MemoryBus.SP_STATUS)
        assertEquals(1, clearedBrokeStatus and 0x1)
        assertEquals(0, clearedBrokeStatus and 0x2)

        bus.writeWord(N64MemoryBus.SP_STATUS, 1) // clear HALT
        assertEquals(0, bus.readWord(N64MemoryBus.SP_STATUS) and 0x1)
    }


    @Test fun spStatusCommandsSetAndClearSignalsAndControlFlags() {
        val bus = N64MemoryBus()

        // Set signal 0 and signal 7 using their alternating command bits.
        bus.writeWord(N64MemoryBus.SP_STATUS, (1 shl 10) or (1 shl 24))
        var status = bus.readWord(N64MemoryBus.SP_STATUS)
        assertEquals(1 shl 7, status and (1 shl 7))
        assertEquals(1 shl 14, status and (1 shl 14))

        // Clear the same signals without affecting the other status flags.
        bus.writeWord(N64MemoryBus.SP_STATUS, (1 shl 9) or (1 shl 23))
        status = bus.readWord(N64MemoryBus.SP_STATUS)
        assertEquals(0, status and (1 shl 7))
        assertEquals(0, status and (1 shl 14))

        // Interrupt-on-break and single-step controls are independently writable.
        bus.writeWord(N64MemoryBus.SP_STATUS, (1 shl 8) or (1 shl 6))
        status = bus.readWord(N64MemoryBus.SP_STATUS)
        assertEquals(1 shl 6, status and (1 shl 6))
        assertEquals(1 shl 5, status and (1 shl 5))

        bus.writeWord(N64MemoryBus.SP_STATUS, (1 shl 7) or (1 shl 5))
        status = bus.readWord(N64MemoryBus.SP_STATUS)
        assertEquals(0, status and (1 shl 6))
        assertEquals(0, status and (1 shl 5))
    }

    @Test fun cpuCanSetAndClearSpStatusSignalThroughMemoryMappedRegister() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x0F, 0, 1, 0x0404), // r1 = SP register base
            iType(0x09, 0, 2, 1 shl 12), // set signal 1: command bit 12
            iType(0x2B, 1, 2, 0x10), // write SP_STATUS
            iType(0x23, 1, 3, 0x10), // read SP_STATUS
            iType(0x09, 0, 2, 1 shl 11), // clear signal 1: command bit 11
            iType(0x2B, 1, 2, 0x10), // write SP_STATUS
            iType(0x23, 1, 4, 0x10) // read SP_STATUS again
        ))

        repeat(7) { cpu.step() }

        assertEquals(1L shl 8, cpu.register(3) and (1L shl 8))
        assertEquals(0L, cpu.register(4) and (1L shl 8))
    }

    @Test fun executesZeroSourceMtc0AtKseg1CartridgeEntry() {
        val cpu = R4300Cpu()
        val rom = ByteArray(0x1000)
        fun putWord(offset: Int, value: Int) {
            rom[offset] = (value ushr 24).toByte()
            rom[offset + 1] = (value ushr 16).toByte()
            rom[offset + 2] = (value ushr 8).toByte()
            rom[offset + 3] = value.toByte()
        }

        // Valid normalized Z64 header followed by a small CP0 smoke-test program.
        putWord(0, 0x80371240.toInt())
        putWord(0x40, iType(0x09, 0, 1, 42)) // ADDIU r1, r0, 42
        putWord(0x44, (0x10 shl 26) or (0x04 shl 21) or (1 shl 16) or (13 shl 11)) // MTC0 r1, Cause
        putWord(0x48, 0x40806800) // MTC0 r0, Cause (exact opcode seen in the boot failure)
        putWord(0x4C, (0x10 shl 26) or (2 shl 16) or (13 shl 11)) // MFC0 r2, Cause
        cpu.loadCartridgeRom(rom)
        cpu.pc = 0xFFFF_FFFF_B000_0040UL.toLong()

        repeat(4) { cpu.step() }

        assertEquals(0L, cpu.register(2))
        assertEquals(0xFFFF_FFFF_B000_0050UL.toLong(), cpu.pc)
    }

    @Test fun loadWordFromKseg1CartridgeAddressUsesPhysicalRomMapping() {
        val cpu = R4300Cpu()
        val rom = ByteArray(0x1000)
        fun putRomWord(offset: Int, value: Int) {
            rom[offset] = (value ushr 24).toByte()
            rom[offset + 1] = (value ushr 16).toByte()
            rom[offset + 2] = (value ushr 8).toByte()
            rom[offset + 3] = value.toByte()
        }
        putRomWord(0, 0x80371240.toInt())
        putRomWord(0x50, 0x13572468)

        cpu.loadCartridgeRom(rom)
        cpu.loadBytes(0, words(
            iType(0x0F, 0, 1, 0xB000), // LUI r1, 0xB000 -> sign-extended KSEG1 base
            iType(0x23, 1, 2, 0x50)    // LW r2, 0x50(r1) from cartridge ROM
        ))

        repeat(2) { cpu.step() }

        assertEquals(0x13572468L, cpu.register(2))
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
