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
        val rom = syntheticRomWithInstructions(0xFFFFFFFF.toInt())
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

    private fun syntheticRomWithInstructions(vararg instructions: Int): ByteArray {
        val rom = ByteArray(0x1000)
        rom[0] = 0x80.toByte()
        rom[1] = 0x37
        rom[2] = 0x12
        rom[3] = 0x40
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
