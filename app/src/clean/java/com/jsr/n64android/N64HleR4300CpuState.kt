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

    var programCounter: Long = programCounter
        private set

    private var hiValue: Long? = initialHi
    private var loValue: Long? = initialLo

    init {
        requireAlignedPc(programCounter)
        require(initialGprValues.keys.all { it in 1..31 }) {
            "Initial GPR indices must be in 1..31; GPR0 is constant zero"
        }
        initialGprValues.forEach { (index, value) ->
            gprValues[index] = value
            knownGpr[index] = true
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
        requireAlignedPc(value)
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

    private fun requireGprIndex(index: Int) {
        require(index in 0..31) { "GPR index must be in 0..31" }
    }

    private fun requireAlignedPc(value: Long) {
        require((value and 3L) == 0L) { "R4300 instruction PC must be word-aligned" }
    }
}
