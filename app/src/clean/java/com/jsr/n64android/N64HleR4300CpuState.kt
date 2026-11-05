package com.jsr.n64android

/**
 * Mutable architectural integer state for the clean R4300 execution core.
 *
 * Only explicitly supplied GPR values are known. GPR0 is architecturally
 * constant zero. HI and LO remain unknown until supplied or written by an
 * executed instruction. This class does not invent reset-state values.
 */
class N64HleR4300CpuState(
    programCounter: Long,
    initialGprValues: Map<Int, Long> = emptyMap(),
    initialHi: Long? = null,
    initialLo: Long? = null,
    val cop0: N64HleCop0EntryState = N64HleCop0EntryState.unspecified()
) {
    private val gprValues = LongArray(32)
    private val knownGpr = BooleanArray(32)
    private val cop0Values = LongArray(32)
    private val knownCop0 = BooleanArray(32)

    var programCounter: Long = programCounter
        private set

    private var hiValue: Long? = initialHi
    private var loValue: Long? = initialLo

    init {
        require(initialGprValues.keys.all { it in 1..31 }) {
            "Initial GPR indices must be in 1..31; GPR0 is constant zero"
        }
        initialGprValues.forEach { (index, value) ->
            gprValues[index] = value
            knownGpr[index] = true
        }
        cop0.copyRegisterValues().forEach { (index, value) ->
            cop0Values[index] = value.toInt().toLong()
            knownCop0[index] = true
        }
        knownGpr[0] = true
    }

    fun isGprKnown(index: Int): Boolean {
        requireGprIndex(index)
        return knownGpr[index]
    }

    fun readGpr(index: Int): Long {
        requireGprIndex(index)
        if (index == 0) return 0L
        check(knownGpr[index]) { "GPR $index has no explicitly established value" }
        return gprValues[index]
    }

    /** Writes GPR1..GPR31. Writes to GPR0 are discarded, as on the R4300. */
    fun writeGpr(index: Int, value: Long) {
        requireGprIndex(index)
        if (index == 0) return
        gprValues[index] = value
        knownGpr[index] = true
    }

    /** COP0 registers are modeled as 32-bit values; unknown entry values stay unknown. */
    fun isCop0Known(index: Int): Boolean {
        requireCop0Index(index)
        return knownCop0[index]
    }

    fun readCop0(index: Int): Long {
        requireCop0Index(index)
        check(knownCop0[index]) { "COP0 register $index has no established value" }
        return cop0Values[index].toInt().toLong()
    }

    /** MTC0 writes the low 32 bits; device-specific register side effects are not modeled. */
    fun writeCop0(index: Int, value: Long) {
        requireCop0Index(index)
        cop0Values[index] = value.toInt().toLong()
        knownCop0[index] = true
    }

    fun copyKnownCop0(): Map<Int, Long> {
        val result = LinkedHashMap<Int, Long>()
        for (index in 0..31) if (knownCop0[index]) result[index] = cop0Values[index]
        return result.toMap()
    }

    fun isHiKnown(): Boolean = hiValue != null

    fun isLoKnown(): Boolean = loValue != null

    fun readHi(): Long =
        hiValue ?: throw IllegalStateException("HI has no established value")

    fun readLo(): Long =
        loValue ?: throw IllegalStateException("LO has no established value")

    fun writeHi(value: Long) {
        hiValue = value
    }

    fun writeLo(value: Long) {
        loValue = value
    }

    fun writeHiLo(hi: Long, lo: Long) {
        hiValue = hi
        loValue = lo
    }

    fun setProgramCounter(value: Long) {
        programCounter = value
    }

    /** Defensive copy containing only known non-zero GPR indices. */
    fun copyKnownGprs(): Map<Int, Long> {
        val result = LinkedHashMap<Int, Long>()
        for (index in 1..31) {
            if (knownGpr[index]) result[index] = gprValues[index]
        }
        return result.toMap()
    }

    private fun requireCop0Index(index: Int) {
        require(index in 0..31) { "COP0 register index must be in 0..31" }
    }

    private fun requireGprIndex(index: Int) {
        require(index in 0..31) { "GPR index must be in 0..31" }
    }

    companion object {
        /**
         * Builds mutable execution state from an explicit HLE entry snapshot.
         * HI/LO remain unknown because the entry profile does not describe them.
         */
        fun fromEntryState(entryState: N64HleCpuEntryState): N64HleR4300CpuState {
            val knownGprs = entryState.copyKnownGprs().filterKeys { it != 0 }
            return N64HleR4300CpuState(
                programCounter = entryState.programCounter,
                initialGprValues = knownGprs,
                cop0 = entryState.cop0State
            )
        }
    }
}
