package com.jovemaprendz55byte.n64android

/**
 * Initializes and verifies the emulator's RDRAM backing store before IPL3 runs.
 *
 * This is a software memory-model check, not a substitute for IPL3's physical
 * RDRAM-controller timing/training sequence. IPL3 remains responsible for
 * programming the emulated hardware registers during normal boot.
 */
internal class N64RdramInitialization(private val memoryBus: N64MemoryBus) {
    data class Result(
        val capacityBytes: Int,
        val memoryModelReady: Boolean
    )

    fun initializeModel(): Result {
        // Probe a word in the boot-reserved low-memory area, restoring its
        // original contents so this diagnostic does not modify game state.
        val probeAddress = 0x0000_03FCL
        val original = memoryBus.readWord(probeAddress)
        val pattern = 0xA55A_3CC3.toInt()
        try {
            memoryBus.writeWord(probeAddress, pattern)
            check(memoryBus.readWord(probeAddress) == pattern) {
                "RDRAM read/write probe failed at 0x" + probeAddress.toString(16)
            }
        } finally {
            memoryBus.writeWord(probeAddress, original)
        }
        return Result(
            capacityBytes = R4300Cpu.DEFAULT_RDRAM_SIZE,
            memoryModelReady = true
        )
    }
}
