package com.jsr.n64android

/**
 * Immutable COP0 snapshot for an explicitly described HLE CPU handoff.
 *
 * Missing registers remain unknown; this class never fills in hardware reset
 * values or guesses Status, Cause, EPC, Config, Wired, or TLB state.
 */
class N64HleCop0EntryState(
    registerValues: Map<Int, Long>,
    val evidence: String
) {
    private val values = registerValues.toMap()

    init {
        require(evidence.isNotBlank()) { "COP0 state evidence is required" }
        require(values.keys.all { it in 0..31 }) {
            "COP0 register indices must be in 0..31"
        }
    }

    /** True only when the HLE profile supplied this register explicitly. */
    fun isKnown(registerIndex: Int): Boolean {
        require(registerIndex in 0..31) { "COP0 register index must be in 0..31" }
        return values.containsKey(registerIndex)
    }

    /** Returns null for an unspecified register; never returns an invented default. */
    fun valueOrNull(registerIndex: Int): Long? {
        require(registerIndex in 0..31) { "COP0 register index must be in 0..31" }
        return values[registerIndex]
    }

    /** Reads an explicitly supplied value or fails with a clear unknown-state error. */
    fun requireValue(registerIndex: Int): Long {
        require(registerIndex in 0..31) { "COP0 register index must be in 0..31" }
        return values[registerIndex]
            ?: throw IllegalStateException("COP0 register $registerIndex was not specified")
    }

    /** Defensive snapshot; callers cannot mutate the state held by this object. */
    fun copyRegisterValues(): Map<Int, Long> = values.toMap()

    companion object {
        fun unspecified(): N64HleCop0EntryState =
            N64HleCop0EntryState(
                emptyMap(),
                "COP0 values were not supplied by the HLE boot profile"
            )
    }
}
