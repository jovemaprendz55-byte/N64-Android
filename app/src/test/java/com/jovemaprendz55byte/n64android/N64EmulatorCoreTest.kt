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

    @Test fun reportsUnsupportedInstructionInsteadOfCrashingCaller() {
        val rom = syntheticRomWithInstructions(0xFFFFFFFF.toInt())
        val core = N64EmulatorCore()
        core.loadRom(rom)

        val result = core.executeFrom(0xFFFF_FFFF_B000_0040UL.toLong(), 10)

        assertEquals(0, result.instructionsExecuted)
        assertEquals(N64EmulatorCore.StopReason.EXECUTION_ERROR, result.stopReason)
        assertNotNull(result.errorMessage)
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
