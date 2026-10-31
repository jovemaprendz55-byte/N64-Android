package com.jsr.n64android

/**
 * Bounded instruction trace storage for the HLE boot diagnostic.
 *
 * Keeps the final instruction window and a bounded set of PC visit counters.
 * It records observations only; it does not interpret CPU or hardware behavior.
 */
internal class N64HleInstructionTrace(
    private val recentLimit: Int = DEFAULT_RECENT_LIMIT,
    private val trackedPcLimit: Int = DEFAULT_TRACKED_PC_LIMIT
) {
    data class Snapshot(
        val recentLines: List<String>,
        val mostVisitedLines: List<String>,
        val distinctPcCount: Int
    )

    private val recent = ArrayDeque<String>()
    private val visits = LinkedHashMap<Long, Int>()

    init {
        require(recentLimit > 0) { "recentLimit must be positive" }
        require(trackedPcLimit > 0) { "trackedPcLimit must be positive" }
    }

    fun record(pc: Long, instruction: Int?, nextPc: Long) {
        if (recent.size >= recentLimit) recent.removeFirst()
        recent.addLast(
            "PC=0x${pc.toULong().toString(16).uppercase()} " +
                "INST=${instruction?.toUInt()?.toString(16)?.uppercase()?.padStart(8, '0') ?: "N/A"} " +
                "NEXT=0x${nextPc.toULong().toString(16).uppercase()}"
        )
        if (visits.containsKey(pc) || visits.size < trackedPcLimit) {
            visits[pc] = (visits[pc] ?: 0) + 1
        }
    }

    fun snapshot(mostVisitedLimit: Int = DEFAULT_MOST_VISITED_LIMIT): Snapshot {
        require(mostVisitedLimit >= 0) { "mostVisitedLimit cannot be negative" }
        val mostVisited = visits.entries
            .sortedByDescending { it.value }
            .take(mostVisitedLimit)
            .map { (pc, count) ->
                "PC=0x${pc.toULong().toString(16).uppercase()} visits=$count"
            }
        return Snapshot(
            recentLines = recent.toList(),
            mostVisitedLines = mostVisited,
            distinctPcCount = visits.size
        )
    }

    companion object {
        const val DEFAULT_RECENT_LIMIT = 256
        const val DEFAULT_TRACKED_PC_LIMIT = 4096
        const val DEFAULT_MOST_VISITED_LIMIT = 16
    }
}
