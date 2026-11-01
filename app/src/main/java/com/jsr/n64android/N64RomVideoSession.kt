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
    private val onFrame: (N64VideoFrame) -> Unit
) : N64ExecutionController {
    private val instructionCount = AtomicLong(0L)
    private val publishedFrames = AtomicLong(0L)
    @Volatile private var previousPixels: IntArray? = null

    private val delegate: N64ExecutionSession

    init {
        require(framePollInterval > 0L) { "Frame polling interval must be positive." }
        delegate = N64ExecutionSession(
            instructionLimit = instructionLimit,
            batchSize = batchSize,
            afterInstruction = {
                val count = instructionCount.incrementAndGet()
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

    companion object {
        const val DEFAULT_INSTRUCTION_LIMIT = Long.MAX_VALUE
        const val DEFAULT_FRAME_POLL_INTERVAL = 100_000L
    }
}
