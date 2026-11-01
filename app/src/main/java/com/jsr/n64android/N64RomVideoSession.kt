package com.jsr.n64android

import java.util.concurrent.atomic.AtomicLong

/**
 * Continuous CPU-side ROM execution with bounded VI polling.
 *
 * The caller must prepare the CPU/bus (currently via N64HleBoot.prepare) before
 * constructing this session. It runs on a dedicated worker through the
 * N64ExecutionWorker and publishes only changed VI frames. RSP scheduling,
 * device timing and audio are deliberately not implied by this class.
 */
class N64RomVideoSession(
    private val bus: N64Bus,
    cpu: R4300Cpu,
    instructionLimit: Long = DEFAULT_INSTRUCTION_LIMIT,
    batchSize: Int = N64ExecutionSession.DEFAULT_BATCH_SIZE,
    private val framePollInterval: Long = DEFAULT_FRAME_POLL_INTERVAL,
    private val rspPollInterval: Long = DEFAULT_RSP_POLL_INTERVAL,
    private val rspInstructionQuantum: Int = DEFAULT_RSP_INSTRUCTION_QUANTUM,
    private val onFrame: (N64VideoFrame) -> Unit
) : N64ExecutionController {
    private val instructionCount = AtomicLong(0L)
    private val publishedFrames = AtomicLong(0L)
    private val rspInstructions = AtomicLong(0L)
    @Volatile private var previousPixels: IntArray? = null
    @Volatile private var rspCore: N64RspCore? = null
    @Volatile private var rspFailure: String? = null

    private val delegate: N64ExecutionSession

    init {
        require(framePollInterval > 0L) { "Frame polling interval must be positive." }
        require(rspPollInterval > 0L) { "RSP polling interval must be positive." }
        require(rspInstructionQuantum > 0) { "RSP instruction quantum must be positive." }
        delegate = N64ExecutionSession(
            instructionLimit = instructionLimit,
            batchSize = batchSize,
            afterInstruction = {
                val count = instructionCount.incrementAndGet()
                if (count % rspPollInterval == 0L) pumpRsp()
                if (count % framePollInterval == 0L) {
                    val frame = bus.captureViFrameFromRegisters()
                    if (frame != null && !frame.argbPixels.contentEquals(previousPixels)) {
                        previousPixels = frame.argbPixels.copyOf()
                        publishedFrames.incrementAndGet()
                        onFrame(frame)
                    }
                }
                false
            },
            stepInstruction = cpu::step
        )
    }

    override fun execute(): N64ExecutionSession.Snapshot = delegate.execute()

    override fun requestStop() = delegate.requestStop()

    override fun snapshot(): N64ExecutionSession.Snapshot = delegate.snapshot()

    fun publishedFrameCount(): Long = publishedFrames.get()

    fun executedInstructionCount(): Long = instructionCount.get()

    fun executedRspInstructionCount(): Long = rspInstructions.get()

    fun rspFailureMessage(): String? = rspFailure

    /**
     * Gives the RSP a bounded execution quantum whenever the CPU has released SP.
     * The core is recreated after HALT so it reloads the architectural SP_PC for
     * the next task. This is an approximate cooperative scheduler, not cycle timing.
     */
    private fun pumpRsp() {
        val status = bus.read32(N64Bus.SP_STATUS_REG)
        if ((status and N64Bus.SP_STATUS_HALT) != 0) {
            rspCore = null
            return
        }

        val core = rspCore?.takeUnless { it.halted } ?: N64RspCore(bus).also {
            rspCore = it
        }
        val before = core.executedInstructions
        try {
            core.run(rspInstructionQuantum)
            rspInstructions.addAndGet(core.executedInstructions - before)
            if (core.halted) rspCore = null
        } catch (error: Throwable) {
            rspInstructions.addAndGet(core.executedInstructions - before)
            rspFailure = "${error.javaClass.simpleName}: ${error.message ?: "instrução RSP não suportada"}"
            bus.write32(
                N64Bus.SP_STATUS_REG,
                N64Bus.SP_STATUS_CMD_SET_HALT
            )
            rspCore = null
        }
    }

    companion object {
        const val DEFAULT_INSTRUCTION_LIMIT = Long.MAX_VALUE
        const val DEFAULT_FRAME_POLL_INTERVAL = 100_000L
        const val DEFAULT_RSP_POLL_INTERVAL = 4_096L
        const val DEFAULT_RSP_INSTRUCTION_QUANTUM = 512
    }
}
