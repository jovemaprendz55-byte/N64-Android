package com.jsr.n64android

/**
 * Explicit HLE handoff prepared in guest memory.
 *
 * This is a data snapshot only: it does not instantiate or step the R4300,
 * execute IPL3, or start RSP/RDP/audio devices.
 */
class N64HlePreparedHandoff internal constructor(
    val guestMemory: N64HleGuestAddressSpace,
    val cpuEntryState: N64HleCpuEntryState
) {
    /** Creates mutable CPU state without inventing HI/LO or unspecified COP0 values. */
    fun createR4300CpuState(): N64HleR4300CpuState =
        N64HleR4300CpuState.fromEntryState(cpuEntryState)

    /** Creates a stepper explicitly connected to this handoff's guest RDRAM. */
    fun createR4300InstructionStepper(): N64HleR4300InstructionStepper =
        N64HleR4300InstructionStepper(memorySpace = guestMemory)
}

/**
 * In-memory, validated cartridge held by the clean application session.
 *
 * A caller may prepare an evidence-labelled HLE memory/register handoff.
 * Preparing it is not the same as executing the game.
 */
class N64HleCartridgeSession private constructor(
    val cartridge: N64HleCartridgeImage
) {
    val romSizeBytes: Int
        get() = cartridge.sizeBytes

    fun readRomBytes(offset: Int, length: Int): ByteArray =
        cartridge.readBytes(offset, length)

    /**
     * Applies only the caller-supplied profile to a fresh guest RDRAM space.
     * No defaults for CIC, stack, COP0, or game-specific registers are added.
     */
    fun prepareHleHandoff(
        profile: N64HleBootProfile,
        rdramSize: Int = N64HleGuestAddressSpace.DEFAULT_RDRAM_SIZE
    ): N64HlePreparedHandoff {
        val memory = N64HleGuestAddressSpace(rdramSize)
        val cpuState = N64HleBootInitializer().initialize(cartridge, memory, profile)
        return N64HlePreparedHandoff(memory, cpuState)
    }

    companion object {
        fun fromValidatedImage(cartridge: N64HleCartridgeImage): N64HleCartridgeSession =
            N64HleCartridgeSession(cartridge)
    }
}
