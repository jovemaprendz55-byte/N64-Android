package com.jsr.n64android

/**
 * Explicit, evidence-labelled state requested at an HLE handoff.
 *
 * The profile deliberately contains no built-in CIC, GoldenEye or libultra
 * constants. A caller must provide values established for its exact target.
 */
class N64HleBootProfile(
    val entryPc: Long,
    registerValues: Map<Int, Long>,
    romCopies: List<RomCopy>,
    memoryWrites: List<MemoryWrite>,
    val evidence: String,
    cop0RegisterValues: Map<Int, Long> = emptyMap(),
    val cop0Evidence: String? = null
) {
    data class RomCopy(
        val romOffset: Int,
        val guestDestination: Long,
        val length: Int
    )

    data class MemoryWrite(
        val guestAddress: Long,
        val widthBytes: Int,
        val value: Long
    )

    val registerValues: Map<Int, Long> = registerValues.toMap()
    val romCopies: List<RomCopy> = romCopies.toList()
    val memoryWrites: List<MemoryWrite> = memoryWrites.toList()
    val cop0RegisterValues: Map<Int, Long> = cop0RegisterValues.toMap()

    init {
        require(evidence.isNotBlank()) { "Boot profile evidence is required" }
        require((entryPc and 3L) == 0L) { "Entry PC must be word-aligned" }
        val normalizedPc = entryPc and 0xFFFF_FFFFL
        require((normalizedPc ushr 29) == 4L) { "Entry PC must be in KSEG0" }
        require(registerValues.keys.all { it in 1..31 }) {
            "GPR indices must be in 1..31; GPR0 is immutable zero"
        }
        require(cop0RegisterValues.keys.all { it in 0..31 }) {
            "COP0 register indices must be in 0..31"
        }
        require(cop0RegisterValues.isEmpty() || !cop0Evidence.isNullOrBlank()) {
            "Explicit COP0 values require their own evidence label"
        }
        require(romCopies.all { it.romOffset >= 0 && it.length > 0 }) {
            "ROM copy ranges must have non-negative offsets and positive lengths"
        }
        require(memoryWrites.all { it.widthBytes in setOf(1, 2, 4, 8) }) {
            "Memory initialization width must be 1, 2, 4 or 8 bytes"
        }
        memoryWrites.forEach {
            val max = when (it.widthBytes) {
                1 -> 0xFFL
                2 -> 0xFFFFL
                4 -> 0xFFFF_FFFFL
                else -> Long.MAX_VALUE
            }
            require(it.value >= 0 && (it.widthBytes == 8 || it.value <= max)) {
                "Memory initialization value does not fit its width"
            }
        }
    }
}

/** Architectural integer-register/PC snapshot produced by an HLE profile. */
class N64HleCpuEntryState(
    val programCounter: Long,
    registers: LongArray,
    val cop0State: N64HleCop0EntryState = N64HleCop0EntryState.unspecified(),
    knownGprIndices: Set<Int> = emptySet()
) {
    private val values = registers.copyOf()
    private val knownGprs = knownGprIndices.toSet() + 0

    init {
        require(values.size == 32) { "R4300 state must contain 32 GPRs" }
        require(values[0] == 0L) { "R4300 GPR0 must remain zero" }
        require(knownGprs.all { it in 0..31 }) {
            "Known GPR indices must be in 0..31"
        }
    }

    fun isGprKnown(index: Int): Boolean {
        require(index in 0..31) { "GPR index must be in 0..31" }
        return index in knownGprs
    }

    fun copyKnownGprs(): Map<Int, Long> =
        knownGprs.associateWith { values[it] }

    fun copyRegisters(): LongArray = values.copyOf()
}

/**
 * Applies a fully explicit HLE profile to guest RDRAM and returns its CPU
 * integer-register/PC state. It does not emulate IPL3, configure COP0, or
 * claim that the supplied profile is sufficient to boot a particular game.
 */
class N64HleBootInitializer {
    fun initialize(
        cartridge: N64HleCartridgeImage,
        memory: N64HleGuestAddressSpace,
        profile: N64HleBootProfile
    ): N64HleCpuEntryState {
        val normalizedPc = profile.entryPc and 0xFFFF_FFFFL
        require((normalizedPc ushr 29) == 4L) { "Entry PC must be in KSEG0" }
        memory.read32(profile.entryPc)

        // Preflight every source and destination before changing guest memory.
        profile.romCopies.forEach { copy ->
            cartridge.readBytes(copy.romOffset, copy.length)
            memory.readBytes(copy.guestDestination, copy.length)
        }
        profile.memoryWrites.forEach { write ->
            when (write.widthBytes) {
                1 -> memory.read8(write.guestAddress)
                2 -> memory.read16(write.guestAddress)
                4 -> memory.read32(write.guestAddress)
                8 -> memory.read64(write.guestAddress)
            }
        }

        profile.romCopies.forEach { copy ->
            memory.writeBytes(
                copy.guestDestination,
                cartridge.readBytes(copy.romOffset, copy.length)
            )
        }
        profile.memoryWrites.forEach { write ->
            when (write.widthBytes) {
                1 -> memory.write8(write.guestAddress, write.value.toInt())
                2 -> memory.write16(write.guestAddress, write.value.toInt())
                4 -> memory.write32(write.guestAddress, write.value)
                8 -> memory.write64(write.guestAddress, write.value)
            }
        }

        val registers = LongArray(32)
        profile.registerValues.forEach { (index, value) -> registers[index] = value }
        registers[0] = 0L
        val cop0State = if (profile.cop0RegisterValues.isEmpty()) {
            N64HleCop0EntryState.unspecified()
        } else {
            N64HleCop0EntryState(
                profile.cop0RegisterValues,
                requireNotNull(profile.cop0Evidence)
            )
        }
        return N64HleCpuEntryState(
            profile.entryPc,
            registers,
            cop0State,
            profile.registerValues.keys
        )
    }
}
