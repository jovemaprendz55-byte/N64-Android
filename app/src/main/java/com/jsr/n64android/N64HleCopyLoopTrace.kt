package com.jsr.n64android

/**
 * Bounded, UI-independent capture of the HLE byte-copy loop and its exit window.
 *
 * The collector stores diagnostic text only. It does not execute or interpret CPU
 * instructions and receives register snapshots lazily to avoid formatting work
 * for instructions that will not be retained.
 */
internal class N64HleCopyLoopTrace {
    data class BeforeInstruction(
        val pc: Long,
        val inCopyLoop: Boolean,
        val sampleIteration: Boolean,
        val registersBefore: String
    )

    data class Snapshot(
        val iterations: Long,
        val takenBranches: Long,
        val exitObserved: Boolean,
        val loopLines: List<String>,
        val sampleLines: List<String>,
        val postExitLines: List<String>
    )

    private var iterations = 0L
    private var takenBranches = 0L
    private var exitObserved = false
    private var postExitRemaining = 0
    private val loopLines = ArrayList<String>()
    private val sampleLines = ArrayList<String>()
    private val postExitLines = ArrayList<String>()

    fun beforeInstruction(pc: Long, registersBefore: () -> String): BeforeInstruction {
        val inLoop = pc in LOOP_FIRST_PC..LOOP_LAST_PC
        val sample = pc == LOOP_STORE_PC &&
            (iterations < INITIAL_SAMPLE_COUNT || (iterations + 1L) % SAMPLE_INTERVAL == 0L)
        val capturedRegisters = if (inLoop && (loopLines.size < LOOP_TRACE_LIMIT || sample)) {
            registersBefore()
        } else {
            ""
        }
        return BeforeInstruction(pc, inLoop, sample, capturedRegisters)
    }

    fun afterInstruction(
        before: BeforeInstruction,
        instruction: Int?,
        nextPc: Long,
        registersAfter: () -> String
    ) {
        if (before.inCopyLoop && loopLines.size < LOOP_TRACE_LIMIT) {
            loopLines.add(
                "PC=0x${hex(before.pc)} " +
                    "INST=${formatInstruction(instruction)} " +
                    "NEXT=0x${hex(nextPc)} " +
                    "BEFORE{${before.registersBefore}} AFTER{${registersAfter()}}"
            )
        }

        if (before.pc == LOOP_STORE_PC) {
            iterations++
            val branchTaken = nextPc == LOOP_START_PC
            if (branchTaken) takenBranches++ else exitObserved = true
            if (before.sampleIteration || !branchTaken) {
                sampleLines.add(
                    "iteration=$iterations branchTaken=$branchTaken " +
                        "NEXT=0x${hex(nextPc)} " +
                        "BEFORE{${before.registersBefore}} AFTER{${registersAfter()}}"
                )
            }
        }

        if (exitObserved && postExitRemaining == 0 && postExitLines.isEmpty()) {
            postExitRemaining = POST_EXIT_LIMIT
        }
        if (postExitRemaining > 0) {
            postExitLines.add(
                "PC=0x${hex(before.pc)} " +
                    "INST=${formatInstruction(instruction)} " +
                    "NEXT=0x${hex(nextPc)} REG{${registersAfter()}}"
            )
            postExitRemaining--
        }
    }

    fun snapshot(): Snapshot = Snapshot(
        iterations = iterations,
        takenBranches = takenBranches,
        exitObserved = exitObserved,
        loopLines = loopLines.toList(),
        sampleLines = sampleLines.toList(),
        postExitLines = postExitLines.toList()
    )

    private fun hex(value: Long): String = value.toULong().toString(16).uppercase()

    private fun formatInstruction(instruction: Int?): String =
        instruction?.toUInt()?.toString(16)?.uppercase()?.padStart(8, '0') ?: "N/A"

    companion object {
        const val LOOP_FIRST_PC = 0x70000570L
        const val LOOP_START_PC = 0x70000574L
        const val LOOP_STORE_PC = 0x70000588L
        const val LOOP_LAST_PC = 0x70000588L
        const val LOOP_TRACE_LIMIT = 32
        const val POST_EXIT_LIMIT = 64
        const val INITIAL_SAMPLE_COUNT = 8L
        const val SAMPLE_INTERVAL = 4096L
    }
}
