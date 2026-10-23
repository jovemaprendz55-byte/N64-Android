package com.jovemaprendz55byte.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class N64EmulatorCoreTest {
    @Test fun executesOnlyRequestedBudgetFromExplicitCartridgeEntryPoint() {
        val rom = syntheticRomWithInstructions(0x2401002A, 0x24210001)
        val core = N64EmulatorCore()
        core.loadRom(rom)

        val result = core.executeFrom(0xFFFF_FFFF_B000_0040UL.toLong(), 1)

        assertEquals(1, result.instructionsExecuted)
        assertEquals(0xFFFF_FFFF_B000_0044UL.toLong(), result.programCounter)
        assertEquals(N64EmulatorCore.StopReason.INSTRUCTION_BUDGET_REACHED, result.stopReason)
        assertEquals(null, result.errorMessage)
    }

    @Test fun executionSlicesContinueFromPreviousProgramCounter() {
        val rom = syntheticRomWithInstructions(0x2401002A, 0x24210001, 0x24210001)
        val core = N64EmulatorCore()
        core.loadRom(rom)

        core.startExecution(0xFFFF_FFFF_B000_0040UL.toLong())
        val first = core.executeSlice(1)
        val second = core.executeSlice(2)

        assertEquals(1, first.instructionsExecuted)
        assertEquals(0xFFFF_FFFF_B000_0044UL.toLong(), first.programCounter)
        assertEquals(2, second.instructionsExecuted)
        assertEquals(0xFFFF_FFFF_B000_004CUL.toLong(), second.programCounter)
        assertEquals(N64EmulatorCore.StopReason.INSTRUCTION_BUDGET_REACHED, second.stopReason)
    }

    @Test fun reportsUnsupportedInstructionInsteadOfCrashingCaller() {
        // Opcode 0x1C (SPECIAL2) is not implemented; 0xFFFFFFFF is a valid SD encoding.
        val rom = syntheticRomWithInstructions(0x70000000)
        val core = N64EmulatorCore()
        core.loadRom(rom)

        val result = core.executeFrom(0xFFFF_FFFF_B000_0040UL.toLong(), 10)

        assertEquals(0, result.instructionsExecuted)
        assertEquals(0xFFFF_FFFF_B000_0040UL.toLong(), result.programCounter)
        assertEquals(N64EmulatorCore.StopReason.EXECUTION_ERROR, result.stopReason)
        assertNotNull(result.errorMessage)
    }

    @Test fun cartridgeDiagnosticStartsWithAValidRdramStack() {
        // SW r19, 0(r29): direct cartridge entry must not start with SP = 0.
        val rom = syntheticRomWithInstructions(0xAFB30000.toInt())
        val core = N64EmulatorCore()
        core.loadRom(rom)

        core.startCartridgeDiagnosticExecution(0xFFFF_FFFF_B000_0040UL.toLong())
        val result = core.executeSlice(1)

        assertEquals(1, result.instructionsExecuted)
        assertEquals(N64EmulatorCore.StopReason.INSTRUCTION_BUDGET_REACHED, result.stopReason)
        assertEquals(null, result.errorMessage)
        assertEquals(0xFFFF_FFFF_B000_0044UL.toLong(), result.programCounter)
    }

    @Test fun ipl3DiagnosticCopiesCodeAndUsesNtscBootSeed() {
        // SW r20, 0(r0): the diagnostic CIC-6105 handoff seeds r20=1 for NTSC.
        val rom = syntheticRomWithInstructions(0xAC140000.toInt())
        rom[0x3E] = 'J'.code.toByte()
        val core = N64EmulatorCore()
        core.loadRom(rom)

        core.startIpl3DiagnosticExecution()
        assertEquals(0xAC140000.toInt(), core.memoryBus.readWord(N64SpMemory.DMEM_BASE + 0x40L))
        val result = core.executeSlice(1)

        assertEquals(1, result.instructionsExecuted)
        assertEquals(N64EmulatorCore.StopReason.INSTRUCTION_BUDGET_REACHED, result.stopReason)
        assertEquals(null, result.errorMessage)
        assertEquals(0xFFFF_FFFF_A400_0044UL.toLong(), result.programCounter)
        assertEquals(1, core.memoryBus.readWord(0))
    }

    @Test fun cicHandoffDiagnosticRecordsIpl3CopyBeforeExecution() {
        val rom = syntheticRomWithInstructions(0xAC140000.toInt())
        rom[0x3E] = 'J'.code.toByte()
        val core = N64EmulatorCore()
        core.loadRom(rom)

        core.startCic6105BootSequence()

        assertEquals(
            true,
            core.bootMemoryDiagnosticSummary().contains("SP_DMEM_IPL3_COPY_MATCH_AT_HANDOFF=true")
        )
    }

    @Test fun ipl3DiagnosticUsesPalBootSeedForEuropeanCountryCode() {
        // SW r20, 0(r0): the diagnostic CIC-6105 handoff seeds r20=0 for PAL.
        // 'P' is a PAL country code; 'E' identifies North America (NTSC).
        val rom = syntheticRomWithInstructions(0xAC140000.toInt())
        rom[0x3E] = 'P'.code.toByte()
        val core = N64EmulatorCore()
        core.loadRom(rom)

        core.startIpl3DiagnosticExecution()
        assertEquals(0xAC140000.toInt(), core.memoryBus.readWord(N64SpMemory.DMEM_BASE + 0x40L))
        val result = core.executeSlice(1)

        assertEquals(1, result.instructionsExecuted)
        assertEquals(N64EmulatorCore.StopReason.INSTRUCTION_BUDGET_REACHED, result.stopReason)
        assertEquals(null, result.errorMessage)
        assertEquals(0, core.memoryBus.readWord(0))
    }

    @Test fun stopsTheKnownIpl3ChecksumFailureLoopEarly() {
        val core = N64EmulatorCore()
        core.loadRom(syntheticRomWithInstructions(0))
        // IPL3 failure loop: BGEZAL $zero,-1, with a delay-slot NOP.
        core.memoryBus.writeWord(0x1C8L, 0x0411FFFF)
        core.memoryBus.writeWord(0x1CCL, 0x00000000)
        core.startExecution(0xFFFF_FFFF_8000_01C8UL.toLong())

        val result = core.executeSlice(1000)

        assertEquals(N64EmulatorCore.StopReason.BOOT_CHECKSUM_FAILURE_LOOP, result.stopReason)
        assertNotNull(result.errorMessage)
        org.junit.Assert.assertTrue(result.instructionsExecuted < 1000)
    }

    @Test fun requiresRomBeforeExecution() {
        val core = N64EmulatorCore()
        try {
            core.executeFrom(0xFFFF_FFFF_B000_0040UL.toLong(), 1)
            throw AssertionError("Expected execution without ROM to be rejected")
        } catch (_: IllegalArgumentException) {
            // A ROM must be loaded before attempting a run.
        }
    }

    @Test fun syntheticCpuSelfTestExecutesInstructionAndChecksRegisterResult() {
        val result = N64CpuSelfTest.run()

        assertEquals(true, result.success)
        assertEquals(8, result.instructionsExecuted)
        assertEquals(42L, result.expectedRegisterValue)
        assertEquals(42L, result.actualRegisterValue)
        assertEquals(42L, result.loadedBackValue)
        assertEquals(7L, result.delaySlotValue)
        assertEquals(42L, result.branchTargetValue)
        assertEquals(0x20L, result.programCounter)
    }


    @Test fun eachExecutionStartsWithCleanCpuRegistersButPreservesRomMemory() {
        // First instruction leaves r1=42. The second instruction would perform
        // an unaligned load from address 42 if the next execution reused r1.
        val rom = syntheticRomWithInstructions(0x2401002A, 0x8C220000.toInt())
        val core = N64EmulatorCore()
        core.loadRom(rom)

        val first = core.executeFrom(0xFFFF_FFFF_B000_0040UL.toLong(), 1)
        assertEquals(N64EmulatorCore.StopReason.INSTRUCTION_BUDGET_REACHED, first.stopReason)

        val second = core.executeFrom(0xFFFF_FFFF_B000_0044UL.toLong(), 1)
        assertEquals(1, second.instructionsExecuted)
        assertEquals(N64EmulatorCore.StopReason.INSTRUCTION_BUDGET_REACHED, second.stopReason)
        assertEquals(null, second.errorMessage)
    }

    @Test fun coreExecutesZeroSourceMtc0CauseWithoutUnsupportedInstructionError() {
        val rom = syntheticRomWithInstructions(
            0x40806800, // MTC0 r0, Cause: regression instruction from the reported boot error.
            (0x10 shl 26) or (2 shl 16) or (13 shl 11) // MFC0 r2, Cause
        )
        val core = N64EmulatorCore()
        core.loadRom(rom)

        val result = core.executeFrom(0xFFFF_FFFF_B000_0040UL.toLong(), 2)

        assertEquals(2, result.instructionsExecuted)
        assertEquals(N64EmulatorCore.StopReason.INSTRUCTION_BUDGET_REACHED, result.stopReason)
        assertEquals(null, result.errorMessage)
        assertEquals(0xFFFF_FFFF_B000_0048UL.toLong(), result.programCounter)
    }

    @Test fun cpuSpStatusWriteAutomaticallyStartsAndAdvancesRsp() {
        val rom = syntheticRomWithInstructions(
            0x3C01A408, // LUI r1, 0xA4080000: SP_PC register window (KSEG1).
            0xAC200000.toInt(), // SW r0, 0(r1): SP_PC = 0.
            0x3C01A404, // LUI r1, 0xA4040000: SP register window.
            0x34210010, // ORI r1, r1, 0x10: SP_STATUS.
            0x24020001, // ADDIU r2, r0, 1: clear HALT.
            0xAC220000.toInt(), // SW r2, 0(r1): release SP; scheduler should start RSP.
            0x00000000 // NOP gives scheduler another RSP step (BREAK).
        )
        val core = N64EmulatorCore()
        core.loadRom(rom)
        core.memoryBus.writeWord(N64SpMemory.IMEM_BASE, 0x2401002A) // ADDIU R1, R0, 42
        core.memoryBus.writeWord(N64SpMemory.IMEM_BASE + 4L, 0x0000000D) // BREAK

        core.startExecution(0xFFFF_FFFF_B000_0040UL.toLong())
        val result = core.executeSlice(7)

        assertEquals(N64EmulatorCore.StopReason.INSTRUCTION_BUDGET_REACHED, result.stopReason)
        // BREAK must have run automatically after the CPU released SP from HALT.
        org.junit.Assert.assertTrue(core.memoryBus.isSpHalted())
        org.junit.Assert.assertTrue(core.memoryBus.readWord(N64MemoryBus.SP_STATUS) and (1 shl 1) != 0)
    }

    private fun syntheticRomWithInstructions(vararg instructions: Int): ByteArray {
        val rom = ByteArray(0x1000)
        rom[0] = 0x80.toByte()
        rom[1] = 0x37
        rom[2] = 0x12
        rom[3] = 0x40
        // Valid NTSC country code so boot preflight can select the CIC-6105 seed.
        rom[0x3E] = 'E'.code.toByte()
        instructions.forEachIndexed { index, instruction ->
            val offset = 0x40 + index * 4
            rom[offset] = (instruction ushr 24).toByte()
            rom[offset + 1] = (instruction ushr 16).toByte()
            rom[offset + 2] = (instruction ushr 8).toByte()
            rom[offset + 3] = instruction.toByte()
        }
        return rom
    }
}
