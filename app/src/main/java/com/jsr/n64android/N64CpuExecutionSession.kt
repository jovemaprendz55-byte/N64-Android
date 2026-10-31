package com.jsr.n64android

/**
 * Binds the generic bounded runner to the real R4300 CPU.
 *
 * The caller owns the CPU/bus lifetime and must invoke [execute] on a worker thread.
 * No Android APIs or shared-state synchronization are introduced here.
 */
class N64CpuExecutionSession(
    cpu: R4300Cpu,
    instructionLimit: Long,
    batchSize: Int = N64ExecutionSession.DEFAULT_BATCH_SIZE
) {
    private val runner = N64ExecutionSession(
        instructionLimit = instructionLimit,
        batchSize = batchSize,
        stepInstruction = cpu::step
    )

    fun execute(): N64ExecutionSession.Snapshot = runner.execute()

    fun requestStop() = runner.requestStop()

    fun snapshot(): N64ExecutionSession.Snapshot = runner.snapshot()
}
