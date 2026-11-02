package com.jsr.n64android

/**
 * Small rolling trace of successfully fetched CPU instructions.
 * It is intentionally independent of emulation state and never changes execution.
 */
class N64InstructionTrace(private val capacity: Int = DEFAULT_CAPACITY) {
    data class Entry(val pc: Long, val instruction: Int)
    data class Loop(val period: Int, val repetitions: Int, val pattern: List<Entry>)

    private val entries = ArrayDeque<Entry>()

    init {
        require(capacity >= 4) { "Trace capacity must be at least four instructions." }
    }

    @Synchronized
    fun record(pc: Long?, instruction: Int?) {
        if (pc == null || instruction == null) return
        if (entries.size == capacity) entries.removeFirst()
        entries.addLast(Entry(pc, instruction))
    }

    @Synchronized
    fun snapshot(): List<Entry> = entries.toList()

    /**
     * Finds an exact repeated PC+opcode sequence, including branch delay-slot
     * instructions. Three consecutive repetitions are required to avoid
     * flagging ordinary short code sequences as loops.
     */
    @Synchronized
    fun detectLoop(maxPeriod: Int = MAX_PERIOD, repetitions: Int = MIN_REPETITIONS): Loop? {
        require(maxPeriod > 0)
        require(repetitions >= MIN_REPETITIONS)
        val trace = entries.toList()
        for (period in 1..minOf(maxPeriod, capacity / repetitions)) {
            val required = period * repetitions
            if (trace.size < required) continue
            val tail = trace.takeLast(required)
            val pattern = tail.takeLast(period)
            val repeatsExactly = (0 until repetitions).all { block ->
                tail.subList(block * period, (block + 1) * period) == pattern
            }
            if (repeatsExactly) return Loop(period, repetitions, pattern)
        }
        return null
    }

    companion object {
        const val DEFAULT_CAPACITY = 128
        const val MAX_PERIOD = 4
        const val MIN_REPETITIONS = 3
    }
}
