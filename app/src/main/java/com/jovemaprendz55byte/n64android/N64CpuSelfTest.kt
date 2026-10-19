package com.jovemaprendz55byte.n64android

/**
 * Synthetic integration check for the UI. It exercises arithmetic, RAM load/store,
 * and a branch delay slot. It never executes a commercial cartridge or boots a game.
 */
object N64CpuSelfTest {
    data class Result(
        val success: Boolean,
        val instructionsExecuted: Int,
        val expectedRegisterValue: Long,
        val actualRegisterValue: Long,
        val loadedBackValue: Long,
        val delaySlotValue: Long,
        val branchTargetValue: Long,
        val programCounter: Long
    )

    fun run(): Result {
        val cpu = R4300Cpu()
        val program = intArrayOf(
            iType(0x09, 0, 1, 20),      // r1 = 20
            iType(0x09, 0, 2, 22),      // r2 = 22
            rType(1, 2, 3, 0x21),       // r3 = r1 + r2 = 42 (ADDU)
            iType(0x2B, 0, 3, 0x100),  // SW r3, 0x100(r0)
            iType(0x23, 0, 4, 0x100),  // LW r4, 0x100(r0)
            iType(0x04, 3, 4, 1),      // BEQ r3, r4, +1
            iType(0x09, 0, 5, 7),      // delay slot must execute
            iType(0x09, 0, 6, 42),     // branch target
            iType(0x09, 0, 7, 99)      // should not execute in this budget
        )
        val bytes = ByteArray(program.size * 4)
        program.forEachIndexed { index, instruction ->
            val offset = index * 4
            bytes[offset] = (instruction ushr 24).toByte()
            bytes[offset + 1] = (instruction ushr 16).toByte()
            bytes[offset + 2] = (instruction ushr 8).toByte()
            bytes[offset + 3] = instruction.toByte()
        }
        cpu.loadBytes(0, bytes)
        cpu.reset(0L)
        val executed = cpu.run(8)

        val expected = 42L
        val actual = cpu.register(3)
        val loadedBack = cpu.register(4)
        val delaySlot = cpu.register(5)
        val branchTarget = cpu.register(6)
        return Result(
            success = executed == 8 &&
                actual == expected &&
                loadedBack == expected &&
                delaySlot == 7L &&
                branchTarget == expected &&
                cpu.register(7) == 0L &&
                cpu.readWord(0x100) == 42,
            instructionsExecuted = executed,
            expectedRegisterValue = expected,
            actualRegisterValue = actual,
            loadedBackValue = loadedBack,
            delaySlotValue = delaySlot,
            branchTargetValue = branchTarget,
            programCounter = cpu.pc
        )
    }

    private fun iType(opcode: Int, rs: Int, rt: Int, immediate: Int): Int =
        (opcode shl 26) or (rs shl 21) or (rt shl 16) or (immediate and 0xFFFF)

    private fun rType(rs: Int, rt: Int, rd: Int, function: Int): Int =
        (rs shl 21) or (rt shl 16) or (rd shl 11) or function
}
