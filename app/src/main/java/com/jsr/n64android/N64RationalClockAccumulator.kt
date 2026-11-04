package com.jsr.n64android

/**
 * Deterministic integer clock-ratio converter.
 *
 * Converts input units to output clock ticks using numerator / denominator,
 * retaining the fractional remainder between calls so batching cannot lose time.
 * This utility does not define which hardware clock the input units represent.
 */
class N64RationalClockAccumulator(
    numerator: Int,
    denominator: Int
) {
    private val numerator: Long
    private val denominator: Long
    private var remainder: Long = 0L

    init {
        require(numerator > 0) { "Clock numerator must be positive." }
        require(denominator > 0) { "Clock denominator must be positive." }
        this.numerator = numerator.toLong()
        this.denominator = denominator.toLong()
    }

    /** Fractional numerator carried into the next advance; always less than denominator. */
    fun remainder(): Long = remainder

    /**
     * Advances by non-negative input units and returns whole output ticks.
     * Arithmetic overflow is rejected rather than silently wrapping.
     */
    fun advance(inputUnits: Long): Long {
        require(inputUnits >= 0L) { "Clock advance cannot be negative." }

        val wholeInput = inputUnits / denominator
        val partialInput = inputUnits % denominator
        val wholeOutput = Math.multiplyExact(wholeInput, numerator)
        val partialNumerator = Math.addExact(
            Math.multiplyExact(partialInput, numerator),
            remainder
        )
        val partialOutput = partialNumerator / denominator
        val nextRemainder = partialNumerator % denominator
        val output = Math.addExact(wholeOutput, partialOutput)
        remainder = nextRemainder
        return output
    }
}
