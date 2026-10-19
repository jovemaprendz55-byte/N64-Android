package com.jovemaprendz55byte.n64android

/**
 * A small synthetic CPU check for the app UI.
 * This deliberately does not execute a commercial cartridge or claim game compatibility.
 */
object N64CpuSelfTest {
    data class Result(
        val success: Boolean,
        val instructionsExecuted: Int,
        val expectedRegisterValue: Long,
        val actualRegisterValue: Long,
        val programCounter: Long
    )

    fun run(): Result {
        val cpu = R4300Cpu()
        // ADDIU r1, r0, 42: write the known value 42 into register 1.
        cpu.loadBytes(0, byteArrayOf(0x24, 0x01, 0x00, 0x2A))
        cpu.reset(0L)
        cpu.step()

        val expected = 42L
        val actual = cpu.register(1)
        return Result(
            success = actual == expected && cpu.pc == 4L,
            instructionsExecuted = 1,
            expectedRegisterValue = expected,
            actualRegisterValue = actual,
            programCounter = cpu.pc
        )
    }
}
